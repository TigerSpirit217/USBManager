package com.tiger.usbmanager.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.view.WindowCompat
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.auth.KnownComputer
import com.tiger.usbmanager.auth.RecognitionSettings
import com.tiger.usbmanager.auth.RootAuthManager
import com.tiger.usbmanager.policy.UsbMode
import java.text.DateFormat
import java.util.Date

class UsbAuthenticationActivity : Activity() {
    private lateinit var content: LinearLayout
    private var settingsCardView: MaterialCardView? = null
    private var transitionRow: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        render()
    }

    private fun render(message: String? = null) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            addView(toolbar(getString(R.string.auth_page_title), back = { finish() }).apply {
                applySystemBarPadding(includeTop = true)
            })
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(28))
            applySystemBarPadding(includeBottom = true)
        }
        transitionRow = null
        settingsCardView = null
        content.apply {
            addView(introCard())
            if (message != null) addView(messageCard(message), verticalMargins(top = dp(12), bottom = 0))
            if (!RecognitionSettings.isSupported(this@UsbAuthenticationActivity)) {
                addView(primaryButton(getString(R.string.auth_detect)) { runDetection() }, verticalMargins(top = dp(18)))
            } else {
                addView(messageCard(getString(R.string.auth_supported)), verticalMargins(top = dp(12), bottom = 0))
                addView(sectionLabel(getString(R.string.auth_section_title)))
                val settings = settingsCard()
                settingsCardView = settings
                addView(settings)
                if (RecognitionSettings.isEnabled(this@UsbAuthenticationActivity)) {
                    addView(primaryButton(getString(R.string.auth_allow_pair)) { runPairingWindow() }, verticalMargins(top = dp(14)))
                    addView(sectionLabel(getString(R.string.auth_saved_title)))
                    loadKnownComputers()
                }
            }
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun introCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@UsbAuthenticationActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            addView(TextView(this@UsbAuthenticationActivity).apply {
                text = getString(R.string.auth_experimental_badge)
                textSize = 12f
                setTextColor(getColor(R.color.on_accent_soft))
                background = roundedBackground(R.color.accent_soft, 10)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addView(TextView(this@UsbAuthenticationActivity).apply {
                text = getString(R.string.auth_usage)
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setLineSpacing(0f, 1.18f)
            })
        })
    }

    private fun settingsCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@UsbAuthenticationActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            addView(TextView(this@UsbAuthenticationActivity).apply {
                text = getString(R.string.auth_enable)
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(SwitchMaterial(this@UsbAuthenticationActivity).apply {
                useUsbManagerColors()
                isChecked = RecognitionSettings.isEnabled(this@UsbAuthenticationActivity)
                setOnCheckedChangeListener { _, enabled ->
                    RecognitionSettings.setEnabled(this@UsbAuthenticationActivity, enabled)
                    if (!enabled) Thread { runCatching { RootAuthManager.restore(this@UsbAuthenticationActivity) } }.start()
                    showToggleTransition()
                    postDelayed({
                        if (!isFinishing && !isDestroyed) render()
                    }, 360L)
                }
            })
        })
    }

    private fun showToggleTransition() {
        if (!::content.isInitialized || transitionRow != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            addView(ProgressBar(this@UsbAuthenticationActivity, null, android.R.attr.progressBarStyleSmall),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(9) })
            addView(TextView(this@UsbAuthenticationActivity).apply {
                setText(R.string.auth_applying_setting)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        transitionRow = row
        val anchorIndex = settingsCardView?.let { content.indexOfChild(it) } ?: -1
        if (anchorIndex >= 0) content.addView(row, anchorIndex + 1) else content.addView(row)
    }

    private fun runDetection() {
        showBusy(getString(R.string.auth_detecting))
        Thread {
            val result = runCatching { RootAuthManager.detect(this) }.getOrElse {
                RootAuthManager.Detection(false, detail = it.message.orEmpty(), failure = RootAuthManager.DetectionFailure.UNSUPPORTED)
            }
            runOnUiThread {
                val message = when {
                    result.supported -> R.string.auth_detect_pass
                    result.failure == RootAuthManager.DetectionFailure.ROOT_REQUIRED -> R.string.auth_detect_root_required
                    else -> R.string.auth_detect_fail
                }
                render(getString(message))
            }
        }.apply { name = "usb-auth-detection"; start() }
    }

    private fun editComputer(computer: KnownComputer, afterPairing: Boolean = false) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val name = EditText(this).apply {
            hint = getString(R.string.auth_computer_name); setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(64)); setText(computer.label)
        }
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@UsbAuthenticationActivity, android.R.layout.simple_spinner_dropdown_item, UsbMode.entries.map { getString(it.displayRes) })
            setSelection((computer.mode ?: UsbMode.fromWire(ModuleSettings.defaultMode())).ordinal)
        }
        val adb = CheckBox(this).apply {
            useUsbManagerColors()
            setText(R.string.auth_adb_label)
            isChecked = computer.adb
        }
        form.addView(name); form.addView(mode); form.addView(adb)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (afterPairing) R.string.auth_pair_customize else R.string.auth_edit)
            .setView(form).setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.auth_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                if (label.isEmpty() || label.any { char -> char.isISOControl() }) {
                    name.error = getString(R.string.auth_name_required); return@setOnClickListener
                }
                val selectedMode = UsbMode.entries[mode.selectedItemPosition]
                val selectedAdb = adb.isChecked
                dialog.dismiss(); showBusy(getString(R.string.auth_saving))
                Thread {
                    val ok = runCatching { RootAuthManager.update(this, computer.id, label, selectedMode, selectedAdb) }.getOrDefault(false)
                    runOnUiThread { render(getString(if (ok) R.string.auth_saved else R.string.auth_save_failed)) }
                }.start()
            }
        }
        dialog.show()
    }

    private fun runPairingWindow() {
        val previous = RecognitionSettings.recentChooserSelection(this)
        val mode = previous?.mode ?: UsbMode.fromWire(ModuleSettings.defaultMode())
        val adb = previous?.adb ?: ModuleSettings.defaultAdb()
        showBusy(getString(R.string.auth_pair_starting))
        Thread {
            val result = runCatching { RootAuthManager.start(this, mode, adb) }.getOrNull()
            val paired = result != null && result.status in setOf("PAIRED", "KNOWN") && result.mode != null
            runOnUiThread {
                if (!paired) { render(getString(R.string.auth_pair_failed)); return@runOnUiThread }
                checkNotNull(result)
                val computer = KnownComputer(result.id, result.label, System.currentTimeMillis(), result.mode, result.adb)
                render(getString(R.string.auth_pair_success)); editComputer(computer, afterPairing = true)
            }
        }.apply { name = "usb-auth-pair"; start() }
    }

    private fun loadKnownComputers() {
        val progress = ProgressBar(this)
        val target = content
        target.addView(progress)
        Thread {
            val computers = runCatching { RootAuthManager.list(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (content !== target || isFinishing || isDestroyed) return@runOnUiThread
                content.removeView(progress)
                if (computers.isEmpty()) content.addView(TextView(this).apply {
                    text = getString(R.string.auth_saved_empty); textSize = 14f; setTextColor(getColor(R.color.text_secondary)); setPadding(dp(4), dp(8), dp(4), dp(8))
                }) else computers.sortedByDescending { it.lastSeen }.forEach { content.addView(computerCard(it), verticalMargins(bottom = dp(10))) }
            }
        }.apply { name = "usb-auth-hosts"; start() }
    }

    private fun computerCard(computer: KnownComputer): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@UsbAuthenticationActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
            addView(TextView(this@UsbAuthenticationActivity).apply {
                text = computer.label; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(getColor(R.color.text_primary))
            })
            addView(TextView(this@UsbAuthenticationActivity).apply {
                val details = getString(R.string.auth_saved_item, computer.label, DateFormat.getDateTimeInstance().format(Date(computer.lastSeen)), computer.id.take(12)).substringAfter('\n')
                val mode = computer.mode?.let { getString(it.displayRes) } ?: getString(R.string.auth_config_missing)
                text = details + "\n" + getString(R.string.auth_saved_config, mode, if (computer.adb) getString(R.string.auth_adb_enabled) else "")
                textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(5), 0, dp(7))
            })
            addView(LinearLayout(this@UsbAuthenticationActivity).apply {
                gravity = Gravity.END
                addView(outlinedButton(getString(R.string.auth_edit)) { editComputer(computer) })
                addView(outlinedButton(getString(R.string.auth_delete)) { confirmDelete(computer) })
            })
        })
    }

    private fun confirmDelete(computer: KnownComputer) {
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.auth_delete_confirm, computer.label))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.auth_delete) { _, _ ->
                Thread {
                    val ok = runCatching { RootAuthManager.delete(this, computer.id) }.getOrDefault(false)
                    runOnUiThread { render(if (ok) null else getString(R.string.auth_save_failed)) }
                }.start()
            }.show()
    }

    private fun showBusy(text: String) {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(ProgressBar(this), verticalMargins(top = dp(36), bottom = dp(12)))
        content.addView(messageCard(text))
    }

    private fun messageCard(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(getColor(R.color.on_accent_soft))
        background = roundedBackground(R.color.accent_soft, 16)
        setPadding(dp(15), dp(12), dp(15), dp(12))
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; cornerRadius = dp(16)
        useUsbManagerPrimaryColors()
        setOnClickListener { click() }
    }

    private fun outlinedButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false
        useUsbManagerOutlinedColors()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        }
        setOnClickListener { click() }
    }
}
