package com.tblz.tvlauncher.player

import com.tblz.tvlauncher.model.ChannelData

internal object ChannelNavigation {
    const val FAVORITES = "FAVORITES"
    const val ALL = "ALL"

    fun initialChannel(all: List<ChannelData>, scope: List<ChannelData>, targetId: String): ChannelData? =
        all.firstOrNull { it.id == targetId } ?: scope.firstOrNull()

    fun nextChannel(scope: List<ChannelData>, currentId: String?, direction: Int): ChannelData? {
        if (scope.isEmpty()) return null
        val index = scope.indexOfFirst { it.id == currentId }
        val nextIndex = if (index < 0) {
            if (direction < 0) scope.lastIndex else 0
        } else {
            Math.floorMod(index + direction, scope.size)
        }
        return scope[nextIndex]
    }
}
