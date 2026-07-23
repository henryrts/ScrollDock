package com.scrolldock

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

class ScrollAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    lateinit var prefs: Prefs
        private set
    private lateinit var resolver: ScrollableNodeResolver
    private lateinit var executor: ScrollCommandExecutor
    private lateinit var overlay: OverlayController
    private lateinit var promptOverlay: CompactPromptOverlayController
    private val handler = Handler(Looper.getMainLooper())
    private val observations = ArrayDeque<ScrollObservation>()
    private var foregroundPackage: String? = null
    private val restoreOverlay = Runnable { updateVisibility() }

    @Volatile
    var scrollObservationSequence: Long = 0
        private set

    override fun onServiceConnected() {
        prefs = Prefs(this)
        FeaturePrefs(this).ensureRecommendedApps(prefs)
        prefs.register(this)
        resolver = ScrollableNodeResolver(this)
        val gesture = GestureFallback(this)
        val messageNavigator = MessageNavigator(this)
        promptOverlay = CompactPromptOverlayController(this)
        overlay = OverlayController(
            service = this,
            prefs = prefs,
            commandSink = { command ->
                if (command in SCROLL_COMMANDS) {
                    captureDiagnostics(command.scrollDirectionOrNull())
                    DiagnosticBridge.markRunning(this, command)
                }
                executor.execute(command)
            },
            continuousStart = { direction ->
                captureDiagnostics(direction)
                DiagnosticBridge.markRunning(
                    this,
                    if (direction == ScrollDirection.UP) ScrollCommand.PAGE_UP else ScrollCommand.PAGE_DOWN,
                )
                executor.startContinuous(direction)
            },
            stop = { executor.stop() },
            promptToggle = { promptOverlay.toggle(overlay.bounds()) },
        )
        executor = ScrollCommandExecutor(
            service = this,
            resolver = resolver,
            gestureFallback = gesture,
            messageNavigator = messageNavigator,
            listener = object : ScrollCommandExecutor.Listener {
                override fun onActiveCommand(command: ScrollCommand?) = overlay.updateActive(command)

                override fun onMoved() {
                    DiagnosticBridge.markSuccess(this@ScrollAccessibilityService)
                }

                override fun onEdge(direction: ScrollDirection) {
                    DiagnosticBridge.markEdge(this@ScrollAccessibilityService, direction)
                    overlay.edge(direction)
                }

                override fun onMessageEdge(direction: MessageDirection, role: MessageRole) =
                    overlay.messageEdge(direction, role)

                override fun onFailure() {
                    DiagnosticBridge.markFailure(this@ScrollAccessibilityService)
                    overlay.failure()
                }

                override fun onTimeout(direction: ScrollDirection) {
                    DiagnosticBridge.markTimeout(this@ScrollAccessibilityService, direction)
                    overlay.timeout(direction)
                }
            },
        )
        refreshScope()
        scheduleHiddenOverlayRestore()
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            recordScrollObservation(event)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val detected = detectForegroundPackage()
            if (detected != foregroundPackage) {
                foregroundPackage = detected
                executor.stop()
                promptOverlay.hide()
                resolver.invalidate()
            }
            updateVisibility()
            captureDiagnostics()
        }
    }

    override fun onInterrupt() {
        executor.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregister(this)
        if (::executor.isInitialized) executor.stop()
        if (::overlay.isInitialized) overlay.hide()
        if (::promptOverlay.isInitialized) promptOverlay.hide()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when {
            key == "selected_packages" -> refreshScope()
            key == "enabled" || key == FeaturePrefs.KEY_PAUSED -> updateVisibility()
            key == "hide_until" -> {
                scheduleHiddenOverlayRestore()
                updateVisibility()
            }
            key?.startsWith(FeaturePrefs.QUICK_PHRASE_PREFIX) == true -> {
                if (::promptOverlay.isInitialized) promptOverlay.hide()
                updateVisibility()
            }
            key?.startsWith(DiagnosticBridge.KEY_PREFIX) == true -> Unit
            key?.startsWith("profile.") == true -> {
                resolver.invalidate()
                if (::overlay.isInitialized) overlay.rebuild()
            }
            else -> if (::overlay.isInitialized) overlay.rebuild()
        }
    }

    fun refreshScope() {
        resolver.invalidate()
        updateVisibility()
    }

    fun currentForegroundPackage(): String? = foregroundPackage ?: detectForegroundPackage()

    fun currentProfile(): AppProfile = prefs.profile(currentForegroundPackage())

    fun overlayBounds(): Rect? = if (::overlay.isInitialized) overlay.bounds() else null

    fun hideTemporarily(durationMs: Long) {
        prefs.hideUntilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(0L)
        scheduleHiddenOverlayRestore()
        updateVisibility()
    }

    fun beginTargetSelection() {
        val root = rootInActiveWindow
        val appPackage = currentForegroundPackage()
        if (root == null || appPackage.isNullOrBlank()) {
            overlay.failure()
            return
        }
        val candidates = resolver.candidates(root)
        if (candidates.isEmpty()) {
            overlay.failure()
            return
        }
        executor.stop()
        promptOverlay.hide()
        overlay.showTargetPicker(candidates) { selected ->
            prefs.setTargetSignature(appPackage, selected.signature)
            prefs.updateProfile(appPackage) { it.copy(scrollMethod = ScrollMethod.LOCKED) }
            resolver.invalidate()
            overlay.targetSelected(selected.label)
        }
    }

    fun setTargetMode(method: ScrollMethod) {
        val appPackage = currentForegroundPackage() ?: return
        prefs.updateProfile(appPackage) { it.copy(scrollMethod = method) }
        if (method != ScrollMethod.LOCKED) prefs.setTargetSignature(appPackage, null)
        resolver.invalidate()
    }

    fun hasMatchingScrollAfter(
        sequence: Long,
        target: ScrollTargetFingerprint?,
    ): Boolean = synchronized(observations) {
        observations.any { observation ->
            observation.sequence > sequence &&
                if (target != null) {
                    ScrollCorrelation.matches(target, observation)
                } else {
                    val currentPackage = currentForegroundPackage()
                    currentPackage.isNullOrBlank() || observation.packageName == currentPackage
                }
        }
    }

    fun availableAppBounds(): Rect {
        val manager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(manager.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    fun keyboardBounds(): Rect? {
        val keyboardWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect()
        keyboardWindow.getBoundsInScreen(bounds)
        return bounds.takeIf { !it.isEmpty }
    }

    fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun recordScrollObservation(event: AccessibilityEvent) {
        val source = event.source
        val bounds = source?.let {
            val rect = Rect()
            it.getBoundsInScreen(rect)
            IntBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
        val observation = ScrollObservation(
            sequence = ++scrollObservationSequence,
            windowId = event.windowId,
            packageName = event.packageName?.toString() ?: source?.packageName?.toString(),
            viewId = source?.viewIdResourceName,
            className = source?.className?.toString() ?: event.className?.toString(),
            bounds = bounds,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            scrollY = event.scrollY,
            maxScrollY = event.maxScrollY,
        )
        synchronized(observations) {
            observations.addLast(observation)
            while (observations.size > MAX_OBSERVATIONS) observations.removeFirst()
        }
    }

    private fun scheduleHiddenOverlayRestore() {
        handler.removeCallbacks(restoreOverlay)
        val remaining = prefs.hideUntilMs - System.currentTimeMillis()
        if (remaining > 0L) handler.postDelayed(restoreOverlay, remaining)
    }

    private fun updateVisibility() {
        if (!::prefs.isInitialized || !::overlay.isInitialized || !::promptOverlay.isInitialized) return
        val power = getSystemService(PowerManager::class.java)
        val foreground = currentForegroundPackage()
        val allowed = foreground != null && prefs.isAllowed(foreground, packageName)
        val visible = prefs.consentGranted && prefs.overlayEnabled && !FeaturePrefs(this).paused && allowed && power.isInteractive &&
            prefs.hideUntilMs <= System.currentTimeMillis() && !hasBlockingSystemSurface()
        if (visible) {
            overlay.show()
            if (foreground == packageName) promptOverlay.hide()
        } else {
            executor.stop()
            overlay.hide()
            promptOverlay.hide()
        }
    }

    private fun detectForegroundPackage(): String? {
        val activeWindow = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        return activeWindow?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
    }

    private fun captureDiagnostics(direction: ScrollDirection? = null) {
        val appPackage = currentForegroundPackage()
        if (appPackage.isNullOrBlank() || appPackage == packageName || !prefs.selectedPackages().contains(appPackage)) return
        DiagnosticBridge.capture(this, buildDiagnosticTargetReport(direction))
    }

    private fun buildDiagnosticTargetReport(direction: ScrollDirection?): String {
        val appPackage = currentForegroundPackage()
        val profile = currentProfile()
        val root = rootInActiveWindow
        val node = root?.let {
            if (direction != null) {
                resolver.resolve(it, direction)
            } else {
                resolver.resolve(it, ScrollDirection.DOWN) ?: resolver.resolve(it, ScrollDirection.UP)
            }
        }
        val fingerprint = node?.let(resolver::fingerprint)
        val bounds = fingerprint?.bounds
        val keyboard = keyboardBounds()
        val actions = node?.actionList?.map { it.id }.orEmpty()
        val method = when (profile.scrollMethod) {
            ScrollMethod.AUTO -> "Automatic semantic target, then gesture fallback"
            ScrollMethod.LOCKED -> "Locked semantic target, then gesture fallback"
            ScrollMethod.GESTURE_ONLY -> "Gesture only"
        }

        return buildString {
            appendLine("ScrollDock compatibility diagnostics")
            appendLine("Package: ${appPackage ?: "None"}")
            appendLine("Requested direction: ${direction?.name ?: "Snapshot"}")
            appendLine("Selected node: ${node?.className ?: "None"}")
            appendLine("View ID: ${node?.viewIdResourceName ?: "None"}")
            appendLine("Node bounds: ${bounds?.let(::formatBounds) ?: "None"}")
            appendLine("Available scroll actions: ${DiagnosticBridge.describeActions(actions)}")
            appendLine("Chosen method: $method")
            appendLine("Keyboard bounds: ${keyboard?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "Hidden"}")
            append("Target signature: ${DiagnosticBridge.targetSignature(fingerprint)}")
        }
    }

    private fun formatBounds(bounds: IntBounds): String =
        "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"

    private fun hasBlockingSystemSurface(): Boolean = windows.any { window ->
        window.isActive && window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            window.root?.packageName?.toString() in setOf("com.android.systemui", "android")
    }

    companion object {
        private const val MAX_OBSERVATIONS = 24
        private val SCROLL_COMMANDS = setOf(
            ScrollCommand.PAGE_UP,
            ScrollCommand.PAGE_DOWN,
            ScrollCommand.TOP,
            ScrollCommand.BOTTOM,
        )
    }
}
