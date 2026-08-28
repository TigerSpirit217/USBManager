package com.tiger.usbmanager.policy

import java.security.MessageDigest
import java.util.Base64

/** Stable identifier derived from the key material of an ADB RSA public key. */
object HostKeyFingerprint {

    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Converts either a full adb public-key line or an existing SHA-256 value to
     * the canonical lowercase fingerprint used by the host database.
     *
     * The optional `user@computer` comment is deliberately excluded: it is a
     * display label and can change without the RSA identity changing.
     */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (SHA256_HEX.matches(trimmed)) return trimmed.lowercase()

        val encodedKey = trimmed.substringBefore(' ').substringBefore('\t')
        val keyBytes = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()
            ?: return null
        if (keyBytes.isEmpty()) return null

        return MessageDigest.getInstance("SHA-256")
            .digest(keyBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
