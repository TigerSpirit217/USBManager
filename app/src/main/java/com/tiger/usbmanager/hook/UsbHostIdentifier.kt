package com.tiger.usbmanager.hook

import com.tiger.usbmanager.policy.HostInfo

/**
 * Identifies the currently connected USB host by its ADB RSA public key.
 *
 * VID/PID describe the phone gadget side, not the connected computer, so they
 * are intentionally NOT used. Instead the host is fingerprinted by the key it
 * presents to adbd (stored in /data/misc/adb/adb_keys once authorized).
 *
 * Two sources are combined:
 *  1. The most recent key captured by [AdbServiceHook] when a host presents it.
 *  2. The last line of /data/misc/adb/adb_keys (most recently authorized host).
 *
 * If neither is available (ADB never enabled/authorized for this host) the host
 * is treated as unknown and the chooser dialog is shown without a saved entry.
 */
internal object UsbHostIdentifier {

    @Volatile private var lastPresentedKey: String? = null

    fun onKeyPresented(key: String?) {
        if (key.isNullOrBlank()) return
        lastPresentedKey = key.trim()
    }

    /** Best-effort current host key, or null if unknown. */
    fun currentHostKey(): String? {
        lastPresentedKey?.let { return it }
        return lastAuthorizedKeyFromFile()
    }

    private fun lastAuthorizedKeyFromFile(): String? = runCatching {
        val file = java.io.File(ADB_KEYS_PATH)
        if (!file.exists()) return@runCatching null
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .lastOrNull()
    }.getOrNull()

    /**
     * Builds a display name for an unknown host, used as the default label when
     * saving it for the first time.
     */
    fun defaultHostName(key: String?): String {
        if (key.isNullOrBlank()) return "Unknown PC"
        return "PC ${key.take(8)}"
    }

    /** Returns a [HostInfo] stub for an as-yet-unknown host. */
    fun unknownHost(): HostInfo? {
        val key = currentHostKey() ?: return null
        return HostInfo(
            name = defaultHostName(key),
            hostKey = key,
            usbMode = "mtp",
            adb = true,
            auto = false,
        )
    }

    private const val ADB_KEYS_PATH = "/data/misc/adb/adb_keys"
}
