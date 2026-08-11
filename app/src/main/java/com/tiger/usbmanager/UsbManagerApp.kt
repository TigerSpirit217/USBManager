package com.tiger.usbmanager

import android.app.Application

class UsbManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleSettings.init(this)
        // Persist USBManager-tagged logs to Android/data/<pkg>/logs/ so the
        // user can read them even after USB (and thus ADB logcat) is unplugged.
        LogCapture.start(this)
    }
}
