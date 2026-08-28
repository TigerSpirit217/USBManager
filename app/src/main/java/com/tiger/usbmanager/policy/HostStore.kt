package com.tiger.usbmanager.policy

import android.content.Context
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.bridge.UsbBridgeContract

/**
 * Local (module-app side) JSON-backed database of known USB hosts. Persists the
 * host list as a single JSON blob inside [ModuleConstants.PREFS_HOSTS].
 *
 * The system_server side does NOT touch this file directly (SELinux blocks
 * cross-app data writes); it reaches the same data through [HostProvider] via
 * [com.tiger.usbmanager.bridge.HostProviderClient].
 */
class HostStore private constructor(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        ModuleConstants.PREFS_HOSTS,
        Context.MODE_PRIVATE,
    )

    private val gson = UsbBridgeContract.GSON
    private val type = object : TypeToken<MutableList<HostInfo>>() {}.type

    fun list(): List<HostInfo> {
        val raw = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        val parsed = runCatching {
            gson.fromJson<MutableList<HostInfo>>(raw, type) ?: emptyList()
        }.onFailure {
            // Corrupt JSON — reset rather than crash.
            prefs.edit().remove(KEY_HOSTS_JSON).apply()
        }.getOrDefault(emptyList())

        // v2 and earlier stored the complete adb_keys line. Migrate it lazily to
        // the stable SHA-256 key-material fingerprint used by current sessions.
        val migrated = parsed.map { host ->
            val normalized = HostKeyFingerprint.normalize(host.hostKey)
            if (normalized != null && normalized != host.hostKey) {
                host.copy(hostKey = normalized)
            } else host
        }.distinctBy { it.hostKey }
        if (migrated != parsed) persist(migrated)
        return migrated
    }

    fun getByKey(hostKey: String): HostInfo? =
        list().firstOrNull { it.hostKey == hostKey }

    fun upsert(host: HostInfo) {
        val canonicalHost = HostKeyFingerprint.normalize(host.hostKey)
            ?.let { host.copy(hostKey = it) }
            ?: host
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.hostKey == canonicalHost.hostKey }
        if (idx >= 0) current[idx] = canonicalHost else current.add(canonicalHost)
        persist(current)
    }

    fun delete(hostKey: String) {
        val current = list().filterNot { it.hostKey == hostKey }
        persist(current)
    }

    fun clear() = persist(emptyList())

    private fun persist(hosts: List<HostInfo>) {
        prefs.edit().putString(KEY_HOSTS_JSON, gson.toJson(hosts)).apply()
    }

    companion object {
        private const val KEY_HOSTS_JSON = "hosts_json"

        @Volatile private var instance: HostStore? = null

        fun get(context: Context): HostStore =
            instance ?: synchronized(this) {
                instance ?: HostStore(context.applicationContext).also { instance = it }
            }
    }
}
