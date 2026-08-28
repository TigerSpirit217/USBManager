package com.tiger.usbmanager.hook

import io.github.libxposed.api.XposedInterface

/**
 * Hooks the ADB service stack inside system_server:
 *  - captures the [com.android.server.adb.AdbService] instance so [UsbController]
 *    can drive ADB state directly;
 *  - captures host RSA keys presented to [com.android.server.adb.AdbDebuggingManager]
 *    so [UsbHostIdentifier] can fingerprint the connected computer.
 *
 * Everything is best-effort: class names and method signatures vary between
 * Android releases, so failures are logged and never propagated.
 */
internal object AdbServiceHook {

    @Volatile private var adbService: Any? = null

    fun install(env: HookEnv) {
        hookAdbService(env)
        hookAdbDebuggingManager(env)
    }

    private fun hookAdbService(env: HookEnv) {
        val cls = env.classLoader.findClassOrNull("com.android.server.adb.AdbService") ?: run {
            env.warn("com.android.server.adb.AdbService not found; ADB control via reflection disabled")
            return
        }
        val ctors = cls.declaredConstructors
        if (ctors.isEmpty()) {
            env.warn("AdbService has no declared constructors to hook")
            return
        }
        ctors.forEach { ctor ->
            ctor.isAccessible = true
            runCatching {
                env.xposed.hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed(chain.getArgs().toTypedArray())
                        runCatching {
                            val instance = chain.getThisObject()
                            if (instance != null) {
                                adbService = instance
                                env.info("AdbService instance captured")
                            }
                        }
                        result
                    }
            }.onFailure { env.warn("Failed to hook AdbService constructor", it) }
        }
    }

    private fun hookAdbDebuggingManager(env: HookEnv) {
        // Class name has moved across versions; try the known candidates.
        val cls = ADB_DEBUGGING_CANDIDATES
            .firstNotNullOfOrNull { name -> env.classLoader.findClassOrNull(name) }
            ?: run {
                env.warn("AdbDebuggingManager not found; host-key capture relies on adb_keys file only")
                return
            }

        // Hook any method that looks like it persists a presented key. Names vary
        // (saveKey / writeKey / addKey / handleConfirmAdb). We capture the first
        // String argument that resembles an RSA key (long, Base64-ish).
        val candidates = listOf("saveKey", "writeKey", "addKey", "handleConfirmAdb", "onKeyPresented")
        candidates.forEach { name ->
            cls.methodsNamed(name).forEach { method ->
                if (method.parameterTypes.isEmpty()) return@forEach
                runCatching {
                    env.xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            chain.getArgs().forEach { arg ->
                                if (arg is String && arg.length > 40) {
                                    UsbHostIdentifier.onKeyPresented(arg)
                                    env.info("Captured ADB host key via ${method.name}")
                                }
                            }
                            chain.proceed(chain.getArgs().toTypedArray())
                        }
                }.onFailure { env.warn("Failed to hook ${cls.name}.$name", it) }
            }
        }
    }

    /** Toggle ADB through the framework-wide [AdbService]. Returns true iff a
     *  matching `setAdbEnabled` method was found and invoked without throwing
     *  (the exact signature/ROM variant does not matter — success means the
     *  framework took over).
     *
     *  This is the *authoritative* driver: it keeps `Settings.Global.ADB_ENABLED`,
     *  the adbd daemon lifecycle AND the "USB debugging connected" notification all
     *  in sync the way the framework expects. Bypassing it (as the old direct
     *  `Settings.Global` + `ctl.adbd` path did) left the notification in a stale
     *  state, which is why a transient / leftover "USB 调试已连接" notice surfaced
     *  on unplug. */
    fun trySetAdbEnabled(enabled: Boolean): Boolean {
        val service = adbService ?: return false
        return runCatching {
            // setAdbEnabled(boolean enable, String packageName) on modern AOSP.
            val m2 = service.javaClass.methodsNamed("setAdbEnabled").firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
            }
            if (m2 != null) {
                m2.invoke(service, enabled, MODULE_PACKAGE)
            } else {
                val m1 = service.javaClass.methodsNamed("setAdbEnabled").firstOrNull {
                    it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                if (m1 != null) m1.invoke(service, enabled) else return@runCatching false
            }
            true
        }.onFailure { /* swallow; UsbController owns its own fallback paths */ }.getOrDefault(false)
    }

    private const val MODULE_PACKAGE = "com.tiger.usbmanager"

    private val ADB_DEBUGGING_CANDIDATES = listOf(
        "com.android.server.adb.AdbDebuggingManager",
        "com.android.server.AdbDebuggingManager",
    )
}
