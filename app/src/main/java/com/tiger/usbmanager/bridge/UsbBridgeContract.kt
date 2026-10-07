package com.tiger.usbmanager.bridge

import android.net.Uri
import com.tiger.usbmanager.ModuleConstants

/**
 * Contract for the ContentProvider used by the module (pending-apply mailbox and
 * module settings) and the bridge broadcasts.
 *
 * Both the system_server hook process and the module app UI speak this contract,
 * so changes here must stay in sync on both sides (they live in the same APK, so
 * that is automatic at build time).
 */
object UsbBridgeContract {

    val HOST_URI: Uri = Uri.parse("content://${ModuleConstants.HOST_AUTHORITY}")

    /** Shared Gson used across process boundaries (bridge + provider). A single
     *  instance avoids the small per-use allocation of constructing Gson repeatedly
     *  in HotPaths like USB-connect handling. */
    val GSON: com.google.gson.Gson = com.google.gson.Gson()

    // ---- ContentProvider.call() methods ----

    /** Returns module settings needed by system_server (auto-off, defaults). */
    const val METHOD_GET_SETTINGS = "get_settings"
    const val METHOD_BEGIN_PACKAGE_NAMES = "begin_package_names"
    const val METHOD_GET_PACKAGE_REQUEST = "get_package_request"
    const val METHOD_PUBLISH_PACKAGE_NAMES = "publish_package_names"
    const val METHOD_GET_PACKAGE_NAMES = "get_package_names"
    const val METHOD_PUBLISH_PACKAGE_APPS = "publish_package_apps"
    const val METHOD_GET_PACKAGE_APPS = "get_package_apps"
    const val KEY_PACKAGE_APPS = "package_apps"
    const val KEY_PACKAGE_PAGE = "package_page"
    const val PACKAGE_PAGE_SIZE = 16
    const val KEY_PACKAGE_REQUEST = "package_request"
    const val KEY_PACKAGE_NAMES = "package_names"
    const val KEY_PACKAGE_ERROR = "package_error"
    const val KEY_PACKAGE_READY = "package_ready"
    const val KEY_GAME_DND_ENABLED = "game_dnd_enabled"
    const val KEY_GAME_DND_PACKAGES = "game_dnd_packages"
    const val KEY_GAME_DND_USE_DEFAULT = "game_dnd_use_default"

    /** extras = full pending apply payload. */
    const val METHOD_PUT_PENDING_APPLY = "put_pending_apply"

    /** Atomically read + consume. Returns Bundle with KEY_RESULT JSON, or empty. */
    const val METHOD_GET_AND_CLEAR_PENDING_APPLY = "get_and_clear_pending_apply"
    const val METHOD_START_AUTH = "start_auth"
    const val METHOD_GET_AUTH_RESULT = "get_auth_result"
    const val METHOD_CANCEL_AUTH = "cancel_auth"

    const val KEY_RESULT = "result"
    const val KEY_MODE = "mode"
    const val KEY_ADB = "adb"
    const val KEY_DISCONNECT_AUTO_OFF = "disconnect_auto_off"
    /** Whether the chooser may show while the device is locked. */
    const val KEY_CHOOSER_WHILE_LOCKED = "chooser_while_locked"
    const val KEY_AUTH_ENABLED = "auth_enabled"
    const val KEY_AUTH_BACKEND = "auth_backend"
    const val KEY_AUTH_TRANSITION_UNTIL = "auth_transition_until"
    /** Full JSON of PendingApply payload, used by put/get methods above. */
    const val KEY_PENDING_JSON = "pending_json"
    const val KEY_AUTH_SESSION = "auth_session"
    const val KEY_AUTH_READY = "auth_ready"
    const val KEY_AUTH_STATUS = "auth_status"
    const val KEY_AUTH_ID = "auth_id"
    const val KEY_AUTH_LABEL = "auth_label"
    const val KEY_AUTH_MODE = "auth_mode"
    const val KEY_AUTH_ADB = "auth_adb"
    const val KEY_AUTH_DETAIL = "auth_detail"

    /** Bundle key carrying the shared BRIDGE_TOKEN on mutating provider calls. */
    const val KEY_BRIDGE_TOKEN = "bridge_token"
}
