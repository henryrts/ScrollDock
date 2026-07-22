package com.scrolldock

data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
