package com.scrolldock

import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class FirstReleaseActivity : BaseSettingsActivity() {
    private lateinit var prefs: Prefs
    private lateinit var features: FeaturePrefs
    private lateinit var status: TextView
    private lateinit var aiStatus: TextView
    private lateinit var phraseStatus: TextView
    private var openedSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        features = FeaturePrefs(this)
        features.ensureRecommendedApps(prefs)
        setContentView(buildContent())
        window.decorView.post(::launchSetupWhenRequired)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
        if (openedSetup) openedSetup = false
    }

    private fun buildContent(): View {
        val root = pageRoot()
        root.addView(title("ScrollDock"))
        root.addView(body("Version ${appVersion()} · Navigation, quick phrases, diagnostics and app controls."))

        root.addView(section("Service"))
        status = cardText("")
        root.addView(status)
        root.addView(actionButton("Set up or manage accessibility") {
            openedSetup = true
            startActivity(Intent(this, SetupActivity::class.java))
        })
        root.addView(actionButton("Open navigation settings") {
            startActivity(Intent(this, MainActivity::class.java))
        })

        root.addView(section("AI Apps"))
        aiStatus = body("")
        root.addView(aiStatus)
        root.addView(actionButton("Manage AI apps") {
            startActivity(Intent(this, AiAppsActivity::class.java))
        })

        root.addView(section("Quick phrases"))
        phraseStatus = body("")
        root.addView(phraseStatus)
        root.addView(actionButton("Edit five Quick phrases") {
            startActivity(Intent(this, QuickPhrasesActivity::class.java))
        })
        root.addView(body("Configured phrases appear as one-tap buttons in enabled apps. ScrollDock inserts them without pressing Send."))

        root.addView(section("Compatibility diagnostics"))
        root.addView(body("Inspect the current structural scroll target, supported actions, method, keyboard bounds, target signature and last failure without collecting screen text."))
        root.addView(actionButton("Open diagnostics") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })

        root.addView(section("Quick Settings"))
        root.addView(body("Add a tile to pause, resume or restore ScrollDock without opening Android settings."))
        root.addView(actionButton("Add Quick Settings tile", ::requestQuickSettingsTile))

        root.addView(section("Privacy"))
        root.addView(
            body(
                "No Internet permission, analytics, ads, accounts or screen capture. " +
                    "Quick phrases are stored locally. When you tap a phrase, ScrollDock temporarily reads only the focused editable field so it can insert text without deleting what is already there."
            )
        )

        refreshStatus()
        return scrollPage(root)
    }

    private fun refreshStatus() {
        val enabled = isScrollDockServiceEnabled(this)
        val paused = features.paused
        status.text = when {
            !enabled -> "Accessibility service: Setup required"
            paused -> "Accessibility service: Paused"
            !prefs.overlayEnabled -> "Accessibility service: Active · controls hidden"
            else -> "Accessibility service: Active"
        }
        status.setTextColor(
            when {
                !enabled -> 0xFFC62828.toInt()
                paused || !prefs.overlayEnabled -> 0xFFEF6C00.toInt()
                else -> 0xFF2E7D32.toInt()
            }
        )
        aiStatus.text = "Enabled apps: ${prefs.selectedPackages().size}\nRecommended: ChatGPT, Claude, Gemini, DeepSeek and Kimi"
        phraseStatus.text = "Configured phrases: ${features.configuredPhraseCount()} / ${FeaturePrefs.MAX_PHRASES}"
    }

    private fun launchSetupWhenRequired() {
        if (!prefs.consentGranted || !isScrollDockServiceEnabled(this)) {
            openedSetup = true
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Edit Android Quick Settings and add ScrollDock manually.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, ScrollDockTileService::class.java),
            "ScrollDock",
            Icon.createWithResource(this, R.drawable.ic_launcher),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "ScrollDock tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "ScrollDock tile already added"
                else -> "Tile request finished"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()
}

class AiAppsActivity : BaseSettingsActivity() {
    private lateinit var prefs: Prefs
    private lateinit var features: FeaturePrefs
    private val selected = linkedSetOf<String>()
    private lateinit var selectedStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        features = FeaturePrefs(this)
        features.ensureRecommendedApps(prefs)
        selected += prefs.selectedPackages()
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = pageRoot()
        root.addView(title("AI Apps"))
        root.addView(body("Recommended AI apps are enabled by default once. Turn any one off, or add another installed app separately."))

        selectedStatus = cardText("")
        root.addView(selectedStatus)
        root.addView(section("Recommended AI apps"))
        FeaturePrefs.RECOMMENDED_APPS.forEach { app ->
            root.addView(recommendedSwitch(app))
        }

        root.addView(section("Other installed apps"))
        root.addView(body("This list is kept separate so the recommended AI apps remain easy to manage."))
        root.addView(actionButton("Add or remove another app", ::showOtherAppsPicker))

        root.addView(actionButton("Save") {
            prefs.setSelectedPackages(selected)
            Toast.makeText(this, "Enabled apps saved", Toast.LENGTH_SHORT).show()
            finish()
        })
        root.addView(actionButton("Restore recommended apps") {
            selected += FeaturePrefs.RECOMMENDED_APPS.map { it.packageName }
            prefs.setSelectedPackages(selected)
            recreate()
        })

        refreshSelectedStatus()
        return scrollPage(root)
    }

    private fun recommendedSwitch(app: RecommendedApp): Switch = Switch(this).apply {
        val installed = isInstalled(app.packageName)
        text = "${app.label}\n${app.packageName} · ${if (installed) "Installed" else "Not installed"}"
        isChecked = selected.contains(app.packageName)
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
            refreshSelectedStatus()
        }
    }

    private fun showOtherAppsPicker() {
        val recommendedPackages = FeaturePrefs.RECOMMENDED_APPS.mapTo(hashSetOf()) { it.packageName }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .filterNot { recommendedPackages.contains(it.activityInfo.packageName) }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "No additional launcher apps found", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map {
            "${it.loadLabel(packageManager)} — ${it.activityInfo.packageName}"
        }.toTypedArray()
        val draft = selected.toMutableSet()
        val checked = BooleanArray(apps.size) { draft.contains(apps[it].activityInfo.packageName) }

        AlertDialog.Builder(this)
            .setTitle("Other installed apps")
            .setMultiChoiceItems(labels, checked) { _, index, enabled ->
                val appPackage = apps[index].activityInfo.packageName
                if (enabled) draft.add(appPackage) else draft.remove(appPackage)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                selected.clear()
                selected.addAll(draft)
                refreshSelectedStatus()
            }
            .show()
    }

    private fun refreshSelectedStatus() {
        if (::selectedStatus.isInitialized) selectedStatus.text = "Enabled apps: ${selected.size}"
    }

    private fun isInstalled(appPackage: String): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(appPackage, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

class QuickPhrasesActivity : BaseSettingsActivity() {
    private lateinit var features: FeaturePrefs
    private val labelFields = mutableListOf<EditText>()
    private val textFields = mutableListOf<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        features = FeaturePrefs(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = pageRoot()
        root.addView(title("Quick phrases"))
        root.addView(body("Store up to five local prompts. Each configured phrase appears as a one-tap button in enabled apps. ScrollDock never presses Send."))

        features.quickPhrases().forEachIndexed { index, phrase ->
            root.addView(section("Phrase ${index + 1}"))
            val label = EditText(this).apply {
                hint = "Short label"
                setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(FeaturePrefs.MAX_LABEL_LENGTH))
                setText(phrase.label.takeIf { phrase.text.isNotBlank() || !it.startsWith("Phrase ") }.orEmpty())
            }
            val text = EditText(this).apply {
                hint = "Prompt text"
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                filters = arrayOf(InputFilter.LengthFilter(FeaturePrefs.MAX_PHRASE_LENGTH))
                setText(phrase.text)
            }
            labelFields += label
            textFields += text
            root.addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            root.addView(text, ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
        }

        root.addView(actionButton("Save Quick phrases") {
            for (index in 0 until FeaturePrefs.MAX_PHRASES) {
                features.saveQuickPhrase(
                    index,
                    labelFields[index].text.toString(),
                    textFields[index].text.toString(),
                )
            }
            Toast.makeText(this, "Quick phrases saved", Toast.LENGTH_SHORT).show()
            finish()
        })
        root.addView(body("Do not store passwords, authentication codes or highly sensitive personal information."))
        return scrollPage(root)
    }
}

class DiagnosticsActivity : BaseSettingsActivity() {
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) refresh()
    }

    private fun buildContent(): View {
        val root = pageRoot()
        root.addView(title("Compatibility diagnostics"))
        root.addView(body("The report contains structural metadata only. It excludes screen text, input contents and clipboard contents."))
        report = cardText("").apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(report)
        root.addView(actionButton("Refresh", ::refresh))
        root.addView(actionButton("Copy diagnostic report") {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ScrollDock diagnostics", report.text))
            Toast.makeText(this, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
        })
        refresh()
        return scrollPage(root)
    }

    private fun refresh() {
        report.text = DiagnosticBridge.report(this)
    }
}

abstract class BaseSettingsActivity : Activity() {
    protected fun pageRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(40))
    }

    protected fun scrollPage(root: View): ScrollView = ScrollView(this).apply {
        addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    protected fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 32f
        setTextColor(resolveTextColor())
        setPadding(0, 0, 0, dp(6))
    }

    protected fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 21f
        setTextColor(resolveTextColor())
        setPadding(0, dp(24), 0, dp(6))
    }

    protected fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(resolveTextColor())
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(4), 0, dp(8))
    }

    protected fun cardText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(resolveTextColor())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(0x10000000)
    }

    protected fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun resolveTextColor(): Int {
        val attrs = intArrayOf(android.R.attr.textColorPrimary)
        val array = obtainStyledAttributes(attrs)
        return array.getColor(0, Color.BLACK).also { array.recycle() }
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

fun isScrollDockServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, ScrollAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
