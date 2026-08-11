package com.tiger.usbmanager

import android.content.Context
import android.content.SharedPreferences

/**
 * Generic module settings (default behavior for unknown hosts, auto-off toggle).
 * The known-host database itself lives in [com.tiger.usbmanager.policy.HostStore], not here.
 *
 * Both the module app and system_server read these through the same file. The
 * module app accesses it directly; system_server reaches it via the HostProvider
 * ContentProvider call surface (METHOD_GET_SETTINGS).
 */
object ModuleSettings {

    const val KEY_DEFAULT_MODE = "default_mode"
    const val KEY_DEFAULT_ADB = "default_adb"
    const val KEY_DISCONNECT_AUTO_OFF_ADB = "disconnect_auto_off_adb"
    const val KEY_LAST_HOST_KEY = "last_host_key"
    const val KEY_LAST_HOST_NAME = "last_host_name"
    const val KEY_LAST_LOAD_INFO = "last_load_info"
    const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"

    private lateinit var prefsBacking: SharedPreferences

    fun init(context: Context) {
        if (::prefsBacking.isInitialized) return
        prefsBacking = context.applicationContext.getSharedPreferences(
            ModuleConstants.PREFS_SETTINGS,
            Context.MODE_PRIVATE,
        )
    }

    fun prefs(): SharedPreferences {
        check(::prefsBacking.isInitialized) { "ModuleSettings not initialized" }
        return prefsBacking
    }

    fun defaultMode(): String =
        prefs().getString(KEY_DEFAULT_MODE, "charging") ?: "charging"

    fun defaultAdb(): Boolean = prefs().getBoolean(KEY_DEFAULT_ADB, false)

    fun disconnectAutoOffAdb(): Boolean =
        prefs().getBoolean(KEY_DISCONNECT_AUTO_OFF_ADB, true)

    fun isFirstLaunchDone(): Boolean = prefs().getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchDone() {
        prefs().edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }
}
