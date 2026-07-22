package com.scrolldock

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

data class MessageCandidate(
    val node: AccessibilityNodeInfo,
    val bounds: IntBounds,
    val role: MessageRole,
    val structuralHint: String,
)

class MessageNavigator(private val service: ScrollAccessibilityService) {
    fun navigate(
        direction: MessageDirection,
        role: MessageRole,
        completion: (StepResult) -> Unit,
    ) {
        val root = service.rootInActiveWindow ?: run {
            completion(StepResult.FAILED)
            return
        }
        val allCandidates = candidates(root)
        val candidates = if (role == MessageRole.ANY) {
            allCandidates
        } else {
            allCandidates.filter { it.role == role }
        }
        if (candidates.isEmpty()) {
            completion(StepResult.FAILED)
            return
        }

        val screen = service.availableAppBounds()
        val centerY = screen.centerY()
        val currentIndex = candidates.indexOfFirst { centerY in it.bounds.top..it.bounds.bottom }
        val targetIndex = when (direction) {
            MessageDirection.PREVIOUS -> {
                if (currentIndex >= 0) currentIndex - 1
                else candidates.indexOfLast { it.bounds.top < centerY }
            }
            MessageDirection.NEXT -> {
                if (currentIndex >= 0) currentIndex + 1
                else candidates.indexOfFirst { it.bounds.bottom > centerY }
            }
        }
        val target = candidates.getOrNull(targetIndex) ?: run {
            completion(StepResult.EDGE)
            return
        }

        completion(if (showOnScreen(target.node)) StepResult.MOVED else StepResult.FAILED)
    }

    fun candidates(root: AccessibilityNodeInfo): List<MessageCandidate> {
        val screenRect = service.availableAppBounds()
        val screen = IntBounds(screenRect.left, screenRect.top, screenRect.right, screenRect.bottom)
        val queue = ArrayDeque<TraversalItem>()
        queue.add(TraversalItem(root, 0, false))
        val result = mutableListOf<MessageCandidate>()

        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            val node = item.node
            val bounds = nodeBounds(node)
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val className = node.className?.toString().orEmpty().lowercase()
            val hint = "$id $className"
            val detectedRole = detectRole(hint)
            val strongMessageHint = MESSAGE_HINTS.any(hint::contains)
            val structurallyPlausible = item.insideScrollable && !node.isScrollable &&
                bounds.width >= screen.width * 55 / 100 &&
                bounds.height >= service.dp(56) &&
                bounds.height <= screen.height * 90 / 100 &&
                bounds.intersects(screen)

            if (node.isVisibleToUser && (strongMessageHint || structurallyPlausible)) {
                result += MessageCandidate(
                    node = node,
                    bounds = bounds,
                    role = detectedRole,
                    structuralHint = hint,
                )
            }

            val childInsideScrollable = item.insideScrollable || node.isScrollable || supportsVerticalScroll(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    queue.add(TraversalItem(child, item.depth + 1, childInsideScrollable))
                }
            }
        }

        val strong = result.filter { MESSAGE_HINTS.any(it.structuralHint::contains) }
        val chosen = if (strong.size >= 2) strong else result
        return chosen
            .sortedBy { it.bounds.top }
            .distinctBy { candidate ->
                listOf(
                    candidate.bounds.left / service.dp(12).coerceAtLeast(1),
                    candidate.bounds.top / service.dp(12).coerceAtLeast(1),
                    candidate.bounds.width / service.dp(12).coerceAtLeast(1),
                    candidate.bounds.height / service.dp(12).coerceAtLeast(1),
                )
            }
    }

    private fun showOnScreen(node: AccessibilityNodeInfo): Boolean {
        val itemInfo = node.collectionItemInfo
        var parent = node.parent
        if (itemInfo != null) {
            repeat(5) {
                val current = parent ?: return@repeat
                val supportsPosition = current.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
                }
                if (supportsPosition) {
                    val arguments = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, itemInfo.rowIndex)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, itemInfo.columnIndex)
                    }
                    if (current.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                            arguments,
                        )
                    ) return true
                }
                parent = current.parent
            }
        }

        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return true
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return true

        parent = node.parent
        repeat(5) {
            val current = parent ?: return@repeat
            if (current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return true
            parent = current.parent
        }
        return false
    }

    private fun detectRole(hint: String): MessageRole = when {
        USER_HINTS.any(hint::contains) -> MessageRole.USER
        ASSISTANT_HINTS.any(hint::contains) -> MessageRole.ASSISTANT
        else -> MessageRole.ANY
    }

    private fun supportsVerticalScroll(node: AccessibilityNodeInfo): Boolean {
        val actions = node.actionList.map { it.id }.toSet()
        return actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
            actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ||
            actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id) ||
            actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): IntBounds {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return IntBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private data class TraversalItem(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val insideScrollable: Boolean,
    )

    companion object {
        private val MESSAGE_HINTS = listOf(
            "message",
            "conversation",
            "chat_turn",
            "chatturn",
            "prompt",
            "response",
            "assistant",
            "user_turn",
        )
        private val USER_HINTS = listOf("user", "human", "prompt", "request")
        private val ASSISTANT_HINTS = listOf("assistant", "response", "answer", "bot", "model")
    }
}