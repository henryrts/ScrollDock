package com.scrolldock

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs

data class ScrollTargetCandidate(
    val node: AccessibilityNodeInfo,
    val signature: TargetSignature,
    val bounds: IntBounds,
    val score: Int,
    val label: String,
)

data class NodePositionSnapshot(
    val childCount: Int,
    val firstChild: IntBounds?,
    val lastChild: IntBounds?,
    val rangeCurrent: Float?,
    val structuralHash: Int,
) {
    fun differsFrom(other: NodePositionSnapshot): Boolean =
        childCount != other.childCount ||
            firstChild != other.firstChild ||
            lastChild != other.lastChild ||
            rangeCurrent != other.rangeCurrent ||
            structuralHash != other.structuralHash
}

class ScrollableNodeResolver(private val service: ScrollAccessibilityService) {
    private var cached: AccessibilityNodeInfo? = null
    private var cachedDirection: ScrollDirection? = null
    private var cachedPackage: String? = null

    fun invalidate() {
        cached = null
        cachedDirection = null
        cachedPackage = null
    }

    fun resolve(root: AccessibilityNodeInfo, direction: ScrollDirection): AccessibilityNodeInfo? {
        val appPackage = service.currentForegroundPackage()
        val profile = service.currentProfile()
        if (profile.scrollMethod == ScrollMethod.GESTURE_ONLY) return null

        if (profile.scrollMethod == ScrollMethod.LOCKED) {
            val signature = service.prefs.targetSignature(appPackage) ?: return null
            return resolveLocked(root, direction, signature)?.node
        }

        cached?.let { node ->
            if (cachedDirection == direction && cachedPackage == appPackage && node.refresh() &&
                node.isVisibleToUser && supports(node, direction)
            ) {
                return node
            }
        }

        val best = candidates(root, direction, limit = 1).firstOrNull()?.node
        cached = best
        cachedDirection = direction
        cachedPackage = appPackage
        return best
    }

    fun candidates(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection = ScrollDirection.DOWN,
        limit: Int = 8,
    ): List<ScrollTargetCandidate> {
        val screenRect = service.availableAppBounds()
        val screen = IntBounds(screenRect.left, screenRect.top, screenRect.right, screenRect.bottom)
        val screenArea = screen.area().coerceAtLeast(1L)
        val queue = ArrayDeque<TraversalItem>()
        queue.add(TraversalItem(root, 0, emptyList()))
        val result = mutableListOf<ScrollTargetCandidate>()

        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            val node = item.node
            if (node.isVisibleToUser) {
                val bounds = nodeBounds(node)
                val actionIds = node.actionList.map { it.id }.toSet()
                val pageId = pageActionId(direction)
                val directionalId = directionalActionId(direction)
                val className = node.className?.toString().orEmpty()
                val ratio = (bounds.area().toDouble() / screenArea.toDouble()).toFloat()
                val horizontalOnly = className.contains("HorizontalScroll", ignoreCase = true) ||
                    (bounds.width > bounds.height * 2 && node.isScrollable && !actionIds.contains(directionalId))
                val score = CandidateScoring.score(
                    CandidateFeatures(
                        supportsPageAction = pageId != null && actionIds.contains(pageId),
                        supportsDirectionalAction = actionIds.contains(directionalId),
                        scrollable = node.isScrollable,
                        areaRatio = ratio,
                        containsAccessibilityFocus = node.isAccessibilityFocused,
                        vertical = bounds.height >= bounds.width,
                        tiny = bounds.width < service.dp(80) || bounds.height < service.dp(120),
                        horizontalOnly = horizontalOnly,
                        depth = item.depth,
                    )
                )
                if (score > Int.MIN_VALUE && supports(node, direction) && bounds.intersects(screen)) {
                    val centerXPercent = ((bounds.centerX - screen.left) * 100 / screen.width.coerceAtLeast(1)).coerceIn(0, 100)
                    val centerYPercent = ((bounds.centerY - screen.top) * 100 / screen.height.coerceAtLeast(1)).coerceIn(0, 100)
                    val signature = TargetSignature(
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        path = item.path,
                        centerXPercent = centerXPercent,
                        centerYPercent = centerYPercent,
                    )
                    result += ScrollTargetCandidate(
                        node = node,
                        signature = signature,
                        bounds = bounds,
                        score = score,
                        label = node.className?.toString()?.substringAfterLast('.')?.ifBlank { null }
                            ?: "Scrollable area",
                    )
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    queue.add(TraversalItem(child, item.depth + 1, item.path + index))
                }
            }
        }

        return result
            .sortedWith(compareByDescending<ScrollTargetCandidate> { it.score }.thenByDescending { it.bounds.area() })
            .distinctBy { candidate ->
                listOf(
                    candidate.signature.viewId,
                    candidate.signature.className,
                    candidate.bounds.left / service.dp(16).coerceAtLeast(1),
                    candidate.bounds.top / service.dp(16).coerceAtLeast(1),
                    candidate.bounds.width / service.dp(16).coerceAtLeast(1),
                    candidate.bounds.height / service.dp(16).coerceAtLeast(1),
                )
            }
            .take(limit)
    }

    fun fingerprint(node: AccessibilityNodeInfo): ScrollTargetFingerprint = ScrollTargetFingerprint(
        windowId = node.windowId,
        packageName = node.packageName?.toString() ?: service.currentForegroundPackage(),
        viewId = node.viewIdResourceName,
        className = node.className?.toString(),
        bounds = nodeBounds(node),
    )

    fun snapshot(node: AccessibilityNodeInfo): NodePositionSnapshot {
        node.refresh()
        val visibleChildren = buildList {
            for (index in 0 until node.childCount) {
                node.getChild(index)?.takeIf { it.isVisibleToUser }?.let(::add)
            }
        }
        val structuralHash = visibleChildren.fold(17) { hash, child ->
            var next = hash * 31 + (child.className?.hashCode() ?: 0)
            next = next * 31 + (child.viewIdResourceName?.hashCode() ?: 0)
            next = next * 31 + nodeBounds(child).hashCode()
            val item = child.collectionItemInfo
            if (item != null) {
                next = next * 31 + item.rowIndex
                next = next * 31 + item.columnIndex
            }
            next
        }
        return NodePositionSnapshot(
            childCount = node.childCount,
            firstChild = visibleChildren.firstOrNull()?.let(::nodeBounds),
            lastChild = visibleChildren.lastOrNull()?.let(::nodeBounds),
            rangeCurrent = node.rangeInfo?.current,
            structuralHash = structuralHash,
        )
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

    private fun resolveLocked(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signature: TargetSignature,
    ): ScrollTargetCandidate? {
        var pathNode: AccessibilityNodeInfo? = root
        signature.path.forEach { index ->
            pathNode = pathNode?.takeIf { index in 0 until it.childCount }?.getChild(index)
        }
        pathNode?.let { node ->
            if (node.isVisibleToUser && supports(node, direction) && signatureMatches(node, signature)) {
                return candidateFromLocked(node, signature)
            }
        }

        return candidates(root, direction, limit = 20)
            .filter { candidate ->
                val idMatch = !signature.viewId.isNullOrBlank() && candidate.signature.viewId == signature.viewId
                val classMatch = !signature.className.isNullOrBlank() && candidate.signature.className == signature.className
                idMatch || classMatch
            }
            .minByOrNull { candidate ->
                abs(candidate.signature.centerXPercent - signature.centerXPercent) +
                    abs(candidate.signature.centerYPercent - signature.centerYPercent)
            }
    }

    private fun candidateFromLocked(node: AccessibilityNodeInfo, signature: TargetSignature): ScrollTargetCandidate =
        ScrollTargetCandidate(
            node = node,
            signature = signature,
            bounds = nodeBounds(node),
            score = Int.MAX_VALUE,
            label = node.className?.toString()?.substringAfterLast('.') ?: "Locked area",
        )

    private fun signatureMatches(node: AccessibilityNodeInfo, signature: TargetSignature): Boolean {
        if (!signature.viewId.isNullOrBlank() && node.viewIdResourceName != signature.viewId) return false
        if (!signature.className.isNullOrBlank() && node.className?.toString() != signature.className) return false
        return true
    }

    private fun supports(node: AccessibilityNodeInfo, direction: ScrollDirection): Boolean {
        if (node.isScrollable) return true
        val ids = node.actionList.map { it.id }.toSet()
        return actionIds(direction).any(ids::contains)
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): IntBounds {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return IntBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

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

    private data class TraversalItem(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val path: List<Int>,
    )
}