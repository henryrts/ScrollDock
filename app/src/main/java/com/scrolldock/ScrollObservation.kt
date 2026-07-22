package com.scrolldock

data class ScrollTargetFingerprint(
    val windowId: Int,
    val packageName: String?,
    val viewId: String?,
    val className: String?,
    val bounds: IntBounds?,
)

data class ScrollObservation(
    val sequence: Long,
    val windowId: Int,
    val packageName: String?,
    val viewId: String?,
    val className: String?,
    val bounds: IntBounds?,
    val fromIndex: Int,
    val toIndex: Int,
    val itemCount: Int,
    val scrollY: Int,
    val maxScrollY: Int,
)

object ScrollCorrelation {
    fun matches(target: ScrollTargetFingerprint?, observation: ScrollObservation?): Boolean {
        if (observation == null) return false
        if (target == null) return true
        if (target.windowId >= 0 && observation.windowId >= 0 && target.windowId != observation.windowId) return false
        if (!target.packageName.isNullOrBlank() && !observation.packageName.isNullOrBlank() &&
            target.packageName != observation.packageName
        ) return false

        if (!target.viewId.isNullOrBlank() && !observation.viewId.isNullOrBlank()) {
            return target.viewId == observation.viewId
        }

        if (!target.className.isNullOrBlank() && !observation.className.isNullOrBlank() &&
            target.className != observation.className
        ) return false

        val targetBounds = target.bounds
        val observedBounds = observation.bounds
        if (targetBounds != null && observedBounds != null) {
            if (!targetBounds.intersects(observedBounds)) return false
            val smallerArea = minOf(targetBounds.area(), observedBounds.area()).coerceAtLeast(1L)
            return targetBounds.intersectionArea(observedBounds).toDouble() / smallerArea.toDouble() >= 0.25
        }
        return true
    }
}