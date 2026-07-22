package com.scrolldock

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ScrollAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    lateinit var prefs: Prefs
        private set
    private lateinit var resolver: ScrollableNodeResolver
    private lateinit var executor: ScrollCommandExecutor
    private lateinit var overlay: OverlayController
    private var foregroundPackage: String? = null

    @Volatile
    var scrollEventCounter: Long = 0
        private set

    override fun onServiceConnected() {
        prefs = Prefs(this)
        prefs.register(this)
        resolver = ScrollableNodeResolver(this)
        val gesture = GestureFallback(this, prefs)
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
            prefs = prefs,
            listener = object : ScrollCommandExecutor.Listener {
                override fun onActiveCommand(command: ScrollCommand?) = overlay.updateActive(command)
                override fun onEdge(direction: ScrollDirection) = overlay.edge(direction)
                override fun onFailure() = overlay.failure()
            },
        )
        refreshScope()
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) scrollEventCounter += 1
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
        if (::prefs.isInitialized) prefs.unregister(this)
        if (::executor.isInitialized) executor.stop()
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "selected_packages" -> refreshScope()
            "enabled", "hide_until" -> updateVisibility()
            else -> if (::overlay.isInitialized) overlay.rebuild()
        }
    }

    fun refreshScope() {
        // Window-change events remain global so the overlay can hide immediately after
        // the user leaves an allowed app. Content traversal still occurs only when a
        // direct command is issued in an allowed foreground app.
        resolver.invalidate()
        updateVisibility()
    }

    fun currentForegroundPackage(): String? = foregroundPackage ?: detectForegroundPackage()

    fun overlayBounds(): Rect? = if (::overlay.isInitialized) overlay.bounds() else null

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
}
