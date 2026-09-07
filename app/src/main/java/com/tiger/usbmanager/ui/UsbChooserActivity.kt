package com.tiger.usbmanager.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Toast
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/**
 * Dialog activity launched by the system_server hook every time a USB device-mode
 * connection is detected (the phone plugged into a computer). Shows the USB mode
 * picker and an ADB toggle, then dispatches the choice back to system_server via
 * [UsbConfigSender].
 *
 * The "remember this computer" feature was removed: there is no host identification
 * or persistence, so every connection asks again.
 *
 * Runs in the module app process — never in system_server — so a UI crash can
 * never take down the system.
 *
 * ## Outcome signalling
 *
 * No matter how the user closes this activity (+ve / -ve / back / swipe-away)
 * we send an `ACTION_CHOOSER_CLOSED` broadcast to system_server so the watcher
 * knows whether to cancel the pending-apply poll. Paths:
 *   - Positive button → outcome="confirmed" (also sends APPLY_USB_CONFIG)
 *   - Negative button → outcome="cancelled"
 *   - onBackPressed / onCancel / finish without explicit action → outcome="dismissed"
 *   Guarded by a `finished` boolean so we never send duplicates.
 */
class UsbChooserActivity : Activity() {

    private var token: Int = 0
    private var outcomeReported: Boolean = false

    /**
     * Listens for system_server's ACTION_DISMISS_CHOOSER (sent when the USB cable is
     * unplugged while we're still on screen). On receipt we finish ourselves so the
     * chooser window doesn't linger after the cable is pulled. Registration is scoped
     * to the activity's visible lifetime (onStart/onStop) and guarded by token.
     */
    private val dismissReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val receivedToken = intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0)
            if (receivedToken != token) return // not ours; ignore
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ModuleConstants.ACTION_DISMISS_CHOOSER)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dismissReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(dismissReceiver, filter)
            }
        }.onFailure { /* best-effort; chooser still closable via buttons */ }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(dismissReceiver) }.onFailure { }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preselectMode = UsbMode.fromWire(intent?.getStringExtra(ModuleConstants.EXTRA_USB_MODE))
        val rawPreselectAdb = intent?.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, false) ?: false
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0

        // This activity is exported so system_server can start it, which means any
        // third-party app can also launch it with forged extras (e.g. ADB pre-ticked)
        // as a social-engineering vector. When the caller is an untrusted app we
        // refuse to honour a pre-selected "ADB on" — the user must explicitly check
        // ADB themselves. A launch from system_server yields a null calling activity
        // (system isn't an activity), so those legitimate launches still work.
        val caller = getCallingActivity()
        val trustedPublisher = caller == null || caller.packageName == ModuleConstants.MODULE_PACKAGE
        val preselectAdb = rawPreselectAdb && trustedPublisher

        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

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

        AlertDialog.Builder(this)
            .setTitle(R.string.chooser_title)
            .setView(root)
            .setPositiveButton(R.string.chooser_confirm) { _, _ ->
                val selectedMode = UsbMode.entries.firstOrNull {
                    radioGroup.checkedRadioButtonId == it.ordinal
                } ?: UsbMode.MTP
                val adb = adbCheck.isChecked

                UsbConfigSender.apply(
                    context = this,
                    mode = selectedMode,
                    adb = adb,
                )
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
        UsbConfigSender.sendChooserClosed(this, token, outcome)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}