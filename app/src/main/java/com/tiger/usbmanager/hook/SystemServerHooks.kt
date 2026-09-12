package com.tiger.usbmanager.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.bridge.HostProviderClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Installs the full system_server hook bundle:
 *
 *  1. Install [UsbDeviceManagerHook] + [AdbServiceHook] immediately so the
 *     UsbDeviceManager constructor (which may run shortly after boot) is caught.
 *  2. Bootstrap using the early system Context captured by the module entry point
 *     via ActivityThread.getSystemContext(). This is reliable on Android 16 /
 *     custom ROMs where Application.attach either never fires or fires with a
 *     half-initialised Context during boot (its applicationContext is still null
 *     inside makeApplicationInner, which crashed HostProviderClient).
 *  3. Register runtime broadcast receivers only once ActivityManagerService is
 *     published to ServiceManager (polled). Registering during
 *     startBootstrapServices fails with a null IActivityManager.
 *
 * If no early Context could be resolved, [Application.attach] is hooked as a
 * fallback context source instead.
 */
internal object SystemServerHooks {

    /**
     * Primary entry point (4 args). Kept for backwards compatibility.
     * Delegates to the 5-arg variant below.
     */
    fun install(
        xposed: XposedInterface,
        module: XposedModule,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
    ) = install(xposed, module, classLoader, logger, null)

    /**
     * Install the system_server hook bundle.
     *
     * [earlyContext] is an optional system Context captured by the module entry
     * point using ActivityThread.currentActivityThread().getSystemContext().
     * When provided it is used directly to bootstrap the watcher path.
     */
    fun install(
        xposed: XposedInterface,
        module: XposedModule,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
        earlyContext: Context?,
    ) {
        module.log(Log.INFO, "USBManager",
            "[HOOK] install ENTER; classLoader=${classLoader.javaClass.name} earlyContext=${earlyContext?.javaClass?.name}")

        val env = HookEnv(xposed, module, classLoader)
        env.info("[HOOK] install start; HookEnv constructed OK")

        val rootFallback = RootFallback(env)
        val controller = UsbController(env, rootFallback)
        val listenerHolder = DelegatingListener()

        runCatching {
            UsbDeviceManagerHook(env, controller, listenerHolder).install()
        }.onFailure {
            module.log(Log.ERROR, "USBManager", "[HOOK] UsbDeviceManagerHook.install() threw", it)
            env.error("[HOOK] UsbDeviceManagerHook.install() threw", it)
        }
        runCatching {
            AdbServiceHook.install(env)
        }.onFailure {
            module.log(Log.ERROR, "USBManager", "[HOOK] AdbServiceHook.install() threw", it)
            env.error("[HOOK] AdbServiceHook.install() threw", it)
        }

        val onContextReadyRan = AtomicBoolean(false)
        val runOnce = fun(ctx: Context, source: String) {
            if (!onContextReadyRan.compareAndSet(false, true)) return
            env.systemContext = ctx
            module.log(Log.INFO, "USBManager",
                "[HOOK] system_context ready via $source pkg=${runCatching { ctx.packageName }.getOrDefault("?")} uid=${android.os.Process.myUid()}")
            env.info("[HOOK] system_context committed; calling onContextReady")
            runCatching { onContextReady(env, controller, listenerHolder) }
                .onSuccess { env.info("[HOOK] onContextReady OK") }
                .onFailure { th -> env.error("[HOOK] onContextReady failed", th) }
        }

        if (earlyContext != null) {
            env.info("[HOOK] earlyContext provided; initializing watcher immediately")
            runOnce(earlyContext, "earlyContext(ActivityThread)")
        } else {
            runCatching {
                hookApplicationAttach(env) { ctx -> runOnce(ctx, "Application.attach") }
            }.onFailure {
                module.log(Log.ERROR, "USBManager", "[HOOK] hookApplicationAttach failed", it)
                env.error("[HOOK] hookApplicationAttach failed", it)
            }
        }

        val rootOk = runCatching { rootFallback.isAvailable() }.getOrDefault(false)
        env.info("[HOOK] install end; rootFallbackAvailable=$rootOk")
        module.log(Log.INFO, "USBManager",
            "[HOOK] install EXIT; rootFallbackAvailable=$rootOk earlyContext=${earlyContext != null}")
    }

    private fun onContextReady(
        env: HookEnv,
        controller: UsbController,
        listenerHolder: DelegatingListener,
    ) {
        val ctx = env.requireContext()
        env.info("[HOOK] onContextReady start; contentResolver ok=${ctx.contentResolver != null}")

        clearLegacyHostFallback(env, ctx)

        val hostClient = HostProviderClient(ctx)
        val watcher = UsbStateWatcher(env, controller, hostClient)
        env.info("[HOOK] UsbStateWatcher built; wiring to listenerHolder")
        listenerHolder.delegate = watcher

        val receiver = SystemServerReceiver(env, controller, watcher, watcher)

        scheduleReceiverRegistration(env, ctx, receiver)

        env.info("[HOOK] system_server core ready, receiver registration scheduled")
    }
    private fun scheduleReceiverRegistration(
        env: HookEnv,
        ctx: Context,
        receiver: SystemServerReceiver,
    ) {
        val mainHandler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        if (mainHandler == null) {
            // Defensive only: SystemServer.run() prepares the main looper before
            // this hook fires, so this branch is unreachable in practice. Fall
            // back to a single immediate attempt instead of recursing (which
            // would end in a StackOverflowError).
            env.warn("[HOOK] MainLooper unavailable, registering immediately")
            runCatching { receiver.register(ctx) }
                .onFailure { env.error("[HOOK] SystemServerReceiver.register failed", it) }
            return
        }

        val task = object : Runnable {
            var attempts = 0

            override fun run() {
                attempts++
                val amReady = runCatching {
                    val smCls = Class.forName("android.os.ServiceManager")
                    val getServiceMethod = smCls.getMethod("getService", String::class.java)
                    getServiceMethod.invoke(null, Context.ACTIVITY_SERVICE) != null
                }.getOrDefault(false)

                if (amReady) {
                    env.info("[HOOK] ActivityManagerService is ready; registering receivers (attempt $attempts)")
                    runCatching {
                        receiver.register(ctx)
                        env.info("[HOOK] SystemServerReceiver registered successfully")
                    }.onFailure { th ->
                        env.error("[HOOK] SystemServerReceiver.register failed", th)
                    }
                } else if (attempts < 30) {
                    mainHandler.postDelayed(this, 500L)
                } else {
                    env.warn("[HOOK] AMS wait timeout after $attempts attempts; attempting register anyway")
                    runCatching { receiver.register(ctx) }
                        .onFailure { env.error("[HOOK] SystemServerReceiver register final attempt failed", it) }
                }
            }
        }

        mainHandler.post(task)
    }

    private fun clearLegacyHostFallback(env: HookEnv, ctx: Context) {
        runCatching {
            ctx.getSharedPreferences(ModuleConstants.PREFS_HOSTS_FALLBACK, Context.MODE_PRIVATE)
                .edit().clear().apply()
            env.info("[HOOK] cleared legacy host fallback prefs")
        }.onFailure { env.warn("[HOOK] failed to clear legacy host fallback prefs", it) }
    }

    /**
     * Fallback context source, used only when the module entry point could not
     * resolve the system Context via ActivityThread. Accepts only
     * Application.attach events from the "android" package (the system_server
     * app) and passes the Application object itself — unlike the raw attach
     * ContextImpl, an Application's applicationContext is never null, even
     * mid-boot inside makeApplicationInner.
     */
    private fun hookApplicationAttach(env: HookEnv, onAttach: (Context) -> Unit) {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        runCatching {
            env.xposed.hook(attach)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val app = chain.getThisObject() as? Application
                    val ctx = chain.getArgs().firstOrNull() as? Context
                    val result = chain.proceed(chain.getArgs().toTypedArray())

                    runCatching {
                        if (app != null && ctx != null) {
                            if (env.systemContext != null) return@runCatching

                            val effectiveContext = app.applicationContext ?: app
                            val pkg = runCatching { effectiveContext.packageName }.getOrDefault("<null>")

                            if (pkg == "android") {
                                onAttach(effectiveContext)
                            }
                        }
                    }.onFailure { env.warn("Application.attach handler failed", it) }

                    result
                }
        }.onFailure { env.error("[HOOK] Failed to hook Application.attach", it) }
    }

    private class DelegatingListener : UsbDeviceManagerHook.StateListener {
        private val pending = ConcurrentLinkedQueue<Boolean>()
        @Volatile var delegate: UsbDeviceManagerHook.StateListener? = null
            set(value) {
                if (value == null) return
                field = value
                var replayed = 0
                var evt = pending.poll()
                while (evt != null) {
                    runCatching { value.onUsbState(evt) }
                    replayed++
                    evt = pending.poll()
                }
                android.util.Log.i("USBManager", "[HOOK] DelegatingListener wired delegate; replayed $replayed buffered events")
            }

        override fun onUsbState(connected: Boolean) {
            val current = delegate
            if (current != null) {
                runCatching { current.onUsbState(connected) }
                return
            }
            pending.add(connected)
        }
    }
}