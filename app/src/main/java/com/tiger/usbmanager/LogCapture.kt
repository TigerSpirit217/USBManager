package com.tiger.usbmanager

import android.app.Application

/**
 * App-side log capture is disabled.
 *
 * ## Why
 *
 * On modern Android the module app process (uid=10xxx) is *not* allowed to read
 * logcat lines produced by system_server (uid=1000) unless it holds the
 * `android.permission.READ_LOGS` permission, which signature|privileged apps
 * only can get.
 *
 * This leaves the previous `logcat -s USBManager:V` subprocess only able to
 * read lines emitted by the module UI process — a useless subset — so we don't
 * bother writing any files into `files/logs/` anymore.
 *
 * Users should follow the built-in LSPosed log viewer:
 *   LSPosed Manager → Modules → USBManager → ⋮ → View log
 *   or `adb logcat -s USBManager:V LSPosedFramework:V`.
 */
object LogCapture {

    @JvmStatic
    fun start(app: Application) {
        // No-op. Previously started a logcat subprocess that captured the USB
        // tag and appended it to files/logs/ — kept as a stub so MainActivity,
        // UsbManagerApp callers keep building.
    }
}
