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
    private val handler = Handler(Looper.getMainLooper())
    private val observations = ArrayDeque<ScrollObservation>()
    private var foregroundPackage: String? = null
    private val restoreOverlay = Runnable { updateVisibility() }

    @Volatile
    var scrollObservationSequence: Long = 0
        private set

    override fun onServiceConnected() {
        prefs = Prefs(this)
        prefs.register(this)
        resolver = ScrollableNodeResolver(this)
        val gesture = GestureFallback(this)
        val messageNavigator = MessageNavigator(this)
        overlay = OverlayController(
            service = this,
            prefs = prefs,
            commandSink = { executor.execute(it) },
            continuousStart = { executor.startContinuous(it) },
            stop = { executor.stop() },
        )
        executor = ScrollCommandExecutor(
            service = this,
            resolver = resolver,
            gestureFallback = gesture,
            messageNavigator = messageNavigator,
            listener = object : ScrollCommandExecutor.Listener {
                override fun onActiveCommand(command: ScrollCommand?) = overlay.updateActive(command)
                override fun onEdge(direction: ScrollDirection) = overlay.edge(direction)
                override fun onMessageEdge(direction: MessageDirection, role: MessageRole) =
                    overlay.messageEdge(direction, role)
                override fun onFailure() = overlay.failure()
                override fun onTimeout(direction: ScrollDirection) = overlay.timeout(direction)
            },
        )
        refreshScope()
        scheduleHiddenOverlayRestore()
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) recordScrollObservation(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val detected = detectForegroundPackage()
            if (detected != foregroundPackage) {
                foregroundPackage = detected
                executor.stop()
                resolver.invalidate()
            }
            updateVisibility()
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
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when {
            key == "selected_packages" -> refreshScope()
            key == "enabled" -> updateVisibility()
            key == "hide_until" -> {
                scheduleHiddenOverlayRestore()
                updateVisibility()
            }
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
        if (!::prefs.isInitialized || !::overlay.isInitialized) return
        val power = getSystemService(PowerManager::class.java)
        val foreground = currentForegroundPackage()
        val allowed = foreground != null && prefs.isAllowed(foreground, packageName)
        val visible = prefs.consentGranted && prefs.overlayEnabled && allowed && power.isInteractive &&
            prefs.hideUntilMs <= System.currentTimeMillis() && !hasBlockingSystemSurface()
        if (visible) {
            overlay.show()
        } else {
            executor.stop()
            overlay.hide()
        }
    }

    private fun detectForegroundPackage(): String? {
        val activeWindow = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        return activeWindow?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
    }

    private fun hasBlockingSystemSurface(): Boolean = windows.any { window ->
        window.isActive && window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            window.root?.packageName?.toString() in setOf("com.android.systemui", "android")
    }

    companion object {
        private const val MAX_OBSERVATIONS = 24
    }
}