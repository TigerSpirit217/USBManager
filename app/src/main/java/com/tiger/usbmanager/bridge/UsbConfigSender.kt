package com.tiger.usbmanager.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.policy.UsbMode

/**
 * Sends the user's USB choice from the module app UI (UsbChooserActivity) to the
 * system_server hook runtime — via TWO redundant paths so the user never sees
 * "tap OK and nothing happens" again.
 *
 * ## Path 1 (fast, optimistic): Broadcast ACTION_APPLY_USB_CONFIG
 *
 * `setPackage("android")` is NOT used here: system_server registers receivers
 * **dynamically** and they are NOT associated with any installed app package.
 * `setPackage("android")` would silently drop the broadcast. Instead we tag
 * the intent with [ModuleConstants.BRIDGE_TOKEN] and the receiver drops
 * anything that doesn't match.
 *
 * ## Path 2 (reliable, polled): ContentProvider "pending-apply mailbox"
 *
 * On many OEM Android builds (NothingOS, MIUI, ColorOS) — especially Android 14+
 * — broadcasts from a third-party uid (u0_aXXX) to system_server's dynamically
 * registered receivers are throttled by the "background broadcast dispatch quota"
 * or by the binder-invocation firewall, so path 1 is silently dropped.
 *
 * To work around this we additionally write the full user choice into the
 * module-owned [HostProvider] via `resolver.call(METHOD_PUT_PENDING_APPLY, …)`.
 * The [com.tiger.usbmanager.hook.UsbStateWatcher] in system_server polls this
 * mailbox every ~1 s after launching the chooser (for up to 30 s) and if it
 * finds a payload there, applies it exactly the same way as path 1. Provider
 * `call()` is synchronous binder — not subject to any quota or firewall — so it
 * always works.
 */
object UsbConfigSender {

    private const val TAG = "USBManager"

    /** `Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND` (added in API 34).
     *  On older compiles we simply pass the numeric value — it's harmless to
     *  set flags the system doesn't recognise (they're masked off). */
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND_FALLBACK: Int = 0x01000000 or 0x00800000

    fun apply(
        context: Context,
        mode: UsbMode,
        adb: Boolean,
        remember: Boolean,
        auto: Boolean,
        hostKey: String,
        hostName: String,
    ) {
        // ---- Path 1: broadcast (best-effort; dropped on many OEM Android 14+ builds) ----
        val intent = Intent(ModuleConstants.ACTION_APPLY_USB_CONFIG).apply {
            addFlags(
                Intent.FLAG_RECEIVER_FOREGROUND or FLAG_RECEIVER_INCLUDE_BACKGROUND_FALLBACK,
            )
            putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
            putExtra(ModuleConstants.EXTRA_USB_MODE, mode.wireValue)
            putExtra(ModuleConstants.EXTRA_ADB_ENABLED, adb)
            putExtra(ModuleConstants.EXTRA_REMEMBER, remember)
            putExtra(ModuleConstants.EXTRA_AUTO, auto)
            putExtra(ModuleConstants.EXTRA_HOST_KEY, hostKey)
            putExtra(ModuleConstants.EXTRA_HOST_NAME, hostName)
        }
        Log.i(TAG, "[SENDER] path1:sendBroadcast action=${intent.action} mode=$mode adb=$adb remember=$remember host=${hostName.take(24)} keyLen=${hostKey.length}")
        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            Log.i(TAG, "[SENDER] path1: sendBroadcast returned OK")
        }.onFailure {
            Log.e(TAG, "[SENDER] path1: sendBroadcast FAILED", it)
        }

        // ---- Path 2: ContentProvider pending-apply mailbox (always works) ----
        Log.i(TAG, "[SENDER] path2: writing pending-apply to HostProvider")
        val client = HostProviderClient(context)
        val payload = PendingApplyPayload(
            modeWire = mode.wireValue,
            adb = adb,
            remember = remember,
            auto = auto,
            hostKey = hostKey,
            hostName = hostName,
            confirmed = true,
        )
        val written = runCatching { client.putPendingApply(payload) }
            .onFailure { Log.e(TAG, "[SENDER] path2: putPendingApply exception", it) }
            .getOrDefault(false)
        Log.i(TAG, "[SENDER] path2: putPendingApply written=$written")
    }

    /**
     * Send a "user closed the chooser" notification to system_server without
     * applying any config. Used to distinguish:
     *   - "confirmed" (user pressed +ve button) → remember last choice
     *   - "cancelled" (user pressed -ve button) → apply default disconnect rules
     *   - "dismissed" (user backed away / system killed the activity) → same as cancelled
     *
     * This tells the watcher whether to honour the user's "ADB on" choice on
     * the next cable-disconnect event (see disconnectAutoOffAdb logic in
     * UsbStateWatcher.handleDisconnect).
     */
    fun sendChooserClosed(
        context: Context,
        token: Int,
        outcome: String, // "confirmed" | "cancelled" | "dismissed"
        hostKey: String,
    ) {
        val intent = Intent(ModuleConstants.ACTION_CHOOSER_CLOSED).apply {
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND or FLAG_RECEIVER_INCLUDE_BACKGROUND_FALLBACK)
            putExtra(ModuleConstants.EXTRA_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
            putExtra(ModuleConstants.EXTRA_TOKEN, token)
            putExtra(ModuleConstants.EXTRA_OUTCOME, outcome)
            putExtra(ModuleConstants.EXTRA_HOST_KEY, hostKey)
        }
        Log.i(TAG, "[SENDER] chooser-closed token=$token outcome=$outcome")
        runCatching {
            context.sendBroadcast(intent)
        }.onFailure {
            Log.e(TAG, "[SENDER] chooser-closed sendBroadcast failed", it)
        }
    }
}
