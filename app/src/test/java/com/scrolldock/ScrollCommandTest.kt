package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScrollCommandTest {
    @Test
    fun navigationCommandsRemainDistinct() {
        assertNotEquals(ScrollCommand.PAGE_UP, ScrollCommand.TOP)
        assertNotEquals(ScrollCommand.PAGE_DOWN, ScrollCommand.BOTTOM)
    }

    @Test
    fun directionsExposeBothAxes() {
        assertEquals(setOf(ScrollDirection.UP, ScrollDirection.DOWN), ScrollDirection.entries.toSet())
    }
}
