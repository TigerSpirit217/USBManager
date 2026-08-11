package com.tiger.usbmanager.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/**
 * Dialog activity launched by the system_server hook when a USB host connects and
 * a user decision is required (unknown host, or a known host without auto-apply).
 *
 * Shows the USB mode picker, an ADB toggle and a "remember this computer" option,
 * then dispatches the choice back to system_server via [UsbConfigSender].
 *
 * Runs in the module app process — never in system_server — so a UI crash can
 * never take down the system.
 *
 * ## Outcome signalling
 *
 * No matter how the user closes this activity (+ve / -ve / back / swipe-away)
 * we send an `ACTION_CHOOSER_CLOSED` broadcast to system_server so the watcher
 * can decide whether "disconnect auto-off ADB" should apply on the next unplug.
 * Paths:
 *   - Positive button → outcome="confirmed" (also sends APPLY_USB_CONFIG)
 *   - Negative button → outcome="cancelled"
 *   - onBackPressed / onCancel / finish without explicit action → outcome="dismissed"
 *   Guarded by a `finished` boolean so we never send duplicates.
 */
class UsbChooserActivity : Activity() {

    private var token: Int = 0
    private lateinit var hostKey: String
    private var outcomeReported: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hostKey = intent?.getStringExtra(ModuleConstants.EXTRA_HOST_KEY).orEmpty()
        val hostName = intent?.getStringExtra(ModuleConstants.EXTRA_HOST_NAME).orEmpty()
        val preselectMode = UsbMode.fromWire(intent?.getStringExtra(ModuleConstants.EXTRA_USB_MODE))
        val preselectAdb = intent?.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, true) ?: true
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0

        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val hostLabel = TextView(this).apply {
            text = if (hostName.isNotBlank()) {
                getString(R.string.chooser_host_label, hostName)
            } else {
                getString(R.string.chooser_host_unknown)
            }
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(hostLabel)

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        UsbMode.entries.forEach { mode ->
            RadioButton(this).apply {
                text = getString(mode.displayRes)
                id = mode.ordinal
                isChecked = mode == preselectMode
                radioGroup.addView(this)
            }
        }
        root.addView(radioGroup)

        val adbCheck = CheckBox(this).apply {
            text = getString(R.string.chooser_adb)
            isChecked = preselectAdb
        }
        root.addView(adbCheck)

        val rememberCheck = CheckBox(this).apply {
            text = getString(R.string.chooser_remember)
            // Default to NOT auto-save; the user has to explicitly opt in.
            // Follows the new default policy (see ModuleSettings).
            isChecked = false
        }
        root.addView(rememberCheck)

        val nameInput = EditText(this).apply {
            hint = getString(R.string.chooser_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(hostName)
        }
        root.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.chooser_title)
            .setView(root)
            .setPositiveButton(R.string.chooser_confirm) { _, _ ->
                val selectedMode = UsbMode.entries.firstOrNull {
                    radioGroup.checkedRadioButtonId == it.ordinal
                } ?: UsbMode.MTP
                val adb = adbCheck.isChecked
                val remember = rememberCheck.isChecked
                val name = nameInput.text?.toString().orEmpty().ifBlank { hostName }

                // 1) Apply the actual USB/ADB setting + host save.
                //    "auto" (auto-apply on next connection) is only valid when
                //    the user also asked us to remember this computer. There's
                //    no point storing "auto" for something we'll never save.
                val auto = remember && remember
                UsbConfigSender.apply(
                    context = this,
                    mode = selectedMode,
                    adb = adb,
                    remember = remember,
                    auto = auto,
                    hostKey = hostKey,
                    hostName = name,
                )
                // 2) Tell watcher: user confirmed the dialog, so don't flip ADB
                //    off if the cable is unplugged within ~30s.
                reportOutcome("confirmed")
                Toast.makeText(
                    this,
                    selectedMode.name + if (adb) " + ADB" else "",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
            .setNegativeButton(R.string.chooser_cancel) { _, _ ->
                reportOutcome("cancelled")
                finish()
            }
            .setOnCancelListener {
                reportOutcome("dismissed")
            }
            .create()
            .apply {
                window?.setGravity(Gravity.CENTER)
                show()
            }
    }

    override fun onBackPressed() {
        reportOutcome("dismissed")
        super.onBackPressed()
    }

    override fun onDestroy() {
        // Safety net: if neither positive / negative / onCancel / onBackPressed
        // fired (e.g. system killed the task), emit "dismissed" once.
        reportOutcome("dismissed")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reset outcome flag so the recreated activity can report again.
        outcomeReported = false
        recreate()
    }

    private fun reportOutcome(outcome: String) {
        if (outcomeReported) return
        outcomeReported = true
        UsbConfigSender.sendChooserClosed(this, token, outcome, hostKey)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
