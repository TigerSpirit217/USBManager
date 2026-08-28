package com.tiger.usbmanager.hook

import android.os.Message
import io.github.libxposed.api.XposedInterface

/**
 * Hooks the ADB service stack inside system_server:
 *  - captures the [com.android.server.adb.AdbService] instance so [UsbController]
 *    can drive ADB state directly;
 *  - observes adbd's connected/disconnected RSA-key messages so
 *    [UsbHostIdentifier] knows the key belongs to the current USB session.
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
                env.warn("AdbDebuggingManager not found; current USB host cannot be identified")
                return
            }

        val handlerClasses = cls.declaredClasses.filter {
            it.simpleName.contains("DebuggingHandler", ignoreCase = true)
        }
        if (handlerClasses.isEmpty()) {
            env.warn("No AdbDebuggingHandler inner class found; current-key tracking disabled")
            return
        }

        handlerClasses.forEach { handlerClass -> hookConnectionMessages(env, handlerClass) }
    }

    /**
     * AOSP forwards adbd socket messages through AdbDebuggingHandler:
     * MESSAGE_ADB_CONNECTED_KEY carries the currently connected public key and
     * MESSAGE_ADB_DISCONNECT carries that same key when its transport closes.
     * Reflecting the constants avoids relying on version-specific numeric values.
     */
    private fun hookConnectionMessages(env: HookEnv, handlerClass: Class<*>) {
        fun intConstant(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
            handlerClass.fieldOrNull(name)?.let { field ->
                runCatching { (field.get(null) as? Number)?.toInt() }.getOrNull()
            }
        }

        val connectedWhat = intConstant("MESSAGE_ADB_CONNECTED_KEY")
        val disconnectedWhat = intConstant(
            "MESSAGE_ADB_DISCONNECT",
            "MESSAGE_ADB_DISCONNECTED_KEY",
        )
        if (connectedWhat == null || disconnectedWhat == null) {
            env.warn(
                "ADB key message constants unavailable on ${handlerClass.name}: " +
                    "connected=$connectedWhat disconnected=$disconnectedWhat",
            )
            return
        }

        val methods = handlerClass.declaredMethods.filter { method ->
            method.name == "handleMessage" &&
                method.parameterTypes.contentEquals(arrayOf(Message::class.java))
        }
        if (methods.isEmpty()) {
            env.warn("No handleMessage(Message) on ${handlerClass.name}; current-key tracking disabled")
            return
        }

        methods.forEach { method ->
            method.isAccessible = true
            runCatching {
                env.xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val message = chain.getArgs().firstOrNull() as? Message
                        val result = chain.proceed(chain.getArgs().toTypedArray())
                        val key = message?.obj as? String
                        when (message?.what) {
                            connectedWhat -> {
                                UsbHostIdentifier.onAdbKeyConnected(key)
                                env.info("ADB USB host key CONNECTED keyPresent=${!key.isNullOrBlank()}")
                            }
                            disconnectedWhat -> {
                                UsbHostIdentifier.onAdbKeyDisconnected(key)
                                env.info("ADB USB host key DISCONNECTED keyPresent=${!key.isNullOrBlank()}")
                            }
                        }
                        result
                    }
                env.info(
                    "ADB current-key hook installed on ${handlerClass.name}; " +
                        "connectedWhat=$connectedWhat disconnectedWhat=$disconnectedWhat",
                )
            }.onFailure { env.warn("Failed to hook ${handlerClass.name}.handleMessage", it) }
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
