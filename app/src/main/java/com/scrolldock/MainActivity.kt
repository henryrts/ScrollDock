package com.scrolldock

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var serviceStatus: TextView
    private lateinit var selectedAppsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildContent())
        if (!prefs.consentGranted) showDisclosure()
    }

    override fun onResume() {
        super.onResume()
        if (::serviceStatus.isInitialized) refreshStatus()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(title("ScrollDock"))
        root.addView(body("Persistent Page Up, Page Down, Top and Bottom controls for selected Android apps."))

        root.addView(section("Setup"))
        serviceStatus = body("")
        root.addView(serviceStatus)
        root.addView(actionButton("Open accessibility settings", ::openAccessibilitySettings))

        selectedAppsStatus = body("")
        root.addView(selectedAppsStatus)
        root.addView(actionButton("Choose apps", ::showAppPicker))
        root.addView(Switch(this).apply {
            text = "Enable floating controls"
            isChecked = prefs.overlayEnabled
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked -> prefs.overlayEnabled = checked }
        })

        root.addView(section("Controls"))
        root.addView(slider("Button size", 36, 64, prefs.buttonSizeDp) { prefs.buttonSizeDp = it })
        root.addView(slider("Overlay opacity", 40, 100, prefs.opacityPercent) { prefs.opacityPercent = it })
        root.addView(slider("Page distance", 60, 95, prefs.pagePercent) { prefs.pagePercent = it })
        root.addView(slider("Repeat interval", 250, 900, prefs.intervalMs.toInt()) { prefs.intervalMs = it.toLong() })

        root.addView(section("Test"))
        root.addView(body("Open a long local page. ScrollDock permits its own test screen even when only ChatGPT is selected."))
        root.addView(actionButton("Open navigation test") {
            startActivity(Intent(this, TestActivity::class.java))
        })

        root.addView(section("Privacy"))
        root.addView(
            body(
                "Internet permission: Not requested\n" +
                    "Screen recording: Not used\n" +
                    "Text storage: None\n" +
                    "Analytics: None\n" +
                    "Accessibility node text: Never read, logged, or stored\n" +
                    "Local backup: Disabled"
            )
        )
        root.addView(actionButton("Review disclosure") { showDisclosure(force = true) })
        root.addView(actionButton("Clear local settings", ::confirmClearSettings))

        refreshStatus()
        return scroll
    }

    private fun refreshStatus() {
        serviceStatus.text = if (isServiceEnabled()) {
            "Accessibility service: Enabled"
        } else {
            "Accessibility service: Disabled"
        }
        val packages = prefs.selectedPackages()
        selectedAppsStatus.text = "Selected apps: ${packages.size}\n${packages.sorted().joinToString("\n")}"
    }

    private fun showDisclosure(force: Boolean = false) {
        if (prefs.consentGranted && !force) return
        val consent = CheckBox(this).apply {
            text = "I understand and agree"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Accessibility disclosure")
            .setMessage(
                "ScrollDock can observe which selected app is open and interact with scrollable screen elements. " +
                    "It uses this access only when you press a ScrollDock control. " +
                    "It does not collect, save, or transmit screen text."
            )
            .setView(consent)
            .setNegativeButton(if (force) "Close" else "Exit") { _, _ -> if (!force) finish() }
            .setPositiveButton("Agree", null)
            .create()
        dialog.setOnShowListener {
            val agree = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            agree.isEnabled = consent.isChecked
            consent.setOnCheckedChangeListener { _, checked -> agree.isEnabled = checked }
            agree.setOnClickListener {
                prefs.consentGranted = true
                dialog.dismiss()
                refreshStatus()
            }
        }
        dialog.setCanceledOnTouchOutside(force)
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, ScrollAccessibilityService::class.java)
        val detail = Intent(ACCESSIBILITY_DETAILS_ACTION).apply {
            data = Uri.parse("package:$packageName")
            putExtra("android.intent.extra.COMPONENT_NAME", component)
        }
        runCatching { startActivity(detail) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun showAppPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .toMutableList()

        if (resolved.none { it.activityInfo.packageName == CHATGPT_PACKAGE }) {
            resolved.add(0, syntheticChatGptResolveInfo())
        }

        val selected = prefs.selectedPackages().toMutableSet()
        val labels = resolved.map { info ->
            val appPackage = info.activityInfo.packageName
            if (appPackage == CHATGPT_PACKAGE && info.activityInfo.name == SYNTHETIC_ACTIVITY) {
                "ChatGPT — $appPackage"
            } else {
                "${info.loadLabel(packageManager)} — $appPackage"
            }
        }.toTypedArray()
        val checked = BooleanArray(resolved.size) { selected.contains(resolved[it].activityInfo.packageName) }

        AlertDialog.Builder(this)
            .setTitle("Show ScrollDock in")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val appPackage = resolved[index].activityInfo.packageName
                if (isChecked) selected.add(appPackage) else selected.remove(appPackage)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.setSelectedPackages(selected)
                refreshStatus()
            }
            .show()
    }

    private fun syntheticChatGptResolveInfo(): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = CHATGPT_PACKAGE
            name = SYNTHETIC_ACTIVITY
        }
    }

    private fun confirmClearSettings() {
        AlertDialog.Builder(this)
            .setTitle("Clear settings?")
            .setMessage("This resets consent, selected apps, position, size, and opacity.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                getSharedPreferences("scroll_dock", MODE_PRIVATE).edit().clear().apply()
                recreate()
            }
            .show()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ScrollAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun slider(label: String, min: Int, max: Int, current: Int, onChange: (Int) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val value = TextView(this).apply {
            textSize = 16f
            text = "$label: $current"
        }
        val seek = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$label: $progress"
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(value)
        container.addView(seek)
        return container
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 32f
        setTextColor(resolveTextColor())
        setPadding(0, 0, 0, dp(6))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 21f
        setTextColor(resolveTextColor())
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(resolveTextColor())
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACCESSIBILITY_DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val SYNTHETIC_ACTIVITY = "synthetic"
    }
}
