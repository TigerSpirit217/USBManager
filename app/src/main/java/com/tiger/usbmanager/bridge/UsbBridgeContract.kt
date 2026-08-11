package com.tiger.usbmanager.bridge

import android.net.Uri
import com.tiger.usbmanager.ModuleConstants

/**
 * Contract for the host-database ContentProvider and the bridge broadcasts.
 *
 * Both the system_server hook process and the module app UI speak this contract,
 * so changes here must stay in sync on both sides (they live in the same APK, so
 * that is automatic at build time).
 */
object UsbBridgeContract {

    val HOST_URI: Uri = Uri.parse("content://${ModuleConstants.HOST_AUTHORITY}")

    // ---- ContentProvider.call() methods ----

    /** arg = hostKey; returns HostInfo JSON or null. */
    const val METHOD_GET_HOST = "get_host"

    /** Returns a JSON array of all hosts. */
    const val METHOD_LIST_HOSTS = "list_hosts"

    /** extras[KEY_HOST_JSON] = HostInfo JSON; upserts. */
    const val METHOD_SAVE_HOST = "save_host"

    /** arg = hostKey; deletes. */
    const val METHOD_DELETE_HOST = "delete_host"

    /** Returns a JSON object {mode, adb} describing defaults for unknown hosts. */
    const val METHOD_GET_DEFAULTS = "get_defaults"

    /** Returns module settings needed by system_server (auto-off, defaults). */
    const val METHOD_GET_SETTINGS = "get_settings"

    // ---- Pending-apply fallback channel (used when broadcast APPLY_USB_CONFIG
    //      does not reach system_server: Android 14+ dispatch quotas, dynamic
    //      receiver restrictions, OEM kills). The chooser activity writes the
    //      user choice into the provider; the watcher in system_server polls it
    //      for ~30 s after launching the chooser, reads it back, and applies. ----

    /** extras = full pending apply payload. */
    const val METHOD_PUT_PENDING_APPLY = "put_pending_apply"

    /** Atomically read + consume. Returns Bundle with KEY_RESULT JSON, or empty. */
    const val METHOD_GET_AND_CLEAR_PENDING_APPLY = "get_and_clear_pending_apply"

    const val KEY_HOST_JSON = "host_json"
    const val KEY_RESULT = "result"
    const val KEY_MODE = "mode"
    const val KEY_ADB = "adb"
    const val KEY_DISCONNECT_AUTO_OFF = "disconnect_auto_off"
    /** Full JSON of PendingApply payload, used by put/get methods above. */
    const val KEY_PENDING_JSON = "pending_json"
}
