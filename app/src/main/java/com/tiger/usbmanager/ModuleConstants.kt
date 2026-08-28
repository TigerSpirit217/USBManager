package com.tiger.usbmanager

/**
 * Cross-process constants: package name, broadcast actions, intent extras and the
 * ContentProvider authority used to share the host database between the
 * system_server hook and the module app UI.
 */
object ModuleConstants {

    /** ApplicationId of this module APK. */
    const val MODULE_PACKAGE = "com.tiger.usbmanager"

    /** system_server package name (scope target). */
    const val SYSTEM_SERVER_PACKAGE = "android"

    /** Chooser activity launched when a user decision is required. */
    const val CHOOSER_ACTIVITY = "$MODULE_PACKAGE.ui.UsbChooserActivity"

    // ---- Bridge broadcast actions (app -> system_server runtime receiver) ----

    /** Apply a USB configuration chosen by the user. */
    const val ACTION_APPLY_USB_CONFIG = "$MODULE_PACKAGE.action.APPLY_USB_CONFIG"

    /** Query current USB host / status (debug aid). */
    const val ACTION_QUERY_STATUS = "$MODULE_PACKAGE.action.QUERY_STATUS"

    /** Sent when the chooser activity is closed (via any path: confirmed / cancelled / dismissed). */
    const val ACTION_CHOOSER_CLOSED = "$MODULE_PACKAGE.action.CHOOSER_CLOSED"

    /**
     * Token embedded in every bridge broadcast / provider call (app ⇄ system_server)
     * so the receiver / provider can drop spoofed traffic.
     *
     * Why a token in the extras instead of a custom permission string:
     *   Custom `signature|privileged` permissions declared in AndroidManifest
     * are granted to holders of our app certificate — but `system_server`
     * (uid 1000) is signed with the PLATFORM key, not ours, so it would NEVER
     * hold `BRIDGE_PERMISSION`. A receiver registered with
     * `registerReceiver(receiver, filter, "com.tiger.usbmanager.permission.BRIDGE", null)`
     * (or a provider with `android:permission="...BRIDGE"`) would therefore refuse
     * every broadcast we send — which is what happened. The provider no longer
     * declares a signature permission for this reason.
     *
     * Since we don't use `permission` on the receiver / provider side, we MUST include a
     * per-build shared secret. Any third-party app that wants to impersonate
     * the chooser must know this constant, which is only obtainable by
     * decompiling this specific APK build.
     */
    const val BRIDGE_TOKEN = "Z4U~{K9m!s[=qB7;Rc*T|6p@Wd%G<hV}"

    // ---- Intent extras ----

    const val EXTRA_HOST_KEY = "host_key"
    const val EXTRA_HOST_NAME = "host_name"
    const val EXTRA_USB_MODE = "usb_mode"
    const val EXTRA_ADB_ENABLED = "adb_enabled"
    const val EXTRA_REMEMBER = "remember"
    const val EXTRA_AUTO = "auto"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TOKEN = "token"
    /** Value: "confirmed" | "cancelled" | "dismissed". */
    const val EXTRA_OUTCOME = "outcome"
    /** The bridge token shared constant; receiver side drops the intent on mismatch. */
    const val EXTRA_BRIDGE_TOKEN = "bridge_token"

    // ---- Host ContentProvider ----

    const val HOST_AUTHORITY = "$MODULE_PACKAGE.hosts"

    // ---- SharedPreferences file names ----

    /** Holds the known-host database (JSON blob). */
    const val PREFS_HOSTS = "usbmanager_hosts"

    /** Holds generic module settings (default mode, auto-off, etc.). */
    const val PREFS_SETTINGS = "usbmanager_settings"
}
