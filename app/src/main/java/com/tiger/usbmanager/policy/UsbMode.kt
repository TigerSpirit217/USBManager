package com.tiger.usbmanager.policy

import com.tiger.usbmanager.ModuleConstants

/**
 * USB working modes offered to the user. The [wireValue] matches Android's
 * `UsbManager.FUNCTION_*` string constants (used by the legacy
 * `setUsbConfig` / `setCurrentFunction` path) and the JSON persistence.
 */
enum class UsbMode(val wireValue: String, val displayRes: Int) {
    CHARGING("none", com.tiger.usbmanager.R.string.chooser_mode_charging),
    MTP("mtp", com.tiger.usbmanager.R.string.chooser_mode_mtp),
    PTP("ptp", com.tiger.usbmanager.R.string.chooser_mode_ptp),
    RNDIS("rndis", com.tiger.usbmanager.R.string.chooser_mode_rndis),
    MIDI("midi", com.tiger.usbmanager.R.string.chooser_mode_midi);

    companion object {
        fun fromWire(value: String?): UsbMode =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: MTP
    }
}
