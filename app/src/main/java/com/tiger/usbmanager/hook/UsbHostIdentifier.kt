package com.tiger.usbmanager.hook

import com.tiger.usbmanager.policy.HostKeyFingerprint

/**
 * Identifies the currently connected USB host by its ADB RSA public key.
 *
 * VID/PID describe the phone gadget side, not the connected computer, so they
 * are intentionally NOT used. Instead the host is fingerprinted by the key it
 * presents to adbd (stored in /data/misc/adb/adb_keys once authorized).
 *
 * Only a key explicitly reported as connected by adbd during the current
 * physical USB session is accepted. `/data/misc/adb/adb_keys` is an authorization
 * history, not current-connection state, and must never be used as a fallback.
 */
internal object UsbHostIdentifier {

    @Volatile private var usbConnected = false
    @Volatile private var currentHostKey: String? = null
    @Volatile private var sessionGeneration: Long = 0L
    @Volatile private var keyListener: ((Long, String) -> Unit)? = null

    @Synchronized
    fun onUsbConnected(): Long {
        if (!usbConnected) {
            usbConnected = true
            currentHostKey = null
            sessionGeneration += 1L
        }
        return sessionGeneration
    }

    @Synchronized
    fun onUsbDisconnected() {
        usbConnected = false
        currentHostKey = null
        sessionGeneration += 1L
    }

    fun onAdbKeyConnected(key: String?) {
        val fingerprint = HostKeyFingerprint.normalize(key) ?: return
        val generation: Long
        val listener: ((Long, String) -> Unit)?
        synchronized(this) {
            if (!usbConnected) return
            currentHostKey = fingerprint
            generation = sessionGeneration
            listener = keyListener
        }
        listener?.invoke(generation, fingerprint)
    }

    @Synchronized
    fun onAdbKeyDisconnected(key: String?) {
        val fingerprint = HostKeyFingerprint.normalize(key) ?: return
        // Keep a key that was positively identified during this physical USB
        // session. Changing USB functions restarts/re-enumerates the ADB
        // transport and emits DISCONNECT even though the cable is still present;
        // clearing here would make that same computer "unknown" and reopen the
        // chooser. A debounced physical disconnect is the authoritative boundary.
        if (currentHostKey != fingerprint) return
    }

    /** Current-session fingerprint, or null until adbd confirms a connected key. */
    fun currentHostKey(): String? = currentHostKey

    fun currentSessionGeneration(): Long = sessionGeneration

    fun setKeyListener(listener: ((Long, String) -> Unit)?) {
        keyListener = listener
    }

    /**
     * Builds a display name for an unknown host, used as the default label when
     * saving it for the first time.
     */
    fun defaultHostName(key: String?): String {
        if (key.isNullOrBlank()) return "Unknown PC"
        return "PC ${key.take(8)}"
    }
}
