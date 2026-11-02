package com.tiger.usbmanager

/**
 * Cross-process constants: package name, broadcast actions, intent extras and the
 * ContentProvider authority used to share module settings and the pending-apply
 * mailbox between the system_server hook and the module app UI.
 */
object ModuleConstants {

    /** ApplicationId of this module APK. */
    const val MODULE_PACKAGE = "com.tiger.usbmanager"

    /** system_server package name (scope target). */
    const val SYSTEM_SERVER_PACKAGE = "android"
    const val CONTROL_PERMISSION = "$MODULE_PACKAGE.permission.CONTROL_USB"
    const val ACTION_QUERY_HEALTH = "$MODULE_PACKAGE.action.QUERY_HEALTH"
    const val RESULT_HEALTH_AVAILABLE = 4
    const val EXTRA_REQUEST_ID = "request_id"

    /** Chooser activity launched when a user decision is required. */
    const val CHOOSER_ACTIVITY = "$MODULE_PACKAGE.ui.UsbChooserActivity"

    // ---- Bridge broadcast actions (app -> system_server runtime receiver) ----

    /** Apply a USB configuration chosen by the user. */
    const val ACTION_APPLY_USB_CONFIG = "$MODULE_PACKAGE.action.APPLY_USB_CONFIG"
    /** Ordered request from the status page, acknowledged by the live runtime. */
    const val ACTION_APPLY_LIVE_CONFIG = "$MODULE_PACKAGE.action.APPLY_LIVE_CONFIG"
    const val EXTRA_ADB_ONLY = "adb_only"
    const val RESULT_LIVE_APPLIED = 1
    const val RESULT_LIVE_FAILED = 2

    /** Ordered query of current framework functions, including the zero/charging mask. */
    const val ACTION_QUERY_STATUS = "$MODULE_PACKAGE.action.QUERY_STATUS"
    const val RESULT_STATUS_AVAILABLE = 3
    const val EXTRA_USB_FUNCTIONS = "current_usb_functions"
    const val ACTION_REQUEST_PACKAGE_NAMES = "$MODULE_PACKAGE.action.REQUEST_PACKAGE_NAMES"

    /** Sent when the chooser activity is closed (via any path: confirmed / cancelled / dismissed). */
    const val ACTION_CHOOSER_CLOSED = "$MODULE_PACKAGE.action.CHOOSER_CLOSED"

    /**
     * Sent from system_server to the module app when the USB cable is unplugged while
     * the chooser is still on screen. The chooser activity listens for it and finishes
     * itself, so a stale chooser window doesn't linger after the cable is pulled.
     * Carries [EXTRA_TOKEN] so the activity only closes the dialog it owns.
     */
    const val ACTION_DISMISS_CHOOSER = "$MODULE_PACKAGE.action.DISMISS_CHOOSER"

    /** Wire marker only. Sender signature permissions and provider UID checks enforce trust. */
    const val BRIDGE_TOKEN = "Z4U~{K9m!s[=qB7;Rc*T|6p@Wd%G<hV}"

    // ---- Intent extras ----

    const val EXTRA_USB_MODE = "usb_mode"
    const val EXTRA_ADB_ENABLED = "adb_enabled"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TOKEN = "token"
    /** Value: "confirmed" | "cancelled" | "dismissed". */
    const val EXTRA_OUTCOME = "outcome"
    /** The bridge token shared constant; receiver side drops the intent on mismatch. */
    const val EXTRA_BRIDGE_TOKEN = "bridge_token"

    // ---- Host ContentProvider ----

    const val HOST_AUTHORITY = "$MODULE_PACKAGE.hosts"

    // ---- SharedPreferences file names ----

    /** Legacy known-host database (JSON blob). Retained only for one-time cleanup
     *  migration: the identification/memory feature has been removed. */
    const val PREFS_HOSTS = "usbmanager_hosts"

    /** system_server-side fallback copy of the legacy known-host database.
     *  Retained only for one-time cleanup migration. */
    const val PREFS_HOSTS_FALLBACK = "usbmanager_hosts_fallback"

    /** Holds generic module settings (default mode, auto-off, etc.). */
    const val PREFS_SETTINGS = "usbmanager_settings"
}
