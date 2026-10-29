package com.tblz.tvlauncher.model

import java.io.Serializable

data class PlaybackInfo(
    val url: String,
    val cookie: String = "",
    val drmServerUrl: String = "",
    val drmHeaderKey: String = "",
    val drmHeaderValue: String = "",
    val resolution: String = "FHD",
    val startTime: Long = 0L
) : Serializable

sealed class PlaybackResult {
    data class Success(val info: PlaybackInfo) : PlaybackResult()
    data class AuthRequired(val message: String) : PlaybackResult()
    data class Error(val message: String, val retryable: Boolean = true) : PlaybackResult()
}
