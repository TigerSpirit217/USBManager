package com.tiger.usbmanager.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tiger.usbmanager.ModuleActivationCheck
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.policy.UsbMode

/**
 * Main entry point when launched from the desktop.
 *
 * - First launch: shows a full-screen feature / usage intro. Tapping "开始使用"
 *   marks the intro as seen and reloads the settings view.
 * - Subsequent launches: shows module activation status and the module settings
 *   (default mode, default ADB, auto-off, while-locked chooser).
 *
 * The device-identification & "remember this computer" feature has been removed.
 */
class MainActivity : Activity() {

    private lateinit var activationStatusContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleSettings.init(this)

        if (!ModuleSettings.isFirstLaunchDone()) {
            showIntro()
        } else {
            showConfigManager()
        }
    }

    // ------------------------------------------------------------------ Intro

    private fun showIntro() {
        val padding = dp(24)
        val topExtra = statusBarHeight()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            setPadding(padding, padding + topExtra, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        })

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("unknown")

        root.addView(TextView(this).apply {
            text = getString(R.string.intro_module_info, versionName)
            textSize = 14f
            setTextColor(getColor(R.color.text_tertiary))
            setPadding(0, 0, 0, dp(24))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.intro_section_features)
            textSize = 18f
            setTextColor(getColor(R.color.accent))
            setPadding(0, 0, 0, dp(12))
        })

        val features = listOf(
            getString(R.string.intro_feature_1),
            getString(R.string.intro_feature_2),
            getString(R.string.intro_feature_3),
            getString(R.string.intro_feature_4),
            getString(R.string.intro_feature_5),
            getString(R.string.intro_feature_6),
            getString(R.string.intro_feature_7),
        )
        features.forEach { line ->
            root.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.intro_section_usage)
            textSize = 18f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(20), 0, dp(12))
        })

        val usage = listOf(
            getString(R.string.intro_usage_1),
            getString(R.string.intro_usage_2),
            getString(R.string.intro_usage_3),
            getString(R.string.intro_usage_4),
            getString(R.string.intro_usage_5),
            getString(R.string.intro_usage_6),
        )
        usage.forEach { line ->
            root.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }

        root.addView(Button(this).apply {
            text = getString(R.string.intro_start)
            setOnClickListener {
                ModuleSettings.markFirstLaunchDone()
                recreate()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg_page))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    // -------------------------------------------------------- Config manager

    private fun showConfigManager() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
        }

        // Toolbar
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(dp(16), dp(14) + statusBarHeight(), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 20f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.settings_view_intro)
                textSize = 14f
                setTextColor(getColor(R.color.accent))
                setOnClickListener {
                    showIntro()
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
        })

        // Module activation status banner (below toolbar, above scroll)
        activationStatusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        root.addView(activationStatusContainer)

        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Module settings section
        column.addView(sectionHeader(getString(R.string.settings_module_settings)))
        column.addView(settingsCard())

        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        setContentView(root)
        refreshActivationStatus()
    }

    /** Background-check module activation and render the banner on the UI thread. */
    private fun refreshActivationStatus() {
        activationStatusContainer.removeAllViews()
        val loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.banner_loading_bg))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = margin()
        }
        loadingRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            setPadding(0, 0, dp(10), 0)
        })
        loadingRow.addView(TextView(this).apply {
            text = getString(R.string.activate_checking)
            textSize = 14f
            setTextColor(getColor(R.color.banner_loading_text))
        })
        activationStatusContainer.addView(loadingRow)

        val handler = Handler(Looper.getMainLooper())
        Thread {
            val status = runCatching { ModuleActivationCheck.check(this) }
                .getOrElse { t ->
                    ModuleActivationCheck.Status.Unknown(getString(R.string.activate_check_error, t.message))
                }
            handler.post { renderActivationStatus(status) }
        }.apply { name = "usb-activation-check"; isDaemon = true }.start()
    }

    private fun renderActivationStatus(status: ModuleActivationCheck.Status) {
        activationStatusContainer.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = margin()
        }
        val title = TextView(this).apply { textSize = 15f }
        val body = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        when (status) {
            is ModuleActivationCheck.Status.Active -> {
                card.setBackgroundColor(getColor(R.color.banner_active_bg))
                title.text = getString(R.string.activate_state_active)
                title.setTextColor(getColor(R.color.banner_active_text))
                body.text = getString(
                    R.string.activate_body,
                    getString(if (status.hasUsbDeviceManagerHook) R.string.activate_hook_ok else R.string.activate_hook_fail),
                    getString(if (status.hasAdbHook) R.string.activate_hook_ok else R.string.activate_hook_fail),
                    status.packageName,
                )
                btnRow.addView(recheckButton())
            }
            is ModuleActivationCheck.Status.Inactive -> {
                card.setBackgroundColor(getColor(R.color.banner_inactive_bg))
                title.text = getString(R.string.activate_state_inactive)
                title.setTextColor(getColor(R.color.banner_inactive_text))
                body.text = status.reason
                btnRow.addView(openLsposedGuideButton())
                btnRow.addView(recheckButton())
            }
            is ModuleActivationCheck.Status.Unknown -> {
                card.setBackgroundColor(getColor(R.color.banner_unknown_bg))
                title.text = getString(R.string.activate_state_unknown)
                title.setTextColor(getColor(R.color.banner_unknown_text))
                body.text = status.note
                btnRow.addView(openLsposedGuideButton())
                btnRow.addView(recheckButton())
            }
        }
        card.addView(title)
        card.addView(body)
        card.addView(btnRow)
        activationStatusContainer.addView(card)
    }

    private fun recheckButton(): Button = Button(this).apply {
        text = getString(R.string.action_recheck)
        setOnClickListener { refreshActivationStatus() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
    }

    private fun openLsposedGuideButton(): Button = Button(this).apply {
        text = getString(R.string.action_activation_guide)
        setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.activation_guide_title)
                .setMessage(getString(R.string.activation_guide_message))
                .setPositiveButton(R.string.dialog_got_it, null)
                .show()
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::activationStatusContainer.isInitialized) refreshActivationStatus()
    }

    // ------------------------------------------------------- Settings card

    private fun settingsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.bg_card))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = margin()

        addView(row(getString(R.string.settings_default_mode), ModuleSettings.defaultMode().uppercase()) {
            showModePicker()
        })
        addView(toggleRow(getString(R.string.settings_default_adb), ModuleSettings.defaultAdb()) { checked ->
            ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DEFAULT_ADB, checked).apply()
        })
        addView(toggleRow(getString(R.string.settings_disconnect_auto_off), ModuleSettings.disconnectAutoOffAdb()) { checked ->
            ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DISCONNECT_AUTO_OFF_ADB, checked).apply()
        })
        addView(toggleRow(getString(R.string.settings_chooser_while_locked), ModuleSettings.chooserWhileLocked()) { checked ->
            ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_CHOOSER_WHILE_LOCKED, checked).apply()
        })
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.action_get_logs)
            setOnClickListener { showHowToGetLogs() }
        })
    }

    // ------------------------------------------------------- Log viewer (replaced by how-to)

    private fun showHowToGetLogs() {
        val padding = dp(16)
        val tv = TextView(this).apply {
            text = getString(R.string.logs_how_to_message)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_body))
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_how_to_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_got_it, null)
            .show()
    }

    private fun showModePicker() {
        val labels = UsbMode.entries.map { getString(it.displayRes) }.toTypedArray()
        val current = UsbMode.fromWire(ModuleSettings.defaultMode())
        val checked = UsbMode.entries.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_default_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = UsbMode.entries[which]
                ModuleSettings.prefs().edit().putString(ModuleSettings.KEY_DEFAULT_MODE, mode.wireValue).apply()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------- Helpers

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun row(title: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.text_subtitle))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(getColor(R.color.accent))
            })
        }

    private fun toggleRow(title: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.text_subtitle))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(CheckBox(this@MainActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }

    private fun margin(): ViewGroup.MarginLayoutParams =
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }
}