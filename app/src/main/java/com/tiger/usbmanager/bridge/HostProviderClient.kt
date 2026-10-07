package com.tiger.usbmanager.bridge

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.policy.UsbMode

/**
 * system_server-side client for [HostProvider]. Settings, authentication sessions,
 * and the pending-apply mailbox are reached through [ContentResolver.call].
 */
class HostProviderClient(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val gson = UsbBridgeContract.GSON

    /**
     * Returns the given extras (or a new Bundle) stamped with the shared
     * BRIDGE_TOKEN so the exported provider can authorise mutating calls.
     */
    private fun tokenFor(existing: Bundle?): Bundle = (existing ?: Bundle()).apply {
        putString(UsbBridgeContract.KEY_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
    }

    fun startAuth(id: Long): Boolean {
        val extras = tokenFor(Bundle().apply { putLong(UsbBridgeContract.KEY_AUTH_SESSION, id) })
        return runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_START_AUTH, null, extras)
                ?.getBoolean(UsbBridgeContract.KEY_RESULT, false) == true
        }.onFailure { Log.w(TAG, "[CLIENT] startAuth failed", it) }.getOrDefault(false)
    }

    /** Null means the worker is still authenticating this session. */
    fun authResult(id: Long): ProviderAuthResult? {
        val extras = tokenFor(Bundle().apply { putLong(UsbBridgeContract.KEY_AUTH_SESSION, id) })
        val bundle = resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_AUTH_RESULT, null, extras)
            ?: return null
        if (!bundle.getBoolean(UsbBridgeContract.KEY_AUTH_READY, false)) return null
        return ProviderAuthResult(
            bundle.getString(UsbBridgeContract.KEY_AUTH_STATUS).orEmpty(),
            bundle.getString(UsbBridgeContract.KEY_AUTH_ID).orEmpty(),
            bundle.getString(UsbBridgeContract.KEY_AUTH_LABEL).orEmpty(),
            UsbMode.entries.firstOrNull { it.wireValue == bundle.getString(UsbBridgeContract.KEY_AUTH_MODE) },
            bundle.getBoolean(UsbBridgeContract.KEY_AUTH_ADB, false),
            bundle.getString(UsbBridgeContract.KEY_AUTH_DETAIL).orEmpty(),
        )
    }

    fun cancelAuth(id: Long) {
        val extras = tokenFor(Bundle().apply { putLong(UsbBridgeContract.KEY_AUTH_SESSION, id) })
        resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_CANCEL_AUTH, null, extras)
    }

    /** Full settings snapshot needed by system_server. */
    fun settings(): ModuleSettingsSnapshot {
        Log.v(TAG, "[CLIENT] settings() call")
        val result = runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_SETTINGS, null, null)
        }.onFailure { Log.e(TAG, "[CLIENT] settings FAILED", it) }.getOrNull()
        val snap = ModuleSettingsSnapshot(
            defaultMode = UsbMode.fromWire(result?.getString(UsbBridgeContract.KEY_MODE)),
            defaultAdb = result?.getBoolean(UsbBridgeContract.KEY_ADB, false) ?: false,
            disconnectAutoOffAdb = result?.getBoolean(UsbBridgeContract.KEY_DISCONNECT_AUTO_OFF, true) ?: true,
            chooserWhileLocked = result?.getBoolean(UsbBridgeContract.KEY_CHOOSER_WHILE_LOCKED, false) ?: false,
            authEnabled = result?.getBoolean(UsbBridgeContract.KEY_AUTH_ENABLED, false) ?: false,
            authBackend = result?.getString(UsbBridgeContract.KEY_AUTH_BACKEND).orEmpty(),
            authTransitionUntilMs = result?.getLong(UsbBridgeContract.KEY_AUTH_TRANSITION_UNTIL, 0L) ?: 0L,
            gameDndEnabled = result?.getBoolean(UsbBridgeContract.KEY_GAME_DND_ENABLED, false) ?: false,
            gameDndPackages = result?.getStringArrayList(UsbBridgeContract.KEY_GAME_DND_PACKAGES)?.toSet().orEmpty(),
            gameDndUseDefault = result?.getBoolean(UsbBridgeContract.KEY_GAME_DND_USE_DEFAULT, false) ?: false,
        )
        Log.i(TAG, "[CLIENT] settings → $snap")
        return snap
    }

    // ---- Pending-apply fallback channel ----

    /**
     * Written by the chooser activity (UsbConfigSender) so system_server can
     * retrieve it even if ACTION_APPLY_USB_CONFIG broadcast was dropped.
     * Returns true if the provider acknowledged the write.
     */
    fun putPendingApply(payload: PendingApplyPayload): Boolean {
        val json = runCatching { gson.toJson(payload) }.getOrDefault(null) ?: return false
        val extras = tokenFor(Bundle().apply { putString(UsbBridgeContract.KEY_PENDING_JSON, json) })
        val result = runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_PUT_PENDING_APPLY, null, extras)
        }.onFailure { Log.e(TAG, "[CLIENT] putPendingApply FAILED", it) }.getOrNull()
        val ok = result?.getBoolean(UsbBridgeContract.KEY_RESULT, false) == true
        Log.i(TAG, "[CLIENT] putPendingApply ok=$ok len=${json.length}")
        return ok
    }

    /**
     * Polled by UsbStateWatcher after launching the chooser. Returns the payload
     * left by the chooser UI and clears it atomically, or null if nothing pending.
     */
    fun getAndClearPendingApply(): PendingApplyPayload? {
        val result = runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY, null, tokenFor(null))
        }.onFailure { Log.e(TAG, "[CLIENT] getAndClearPendingApply FAILED", it) }.getOrNull()
        val json = result?.getString(UsbBridgeContract.KEY_RESULT) ?: return null
        val payload = runCatching { gson.fromJson(json, PendingApplyPayload::class.java) }
            .onFailure { Log.w(TAG, "[CLIENT] getAndClearPendingApply parse FAILED", it) }
            .getOrNull()
        Log.i(TAG, "[CLIENT] getAndClearPendingApply → ${payload != null}")
        return payload
    }

    private companion object {
        const val TAG = "USBManager"
    }
}

data class ProviderAuthResult(val status: String, val id: String, val label: String,
                              val mode: UsbMode?, val adb: Boolean, val detail: String)

/**
 * Full payload of the user's choice in the chooser, transported via the
 * ContentProvider pending-apply mailbox. Mirrors the extras of APPLY_USB_CONFIG
 * broadcast so the watcher can treat both paths identically.
 */
data class PendingApplyPayload(
    val modeWire: String,
    val adb: Boolean,
    /** Used to reset outcome/confirmed timers in UsbStateWatcher. */
    val confirmed: Boolean = true,
)

/** Read-only settings snapshot delivered to system_server. */
data class ModuleSettingsSnapshot(
    val defaultMode: UsbMode,
    val defaultAdb: Boolean,
    val disconnectAutoOffAdb: Boolean,
    /** Whether the USB mode chooser may show while the device is locked. */
    val chooserWhileLocked: Boolean = false,
    val authEnabled: Boolean = false,
    val authBackend: String = "",
    val authTransitionUntilMs: Long = 0L,
    val gameDndEnabled: Boolean = false,
    val gameDndPackages: Set<String> = emptySet(),
    val gameDndUseDefault: Boolean = false,
)
