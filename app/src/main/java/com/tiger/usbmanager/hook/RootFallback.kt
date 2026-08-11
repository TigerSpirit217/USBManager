package com.tiger.usbmanager.hook

import com.tiger.usbmanager.policy.UsbMode
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Last-resort implementation path that drives USB / ADB state through root
 * (`su`) when the framework reflection path is unavailable or incomplete.
 *
 * This is intentionally a *fallback* — the primary path is the in-process call
 * to [com.android.server.usb.UsbDeviceManager.setCurrentFunctions] plus
 * [android.provider.Settings.Global.ADB_ENABLED], which is faster and doesn't
 * depend on a superuser daemon being installed.
 *
 * Commands used:
 *  - `setprop persist.sys.usb.config <mode>[,adb]`  (legacy config string)
 *  - `setprop sys.usb.config <mode>[,adb]`          (runtime config)
 *  - `setprop ctl.start/stop adbd`                  (daemon lifecycle)
 *  - `settings put global adb_enabled 0/1`          (framework setting)
 */
internal class RootFallback(private val env: HookEnv) {

    /**
     * Attempts the root commands; returns a 4-element BooleanArray:
     *   [0] = persist.sys.usb.config ok
     *   [1] = sys.usb.config         ok
     *   [2] = ctl.(start|stop) adbd  ok
     *   [3] = settings put global adb_enabled ok
     * Returns null if su is not available (fast path, avoids 4x IOException).
     */
    fun applyConfig(mode: UsbMode, adb: Boolean): BooleanArray? {
        if (!isAvailable()) {
            env.info("RootFallback: `su` missing; skipped")
            return null
        }
        val config = if (adb) "${mode.wireValue},adb" else mode.wireValue
        env.info("RootFallback: applying config=$config adb=$adb")

        val results = listOf(
            runRoot("setprop persist.sys.usb.config $config"),
            runRoot("setprop sys.usb.config $config"),
            runRoot("setprop ctl.${if (adb) "start" else "stop"} adbd"),
            runRoot("settings put global adb_enabled ${if (adb) 1 else 0}"),
        )
        env.info("RootFallback results: $results")
        return results.toBooleanArray()
    }

    /** Runs a single command via `su -c`. Returns true on exit code 0. */
    private fun runRoot(command: String): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).readText()
            val code = proc.waitFor()
            if (stdout.isNotBlank()) env.info("su stdout: ${stdout.trim()}")
            if (stderr.isNotBlank()) env.warn("su stderr: ${stderr.trim()}")
            code == 0
        }.onFailure { env.warn("RootFallback command failed: $command", it) }.getOrDefault(false)
    }

    /** Best-effort availability probe: does `su` exist and return 0? */
    fun isAvailable(): Boolean = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        proc.waitFor() == 0
    }.getOrDefault(false)
}
