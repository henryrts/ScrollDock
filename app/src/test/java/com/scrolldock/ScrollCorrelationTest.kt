package com.scrolldock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollCorrelationTest {
    private val target = ScrollTargetFingerprint(
        windowId = 4,
        packageName = "com.example",
        viewId = "com.example:id/list",
        className = "RecyclerView",
        bounds = IntBounds(0, 100, 1000, 2000),
    )

    @Test
    fun matchingSourceIsAccepted() {
        val observation = ScrollObservation(
            sequence = 2,
            windowId = 4,
            packageName = "com.example",
            viewId = "com.example:id/list",
            className = "RecyclerView",
            bounds = IntBounds(0, 120, 1000, 1980),
            fromIndex = 4,
            toIndex = 12,
            itemCount = 80,
            scrollY = 500,
            maxScrollY = 4_000,
        )

        assertTrue(ScrollCorrelation.matches(target, observation))
    }

    @Test
    fun unrelatedNestedScrollerIsRejected() {
        val observation = ScrollObservation(
            sequence = 2,
            windowId = 4,
            packageName = "com.example",
            viewId = "com.example:id/carousel",
            className = "RecyclerView",
            bounds = IntBounds(0, 400, 1000, 700),
            fromIndex = 1,
            toIndex = 3,
            itemCount = 10,
            scrollY = 0,
            maxScrollY = 0,
        )

        assertFalse(ScrollCorrelation.matches(target, observation))
    }

    @Test
    fun anotherWindowIsRejected() {
        val observation = ScrollObservation(
            sequence = 2,
            windowId = 9,
            packageName = "com.example",
            viewId = "com.example:id/list",
            className = "RecyclerView",
            bounds = target.bounds,
            fromIndex = 0,
            toIndex = 0,
            itemCount = 0,
            scrollY = 0,
            maxScrollY = 0,
        )

        assertFalse(ScrollCorrelation.matches(target, observation))
    }
}