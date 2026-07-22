package com.scrolldock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

class GestureFallback(
    private val service: ScrollAccessibilityService,
    private val prefs: Prefs,
) {
    fun dispatch(direction: ScrollDirection, completion: (Boolean) -> Unit) {
        val bounds = service.availableAppBounds()
        val overlay = service.overlayBounds()
        val top = bounds.top + service.dp(56)
        val bottom = keyboardTopOr(bounds.bottom) - service.dp(24)
        val usable = bottom - top
        if (usable < service.dp(160)) {
            completion(false)
            return
        }

        val fraction = prefs.pagePercent / 100f
        val distance = (usable * fraction).coerceAtMost(usable - service.dp(40))
        var x = bounds.centerX().toFloat()
        if (overlay != null && overlay.contains(x.toInt(), bounds.centerY())) {
            x = bounds.left + bounds.width() * 0.35f
        }

        val startY: Float
        val endY: Float
        if (direction == ScrollDirection.DOWN) {
            startY = bottom - service.dp(20).toFloat()
            endY = (startY - distance).coerceAtLeast(top.toFloat())
        } else {
            startY = top + service.dp(20).toFloat()
            endY = (startY + distance).coerceAtMost(bottom.toFloat())
        }

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = completion(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = completion(false)
            },
            null,
        )
        if (!accepted) completion(false)
    }

    private fun keyboardTopOr(defaultBottom: Int): Int {
        val keyboard = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return defaultBottom
        val bounds = Rect()
        keyboard.getBoundsInScreen(bounds)
        return if (bounds.top > 0) bounds.top else defaultBottom
    }
}
