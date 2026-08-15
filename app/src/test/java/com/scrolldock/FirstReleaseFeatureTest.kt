package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstReleaseFeatureTest {
    @Test
    fun mapsOnlyScrollCommandsToDirections() {
        assertEquals(ScrollDirection.UP, ScrollCommand.PAGE_UP.scrollDirectionOrNull())
        assertEquals(ScrollDirection.UP, ScrollCommand.TOP.scrollDirectionOrNull())
        assertEquals(ScrollDirection.DOWN, ScrollCommand.PAGE_DOWN.scrollDirectionOrNull())
        assertEquals(ScrollDirection.DOWN, ScrollCommand.BOTTOM.scrollDirectionOrNull())
        assertNull(ScrollCommand.NEXT_MESSAGE.scrollDirectionOrNull())
        assertNull(ScrollCommand.STOP.scrollDirectionOrNull())
    }
}
