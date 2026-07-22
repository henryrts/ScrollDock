package com.scrolldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateScoringTest {
    @Test
    fun semanticPageActionWins() {
        val page = CandidateScoring.score(
            CandidateFeatures(true, true, true, 0.8f, false, true, false, false, 2)
        )
        val generic = CandidateScoring.score(
            CandidateFeatures(false, false, true, 0.8f, false, true, false, false, 8)
        )
        assertTrue(page > generic)
    }

    @Test
    fun horizontalOnlyIsRejected() {
        val score = CandidateScoring.score(
            CandidateFeatures(true, true, true, 0.8f, false, false, false, true, 4)
        )
        assertEquals(Int.MIN_VALUE, score)
    }
}
