package com.tblz.tvlauncher.engine

import android.content.Context
import android.util.Log
import com.tblz.tvlauncher.model.CategoryData
import com.tblz.tvlauncher.model.ChannelData
import com.tblz.tvlauncher.model.PlaybackInfo
import com.tblz.tvlauncher.model.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object LiveTvManager {
    private const val TAG = "LiveTvManager"

    private val allChannels = mutableListOf<ChannelData>()
    private val categories = mutableListOf<CategoryData>()

    /** When the channel list (with its EPG/program snapshot) was last fetched from the network. */
    @Volatile
    var lastLoadedAt: Long = 0L
        private set

    /** True if the cached list is older than [maxAgeMs] and worth a background refresh. */
    fun isStale(maxAgeMs: Long): Boolean =
        allChannels.isEmpty() || System.currentTimeMillis() - lastLoadedAt > maxAgeMs

    fun init(context: Context) {
        ConfigStore.init(context)
        TvingEngine.init(context)
    }

    suspend fun loadAllChannels(forceRefresh: Boolean = false): List<ChannelData> = withContext(Dispatchers.IO) {
        if (allChannels.isNotEmpty() && !forceRefresh) {
            updateFavoritesFlag()
            return@withContext allChannels
        }

        coroutineScope {
            val wavveDeferred = async { WavveEngine.fetchChannels() }
            val tvingDeferred = async { TvingEngine.fetchChannels() }

            val (wavveCats, wavveChans) = wavveDeferred.await()
            val (tvingCats, tvingChans) = tvingDeferred.await()

            synchronized(allChannels) {
                allChannels.clear()
                categories.clear()

                categories.addAll(tvingCats)
                categories.addAll(wavveCats)

                allChannels.addAll(tvingChans)
                allChannels.addAll(wavveChans)

                updateFavoritesFlag()
            }
        }
        if (allChannels.isNotEmpty()) {
            lastLoadedAt = System.currentTimeMillis()
        }
        Log.d(TAG, "Loaded total ${allChannels.size} channels (Tving: ${getTvingChannels().size}, Wavve: ${getWavveChannels().size})")
        allChannels
    }

    fun getAllChannels(): List<ChannelData> {
        synchronized(allChannels) {
            return allChannels.toList()
        }
    }

    fun getFavoriteChannels(): List<ChannelData> {
        val favs = ConfigStore.getFavorites()
        synchronized(allChannels) {
            return allChannels.filter { favs.contains(it.id) }
        }
    }

    fun getTvingChannels(): List<ChannelData> {
        synchronized(allChannels) {
            return allChannels.filter { it.siteType == ChannelData.SITE_TVING }
        }
    }

    fun getWavveChannels(): List<ChannelData> {
        synchronized(allChannels) {
            return allChannels.filter { it.siteType == ChannelData.SITE_WAVVE }
        }
    }

    fun getCategories(): List<CategoryData> {
        synchronized(allChannels) {
            return categories.toList()
        }
    }

    fun getChannelsByCategory(categoryId: String): List<ChannelData> {
        synchronized(allChannels) {
            return when (categoryId) {
                "FAVORITES" -> getFavoriteChannels()
                "ALL_TVING" -> getTvingChannels()
                "ALL_WAVVE" -> getWavveChannels()
                "ALL" -> allChannels.toList()
                "MERGED_TERRESTRIAL" -> allChannels.filter { it.categoryId in listOf("LN102", "CG200", "CG150") }
                "MERGED_COMPREHENSIVE" -> allChannels.filter { it.categoryId == "LN161" }
                "MERGED_DRAMA_ENT" -> allChannels.filter { it.categoryId in listOf("LN103", "LN130", "LN131", "CG110", "CG180") }
                "MERGED_NEWS_ECON" -> allChannels.filter { it.categoryId in listOf("LN201", "LN109", "CG130") }
                "MERGED_SPORTS" -> allChannels.filter { it.categoryId in listOf("LN108", "CG120") }
                "MERGED_MOVIE" -> allChannels.filter { it.categoryId in listOf("LN104", "CG100") }
                "MERGED_HOMESHOPPING" -> allChannels.filter { it.categoryId in listOf("LN100", "CG170") }
                "MERGED_KIDS" -> allChannels.filter { it.categoryId in listOf("LN105", "CG140", "CG220") }
                "WAVVE_BINGE" -> allChannels.filter { it.categoryId == "LN107" }
                "WAVVE_RADIO" -> allChannels.filter { it.categoryId == "LN106" }
                "TVING_DOCU" -> allChannels.filter { it.categoryId == "CG160" }
                "TVING_EDU" -> emptyList()
                else -> allChannels.filter { it.categoryId == categoryId }
            }
        }
    }

    fun getChannelById(id: String): ChannelData? {
        synchronized(allChannels) {
            return allChannels.firstOrNull { it.id == id }
        }
    }

    fun updateFavoritesFlag() {
        val favs = ConfigStore.getFavorites()
        synchronized(allChannels) {
            for (ch in allChannels) {
                ch.favorite = if (favs.contains(ch.id)) 1 else 0
            }
        }
    }

    fun toggleFavorite(channel: ChannelData): Boolean {
        val isFav = ConfigStore.toggleFavorite(channel.id)
        channel.favorite = if (isFav) 1 else 0
        updateFavoritesFlag()
        return isFav
    }

    suspend fun resolvePlayback(channel: ChannelData): PlaybackResult {
        return when (channel.siteType) {
            ChannelData.SITE_WAVVE -> WavveEngine.getStreamPlaybackInfo(channel)
            ChannelData.SITE_TVING -> TvingEngine.getStreamPlaybackInfo(channel)
            else -> PlaybackResult.Error("알 수 없는 채널 제공자입니다.")
        }
    }

    suspend fun refreshEPG(channel: ChannelData) {
        val epgList = when (channel.siteType) {
            ChannelData.SITE_WAVVE -> WavveEngine.fetchEPG(channel.id)
            ChannelData.SITE_TVING -> TvingEngine.fetchEPG(channel.id)
            else -> emptyList()
        }
        if (epgList.isNotEmpty()) {
            channel.epg.clear()
            channel.epg.addAll(epgList)
            channel.getCurrentEPG()?.let {
                channel.programName = it.programName
            }
        }
    }
}
