package com.tiger.usbmanager.hook

import android.content.Context
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.tiger.usbmanager.policy.UsbMode

/**
 * Applies a (USB mode, ADB) configuration to the framework. Runs in system_server
 * where it has the system uid and can reach [UsbDeviceManager], [Settings.Global]
 * and [android.os.SystemProperties] directly.
 *
 * Strategy is layered so that ROM differences degrade gracefully:
 *  1. Framework API: reflectively call [UsbDeviceManager.setCurrentFunctions].
 *  2. Public API: the deprecated [android.hardware.usb.UsbManager.setCurrentFunction].
 *  3. Root fallback: setprop persist.sys.usb.config + ctl start/stop adbd.
 *
 * ADB is always toggled through [Settings.Global.ADB_ENABLED] plus an adbd
 * ctl.start/stop, so that unplugging the cable reliably stops adbd (the user
 * requirement: ADB must be off after disconnect — not just the setting flipped).
 */
internal class UsbController(
    private val env: HookEnv,
    private val rootFallback: RootFallback,
) {

    @Volatile private var usbDeviceManager: Any? = null

    /** UsbManager function bitmask constants, resolved reflectively. */
    private val functionBits: Map<UsbMode, Long> by lazy { resolveFunctionBits() }
    private val adbBit: Long? by lazy { resolveAdbBit() }

    fun bindUsbDeviceManager(instance: Any?) {
        if (instance != null) {
            usbDeviceManager = instance
            env.info("UsbDeviceManager instance captured")
        }
    }

    /** Apply the full configuration: USB mode + ADB state.
     *  Returns true iff BOTH the mode switch AND the adb toggle succeeded via
     *  framework APIs. Callers use this boolean (instead of `runCatching.isSuccess`)
     *  to determine whether the configuration actually took effect — catching no
     *  exceptions does NOT mean the functions were applied, since several paths
     *  silently return false (e.g. reflection lookup missed, root unavailable). */
    fun applyConfig(mode: UsbMode, adb: Boolean): Boolean {
        env.info("applyConfig: mode=$mode adb=$adb")
        val modeOk = setUsbFunctions(mode, adb)
        val adbOk = setAdbEnabled(adb)
        env.info("applyConfig result: modeOk=$modeOk adbOk=$adbOk")
        val rootOk: BooleanArray? = if (!modeOk || !adbOk) {
            env.warn("Framework path incomplete; invoking root fallback")
            rootFallback.applyConfig(mode, adb)
        } else null
        val effectiveModeOk = modeOk || (rootOk?.getOrNull(0) == true)
        val effectiveAdbOk = adbOk || ((rootOk?.getOrNull(2) ?: false) || (rootOk?.getOrNull(3) ?: false))
        env.info("applyConfig effective: modeOk=$effectiveModeOk adbOk=$effectiveAdbOk (root fallback ran=${rootOk != null})")
        return effectiveModeOk && effectiveAdbOk
    }

    /** Sets the USB gadget functions. Returns true if a framework path succeeded.
     *
     *  IMPORTANT — the ADB function bit / string is NEVER mixed into the
     *  parameter list here. Since Android 14 (and enforced on many OEM
     *  Android 16 builds including NothingOS 3.x), passing "adb" as part of
     *  the gadget function list triggers an IllegalArgumentException inside
     *  UsbService.setCurrentFunctions (the adb function has been moved out of
     *  the function-mask API and is now exclusively controlled via
     *  Settings.Global.ADB_ENABLED / AdbService). ADB is toggled separately
     *  by the caller via [setAdbEnabled]. */
    fun setUsbFunctions(mode: UsbMode, @Suppress("UNUSED_PARAMETER") adb: Boolean): Boolean {
        val manager = usbDeviceManager ?: run {
            env.warn("UsbDeviceManager not captured; cannot set functions via framework")
            return false
        }

        // 1) Modern bitmask API on UsbDeviceManager. Skip entirely if the
        //    UsbManager.FUNCTION_* reflection lookup returned empty (we'd
        //    otherwise call setCurrentFunctions(0L) which itself throws IAE).
        val modeBit = functionBits[mode]
        if (modeBit != null && modeBit != 0L) {
            if (callSetCurrentFunctions(manager, modeBit)) return true
        }

        // 2) Legacy string API on UsbDeviceManager — WITHOUT the ",adb" suffix.
        val modeOnlyString = mode.wireValue
        if (callSetCurrentFunction(manager, modeOnlyString)) return true

        // 3) Public UsbManager API via system context — WITHOUT the ",adb" suffix.
        if (callPublicSetCurrentFunction(modeOnlyString)) return true

        // 4) Direct UsbService setCurrentFunctions (string overload; no adb).
        if (callUsbServiceSetCurrentFunction(modeOnlyString)) return true

        return false
    }

    /** Toggles ADB at the framework + daemon level. */
    fun setAdbEnabled(enabled: Boolean): Boolean {
        val ctx = env.systemContext
        var ok = false

        // 1) Settings.Global.ADB_ENABLED — the canonical framework switch.
        if (ctx != null) {
            ok = runCatching {
                Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (enabled) 1 else 0)
            }.onFailure { env.warn("setAdbEnabled: Settings.Global failed", it) }.isSuccess
        }

        // 2) Start/stop the adbd daemon so it actually goes down on unplug.
        val ctlOk = setSystemProperty(
            if (enabled) "ctl.start" else "ctl.stop",
            "adbd",
        )
        if (!ctlOk) {
            env.warn("setAdbEnabled: ctl.start/stop adbd failed (property write blocked?)")
        }

        // 3) Reflectively poke AdbService if we captured one.
        AdbServiceHook.setAdbEnabled(enabled)

        return ok || ctlOk
    }

    // ---- Framework API attempts ----

    private fun callSetCurrentFunctions(manager: Any, functions: Long): Boolean {
        // setCurrentFunctions(long) or setCurrentFunctions(long, boolean)
        val withBool = manager.javaClass.methodOrNull(
            "setCurrentFunctions",
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        if (withBool != null) {
            return runCatching {
                withBool.invoke(manager, functions, true)
                env.info("setCurrentFunctions(long, boolean=true) functions=$functions ok")
                true
            }.onFailure { env.warn("setCurrentFunctions(long,boolean) failed functions=$functions", it) }.getOrDefault(false)
        }
        val single = manager.javaClass.methodOrNull(
            "setCurrentFunctions",
            Long::class.javaPrimitiveType!!,
        )
        if (single != null) {
            return runCatching {
                single.invoke(manager, functions)
                env.info("setCurrentFunctions(long=$functions) ok")
                true
            }.onFailure { env.warn("setCurrentFunctions(long) failed functions=$functions", it) }.getOrDefault(false)
        }
        // Some ROMs use the boxed Long signature.
        val boxed = manager.javaClass.methodOrNull("setCurrentFunctions", Long::class.javaObjectType)
        if (boxed != null) {
            return runCatching {
                boxed.invoke(manager, functions)
                env.info("setCurrentFunctions(boxed $functions) ok")
                true
            }.onFailure { env.warn("setCurrentFunctions(Long) failed functions=$functions", it) }.getOrDefault(false)
        }
        env.warn("No setCurrentFunctions(long*/boolean) method found on ${manager.javaClass.name}; skipping bitmask path")
        return false
    }

    private fun callSetCurrentFunction(manager: Any, config: String): Boolean {
        val m = manager.javaClass.methodOrNull(
            "setCurrentFunction",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: manager.javaClass.methodOrNull("setCurrentFunction", String::class.java)
            ?: return false
        return runCatching {
            if (m.parameterCount == 2) m.invoke(manager, config, false)
            else m.invoke(manager, config)
            env.info("setCurrentFunction($config) ok")
            true
        }.onFailure { env.warn("setCurrentFunction failed", it) }.getOrDefault(false)
    }

    private fun callPublicSetCurrentFunction(config: String): Boolean {
        val ctx = env.systemContext ?: return false
        return runCatching {
            val cls = env.classLoader.findClassOrNull("android.hardware.usb.UsbManager") ?: return@runCatching false
            val service = ctx.getSystemService("usb") ?: return@runCatching false
            val m = cls.methodOrNull(
                "setCurrentFunction",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
            ) ?: return@runCatching false
            m.invoke(service, config, false)
            env.info("UsbManager.setCurrentFunction($config) ok")
            true
        }.onFailure { env.warn("public setCurrentFunction failed config=$config", it) }.getOrDefault(false)
    }

    /**
     * Direct bridge into the system_server singleton `UsbService`.
     *
     * On NothingOS 3.x (Android 16 / SDK 36) the public `UsbManager` API
     * rejects any multi-function string or legacy string format via
     * `Preconditions.checkArgument`, but the internal `UsbService` still
     * accepts them. We obtain the service via the same hidden
     * `ServiceManager.getService("usb")` that AOSP uses.
     */
    private fun callUsbServiceSetCurrentFunction(config: String): Boolean {
        return runCatching {
            val smCls = env.classLoader.findClassOrNull("android.os.ServiceManager")
                ?: return@runCatching false
            val getSvc = smCls.methodOrNull("getService", String::class.java)
                ?: return@runCatching false
            val usbServiceIBinder = getSvc.invoke(null, "usb") ?: return@runCatching false
            // Wrap with IUsbService.Stub.asInterface(IBinder).
            val stubCls = env.classLoader.findClassOrNull("android.hardware.usb.IUsbService\$Stub")
                ?: return@runCatching false
            val ibinderClass = IBinder::class.java
            val asInterface = stubCls.methodOrNull("asInterface", ibinderClass)
                ?: return@runCatching false
            val usbService = asInterface.invoke(null, usbServiceIBinder) ?: return@runCatching false

            // Try both UsbService.setCurrentFunction(String, boolean) and setCurrentFunctions(String, boolean).
            val svcCls: Class<*> = usbService.javaClass
            val methods = listOf("setCurrentFunction", "setCurrentFunctions")
            for (name in methods) {
                val m = svcCls.methodOrNull(name, String::class.java, Boolean::class.javaPrimitiveType!!)
                if (m != null) {
                    runCatching {
                        m.invoke(usbService, config, false)
                        env.info("UsbService.$name($config) ok via IUsbService")
                        return@runCatching true
                    }.onFailure { env.warn("UsbService.$name($config) failed via IUsbService", it) }
                }
            }
            false
        }.getOrDefault(false)
    }

    // ---- Constants resolution ----

    private fun resolveFunctionBits(): Map<UsbMode, Long> {
        val cls = env.classLoader.findClassOrNull("android.hardware.usb.UsbManager")
        if (cls == null) {
            env.warn("UsbManager class not found; bitmask unavailable")
            return emptyMap()
        }
        val map = mutableMapOf<UsbMode, Long>()
        UsbMode.entries.forEach { mode ->
            val fieldName = "FUNCTION_${mode.name}"
            val bit = cls.staticLongFieldOrNull(fieldName)
            if (bit != null && bit != 0L) {
                map[mode] = bit
                env.info("Resolved UsbManager.$fieldName = $bit")
            } else {
                env.warn("Constant $fieldName not found on UsbManager; will skip bitmask path for mode=$mode")
            }
        }
        // Also try FUNCTION_{name}_BIT variants / aliases used by older ROMs.
        if (map.size < UsbMode.entries.size) {
            UsbMode.entries.forEach { mode ->
                if (map.containsKey(mode)) return@forEach
                val alt = "FUNCTION_${mode.wireValue.uppercase()}"
                val bit = cls.staticLongFieldOrNull(alt)
                if (bit != null && bit != 0L) {
                    map[mode] = bit
                    env.info("Resolved UsbManager alt $alt = $bit → mode=$mode")
                }
            }
        }
        return map
    }

    private fun resolveAdbBit(): Long? {
        // NOTE: as of Android 14 the adb function bit may simply not exist on
        // UsbManager anymore (it was moved under the AdbService umbrella).
        // Returning null is perfectly valid — we just don't OR any adb bit
        // into the gadget function mask.
        val cls = env.classLoader.findClassOrNull("android.hardware.usb.UsbManager") ?: return null
        val bit = cls.staticLongFieldOrNull("FUNCTION_ADB")
        env.info("Resolved UsbManager.FUNCTION_ADB = $bit")
        return bit
    }

    @Suppress("unused")
    private fun buildConfigString(mode: UsbMode, adb: Boolean): String {
        // Legacy property-style string. Kept only for reference; the actual
        // function-switching code never includes ",adb" anymore (see KDoc on
        // setUsbFunctions).
        return if (adb) "${mode.wireValue},adb" else mode.wireValue
    }

    // ---- SystemProperties helper ----

    fun setSystemProperty(key: String, value: String): Boolean {
        return runCatching {
            val cls = env.classLoader.findClassOrNull("android.os.SystemProperties") ?: return@runCatching false
            val m = cls.methodOrNull("set", String::class.java, String::class.java) ?: return@runCatching false
            m.invoke(null, key, value)
            true
        }.onFailure { env.warn("SystemProperties.set($key) failed", it) }.getOrDefault(false)
    }

    fun getSystemProperty(key: String, def: String): String {
        return runCatching {
            val cls = env.classLoader.findClassOrNull("android.os.SystemProperties") ?: return@runCatching def
            val m = cls.methodOrNull("get", String::class.java, String::class.java) ?: return@runCatching def
            m.invoke(null, key, def) as? String ?: def
        }.getOrDefault(def)
    }
}
