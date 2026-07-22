package com.scrolldock

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private var openedAppInfo = false
    private var openedAccessibility = false
    private var leavingSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildContent())
        window.decorView.post(::advanceSetup)
    }

    override fun onResume() {
        super.onResume()
        if (leavingSetup || !::prefs.isInitialized) return

        if (isServiceEnabled()) {
            openMainSettings()
            return
        }

        if (::status.isInitialized) refreshStatus()

        when {
            openedAppInfo -> {
                openedAppInfo = false
                window.decorView.post(::showAccessibilityNextStep)
            }
            openedAccessibility -> {
                openedAccessibility = false
                window.decorView.post {
                    if (!isServiceEnabled()) showStillDisabledDialog()
                }
            }
        }
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        root.addView(title("Set up ScrollDock"))
        root.addView(
            body(
                "ScrollDock was installed from an APK. Android may block its accessibility service " +
                    "until you manually allow restricted settings."
            )
        )

        status = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(status)

        root.addView(section("Do this first"))
        root.addView(
            body(
                "1. If Android shows a Restricted setting message, tap Close.\n" +
                    "2. Open Settings > Apps > ScrollDock. You can also hold the ScrollDock icon and tap App info.\n" +
                    "3. Tap the three-dot menu in the upper-right.\n" +
                    "4. Select Allow restricted settings and confirm with your PIN or fingerprint.\n" +
                    "5. Return to Settings > Accessibility > Installed apps > ScrollDock.\n" +
                    "6. Turn the service on and tap Allow."
            )
        )
        root.addView(actionButton("1. Open App info", ::openAppInfo))
        root.addView(actionButton("2. Open Accessibility settings", ::openAccessibilitySettings))

        root.addView(section("Why this is required"))
        root.addView(
            body(
                "Restricted settings are an Android security control for apps installed outside a trusted app store. " +
                    "ScrollDock cannot enable or bypass this permission itself. Only allow it when you trust the APK source."
            )
        )

        root.addView(actionButton("Open ScrollDock settings", ::openMainSettings))

        refreshStatus()
        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun advanceSetup() {
        when {
            !prefs.consentGranted -> showDisclosure()
            isServiceEnabled() -> openMainSettings()
            else -> refreshStatus()
        }
    }

    private fun showDisclosure() {
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
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setPositiveButton("Continue", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val continueButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            continueButton.isEnabled = consent.isChecked
            consent.setOnCheckedChangeListener { _, checked -> continueButton.isEnabled = checked }
            continueButton.setOnClickListener {
                prefs.consentGranted = true
                dialog.dismiss()
                refreshStatus()
            }
        }
        dialog.show()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        status.text = if (enabled) {
            "Accessibility access enabled"
        } else {
            "Restricted settings and accessibility access are still required"
        }
        status.setTextColor(if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        status.setBackgroundColor(if (enabled) 0x182E7D32 else 0x18C62828)
    }

    private fun openAppInfo() {
        openedAppInfo = true
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun openAccessibilitySettings() {
        openedAccessibility = true
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    private fun showAccessibilityNextStep() {
        if (isFinishing || isDestroyed || isServiceEnabled()) return
        AlertDialog.Builder(this)
            .setTitle("Next: enable accessibility")
            .setMessage(
                "After selecting Allow restricted settings, open Settings > Accessibility > Installed apps > " +
                    "ScrollDock. Turn the service on and tap Allow."
            )
            .setPositiveButton("Open Accessibility") { _, _ -> openAccessibilitySettings() }
            .setNeutralButton("Back to App info") { _, _ -> openAppInfo() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showStillDisabledDialog() {
        if (isFinishing || isDestroyed || isServiceEnabled()) return
        AlertDialog.Builder(this)
            .setTitle("ScrollDock is still disabled")
            .setMessage(
                "If Android blocked the switch, return to App info, tap the three-dot menu, select " +
                    "Allow restricted settings, then try Accessibility again."
            )
            .setPositiveButton("Open App info") { _, _ -> openAppInfo() }
            .setNeutralButton("Open Accessibility") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openMainSettings() {
        if (leavingSetup) return
        leavingSetup = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ScrollAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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
}
