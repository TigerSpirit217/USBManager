package com.tiger.usbmanager.ui

/** Internal settings editor that reuses the connection chooser UI without applying USB functions. */
class DefaultUsbConfigActivity : UsbChooserActivity() {
    override val editsDefaultConfiguration: Boolean = true
}
