package com.tiger.usbmanager.policy

import com.google.gson.annotations.SerializedName

/**
 * A known USB host (typically a desktop computer), identified by the ADB RSA
 * host key rather than USB VID/PID (which describe the phone gadget, not the host).
 */
data class HostInfo(
    @SerializedName("name")
    val name: String,

    /** ADB RSA public key fingerprint (the line stored in /data/misc/adb/adb_keys). */
    @SerializedName("hostKey")
    val hostKey: String,

    @SerializedName("usbMode")
    val usbMode: String = "mtp",

    @SerializedName("adb")
    val adb: Boolean = true,

    /** When true, the policy engine auto-applies this host's config without a dialog. */
    @SerializedName("auto")
    val auto: Boolean = true,

    @SerializedName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),
) {
    val mode: UsbMode get() = UsbMode.fromWire(usbMode)

    companion object {
        /** Truncate the stored key for display purposes. */
        fun shortKey(key: String): String =
            key.trim().take(12) + if (key.length > 12) "…" else ""
    }
}
