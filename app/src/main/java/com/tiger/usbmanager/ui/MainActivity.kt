package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.ModuleActivationCheck
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.policy.UsbMode

class MainActivity : Activity() {
    private lateinit var activationStatusContainer: LinearLayout
    private var defaultConfigSummaryView: TextView? = null
    private var hasResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ModuleSettings.init(this)
        if (ModuleSettings.isFirstLaunchDone()) showConfigManager() else showIntro()
    }

    private fun showIntro() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            applySystemBarPadding(includeTop = true, includeBottom = true)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 32f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })
            val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("unknown")
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.intro_module_info, versionName)
                textSize = 14f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(infoCard(
                getString(R.string.intro_section_features),
                listOf(R.string.intro_feature_1, R.string.intro_feature_2, R.string.intro_feature_3, R.string.intro_feature_4,
                    R.string.intro_feature_5, R.string.intro_feature_6, R.string.intro_feature_7),
            ), verticalMargins(top = dp(8)))
            addView(infoCard(
                getString(R.string.intro_section_usage),
                listOf(R.string.intro_usage_1, R.string.intro_usage_2, R.string.intro_usage_3,
                    R.string.intro_usage_4, R.string.intro_usage_5, R.string.intro_usage_6),
            ), verticalMargins(top = dp(4)))
            addView(primaryButton(getString(R.string.intro_start)) {
                ModuleSettings.markFirstLaunchDone()
                recreate()
            }, verticalMargins(top = dp(12), bottom = 0))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg_page))
            isFillViewport = true
            addView(column)
        })
    }

    private fun infoCard(title: String, lines: List<Int>): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.on_accent_soft))
                setPadding(0, 0, 0, dp(8))
            })
            lines.forEach { res -> addView(TextView(this@MainActivity).apply {
                setText(res)
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(5), 0, dp(5))
            }) }
        })
    }

    private fun showConfigManager() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            addView(toolbar(getString(R.string.app_name), action = getString(R.string.settings_view_intro) to { showIntro() }).apply {
                applySystemBarPadding(includeTop = true)
            })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(28))
            applySystemBarPadding(includeBottom = true)
            addView(sectionLabel(getString(R.string.settings_status_section)))
            activationStatusContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(activationStatusContainer)
            addView(sectionLabel(getString(R.string.settings_module_settings)))
            addView(settingsCard())
            addView(sectionLabel(getString(R.string.auth_section_title)))
            addView(authenticationCard())
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshActivationStatus()
    }

    private fun refreshActivationStatus() {
        val previousHeight = activationStatusContainer.getChildAt(0)?.height?.takeIf { it > 0 }
        activationStatusContainer.removeAllViews()
        val loadingCard = statusCard(
            R.color.banner_loading_bg,
            R.color.banner_loading_text,
            getString(R.string.activate_checking),
            null,
            loading = true,
        )
        if (previousHeight != null) loadingCard.minimumHeight = previousHeight
        activationStatusContainer.addView(loadingCard)
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val status = runCatching { ModuleActivationCheck.check(this) }.getOrElse {
                ModuleActivationCheck.Status.Unknown(getString(R.string.activate_check_error, it.message))
            }
            handler.post { renderActivationStatus(status) }
        }.apply { name = "usb-activation-check"; isDaemon = true }.start()
    }

    private fun renderActivationStatus(status: ModuleActivationCheck.Status) {
        if (!::activationStatusContainer.isInitialized) return
        activationStatusContainer.removeAllViews()
        val view = when (status) {
            is ModuleActivationCheck.Status.Active -> statusCard(
                R.color.banner_active_bg, R.color.banner_active_text,
                getString(R.string.activate_state_active),
                getString(R.string.activate_body,
                    getString(if (status.hasUsbDeviceManagerHook) R.string.activate_hook_ok else R.string.activate_hook_fail),
                    getString(if (status.hasAdbHook) R.string.activate_hook_ok else R.string.activate_hook_fail), status.packageName),
                actions = listOf(getString(R.string.action_recheck) to { refreshActivationStatus() }),
            )
            is ModuleActivationCheck.Status.Inactive -> statusCard(
                R.color.banner_inactive_bg, R.color.banner_inactive_text,
                getString(R.string.activate_state_inactive), status.reason, actions = statusActions(),
            )
            is ModuleActivationCheck.Status.Unknown -> statusCard(
                R.color.banner_unknown_bg, R.color.banner_unknown_text,
                getString(R.string.activate_state_unknown), status.note, actions = statusActions(),
            )
        }
        activationStatusContainer.addView(view)
    }

    private fun statusActions() = listOf(
        getString(R.string.action_activation_guide) to { showActivationGuide() },
        getString(R.string.action_recheck) to { refreshActivationStatus() },
    )

    private fun statusCard(
        background: Int,
        foreground: Int,
        title: String,
        body: String?,
        loading: Boolean = false,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ): MaterialCardView = MaterialCardView(this).apply {
        setCardBackgroundColor(getColor(background))
        radius = dp(20).toFloat()
        cardElevation = 0f
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (loading) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall),
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(foreground))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            if (body != null) addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(7), 0, 0)
            })
            if (actions.isNotEmpty()) addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
                actions.forEach { action -> addView(outlinedButton(action.first, action.second)) }
            })
        })
    }

    private fun settingsCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueRow(
                getString(R.string.settings_default_usb_config),
                defaultConfigSummary(),
                onValueBound = { defaultConfigSummaryView = it },
            ) { startActivity(Intent(this@MainActivity, DefaultUsbConfigActivity::class.java)) })
            addView(divider())
            addView(toggleRow(getString(R.string.settings_disconnect_auto_off), ModuleSettings.disconnectAutoOffAdb()) {
                ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DISCONNECT_AUTO_OFF_ADB, it).apply()
            })
            addView(divider())
            addView(toggleRow(getString(R.string.settings_chooser_while_locked), ModuleSettings.chooserWhileLocked()) {
                ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_CHOOSER_WHILE_LOCKED, it).apply()
            })
            addView(divider())
            addView(valueRow(getString(R.string.action_get_logs), getString(R.string.settings_logs_description)) { showHowToGetLogs() })
        })
    }

    private fun authenticationCard(): MaterialCardView = surfaceCard().apply {
        addView(valueRow(getString(R.string.auth_entry_title), getString(R.string.auth_entry_value)) {
            startActivity(Intent(this@MainActivity, UsbAuthenticationActivity::class.java))
        })
    }

    private fun defaultConfigSummary(): String {
        val mode = getString(UsbMode.fromWire(ModuleSettings.defaultMode()).displayRes)
        val adb = getString(if (ModuleSettings.defaultAdb()) R.string.settings_adb_on else R.string.settings_adb_off)
        return getString(R.string.settings_default_usb_summary, mode, adb)
    }

    private fun valueRow(
        title: String,
        value: String,
        onValueBound: ((TextView) -> Unit)? = null,
        click: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { click() }
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text_primary)) })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 12f
            setTextColor(getColor(R.color.usb_text_secondary))
            setPadding(0, dp(3), 0, 0)
            onValueBound?.invoke(this)
        })
    }

    private fun toggleRow(title: String, checked: Boolean, changed: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(8), dp(10))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text_primary)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(SwitchMaterial(this@MainActivity).apply {
            useUsbManagerColors()
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        })
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        cornerRadius = dp(16)
        useUsbManagerPrimaryColors()
        setOnClickListener { click() }
    }

    private fun outlinedButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        useUsbManagerOutlinedColors()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        }
        setOnClickListener { click() }
    }

    private fun showActivationGuide() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.activation_guide_title)
            .setMessage(R.string.activation_guide_message).setPositiveButton(R.string.dialog_got_it, null).show()
    }

    private fun showHowToGetLogs() {
        val tv = TextView(this).apply {
            text = getString(R.string.logs_how_to_message)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_body))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.logs_how_to_title)
            .setView(ScrollView(this).apply { addView(tv) }).setPositiveButton(R.string.dialog_got_it, null).show()
    }

    override fun onResume() {
        super.onResume()
        if (hasResumed) defaultConfigSummaryView?.text = defaultConfigSummary()
        hasResumed = true
    }
}
