package com.tiger.usbmanager.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.ModuleSettings

/**
 * ContentProvider in the module app process, queried by system_server via
 * [ContentResolver.call]. After the identification/memory feature was removed it
 * only serves the module-settings snapshot and the pending-apply mailbox.
 */
class HostProvider : ContentProvider() {

    /** In-memory pending apply (written by chooser UI, polled by system_server watcher).
     *  Volatile so binder thread reads are visible; single slot because there is at
     *  most one chooser live at a time. */
    @Volatile private var pendingApplyJson: String? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ModuleSettings.init(ctx)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return runCatching {
            // ----- UID authorization (authoritative gate, applies to ALL methods). -----
            val callingUid = android.os.Binder.getCallingUid()
            if (callingUid != android.os.Process.SYSTEM_UID &&
                callingUid != android.os.Process.myUid()
            ) {
                Log.w(TAG, "call($method) blocked: uid=$callingUid not authorized")
                return@runCatching null
            }

            // Mutating operations additionally require the shared BRIDGE_TOKEN.
            when (method) {
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY,
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY,
                -> {
                    val tok = extras?.getString(UsbBridgeContract.KEY_BRIDGE_TOKEN)
                    if (tok != ModuleConstants.BRIDGE_TOKEN) {
                        Log.w(TAG, "call($method) blocked: bridge-token mismatch")
                        return@runCatching null
                    }
                }
            }
            when (method) {
                UsbBridgeContract.METHOD_GET_SETTINGS -> handleGetSettings()
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY -> handlePutPendingApply(extras)
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY -> handleGetAndClearPendingApply()
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "call($method) failed", it)
        }.getOrNull()
    }

    // ---- Pending-apply fallback channel ----

    private fun handlePutPendingApply(extras: Bundle?): Bundle {
        val json = extras?.getString(UsbBridgeContract.KEY_PENDING_JSON)
            ?: return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, false) }
        pendingApplyJson = json
        Log.i(TAG, "handlePutPendingApply: jsonLen=${json.length}")
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    private fun handleGetAndClearPendingApply(): Bundle {
        val json = pendingApplyJson
        pendingApplyJson = null
        Log.i(TAG, "handleGetAndClearPendingApply: present=${json != null}")
        return Bundle().apply {
            if (json != null) putString(UsbBridgeContract.KEY_RESULT, json)
        }
    }

    private fun handleGetSettings(): Bundle {
        return Bundle().apply {
            putString(UsbBridgeContract.KEY_MODE, ModuleSettings.defaultMode())
            putBoolean(UsbBridgeContract.KEY_ADB, ModuleSettings.defaultAdb())
            putBoolean(
                UsbBridgeContract.KEY_DISCONNECT_AUTO_OFF,
                ModuleSettings.disconnectAutoOffAdb(),
            )
            putBoolean(
                UsbBridgeContract.KEY_CHOOSER_WHILE_LOCKED,
                ModuleSettings.chooserWhileLocked(),
            )
        }
    }

    // ---- Standard ContentProvider plumbing (not used; data exposed via call()) ----

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        @Suppress("unused")
        const val TAG = "HostProvider"
    }
}