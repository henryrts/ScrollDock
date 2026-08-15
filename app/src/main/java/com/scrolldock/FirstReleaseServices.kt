package com.scrolldock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

object DiagnosticBridge {
    const val KEY_PREFIX = "diagnostics."

    fun capture(context: Context, targetReport: String) {
        store(context).edit().putString(KEY_TARGET_REPORT, targetReport).apply()
    }

    fun markRunning(context: Context, command: ScrollCommand) {
        save(context, result = "RUNNING", failureCode = "NONE", failure = "None", action = command.name)
    }

    fun markSuccess(context: Context) {
        val store = store(context)
        if (store.getString(KEY_RESULT, "") == "RUNNING") {
            save(
                context,
                result = "SUCCESS",
                failureCode = "NONE",
                failure = "None",
                action = store.getString(KEY_ACTION, "Unknown").orEmpty(),
            )
        }
    }

    fun markEdge(context: Context, direction: ScrollDirection) {
        save(
            context,
            result = "EDGE",
            failureCode = "EDGE_REACHED",
            failure = "No position change; ${direction.name.lowercase()} edge reached",
            action = currentAction(context),
        )
    }

    fun markFailure(context: Context) {
        save(
            context,
            result = "FAILED",
            failureCode = "NO_SCROLL_TARGET_OR_ACTION",
            failure = "No compatible scroll target or action succeeded",
            action = currentAction(context),
        )
    }

    fun markTimeout(context: Context, direction: ScrollDirection) {
        save(
            context,
            result = "TIMEOUT",
            failureCode = "SAFETY_TIMEOUT",
            failure = "Safety limit reached before confirming the ${direction.name.lowercase()} edge",
            action = currentAction(context),
        )
    }

    fun report(context: Context): String = buildString {
        appendLine(store(context).getString(KEY_TARGET_REPORT, null) ?: defaultTargetReport(context))
        statusLines(context).forEach(::appendLine)
        append("Screen text collected: No")
    }

    fun statusLines(context: Context): List<String> {
        val store = store(context)
        val updated = store.getLong(KEY_UPDATED, 0L)
        return listOf(
            "Last action: ${store.getString(KEY_ACTION, "None")}",
            "Last result: ${store.getString(KEY_RESULT, "No action recorded")}",
            "Failure code: ${store.getString(KEY_FAILURE_CODE, "NONE")}",
            "Failure reason: ${store.getString(KEY_FAILURE, "None")}",
            "Updated: ${if (updated > 0L) DateFormat.getDateTimeInstance().format(Date(updated)) else "Never"}",
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

    private fun save(
        context: Context,
        result: String,
        failureCode: String,
        failure: String,
        action: String,
    ) {
        store(context).edit()
            .putString(KEY_RESULT, result)
            .putString(KEY_FAILURE_CODE, failureCode)
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

    private fun store(context: Context) = context.getSharedPreferences(FeaturePrefs.STORE_NAME, Context.MODE_PRIVATE).also {
        context.getSharedPreferences(LEGACY_DIAGNOSTIC_STORE_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private const val LEGACY_DIAGNOSTIC_STORE_NAME = "scroll_dock_diagnostics"
    private const val KEY_TARGET_REPORT = "${KEY_PREFIX}target_report"
    private const val KEY_RESULT = "${KEY_PREFIX}last_result"
    private const val KEY_FAILURE_CODE = "${KEY_PREFIX}failure_code"
    private const val KEY_FAILURE = "${KEY_PREFIX}last_failure"
    private const val KEY_ACTION = "${KEY_PREFIX}last_action"
    private const val KEY_UPDATED = "${KEY_PREFIX}updated"
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
