package com.tiger.usbmanager.bridge

import android.content.BroadcastReceiver
import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.policy.UsbMode
import com.tiger.usbmanager.policy.UsbStatusMode

data class CurrentUsbSnapshot(val connected: Boolean?, val mode: UsbMode?, val adb: Boolean?)

/** Observe actual framework state, never the saved defaults or a requested value. */
class CurrentUsbState(private val context: Context, private val changed: (CurrentUsbSnapshot) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var usbIntent: Intent? = null
    private var observing = false
    private var receiverRegistered = false
    private var adbObserverRegistered = false
    private var queryGeneration = 0
    private var snapshot = CurrentUsbSnapshot(null, null, null)
    private val poll = object : Runnable {
        override fun run() {
            if (!observing) return
            refresh()
            handler.postDelayed(this, 1_000L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            usbIntent = intent
            refresh()
        }
    }
    private val adbObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    fun start() {
        if (observing) return
        observing = true
        runCatching {
            usbIntent = ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_STATE),
                ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }.onFailure { android.util.Log.w("USBManager", "USB state observation unavailable", it) }
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false, adbObserver)
            adbObserverRegistered = true
        }.onFailure { android.util.Log.w("USBManager", "ADB state observation unavailable", it) }
        refresh()
        handler.postDelayed(poll, 1_000L)
    }

    fun stop() {
        if (!observing) return
        if (receiverRegistered) context.unregisterReceiver(receiver)
        if (adbObserverRegistered) context.contentResolver.unregisterContentObserver(adbObserver)
        receiverRegistered = false
        adbObserverRegistered = false
        observing = false
        queryGeneration++
        handler.removeCallbacks(poll)
    }

    fun refresh() {
        // Refresh the sticky state as well when returning from a dialog/settings.
        val current = runCatching { context.registerReceiver(null, IntentFilter(ACTION_USB_STATE)) }.getOrNull() ?: usbIntent
        usbIntent = current
        val mode = current?.let { state ->
            val enabled = (UsbMode.entries.map { it.wireValue } +
                listOf("accessory", "audio_source", "mass_storage", "ncm", "uvc"))
                .filter { state.getBooleanExtra(it, false) }.toSet()
            UsbStatusMode.fromBroadcast(state.getStringExtra("functions"), enabled,
                if (state.hasExtra("unlocked")) state.getBooleanExtra("unlocked", false) else null)
        }
        val adb = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) != 0 }
            .getOrNull()
        val fallback = CurrentUsbSnapshot(current?.getBooleanExtra("connected", false), mode, adb)
        val request = ++queryGeneration
        // Query the framework mask before publishing; avoid briefly flashing an advertised MTP flag.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (request != queryGeneration) return
                val functions = if (resultCode == ModuleConstants.RESULT_STATUS_AVAILABLE)
                    getResultExtras(false)?.getString(ModuleConstants.EXTRA_USB_FUNCTIONS) else null
                publish(if (functions != null) fallback.copy(mode = UsbStatusMode.fromFunctions(functions)) else fallback)
            }
        }
        runCatching {
            sendOrdered(context, Intent(ModuleConstants.ACTION_QUERY_STATUS).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
            }, receiver)
        }.onFailure { if (request == queryGeneration) publish(fallback) }
    }

    private fun publish(current: CurrentUsbSnapshot) {
        if (snapshot != current) {
            snapshot = current
            changed(current)
        }
    }

    companion object {
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

        /** The caller must also observe the resulting state: an ACK is not a state snapshot. */
        fun apply(context: Context, mode: UsbMode, adb: Boolean, adbOnly: Boolean, result: (Int) -> Unit) {
            val intent = Intent(ModuleConstants.ACTION_APPLY_LIVE_CONFIG).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
                putExtra(ModuleConstants.EXTRA_USB_MODE, mode.wireValue)
                putExtra(ModuleConstants.EXTRA_ADB_ENABLED, adb)
                putExtra(ModuleConstants.EXTRA_ADB_ONLY, adbOnly)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { result(resultCode) }
            }
            sendOrdered(context, intent, receiver)
        }

        private fun sendOrdered(context: Context, intent: Intent, receiver: BroadcastReceiver) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                context.sendOrderedBroadcast(intent, null, options, receiver, Handler(Looper.getMainLooper()), 0, null, null)
            } else {
                context.sendOrderedBroadcast(intent, null, receiver, Handler(Looper.getMainLooper()), 0, null, null)
            }
        }
    }
}
