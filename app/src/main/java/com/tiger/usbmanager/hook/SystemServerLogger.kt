package com.tiger.usbmanager.hook

import android.content.Context
import android.util.Log

/**
 * This class is deliberately a no-op.
 *
 * ## Why?
 *
 * Previous attempts to persist USBManager logs from system_server to
 * `/sdcard/Android/data/com.tiger.usbmanager/files/logs/` all failed under
 * NothingOS / Android 16 (NoSuchFile / EACCES / no files ever materialised)
 * because:
 *  1. system_server (uid 1000) cannot directly write into another app's
 *     CE/DE external storage directory — the storage daemon enforces per-pkg
 *     access by gid and the platform only gets `android.permission.WRITE_EXTERNAL_STORAGE`
 *     for the classic `/sdcard/Download`, `Documents`, DCIM style buckets.
 *  2. `createPackageContext(...).getExternalFilesDir()` requires the target
 *     package to have been launched at least once, and even when that's true
 *     the returned directory's `canWrite` check often fails for uid 1000.
 *
 * Users should ALWAYS use the official log sink for Xposed modules:
 *   LSPosed Manager → Modules → USBManager → ⋮ menu → View log
 *   (or, equivalently, `adb logcat -s USBManager:V LSPosedFramework:V`).
 *
 * All `XposedModule.log(...)` / `android.util.Log.println(..., "USBManager", ...)`
 * calls go to both sinks automatically; there is no need to keep a copy in
 * the module's private files.
 *
 * This stub exists so callers (`HookEnv.log`) keep compiling.
 */
internal object SystemServerLogger {

    @Volatile private var initialised = false
    @Volatile private var sinkDir: String? = null

    fun init(context: Context?) {
        if (initialised) return
        initialised = true
        val candidate = runCatching {
            context?.getExternalFilesDir(null)?.absolutePath + "/logs"
        }.getOrDefault("<unknown>")
        sinkDir = candidate
        Log.i("USBManager", "[SSLOG] file-persistence disabled (see LSPosed Manager→Modules→USBManager→⋮→View log)")
    }

    /** Accepts numeric android.util.Log.* level (e.g. Log.INFO == 4). */
    fun log(level: Int, tag: String, msg: String, throwable: Throwable? = null) {
        // no-op
    }

    /** Accepts single-letter level string ("V"/"D"/"I"/"W"/"E") used by callers
     *  that convert via `when (level) { Log.INFO -> "I" ... }`. */
    fun log(levelStr: String, tag: String, msg: String, throwable: Throwable? = null) {
        // no-op
    }
}
