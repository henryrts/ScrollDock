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
    private var params: WindowManager.LayoutParams? = null
    private var activeCommand: ScrollCommand? = null
    private val buttons = mutableMapOf<ScrollCommand, TextView>()
    private var showing = false
    private var lastHandleTapMs = 0L

    fun show() {
        if (showing || !prefs.overlayEnabled || prefs.hideUntilMs > System.currentTimeMillis()) return
        root = buildOverlay()
        val view = root ?: return
        val screen = service.availableAppBounds()
        val isLandscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val saved = prefs.getPosition(isLandscape)
        val sizeEstimate = prefs.buttonSizeDp * 5
        val initialX = saved.first ?: (screen.right - service.dp(prefs.buttonSizeDp + 8))
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
        windowManager.addView(view, params)
        showing = true
        view.post { clampAndPersist() }
    }

    fun hide() {
        hideMenu()
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
        Toast.makeText(service, if (direction == ScrollDirection.UP) "Top reached" else "Bottom reached", Toast.LENGTH_SHORT).show()
    }

    fun failure() {
        root?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        Toast.makeText(service, "No scrollable area found", Toast.LENGTH_SHORT).show()
    }

    private fun buildOverlay(): LinearLayout {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = prefs.opacityPercent / 100f
            background = roundedBackground(0xE61D2433.toInt(), 16)
            setPadding(service.dp(4), service.dp(4), service.dp(4), service.dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        buttons.clear()
        val handle = control("⋮", "ScrollDock handle", prefs.buttonSizeDp).apply {
            setOnTouchListener(DragTouchListener())
        }
        container.addView(handle)
        if (!prefs.collapsed) {
            listOf(ScrollCommand.TOP, ScrollCommand.PAGE_UP, ScrollCommand.PAGE_DOWN, ScrollCommand.BOTTOM)
                .forEach { command ->
                    val button = control(symbol(command), label(command), prefs.buttonSizeDp)
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
        textSize = if (text == "⋮") 25f else 24f
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
            layoutParams = LinearLayout.LayoutParams(service.dp(190), service.dp(48))
            setOnClickListener { hideMenu(); action() }
        }
        panel.addView(item(if (prefs.collapsed) "Expand" else "Collapse") {
            prefs.collapsed = !prefs.collapsed
            rebuild()
        })
        panel.addView(item("Hide for 10 minutes") {
            prefs.hideUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
            stop()
            hide()
        })
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
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ((anchor?.left ?: service.dp(20)) - service.dp(200)).coerceAtLeast(service.dp(8))
            topMargin = (anchor?.top ?: service.dp(100)).coerceAtLeast(service.dp(8))
        }
        full.addView(panel, lp)
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(full, windowParams)
        menu = full
    }

    private fun hideMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
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
        prefs.setPosition(landscape, lp.x, lp.y)
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
                        if (prefs.collapsed) {
                            prefs.collapsed = false
                            rebuild()
                        } else if (now - lastHandleTapMs <= 320L) {
                            prefs.collapsed = true
                            rebuild()
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

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = service.dp(radiusDp).toFloat()
    }

    private fun symbol(command: ScrollCommand): String = when (command) {
        ScrollCommand.TOP -> "⇈"
        ScrollCommand.PAGE_UP -> "↑"
        ScrollCommand.PAGE_DOWN -> "↓"
        ScrollCommand.BOTTOM -> "⇊"
        ScrollCommand.STOP -> "■"
    }

    private fun label(command: ScrollCommand): String = when (command) {
        ScrollCommand.TOP -> service.getString(R.string.top)
        ScrollCommand.PAGE_UP -> service.getString(R.string.page_up)
        ScrollCommand.PAGE_DOWN -> service.getString(R.string.page_down)
        ScrollCommand.BOTTOM -> service.getString(R.string.bottom)
        ScrollCommand.STOP -> service.getString(R.string.stop)
    }
}
