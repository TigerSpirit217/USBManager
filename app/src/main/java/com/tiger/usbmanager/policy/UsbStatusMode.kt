package com.tiger.usbmanager.policy

/** Current state parsing has no saved-default fallback (UsbMode.fromWire is for preferences). */
internal object UsbStatusMode {
    fun fromFunctions(value: String?): UsbMode? {
        if (value == null) return null
        val functions = value.split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it !in listOf("", "none", "adb") }
        if (functions.isEmpty()) return UsbMode.CHARGING
        return UsbMode.entries.firstOrNull { it != UsbMode.CHARGING && it.wireValue in functions }
    }

    fun fromBroadcast(functions: String?, enabled: Set<String>, dataUnlocked: Boolean?): UsbMode? {
        // An explicit list (including "none" or "adb") takes precedence over stale flags.
        val mode = fromFunctions(functions ?: enabled.joinToString(","))
        // Legacy charging gadgets can advertise MTP/PTP while file transfer remains disabled.
        return if (dataUnlocked == false && (mode == UsbMode.MTP || mode == UsbMode.PTP))
            UsbMode.CHARGING else mode
    }
}
