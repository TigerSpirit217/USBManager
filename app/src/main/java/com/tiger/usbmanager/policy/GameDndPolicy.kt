package com.tiger.usbmanager.policy

/** A foreground match is required: merely having a selected app running is insufficient. */
object GameDndPolicy {
    data class Configuration(val mode: UsbMode, val adb: Boolean)

    fun configuration(
        enabled: Boolean,
        packages: Set<String>,
        foregroundPackage: String?,
        useDefault: Boolean,
        defaultMode: UsbMode,
        defaultAdb: Boolean,
    ): Configuration? {
        if (!enabled || foregroundPackage == null || foregroundPackage !in packages) return null
        return if (useDefault) Configuration(defaultMode, defaultAdb)
        else Configuration(UsbMode.CHARGING, false)
    }
}
