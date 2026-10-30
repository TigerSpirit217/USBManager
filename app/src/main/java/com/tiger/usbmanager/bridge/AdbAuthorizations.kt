package com.tiger.usbmanager.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.ModuleConstants

/** Only display metadata crosses this bridge; public keys remain in system_server. */
internal object AdbAuthorizations {
    const val ACTION = "${ModuleConstants.MODULE_PACKAGE}.action.ADB_AUTHORIZATIONS"
    const val PERMISSION = "${ModuleConstants.MODULE_PACKAGE}.permission.MANAGE_ADB_AUTHORIZATIONS"
    const val FINGERPRINT = "fingerprint"
    const val DEVICES = "devices"
    const val NAME = "name"
    const val OK = 1
    const val UNSUPPORTED = 2
    const val FAILED = 3
    const val LIMIT_EXCEEDED = 4
    const val DISCONNECT_REQUIRED = 5
    const val MAX_DEVICES = 200

    data class Device(val name: String, val fingerprint: String)
    data class Result(val code: Int, val devices: List<Device> = emptyList())

    /** A request has one terminal callback even if the runtime disappears. */
    fun request(context: Context, fingerprint: String? = null, callback: (Result) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        val timeout = Runnable {
            if (!completed) { completed = true; callback(Result(FAILED)) }
        }
        fun finish(result: Result) {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            callback(result)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = getResultExtras(false)
                @Suppress("DEPRECATION")
                val devices = extras?.getParcelableArrayList<Bundle>(DEVICES).orEmpty().map {
                    Device(it.getString(NAME).orEmpty(), it.getString(FINGERPRINT).orEmpty())
                }
                finish(Result(resultCode, devices))
            }
        }
        handler.postDelayed(timeout, 8_000L)
        runCatching {
            context.sendOrderedBroadcast(Intent(ACTION).apply {
                setPackage(ModuleConstants.SYSTEM_SERVER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                fingerprint?.let { putExtra(FINGERPRINT, it) }
            }, null, receiver, handler, FAILED, null, null)
        }.onFailure { finish(Result(FAILED)) }
    }
}
