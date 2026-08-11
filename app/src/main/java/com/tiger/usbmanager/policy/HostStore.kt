package com.tiger.usbmanager.policy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.tiger.usbmanager.ModuleConstants

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

    private val gson = Gson()
    private val type = object : TypeToken<MutableList<HostInfo>>() {}.type

    fun list(): List<HostInfo> {
        val raw = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<MutableList<HostInfo>>(raw, type) ?: emptyList()
        }.onFailure {
            // Corrupt JSON — reset rather than crash.
            prefs.edit().remove(KEY_HOSTS_JSON).apply()
        }.getOrDefault(emptyList())
    }

    fun getByKey(hostKey: String): HostInfo? =
        list().firstOrNull { it.hostKey == hostKey }

    fun upsert(host: HostInfo) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.hostKey == host.hostKey }
        if (idx >= 0) current[idx] = host else current.add(host)
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
