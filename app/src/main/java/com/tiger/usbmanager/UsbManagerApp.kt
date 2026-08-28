package com.tiger.usbmanager

import android.app.Application

class UsbManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleSettings.init(this)
    }
}
