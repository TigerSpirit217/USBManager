package com.tiger.usbmanager.hook

import io.github.libxposed.api.XposedInterface

/**
 * Hooks [com.android.server.usb.UsbDeviceManager] inside system_server to:
 *  - capture the manager instance (handed to [UsbController] for function control);
 *  - observe USB state transitions ("CONNECTED" / "DISCONNECTED") coming from the
 *    kernel and forward them to a [StateListener].
 *
 * The state machine lives in an inner handler class whose name has shifted across
 * Android versions, so [updateState] is hooked on the outer class AND every
 * declared inner class. Any matching single-String-arg method is intercepted.
 */
internal class UsbDeviceManagerHook(
    private val env: HookEnv,
    private val controller: UsbController,
    private val listener: StateListener,
) {

    fun interface StateListener {
        fun onUsbState(connected: Boolean)
    }

    fun install() {
        env.info("[USB] UsbDeviceManagerHook install start")
        val cls = env.classLoader.findClassOrNull("com.android.server.usb.UsbDeviceManager") ?: run {
            env.warn("[USB] com.android.server.usb.UsbDeviceManager NOT FOUND in loader=${env.classLoader}; USB hook DISABLED")
            // Dump known classes for debugging (a few candidates).
            listOf(
                "com.android.server.usb.UsbHandler",
                "com.android.server.usb.UsbDeviceHandler",
                "com.android.server.usb.UsbService",
                "com.android.server.SystemServer",
            ).forEach { name ->
                val found = env.classLoader.findClassOrNull(name) != null
                env.warn("[USB] candidate classprobe $name -> found=$found")
            }
            return
        }
        env.info("[USB] UsbDeviceManager class resolved: ${cls.name}; declaredMethods=${cls.declaredMethods.size} declaredCtors=${cls.declaredConstructors.size} declaredClasses=${cls.declaredClasses.size}")

        hookConstructors(cls)
        hookUpdateState(cls)
        // Inner handler classes (e.g. UsbHandler / UsbDeviceHandler).
        cls.declaredClasses.forEach { inner ->
            env.info("[USB] Probing inner class ${inner.name} for updateState")
            hookUpdateState(inner)
        }
        // Log setCurrentFunctions calls for diagnostics.
        hookSetCurrentFunctions(cls)
        env.info("[USB] UsbDeviceManagerHook install done")
    }

    private fun hookConstructors(cls: Class<*>) {
        cls.declaredConstructors.forEach { ctor ->
            ctor.isAccessible = true
            env.info("[USB] Hooking constructor $ctor")
            runCatching {
                env.xposed.hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed(chain.getArgs().toTypedArray())
                        runCatching {
                            val inst = chain.getThisObject()
                            controller.bindUsbDeviceManager(inst)
                            env.info("[USB] UsbDeviceManager constructor called; instance=${inst?.javaClass?.name}@${System.identityHashCode(inst)}")
                        }.onFailure { env.warn("[USB] bindUsbDeviceManager in ctor failed", it) }
                        result
                    }
                env.info("[USB] Constructor hook installed: $ctor")
            }.onFailure { env.error("[USB] Failed to hook UsbDeviceManager constructor $ctor", it) }
        }
    }

    private fun hookUpdateState(cls: Class<*>) {
        val methods = cls.methodsNamed("updateState")
        if (methods.isEmpty()) {
            env.warn("[USB] No updateState method on ${cls.name}; skipping")
            return
        }
        methods.forEach { method ->
            // Accept any single-argument variant whose first arg is String.
            val firstIsString = method.parameterTypes.isNotEmpty() &&
                method.parameterTypes[0] == String::class.java
            env.info("[USB] candidate updateState on ${cls.name}: ${method.name}(${method.parameterTypes.joinToString()}) firstIsString=$firstIsString")
            if (!firstIsString) return@forEach
            runCatching {
                env.xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val state = chain.getArgs().firstOrNull() as? String
                        env.info("[USB] updateState fired: state=$state cls=${cls.simpleName} args=${chain.getArgs().joinToString { it.toString() }}")
                        if (state != null) {
                            // Match exact state names; "CONFIGURED" must NOT be treated as connect.
                            when (state.uppercase()) {
                                "DISCONNECTED" -> {
                                    env.info("[USB] → forwarding DISCONNECTED")
                                    listener.onUsbState(false)
                                }
                                "CONNECTED" -> {
                                    env.info("[USB] → forwarding CONNECTED")
                                    listener.onUsbState(true)
                                }
                            }
                        }
                        chain.proceed(chain.getArgs().toTypedArray())
                    }
                env.info("[USB] updateState hook installed: ${cls.name}.${method.name}")
            }.onFailure { env.error("[USB] Failed to hook updateState on ${cls.name}.${method.name}", it) }
        }
    }

    private fun hookSetCurrentFunctions(cls: Class<*>) {
        val methods = cls.methodsNamed("setCurrentFunctions")
        env.info("[USB] setCurrentFunctions candidates on ${cls.name}: count=${methods.size}")
        methods.forEach { method ->
            env.info("[USB]   → ${method.name}(${method.parameterTypes.joinToString()})")
            runCatching {
                env.xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        env.info("[USB] AOSP setCurrentFunctions called: args=${chain.getArgs().joinToString { it.toString() }}")
                        chain.proceed(chain.getArgs().toTypedArray())
                    }
            }.onFailure { env.warn("[USB] Failed to hook setCurrentFunctions", it) }
        }
    }
}
