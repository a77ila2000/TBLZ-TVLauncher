package com.tblz.tvlauncher.player

import com.tblz.tvlauncher.model.ChannelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNavigationTest {
    private val a = ChannelData(id = "a")
    private val b = ChannelData(id = "b")
    private val c = ChannelData(id = "c")
    private val all = listOf(a, b, c)

    @Test fun initialSelectionOutsideFavoritesStillPlaysSelectedChannel() {
        assertEquals(b, ChannelNavigation.initialChannel(all, listOf(a, c), "b"))
    }

    @Test fun noFavoritesStillAllowsExplicitInitialSelection() {
        assertEquals(b, ChannelNavigation.initialChannel(all, emptyList(), "b"))
    }

    @Test fun missingInitialSelectionUsesScopeOnly() {
        assertEquals(c, ChannelNavigation.initialChannel(all, listOf(c), "missing"))
        assertNull(ChannelNavigation.initialChannel(all, emptyList(), "missing"))
    }

    @Test fun homeNavigationSkipsNonFavoritesBothWays() {
        assertEquals(c, ChannelNavigation.nextChannel(listOf(a, c), "a", 1))
        assertEquals(a, ChannelNavigation.nextChannel(listOf(a, c), "c", -1))
    }

    @Test fun favoritesWrapInBothDirections() {
        assertEquals(a, ChannelNavigation.nextChannel(listOf(a, c), "c", 1))
        assertEquals(c, ChannelNavigation.nextChannel(listOf(a, c), "a", -1))
    }

    @Test fun browseNavigationIncludesAllChannels() {
        assertEquals(b, ChannelNavigation.nextChannel(all, "a", 1))
        assertEquals(c, ChannelNavigation.nextChannel(all, "a", -1))
        assertEquals(a, ChannelNavigation.nextChannel(all, "c", 1))
    }

    @Test fun selectionOutsideScopeMovesToFavoriteBoundary() {
        assertEquals(a, ChannelNavigation.nextChannel(listOf(a, c), "b", 1))
        assertEquals(c, ChannelNavigation.nextChannel(listOf(a, c), "b", -1))
    }

    @Test fun emptyFavoritesNeverExpandToAllChannels() {
        assertNull(ChannelNavigation.nextChannel(emptyList(), "b", 1))
        assertNull(ChannelNavigation.nextChannel(emptyList(), "b", -1))
    }

    @Test fun singleFavoriteStaysOnIt() {
        assertEquals(c, ChannelNavigation.nextChannel(listOf(c), "c", 1))
        assertEquals(c, ChannelNavigation.nextChannel(listOf(c), "c", -1))
    }

    @Test fun changedFavoritesReplacePreviousNavigationScope() {
        assertEquals(c, ChannelNavigation.nextChannel(listOf(a, c), "a", 1))
        assertEquals(b, ChannelNavigation.nextChannel(listOf(a, b), "a", 1))
        assertNull(ChannelNavigation.nextChannel(emptyList(), "a", 1))
    }
}
