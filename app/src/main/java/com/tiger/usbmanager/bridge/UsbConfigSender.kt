package com.tiger.usbmanager.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.policy.UsbMode
import java.util.UUID

/** The provider owns each request; notifications and broadcasts only wake the runtime. */
object UsbConfigSender {
    /** Cancels the UI callback, not an already applied USB change. */
    fun apply(context: Context, token: Int, mode: UsbMode, adb: Boolean, result: (Boolean) -> Unit): () -> Unit {
        val client = HostProviderClient(context.applicationContext)
        val handler = Handler(Looper.getMainLooper())
        val id = UUID.randomUUID().toString()
        val payload = PendingApplyPayload(mode.wireValue, adb, true, id, token)
        var finished = false
        val deadline = SystemClock.elapsedRealtime() + 14_000L
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val answer = runCatching { client.applyResult(id) }.getOrDefault(0)
                if (answer != 0 || SystemClock.elapsedRealtime() >= deadline) {
                    finished = true
                    result(answer == ModuleConstants.RESULT_LIVE_APPLIED)
                } else handler.postDelayed(this, 250L)
            }
        }
        val written = runCatching { client.putPendingApply(payload) }.getOrDefault(false)
        if (written) {
            runCatching {
                context.sendBroadcast(Intent(ModuleConstants.ACTION_APPLY_USB_CONFIG).apply {
                    setPackage(ModuleConstants.SYSTEM_SERVER_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(ModuleConstants.EXTRA_REQUEST_ID, id)
                    putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
                })
            }.onFailure { Log.w("USBManager", "USB request wakeup failed", it) }
            handler.post(poll)
        } else handler.post { if (!finished) { finished = true; result(false) } }
        return { finished = true; handler.removeCallbacksAndMessages(null) }
    }

    fun sendChooserClosed(context: Context, token: Int, outcome: String) {
        runCatching { HostProviderClient(context).closeChooser(token) }
        runCatching {
            context.sendBroadcast(Intent(ModuleConstants.ACTION_CHOOSER_CLOSED).apply {
                setPackage(ModuleConstants.SYSTEM_SERVER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
                putExtra(ModuleConstants.EXTRA_TOKEN, token)
                putExtra(ModuleConstants.EXTRA_OUTCOME, outcome)
            })
        }.onFailure { Log.w("USBManager", "Chooser close notification failed", it) }
    }
}
