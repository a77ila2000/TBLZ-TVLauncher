package com.tblz.tvlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRowNavigationTest {
    @Test fun rightFromLastWrapsToFirst() {
        assertEquals(0, nextChannelRowPosition(19, 1, 20))
    }
    @Test fun leftFromFirstWrapsToLast() {
        assertEquals(19, nextChannelRowPosition(0, -1, 20))
    }
    @Test fun middleItemsAlsoHaveExplicitTargets() {
        assertEquals(11, nextChannelRowPosition(10, 1, 20))
        assertEquals(9, nextChannelRowPosition(10, -1, 20))
    }
    @Test fun inwardMovesAtEdgesStayInTheRow() {
        assertEquals(1, nextChannelRowPosition(0, 1, 20))
        assertEquals(18, nextChannelRowPosition(19, -1, 20))
    }
    @Test fun aSingleChannelKeepsFocusBothWays() {
        assertEquals(0, nextChannelRowPosition(0, 1, 1))
        assertEquals(0, nextChannelRowPosition(0, -1, 1))
    }
    @Test fun emptyRowsHaveNoTarget() {
        assertNull(nextChannelRowPosition(0, 1, 0))
    }
    @Test fun temporarilyMissingPositionsRecoverWithinTheRow() {
        assertEquals(0, nextChannelRowPosition(-1, 1, 20))
        assertEquals(19, nextChannelRowPosition(-1, -1, 20))
    }
    @Test fun shrinkingAListNeverReturnsAnInvalidPosition() {
        assertEquals(0, nextChannelRowPosition(19, 1, 5))
        assertEquals(4, nextChannelRowPosition(19, -1, 5))
    }
    @Test fun eightyRapidRightInputsKeepTheirFullCycleCount() {
        var pending = 0
        repeat(80) { pending = nextChannelRowPosition(pending, 1, 20)!! }
        assertEquals(0, pending)
    }
    @Test fun mixedPendingInputsAccumulateBeforeLayout() {
        var pending = 19
        repeat(3) { pending = nextChannelRowPosition(pending, 1, 20)!! }
        repeat(5) { pending = nextChannelRowPosition(pending, -1, 20)!! }
        assertEquals(17, pending)
    }
}
