package com.scrolldock

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val store = context.getSharedPreferences("scroll_dock", Context.MODE_PRIVATE)

    var consentGranted: Boolean
        get() = store.getBoolean(KEY_CONSENT, false)
        set(value) = store.edit().putBoolean(KEY_CONSENT, value).apply()

    var overlayEnabled: Boolean
        get() = store.getBoolean(KEY_ENABLED, true)
        set(value) = store.edit().putBoolean(KEY_ENABLED, value).apply()

    var buttonSizeDp: Int
        get() = store.getInt(KEY_SIZE, 48).coerceIn(36, 64)
        set(value) = store.edit().putInt(KEY_SIZE, value.coerceIn(36, 64)).apply()

    var opacityPercent: Int
        get() = store.getInt(KEY_OPACITY, 82).coerceIn(40, 100)
        set(value) = store.edit().putInt(KEY_OPACITY, value.coerceIn(40, 100)).apply()

    var pagePercent: Int
        get() = store.getInt(KEY_PAGE_PERCENT, 85).coerceIn(60, 95)
        set(value) = store.edit().putInt(KEY_PAGE_PERCENT, value.coerceIn(60, 95)).apply()

    var intervalMs: Long
        get() = store.getLong(KEY_INTERVAL, 420L).coerceIn(250L, 900L)
        set(value) = store.edit().putLong(KEY_INTERVAL, value.coerceIn(250L, 900L)).apply()

    var collapsed: Boolean
        get() = store.getBoolean(KEY_COLLAPSED, false)
        set(value) = store.edit().putBoolean(KEY_COLLAPSED, value).apply()

    var hideUntilMs: Long
        get() = store.getLong(KEY_HIDE_UNTIL, 0L)
        set(value) = store.edit().putLong(KEY_HIDE_UNTIL, value).apply()

    fun selectedPackages(): Set<String> =
        store.getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES)?.toSet() ?: DEFAULT_PACKAGES

    fun setSelectedPackages(packages: Set<String>) {
        store.edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    fun isAllowed(packageName: String, ownPackage: String): Boolean =
        packageName == ownPackage || selectedPackages().contains(packageName)

    fun positionKey(isLandscape: Boolean, axis: String): String =
        if (isLandscape) "position_landscape_$axis" else "position_portrait_$axis"

    fun getPosition(isLandscape: Boolean): Pair<Int?, Int?> {
        val xKey = positionKey(isLandscape, "x")
        val yKey = positionKey(isLandscape, "y")
        return Pair(
            if (store.contains(xKey)) store.getInt(xKey, 0) else null,
            if (store.contains(yKey)) store.getInt(yKey, 0) else null,
        )
    }

    fun setPosition(isLandscape: Boolean, x: Int, y: Int) {
        store.edit()
            .putInt(positionKey(isLandscape, "x"), x)
            .putInt(positionKey(isLandscape, "y"), y)
            .apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        store.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        store.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val KEY_CONSENT = "consent"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SIZE = "button_size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_PAGE_PERCENT = "page_percent"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_PACKAGES = "selected_packages"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_HIDE_UNTIL = "hide_until"
        private val DEFAULT_PACKAGES = setOf("com.openai.chatgpt")
    }
}
