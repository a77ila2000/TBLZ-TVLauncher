package com.tblz.tvlauncher.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.ActivityLivePlayerBinding
import com.tblz.tvlauncher.engine.ConfigStore
import com.tblz.tvlauncher.engine.LiveTvManager
import com.tblz.tvlauncher.engine.NetworkValidator
import com.tblz.tvlauncher.model.ChannelData
import com.tblz.tvlauncher.model.PlaybackInfo
import com.tblz.tvlauncher.model.PlaybackResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class LivePlayerActivity : AppCompatActivity(), Player.Listener {

    companion object {
        private const val TAG = "LivePlayerActivity"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        /**
         * Which group the channel was picked from. Zapping stays inside it, because
         * entering from a category means the user wants that category's channels --
         * not a walk through all 361. Empty falls back to the full list.
         */
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Stream resolve budget. Generous on purpose: after a long standby Wi-Fi has to
         * re-associate and DHCP has to finish before any of this can work, and giving up
         * early is what left the TV sitting on "스트림 URL을 발급받지 못했습니다".
         */
        private const val STREAM_MAX_RETRIES = 40
        private const val STREAM_RETRY_MS = 2000L
        private const val STREAM_RELOAD_EVERY = 5

        /** Channel-list fetch budget at cold start, same reasoning. */
        private const val CHANNEL_MAX_RETRIES = 30
        private const val CHANNEL_RETRY_MS = 2000L
    }

    private lateinit var binding: ActivityLivePlayerBinding
    private var player: ExoPlayer? = null

    private var channelList: List<ChannelData> = emptyList()
    private var currentCategoryId: String = ""
    private var currentIndex: Int = 0
    private var currentChannel: ChannelData? = null

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOSD() }

    private var loadJob: Job? = null
    private var isLongPressHandled = false
    private var isColdBootStartup = true
    private var lastVideoLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLivePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LiveTvManager.init(this)

        val targetChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ConfigStore.lastChannelId
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: ConfigStore.lastCategoryId

        initPlayer()

        lifecycleScope.launch {
            showLoading("인터넷 연결 확인 중...", "네트워크 연결을 확인하고 있습니다...")

            if (!NetworkValidator.isNetworkValidated(this@LivePlayerActivity)) {
                NetworkValidator.waitForValidatedNetwork(this@LivePlayerActivity, timeoutMs = 15000)
            }

            var retryCount = 0
            while (LiveTvManager.getAllChannels().isEmpty() && retryCount < CHANNEL_MAX_RETRIES) {
                showLoading("채널 목록 불러오는 중...", "실시간 방송 정보를 수신하고 있습니다... (${retryCount + 1}/$CHANNEL_MAX_RETRIES)")
                LiveTvManager.loadAllChannels(forceRefresh = true)
                if (LiveTvManager.getAllChannels().isEmpty()) {
                    retryCount++
                    // Keep waiting on the link between rounds -- at this point after a
                    // wake it usually is not up yet.
                    NetworkValidator.waitForValidatedNetwork(this@LivePlayerActivity, timeoutMs = CHANNEL_RETRY_MS)
                    kotlinx.coroutines.delay(CHANNEL_RETRY_MS)
                }
            }

            isColdBootStartup = false

            initChannels(targetChannelId, categoryId)
            if (channelList.isNotEmpty()) {
                playChannelAtIndex(currentIndex)
            } else {
                hideLoading()
                showError("실시간 채널 목록을 불러오지 못했습니다.\n네트워크 연결 상태를 확인하세요.")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ConfigStore.lastChannelId
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: ConfigStore.lastCategoryId

        lifecycleScope.launch {
            if (LiveTvManager.getAllChannels().isEmpty()) {
                LiveTvManager.loadAllChannels()
            }
            initChannels(targetChannelId, categoryId)
            if (channelList.isNotEmpty()) {
                playChannelAtIndex(currentIndex)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            initPlayer()
        }
        if (player?.isPlaying != true && channelList.isNotEmpty() && loadJob?.isActive != true) {
            playChannelAtIndex(currentIndex)
        }
    }

    private fun showLoading(title: String, subtitle: String = "") {
        binding.loadingContainer.isVisible = true
        binding.tvLoadingTitle.text = title
        binding.tvLoadingSub.text = subtitle
        binding.tvError.isVisible = false
    }

    private fun hideLoading() {
        binding.loadingContainer.isVisible = false
    }

    private fun initChannels(targetChannelId: String, categoryId: String) {
        currentCategoryId = categoryId
        val scoped = if (categoryId.isNotEmpty()) LiveTvManager.getChannelsByCategory(categoryId) else emptyList()
        // An emptied category (every favourite un-starred, say) would leave nothing to
        // zap through, so fall back to everything rather than a dead player.
        channelList = if (scoped.isNotEmpty()) scoped else LiveTvManager.getAllChannels()
        Log.i(TAG, "initChannels category=[$categoryId] scoped=${scoped.size} zapList=${channelList.size}")

        if (channelList.isNotEmpty()) {
            val foundIndex = channelList.indexOfFirst { it.id == targetChannelId }
            currentIndex = if (foundIndex >= 0) foundIndex else 0
            currentChannel = channelList[currentIndex]
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            addListener(this@LivePlayerActivity)
            playWhenReady = true
        }
        binding.playerView.player = player
    }

    private fun playChannelAtIndex(index: Int) {
        if (channelList.isEmpty()) return
        currentIndex = (index + channelList.size) % channelList.size
        val channel = channelList[currentIndex]
        currentChannel = channel

        ConfigStore.lastChannelId = channel.id
        ConfigStore.lastSiteType = channel.siteType
        ConfigStore.lastCategoryId = currentCategoryId

        lastVideoLabel = ""
        updateOSD(channel)
        showOSD(4000)

        // Instant channel zapping: do not block screen with loading spinner during channel switching
        hideLoading()
        binding.tvError.isVisible = false

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            Log.d(TAG, "Resolving playback for channel: ${channel.title} (ID: ${channel.id}, Site: ${channel.siteType})")

            // Refresh EPG asynchronously
            LiveTvManager.refreshEPG(channel)
            updateOSD(channel)

            var playback: PlaybackInfo? = null
            var retryCount = 0

            while (retryCount < STREAM_MAX_RETRIES) {
                // Coming back from standby the link is still re-associating, and a request
                // fired into that just burns a retry. Wait for a real address first.
                if (!NetworkValidator.isNetworkValidated(this@LivePlayerActivity)) {
                    showLoading("인터넷 연결 확인 중...", "네트워크가 복구되기를 기다리고 있습니다...")
                    NetworkValidator.waitForValidatedNetwork(this@LivePlayerActivity, timeoutMs = 10000)
                }

                val result = LiveTvManager.resolvePlayback(channel)
                when (result) {
                    is PlaybackResult.Success -> {
                        playback = result.info
                        break
                    }
                    is PlaybackResult.AuthRequired -> {
                        // 미로그인/이용권 필요/토큰 만료 시 무의미한 40회 반복 없이 즉시 사용자에게 안내
                        hideLoading()
                        showError(result.message)
                        return@launch
                    }
                    is PlaybackResult.Error -> {
                        if (!result.retryable) {
                            hideLoading()
                            showError(result.message)
                            return@launch
                        }

                        retryCount++
                        if (retryCount >= STREAM_MAX_RETRIES) {
                            hideLoading()
                            showError("${result.message}\n네트워크 연결 상태 및 설정을 확인하세요.")
                            return@launch
                        }

                        if (retryCount % STREAM_RELOAD_EVERY == 0) {
                            showLoading("채널 정보 갱신 중...", "실시간 방송 정보를 다시 받고 있습니다...")
                            LiveTvManager.loadAllChannels(forceRefresh = true)
                        }

                        showLoading("방송 스트림 연결 중...", "${channel.title} 방송을 불러오고 있습니다... ($retryCount/$STREAM_MAX_RETRIES)")
                        kotlinx.coroutines.delay(STREAM_RETRY_MS)
                    }
                }
            }

            if (playback != null && playback.url.isNotEmpty()) {
                Log.d(TAG, "Starting resolved playback")
                hideLoading()
                startPlayback(playback)
            } else {
                hideLoading()
                showError("스트림 URL을 발급받지 못했습니다 (${channel.title})\n네트워크 연결 상태 및 설정을 확인하세요.")
            }
        }
    }

    private fun startPlayback(playback: PlaybackInfo) {
        try {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)

            if (playback.cookie.isNotEmpty()) {
                val cookieVal = if (playback.cookie.contains("=")) playback.cookie else "_tving_token=${playback.cookie}"
                httpDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookieVal))
            }

            val uri = Uri.parse(playback.url)
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(uri)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.0f)
                        .setMinPlaybackSpeed(1.0f)
                        .build()
                )

            if (playback.drmServerUrl.isNotEmpty()) {
                val drmConfig = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(playback.drmServerUrl)
                    .apply {
                        if (playback.drmHeaderKey.isNotEmpty()) {
                            setLicenseRequestHeaders(mapOf(playback.drmHeaderKey to playback.drmHeaderValue))
                        }
                    }
                    .build()
                mediaItemBuilder.setDrmConfiguration(drmConfig)
            }

            val mediaItem = mediaItemBuilder.build()
            val mediaSource: MediaSource = if (playback.url.contains(".m3u8")) {
                HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            } else if (playback.url.contains(".mpd") || playback.drmServerUrl.isNotEmpty()) {
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
            }

            player?.let {
                it.stop()
                it.clearMediaItems()
                it.setMediaSource(mediaSource)
                it.prepare()
                it.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback exception: ${e.message}", e)
            showError("재생 시작 중 오류 발생: ${e.message}")
        }
    }

    private fun updateOSD(channel: ChannelData) {
        binding.tvChannelTitle.text = channel.title

        if (channel.siteType == ChannelData.SITE_WAVVE) {
            binding.tvProviderBadge.text = "WAVVE"
            binding.tvProviderBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_wavve)
        } else {
            binding.tvProviderBadge.text = "TVING"
            binding.tvProviderBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_tving)
        }

        val isFav = ConfigStore.isFavorite(channel.id)
        binding.ivFavorite.setImageResource(if (isFav) R.drawable.ic_star_gold else R.drawable.ic_star_empty)

        // Filled in for real by onVideoSizeChanged once the stream starts decoding.
        binding.tvResolution.text = lastVideoLabel.ifEmpty { "…" }

        val timeSdf = SimpleDateFormat("HH:mm", Locale.KOREA)
        binding.tvCurrentClock.text = timeSdf.format(Date())

        val currentEpg = channel.getCurrentEPG()
        if (currentEpg != null) {
            binding.tvProgramName.text = currentEpg.programName
            binding.tvProgramTime.text = currentEpg.getTimeFormatted()
            binding.programProgressBar.progress = currentEpg.getProgressPercent()
            binding.programProgressBar.isVisible = true
            binding.tvProgramTime.isVisible = true
        } else {
            binding.tvProgramName.text = if (channel.programName.isNotEmpty()) channel.programName else channel.title
            binding.programProgressBar.isVisible = false
            binding.tvProgramTime.isVisible = false
        }
    }

    private fun showOSD(durationMs: Long = 4000) {
        currentChannel?.let { updateOSD(it) }
        binding.osdPanel.isVisible = true
        osdHandler.removeCallbacks(hideOsdRunnable)
        if (durationMs > 0) {
            osdHandler.postDelayed(hideOsdRunnable, durationMs)
        }
    }

    private fun hideOSD() {
        binding.osdPanel.isVisible = false
    }

    private fun showError(msg: String) {
        player?.stop()
        player?.clearMediaItems()
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                playChannelAtIndex(currentIndex - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                playChannelAtIndex(currentIndex + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event?.startTracking()
                if (event?.repeatCount == 0) {
                    isLongPressHandled = false
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.osdPanel.isVisible) {
                    hideOSD()
                    return true
                }
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            isLongPressHandled = true
            currentChannel?.let { channel ->
                val isFav = LiveTvManager.toggleFavorite(channel)
                val msg = if (isFav) getString(R.string.fav_added) else getString(R.string.fav_removed)
                Toast.makeText(this, "${channel.title} $msg", Toast.LENGTH_SHORT).show()
                showOSD(3000)
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!isLongPressHandled) {
                if (binding.osdPanel.isVisible) {
                    hideOSD()
                } else {
                    showOSD(4000)
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (isColdBootStartup) {
                    showLoading("Connecting to Network...", "Buffering stream...")
                }
            }
            Player.STATE_READY -> {
                hideLoading()
                isColdBootStartup = false
                binding.tvError.isVisible = false
            }
            Player.STATE_ENDED -> hideLoading()
            Player.STATE_IDLE -> {}
        }
    }

    /**
     * Ground truth for picture quality. The engines label every channel "FHD" when the
     * list is fetched, which says nothing about what the account is entitled to or which
     * ABR rung is actually playing -- this reports what the decoder is really getting.
     */
    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.height <= 0) return
        val tier = when {
            videoSize.height >= 1080 -> "FHD"
            videoSize.height >= 720 -> "HD"
            videoSize.height >= 480 -> "SD"
            else -> "LD"
        }
        lastVideoLabel = "$tier ${videoSize.width}x${videoSize.height}"
        binding.tvResolution.text = lastVideoLabel
        Log.i(TAG, "video size ${videoSize.width}x${videoSize.height} -> $tier (${currentChannel?.title})")
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ExoPlayer error: ${error.message}", error)
        hideLoading()
        showError("재생 오류가 발생했습니다: ${error.errorCodeName}\n${error.message}")
    }

    override fun onStop() {
        super.onStop()
        loadJob?.cancel()
        player?.stop()
        hideLoading()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        osdHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
