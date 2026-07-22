package com.scrolldock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

class ScrollCommandExecutor(
    private val service: ScrollAccessibilityService,
    private val resolver: ScrollableNodeResolver,
    private val gestureFallback: GestureFallback,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onActiveCommand(command: ScrollCommand?)
        fun onEdge(direction: ScrollDirection)
        fun onFailure()
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
        when (command) {
            ScrollCommand.PAGE_UP -> runPage(ScrollDirection.UP, token)
            ScrollCommand.PAGE_DOWN -> runPage(ScrollDirection.DOWN, token)
            ScrollCommand.TOP -> runToEdge(ScrollDirection.UP, token, 0, SystemClock.uptimeMillis())
            ScrollCommand.BOTTOM -> runToEdge(ScrollDirection.DOWN, token, 0, SystemClock.uptimeMillis())
            ScrollCommand.STOP -> stop()
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

    private fun runPage(direction: ScrollDirection, token: Int) {
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            active = null
            listener.onActiveCommand(null)
            if (result != StepResult.MOVED) listener.onFailure()
        }
    }

    private fun runContinuous(direction: ScrollDirection, token: Int) {
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            if (result == StepResult.MOVED) {
                handler.postDelayed({ runContinuous(direction, token) }, prefs.intervalMs)
            } else {
                active = null
                listener.onActiveCommand(null)
                listener.onEdge(direction)
            }
        }
    }

    private fun runToEdge(direction: ScrollDirection, token: Int, attempts: Int, startedAt: Long) {
        if (!isCurrent(token)) return
        if (attempts >= MAX_ATTEMPTS || SystemClock.uptimeMillis() - startedAt >= MAX_DURATION_MS) {
            finishAtEdge(direction, token)
            return
        }
        performStep(direction, token) { result ->
            if (!isCurrent(token)) return@performStep
            if (result == StepResult.MOVED) {
                handler.postDelayed(
                    { runToEdge(direction, token, attempts + 1, startedAt) },
                    prefs.intervalMs,
                )
            } else {
                finishAtEdge(direction, token)
            }
        }
    }

    private fun finishAtEdge(direction: ScrollDirection, token: Int) {
        if (!isCurrent(token)) return
        active = null
        listener.onActiveCommand(null)
        listener.onEdge(direction)
    }

    private fun performStep(direction: ScrollDirection, token: Int, completion: (StepResult) -> Unit) {
        val root = service.rootInActiveWindow
        if (root == null) {
            completion(StepResult.FAILED)
            return
        }
        val node = resolver.resolve(root, direction)
        if (node == null) {
            tryGesture(direction, token, service.scrollEventCounter, completion)
            return
        }
        attemptSemantic(node, resolver.actionIds(direction), 0, direction, token, completion)
    }

    private fun attemptSemantic(
        node: AccessibilityNodeInfo,
        actions: List<Int>,
        index: Int,
        direction: ScrollDirection,
        token: Int,
        completion: (StepResult) -> Unit,
    ) {
        if (!isCurrent(token)) return
        if (index >= actions.size) {
            tryGesture(direction, token, service.scrollEventCounter, completion)
            return
        }
        val before = service.scrollEventCounter
        val performed = runCatching { node.performAction(actions[index]) }.getOrDefault(false)
        if (!performed) {
            attemptSemantic(node, actions, index + 1, direction, token, completion)
            return
        }
        handler.postDelayed({
            if (!isCurrent(token)) return@postDelayed
            if (service.scrollEventCounter > before) {
                completion(StepResult.MOVED)
            } else {
                attemptSemantic(node, actions, index + 1, direction, token, completion)
            }
        }, ACTION_SETTLE_MS)
    }

    private fun tryGesture(
        direction: ScrollDirection,
        token: Int,
        before: Long,
        completion: (StepResult) -> Unit,
    ) {
        gestureFallback.dispatch(direction) { dispatched ->
            if (!isCurrent(token)) return@dispatch
            if (!dispatched) {
                completion(StepResult.FAILED)
                return@dispatch
            }
            handler.postDelayed({
                if (!isCurrent(token)) return@postDelayed
                completion(if (service.scrollEventCounter > before) StepResult.MOVED else StepResult.NO_MOVEMENT)
            }, GESTURE_SETTLE_MS)
        }
    }

    private fun isCurrent(token: Int): Boolean = token == generation && active != null

    companion object {
        private const val ACTION_SETTLE_MS = 260L
        private const val GESTURE_SETTLE_MS = 420L
        private const val MAX_DURATION_MS = 6_000L
        private const val MAX_ATTEMPTS = 30
    }
}
