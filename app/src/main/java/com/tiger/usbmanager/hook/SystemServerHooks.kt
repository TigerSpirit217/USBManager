package com.tiger.usbmanager.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tiger.usbmanager.bridge.HostProviderClient
import com.tiger.usbmanager.policy.UsbPolicyEngine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Installs the full system_server hook bundle:
 *
 *  1. Hook [Application.attach] to capture the system context.
 *  2. Install [UsbDeviceManagerHook] + [AdbServiceHook] early so the
 *     UsbDeviceManager constructor (which may run shortly after boot) is caught.
 *  3. Once the context is ready, build the policy engine, state watcher and the
 *     runtime broadcast receiver, and wire the watcher into the USB hook via a
 *     delegating listener (so events that fire before context-ready are safe).
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
     * If provided and Application.attach does NOT fire within [FALLBACK_DELAY_MS],
     * we use it directly to bootstrap the watcher path. This works around a
     * NothingOS / Android 16 issue where Application.attach on the system
     * "android" package doesn't seem to fire through the hooked method.
     *
     * ALL diagnostic log lines emitted inside this method use
     * `module.log()` DIRECTLY (not HookEnv). This guarantees lines appear
     * even if HookEnv itself fails to construct or SSLOG is not initialised.
     */
    fun install(
        xposed: XposedInterface,
        module: XposedModule,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
        earlyContext: Context?,
    ) {
        // --- 1. Diagnostic line before anything else, so even if the rest of
        // this method throws we know it was entered.
        module.log(Log.INFO, "USBManager",
            "[HOOK] install ENTER; classLoader=${classLoader.javaClass.name} earlyContext=${earlyContext?.javaClass?.name}")

        val env = HookEnv(xposed, module, classLoader)
        env.info("[HOOK] install start; HookEnv constructed OK")

        val rootFallback = RootFallback(env)
        val controller = UsbController(env, rootFallback)

        // Delegating listener: wired to the real watcher once the context is ready.
        val listenerHolder = DelegatingListener()

        // --- 2. Install UsbDeviceManager + AdbService hooks first (these don't
        // depend on a system Context; their events get queued in the listener).
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

        // --- 3. Context acquisition: Application.attach if possible, else fall
        // back to the earlyContext obtained earlier by ActivityThread.
        val onContextReadyRan = AtomicBoolean(false)
        val runOnce = fun(ctx: Context) {
            if (!onContextReadyRan.compareAndSet(false, true)) return
            env.systemContext = ctx
            module.log(Log.INFO, "USBManager",
                "[HOOK] system_context ready via " +
                    (if (ctx === earlyContext) "earlyContext(ActivityThread)" else "Application.attach") +
                    " pkg=" + runCatching { ctx.packageName }.getOrDefault("?") +
                    " uid=" + android.os.Process.myUid())
            env.info("[HOOK] system_context committed; calling onContextReady")
            runCatching { onContextReady(env, controller, listenerHolder) }
                .onSuccess { env.info("[HOOK] onContextReady OK") }
                .onFailure { th -> env.error("[HOOK] onContextReady failed", th) }
        }

        runCatching {
            hookApplicationAttach(env) { ctx -> runOnce(ctx) }
        }.onFailure {
            module.log(Log.ERROR, "USBManager", "[HOOK] hookApplicationAttach failed", it)
            env.error("[HOOK] hookApplicationAttach failed", it)
        }

        // --- 4. If earlyContext was provided, schedule a fallback check.
        if (earlyContext != null) {
            val mainHandler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
            if (mainHandler != null) {
                mainHandler.postDelayed({
                    if (!onContextReadyRan.get()) {
                        module.log(Log.WARN, "USBManager",
                            "[HOOK] Application.attach never fired within ${FALLBACK_DELAY_MS}ms; using ActivityThread earlyContext fallback")
                        env.warn("[HOOK] Application.attach never fired; using earlyContext fallback")
                        runOnce(earlyContext)
                    }
                }, FALLBACK_DELAY_MS)
            } else {
                // Can't post to main looper — fallback immediately on this thread.
                if (!onContextReadyRan.get()) {
                    module.log(Log.WARN, "USBManager", "[HOOK] MainLooper unavailable; using earlyContext fallback immediately")
                    runOnce(earlyContext)
                }
            }
        } else {
            module.log(Log.WARN, "USBManager",
                "[HOOK] no earlyContext available; Application.attach MUST fire or watcher path is dead")
        }

        val rootOk = runCatching { rootFallback.isAvailable() }.getOrDefault(false)
        env.info("[HOOK] install end; rootFallbackAvailable=$rootOk earlyContext=$earlyContext")
        module.log(Log.INFO, "USBManager",
            "[HOOK] install EXIT; rootFallbackAvailable=$rootOk earlyContext=${earlyContext != null}")
    }

    private const val FALLBACK_DELAY_MS = 3000L

    private fun onContextReady(
        env: HookEnv,
        controller: UsbController,
        listenerHolder: DelegatingListener,
    ) {
        val ctx = env.requireContext()
        env.info("[HOOK] onContextReady start; contentResolver ok=${ctx.contentResolver != null}")
        val hostClient = HostProviderClient(ctx)
        val policyEngine = UsbPolicyEngine(hostClient)
        env.info("[HOOK] policyEngine + hostClient constructed; knownHostCount=${runCatching { hostClient.list().size }.getOrNull()}")
        val watcher = UsbStateWatcher(env, policyEngine, controller, hostClient)
        env.info("[HOOK] UsbStateWatcher built; wiring to listenerHolder (will replay pending events if any)")
        listenerHolder.delegate = watcher
        env.info("[HOOK] UsbStateWatcher wired as delegate")

        val receiver = SystemServerReceiver(env, controller, hostClient, watcher /* stateListener */, watcher /* watcher for chooser callbacks */)
        runCatching { receiver.register(ctx) }
            .onFailure { env.error("[HOOK] SystemServerReceiver.register failed", it) }
        env.info("[HOOK] system_server USB module fully ready")
    }

    private fun hookApplicationAttach(env: HookEnv, onAttach: (Context) -> Unit) {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        env.info("[HOOK] Hooking Application.attach(Context) method=${attach.declaringClass.name}")
        runCatching {
            env.xposed.hook(attach)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val app = chain.getThisObject() as? Application
                    val ctx = chain.getArgs().firstOrNull() as? Context
                    val result = chain.proceed(chain.getArgs().toTypedArray())
                    runCatching {
                        if (app != null && ctx != null) {
                            val myUid = android.os.Process.myUid()
                            val maybeSysContext = ctx.applicationContext ?: ctx
                            val pkg = runCatching { maybeSysContext.packageName }.getOrDefault("<null>")
                            val ctxCls = maybeSysContext.javaClass.name
                            val appCls = app.javaClass.name

                            // If systemContext is already set → guaranteed duplicate.
                            if (env.systemContext != null) {
                                env.info("[HOOK] Application.attach DUP: uid=$myUid pkg=$pkg ctxCls=$ctxCls; systemContext already set → skip")
                                return@runCatching
                            }

                            // Heuristic to accept this attach as "the system server one":
                            //   a) packageName == "android" (canonical AOSP behaviour), OR
                            //   b) we're running as uid 1000 (= SYSTEM_UID) and device
                            //      uptime is under 10 minutes (i.e. still inside boot,
                            //      which is when system_server's Application.attach runs).
                            // uid == 1000 is the reliable check — only system_server is uid 1000.
                            val uptimeSec = (android.os.SystemClock.elapsedRealtime() / 1000L).toInt()
                            val looksLikeSystemServer = (pkg == "android") ||
                                (myUid == 1000 && uptimeSec < 600)
                            env.info(
                                "[HOOK] Application.attach candidate: uid=$myUid pkg=$pkg " +
                                    "ctxCls=$ctxCls appCls=$appCls uptime=${uptimeSec}s " +
                                    "accepted=$looksLikeSystemServer"
                            )
                            if (looksLikeSystemServer) {
                                env.systemContext = maybeSysContext
                                onAttach(maybeSysContext)
                                env.info("[HOOK] Application.attach → system_context committed pkg=$pkg")
                            }
                        } else {
                            env.warn("[HOOK] Application.attach: missing app=$app ctx=$ctx")
                        }
                    }.onFailure { env.warn("Application.attach handler failed", it) }
                    result
                }
            env.info("[HOOK] Application.attach hook installed OK")
        }.onFailure { env.error("[HOOK] Failed to hook Application.attach", it) }
    }

    /**
     * Forwards USB state events to a lazy delegate (UsbStateWatcher) that requires
     * the system Context to build.
     *
     * Threading: USB updateState callbacks arrive on UsbHandler threads and
     * `delegate` is assigned once on the system main thread via Application.attach.
     * We use a CopyOnWriteArrayList for the pending buffer to ensure writes from
     * USB threads are safely visible to the setter thread.
     *
     * Two critical invariants:
     *  1. If the delegate is already set → deliver synchronously on the caller
     *     thread AND clear any pending events so they don't double-fire.
     *  2. If the delegate is not yet set → push to pending queue; the next call to
     *     `delegate = ...` will drain the queue in FIFO order on the setter thread.
     */
    private class DelegatingListener : UsbDeviceManagerHook.StateListener {
        private val pending = ConcurrentLinkedQueue<Boolean>()
        @Volatile var delegate: UsbDeviceManagerHook.StateListener? = null
            set(value) {
                if (value == null) return
                field = value
                // Drain any events that arrived before Context was ready.
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
            // Delegate not wired yet; buffer for replay after Context arrives.
            pending.add(connected)
            val n = pending.size
            // Log a line every so often to help diagnose "delegate never set" without spamming.
            if (n == 1 || n % 4 == 0) {
                android.util.Log.i("USBManager", "[HOOK] DelegatingListener delegate=null; buffering event connected=$connected pending=$n (onContextReady not called yet!)")
            }
        }
    }
}
