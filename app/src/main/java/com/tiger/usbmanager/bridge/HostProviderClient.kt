package com.tiger.usbmanager.bridge

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tiger.usbmanager.policy.HostInfo
import com.tiger.usbmanager.policy.UsbMode

/**
 * system_server-side client for [HostProvider]. All access to the known-host
 * database from system_server goes through here, via [ContentResolver.call].
 *
 * This avoids touching the module app's data directory directly (which SELinux
 * forbids for system_server) and keeps a single source of truth inside the
 * module app process.
 *
 * ### Fallback paths
 *
 * 1. **Known-host read fallback**: If the provider returns empty (e.g. the app
 *    process was never launched so ContentProvider doesn't exist), fall back to
 *    reading `usbmanager_hosts_fallback` SharedPreferences written by
 *    SystemServerReceiver.saveHostFallback.
 * 2. **Pending-apply channel**: Broadcast APPLY_USB_CONFIG is unreliable across
 *    process / uid boundaries on many OEM Android builds; we also write the
 *    user's choice to an in-provider "mailbox" and poll from system_server.
 */
class HostProviderClient(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val gson = Gson()

    // ---- Host lookup with SP fallback ----

    /** Returns the saved host for [hostKey], or null if unknown. */
    fun findByKey(hostKey: String): HostInfo? {
        if (hostKey.isBlank()) return null
        // 1) ContentProvider (authoritative, if app process is alive).
        val providerCallSucceeded: Boolean
        val result: Bundle? = runCatching {
            resolver.call(
                UsbBridgeContract.HOST_URI,
                UsbBridgeContract.METHOD_GET_HOST,
                hostKey,
                null,
            )
        }.onFailure {
            Log.e(TAG, "[CLIENT] findByKey FAILED uri=${UsbBridgeContract.HOST_URI} key=${hostKey.take(16)}…", it)
        }.getOrNull()
        providerCallSucceeded = (result != null)
        val json = result?.getString(UsbBridgeContract.KEY_RESULT)
        if (json != null) {
            val host = runCatching { gson.fromJson(json, HostInfo::class.java) }
                .onFailure { Log.w(TAG, "[CLIENT] findByKey JSON deserialize failed", it) }
                .getOrNull()
            if (host != null) {
                Log.i(TAG, "[CLIENT] findByKey(${hostKey.take(16)}…) → HIT via provider name=${host.name}")
                // Provider has authoritative data: make the fallback SP
                // consistent with it (so that a later crash of the APP process
                // still yields the same host/mode/adb values).
                fallbackUpsert(host)
                return host
            }
        }
        // 2a) Provider was reachable but returned empty — the host is
        //     definitively NOT saved in the APP authority. The fallback SP
        //     copy is therefore stale (user may have deleted the host via
        //     MainActivity without the system_server copy being updated).
        //     Clean it up immediately so the next call after APP death
        //     doesn't resurrect a host the user explicitly deleted.
        if (providerCallSucceeded) {
            val removed = fallbackRemove(hostKey)
            if (removed) Log.i(TAG, "[CLIENT] findByKey: provider alive but host unknown; purged stale fallback copy")
        }
        // 2b) Provider was unreachable (APP process dead / not yet started);
        //     the SP fallback is our only source of truth.
        val fallback = fallbackFindByKey(hostKey)
        Log.i(TAG, "[CLIENT] findByKey(${hostKey.take(16)}…) → ${if (fallback != null) "HIT via SP fallback name=${fallback.name}" else "MISS"} (providerReachable=$providerCallSucceeded)")
        return fallback
    }

    /**
     * Deletes the host for [hostKey] both from the authoritative Provider
     * and from our local fallback SharedPreferences copy. Returns true if at
     * least one side actually reported deletion.
     */
    fun deleteHost(hostKey: String): Boolean {
        if (hostKey.isBlank()) return false
        var ok = false
        runCatching {
            val res = resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_DELETE_HOST, hostKey, null)
            if (res?.getBoolean(UsbBridgeContract.KEY_RESULT, false) == true) ok = true
        }.onFailure { Log.e(TAG, "[CLIENT] deleteHost provider call failed", it) }
        val localRemoved = fallbackRemove(hostKey)
        Log.i(TAG, "[CLIENT] deleteHost keyLen=${hostKey.length} providerOk=$ok fallbackRemoved=$localRemoved")
        return ok || localRemoved
    }

    /** Persists (insert or update) the given host. */
    fun save(host: HostInfo): Boolean {
        val extras = Bundle().apply {
            putString(UsbBridgeContract.KEY_HOST_JSON, gson.toJson(host))
        }
        Log.i(TAG, "[CLIENT] save host: name=${host.name} auto=${host.auto} mode=${host.mode} adb=${host.adb}")
        val result = runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_SAVE_HOST, null, extras)
        }.onFailure { Log.e(TAG, "[CLIENT] save FAILED", it) }.getOrNull()
        val ok = result?.getBoolean(UsbBridgeContract.KEY_RESULT, false) == true
        // Always write the system_server-local SP copy so "remember this computer"
        // works even if the module app process is dead (ContentProvider not
        // instantiated) and the app-side SharedPreferences is therefore unreachable.
        fallbackUpsert(host)
        Log.i(TAG, "[CLIENT] save result=$ok; fallback SP copy written unconditionally")
        return ok
    }

    /** Lists all known hosts. Used for diagnostics. */
    fun listAll(): List<HostInfo> {
        var providerReachable = false
        val viaProvider = runCatching {
            val result = resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_LIST_HOSTS, null, null)
                ?: return@runCatching emptyList<HostInfo>()
            providerReachable = true
            val json = result.getString(UsbBridgeContract.KEY_RESULT) ?: return@runCatching emptyList<HostInfo>()
            val type = object : TypeToken<List<HostInfo>>() {}.type
            runCatching { gson.fromJson<List<HostInfo>>(json, type) ?: emptyList() }
                .getOrDefault(emptyList())
        }.onFailure { Log.e(TAG, "[CLIENT] listAll via provider FAILED", it) }.getOrDefault(emptyList())
        val viaFallback = fallbackListAll()
        // If the APP process is alive we have authoritative data — purge any
        // fallback-host that isn't also present in the provider list. This
        // reverses the "stale fallback" corruption that users see when they
        // edit / delete hosts via MainActivity but the system_server copy
        // keeps resurrecting old usbMode/adb values.
        if (providerReachable) {
            val providerKeys = viaProvider.mapTo(HashSet()) { it.hostKey }
            for (fb in viaFallback) {
                if (fb.hostKey !in providerKeys) {
                    fallbackRemove(fb.hostKey)
                    Log.i(TAG, "[CLIENT] listAll: purged stale fallback host=${fb.name} (absent from provider)")
                }
            }
            // Provider list IS the merged list now (fallback-only entries removed).
            Log.i(TAG, "[CLIENT] listAll → provider=${viaProvider.size} (provider alive; fallback-only entries purged)")
            return viaProvider
        }
        // Provider unreachable (APP dead); merge de-duplicates on hostKey.
        val merged = (viaProvider + viaFallback).distinctBy { it.hostKey }
        Log.i(TAG, "[CLIENT] listAll → provider=${viaProvider.size} fallback=${viaFallback.size} merged=${merged.size} (provider unreachable)")
        return merged
    }

    // ---- Fallback SP layer (system_server-owned copy of hosts) ----

    private val fallbackPrefsName = "usbmanager_hosts_fallback"

    private fun fallbackSp() = appContext.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)

    private fun fallbackKeyFor(hostKey: String): String =
        hostKey.take(32) + "_" + (hostKey.hashCode().toLong() and 0xffffffffL)

    private fun fallbackParseHost(json: String): HostInfo? {
        // Json format written by SystemServerReceiver.saveHostFallback:
        //   {"n":"name","k":"key","m":"mode","a":adbBool,"au":autoBool}
        // Map to HostInfo fields.
        return runCatching {
            val o = gson.fromJson(json, java.util.LinkedHashMap::class.java)
            val name = o["n"] as? String ?: ""
            val k = o["k"] as? String ?: return@runCatching null
            val m = o["m"] as? String ?: UsbMode.CHARGING.wireValue
            val a = o["a"] as? Boolean ?: false
            val au = o["au"] as? Boolean ?: false
            HostInfo(name = name, hostKey = k, usbMode = m, adb = a, auto = au)
        }.getOrNull()
    }

    private fun fallbackFindByKey(hostKey: String): HostInfo? {
        val prefs = runCatching { fallbackSp() }.getOrNull() ?: return null
        val key = fallbackKeyFor(hostKey)
        val json = prefs.getString(key, null) ?: return null
        return fallbackParseHost(json)
    }

    private fun fallbackListAll(): List<HostInfo> {
        val prefs = runCatching { fallbackSp() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<HostInfo>()
        for ((_, v) in prefs.all) {
            if (v is String) {
                val h = fallbackParseHost(v)
                if (h != null) out.add(h)
            }
        }
        return out
    }

    private fun fallbackUpsert(host: HostInfo) {
        runCatching {
            val prefs = fallbackSp()
            val key = fallbackKeyFor(host.hostKey)
            val json = """{"n":${jsonS(host.name)},"k":${jsonS(host.hostKey)},"m":${jsonS(host.usbMode)},"a":${host.adb},"au":${host.auto}}}"""
            prefs.edit().putString(key, json).apply()
        }
    }

    /** Returns true if a fallback SP entry was actually removed. */
    private fun fallbackRemove(hostKey: String): Boolean {
        return runCatching {
            val prefs = fallbackSp()
            val key = fallbackKeyFor(hostKey)
            if (!prefs.contains(key)) return@runCatching false
            prefs.edit().remove(key).apply()
            true
        }.getOrDefault(false)
    }

    private fun jsonS(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"', '\\' -> out.append('\\').append(ch)
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append(String.format("\\u%04x", ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Defaults for unknown hosts: (mode, adb). */
    fun defaults(): Pair<UsbMode, Boolean> {
        val result = runCatching {
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_DEFAULTS, null, null)
        }.onFailure { Log.e(TAG, "[CLIENT] defaults FAILED", it) }.getOrNull()
        val mode = UsbMode.fromWire(result?.getString(UsbBridgeContract.KEY_MODE))
        val adb = result?.getBoolean(UsbBridgeContract.KEY_ADB, false) ?: false
        Log.i(TAG, "[CLIENT] defaults → mode=$mode adb=$adb")
        return mode to adb
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
        )
        Log.i(TAG, "[CLIENT] settings → $snap")
        return snap
    }

    /** Alias for [listAll] — used by SystemServerHooks summary path. */
    fun list(): List<HostInfo> = listAll()

    // ---- Pending-apply fallback channel ----

    /**
     * Written by the chooser activity (UsbConfigSender) so system_server can
     * retrieve it even if ACTION_APPLY_USB_CONFIG broadcast was dropped.
     * Returns true if the provider acknowledged the write.
     */
    fun putPendingApply(payload: PendingApplyPayload): Boolean {
        val json = runCatching { gson.toJson(payload) }.getOrDefault(null) ?: return false
        val extras = Bundle().apply { putString(UsbBridgeContract.KEY_PENDING_JSON, json) }
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
            resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY, null, null)
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

/**
 * Full payload of the user's choice in the chooser, transported via the
 * ContentProvider pending-apply mailbox. Mirrors the extras of APPLY_USB_CONFIG
 * broadcast so the watcher can treat both paths identically.
 */
data class PendingApplyPayload(
    val modeWire: String,
    val adb: Boolean,
    val remember: Boolean,
    val auto: Boolean,
    val hostKey: String,
    val hostName: String,
    /** Used to reset outcome/confirmed timers in UsbStateWatcher. */
    val confirmed: Boolean = true,
)

/** Read-only settings snapshot delivered to system_server. */
data class ModuleSettingsSnapshot(
    val defaultMode: UsbMode,
    val defaultAdb: Boolean,
    val disconnectAutoOffAdb: Boolean,
)
