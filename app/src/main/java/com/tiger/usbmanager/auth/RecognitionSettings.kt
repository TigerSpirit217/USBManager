package com.tiger.usbmanager.auth

import android.content.Context
import com.tiger.usbmanager.ModuleConstants

object RecognitionSettings {
    const val BACKEND_NONE = "none"
    private const val KEY_SCHEME_REVISION = "auth_scheme_revision"
    private const val KEY_SCHEME_ID = "auth_scheme_id"
    private const val KEY_SUPPORTED_REVISION = "auth_supported_revision"
    private const val KEY_SUPPORTED_DEVICE = "auth_supported_device"
    private const val KEY_SCHEME_EXECUTED = "auth_scheme_executed"
    private const val KEY_ENABLED = "auth_enabled"
    private const val KEY_TRANSITION_UNTIL = "auth_transition_until"
    private const val KEY_LAST_MODE = "auth_last_chooser_mode"
    private const val KEY_LAST_ADB = "auth_last_chooser_adb"

    data class UsbChoice(val mode: com.tiger.usbmanager.policy.UsbMode, val adb: Boolean)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        ModuleConstants.PREFS_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun schemeRevision(context: Context): String = prefs(context).getString(KEY_SCHEME_REVISION, "").orEmpty()
    fun hasExecutedScheme(context: Context): Boolean = prefs(context).getBoolean(KEY_SCHEME_EXECUTED, false)
    fun markSchemeExecution(context: Context) { prefs(context).edit().putBoolean(KEY_SCHEME_EXECUTED, true).commit() }
    fun backend(context: Context): String = prefs(context).getString(KEY_SCHEME_ID, BACKEND_NONE) ?: BACKEND_NONE
    fun isSupported(context: Context): Boolean {
        val preferences = prefs(context)
        val revision = schemeRevision(context)
        return revision.isNotEmpty() && revision == preferences.getString(KEY_SUPPORTED_REVISION, null) &&
            android.os.Build.FINGERPRINT == preferences.getString(KEY_SUPPORTED_DEVICE, null)
    }
    fun isEnabled(context: Context): Boolean = isSupported(context) && prefs(context).getBoolean(KEY_ENABLED, false)
    fun transitionUntil(context: Context): Long = prefs(context).getLong(KEY_TRANSITION_UNTIL, 0L)

    fun markTransition(context: Context, durationMs: Long = 45_000L) {
        // commit() is intentional: system_server must see this before USB teardown.
        prefs(context).edit().putLong(KEY_TRANSITION_UNTIL, System.currentTimeMillis() + durationMs).commit()
    }

    fun clearTransition(context: Context) {
        prefs(context).edit().remove(KEY_TRANSITION_UNTIL).apply()
    }

    fun recordChooserSelection(context: Context, mode: com.tiger.usbmanager.policy.UsbMode, adb: Boolean) {
        prefs(context).edit()
            .putString(KEY_LAST_MODE, mode.wireValue)
            .putBoolean(KEY_LAST_ADB, adb)
            .apply()
    }

    /** Seeds a new computer profile with the most recently applied chooser choice. */
    fun recentChooserSelection(context: Context): UsbChoice? {
        val preferences = prefs(context)
        val mode = com.tiger.usbmanager.policy.UsbMode.entries.firstOrNull {
            it.wireValue == preferences.getString(KEY_LAST_MODE, null)
        } ?: return null
        return UsbChoice(mode, preferences.getBoolean(KEY_LAST_ADB, false))
    }

    fun selectScheme(context: Context, revision: String, id: String): Boolean = prefs(context).edit()
        .putString(KEY_SCHEME_REVISION, revision).putString(KEY_SCHEME_ID, id)
        .remove(KEY_SUPPORTED_REVISION).remove(KEY_SUPPORTED_DEVICE)
        .putBoolean(KEY_ENABLED, false).putBoolean(KEY_SCHEME_EXECUTED, false).remove("auth_backend").commit()

    fun saveDetection(context: Context, revision: String, supported: Boolean) {
        if (schemeRevision(context) != revision) return
        prefs(context).edit()
            .putString(KEY_SUPPORTED_REVISION, if (supported) revision else "")
            .putString(KEY_SUPPORTED_DEVICE, android.os.Build.FINGERPRINT)
            .putBoolean(KEY_ENABLED, false).commit()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled && isSupported(context)).commit()
    }
}
