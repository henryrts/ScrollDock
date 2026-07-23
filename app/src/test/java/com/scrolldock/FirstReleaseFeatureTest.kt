package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstReleaseFeatureTest {
    @Test
    fun insertsPhraseAtCursorWithoutDeletingExistingText() {
        val result = QuickPhraseText.insert(
            current = "Explain please",
            phrase = "this ",
            rawStart = 8,
            rawEnd = 8,
        )

        assertEquals("Explain this please", result.text)
        assertEquals(13, result.cursor)
    }

    @Test
    fun replacesOnlySelectedText() {
        val result = QuickPhraseText.insert(
            current = "Explain old answer",
            phrase = "new",
            rawStart = 8,
            rawEnd = 11,
        )

        assertEquals("Explain new answer", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun invalidSelectionFallsBackToEnd() {
        val result = QuickPhraseText.insert(
            current = "Continue",
            phrase = " please",
            rawStart = -1,
            rawEnd = -1,
        )

        assertEquals("Continue please", result.text)
        assertEquals(15, result.cursor)
    }

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
