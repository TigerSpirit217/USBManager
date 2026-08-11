package com.tiger.usbmanager.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.policy.HostInfo
import com.tiger.usbmanager.policy.HostStore
import com.tiger.usbmanager.policy.UsbMode

/**
 * Owns the known-host database. Lives in the module app process and is queried by
 * system_server via [ContentResolver.call]. Guarded by the signature-level
 * `com.tiger.usbmanager.permission.BRIDGE` permission so only this module and the
 * system uid can reach it.
 *
 * Keeping the data here (rather than in a file under /data/system) means the
 * module app UI can read/write it locally while system_server reaches it over
 * binder — no world-readable files, no SELinux exceptions.
 */
class HostProvider : ContentProvider() {

    private lateinit var store: HostStore
    private val gson = Gson()

    /** In-memory pending apply (written by chooser UI, polled by system_server watcher).
     *  Volatile so binder thread reads are visible; single slot because there is at
     *  most one chooser live at a time. */
    @Volatile private var pendingApplyJson: String? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ModuleSettings.init(ctx)
        store = HostStore.get(ctx)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return runCatching {
            when (method) {
                UsbBridgeContract.METHOD_GET_HOST -> handleGetHost(arg)
                UsbBridgeContract.METHOD_LIST_HOSTS -> handleListHosts()
                UsbBridgeContract.METHOD_SAVE_HOST -> handleSaveHost(extras)
                UsbBridgeContract.METHOD_DELETE_HOST -> handleDeleteHost(arg)
                UsbBridgeContract.METHOD_GET_DEFAULTS -> handleGetDefaults()
                UsbBridgeContract.METHOD_GET_SETTINGS -> handleGetSettings()
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY -> handlePutPendingApply(extras)
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY -> handleGetAndClearPendingApply()
                else -> null
            }
        }.onFailure {
            Log.w("HostProvider", "call($method) failed", it)
        }.getOrNull()
    }

    // ---- Pending-apply fallback channel ----

    private fun handlePutPendingApply(extras: Bundle?): Bundle {
        val json = extras?.getString(UsbBridgeContract.KEY_PENDING_JSON)
            ?: return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, false) }
        pendingApplyJson = json
        Log.i("HostProvider", "handlePutPendingApply: jsonLen=${json.length}")
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    private fun handleGetAndClearPendingApply(): Bundle {
        val json = pendingApplyJson
        pendingApplyJson = null
        Log.i("HostProvider", "handleGetAndClearPendingApply: present=${json != null}")
        return Bundle().apply {
            if (json != null) putString(UsbBridgeContract.KEY_RESULT, json)
        }
    }

    private fun handleGetHost(hostKey: String?): Bundle {
        val host = hostKey?.takeIf { it.isNotBlank() }?.let { store.getByKey(it) }
        return Bundle().apply {
            if (host != null) {
                putString(UsbBridgeContract.KEY_RESULT, gson.toJson(host))
            }
        }
    }

    private fun handleListHosts(): Bundle {
        val list = store.list()
        return Bundle().apply {
            putString(UsbBridgeContract.KEY_RESULT, gson.toJson(list))
        }
    }

    private fun handleSaveHost(extras: Bundle?): Bundle {
        val json = extras?.getString(UsbBridgeContract.KEY_HOST_JSON) ?: return Bundle()
        val host = runCatching { gson.fromJson(json, HostInfo::class.java) }.getOrNull()
            ?: return Bundle()
        store.upsert(host)
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    private fun handleDeleteHost(hostKey: String?): Bundle {
        hostKey?.let { store.delete(it) }
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    private fun handleGetDefaults(): Bundle {
        // The provider runs in the module app process, so ModuleSettings is the
        // authoritative source. system_server asks us so it doesn't have to read
        // the settings file directly.
        return Bundle().apply {
            putString(UsbBridgeContract.KEY_MODE, ModuleSettings.defaultMode())
            putBoolean(UsbBridgeContract.KEY_ADB, ModuleSettings.defaultAdb())
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
