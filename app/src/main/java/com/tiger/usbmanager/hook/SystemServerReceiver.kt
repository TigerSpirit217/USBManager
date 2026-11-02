package com.tiger.usbmanager.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.policy.UsbMode

/** Custom commands authenticate the sender's signature; USB_STATE has a separate receiver. */
internal class SystemServerReceiver(
    private val env: HookEnv,
    private val controller: UsbController,
    private val stateListener: UsbDeviceManagerHook.StateListener?,
    private val watcher: UsbStateWatcher?,
) {
    private val packageWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "usb-package-catalog").apply { isDaemon = true }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ModuleConstants.ACTION_QUERY_HEALTH -> {
                    if (isOrderedBroadcast) setResult(ModuleConstants.RESULT_HEALTH_AVAILABLE, null, RuntimeHealth.snapshot(controller))
                }
                ModuleConstants.ACTION_APPLY_USB_CONFIG -> watcher?.applyPendingChoice(intent.getStringExtra(ModuleConstants.EXTRA_REQUEST_ID))
                ModuleConstants.ACTION_CHOOSER_CLOSED -> watcher?.onChooserClosed(
                    intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0),
                    intent.getStringExtra(ModuleConstants.EXTRA_OUTCOME).orEmpty())
                ModuleConstants.ACTION_QUERY_STATUS -> {
                    if (!isOrderedBroadcast) return
                    val functions = controller.currentFunctions() ?: return
                    setResult(ModuleConstants.RESULT_STATUS_AVAILABLE, null, android.os.Bundle().apply {
                        putString(ModuleConstants.EXTRA_USB_FUNCTIONS, functions)
                    })
                }
                ModuleConstants.ACTION_APPLY_LIVE_CONFIG -> {
                    if (!isOrderedBroadcast) return
                    val pending = goAsync()
                    val mode = UsbMode.entries.firstOrNull { it.wireValue == intent.getStringExtra(ModuleConstants.EXTRA_USB_MODE) }
                    val adb = intent.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, false)
                    val adbOnly = intent.getBooleanExtra(ModuleConstants.EXTRA_ADB_ONLY, false)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val applied = mode != null && runCatching {
                                watcher?.onLiveConfigApplying()
                                if (adbOnly) controller.setAdbEnabled(adb) else controller.applyConfig(mode, adb)
                            }.onFailure { env.error("Live USB update failed", it) }.getOrDefault(false)
                            if (applied) watcher?.onChooserApplied(mode, adb)
                            pending.resultCode = if (applied) ModuleConstants.RESULT_LIVE_APPLIED else ModuleConstants.RESULT_LIVE_FAILED
                        } finally { pending.finish() }
                    }
                }
                ModuleConstants.ACTION_REQUEST_PACKAGE_NAMES -> {
                    val request = intent.getStringExtra(com.tiger.usbmanager.bridge.UsbBridgeContract.KEY_PACKAGE_REQUEST).orEmpty()
                    packageWorker.execute {
                        runCatching { SystemPackageCatalog.publish(env, context, request) }
                            .onFailure { env.warn("Package request failed", it) }
                    }
                }
            }
        }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.hardware.usb.action.USB_STATE")
                runCatching { stateListener?.onUsbState(intent.getBooleanExtra("connected", false)) }
                    .onFailure { env.warn("USB state delivery failed", it) }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ModuleConstants.ACTION_APPLY_USB_CONFIG)
            addAction(ModuleConstants.ACTION_APPLY_LIVE_CONFIG)
            addAction(ModuleConstants.ACTION_CHOOSER_CLOSED)
            addAction(ModuleConstants.ACTION_QUERY_STATUS)
            addAction(ModuleConstants.ACTION_QUERY_HEALTH)
            addAction(ModuleConstants.ACTION_REQUEST_PACKAGE_NAMES)
        }
        // The registration permission is checked against the broadcaster, not system_server.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter,
            ModuleConstants.CONTROL_PERMISSION, null, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter, ModuleConstants.CONTROL_PERMISSION, null)
        watcher?.observePendingChoices(context)
        runCatching { AdbAuthorizationReceiver(env).register(context) }
            .onFailure { env.warn("ADB authorization receiver unavailable", it) }
        if (stateListener != null) runCatching {
            val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(usbReceiver, usbFilter)
        }.onFailure { env.warn("USB broadcast fallback unavailable", it) }
    }
}
