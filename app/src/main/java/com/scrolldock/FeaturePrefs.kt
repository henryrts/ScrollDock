package com.scrolldock

import android.content.Context

data class RecommendedApp(
    val label: String,
    val packageName: String,
)

class FeaturePrefs(context: Context) {
    private val store = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var paused: Boolean
        get() = store.getBoolean(KEY_PAUSED, false)
        set(value) = store.edit().putBoolean(KEY_PAUSED, value).apply()

    fun ensureRecommendedApps(prefs: Prefs) {
        if (store.getBoolean(KEY_RECOMMENDED_APPS_SEEDED, false)) return
        prefs.setSelectedPackages(prefs.selectedPackages() + RECOMMENDED_APPS.map { it.packageName })
        store.edit().putBoolean(KEY_RECOMMENDED_APPS_SEEDED, true).apply()
    }

    companion object {
        const val STORE_NAME = "scroll_dock"
        const val KEY_PAUSED = "paused"

        private const val KEY_RECOMMENDED_APPS_SEEDED = "recommended_apps_seeded_v1"

        val RECOMMENDED_APPS = listOf(
            RecommendedApp("ChatGPT", "com.openai.chatgpt"),
            RecommendedApp("Claude", "com.anthropic.claude"),
            RecommendedApp("Gemini", "com.google.android.apps.bard"),
            RecommendedApp("DeepSeek", "com.deepseek.chat"),
            RecommendedApp("Kimi", "com.moonshot.kimichat"),
        )
    }
}
