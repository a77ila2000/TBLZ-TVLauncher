package com.tblz.tvlauncher

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tblz.tvlauncher.databinding.ActivityMainBinding
import com.tblz.tvlauncher.engine.ConfigStore
import com.tblz.tvlauncher.engine.LiveTvManager
import com.tblz.tvlauncher.engine.NetworkValidator
import com.tblz.tvlauncher.player.LivePlayerActivity
import com.tblz.tvlauncher.player.ChannelNavigation
import com.tblz.tvlauncher.ui.AllAppsDialog
import com.tblz.tvlauncher.ui.AppItem
import com.tblz.tvlauncher.ui.AppLauncherAdapter
import com.tblz.tvlauncher.ui.AppSelectDialog
import com.tblz.tvlauncher.ui.ChannelCardAdapter
import com.tblz.tvlauncher.ui.ChannelCategoryDialog
import com.tblz.tvlauncher.ui.SettingsDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_BOOT_LAUNCH = "extra_boot_launch"

        const val TAB_FAVORITES = 0
        const val TAB_TVING = 1
        const val TAB_WAVVE = 2
        const val TAB_ALL = 3

        /** Focus retry budget while the channel row is still being populated. */
        private const val FOCUS_RETRY_MS = 150L
        private const val FOCUS_MAX_ATTEMPTS = 40

        /**
         * Channel fetch keeps retrying this long before it admits failure. Waking this box
         * tears Wi-Fi down and re-associates from scratch, so a fetch started right after
         * the screen comes on has nothing to talk to for several seconds.
         */
        private const val LOAD_RETRY_MS = 2000L
        private const val LOAD_MAX_ATTEMPTS = 45

        /** Re-fetch the channel list on resume if the cache is older than this. */
        private const val LIST_STALE_MS = 60_000L

        /**
         * While sitting on the home, re-fetch the channel list this often so the "지금 방송중"
         * program text tracks program changes without the user leaving. The rebind is
         * focus-preserving (adapter payload), so it does not disturb navigation.
         */
        private const val PROGRAM_REFRESH_MS = 120_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var channelAdapter: ChannelCardAdapter
    private lateinit var appAdapter: AppLauncherAdapter

    private var currentTab = TAB_FAVORITES

    /**
     * True from launch until a fetch actually finishes, so an empty channel row reads
     * "불러오는 중" instead of "채널을 불러올 수 없습니다" while the network is still coming up.
     */
    private var isLoadingChannels = true
    private var loadJob: Job? = null

    /** Launcher dialogs (모아보기 / 설정 / 앱 목록) currently on screen, so HOME can close them. */
    private val openDialogs = mutableListOf<Dialog>()
    private val focusHandler = Handler(Looper.getMainLooper())

    private val programRefreshHandler = Handler(Looper.getMainLooper())
    private val programRefreshRunnable = object : Runnable {
        override fun run() {
            silentProgramRefresh()
            programRefreshHandler.postDelayed(this, PROGRAM_REFRESH_MS)
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LiveTvManager.init(this)

        setupUI()
        startClock()

        val isBootLaunch = intent.getBooleanExtra(EXTRA_BOOT_LAUNCH, false)
        handleStartupFlow(isBootLaunch)
    }

    private fun setupUI() {
        // Setup Channel Recycler
        channelAdapter = ChannelCardAdapter(
            onChannelClick = { channel ->
                val intent = Intent(this, LivePlayerActivity::class.java).apply {
                    putExtra(LivePlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                    putExtra(LivePlayerActivity.EXTRA_CATEGORY_ID, ChannelNavigation.FAVORITES)
                }
                startActivity(intent)
            },
            onChannelLongClick = { channel ->
                val isFav = LiveTvManager.toggleFavorite(channel)
                val msg = if (isFav) getString(R.string.fav_added) else getString(R.string.fav_removed)
                Toast.makeText(this, "${channel.title} $msg", Toast.LENGTH_SHORT).show()
                refreshChannelsView()
            }
        )

        binding.rvLiveChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = channelAdapter
        }

        // Setup App Recycler
        appAdapter = AppLauncherAdapter(
            onAppClick = { appItem ->
                handleAppClick(appItem)
            },
            onAppOrderChanged = { ConfigStore.reorderUserApps(it) },
            onAppRemoved = { appItem ->
                ConfigStore.removeUserApp(appItem.id)
                Toast.makeText(this, "${appItem.title} 바로가기가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onEditModeChanged = { binding.tvAppsEditHint.isVisible = it }
        )

        binding.rvApps.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = appAdapter
        }

        // Setup 4 Filter Tabs
        binding.tabFavorites.setOnClickListener { selectTab(TAB_FAVORITES) }
        binding.tabTving.setOnClickListener { selectTab(TAB_TVING) }
        binding.tabWavve.setOnClickListener { selectTab(TAB_WAVVE) }
        binding.tabAll.setOnClickListener { selectTab(TAB_ALL) }

        // Setup [ ▦ 모아보기 ] Button
        binding.btnBrowseAll.setOnClickListener {
            ChannelCategoryDialog(this) {
                refreshChannelsView()
            }.tracked().show()
        }

        // The prominent gear opens this launcher's account/settings screen.
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        selectTab(TAB_FAVORITES)
        loadAppShortcuts()
    }

    private fun launchSystemSettings() {
        try {
            val systemSettingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(systemSettingsIntent)
        } catch (_: Exception) {
            try {
                val tvSettingsIntent = Intent("android.settings.SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(tvSettingsIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch system settings: ${e.message}")
                Toast.makeText(this, "시스템 설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleStartupFlow(isBootLaunch: Boolean) {
        lifecycleScope.launch {
            if (!NetworkValidator.isNetworkValidated(this@MainActivity)) {
                binding.networkLoadingOverlay.isVisible = true
                binding.tvSplashStatus.text = getString(R.string.connecting_network)
                NetworkValidator.waitForValidatedNetwork(this@MainActivity, timeoutMs = 12000)
                binding.networkLoadingOverlay.isVisible = false
            }

            if (isBootLaunch && ConfigStore.bootMode == ConfigStore.BOOT_MODE_DIRECT_TV && ConfigStore.lastChannelId.isNotEmpty()) {
                // Direct TV Mode: launch player immediately
                val intent = Intent(this@MainActivity, LivePlayerActivity::class.java).apply {
                    putExtra(LivePlayerActivity.EXTRA_CHANNEL_ID, ConfigStore.lastChannelId)
                }
                startActivity(intent)
            } else {
                loadChannels()
            }
        }
    }

    private fun selectTab(tabType: Int) {
        currentTab = tabType
        binding.tabFavorites.isSelected = (tabType == TAB_FAVORITES)
        binding.tabTving.isSelected = (tabType == TAB_TVING)
        binding.tabWavve.isSelected = (tabType == TAB_WAVVE)
        binding.tabAll.isSelected = (tabType == TAB_ALL)

        refreshChannelsView()
        focusFirstChannel()
    }

    private fun loadChannels(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return

        loadJob = lifecycleScope.launch {
            isLoadingChannels = true
            binding.pbChannelsLoading.isVisible = true
            refreshChannelsView()

            var channels = LiveTvManager.loadAllChannels(forceRefresh)
            var attempt = 0
            while (channels.isEmpty() && attempt < LOAD_MAX_ATTEMPTS) {
                // Wait for the link to come back rather than burning retries against a
                // dead one -- after a wake there is no address for the first few seconds.
                NetworkValidator.waitForValidatedNetwork(this@MainActivity, timeoutMs = LOAD_RETRY_MS)
                kotlinx.coroutines.delay(LOAD_RETRY_MS)
                channels = LiveTvManager.loadAllChannels(forceRefresh = true)
                attempt++
            }

            isLoadingChannels = false
            binding.pbChannelsLoading.isVisible = false
            refreshChannelsView()
            if (!binding.rvLiveChannels.hasFocus() && !binding.rvApps.hasFocus()) focusFirstChannel()
        }
    }

    /**
     * Home focus rule: always the first channel card; only when there is genuinely no
     * channel does it fall back to the browse button. Never a tab or settings button -- that
     * is the first focusable in the layout, so it is what the system picks by default
     * every time our requestFocus() fails on an empty row.
     */
    private fun focusFirstChannel(attempt: Int = 0) {
        if (attempt == 0) {
            focusHandler.removeCallbacksAndMessages(null)
        }
        binding.rvLiveChannels.post {
            if (appAdapter.isEditing) return@post
            firstChannelCard()?.let {
                it.requestFocus()
                return@post
            }
            // Cards may simply not be laid out yet, or the fetch may still be running.
            if ((channelAdapter.itemCount > 0 || isLoadingChannels) && attempt < FOCUS_MAX_ATTEMPTS) {
                focusHandler.postDelayed({ focusFirstChannel(attempt + 1) }, FOCUS_RETRY_MS)
                return@post
            }
            binding.btnBrowseAll.requestFocus()
        }
    }

    private fun firstChannelCard(): View? {
        if (!binding.rvLiveChannels.isVisible || channelAdapter.itemCount == 0) return null
        binding.rvLiveChannels.findViewHolderForAdapterPosition(0)?.let { return it.itemView }
        return if (binding.rvLiveChannels.childCount > 0) binding.rvLiveChannels.getChildAt(0) else null
    }

    private fun refreshChannelsView() {
        val favCount = LiveTvManager.getFavoriteChannels().size
        val tvingCount = LiveTvManager.getTvingChannels().size
        val wavveCount = LiveTvManager.getWavveChannels().size
        val allCount = LiveTvManager.getAllChannels().size

        binding.tabFavorites.text = "★ 즐겨찾기 ($favCount)"
        binding.tabTving.text = "🔴 TVING ($tvingCount)"
        binding.tabWavve.text = "🔵 Wavve ($wavveCount)"
        binding.tabAll.text = "전체 ($allCount)"

        val channelsToDisplay = currentTabChannels()

        channelAdapter.refreshData(channelsToDisplay)

        if (channelsToDisplay.isEmpty()) {
            // While a fetch is still running the row is empty because we have not got the
            // data yet, not because there is none -- say "loading", not "cannot load".
            binding.tvEmptyChannels.text = when {
                isLoadingChannels -> getString(R.string.channel_loading)
                // Nothing loaded at all means the fetch failed, not that this tab happens
                // to be empty. Telling the user to go register favourites here points them
                // at the wrong problem -- the channel list never arrived.
                LiveTvManager.getAllChannels().isEmpty() -> getString(R.string.no_channels)
                currentTab == TAB_FAVORITES -> getString(R.string.no_favorites)
                currentTab == TAB_TVING -> "등록된 TVING 채널이 없습니다.\n설정에서 TVING 로그인을 확인하세요."
                currentTab == TAB_WAVVE -> "등록된 Wavve 채널이 없습니다."
                else -> getString(R.string.no_channels)
            }
            binding.tvEmptyChannels.isVisible = true
            binding.rvLiveChannels.isVisible = false
        } else {
            binding.tvEmptyChannels.isVisible = false
            binding.rvLiveChannels.isVisible = true
        }
    }

    private fun currentTabChannels(): List<com.tblz.tvlauncher.model.ChannelData> = when (currentTab) {
        TAB_FAVORITES -> LiveTvManager.getFavoriteChannels()
        TAB_TVING -> LiveTvManager.getTvingChannels()
        TAB_WAVVE -> LiveTvManager.getWavveChannels()
        else -> LiveTvManager.getAllChannels()
    }

    /**
     * Background refresh of the channel list while the home is visible, so "지금 방송중" and the
     * live thumbnails stay current without the user leaving. Rebinds via a focus-preserving
     * payload (no scroll/focus jump). Skips if a foreground load is already running.
     */
    private fun silentProgramRefresh() {
        if (loadJob?.isActive == true) return
        lifecycleScope.launch {
            LiveTvManager.loadAllChannels(forceRefresh = true)

            val favCount = LiveTvManager.getFavoriteChannels().size
            val tvingCount = LiveTvManager.getTvingChannels().size
            val wavveCount = LiveTvManager.getWavveChannels().size
            val allCount = LiveTvManager.getAllChannels().size
            binding.tabFavorites.text = "★ 즐겨찾기 ($favCount)"
            binding.tabTving.text = "🔴 TVING ($tvingCount)"
            binding.tabWavve.text = "🔵 Wavve ($wavveCount)"
            binding.tabAll.text = "전체 ($allCount)"

            val list = currentTabChannels()
            if (list.isNotEmpty()) channelAdapter.refreshData(list)
        }
    }

    private fun loadAppShortcuts() {
        val list = mutableListOf<AppItem>()
        val pm = packageManager
        val userAppPkgs = ConfigStore.getUserApps()

        for (pkg in userAppPkgs) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                list.add(AppItem(id = pkg, title = label, icon = icon))
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        // Action: Add App Button (+)
        list.add(AppItem(id = "ACTION_ADD_APP", title = "앱 추가 (+)", iconRes = R.drawable.ic_add, isAction = true))
        // Action: All Apps
        list.add(AppItem(id = "ACTION_ALL_APPS", title = getString(R.string.all_apps), iconRes = R.drawable.ic_apps, isAction = true))
        // Keep Android settings available after the top gear became launcher settings.
        list.add(AppItem(id = "ACTION_SYSTEM_SETTINGS", title = getString(R.string.system_settings), iconRes = R.drawable.ic_settings, isAction = true))

        appAdapter.submitList(list)
    }

    private fun handleAppClick(appItem: AppItem) {
        when (appItem.id) {
            "ACTION_ADD_APP" -> {
                AppSelectDialog(this) {
                    loadAppShortcuts()
                }.tracked().show()
            }
            "ACTION_ALL_APPS" -> {
                AllAppsDialog(this).tracked().show()
            }
            "ACTION_SYSTEM_SETTINGS" -> {
                launchSystemSettings()
            }
            else -> {
                // TV apps commonly declare only CATEGORY_LEANBACK_LAUNCHER, and
                // getLaunchIntentForPackage() looks for CATEGORY_LAUNCHER -- it returns
                // null for them (TVING and Wavve here), which is why their shortcuts did
                // nothing while the apps started fine on their own.
                val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(appItem.id)
                    ?: packageManager.getLaunchIntentForPackage(appItem.id)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "${appItem.title} 앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        SettingsDialog(this) {
            LiveTvManager.updateFavoritesFlag()
            loadChannels(forceRefresh = true)
            loadAppShortcuts()
            selectTab(TAB_FAVORITES)
        }.tracked().show()
    }

    private fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 10000)
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.KOREA)
        binding.tvClock.text = sdf.format(Date())
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (appAdapter.isEditing) appAdapter.cancelEditing()
        // Do nothing on home screen so launcher never closes or finishes
    }

    /**
     * Track a launcher dialog (모아보기 / 설정 / 앱 목록) so [onNewIntent] can close it on HOME.
     * A Dialog lives in its own window and is NOT dismissed when HOME is pressed, which is why
     * only Back (which the dialog itself consumes) used to close 모아보기.
     */
    private fun <T : Dialog> T.tracked(): T {
        openDialogs.removeAll { !it.isShowing }
        openDialogs.add(this)
        return this
    }

    private fun dismissLauncherDialogs() {
        openDialogs.forEach { if (it.isShowing) it.dismiss() }
        openDialogs.clear()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // This is the HOME app (singleTask + CATEGORY_HOME). Pressing HOME while a launcher
        // dialog is open re-delivers the launcher intent here rather than dismissing the dialog,
        // so close any open dialog to return to a clean home.
        dismissLauncherDialogs()
        appAdapter.cancelEditing()
    }

    override fun onResume() {
        super.onResume()
        loadAppShortcuts()

        if (LiveTvManager.isStale(LIST_STALE_MS)) {
            // Empty (process killed / earlier fetch failed) OR the cached list is old enough
            // that its "지금 방송중" program info and live thumbnails are outdated -- e.g. after
            // watching a video and coming back. Re-fetch so the home isn't frozen on the old
            // list. Throttled by LIST_STALE_MS so quick in-and-out doesn't hammer the API.
            loadChannels(forceRefresh = true)
        } else {
            refreshChannelsView()
            focusFirstChannel()
        }

        // Keep "지금 방송중" current while the home stays open.
        programRefreshHandler.removeCallbacks(programRefreshRunnable)
        programRefreshHandler.postDelayed(programRefreshRunnable, PROGRAM_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        appAdapter.cancelEditing()
        programRefreshHandler.removeCallbacks(programRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        focusHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        programRefreshHandler.removeCallbacksAndMessages(null)
    }
}
