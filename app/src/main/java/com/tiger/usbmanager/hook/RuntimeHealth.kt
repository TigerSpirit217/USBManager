package com.tiger.usbmanager.hook

import android.os.Bundle

/** Set only after a hook has actually been registered, not after install() returns. */
internal object RuntimeHealth {
    @Volatile var usbHookInstalled = false
    @Volatile var adbHookInstalled = false

    fun snapshot(controller: UsbController) = Bundle().apply {
        putBoolean("usb_hook", usbHookInstalled)
        putBoolean("adb_hook", adbHookInstalled)
        putBoolean("usb_service", controller.hasUsbDeviceManager())
        putBoolean("adb_service", AdbServiceHook.hasService())
    }
}
