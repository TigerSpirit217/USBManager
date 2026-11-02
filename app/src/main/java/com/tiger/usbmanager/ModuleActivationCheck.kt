package com.tiger.usbmanager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Queries live hook and service readiness. Load markers and framework metadata are fallback diagnostics. */
object ModuleActivationCheck {

    /** High-level activation status returned to the UI. */
    sealed class Status {
        data class Active(
            val packageName: String,
            val hasUsbDeviceManagerHook: Boolean,
            val hasAdbHook: Boolean,
            val details: String? = null,
        ) : Status()
        /** Hook class definitely missing in system_server = user hasn't enabled scope. */
        data class Inactive(val reason: String) : Status()
        /** Could not determine (device in weird state), prompt user. */
        data class Unknown(val note: String) : Status()
    }

    fun check(context: Context): Status {
        val cr = context.contentResolver

        // The callback runs on the main thread while this check runs on its worker.
        check(Looper.myLooper() != Looper.getMainLooper())
        val health = AtomicReference<Bundle?>()
        val completed = CountDownLatch(1)
        runCatching {
            context.sendOrderedBroadcast(Intent(ModuleConstants.ACTION_QUERY_HEALTH).apply {
                setPackage(ModuleConstants.SYSTEM_SERVER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (resultCode == ModuleConstants.RESULT_HEALTH_AVAILABLE) health.set(getResultExtras(false))
                    completed.countDown()
                }
            }, Handler(Looper.getMainLooper()), 0, null, null)
            completed.await(2, TimeUnit.SECONDS)
        }
        health.get()?.let { state ->
            fun mark(key: String) = context.getString(if (state.getBoolean(key)) R.string.activate_hook_ok else R.string.activate_hook_fail)
            val details = context.getString(R.string.activation_health_details,
                mark("usb_hook"), mark("usb_service"), mark("adb_hook"), mark("adb_service"))
            return if (listOf("usb_hook", "usb_service", "adb_hook", "adb_service").all { state.getBoolean(it) })
                Status.Active(ModuleConstants.MODULE_PACKAGE, true, true, details)
            else Status.Unknown(details)
        }

        // ---- 1) System property set by onModuleLoaded in system_server.
        //    android.os.SystemProperties is a hidden API, so we use reflection.
        val propActive = getSystemProperty(PROP_ACTIVE, "0")
        Log.i(TAG, "[CHECK] system property $PROP_ACTIVE = '$propActive'")
        if (propActive == "1") {
            Log.i(TAG, "[CHECK] module loaded in system_server (property=1)")
            return Status.Unknown(context.getString(R.string.activation_runtime_unavailable))
        }

        // ---- 2) Query LSPosed service ContentProvider.
        val lsposedActive = runCatching { queryLsposedActive(cr) }.getOrDefault(false)
        if (!lsposedActive) {
            Log.w(TAG, "[CHECK] LSPosed service reports module not active")
            return Status.Inactive(context.getString(R.string.check_inactive_reason))
        }

        Log.i(TAG, "[CHECK] LSPosed says active but system property not set; module may not have loaded in system_server yet")
        return Status.Unknown(context.getString(R.string.check_unknown_note))
    }

    /**
     * Asks the canonical LSPosed "are modules with this package in scope actually loaded"
     * ContentProvider. Returns true if the package is listed as active.
     */
    private fun queryLsposedActive(cr: ContentResolver): Boolean {
        val authority = "io.github.libxposed.service"
        val uri = Uri.parse("content://$authority/modules")
        val cursor = runCatching { cr.query(uri, null, null, null, null) }.getOrNull()
            ?: return false
        cursor.use {
            val pkgCol = cursor.getColumnIndex("packageName")
            val activeCol = cursor.getColumnIndex("active")
            val scopeCol = cursor.getColumnIndex("scope")
            while (cursor.moveToNext()) {
                val pkg = if (pkgCol >= 0) cursor.getString(pkgCol) ?: "" else ""
                if (pkg != ModuleConstants.MODULE_PACKAGE) continue
                val active = if (activeCol >= 0) cursor.getInt(activeCol) == 1 else false
                val scope = if (scopeCol >= 0) cursor.getString(scopeCol) ?: "" else ""
                Log.i(TAG, "[CHECK] LSPosed module row: pkg=$pkg active=$active scope=$scope")
                // libxposed API 102 uses the special "system" scope for system_server;
                // legacy "android" is also accepted for backwards compatibility.
                if (active && ("system" in scope || "android" in scope || scope.isBlank())) return true
            }
        }
        return false
    }

    private const val TAG = "USBManager"
    private const val PROP_ACTIVE = "sys.usbmanager.active"

    /** Reflective accessor for the hidden android.os.SystemProperties.get(key, def). */
    private fun getSystemProperty(key: String, def: String): String {
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val getMethod = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, def) as? String ?: def
        }.getOrDefault(def)
    }
}
