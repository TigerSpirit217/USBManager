package com.tiger.usbmanager.policy

import com.tiger.usbmanager.bridge.HostProviderClient

/**
 * Decides what to do when a USB host connects. Runs inside system_server and
 * resolves policy synchronously: a known, auto-allowed host is applied directly;
 * an unknown (or non-auto) host triggers the chooser dialog.
 */
class UsbPolicyEngine(
    private val hosts: HostProviderClient,
) {

    sealed class Decision {
        /** Apply this host's saved configuration automatically. */
        data class Apply(
            val mode: UsbMode,
            val adb: Boolean,
            val host: HostInfo?,
        ) : Decision()

        /** Show the chooser dialog. [preselectMode]/[preselectAdb] seed the UI. */
        data class Ask(
            val hostKey: String,
            val hostName: String?,
            val preselectMode: UsbMode,
            val preselectAdb: Boolean,
        ) : Decision()
    }

    fun resolve(hostKey: String, hostName: String?): Decision {
        val known = runCatching { hosts.findByKey(hostKey) }.getOrNull()
        if (known != null && known.auto) {
            return Decision.Apply(known.mode, known.adb, known)
        }

        val (defaultMode, defaultAdb) = runCatching { hosts.defaults() }
            .getOrDefault(UsbMode.CHARGING to false)

        // For a known-but-not-auto host, preselect its saved config but still ask.
        val preselectMode = known?.mode ?: defaultMode
        val preselectAdb = known?.adb ?: defaultAdb

        return Decision.Ask(
            hostKey = hostKey,
            hostName = hostName ?: known?.name,
            preselectMode = preselectMode,
            preselectAdb = preselectAdb,
        )
    }
}
