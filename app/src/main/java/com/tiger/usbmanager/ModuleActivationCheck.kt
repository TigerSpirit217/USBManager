package com.tiger.usbmanager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * App-side helper that checks whether the module is active in system_server.
 *
 * Strategy (from most reliable to least):
 *  1. Check the `sys.usbmanager.active` system property — set by
 *     [UsbManagerModule.onSystemServerStarting] ONLY after the system_server hooks
 *     are confirmed installed. This is the only signal that proves the hook bundle
 *     is live; a non-persist `sys.*` name is used so the value is cleared on reboot
 *     and cannot be left stale by a previous boot when the module was enabled. Without
 *     the module injected, property stays unset → falls through to LSPosed.
 *  2. Query the LSPosed service ContentProvider (`io.github.libxposed.service`)
 *     to see if LSPosed itself reports this package as an active module with
 *     `android` in scope.
 *  3. Fallback: return [Status.Unknown] and tell the user how to check manually.
 *
 * NOTE: The HostProvider runs in the module app process, NOT in system_server,
 * so it cannot report whether the hooks are installed. We intentionally do NOT
 * query it for activation status.
 */
object ModuleActivationCheck {

    /** High-level activation status returned to the UI. */
    sealed class Status {
        data class Active(
            val packageName: String,
            val hasUsbDeviceManagerHook: Boolean,
            val hasAdbHook: Boolean,
        ) : Status()
        /** Hook class definitely missing in system_server = user hasn't enabled scope. */
        data class Inactive(val reason: String) : Status()
        /** Could not determine (device in weird state), prompt user. */
        data class Unknown(val note: String) : Status()
    }

    fun check(context: Context): Status {
        val cr = context.contentResolver

        // ---- 1) System property set by onModuleLoaded in system_server.
        //    android.os.SystemProperties is a hidden API, so we use reflection.
        val propActive = getSystemProperty(PROP_ACTIVE, "0")
        Log.i(TAG, "[CHECK] system property $PROP_ACTIVE = '$propActive'")
        if (propActive == "1") {
            Log.i(TAG, "[CHECK] module loaded in system_server (property=1)")
            return Status.Active(
                packageName = ModuleConstants.MODULE_PACKAGE,
                hasUsbDeviceManagerHook = true,
                hasAdbHook = true,
            )
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
