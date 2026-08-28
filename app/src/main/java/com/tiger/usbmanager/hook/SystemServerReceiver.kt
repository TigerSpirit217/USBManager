package com.tiger.usbmanager.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.bridge.HostProviderClient
import com.tiger.usbmanager.policy.HostInfo
import com.tiger.usbmanager.policy.UsbMode

/**
 * Runtime-registered broadcast receiver living inside system_server. Catches the
 * [ModuleConstants.ACTION_APPLY_USB_CONFIG] / [ModuleConstants.ACTION_CHOOSER_CLOSED]
 * broadcasts sent by the chooser activity (in the module app process) and applies
 * the chosen configuration.
 *
 * ## Trust model
 *
 * Historically we tried registering the receiver with `permission=BRIDGE_PERMISSION`,
 * but since `system_server` is signed with the PLATFORM key (not the module app key)
 * Android would NEVER grant a custom `signature-level` permission to uid 1000; the
 * receiver silently rejected every broadcast. Instead we include a compile-time
 * shared token ([ModuleConstants.BRIDGE_TOKEN]) in each broadcast extra and drop
 * anything that doesn't match. The same token now gates mutating [HostProvider]
 * calls for the same reason.
 */
internal class SystemServerReceiver(
    private val env: HookEnv,
    private val controller: UsbController,
    private val hostClient: HostProviderClient,
    private val stateListener: UsbDeviceManagerHook.StateListener?,
    /** Optional reference to the watcher (for chooser-closed signals). */
    private val watcher: UsbStateWatcher?,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val callerPkg = runCatching {
                intent.getStringExtra("android.intent.extra.PACKAGE_NAME")
            }.getOrNull()
            val callingUid = runCatching {
                android.os.Binder.getCallingUidOrThrow()
            }.getOrNull() ?: android.os.Process.myUid()

            // ----- Token gating (only for our custom bridge actions, not the
            //       public system ACTION_USB_STATE broadcast). -----
            if (action in listOf(
                    ModuleConstants.ACTION_APPLY_USB_CONFIG,
                    ModuleConstants.ACTION_CHOOSER_CLOSED,
                    ModuleConstants.ACTION_QUERY_STATUS,
                )
            ) {
                val receivedToken = intent.getStringExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN)
                if (receivedToken != ModuleConstants.BRIDGE_TOKEN) {
                    env.warn(
                        "[RX] Drop broadcast $action from uid=$callingUid pkg=$callerPkg: " +
                            "bridge-token mismatch (got ${receivedToken?.take(16)}… expected len=${ModuleConstants.BRIDGE_TOKEN.length})",
                    )
                    return
                }
                env.info("[RX] bridge-token OK for $action")
            }

            env.info("[RX] Broadcast received action=$action callerPkg=$callerPkg callingUid=$callingUid")
            when (action) {
                ModuleConstants.ACTION_APPLY_USB_CONFIG -> handleApply(intent)
                ModuleConstants.ACTION_CHOOSER_CLOSED -> handleChooserClosed(intent)
                ModuleConstants.ACTION_QUERY_STATUS -> env.info("[RX] QUERY_STATUS ping received (no-op)")
                ACTION_USB_STATE -> handleUsbStateBroadcast(intent)
                else -> env.warn("[RX] Ignoring unknown action $action")
            }
        }
    }

    fun register(context: Context) {
        // 1) Internal bridge receiver.
        //    NOTE: NO permission arg on registerReceiver; trust is established
        //    via EXTRA_BRIDGE_TOKEN inside each intent (see class KDoc).
        val bridgeFilter = IntentFilter().apply {
            addAction(ModuleConstants.ACTION_APPLY_USB_CONFIG)
            addAction(ModuleConstants.ACTION_CHOOSER_CLOSED)
            addAction(ModuleConstants.ACTION_QUERY_STATUS)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        env.info("[RX] Registering bridge receiver actions=${bridgeFilter.actionsIterator().asSequence().toList()} ctxPkg=${context.packageName} uid=${android.os.Process.myUid()} (token-gated, no perm)")
        runCatching {
            context.registerReceiver(receiver, bridgeFilter)
            env.info("[RX] Bridge receiver registered OK")
        }.onFailure { env.error("[RX] Failed to register bridge receiver", it) }

        // 2) USB_STATE sticky broadcast fallback.
        //    UsbDeviceManagerHook SHOULD catch updateState(...) before this broadcast
        //    is even sent. However, on newer Android releases (Android 16+) where the
        //    internal UsbDeviceManager method signature changed without warning, this
        //    broadcast is the public contract that will always exist. Registering it
        //    gives us a guaranteed second event source as a safety net.
        if (stateListener != null) {
            val usbFilter = IntentFilter(ACTION_USB_STATE).apply {
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            runCatching {
                // No permission: USB_STATE is a protected sticky broadcast; only the
                // system sends it, so receiving it never leaks data to unprivileged
                // callers. We pass null for the permission parameter.
                context.registerReceiver(receiver, usbFilter)
                env.info("[RX] ACTION_USB_STATE fallback receiver registered OK")
            }.onFailure { env.error("[RX] Failed to register USB_STATE fallback receiver", it) }
        } else {
            env.warn("[RX] No stateListener provided; USB_STATE fallback receiver skipped")
        }
    }

    /**
     * Handles the public Android `android.hardware.usb.action.USB_STATE` sticky
     * broadcast and forwards the connected state to the same watcher that the
     * UsbDeviceManagerHook drives.
     *
     * The `UsbManager` extras documented on developer.android.com are:
     *   - boolean "connected"
     *   - boolean "configured"
     *   - String  "function" (comma-separated list on newer Android)
     *   - int     "usb_data_state" (data transfer enabled/disabled)
     */
    private fun handleUsbStateBroadcast(intent: Intent) {
        val connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false)
        val configured = intent.getBooleanExtra(EXTRA_USB_CONFIGURED, false)
        val function = intent.getStringExtra(EXTRA_USB_FUNCTIONS) ?: ""
        env.info("[RX] USB_STATE connected=$connected configured=$configured functions=$function")
        val listener = stateListener ?: run {
            env.warn("[RX] USB_STATE received but no stateListener wired; dropping")
            return
        }
        runCatching {
            listener.onUsbState(connected)
        }.onFailure { env.error("[RX] stateListener.onUsbState threw from USB_STATE fallback", it) }
    }

    private fun handleChooserClosed(intent: Intent) {
        val token = intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0)
        val outcome = intent.getStringExtra(ModuleConstants.EXTRA_OUTCOME) ?: "dismissed"
        val hostKey = intent.getStringExtra(ModuleConstants.EXTRA_HOST_KEY).orEmpty()
        env.info("[RX] CHOOSER_CLOSED token=$token outcome=$outcome hostKeyLen=${hostKey.length}")
        val w = watcher ?: run {
            env.warn("[RX] CHOOSER_CLOSED but no watcher reference; ignoring")
            return
        }
        runCatching {
            w.onChooserClosed(token, outcome, hostKey)
        }.onFailure { env.error("[RX] watcher.onChooserClosed threw", it) }
    }

    private fun handleApply(intent: Intent) {
        val mode = UsbMode.fromWire(intent.getStringExtra(ModuleConstants.EXTRA_USB_MODE))
        val adb = intent.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, false)
        val remember = intent.getBooleanExtra(ModuleConstants.EXTRA_REMEMBER, false)
        val auto = intent.getBooleanExtra(ModuleConstants.EXTRA_AUTO, true)
        val hostKey = intent.getStringExtra(ModuleConstants.EXTRA_HOST_KEY).orEmpty()
        val hostName = intent.getStringExtra(ModuleConstants.EXTRA_HOST_NAME).orEmpty()

        env.info("[RX] APPLY_USB_CONFIG mode=$mode adb=$adb remember=$remember auto=$auto hostKeyLen=${hostKey.length} hostName=$hostName")

        Handler(Looper.getMainLooper()).post {
            env.info("[RX] posting on main handler; applyConfig running")
            val applied = runCatching { controller.applyConfig(mode, adb) }
                .onFailure { env.error("[RX] applyConfig FAILED (exception)", it) }
                .getOrDefault(false)
            env.info("[RX] applyConfig effective=$applied (true == both mode+adb applied)")

            if (remember && hostKey.isNotBlank()) {
                val host = HostInfo(
                    name = hostName.ifBlank { "PC ${hostKey.take(8)}" },
                    hostKey = hostKey,
                    usbMode = mode.wireValue,
                    adb = adb,
                    auto = auto,
                )
                val saved = runCatching { hostClient.save(host) }
                    .onFailure { t ->
                        env.error("[RX] hostClient.save FAILED — trying SharedPreferences fallback", t)
                        saveHostFallback(env.requireContext(), host)
                    }.getOrDefault(false)
                val fallbackOk = if (!saved) {
                    saveHostFallback(env.requireContext(), host)
                } else true
                env.info("[RX] Host saved=$saved name=${host.name} key=${host.hostKey.take(16)}… fallbackOk=$fallbackOk")
            } else {
                env.info("[RX] remember=$remember hostKey=${hostKey.isNotBlank()}; skipping save")
            }

            // Also record this APPLY as a confirmed user choice so the
            // disconnect-auto-off logic skips it for ~30 s.
            runCatching { watcher?.onChooserApplied(mode, adb, hostKey) }
                .onFailure { env.error("[RX] watcher.onChooserApplied threw", it) }
        }
    }

    /**
     * Save a host entry directly to system_server's own SharedPreferences.
     * Used as fallback when `HostProviderClient` (a ContentProvider in the app
     * process) is unavailable — system_server doesn't need an app alive to
     * remember known hosts, so write our own copy; the watcher reads from
     * both sources on connect.
     */
    private fun saveHostFallback(ctx: Context, host: HostInfo): Boolean {
        val prefs = ctx.getSharedPreferences("usbmanager_hosts_fallback", Context.MODE_PRIVATE)
        val json = usbBridgeGson.toJson(host)
        prefs.edit()
            .putString(fallbackKeyFor(host.hostKey), json)
            .apply()
        env.info("[RX] SharedPreferences fallback: wrote host name=${host.name}")
        return true
    }

    /** Matches HostProviderClient.fallbackKeyFor so both copies use the same key. */
    private fun fallbackKeyFor(hostKey: String): String =
        hostKey.take(32) + "_" + (hostKey.hashCode().toLong() and 0xffffffffL)

    private companion object {
        /** Shared Gson (single instance) for the fallback host serialization. */
        val usbBridgeGson: com.google.gson.Gson by lazy { com.tiger.usbmanager.bridge.UsbBridgeContract.GSON }

        // ---- Android public USB broadcast (sticky) ----
        /** @see android.hardware.usb.UsbManager.ACTION_USB_STATE */
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        /** Boolean extra: is the USB cable physically attached? */
        const val EXTRA_USB_CONNECTED = "connected"
        /** Boolean extra: is USB currently in a configured (functional) state? */
        const val EXTRA_USB_CONFIGURED = "configured"
        /** String extra: comma-separated current USB function list */
        const val EXTRA_USB_FUNCTIONS = "functions"
    }
}
