package com.scrolldock

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class ScrollableNodeResolver(private val service: ScrollAccessibilityService) {
    private var cached: AccessibilityNodeInfo? = null
    private var cachedDirection: ScrollDirection? = null

    fun invalidate() {
        cached = null
        cachedDirection = null
    }

    fun resolve(root: AccessibilityNodeInfo, direction: ScrollDirection): AccessibilityNodeInfo? {
        cached?.let { node ->
            if (cachedDirection == direction && node.refresh() && node.isVisibleToUser && supports(node, direction)) {
                return node
            }
        }

        val screen = service.availableAppBounds()
        val screenArea = (screen.width().toLong() * screen.height().toLong()).coerceAtLeast(1L)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val actionIds = node.actionList.map { it.id }.toSet()
                val pageId = pageActionId(direction)
                val directionalId = directionalActionId(direction)
                val className = node.className?.toString().orEmpty()
                val area = bounds.width().coerceAtLeast(0).toLong() * bounds.height().coerceAtLeast(0).toLong()
                val ratio = (area.toDouble() / screenArea.toDouble()).toFloat()
                val horizontalOnly = className.contains("HorizontalScroll", ignoreCase = true) ||
                    (bounds.width() > bounds.height() * 2 && node.isScrollable && !actionIds.contains(directionalId))
                val score = CandidateScoring.score(
                    CandidateFeatures(
                        supportsPageAction = pageId != null && actionIds.contains(pageId),
                        supportsDirectionalAction = actionIds.contains(directionalId),
                        scrollable = node.isScrollable,
                        areaRatio = ratio,
                        containsAccessibilityFocus = node.isAccessibilityFocused,
                        vertical = bounds.height() >= bounds.width(),
                        tiny = bounds.width() < service.dp(80) || bounds.height() < service.dp(120),
                        horizontalOnly = horizontalOnly,
                        depth = depth,
                    )
                )
                if (score > bestScore && supports(node, direction) && Rect.intersects(bounds, screen)) {
                    bestScore = score
                    best = node
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to depth + 1) }
            }
        }

        cached = best
        cachedDirection = direction
        return best
    }

    private fun supports(node: AccessibilityNodeInfo, direction: ScrollDirection): Boolean {
        if (node.isScrollable) return true
        val ids = node.actionList.map { it.id }.toSet()
        return actionIds(direction).any(ids::contains)
    }

    fun actionIds(direction: ScrollDirection): List<Int> = buildList {
        pageActionId(direction)?.let(::add)
        add(directionalActionId(direction))
        add(
            if (direction == ScrollDirection.UP) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        )
    }.distinct()

    private fun pageActionId(direction: ScrollDirection): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (direction == ScrollDirection.UP) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
            }
        } else null

    private fun directionalActionId(direction: ScrollDirection): Int =
        if (direction == ScrollDirection.UP) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        } else {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        }
}
