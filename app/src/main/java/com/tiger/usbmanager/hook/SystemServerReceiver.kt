package com.tiger.usbmanager.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.ModuleConstants
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
 * anything that doesn't match.
 */
internal class SystemServerReceiver(
    private val env: HookEnv,
    private val controller: UsbController,
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

            // ----- UID authorization (authoritative gate). -----
            // BRIDGE_TOKEN is a compile-time constant embedded in the APK, so any
            // third-party app that decompiles it could forge our bridge broadcasts.
            // The calling UID, however, cannot be spoofed. Only two senders are
            // legitimate: the module app process itself (UsbConfigSender) and
            // system_server (SYSTEM_UID 1000, which emits the public ACTION_USB_STATE
            // broadcast). Anything else is rejected before we even look at the token.
            val systemUid = android.os.Process.SYSTEM_UID
            val moduleUid = runCatching {
                context.packageManager.getApplicationInfo(
                    ModuleConstants.MODULE_PACKAGE, 0,
                ).uid
            }.getOrDefault(-1)
            if (callingUid != systemUid && callingUid != moduleUid) {
                env.warn(
                    "[RX] Drop broadcast $action from uid=$callingUid pkg=$callerPkg: " +
                        "not an authorized sender (expected system=$systemUid or module=$moduleUid)",
                )
                return
            }

            // ----- Token gating (defense-in-depth; only for our custom bridge
            //       actions, not the public system ACTION_USB_STATE broadcast). -----
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, bridgeFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, bridgeFilter)
            }
            env.info("[RX] Bridge receiver registered OK")
        }.onFailure { env.error("[RX] Failed to register bridge receiver", it) }

        // 2) USB_STATE sticky broadcast fallback.
        if (stateListener != null) {
            val usbFilter = IntentFilter(ACTION_USB_STATE).apply {
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, usbFilter)
                }
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
        env.info("[RX] CHOOSER_CLOSED token=$token outcome=$outcome")
        val w = watcher ?: run {
            env.warn("[RX] CHOOSER_CLOSED but no watcher reference; ignoring")
            return
        }
        runCatching {
            w.onChooserClosed(token, outcome)
        }.onFailure { env.error("[RX] watcher.onChooserClosed threw", it) }
    }

    private fun handleApply(intent: Intent) {
        val mode = UsbMode.fromWire(intent.getStringExtra(ModuleConstants.EXTRA_USB_MODE))
        val adb = intent.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, false)

        env.info("[RX] APPLY_USB_CONFIG mode=$mode adb=$adb")

        Handler(Looper.getMainLooper()).post {
            env.info("[RX] posting on main handler; applyConfig running")
            val applied = runCatching { controller.applyConfig(mode, adb) }
                .onFailure { env.error("[RX] applyConfig FAILED (exception)", it) }
                .getOrDefault(false)
            env.info("[RX] applyConfig effective=$applied (true == both mode+adb applied)")

            runCatching { watcher?.onChooserApplied(mode, adb) }
                .onFailure { env.error("[RX] watcher.onChooserApplied threw", it) }
        }
    }

    private companion object {
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