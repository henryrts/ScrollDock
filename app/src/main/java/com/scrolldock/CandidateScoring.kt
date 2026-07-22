package com.scrolldock

data class CandidateFeatures(
    val supportsPageAction: Boolean,
    val supportsDirectionalAction: Boolean,
    val scrollable: Boolean,
    val areaRatio: Float,
    val containsAccessibilityFocus: Boolean,
    val vertical: Boolean,
    val tiny: Boolean,
    val horizontalOnly: Boolean,
    val depth: Int,
)

object CandidateScoring {
    fun score(features: CandidateFeatures): Int {
        if (features.horizontalOnly) return Int.MIN_VALUE
        var score = 0
        if (features.supportsPageAction) score += 100
        if (features.supportsDirectionalAction) score += 80
        if (features.scrollable) score += 50
        if (features.areaRatio > 0.40f) score += 30
        if (features.containsAccessibilityFocus) score += 25
        if (features.vertical) score += 15
        if (features.tiny) score -= 50
        score += features.depth.coerceAtMost(20) * 2
        return score
    }
}
