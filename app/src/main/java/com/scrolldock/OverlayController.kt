package com.scrolldock

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayController(
    private val service: ScrollAccessibilityService,
    private val prefs: Prefs,
    private val commandSink: (ScrollCommand) -> Unit,
    private val continuousStart: (ScrollDirection) -> Unit,
    private val stop: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var menu: View? = null
    private var targetPicker: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var activeCommand: ScrollCommand? = null
    private val buttons = mutableMapOf<ScrollCommand, TextView>()
    private var showing = false
    private var lastHandleTapMs = 0L

    fun show() {
        if (showing || !prefs.overlayEnabled || prefs.hideUntilMs > System.currentTimeMillis()) return
        val profile = service.currentProfile()
        root = buildOverlay(profile)
        val view = root ?: return
        val screen = service.availableAppBounds()
        val isLandscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val appPackage = service.currentForegroundPackage()
        val saved = prefs.getPosition(appPackage, isLandscape)
        val controlCount = if (profile.messageButtonsEnabled) 7 else 5
        val sizeEstimate = profile.buttonSizeDp * controlCount
        val initialX = saved.first ?: (screen.right - service.dp(profile.buttonSizeDp + 8))
        val initialY = saved.second ?: (screen.centerY() - service.dp(sizeEstimate / 2))
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                showing = true
                view.post { clampAndPersist() }
            }
            .onFailure {
                root = null
                params = null
            }
    }

    fun hide() {
        hideMenu()
        hideTargetPicker()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        params = null
        showing = false
    }

    fun rebuild() {
        val wasShowing = showing
        hide()
        if (wasShowing) show()
    }

    fun bounds(): Rect? {
        val view = root ?: return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun updateActive(command: ScrollCommand?) {
        activeCommand = command
        buttons.forEach { (key, button) ->
            button.text = if (key == command) "■" else symbol(key)
            button.contentDescription = if (key == command) service.getString(R.string.stop) else label(key)
        }
    }

    fun edge(direction: ScrollDirection) {
        root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast(if (direction == ScrollDirection.UP) "Top reached" else "Bottom reached")
    }

    fun messageEdge(direction: MessageDirection, role: MessageRole) {
        root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val roleLabel = when (role) {
            MessageRole.USER -> "user message"
            MessageRole.ASSISTANT -> "assistant response"
            MessageRole.ANY -> "message"
        }
        toast(if (direction == MessageDirection.PREVIOUS) "No previous $roleLabel" else "No next $roleLabel")
    }

    fun timeout(direction: ScrollDirection) {
        root?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        toast(
            if (direction == ScrollDirection.UP) {
                "Stopped after safety limit before confirming the top"
            } else {
                "Stopped after safety limit before confirming the bottom"
            }
        )
    }

    fun failure() {
        root?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        toast("No usable navigation target found")
    }

    fun targetSelected(label: String) {
        root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast("Locked to $label")
    }

    fun showTargetPicker(
        candidates: List<ScrollTargetCandidate>,
        onSelected: (ScrollTargetCandidate) -> Unit,
    ) {
        hideMenu()
        hideTargetPicker()
        val screenRect = service.availableAppBounds()
        val full = FrameLayout(service).apply {
            setBackgroundColor(0x55000000)
            isClickable = true
            setOnClickListener { hideTargetPicker() }
        }
        val banner = TextView(service).apply {
            text = "Choose the area ScrollDock should control\nTap an outline. Tap outside to cancel."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(0xF21D2433.toInt(), 14)
            setPadding(service.dp(16), service.dp(10), service.dp(16), service.dp(10))
        }
        full.addView(
            banner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = service.dp(12)
                rightMargin = service.dp(12)
                topMargin = service.dp(20)
            },
        )

        candidates.forEachIndexed { index, candidate ->
            val clippedLeft = candidate.bounds.left.coerceIn(screenRect.left, screenRect.right)
            val clippedTop = candidate.bounds.top.coerceIn(screenRect.top, screenRect.bottom)
            val clippedRight = candidate.bounds.right.coerceIn(clippedLeft + 1, screenRect.right)
            val clippedBottom = candidate.bounds.bottom.coerceIn(clippedTop + 1, screenRect.bottom)
            val marker = TextView(service).apply {
                text = "${index + 1}  ${candidate.label}"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.TOP or Gravity.START
                setPadding(service.dp(6), service.dp(4), service.dp(6), service.dp(4))
                background = outlinedBackground(0x2200A8FF, 0xFF4FC3F7.toInt(), 3, 10)
                isClickable = true
                setOnClickListener {
                    hideTargetPicker()
                    onSelected(candidate)
                }
            }
            full.addView(
                marker,
                FrameLayout.LayoutParams(
                    (clippedRight - clippedLeft).coerceAtLeast(service.dp(48)),
                    (clippedBottom - clippedTop).coerceAtLeast(service.dp(48)),
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = clippedLeft
                    topMargin = clippedTop
                },
            )
        }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(full, windowParams) }
            .onSuccess { targetPicker = full }
    }

    private fun buildOverlay(profile: AppProfile): LinearLayout {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = profile.opacityPercent / 100f
            background = roundedBackground(0xE61D2433.toInt(), 16)
            setPadding(service.dp(4), service.dp(4), service.dp(4), service.dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        buttons.clear()
        val handle = control("⋮", "ScrollDock handle", profile.buttonSizeDp).apply {
            setOnTouchListener(DragTouchListener())
        }
        container.addView(handle)
        if (!profile.collapsed) {
            val commands = buildList {
                add(ScrollCommand.TOP)
                add(ScrollCommand.PAGE_UP)
                if (profile.messageButtonsEnabled) add(ScrollCommand.PREVIOUS_MESSAGE)
                if (profile.messageButtonsEnabled) add(ScrollCommand.NEXT_MESSAGE)
                add(ScrollCommand.PAGE_DOWN)
                add(ScrollCommand.BOTTOM)
            }
            commands.forEach { command ->
                val button = control(symbol(command), label(command), profile.buttonSizeDp)
                configureCommandButton(button, command)
                buttons[command] = button
                container.addView(button)
            }
        }
        return container
    }

    private fun configureCommandButton(button: TextView, command: ScrollCommand) {
        if (command == ScrollCommand.PAGE_UP || command == ScrollCommand.PAGE_DOWN) {
            var downAt = 0L
            var longPressed = false
            val direction = if (command == ScrollCommand.PAGE_UP) ScrollDirection.UP else ScrollDirection.DOWN
            val longPressRunnable = Runnable {
                longPressed = true
                continuousStart(direction)
            }
            button.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAt = event.eventTime
                        longPressed = false
                        handler.postDelayed(longPressRunnable, 450L)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (longPressed) stop() else if (event.eventTime - downAt < 450L) commandSink(command)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (longPressed) stop()
                        true
                    }
                    else -> true
                }
            }
        } else {
            button.setOnClickListener {
                if (activeCommand == command) commandSink(ScrollCommand.STOP) else commandSink(command)
            }
        }
    }

    private fun control(text: String, description: String, sizeDp: Int): TextView = TextView(service).apply {
        this.text = text
        contentDescription = description
        textSize = if (text == "⋮") 25f else 23f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedBackground(0x66FFFFFF, 12)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(service.dp(sizeDp), service.dp(sizeDp)).apply {
            topMargin = service.dp(2)
            bottomMargin = service.dp(2)
        }
    }

    private fun showMenu() {
        if (menu != null) {
            hideMenu()
            return
        }
        val profile = service.currentProfile()
        val appPackage = service.currentForegroundPackage()
        val full = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideMenu() }
        }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xF21D2433.toInt(), 16)
            setPadding(service.dp(8), service.dp(8), service.dp(8), service.dp(8))
            setOnClickListener { }
        }
        fun item(label: String, action: () -> Unit): TextView = TextView(service).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(service.dp(16), 0, service.dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(service.dp(230), service.dp(48))
            setOnClickListener { hideMenu(); action() }
        }
        panel.addView(item(if (profile.collapsed) "Expand" else "Collapse") {
            appPackage?.let { pkg -> prefs.updateProfile(pkg) { it.copy(collapsed = !it.collapsed) } }
        })
        panel.addView(item("Choose scroll area") { service.beginTargetSelection() })
        panel.addView(item("Use automatic target") { service.setTargetMode(ScrollMethod.AUTO) })
        panel.addView(item("Use gesture only") { service.setTargetMode(ScrollMethod.GESTURE_ONLY) })
        panel.addView(item("Previous user message") { commandSink(ScrollCommand.PREVIOUS_USER_MESSAGE) })
        panel.addView(item("Previous assistant response") { commandSink(ScrollCommand.PREVIOUS_ASSISTANT_MESSAGE) })
        panel.addView(item("Next user message") { commandSink(ScrollCommand.NEXT_USER_MESSAGE) })
        panel.addView(item("Next assistant response") { commandSink(ScrollCommand.NEXT_ASSISTANT_MESSAGE) })
        panel.addView(item("Hide for 10 minutes") { service.hideTemporarily(10 * 60 * 1000L) })
        panel.addView(item("Hide in this app") {
            service.currentForegroundPackage()?.let { pkg ->
                if (pkg != service.packageName) {
                    prefs.setSelectedPackages(prefs.selectedPackages() - pkg)
                    service.refreshScope()
                }
            }
            stop()
            hide()
        })
        panel.addView(item("Open settings") {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        panel.addView(item("Disable overlay") {
            prefs.overlayEnabled = false
            stop()
            hide()
        })
        val anchor = bounds()
        val screen = service.availableAppBounds()
        val panelWidth = service.dp(246)
        val left = if ((anchor?.centerX() ?: screen.centerX()) > screen.centerX()) {
            ((anchor?.left ?: screen.right) - panelWidth - service.dp(8)).coerceAtLeast(service.dp(8))
        } else {
            ((anchor?.right ?: screen.left) + service.dp(8)).coerceAtMost(screen.right - panelWidth - service.dp(8))
        }
        val top = (anchor?.top ?: service.dp(100))
            .coerceIn(service.dp(8), (screen.bottom - service.dp(600)).coerceAtLeast(service.dp(8)))
        full.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = top
            },
        )
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(full, windowParams) }
            .onSuccess { menu = full }
    }

    private fun hideMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
    }

    private fun hideTargetPicker() {
        targetPicker?.let { runCatching { windowManager.removeView(it) } }
        targetPicker = null
    }

    private fun clampAndPersist() {
        val view = root ?: return
        val lp = params ?: return
        val screen = service.availableAppBounds()
        val clamped = OverlayBounds.clamp(
            lp.x,
            lp.y,
            view.width,
            view.height,
            IntBounds(screen.left, screen.top, screen.right, screen.bottom),
            service.dp(8),
        )
        lp.x = clamped.first
        lp.y = clamped.second
        runCatching { windowManager.updateViewLayout(view, lp) }
        val landscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        prefs.setPosition(service.currentForegroundPackage(), landscape, lp.x, lp.y)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragged = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!dragged) {
                longPressed = true
                showMenu()
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    longPressed = false
                    handler.postDelayed(longPress, 550L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragged && (abs(dx) > service.dp(4) || abs(dy) > service.dp(4))) {
                        dragged = true
                        handler.removeCallbacks(longPress)
                        stop()
                    }
                    if (dragged) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        root?.let { current -> runCatching { windowManager.updateViewLayout(current, lp) } }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragged) {
                        clampAndPersist()
                    } else if (!longPressed) {
                        val now = event.eventTime
                        val appPackage = service.currentForegroundPackage()
                        val profile = service.currentProfile()
                        if (profile.collapsed) {
                            appPackage?.let { pkg -> prefs.updateProfile(pkg) { it.copy(collapsed = false) } }
                        } else if (now - lastHandleTapMs <= 320L) {
                            appPackage?.let { pkg -> prefs.updateProfile(pkg) { it.copy(collapsed = true) } }
                            lastHandleTapMs = 0L
                        } else {
                            lastHandleTapMs = now
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragged) clampAndPersist()
                    return true
                }
            }
            return true
        }
    }

    private fun toast(message: String) =
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = service.dp(radiusDp).toFloat()
    }

    private fun outlinedBackground(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(service.dp(strokeDp), stroke)
        cornerRadius = service.dp(radiusDp).toFloat()
    }

    private fun symbol(command: ScrollCommand): String = when (command) {
        ScrollCommand.TOP -> "⇈"
        ScrollCommand.PAGE_UP -> "↑"
        ScrollCommand.PREVIOUS_MESSAGE -> "‹"
        ScrollCommand.NEXT_MESSAGE -> "›"
        ScrollCommand.PAGE_DOWN -> "↓"
        ScrollCommand.BOTTOM -> "⇊"
        ScrollCommand.STOP -> "■"
        ScrollCommand.PREVIOUS_USER_MESSAGE,
        ScrollCommand.PREVIOUS_ASSISTANT_MESSAGE -> "‹"
        ScrollCommand.NEXT_USER_MESSAGE,
        ScrollCommand.NEXT_ASSISTANT_MESSAGE -> "›"
    }

    private fun label(command: ScrollCommand): String = when (command) {
        ScrollCommand.TOP -> service.getString(R.string.top)
        ScrollCommand.PAGE_UP -> service.getString(R.string.page_up)
        ScrollCommand.PAGE_DOWN -> service.getString(R.string.page_down)
        ScrollCommand.BOTTOM -> service.getString(R.string.bottom)
        ScrollCommand.STOP -> service.getString(R.string.stop)
        ScrollCommand.PREVIOUS_MESSAGE -> "Previous message"
        ScrollCommand.NEXT_MESSAGE -> "Next message"
        ScrollCommand.PREVIOUS_USER_MESSAGE -> "Previous user message"
        ScrollCommand.NEXT_USER_MESSAGE -> "Next user message"
        ScrollCommand.PREVIOUS_ASSISTANT_MESSAGE -> "Previous assistant response"
        ScrollCommand.NEXT_ASSISTANT_MESSAGE -> "Next assistant response"
    }
}