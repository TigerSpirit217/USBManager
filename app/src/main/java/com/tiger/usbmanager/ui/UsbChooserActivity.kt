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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/** One-time USB mode chooser launched by the KernelSU service. */
class UsbChooserActivity : Activity() {
    private var token = 0
    private var outcomeReported = false

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) == token) finish()
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
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0
        val modes = listOf(UsbMode.CHARGING, UsbMode.MTP, UsbMode.RNDIS)
        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.chooser_manual_description)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        modes.forEach { mode ->
            radioGroup.addView(RadioButton(this).apply {
                id = mode.ordinal
                text = getString(mode.displayRes)
                isChecked = mode == UsbMode.CHARGING
            })
        }
        root.addView(radioGroup)
        val adbCheck = CheckBox(this).apply {
            text = getString(R.string.chooser_adb)
            isChecked = false
        }
        root.addView(adbCheck)

        AlertDialog.Builder(this)
            .setTitle(R.string.chooser_manual_title)
            .setView(root)
            .setPositiveButton(R.string.chooser_confirm) { _, _ ->
                val mode = modes.firstOrNull { it.ordinal == radioGroup.checkedRadioButtonId }
                    ?: UsbMode.CHARGING
                UsbConfigSender.apply(this, mode, adbCheck.isChecked, token)
                outcomeReported = true
                Toast.makeText(
                    this,
                    getString(mode.displayRes) + if (adbCheck.isChecked) " + ADB" else "",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
            .setNegativeButton(R.string.chooser_cancel) { _, _ ->
                reportClosed()
                finish()
            }
            .setOnCancelListener { reportClosed() }
            .create()
            .apply {
                window?.setGravity(Gravity.CENTER)
                show()
            }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        reportClosed()
        super.onBackPressed()
    }

    override fun onDestroy() {
        reportClosed()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        outcomeReported = false
        recreate()
    }

    private fun reportClosed() {
        if (outcomeReported) return
        outcomeReported = true
        UsbConfigSender.close(this, token)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
