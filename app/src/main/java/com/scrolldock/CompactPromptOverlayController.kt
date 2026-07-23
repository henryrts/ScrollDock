package com.scrolldock

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

class CompactPromptOverlayController(private val service: ScrollAccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null

    fun toggle(anchor: Rect?) {
        if (root != null) {
            hide()
            return
        }
        val phrases = FeaturePrefs(service).quickPhrases().filter { it.text.isNotBlank() }
        if (phrases.isEmpty()) {
            service.startActivity(
                Intent(service, QuickPhrasesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(service, "Add a prompt first", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(0xF21D2433.toInt(), 12)
            setPadding(service.dp(3), service.dp(3), service.dp(3), service.dp(3))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        phrases.forEach { container.addView(phraseButton(it)) }

        val screen = service.availableAppBounds()
        val width = service.dp(PROMPT_WIDTH_DP)
        val estimatedHeight = service.dp(phrases.size * PROMPT_HEIGHT_DP + 6)
        val source = anchor ?: Rect(screen.right - width, screen.centerY(), screen.right, screen.centerY())
        val preferredX = if (source.centerX() > screen.centerX()) {
            source.left - width - service.dp(5)
        } else {
            source.right + service.dp(5)
        }
        val maxX = (screen.right - width - service.dp(5)).coerceAtLeast(screen.left + service.dp(5))
        val maxY = (screen.bottom - estimatedHeight - service.dp(5)).coerceAtLeast(screen.top + service.dp(5))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferredX.coerceIn(screen.left + service.dp(5), maxX)
            y = (source.bottom - estimatedHeight).coerceIn(screen.top + service.dp(5), maxY)
        }

        runCatching { windowManager.addView(container, params) }
            .onSuccess { root = container }
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun phraseButton(phrase: QuickPhrase): TextView = TextView(service).apply {
        text = phrase.label
        contentDescription = "Insert ${phrase.label}"
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = roundedBackground(0x55FFFFFF, 9)
        setPadding(service.dp(8), 0, service.dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            service.dp(PROMPT_HEIGHT_DP),
        ).apply {
            topMargin = service.dp(1)
            bottomMargin = service.dp(1)
        }
        setOnClickListener { insertPhrase(phrase.text) }
        setOnLongClickListener {
            service.startActivity(
                Intent(service, QuickPhrasesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }
    }

    private fun insertPhrase(phrase: String) {
        val node = findFocusedEditableNode()
        if (node == null) {
            Toast.makeText(service, "Select a text field first", Toast.LENGTH_SHORT).show()
            return
        }
        if (node.isPassword) {
            Toast.makeText(service, "Prompts are disabled in password fields", Toast.LENGTH_SHORT).show()
            return
        }

        val insertion = QuickPhraseText.insert(
            current = node.text?.toString().orEmpty(),
            phrase = phrase,
            rawStart = node.textSelectionStart,
            rawEnd = node.textSelectionEnd,
        )
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, insertion.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            Toast.makeText(service, "This app blocked text insertion", Toast.LENGTH_SHORT).show()
            return
        }
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, insertion.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, insertion.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        hide()
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
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = service.dp(radiusDp).toFloat()
    }

    companion object {
        private const val PROMPT_WIDTH_DP = 116
        private const val PROMPT_HEIGHT_DP = 38
    }
}
