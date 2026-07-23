package com.scrolldock

import android.content.Context

data class RecommendedApp(
    val label: String,
    val packageName: String,
)

data class QuickPhrase(
    val label: String,
    val text: String,
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

    fun quickPhrases(): List<QuickPhrase> = (0 until MAX_PHRASES).map { index ->
        val text = store.getString(phraseTextKey(index), "").orEmpty().take(MAX_PHRASE_LENGTH)
        val savedLabel = store.getString(phraseLabelKey(index), "").orEmpty().trim().take(MAX_LABEL_LENGTH)
        QuickPhrase(
            label = savedLabel.ifBlank { "Phrase ${index + 1}" },
            text = text,
        )
    }

    fun saveQuickPhrase(index: Int, label: String, text: String) {
        require(index in 0 until MAX_PHRASES)
        store.edit()
            .putString(phraseLabelKey(index), label.trim().take(MAX_LABEL_LENGTH))
            .putString(phraseTextKey(index), text.take(MAX_PHRASE_LENGTH))
            .apply()
    }

    fun configuredPhraseCount(): Int = quickPhrases().count { it.text.isNotBlank() }

    companion object {
        const val STORE_NAME = "scroll_dock"
        const val KEY_PAUSED = "paused"
        const val QUICK_PHRASE_PREFIX = "quick_phrase."
        const val MAX_PHRASES = 5
        const val MAX_LABEL_LENGTH = 16
        const val MAX_PHRASE_LENGTH = 5_000

        private const val KEY_RECOMMENDED_APPS_SEEDED = "recommended_apps_seeded_v1"

        val RECOMMENDED_APPS = listOf(
            RecommendedApp("ChatGPT", "com.openai.chatgpt"),
            RecommendedApp("Claude", "com.anthropic.claude"),
            RecommendedApp("Gemini", "com.google.android.apps.bard"),
            RecommendedApp("DeepSeek", "com.deepseek.chat"),
            RecommendedApp("Kimi", "com.moonshot.kimichat"),
        )

        private fun phraseLabelKey(index: Int): String = "$QUICK_PHRASE_PREFIX$index.label"
        private fun phraseTextKey(index: Int): String = "$QUICK_PHRASE_PREFIX$index.text"
    }
}
