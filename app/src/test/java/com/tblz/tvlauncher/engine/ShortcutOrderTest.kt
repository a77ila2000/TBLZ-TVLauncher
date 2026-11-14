package com.tblz.tvlauncher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutOrderTest {
    @Test fun savesTheChosenVisibleOrder() {
        assertEquals(listOf("b", "a", "c"), reorderedUserApps(listOf("a", "b", "c"), listOf("b", "a", "c")))
    }

    @Test fun keepsUnavailableShortcutsRegistered() {
        assertEquals(listOf("b", "a", "offline"), reorderedUserApps(listOf("a", "offline", "b"), listOf("b", "a")))
    }

    @Test fun ignoresUnknownAppsAndActionCards() {
        assertEquals(listOf("b", "a"), reorderedUserApps(listOf("a", "b"), listOf("b", "ACTION_ADD_APP", "unknown", "a")))
    }

    @Test fun ignoresDuplicateVisibleEntries() {
        assertEquals(listOf("b", "a"), reorderedUserApps(listOf("a", "b"), listOf("b", "b", "a")))
    }

    @Test fun anEmptyVisibleListDoesNotEraseSavedShortcuts() {
        assertEquals(listOf("a", "b"), reorderedUserApps(listOf("a", "b"), emptyList()))
    }
}
