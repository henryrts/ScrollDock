package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppProfileTest {
    @Test
    fun profileValuesAreClampedToSupportedRanges() {
        val profile = AppProfile(
            buttonSizeDp = 10,
            opacityPercent = 200,
            pagePercent = 20,
            intervalMs = 5_000,
        ).sanitized()

        assertEquals(40, profile.buttonSizeDp)
        assertEquals(100, profile.opacityPercent)
        assertEquals(50, profile.pagePercent)
        assertEquals(1_200L, profile.intervalMs)
    }

    @Test
    fun targetSignatureRoundTripsWithoutLosingStructure() {
        val signature = TargetSignature(
            viewId = "com.example:id/message|list",
            className = "androidx.recyclerview.widget.RecyclerView",
            path = listOf(0, 2, 4),
            centerXPercent = 51,
            centerYPercent = 66,
        )

        assertEquals(signature, TargetSignature.decode(signature.encode()))
        assertNull(TargetSignature.decode("invalid"))
    }
}