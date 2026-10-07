package com.tiger.usbmanager

import android.content.Context
import android.content.SharedPreferences
import com.tiger.usbmanager.policy.UsbMode

/**
 * Generic module settings (default mode, default ADB, disconnect auto-off toggle).
 *
 * Both the module app and system_server read these through the same file. The
 * module app accesses it directly; system_server reaches it via the HostProvider
 * ContentProvider call surface (METHOD_GET_SETTINGS).
 */
object ModuleSettings {

    const val KEY_DEFAULT_MODE = "default_mode"
    const val KEY_DEFAULT_ADB = "default_adb"
    const val KEY_DISCONNECT_AUTO_OFF_ADB = "disconnect_auto_off_adb"
    /** Whether to show the USB mode chooser while the device is locked. Default OFF:
     *  the chooser is deferred until the user unlocks. */
    const val KEY_CHOOSER_WHILE_LOCKED = "chooser_while_locked"
    const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
    const val KEY_GAME_DND_ENABLED = "game_dnd_enabled"
    const val KEY_GAME_DND_PACKAGES = "game_dnd_packages"
    const val KEY_GAME_DND_ACTION = "game_dnd_action"
    const val KEY_GAME_DND_SHOW_SYSTEM_APPS = "game_dnd_show_system_apps"

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
        prefs().getString(KEY_DEFAULT_MODE, UsbMode.CHARGING.wireValue) ?: UsbMode.CHARGING.wireValue

    fun defaultAdb(): Boolean = prefs().getBoolean(KEY_DEFAULT_ADB, false)

    fun disconnectAutoOffAdb(): Boolean =
        prefs().getBoolean(KEY_DISCONNECT_AUTO_OFF_ADB, true)

    /** Whether the USB mode chooser may appear while the screen is locked. */
    fun chooserWhileLocked(): Boolean =
        prefs().getBoolean(KEY_CHOOSER_WHILE_LOCKED, false)

    fun isFirstLaunchDone(): Boolean = prefs().getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun gameDndEnabled(): Boolean = prefs().getBoolean(KEY_GAME_DND_ENABLED, false)
    fun gameDndPackages(): Set<String> = prefs().getStringSet(KEY_GAME_DND_PACKAGES, emptySet()).orEmpty().toSet()
    fun gameDndUsesDefault(): Boolean = prefs().getString(KEY_GAME_DND_ACTION, "charging") == "default"

    fun markFirstLaunchDone() {
        prefs().edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }
}
