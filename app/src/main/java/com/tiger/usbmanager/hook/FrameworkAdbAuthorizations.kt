package com.tiger.usbmanager.hook

import com.tiger.usbmanager.bridge.AdbAuthorizations

/** Reflect only the framework contract; never edit adb_keys or clear all authorizations. */
internal object FrameworkAdbAuthorizations {
    private val fingerprintPattern = Regex("(?:[0-9A-Fa-f]{2}:){15}[0-9A-Fa-f]{2}")

    fun supports(manager: Any): Boolean =
        runCatching {
            manager.javaClass.getMethod("getPairedDevices")
            manager.javaClass.getMethod("unpairDevice", String::class.java)
        }.isSuccess

    fun list(manager: Any): List<AdbAuthorizations.Device> {
        val entries = manager.javaClass.getMethod("getPairedDevices").invoke(manager) as? Map<*, *>
            ?: error("Framework did not return an authorization map")
        return entries.values.map { value ->
            requireNotNull(value)
            val fingerprint = readString(value, "guid", "getGuid")
            require(fingerprintPattern.matches(fingerprint))
            val name = readString(value, "name", "getDeviceName")
            AdbAuthorizations.Device(name.filterNot { it.isISOControl() }.take(200), fingerprint)
        }.distinctBy { it.fingerprint }.sortedWith(compareBy({ it.name.lowercase(java.util.Locale.ROOT) }, { it.fingerprint }))
    }

    // Android 11 uses immutable PairDevice getters; newer releases use AIDL fields.
    private fun readString(value: Any, field: String, getter: String): String =
        (runCatching { value.javaClass.getField(field).get(value) }.getOrNull()
            ?: value.javaClass.getMethod(getter).invoke(value)) as? String
            ?: error("Missing framework device metadata")

    /** False means an active connection must be disconnected before revocation. */
    fun revoke(manager: Any, fingerprint: String, current: List<AdbAuthorizations.Device>): Boolean {
        require(fingerprintPattern.matches(fingerprint))
        // Missing/stale rows are idempotent, and an arbitrary supplied key is never forwarded.
        if (current.none { it.fingerprint == fingerprint }) return true
        val method = manager.javaClass.methodOrNull("unpairDevice", String::class.java)
            ?: error("Individual revocation unavailable")
        // AOSP initializes this store on the debugging handler. Never enqueue an
        // unpair into a ROM whose handler cannot safely process it.
        check(manager.javaClass.fieldOrNull("mAdbKeyStore")?.get(manager) != null)
        // AOSP refreshes the stored timestamp when a USB key disconnects. Unpairing
        // it while tracked in mConnectedKeys can consequently re-add the removed key.
        // Refuse this case before making changes; other connected hosts do not block it.
        val connected = manager.javaClass.fieldOrNull("mConnectedKeys")?.get(manager) as? Map<*, *>
            ?: error("Cannot verify active debugging keys")
        val entries = manager.javaClass.getMethod("getPairedDevices").invoke(manager) as? Map<*, *>
            ?: error("Cannot verify target key")
        if (entries.any { (key, value) ->
            value != null && readString(value, "guid", "getGuid") == fingerprint && connected.containsKey(key)
        }) return false
        method.invoke(manager, fingerprint)
        return true
    }
}
