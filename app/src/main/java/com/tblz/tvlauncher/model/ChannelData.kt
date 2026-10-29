package com.tblz.tvlauncher.model

import java.io.Serializable
import java.util.ArrayList

data class ChannelData(
    var id: String = "",
    var title: String = "",
    var programName: String = "",
    var stillImageUrl: String = "",
    var categoryId: String = "",
    var siteType: Int = SITE_WAVVE,
    var qualityType: Int = 0,
    var resolution: String = "FHD",
    var isAudioChannel: Boolean = false,
    var favorite: Int = 0,
    var rowIndex: Int = 0,
    var itemIndex: Int = 0,
    var epg: ArrayList<EPGData> = ArrayList()
) : Serializable, Comparable<ChannelData> {

    companion object {
        const val SITE_WAVVE = 0
        const val SITE_TVING = 1
        const val SITE_FAVORITE = 2

        fun siteName(siteType: Int): String {
            return when (siteType) {
                SITE_WAVVE -> "WAVVE"
                SITE_TVING -> "TVING"
                SITE_FAVORITE -> "FAVORITE"
                else -> "LIVE"
            }
        }
    }

    override fun compareTo(other: ChannelData): Int {
        return this.title.compareTo(other.title)
    }

    fun getCurrentEPG(): EPGData? {
        val now = System.currentTimeMillis()
        for (item in epg) {
            val start = item.startTime?.time ?: continue
            val end = item.endTime?.time ?: continue
            if (now in start..end) {
                return item
            }
        }
        return epg.firstOrNull()
    }
}
