package com.tiger.usbmanager.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Shared state for the system_server hook bundle. Holds the [XposedInterface],
 * the system context (captured from Application.attach) and a logger that routes
 * through the module's [XposedModule.log].
 */
internal class HookEnv(
    val xposed: XposedInterface,
    val module: XposedModule,
    val classLoader: ClassLoader,
) {
    @Volatile var systemContext: Context? = null

    fun log(level: Int, msg: String, t: Throwable? = null) {
        // Route through XposedModule.log → logcat (visible via adb logcat and the
        // LSPosed in-app log viewer). This is the single, canonical log sink; the
        // file-persistence layer was removed because system_server (uid 1000)
        // cannot write into another app's private storage on modern Android.
        if (t == null) module.log(level, TAG, msg)
        else module.log(level, TAG, msg, t)
    }

    fun info(msg: String) = log(Log.INFO, msg)
    fun warn(msg: String, t: Throwable? = null) = log(Log.WARN, msg, t)
    fun error(msg: String, t: Throwable? = null) = log(Log.ERROR, msg, t)

    fun requireContext(): Context {
        val ctx = systemContext
        if (ctx != null) return ctx
        throw IllegalStateException("systemContext not yet captured (attach not called)")
    }

    companion object {
        const val TAG = "USBManager"
    }
}
