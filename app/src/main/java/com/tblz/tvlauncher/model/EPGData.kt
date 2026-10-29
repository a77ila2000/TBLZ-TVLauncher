package com.tblz.tvlauncher.model

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EPGData(
    var programName: String = "",
    var startTime: Date? = null,
    var endTime: Date? = null
) : Serializable {

    fun getProgressPercent(): Int {
        val start = startTime?.time ?: return 0
        val end = endTime?.time ?: return 0
        val now = System.currentTimeMillis()
        if (now <= start) return 0
        if (now >= end) return 100
        val total = end - start
        if (total <= 0) return 0
        return (((now - start) * 100) / total).toInt().coerceIn(0, 100)
    }

    fun getTimeFormatted(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.KOREA)
        val startStr = startTime?.let { sdf.format(it) } ?: ""
        val endStr = endTime?.let { sdf.format(it) } ?: ""
        return if (startStr.isNotEmpty() && endStr.isNotEmpty()) "$startStr ~ $endStr" else ""
    }
}
