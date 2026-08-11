package com.tiger.usbmanager

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * LSPosed module entry point. Loaded into the configured scope (see scope.list).
 *
 * The primary target is `system` (system_server, via `onSystemServerStarting`),
 * where the USB / ADB framework classes live. The same APK also ships the module
 * app UI, which runs unhooked in the module's own process.
 *
 * NOTE: This class intentionally keeps zero compile-time references to hook classes
 * (SystemServerHooks, UsbDeviceManagerHook, etc.). If any of those classes or their
 * transitive dependencies fail to resolve inside system_server (e.g. missing appcompat
 * / gson classes in the system classloader), a direct `object` reference would turn
 * onSystemServerStarting itself into a NoClassDefFoundError with zero log output.
 * Instead we resolve everything reflectively inside a try/catch, which guarantees the
 * diagnostic "[MODULE] ..." lines always appear in logcat first.
 */
class UsbManagerModule : XposedModule() {

    @Volatile private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val processName = runCatching { param.getProcessName() }.getOrDefault("")
        mlog(Log.INFO, "[MODULE] onModuleLoaded OK; processName=$processName framework=$frameworkName v$frameworkVersionCode api=$apiVersion")

        // When LSPosed loads us into system_server, also set a system property
        // so the module app UI can confirm activation by checking
        // `persist.sys.usbmanager.active`. android.os.SystemProperties is a
        // hidden API so we use reflection.
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val setMethod = cls.getDeclaredMethod("set", String::class.java, String::class.java)
            setMethod.isAccessible = true
            setMethod.invoke(null, PROP_ACTIVE, "1")
            mlog(Log.INFO, "[MODULE] set $PROP_ACTIVE=1")
        }.onFailure { t ->
            mlog(Log.WARN, "[MODULE] Failed to set $PROP_ACTIVE", t)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        mlog(Log.INFO, "[MODULE] onSystemServerStarting() ENTER")

        if (installed) {
            mlog(Log.INFO, "[MODULE] hooks already installed; skip duplicate onSystemServerStarting")
            return
        }
        installed = true

        val systemServerClassLoader = runCatching { param.getClassLoader() }.getOrNull()
            ?: runCatching { param.classLoader as ClassLoader }.getOrNull()
        if (systemServerClassLoader == null) {
            mlog(Log.ERROR, "[MODULE] onSystemServerStarting cannot resolve systemServer classLoader")
            return
        }
        mlog(Log.INFO, "[MODULE] system_server classLoader=${systemServerClassLoader.javaClass.name}; moduleLoader=${this.javaClass.classLoader?.javaClass?.name}")

        // -----------------------------------------------------------------
        // EARLY: grab the system Context via ActivityThread.currentActivityThread()
        //   → getSystemContext() and boot the file logger before any hook install.
        //   This guarantees ss_*.log exists even if Application.attach never fires
        //   (which appears to be the case on Nothing Android 16) or SystemServerHooks
        //   crashes before calling our own onContextReady path.
        // -----------------------------------------------------------------
        val earlyContext = runCatching { resolveSystemContextViaActivityThread() }.getOrNull()
        mlog(Log.INFO, "[MODULE] ActivityThread.earlyContext = ${if (earlyContext == null) "FAIL" else "OK: " + earlyContext.javaClass.name + " pkg=" + runCatching { earlyContext.packageName }.getOrDefault("?")}")
        if (earlyContext != null) {
            runCatching {
                val sslogCls = Class.forName("com.tiger.usbmanager.hook.SystemServerLogger", true, this@UsbManagerModule.javaClass.classLoader)
                val initMethod = sslogCls.getDeclaredMethod("init", Context::class.java)
                initMethod.isAccessible = true
                initMethod.invoke(null, earlyContext)
                mlog(Log.INFO, "[MODULE] SystemServerLogger.init(earlyContext) called OK")
            }.onFailure { t ->
                mlog(Log.ERROR, "[MODULE] SystemServerLogger.init via reflection FAILED", t)
            }
        }

        installHooksReflectively(
            xposed = this,
            systemLoader = systemServerClassLoader,
            moduleLoader = this@UsbManagerModule.javaClass.classLoader,
            earlyContext = earlyContext,
        )
    }

    /**
     * Resolve the real system Context by asking ActivityThread — this works inside
     * system_server because the singleton is already created by the time
     * onSystemServerStarting() fires.
     */
    private fun resolveSystemContextViaActivityThread(): Context? {
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val current = atCls.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
                .invoke(null) ?: return null
            // Prefer getSystemContext (public-ish on newer versions), fall back to
            // mSystemContext / getContext on older APIs.
            fun tryMethod(name: String): Context? = runCatching {
                val m = atCls.getDeclaredMethod(name)
                m.isAccessible = true
                m.invoke(current) as? Context
            }.getOrNull()
            fun tryField(name: String): Context? = runCatching {
                val f = atCls.getDeclaredField(name)
                f.isAccessible = true
                f.get(current) as? Context
            }.getOrNull()
            (tryMethod("getSystemContext")
                ?: tryMethod("getContext")
                ?: tryField("mSystemContext")
                ?: tryField("mInitialApplication"))
        } catch (t: Throwable) {
            mlog(Log.WARN, "[MODULE] cannot resolve systemContext via ActivityThread", t)
            null
        }
    }

    // ---- Reflection bridge to SystemServerHooks ----

    private fun installHooksReflectively(
        xposed: XposedInterface,
        systemLoader: ClassLoader,
        moduleLoader: ClassLoader?,
        earlyContext: Context?,
    ) {
        val hooksClassName = "com.tiger.usbmanager.hook.SystemServerHooks"

        val hookLoader: ClassLoader = moduleLoader ?: run {
            mlog(Log.WARN, "[MODULE] moduleClassLoader is null; falling back to systemLoader")
            systemLoader
        }

        val hooksClass: Class<*> = try {
            mlog(Log.INFO, "[MODULE] Class.forName(\"$hooksClassName\") from loader=${hookLoader.javaClass.name} ...")
            Class.forName(hooksClassName, true, hookLoader)
        } catch (t: Throwable) {
            mlog(
                Log.ERROR,
                "[MODULE] REFLECT FAIL: cannot load $hooksClassName from loader=${hookLoader.javaClass.name}. " +
                    "Module dex not accessible to system_server? (scope/module.prop problem or APK not installed correctly)",
                t,
            )
            return
        }
        mlog(Log.INFO, "[MODULE] Class.forName OK: $hooksClassName")

        // List all public methods so we can diagnose an install signature mismatch.
        val methodsSummary = hooksClass.methods.joinToString(", ") { m ->
            "${m.name}(${m.parameterTypes.joinToString { pt -> pt.simpleName }})"
        }
        mlog(Log.INFO, "[MODULE] hooks public methods: $methodsSummary")

        val installMethodCandidates = hooksClass.methods
            .filter { it.name == "install" }
            .sortedByDescending { it.parameterTypes.size }
        if (installMethodCandidates.isEmpty()) {
            mlog(Log.ERROR, "[MODULE] REFLECT FAIL: no method named install on $hooksClassName")
            return
        }

        val singleton: Any = try {
            val instanceField = hooksClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.get(null)
        } catch (t: Throwable) {
            mlog(Log.WARN, "[MODULE] no INSTANCE field; try no-arg constructor", t)
            try {
                hooksClass.getDeclaredConstructor().newInstance()
            } catch (t2: Throwable) {
                mlog(Log.ERROR, "[MODULE] cannot obtain SystemServerHooks instance", t2)
                return
            }
        }
        mlog(Log.INFO, "[MODULE] hooks singleton OK: ${singleton.javaClass.name}")

        // For each candidate install(...) method, try to build a matching args
        // array, always passing the logger parameter as null (the method body
        // uses module.log() directly for diagnostics so the callback is
        // optional). Attempt one candidate after another until one succeeds.
        var lastErr: Throwable? = null
        for (candidate in installMethodCandidates) {
            candidate.isAccessible = true
            val params = candidate.parameterTypes
            val args = buildArgsForInstall(params, xposed, systemLoader, earlyContext)
            if (args == null) {
                mlog(Log.WARN, "[MODULE] skipping ${candidate.toGenericString()}: could not build ${params.size} args")
                continue
            }
            mlog(Log.INFO, "[MODULE] trying ${candidate.toGenericString()} args=${args.map { it?.javaClass?.simpleName }}")
            try {
                candidate.invoke(singleton, *args)
                mlog(Log.INFO, "[MODULE] SystemServerHooks.install() returned OK via candidate " +
                    "${params.size}-arg")
                return
            } catch (tx: Throwable) {
                lastErr = tx.cause ?: tx
                mlog(Log.WARN, "[MODULE] ${params.size}-arg candidate FAILED", lastErr)
            }
        }
        if (lastErr != null) {
            mlog(Log.ERROR, "[MODULE] FATAL: all install() candidates failed", lastErr)
        }
    }

    /**
     * Build the args array for a SystemServerHooks.install() method whose
     * parameter types are [params]. Positional semantics we expect:
     *   [0] XposedInterface
     *   [1] XposedModule
     *   [2] ClassLoader        (system_server class loader)
     *   [3] Any?               (logger lambda / consumer / nullable)
     *   [4] Context?           (optional: early context from ActivityThread)
     * If [params] don't match these positions even after null padding, return
     * null and the caller will skip this candidate.
     */
    private fun buildArgsForInstall(
        params: Array<Class<*>>,
        xposed: XposedInterface,
        systemLoader: ClassLoader,
        earlyContext: Context?,
    ): Array<Any?>? {
        val out = arrayOfNulls<Any>(params.size)
        for (i in params.indices) {
            val type = params[i]
            when (i) {
                0 -> whenAssignable(out, i, type, xposed, XposedInterface::class.java) ?: return null
                1 -> whenAssignable(out, i, type, this@UsbManagerModule, XposedModule::class.java) ?: return null
                2 -> whenAssignable(out, i, type, systemLoader, ClassLoader::class.java) ?: return null
                3 -> {
                    // 4th slot: accept any nullable reference type we can legally pass.
                    if (type.isPrimitive) return null
                    out[i] = null
                }
                4 -> {
                    if (Context::class.java.isAssignableFrom(type) || !type.isPrimitive) {
                        out[i] = earlyContext
                    } else return null
                }
                else -> {
                    if (type.isPrimitive) return null
                    out[i] = null
                }
            }
        }
        return out
    }

    /** If [value] can be assigned to [expectedType] at index [i], write it to
     *  [out] and return [out]. Otherwise return null. */
    private fun whenAssignable(
        out: Array<Any?>,
        i: Int,
        paramType: Class<*>,
        value: Any,
        expectedType: Class<*>,
    ): Array<Any?>? {
        return if (paramType.isAssignableFrom(expectedType) || paramType.isInstance(value)) {
            out[i] = value
            out
        } else null
    }

    // Convenience so inline logging calls inside this class don't have to
    // mention TAG / nullable throwable every time.
    private fun mlog(level: Int, msg: String, th: Throwable? = null) {
        if (th == null) log(level, LOG_TAG, msg) else log(level, LOG_TAG, msg, th)
    }

    companion object {
        const val LOG_TAG = "USBManager"
        const val PROP_ACTIVE = "persist.sys.usbmanager.active"
    }
}
