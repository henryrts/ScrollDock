package com.scrolldock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

class ScrollCommandExecutor(
    private val service: ScrollAccessibilityService,
    private val resolver: ScrollableNodeResolver,
    private val gestureFallback: GestureFallback,
    private val messageNavigator: MessageNavigator,
    private val listener: Listener,
) {
    interface Listener {
        fun onActiveCommand(command: ScrollCommand?)
        fun onMoved()
        fun onEdge(direction: ScrollDirection)
        fun onMessageEdge(direction: MessageDirection, role: MessageRole)
        fun onFailure()
        fun onTimeout(direction: ScrollDirection)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var active: ScrollCommand? = null

    fun execute(command: ScrollCommand) {
        if (command == ScrollCommand.STOP || active == command && command in setOf(ScrollCommand.TOP, ScrollCommand.BOTTOM)) {
            stop()
            return
        }
        stop(silent = true)
        generation += 1
        active = command
        listener.onActiveCommand(command)
        val token = generation

        command.messageRequestOrNull()?.let { (direction, role) ->
            runMessage(direction, role, token)
            return
        }

        when (command) {
            ScrollCommand.PAGE_UP -> runPage(ScrollDirection.UP, token)
            ScrollCommand.PAGE_DOWN -> runPage(ScrollDirection.DOWN, token)
            ScrollCommand.TOP -> runToEdge(ScrollDirection.UP, token, 0, SystemClock.uptimeMillis())
            ScrollCommand.BOTTOM -> runToEdge(ScrollDirection.DOWN, token, 0, SystemClock.uptimeMillis())
            else -> stop()
        }
    }

    fun startContinuous(direction: ScrollDirection) {
        stop(silent = true)
        generation += 1
        active = if (direction == ScrollDirection.UP) ScrollCommand.PAGE_UP else ScrollCommand.PAGE_DOWN
        listener.onActiveCommand(active)
        val token = generation
        runContinuous(direction, token)
    }

    fun stop(silent: Boolean = false) {
        generation += 1
        handler.removeCallbacksAndMessages(null)
        active = null
        if (!silent) listener.onActiveCommand(null)
    }

    private fun runMessage(direction: MessageDirection, role: MessageRole, token: Int) {
        messageNavigator.navigate(direction, role) { result ->
            if (!isCurrent(token)) return@navigate
            active = null
            listener.onActiveCommand(null)
            when (result) {
                StepResult.MOVED -> Unit
                StepResult.EDGE -> listener.onMessageEdge(direction, role)
                StepResult.FAILED -> listener.onFailure()
            }
        }
    }

    private fun runPage(direction: ScrollDirection, token: Int) {
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            active = null
            listener.onActiveCommand(null)
            when (result) {
                StepResult.MOVED -> listener.onMoved()
                StepResult.EDGE -> listener.onEdge(direction)
                StepResult.FAILED -> listener.onFailure()
            }
        }
    }

    private fun runContinuous(direction: ScrollDirection, token: Int) {
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            when (result) {
                StepResult.MOVED -> {
                    listener.onMoved()
                    handler.postDelayed(
                        { runContinuous(direction, token) },
                        service.currentProfile().intervalMs,
                    )
                }
                StepResult.EDGE -> finishAtEdge(direction, token)
                StepResult.FAILED -> finishFailure(token)
            }
        }
    }

    private fun runToEdge(direction: ScrollDirection, token: Int, attempts: Int, startedAt: Long) {
        if (!isCurrent(token)) return
        if (attempts >= MAX_ATTEMPTS || SystemClock.uptimeMillis() - startedAt >= MAX_DURATION_MS) {
            finishTimeout(direction, token)
            return
        }
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            when (result) {
                StepResult.MOVED -> {
                    listener.onMoved()
                    handler.postDelayed(
                        { runToEdge(direction, token, attempts + 1, startedAt) },
                        service.currentProfile().intervalMs,
                    )
                }
                StepResult.EDGE -> finishAtEdge(direction, token)
                StepResult.FAILED -> finishFailure(token)
            }
        }
    }

    private fun finishAtEdge(direction: ScrollDirection, token: Int) {
        if (!isCurrent(token)) return
        active = null
        listener.onActiveCommand(null)
        listener.onEdge(direction)
    }

    private fun finishFailure(token: Int) {
        if (!isCurrent(token)) return
        active = null
        listener.onActiveCommand(null)
        listener.onFailure()
    }

    private fun finishTimeout(direction: ScrollDirection, token: Int) {
        if (!isCurrent(token)) return
        active = null
        listener.onActiveCommand(null)
        listener.onTimeout(direction)
    }

    private fun performStep(direction: ScrollDirection, token: Int, completion: (StepResult) -> Unit) {
        val root = service.rootInActiveWindow
        if (root == null) {
            completion(StepResult.FAILED)
            return
        }
        val node = resolver.resolve(root, direction)
        if (node == null) {
            tryGesture(direction, token, null, null, completion)
            return
        }
        val fingerprint = resolver.fingerprint(node)
        attemptSemantic(
            node = node,
            actions = resolver.actionIds(direction),
            index = 0,
            direction = direction,
            token = token,
            fingerprint = fingerprint,
            completion = completion,
        )
    }

    private fun attemptSemantic(
        node: AccessibilityNodeInfo,
        actions: List<Int>,
        index: Int,
        direction: ScrollDirection,
        token: Int,
        fingerprint: ScrollTargetFingerprint,
        completion: (StepResult) -> Unit,
    ) {
        if (!isCurrent(token)) return
        if (index >= actions.size) {
            tryGesture(direction, token, node, fingerprint, completion)
            return
        }

        val beforeSequence = service.scrollObservationSequence
        val beforeSnapshot = resolver.snapshot(node)
        val performed = runCatching { node.performAction(actions[index]) }.getOrDefault(false)
        if (!performed) {
            attemptSemantic(node, actions, index + 1, direction, token, fingerprint, completion)
            return
        }

        handler.postDelayed({
            if (!isCurrent(token)) return@postDelayed
            val structurallyMoved = runCatching { resolver.snapshot(node).differsFrom(beforeSnapshot) }.getOrDefault(false)
            if (structurallyMoved || service.hasMatchingScrollAfter(beforeSequence, fingerprint)) {
                completion(StepResult.MOVED)
            } else {
                attemptSemantic(node, actions, index + 1, direction, token, fingerprint, completion)
            }
        }, ACTION_SETTLE_MS)
    }

    private fun tryGesture(
        direction: ScrollDirection,
        token: Int,
        node: AccessibilityNodeInfo?,
        fingerprint: ScrollTargetFingerprint?,
        completion: (StepResult) -> Unit,
    ) {
        val beforeSequence = service.scrollObservationSequence
        val beforeSnapshot = node?.let { runCatching { resolver.snapshot(it) }.getOrNull() }
        gestureFallback.dispatch(direction) { dispatched ->
            if (!isCurrent(token)) return@dispatch
            if (!dispatched) {
                completion(StepResult.FAILED)
                return@dispatch
            }
            handler.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                val structurallyMoved = if (node != null && beforeSnapshot != null) {
                    runCatching { resolver.snapshot(node).differsFrom(beforeSnapshot) }.getOrDefault(false)
                } else false
                val observedMovement = service.hasMatchingScrollAfter(beforeSequence, fingerprint)
                completion(if (structurallyMoved || observedMovement) StepResult.MOVED else StepResult.EDGE)
            }, GESTURE_SETTLE_MS)
        }
    }

    private fun isCurrent(token: Int): Boolean = token == generation && active != null

    companion object {
        private const val ACTION_SETTLE_MS = 300L
        private const val GESTURE_SETTLE_MS = 480L
        private const val MAX_DURATION_MS = 60_000L
        private const val MAX_ATTEMPTS = 300
    }
}
