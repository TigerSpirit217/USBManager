package com.tiger.usbmanager.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.HostProviderClient
import com.tiger.usbmanager.bridge.ModuleSettingsSnapshot
import com.tiger.usbmanager.bridge.PendingApplyPayload
import com.tiger.usbmanager.policy.UsbMode

/**
 * Higher-level USB event handler. Consumes connect/disconnect events from
 * [UsbDeviceManagerHook] and runs the policy:
 *
 *  - CONNECT: every physical USB connection (device/peripheral mode, i.e. the phone
 *    plugged into a computer) launches [com.tiger.usbmanager.ui.UsbChooserActivity].
 *    The identification + "remember this computer" feature has been removed, so there
 *    is no auto-apply and no host fingerprinting.
 *  - DISCONNECT: turn ADB off (framework setting + stop adbd) so the daemon is
 *    guaranteed down after the cable is unplugged.
 *
 * OTG (the phone acting as USB **host**) never reaches this code path: it is handled
 * by UsbHostManager, not UsbDeviceManager, and never produces a gadget CONNECTED
 * state. So no chooser is shown for OTG peripherals, which the system already
 * recognizes natively and needs no ADB.
 *
 * Events are debounced on a main-thread handler so rapid state machine churn
 * (CONNECTED → CONFIGURED → …) doesn't fire the flow twice.
 *
 * ### Starting the chooser activity
 *
 * Modern Android has very strict "starting activities from the background" rules
 * (Android 10 Q+ → "activity starts" restricted; Android 14+ → even stricter).
 * system_server is an "exempt" process for many APIs, but the package manager
 * still enforces rule #2 ("apps running in a visible foreground window"). We use:
 *
 *   1. `context.startActivity` — works if the user is currently unlocked and on
 *      a system-owned window. Best-effort first.
 *   2. A **head-up Notification** with `fullScreenIntent` — Android will show a
 *      full-screen intent instead of a notification whenever the screen is on
 *      (and the notification itself when it's off). This works on Android 11+,
 *      is officially supported, and never requires `SYSTEM_ALERT_WINDOW`.
 *
 * Both launching paths converge on the pending-apply poll loop: the chooser's
 * choice is written into the module ContentProvider mailbox and polled from
 * system_server, which guarantees the configuration is applied even if the
 * broadcast path is throttled.
 */
internal class UsbStateWatcher(
    private val env: HookEnv,
    private val controller: UsbController,
    private val hostClient: HostProviderClient,
) : UsbDeviceManagerHook.StateListener {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastConnected: Boolean = false
    @Volatile private var pendingChooserToken: Int = 0
    /** Token of the last chooser notification posted via FullScreenIntent, so we can
     *  cancel it (and its lingering full-screen intent) when the cable is unplugged. */
    @Volatile private var lastChooserNotificationToken: Int = 0
    @Volatile private var notificationChannelCreated = false
    /** Lazily-resolved module package Context, used to localize the notification text
     *  from the module APK resources while running inside system_server. */
    @Volatile private var moduleContext: Context? = null
    /** Token of the currently-pending debounced handleConnect runnable, used to
     *  cancel it when a DISCONNECT event arrives before the debounce window
     *  elapses — this is the root cause of "sometimes unplugging a cable still
     *  shows the chooser": OEMs frequently emit a transient CONNECTED=true
     *  right before DISCONNECTED during USB teardown. */
    @Volatile private var pendingConnectRunnable: Runnable? = null
    /** Token of the currently-pending debounced handleDisconnect runnable. Used to
     *  cancel the DISCONNECT action when a CONNECTED rising edge arrives right after
     *  (USB re-enumeration from a mode / ADB change). Without this, the transient
     *  DISCONNECTED fired during re-enumeration runs handleDisconnect → turns ADB
     *  OFF, then the subsequent CONNECTED turns it back ON — the "ADB flickers ~3x
     *  before settling" bug. */
    @Volatile private var pendingDisconnectRunnable: Runnable? = null

    // ---- Pending-apply polling state ----
    // Id of the poll loop runnable so we can cancel it when USB disconnects or
    // when the user cancels the chooser (via CHOOSER_CLOSED).
    @Volatile private var pendingPollGeneration: Int = 0
    // Has a pending payload already been applied? Prevents double-apply when
    // both broadcast path + poll path deliver the same choice.
    @Volatile private var lastAppliedAtMs: Long = 0L

    // ---- Lock-deferral of the chooser (setting chooserWhileLocked=false) ----
    // When the USB mode chooser can't be shown while locked, we remember the pending
    // request and re-launch it once the user unlocks. A single pending slot is enough:
    // at most one request can be dialogged at a time, and it's cleared on disconnect
    // / cancel / new connect.
    //
    // We deliberately DO NOT hook ACTION_USER_PRESENT: on modern Android that protected
    // broadcast is frequently not delivered to dynamically-registered system_server
    // receivers (observed on NothingOS / Android 16), so unlock would never fire. Instead
    // we poll the keyguard state on the main handler (~500 ms) until unlocked, then launch.
    @Volatile private var deferredRequest: ChooserRequest? = null
    @Volatile private var deferredPollGeneration: Int = 0
    private var deferredPollRunnable: Runnable? = null

    /** A pending chooser launch: the preselected mode + ADB state to seed into the UI. */
    private data class ChooserRequest(val mode: UsbMode, val adb: Boolean)

    private companion object {
        /**
         * Debounce window (ms) for handleConnect. An onUsbState(true) has to
         *  "survive" this long before we actually launch the chooser. If a
         *  DISCONNECT event arrives in the meantime, the pending runnable is
         *  torn down and nothing fires. 300 ms is well below user-perceptible
         *  latency but long enough to absorb the USB teardown fake-positive. */
        private const val CONNECT_DEBOUNCE_MS = 300L

        /** Debounce window (ms) for handleDisconnect. A DISCONNECTED edge must
         *  "survive" this long before we turn ADB off. This is the symmetric
         *  guard to [CONNECT_DEBOUNCE_MS]: applying the chosen mode/ADB
         *  re-enumerates the USB gadget, which the kernel reports as a brief
         *  DISCONNECTED → CONNECTED pair. Without the debounce, the transient
         *  DISCONNECTED runs handleDisconnect and kills ADB, then CONNECTED
         *  re-enables it — the reported "ADB blinks ~3x before staying on".
         *  A genuine unplug never re-connects, so it still turns ADB off (just
         *  imperceptibly later). */
        private const val DISCONNECT_DEBOUNCE_MS = 800L

        private const val CHANNEL_ID = "usb_chooser"
        private const val NOTIF_TAG = "com.tiger.usbmanager.chooser"

        /** Poll interval for the ContentProvider pending-apply mailbox (ms). */
        private const val POLL_INTERVAL_MS = 1000L
        /** Maximum number of polls before giving up (~30 s). */
        private const val POLL_MAX_ATTEMPTS = 30
        /** Dedup window for applying the same pending payload (ms). */
        private const val APPLY_DEDUP_WINDOW_MS = 5_000L

        /** Interval (ms) at which we re-check whether the keyguard is unlocked while a
         *  chooser is deferred. Must be short enough to feel instant after unlocking. */
        private const val LOCK_UNLOCK_POLL_MS = 500L
        /** Guard so the poll never runs forever if the user never unlocks (~60 s). */
        private const val LOCK_UNLOCK_MAX_ATTEMPTS = 120
    }

    /**
     * Called by SystemServerReceiver whenever an ACTION_CHOOSER_CLOSED broadcast
     * arrives (i.e. the chooser activity is closed via any path). [outcome] is
     * one of "confirmed" | "cancelled" | "dismissed".
     */
    fun onChooserClosed(token: Int, outcome: String) {
        env.info("[WATCHER] onChooserClosed token=$token outcome=$outcome")
        handler.post {
            if (outcome != "confirmed") {
                // User cancelled / dismissed the chooser — no point polling
                // any further; there's nothing to apply.
                pendingPollGeneration += 1
                env.info("[WATCHER] chooser $outcome; cancelling pending-apply poll loop")
            }
        }
    }

    /**
     * Called by SystemServerReceiver / the poll path after applying the USB config
     * chosen via APPLY_USB_CONFIG (may arrive without a preceding CHOOSER_CLOSED=confirmed
     * on some OEM dispatch timings). Records the apply timestamp so the poll path can
     * dedup a broadcast-vs-poll double delivery.
     */
    fun onChooserApplied(mode: UsbMode, adb: Boolean) {
        env.info("[WATCHER] onChooserApplied mode=$mode adb=$adb")
        handler.post {
            lastAppliedAtMs = System.currentTimeMillis()
        }
    }

    override fun onUsbState(connected: Boolean) {
        env.info("[WATCHER] onUsbState($connected) called; lastConnected=$lastConnected")
        if (connected == lastConnected) {
            env.info("[WATCHER] no state change; debounced connected=$connected")
            return
        }
        lastConnected = connected
        if (connected) {
            // CONNECTED rising edge — debounce before running policy. This
            // absorbs the well-known OEM fake-positive where CONNECTED=true
            // fires 10 ms before DISCONNECTED during cable-unplug teardown.
            // A rising edge ALSO cancels any pending disconnect debounce: it
            // proves the preceding DISCONNECTED was just re-enumeration, so we
            // must NOT have turned ADB off (that was the flicker root cause).
            val pendingDisc = pendingDisconnectRunnable
            if (pendingDisc != null) {
                handler.removeCallbacks(pendingDisc)
                pendingDisconnectRunnable = null
                env.info("[WATCHER] rising edge cancelled pending disconnect debounce (re-enumeration; ADB left alone)")
            }
            val token = Runnable {
                pendingConnectRunnable = null
                runCatching {
                    env.info("[WATCHER] debounced connected=true survived ${CONNECT_DEBOUNCE_MS}ms; running handleConnect")
                    handleConnect()
                }.onFailure { env.error("[WATCHER] handleConnect FAILED after debounce", it) }
            }
            pendingConnectRunnable = token
            handler.postDelayed(token, CONNECT_DEBOUNCE_MS)
            env.info("[WATCHER] USB rising edge scheduled handleConnect after ${CONNECT_DEBOUNCE_MS}ms debounce")
        } else {
            // FALLING edge: cancel any pending CONNECT runnable first (it was
            // a false positive), then debounce handleDisconnect so a re-enumerating
            // DISCONNECT (immediately followed by CONNECT) doesn't turn ADB off.
            val pending = pendingConnectRunnable
            if (pending != null) {
                handler.removeCallbacks(pending)
                pendingConnectRunnable = null
                env.info("[WATCHER] USB falling edge cancelled pending handleConnect debounce (ghost connect suppressed)")
            }
            val token = Runnable {
                pendingDisconnectRunnable = null
                runCatching { handleDisconnect() }
                    .onFailure { env.error("[WATCHER] handleDisconnect FAILED", it) }
            }
            pendingDisconnectRunnable = token
            handler.postDelayed(token, DISCONNECT_DEBOUNCE_MS)
            env.info("[WATCHER] USB falling edge scheduled handleDisconnect after ${DISCONNECT_DEBOUNCE_MS}ms debounce")
        }
    }

    private fun handleConnect() {
        env.info("[WATCHER] handleConnect ENTER")
        val ctx = env.systemContext ?: run {
            env.warn("[WATCHER] systemContext not ready; skipping connect handling")
            return
        }
        env.info("[WATCHER] systemContext available: uid=${android.os.Process.myUid()}")

        // Every device-mode connection prompts. Preselect the chooser from the
        // user's default-mode / default-ADB settings (no host memory).
        val settings: ModuleSettingsSnapshot = runCatching { hostClient.settings() }
            .onFailure { env.warn("[WATCHER] settings lookup failed; using defaults", it) }
            .getOrDefault(ModuleSettingsSnapshot(UsbMode.CHARGING, false, true))
        env.info("[WATCHER] → ASK user preselect=${settings.defaultMode} adb=${settings.defaultAdb}")

        val request = ChooserRequest(mode = settings.defaultMode, adb = settings.defaultAdb)
        if (shouldDeferChooserForLock(ctx, settings)) {
            env.info("[WATCHER] device locked & chooserWhileLocked=false → defer chooser until unlock")
            deferChooserUntilUnlock(ctx, request)
        } else {
            launchChooser(ctx, request)
        }
    }

    /**
     * True when the chooser must be deferred because the device is locked and the
     * user asked NOT to show the chooser while locked (chooserWhileLocked=false).
     */
    private fun shouldDeferChooserForLock(ctx: Context, settings: ModuleSettingsSnapshot): Boolean {
        if (settings.chooserWhileLocked) {
            env.info("[WATCHER] chooserWhileLocked=true → show even while locked")
            return false
        }
        return isScreenLocked(ctx)
    }

    private fun isScreenLocked(ctx: Context): Boolean {
        return runCatching {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: run {
                env.warn("[WATCHER] KeyguardManager unavailable; assuming unlocked")
                return@runCatching false
            }
            km.isKeyguardLocked
        }.getOrDefault(false)
    }

    /**
     * Defers [request] until the user unlocks, by polling the keyguard state on the
     * main handler. Unlock is detected via [isScreenLocked] flipping to false, then we
     * launch the previously-stored chooser. Any previously deferred request is dropped.
     */
    private fun deferChooserUntilUnlock(ctx: Context, request: ChooserRequest) {
        cancelDeferredChooser()
        deferredRequest = request
        val generation = ++deferredPollGeneration
        env.info("[WATCHER] defer chooser (gen=$generation): polling keyguard every ${LOCK_UNLOCK_POLL_MS}ms up to ${LOCK_UNLOCK_MAX_ATTEMPTS} attempts")
        val runnable = object : Runnable {
            private var attempts = 0
            override fun run() {
                if (generation != deferredPollGeneration) return  // superseded
                attempts++
                if (attempts > LOCK_UNLOCK_MAX_ATTEMPTS) {
                    env.warn("[WATCHER] lock-poll exhausted after $attempts tries; dropping deferred chooser")
                    cancelDeferredChooser()
                    return
                }
                if (!isScreenLocked(ctx)) {
                    val req = deferredRequest
                    cancelDeferredChooser()
                    if (req == null) return
                    env.info("[WATCHER] keyguard unlocked after ${attempts} polls; launching deferred chooser")
                    runCatching { launchChooser(ctx, req) }
                        .onFailure { env.error("[WATCHER] deferred launchChooser threw", it) }
                } else {
                    handler.postDelayed(this, LOCK_UNLOCK_POLL_MS)
                }
            }
        }
        deferredPollRunnable = runnable
        handler.postDelayed(runnable, LOCK_UNLOCK_POLL_MS)
    }

    /** Drops any deferred chooser (disconnect/new connect) and stops the lock poll. */
    private fun cancelDeferredChooser() {
        deferredPollGeneration += 1
        deferredRequest = null
        val runnable = deferredPollRunnable ?: return
        deferredPollRunnable = null
        handler.removeCallbacks(runnable)
    }

    private fun handleDisconnect() {
        // USB cable unplugged — stop any in-flight pending-apply poll loop
        // (the chooser UI can't resolve anything useful for a cable that isn't
        // connected anymore, and we'd otherwise keep polling for 30 s).
        pendingPollGeneration += 1
        // Dismiss any lingering chooser FullScreenIntent notification so an
        // unhandled chooser doesn't stay on the lock screen / notification shade.
        cancelChooserNotification()
        // And explicitly tell any on-screen chooser activity to finish itself —
        // otherwise the window (launched via startActivity) stays up after unplug.
        dismissChooserActivity()
        // Drop any lock-deferred chooser: the cable is gone, no point re-launching
        // on a later unlock.
        cancelDeferredChooser()
        env.info("[WATCHER] handleDisconnect ENTER; cancelled pending-apply poll loop generation=$pendingPollGeneration")
        val settings: ModuleSettingsSnapshot = runCatching { hostClient.settings() }
            .onFailure { env.warn("[WATCHER] hostClient.settings lookup failed; using defaults", it) }
            .getOrDefault(ModuleSettingsSnapshot(UsbMode.CHARGING, false, true))
        env.info("[WATCHER] settings: defaultMode=${settings.defaultMode} defaultAdb=${settings.defaultAdb} disconnectAutoOffAdb=${settings.disconnectAutoOffAdb}")

        // Honor the "拔线自动关ADB" setting unconditionally on a real cable unplug.
        // There is deliberately NO grace/"ADB just turned on" exemption here: the
        // user has stated via the disconnectAutoOffAdb toggle that ADB must be off
        // once the cable is pulled. Transient re-enumeration during mode/ADB changes
        // is already absorbed by the CONNECT_DEBOUNCE / DISCONNECT_DEBOUNCE / rising-
        // edge-cancel machinery, so an unconditional auto-off never kills ADB that
        // legitimately reconnects within the same plug.
        val turnAdbOff: Boolean = settings.disconnectAutoOffAdb
        env.info("[WATCHER] decision: turnAdbOff=$turnAdbOff")
        if (turnAdbOff) {
            env.info("[WATCHER] → Turning ADB OFF (framework + adbd)")
            val ok = runCatching { controller.setAdbEnabled(false) }
                .onFailure { env.error("[WATCHER] setAdbEnabled(false) FAILED", it) }
                .getOrDefault(false)
            env.info("[WATCHER] setAdbEnabled(false) returned ok=$ok")
        } else {
            env.info("[WATCHER] leaving ADB untouched (拔线自动关ADB is OFF)")
        }
    }

    private fun cancelChooserNotification() {
        val token = lastChooserNotificationToken
        if (token == 0) return
        val ctx = env.systemContext ?: return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_TAG, token)
            lastChooserNotificationToken = 0
            env.info("[WATCHER] cancelled chooser notification token=$token")
        }.onFailure { env.warn("[WATCHER] failed to cancel chooser notification", it) }
    }

    private fun dismissChooserActivity() {
        val token = pendingChooserToken
        if (token == 0) return
        val ctx = env.systemContext ?: return
        runCatching {
            val intent = Intent(ModuleConstants.ACTION_DISMISS_CHOOSER).apply {
                `package` = ModuleConstants.MODULE_PACKAGE
                putExtra(ModuleConstants.EXTRA_TOKEN, token)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(intent)
            env.info("[WATCHER] sent DISMISS_CHOOSER token=$token")
        }.onFailure { env.warn("[WATCHER] failed to send DISMISS_CHOOSER", it) }
    }

    private fun launchChooser(ctx: Context, request: ChooserRequest) {
        pendingChooserToken += 1
        val token = pendingChooserToken
        lastChooserNotificationToken = token
        env.info("[WATCHER] launchChooser token=$token preselect=${request.mode} adb=${request.adb}")

        val intent = Intent().apply {
            component = ComponentName(ModuleConstants.MODULE_PACKAGE, ModuleConstants.CHOOSER_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                Intent.FLAG_RECEIVER_FOREGROUND
            putExtra(ModuleConstants.EXTRA_TOKEN, token)
            putExtra(ModuleConstants.EXTRA_USB_MODE, request.mode.wireValue)
            putExtra(ModuleConstants.EXTRA_ADB_ENABLED, request.adb)
        }
        env.info("[WATCHER] chooser intent component=${intent.component}")

        // ---- Path 1: direct startActivity (best-effort, works on older Android).
        //   On Android 14+ a startActivity from a system_server context may still be
        //   blocked despite the package-manager exemption; in that case this throws
        //   and we fall through to Path 2 (full-screen notification).
        val directOk = runCatching { ctx.startActivity(intent) }.onSuccess {
            env.info("[WATCHER] Chooser launched (startActivity direct) token=$token")
        }.onFailure {
            env.warn("[WATCHER] startActivity FAILED token=$token msg=${it.message}", it)
        }.isSuccess

        if (!directOk) {
            // ---- Path 2: high-priority Notification + fullScreenIntent.
            env.info("[WATCHER] Falling back to Notification FullScreenIntent token=$token")
            runCatching {
                ensureNotificationChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val pi = PendingIntent.getActivity(
                    ctx,
                    token,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val title = moduleString(ctx, R.string.chooser_notification_title, "USB connected")
                val text = moduleString(ctx, R.string.chooser_notification_text, "Tap to choose USB mode and ADB.")
                val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setCategory(Notification.CATEGORY_EVENT)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setVisibility(Notification.VISIBILITY_SECRET)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setFullScreenIntent(pi, true)
                        .setContentIntent(pi)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(ctx)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setAutoCancel(true)
                        .setFullScreenIntent(pi, true)
                        .setContentIntent(pi)
                        .build()
                }
                nm.notify(NOTIF_TAG, token, notif)
                env.info("[WATCHER] FullScreenIntent notification posted token=$token")
            }.onFailure {
                env.error("[WATCHER] Failed to post FullScreenIntent notification token=$token", it)
            }
        }

        // ---- Path 3 (for the *response*, not the launch): poll the mailbox.
        //   Regardless of how we launched the UI (startActivity or notification),
        //   the APPLY_USB_CONFIG broadcast path is unreliable on Android 14+.
        //   So we start a ~30 s poll loop on the main handler, which picks up
        //   the user choice from the provider mailbox if/when UsbConfigSender
        //   puts it there. This is the path that actually guarantees the apply.
        startPendingApplyPoll(token)
    }

    private fun ensureNotificationChannel(ctx: Context) {
        if (notificationChannelCreated) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            notificationChannelCreated = true
            return
        }
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                notificationChannelCreated = true
                return
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                moduleString(ctx, R.string.notification_channel_name, "USB chooser"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = moduleString(ctx, R.string.notification_channel_desc, "Shows a chooser when USB is plugged in")
                enableLights(false)
                enableVibration(false)
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
            notificationChannelCreated = true
            env.info("[WATCHER] Notification channel $CHANNEL_ID created OK")
        }.onFailure {
            env.warn("[WATCHER] Could not create notification channel", it)
        }
    }

    /**
     * Resolves a module-APK string resource while running inside system_server. The
     * system_server Context's resources map to the framework, not our APK, so we must
     * open a package-scoped Context for the module to read its own strings. Falls back
     * to [fallback] (English) when the module Context can't be created.
     */
    private fun moduleString(ctx: Context, resId: Int, fallback: String): String {
        val mc = moduleContext ?: runCatching {
            ctx.createPackageContext(ModuleConstants.MODULE_PACKAGE, 0).also { moduleContext = it }
        }.getOrNull()
        if (mc == null) return fallback
        return runCatching { mc.getString(resId) }.getOrElse { fallback }
    }

    // ---- Pending-apply mailbox polling (UsbConfigSender path 2 fallback) ----

    private fun startPendingApplyPoll(token: Int) {
        val generation = ++pendingPollGeneration
        env.info("[WATCHER] startPendingApplyPoll token=$token gen=$generation (max attempts=$POLL_MAX_ATTEMPTS interval=${POLL_INTERVAL_MS}ms)")
        handler.post { pollForPendingApply(token, generation, POLL_MAX_ATTEMPTS) }
    }

    private fun pollForPendingApply(token: Int, generation: Int, attemptsLeft: Int) {
        if (generation != pendingPollGeneration) {
            env.info("[WATCHER] pollForPendingApply token=$token gen=$generation stale (current=$pendingPollGeneration); aborting")
            return
        }
        if (attemptsLeft <= 0) {
            env.info("[WATCHER] pollForPendingApply token=$token gen=$generation exhausted; user never tapped OK in time")
            return
        }
        val payload: PendingApplyPayload? = runCatching { hostClient.getAndClearPendingApply() }
            .onFailure { env.warn("[WATCHER] pollForPendingApply hostClient.getAndClearPendingApply threw", it) }
            .getOrNull()
        if (payload != null && payload.confirmed) {
            env.info("[WATCHER] pollForPendingApply HIT token=$token gen=$generation attemptsLeft=$attemptsLeft mode=${payload.modeWire} adb=${payload.adb}")
            // Dedup: if the broadcast path also delivered APPLY_USB_CONFIG within
            // the last 5 s, don't apply twice (both toggle actions are idempotent,
            // but the log spam is undesirable).
            val now = System.currentTimeMillis()
            if (now - lastAppliedAtMs < APPLY_DEDUP_WINDOW_MS) {
                env.info("[WATCHER] pollForPendingApply dedup skip: broadcast path already applied ${now - lastAppliedAtMs}ms ago")
            } else {
                lastAppliedAtMs = now
                applyPendingPayload(payload)
            }
            // Payload delivered; stop polling (nothing more will arrive).
            pendingPollGeneration += 1
            return
        }
        if (payload != null && !payload.confirmed) {
            env.info("[WATCHER] pollForPendingApply got payload but confirmed=false (cancelled); stopping")
            pendingPollGeneration += 1
            return
        }
        // Nothing there yet — reschedule.
        val nextAttempts = attemptsLeft - 1
        if (nextAttempts <= 0) {
            env.info("[WATCHER] pollForPendingApply token=$token gen=$generation done — no choice ever submitted")
            return
        }
        handler.postDelayed({
            pollForPendingApply(token, generation, nextAttempts)
        }, POLL_INTERVAL_MS)
    }

    /** Mirrors the logic of SystemServerReceiver.handleApply so the poll path
     *  behaves identically to the broadcast path. */
    private fun applyPendingPayload(payload: PendingApplyPayload) {
        val mode = UsbMode.fromWire(payload.modeWire)
        val adb = payload.adb
        env.info("[WATCHER] applyPendingPayload (via poll) mode=$mode adb=$adb")

        Handler(Looper.getMainLooper()).post {
            val applied = runCatching { controller.applyConfig(mode, adb) }
                .onFailure { env.error("[WATCHER] applyPendingPayload: controller.applyConfig threw", it) }
                .getOrDefault(false)
            env.info("[WATCHER] applyPendingPayload: controller.applyConfig effective=$applied (true == both mode+adb applied)")

            runCatching { onChooserApplied(mode, adb) }
                .onFailure { env.error("[WATCHER] applyPendingPayload: onChooserApplied threw", it) }
        }
    }
}