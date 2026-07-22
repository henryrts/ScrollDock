package com.scrolldock

data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun intersects(other: IntBounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun intersectionArea(other: IntBounds): Long {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return intersectionWidth.toLong() * intersectionHeight.toLong()
    }

    fun area(): Long = width.toLong() * height.toLong()
}

object OverlayBounds {
    fun clamp(x: Int, y: Int, width: Int, height: Int, screen: IntBounds, margin: Int): Pair<Int, Int> {
        val maxX = (screen.right - width - margin).coerceAtLeast(screen.left + margin)
        val maxY = (screen.bottom - height - margin).coerceAtLeast(screen.top + margin)
        return Pair(
            x.coerceIn(screen.left + margin, maxX),
            y.coerceIn(screen.top + margin, maxY),
        )
    }
}