package com.scrolldock

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val store = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var consentGranted: Boolean
        get() = store.getBoolean(KEY_CONSENT, false)
        set(value) = store.edit().putBoolean(KEY_CONSENT, value).apply()

    var overlayEnabled: Boolean
        get() = store.getBoolean(KEY_ENABLED, true)
        set(value) = store.edit().putBoolean(KEY_ENABLED, value).apply()

    var buttonSizeDp: Int
        get() = store.getInt(KEY_SIZE, 48).coerceIn(40, 72)
        set(value) = store.edit().putInt(KEY_SIZE, value.coerceIn(40, 72)).apply()

    var opacityPercent: Int
        get() = store.getInt(KEY_OPACITY, 82).coerceIn(30, 100)
        set(value) = store.edit().putInt(KEY_OPACITY, value.coerceIn(30, 100)).apply()

    var pagePercent: Int
        get() = store.getInt(KEY_PAGE_PERCENT, 85).coerceIn(50, 95)
        set(value) = store.edit().putInt(KEY_PAGE_PERCENT, value.coerceIn(50, 95)).apply()

    var intervalMs: Long
        get() = store.getLong(KEY_INTERVAL, 420L).coerceIn(200L, 1_200L)
        set(value) = store.edit().putLong(KEY_INTERVAL, value.coerceIn(200L, 1_200L)).apply()

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

    fun profile(packageName: String?): AppProfile {
        val appPackage = packageName.orEmpty().ifBlank { DEFAULT_PROFILE }
        val prefix = profilePrefix(appPackage)
        return AppProfile(
            buttonSizeDp = store.getInt(prefix + PROFILE_SIZE, buttonSizeDp),
            opacityPercent = store.getInt(prefix + PROFILE_OPACITY, opacityPercent),
            pagePercent = store.getInt(prefix + PROFILE_PAGE_PERCENT, pagePercent),
            intervalMs = store.getLong(prefix + PROFILE_INTERVAL, intervalMs),
            collapsed = store.getBoolean(prefix + PROFILE_COLLAPSED, store.getBoolean(KEY_COLLAPSED, false)),
            scrollMethod = store.getString(prefix + PROFILE_METHOD, null)
                ?.let { runCatching { ScrollMethod.valueOf(it) }.getOrNull() }
                ?: ScrollMethod.AUTO,
            messageButtonsEnabled = store.getBoolean(
                prefix + PROFILE_MESSAGE_BUTTONS,
                appPackage == CHATGPT_PACKAGE,
            ),
        ).sanitized()
    }

    fun saveProfile(packageName: String, profile: AppProfile) {
        val value = profile.sanitized()
        val prefix = profilePrefix(packageName)
        store.edit()
            .putInt(prefix + PROFILE_SIZE, value.buttonSizeDp)
            .putInt(prefix + PROFILE_OPACITY, value.opacityPercent)
            .putInt(prefix + PROFILE_PAGE_PERCENT, value.pagePercent)
            .putLong(prefix + PROFILE_INTERVAL, value.intervalMs)
            .putBoolean(prefix + PROFILE_COLLAPSED, value.collapsed)
            .putString(prefix + PROFILE_METHOD, value.scrollMethod.name)
            .putBoolean(prefix + PROFILE_MESSAGE_BUTTONS, value.messageButtonsEnabled)
            .apply()
    }

    fun updateProfile(packageName: String, transform: (AppProfile) -> AppProfile) {
        saveProfile(packageName, transform(profile(packageName)))
    }

    fun clearProfile(packageName: String) {
        val prefix = profilePrefix(packageName)
        val editor = store.edit()
        PROFILE_SUFFIXES.forEach { editor.remove(prefix + it) }
        editor.remove(targetKey(packageName))
        POSITION_SUFFIXES.forEach { editor.remove(prefix + it) }
        editor.apply()
    }

    fun targetSignature(packageName: String?): TargetSignature? =
        packageName?.let { TargetSignature.decode(store.getString(targetKey(it), null)) }

    fun setTargetSignature(packageName: String, signature: TargetSignature?) {
        val editor = store.edit()
        if (signature == null) editor.remove(targetKey(packageName))
        else editor.putString(targetKey(packageName), signature.encode())
        editor.apply()
    }

    fun getPosition(packageName: String?, isLandscape: Boolean): Pair<Int?, Int?> {
        val appPackage = packageName.orEmpty().ifBlank { DEFAULT_PROFILE }
        val xKey = positionKey(appPackage, isLandscape, "x")
        val yKey = positionKey(appPackage, isLandscape, "y")
        if (store.contains(xKey) || store.contains(yKey)) {
            return Pair(
                if (store.contains(xKey)) store.getInt(xKey, 0) else null,
                if (store.contains(yKey)) store.getInt(yKey, 0) else null,
            )
        }

        val legacyX = legacyPositionKey(isLandscape, "x")
        val legacyY = legacyPositionKey(isLandscape, "y")
        return Pair(
            if (store.contains(legacyX)) store.getInt(legacyX, 0) else null,
            if (store.contains(legacyY)) store.getInt(legacyY, 0) else null,
        )
    }

    fun setPosition(packageName: String?, isLandscape: Boolean, x: Int, y: Int) {
        val appPackage = packageName.orEmpty().ifBlank { DEFAULT_PROFILE }
        store.edit()
            .putInt(positionKey(appPackage, isLandscape, "x"), x)
            .putInt(positionKey(appPackage, isLandscape, "y"), y)
            .apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        store.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        store.unregisterOnSharedPreferenceChangeListener(listener)

    private fun profilePrefix(packageName: String): String = "profile.$packageName."

    private fun targetKey(packageName: String): String = profilePrefix(packageName) + PROFILE_TARGET

    private fun positionKey(packageName: String, isLandscape: Boolean, axis: String): String =
        profilePrefix(packageName) + "position_${if (isLandscape) "landscape" else "portrait"}_$axis"

    private fun legacyPositionKey(isLandscape: Boolean, axis: String): String =
        if (isLandscape) "position_landscape_$axis" else "position_portrait_$axis"

    companion object {
        private const val STORE_NAME = "scroll_dock"
        private const val KEY_CONSENT = "consent"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SIZE = "button_size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_PAGE_PERCENT = "page_percent"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_PACKAGES = "selected_packages"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_HIDE_UNTIL = "hide_until"

        private const val PROFILE_SIZE = "size"
        private const val PROFILE_OPACITY = "opacity"
        private const val PROFILE_PAGE_PERCENT = "page_percent"
        private const val PROFILE_INTERVAL = "interval"
        private const val PROFILE_COLLAPSED = "collapsed"
        private const val PROFILE_METHOD = "method"
        private const val PROFILE_MESSAGE_BUTTONS = "message_buttons"
        private const val PROFILE_TARGET = "target"

        private val PROFILE_SUFFIXES = listOf(
            PROFILE_SIZE,
            PROFILE_OPACITY,
            PROFILE_PAGE_PERCENT,
            PROFILE_INTERVAL,
            PROFILE_COLLAPSED,
            PROFILE_METHOD,
            PROFILE_MESSAGE_BUTTONS,
        )
        private val POSITION_SUFFIXES = listOf(
            "position_portrait_x",
            "position_portrait_y",
            "position_landscape_x",
            "position_landscape_y",
        )
        private const val DEFAULT_PROFILE = "default"
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private val DEFAULT_PACKAGES = setOf(CHATGPT_PACKAGE)
    }
}