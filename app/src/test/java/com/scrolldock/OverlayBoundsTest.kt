package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayBoundsTest {
    @Test
    fun overlayRemainsReachable() {
        val result = OverlayBounds.clamp(2000, -100, 100, 300, IntBounds(0, 0, 1080, 2400), 8)
        assertEquals(972, result.first)
        assertEquals(8, result.second)
    }
}
