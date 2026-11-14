package com.tblz.tvlauncher.engine

/** Keep temporarily unavailable apps registered when arranging the visible shortcuts. */
internal fun reorderedUserApps(stored: List<String>, visibleOrder: List<String>): List<String> {
    val visible = visibleOrder.filter { it in stored }.distinct()
    return visible + stored.filter { it !in visible }
}
