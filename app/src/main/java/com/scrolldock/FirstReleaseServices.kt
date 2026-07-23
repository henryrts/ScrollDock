package com.scrolldock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.ArrayDeque

object DiagnosticBridge {
    fun capture(context: Context, targetReport: String) {
        store(context).edit().putString(KEY_TARGET_REPORT, targetReport).apply()
    }

    fun markRunning(context: Context, command: ScrollCommand) {
        save(context, result = "RUNNING", failure = "None", action = command.name)
    }

    fun markSuccess(context: Context) {
        val store = store(context)
        if (store.getString(KEY_RESULT, "") == "RUNNING") {
            save(
                context,
                result = "SUCCESS",
                failure = "None",
                action = store.getString(KEY_ACTION, "Unknown").orEmpty(),
            )
        }
    }

    fun markEdge(context: Context, direction: ScrollDirection) {
        save(context, result = "EDGE", failure = "No position change; ${direction.name.lowercase()} edge reached", action = currentAction(context))
    }

    fun markFailure(context: Context) {
        save(context, result = "FAILED", failure = "No compatible scroll target or action succeeded", action = currentAction(context))
    }

    fun markTimeout(context: Context, direction: ScrollDirection) {
        save(context, result = "TIMEOUT", failure = "Safety limit reached before confirming the ${direction.name.lowercase()} edge", action = currentAction(context))
    }

    fun report(context: Context): String = buildString {
        appendLine(store(context).getString(KEY_TARGET_REPORT, null) ?: defaultTargetReport(context))
        statusLines(context).forEach(::appendLine)
        append("Screen text collected: No")
    }

    fun statusLines(context: Context): List<String> {
        val store = store(context)
        return listOf(
            "Last action: ${store.getString(KEY_ACTION, "None")}",
            "Last result: ${store.getString(KEY_RESULT, "No action recorded")}",
            "Failure reason: ${store.getString(KEY_FAILURE, "None")}",
            "Updated: ${store.getLong(KEY_UPDATED, 0L).takeIf { it > 0L } ?: "Never"}",
        )
    }

    fun targetSignature(fingerprint: ScrollTargetFingerprint?): String {
        if (fingerprint == null) return "None"
        val raw = listOf(
            fingerprint.windowId,
            fingerprint.packageName.orEmpty(),
            fingerprint.viewId.orEmpty(),
            fingerprint.className.orEmpty(),
            fingerprint.bounds?.left ?: 0,
            fingerprint.bounds?.top ?: 0,
            fingerprint.bounds?.right ?: 0,
            fingerprint.bounds?.bottom ?: 0,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)
    }

    fun describeActions(actionIds: Collection<Int>): String {
        if (actionIds.isEmpty()) return "None"
        return actionIds.distinct().joinToString(", ") { id ->
            when {
                id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll forward"
                id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll backward"
                id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "scroll up"
                id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "scroll down"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id -> "page up"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id -> "page down"
                else -> "action $id"
            }
        }
    }

    private fun save(context: Context, result: String, failure: String, action: String) {
        store(context).edit()
            .putString(KEY_RESULT, result)
            .putString(KEY_FAILURE, failure)
            .putString(KEY_ACTION, action)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    private fun currentAction(context: Context): String = store(context).getString(KEY_ACTION, "Unknown").orEmpty()

    private fun defaultTargetReport(context: Context): String = buildString {
        appendLine("ScrollDock compatibility diagnostics")
        appendLine("Service: ${if (isScrollDockServiceEnabled(context)) "Enabled" else "Disabled"}")
        append("Target snapshot: No external app captured yet")
    }

    private fun store(context: Context) =
        context.getSharedPreferences(DIAGNOSTIC_STORE_NAME, Context.MODE_PRIVATE)

    private const val DIAGNOSTIC_STORE_NAME = "scroll_dock_diagnostics"
    private const val KEY_TARGET_REPORT = "diagnostics.target_report"
    private const val KEY_RESULT = "diagnostics.last_result"
    private const val KEY_FAILURE = "diagnostics.last_failure"
    private const val KEY_ACTION = "diagnostics.last_action"
    private const val KEY_UPDATED = "diagnostics.updated"
}

class QuickPhraseOverlayController(private val service: ScrollAccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null

    fun show() {
        if (root != null) return
        val phrases = FeaturePrefs(service).quickPhrases().filter { it.text.isNotBlank() }
        if (phrases.isEmpty()) return

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(0xE61D2433.toInt(), 14)
            setPadding(service.dp(4), service.dp(4), service.dp(4), service.dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        phrases.forEach { phrase ->
            container.addView(phraseButton(phrase))
        }

        val screen = service.availableAppBounds()
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.dp(8)
            y = (screen.centerY() - service.dp(120)).coerceAtLeast(screen.top + service.dp(8))
        }

        windowManager.addView(container, layoutParams)
        root = container
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun phraseButton(phrase: QuickPhrase): TextView = TextView(service).apply {
        text = phrase.label
        contentDescription = "Paste ${phrase.label}"
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = roundedBackground(0x55FFFFFF, 10)
        setPadding(service.dp(10), 0, service.dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(service.dp(132), service.dp(42)).apply {
            topMargin = service.dp(2)
            bottomMargin = service.dp(2)
        }
        setOnClickListener { pastePhrase(phrase.text) }
        setOnLongClickListener {
            service.startActivity(
                Intent(service, QuickPhrasesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
    }

    private fun pastePhrase(phrase: String) {
        val node = findFocusedEditableNode()
        if (node == null) {
            Toast.makeText(service, "Select a text field first", Toast.LENGTH_SHORT).show()
            return
        }
        if (node.isPassword) {
            Toast.makeText(service, "Quick phrases are disabled in password fields", Toast.LENGTH_SHORT).show()
            return
        }

        val current = node.text?.toString().orEmpty()
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart in 0..current.length) rawStart else current.length
        val end = if (rawEnd in start..current.length) rawEnd else start
        val replacement = current.substring(0, start) + phrase + current.substring(end)
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        }
        val inserted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        if (inserted) {
            val cursor = (start + phrase.length).coerceAtMost(replacement.length)
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            Toast.makeText(service, "Phrase inserted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(service, "This app blocked text insertion", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isEditable && focused.isVisibleToUser) return focused
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = service.dp(radiusDp).toFloat()
    }
}

class ScrollDockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        val features = FeaturePrefs(this)
        if (!isScrollDockServiceEnabled(this)) {
            openAccessibilitySettings()
            return
        }

        when {
            features.paused -> features.paused = false
            !prefs.overlayEnabled -> prefs.overlayEnabled = true
            else -> features.paused = true
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val features = FeaturePrefs(this)
        when {
            !isScrollDockServiceEnabled(this) -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Setup ScrollDock"
                setSubtitle(tile, "Accessibility disabled")
            }
            features.paused -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "ScrollDock paused"
                setSubtitle(tile, "Tap to resume")
            }
            !prefs.overlayEnabled -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "ScrollDock hidden"
                setSubtitle(tile, "Tap to restore")
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "ScrollDock on"
                setSubtitle(tile, "Tap to pause")
            }
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = value
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
