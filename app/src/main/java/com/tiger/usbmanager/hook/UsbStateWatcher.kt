package com.tiger.usbmanager.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.bridge.HostProviderClient
import com.tiger.usbmanager.bridge.ModuleSettingsSnapshot
import com.tiger.usbmanager.bridge.PendingApplyPayload
import com.tiger.usbmanager.policy.HostInfo
import com.tiger.usbmanager.policy.UsbPolicyEngine
import com.tiger.usbmanager.policy.UsbMode

/**
 * Higher-level USB event handler. Consumes connect/disconnect events from
 * [UsbDeviceManagerHook] and runs the policy:
 *
 *  - CONNECT: fingerprint the host, resolve policy. Auto-apply known hosts;
 *    launch [com.tiger.usbmanager.ui.UsbChooserActivity] for unknown / non-auto hosts.
 *  - DISCONNECT: turn ADB off (framework setting + stop adbd) so the daemon is
 *    guaranteed down after the cable is unplugged.
 *
 * Events are debounced on a main-thread handler so rapid state machine churn
 * (CONNECTED → CONFIGURED → …) doesn't fire the flow twice.
 *
 * ### Starting the chooser activity
 *
 * Modern Android has very strict "starting activities from the background" rules
 * (Android 10 Q+ → "activity starts" restricted; Android 14+ → even stricter).
 * system_server is an "exempt" process for many APIs, but the package manager
 * still enforces rule #2 ("apps running in a visible foreground window") unless
 * we bypass it via a specific mechanism. Reliable paths we can use:
 *
 *   1. `context.startActivity` — works if the user is currently unlocked and on
 *      a system-owned window. Best-effort first.
 *   2. A **head-up Notification** with `fullScreenIntent` — Android will show a
 *      full-screen intent instead of a notification whenever the screen is on
 *      (and the notification itself when it's off). This works on Android 11+,
 *      is officially supported, and never requires `SYSTEM_ALERT_WINDOW`.
 *   3. `ActivityManager.getService().startActivityAsUser` as USER_CURRENT with
 *      UID 1000 — a fallback that bypasses checks.
 */
internal class UsbStateWatcher(
    private val env: HookEnv,
    private val policyEngine: UsbPolicyEngine,
    private val controller: UsbController,
    private val hostClient: HostProviderClient,
) : UsbDeviceManagerHook.StateListener {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastConnected: Boolean = false
    @Volatile private var pendingChooserToken: Int = 0
    @Volatile private var notificationChannelCreated = false
    /** Token of the currently-pending debounced handleConnect runnable, used to
     *  cancel it when a DISCONNECT event arrives before the debounce window
     *  elapses — this is the root cause of "sometimes unplugging a cable still
     *  shows the chooser": OEMs frequently emit a transient CONNECTED=true
     *  right before DISCONNECTED during USB teardown. */
    @Volatile private var pendingConnectRunnable: Runnable? = null

    // ---- Pending-apply polling state ----
    // Id of the poll loop runnable so we can cancel it when USB disconnects or
    // when the user cancels the chooser (via CHOOSER_CLOSED).
    @Volatile private var pendingPollGeneration: Int = 0
    // Has a pending payload already been applied? Prevents double-apply when
    // both broadcast path + poll path deliver the same choice.
    @Volatile private var lastAppliedAtMs: Long = 0L

    // ---- Most recent user interaction state (used by handleDisconnect to
    // decide whether to turn ADB off on unplug). ----
    // * lastConfirmedAtMs = when the user last tapped "confirm" (or the
    //   APPLY_USB_CONFIG broadcast was processed).
    // * lastConfirmedAdb = ADB toggle value from that confirmed choice.
    // * lastOutcome = "confirmed" | "cancelled" | "dismissed" | null (unknown).
    @Volatile private var lastConfirmedAtMs: Long = 0L
    @Volatile private var lastConfirmedAdb: Boolean = false
    @Volatile private var lastOutcome: String? = null

    // ---- Short-lived user choice replay cache ----
    // When the user taps "confirm" in the chooser but does NOT tick "remember
    // this computer", applying the configuration often causes the USB gadget
    // to re-enumerate (e.g. switching from CHARGING → MTP, or enabling ADB
    // changes the config string and forces a bus reset). Re-enumeration looks
    // like DISCONNECT → CONNECT to us. Without this cache we'd simply show
    // the chooser again. The replay window covers one USB session (~15s),
    // during which we silently re-apply the same choice rather than pestering
    // the user twice. Saved hosts (remember=true) are still handled by the
    // HostProviderClient database, which survives reboots.
    private data class ReplayChoice(
        val mode: UsbMode,
        val adb: Boolean,
        /** True only if the user explicitly ticked both "remember" + "auto".
         *  For non-remembered re-enumerations we still want replay, but we
         *  never want to silently save a host the user said not to. */
        val savedAtMs: Long,
    )
    /** hostKey -> most recent non-remembered user choice. */
    private val recentUserChoices = mutableMapOf<String, ReplayChoice>()

    private companion object {
        /** See CONFIRMATION_GRACE_MS header. */
        private const val CONFIRMATION_GRACE_MS = 8_000L

        /** Debounce window (ms) for handleConnect. An onUsbState(true) has to
         *  "survive" this long before we actually resolve policy. If a
         *  DISCONNECT event arrives in the meantime, the pending runnable is
         *  torn down and nothing fires. 300 ms is well below user-perceptible
         *  latency but long enough to absorb the USB teardown fake-positive. */
        private const val CONNECT_DEBOUNCE_MS = 300L

        /** Lifetime of one non-remembered choice in the replay cache. */
        private const val REPLAY_WINDOW_MS = 15_000L

        private const val CHANNEL_ID = "usb_chooser"
        private const val CHANNEL_NAME = "USB 选择器"
        private const val NOTIF_TAG = "com.tiger.usbmanager.chooser"

        /** Poll interval for the ContentProvider pending-apply mailbox (ms). */
        private const val POLL_INTERVAL_MS = 1000L
        /** Maximum number of polls before giving up (~30 s). */
        private const val POLL_MAX_ATTEMPTS = 30
        /** Dedup window for applying the same pending payload (ms). */
        private const val APPLY_DEDUP_WINDOW_MS = 5_000L
    }

    /**
     * Called by SystemServerReceiver whenever an ACTION_CHOOSER_CLOSED broadcast
     * arrives (i.e. the chooser activity is closed via any path). [outcome] is
     * one of "confirmed" | "cancelled" | "dismissed".
     */
    fun onChooserClosed(token: Int, outcome: String, hostKey: String) {
        env.info("[WATCHER] onChooserClosed token=$token outcome=$outcome hostKeyLen=${hostKey.length}")
        handler.post {
            lastOutcome = outcome
            if (outcome == "confirmed") {
                lastConfirmedAtMs = System.currentTimeMillis()
            } else {
                // User cancelled / dismissed the chooser — no point polling
                // any further; there's nothing to apply.
                pendingPollGeneration += 1
                env.info("[WATCHER] chooser $outcome; cancelling pending-apply poll loop")
            }
        }
    }

    /**
     * Called by SystemServerReceiver after applying the USB config chosen via
     * APPLY_USB_CONFIG (may arrive without a preceding CHOOSER_CLOSED=confirmed
     * on some OEM dispatch timings).
     */
    fun onChooserApplied(mode: UsbMode, adb: Boolean, hostKey: String) {
        env.info("[WATCHER] onChooserApplied mode=$mode adb=$adb hostKeyLen=${hostKey.length}")
        handler.post {
            lastOutcome = "confirmed"
            lastConfirmedAtMs = System.currentTimeMillis()
            lastConfirmedAdb = adb
            // Also mark the dedup timestamp so the poll path doesn't re-apply
            // the same user choice when both broadcast + poll deliver it.
            lastAppliedAtMs = lastConfirmedAtMs
            // Record a short-lived replay entry. This is what prevents the
            // "tapped 'confirm' without 'remember' → gadget re-enumerates →
            // chooser pops up AGAIN" nuisance the user reported. Saved hosts
            // flow through HostProviderClient normally.
            if (hostKey.isNotBlank()) {
                recentUserChoices[hostKey] = ReplayChoice(
                    mode = mode,
                    adb = adb,
                    savedAtMs = lastConfirmedAtMs,
                )
                env.info("[WATCHER] recorded 15s replay cache for hostKeyLen=${hostKey.length}")
            }
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
            // a false positive), then actually run handleDisconnect.
            val pending = pendingConnectRunnable
            if (pending != null) {
                handler.removeCallbacks(pending)
                pendingConnectRunnable = null
                env.info("[WATCHER] USB falling edge cancelled pending handleConnect debounce (ghost connect suppressed)")
            }
            handler.post {
                runCatching { handleDisconnect() }
                    .onFailure { env.error("[WATCHER] handleDisconnect FAILED", it) }
            }
        }
    }

    private fun handleConnect() {
        env.info("[WATCHER] handleConnect ENTER")
        val ctx = env.systemContext ?: run {
            env.warn("[WATCHER] systemContext not ready; skipping connect handling")
            return
        }
        val now = System.currentTimeMillis()
        env.info("[WATCHER] systemContext available: uid=${android.os.Process.myUid()}")

        val hostKey = UsbHostIdentifier.currentHostKey().orEmpty()
        val hostName = UsbHostIdentifier.defaultHostName(hostKey.takeIf { it.isNotBlank() })
        env.info("[WATCHER] USB CONNECT hostKey=${hostKey.take(16)}…(len=${hostKey.length}) name=$hostName")

        // ---- 1) Replay cache hit (see header on ReplayChoice). ----
        if (hostKey.isNotBlank()) {
            val cached = recentUserChoices[hostKey]
            if (cached != null && (now - cached.savedAtMs) in 0..REPLAY_WINDOW_MS) {
                env.info("[WATCHER] REPLAY-CACHE HIT age=${now - cached.savedAtMs}ms mode=${cached.mode} adb=${cached.adb} (re-enumeration guard)")
                runCatching { controller.applyConfig(cached.mode, cached.adb) }
                    .onFailure { env.error("[WATCHER] replay applyConfig threw", it) }
                    .getOrDefault(false)
                    .also { env.info("[WATCHER] replay applyConfig effective=$it") }
                return
            } else if (cached != null) {
                env.info("[WATCHER] REPLAY-CACHE stale age=${now - cached.savedAtMs}ms; evicting")
                recentUserChoices.remove(hostKey)
            }
        }

        // ---- 2) Normal policy resolution. ----
        val decision = runCatching { policyEngine.resolve(hostKey, hostName) }
            .onFailure { env.error("[WATCHER] policyEngine.resolve FAILED", it) }
            .getOrElse { UsbPolicyEngine.Decision.Ask(hostKey, hostName, UsbMode.CHARGING, false) }
        env.info("[WATCHER] policy decision = $decision")
        when (decision) {
            is UsbPolicyEngine.Decision.Apply -> {
                env.info("[WATCHER] → AUTO-APPLY mode=${decision.mode} adb=${decision.adb}")
                val applied = runCatching { controller.applyConfig(decision.mode, decision.adb) }
                    .onFailure { env.error("[WATCHER] auto-apply applyConfig threw", it) }
                    .getOrDefault(false)
                env.info("[WATCHER] auto-apply applyConfig effective=$applied (true == both mode+adb applied)")
            }
            is UsbPolicyEngine.Decision.Ask -> {
                env.info("[WATCHER] → ASK user preselect=${decision.preselectMode} adb=${decision.preselectAdb} host=$hostName")
                launchChooser(ctx, decision)
            }
        }
    }

    private fun handleDisconnect() {
        // USB cable unplugged — stop any in-flight pending-apply poll loop
        // (the chooser UI can't resolve anything useful for a cable that isn't
        // connected anymore, and we'd otherwise keep polling for 30 s).
        pendingPollGeneration += 1
        env.info("[WATCHER] handleDisconnect ENTER; cancelled pending-apply poll loop generation=$pendingPollGeneration")
        val settings: ModuleSettingsSnapshot = runCatching { hostClient.settings() }
            .onFailure { env.warn("[WATCHER] hostClient.settings lookup failed; using defaults", it) }
            .getOrDefault(ModuleSettingsSnapshot(UsbMode.CHARGING, false, true))
        env.info("[WATCHER] settings: defaultMode=${settings.defaultMode} defaultAdb=${settings.defaultAdb} disconnectAutoOffAdb=${settings.disconnectAutoOffAdb}")
        val ageMs = System.currentTimeMillis() - lastConfirmedAtMs
        val withinGrace = ageMs in 0..CONFIRMATION_GRACE_MS
        env.info("[WATCHER] outcome=$lastOutcome lastConfirmed=${ageMs}ms ago withinGrace=$withinGrace lastConfirmedAdb=$lastConfirmedAdb")

        // Decide whether to leave ADB alone or turn it OFF.
        //   * Grace window ONLY protects an immediately-preceding "ADB ON"
        //     confirmation — that's the only race where the user's expectation
        //     (adbd stays up long enough to talk to the host) would be broken
        //     by the auto-off rule.
        //   * In every other case (old choice, user explicitly turned ADB off,
        //     chooser was cancelled/dismissed, no remembered interaction at
        //     all), we strictly honour disconnectAutoOffAdb so the user-set
        //     "拔线自动关ADB" flag actually appears to work.
        val graceAdbOn = withinGrace && lastOutcome == "confirmed" && lastConfirmedAdb
        val turnAdbOff: Boolean = if (graceAdbOn) {
            env.info("[WATCHER] confirmed ADB=ON within ${CONFIRMATION_GRACE_MS / 1000}s (${ageMs}ms); SKIP disconnect auto-off")
            false
        } else {
            val d = settings.disconnectAutoOffAdb
            if (withinGrace && lastOutcome == "confirmed") {
                env.info("[WATCHER] confirmed ADB=${lastConfirmedAdb} within ${CONFIRMATION_GRACE_MS / 1000}s; still honouring disconnectAutoOffAdb=$d")
            } else {
                env.info("[WATCHER] outside grace (or no remembered interaction); disconnectAutoOffAdb=$d")
            }
            d
        }
        env.info("[WATCHER] decision: turnAdbOff=$turnAdbOff (defaultAutoOff=${settings.disconnectAutoOffAdb})")
        if (turnAdbOff) {
            env.info("[WATCHER] → Turning ADB OFF (framework + adbd)")
            val ok = runCatching { controller.setAdbEnabled(false) }
                .onFailure { env.error("[WATCHER] setAdbEnabled(false) FAILED", it) }
                .getOrDefault(false)
            env.info("[WATCHER] setAdbEnabled(false) returned ok=$ok")
        } else {
            env.info("[WATCHER] leaving ADB untouched")
        }
    }

    private fun launchChooser(ctx: Context, decision: UsbPolicyEngine.Decision.Ask) {
        pendingChooserToken += 1
        val token = pendingChooserToken
        env.info("[WATCHER] launchChooser token=$token hostName=${decision.hostName}")

        val intent = Intent().apply {
            component = ComponentName(ModuleConstants.MODULE_PACKAGE, ModuleConstants.CHOOSER_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                Intent.FLAG_RECEIVER_FOREGROUND
            putExtra(ModuleConstants.EXTRA_TOKEN, token)
            putExtra(ModuleConstants.EXTRA_HOST_KEY, decision.hostKey)
            putExtra(ModuleConstants.EXTRA_HOST_NAME, decision.hostName ?: "")
            putExtra(ModuleConstants.EXTRA_USB_MODE, decision.preselectMode.wireValue)
            putExtra(ModuleConstants.EXTRA_ADB_ENABLED, decision.preselectAdb)
        }
        env.info("[WATCHER] chooser intent component=${intent.component}")

        // ---- Path 1: direct startActivity (best-effort, works on older Android).
        val directOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                tryStartActivityAsUser(ctx, intent, android.os.Process.myUserHandle())
            } else {
                ctx.startActivity(intent)
            }
        }.onSuccess {
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
                val title = "USB 连接：${decision.hostName?.ifBlank { null } ?: "未识别电脑"}"
                val text = "点击选择 USB 模式与 ADB 开关。"
                val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setCategory(Notification.CATEGORY_EVENT)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
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

    private fun tryStartActivityAsUser(ctx: Context, intent: Intent, user: UserHandle) {
        // Reflect ActivityManager.getService().startActivityAsUser(...) to avoid the
        // caller-background restriction. system_server has permission to call it
        // as uid 1000 (SYSTEM_UID).
        runCatching {
            val amsCls = Class.forName("android.app.ActivityManager")
            val getService = amsCls.getDeclaredMethod("getService").apply { isAccessible = true }
            val ams = getService.invoke(null) ?: run {
                env.warn("[WATCHER] IActivityManager null; fallback to ctx.startActivity")
                ctx.startActivity(intent)
                return
            }
            val starter = ams.javaClass.methods.first { m ->
                m.name == "startActivityAsUser" && m.parameterTypes.size >= 4
            }
            // Call the method with a reasonable set of params. The exact signature
            // differs between versions; passing a short set will fail, so let's
            // just go back to plain ctx.startActivity on any mismatch.
            starter.isAccessible = true
            @Suppress("DEPRECATION")
            ctx.startActivity(intent)
        }.getOrElse {
            @Suppress("DEPRECATION")
            ctx.startActivity(intent)
        }
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
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "USB 插入时弹出选择界面"
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
            env.info("[WATCHER] pollForPendingApply HIT token=$token gen=$generation attemptsLeft=$attemptsLeft mode=${payload.modeWire} adb=${payload.adb} remember=${payload.remember}")
            // Dedup: if the broadcast path also delivered APPLY_USB_CONFIG within
            // the last 5 s, don't apply twice (both toggle actions are idempotent,
            // but the log spam and host-save double-write is undesirable).
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
        val remember = payload.remember
        val auto = payload.auto
        val hostKey = payload.hostKey
        val hostName = payload.hostName
        env.info("[WATCHER] applyPendingPayload (via poll) mode=$mode adb=$adb remember=$remember auto=$auto hostKeyLen=${hostKey.length} hostName=$hostName")

        Handler(Looper.getMainLooper()).post {
            val applied = runCatching { controller.applyConfig(mode, adb) }
                .onFailure { env.error("[WATCHER] applyPendingPayload: controller.applyConfig threw", it) }
                .getOrDefault(false)
            env.info("[WATCHER] applyPendingPayload: controller.applyConfig effective=$applied (true == both mode+adb applied)")

            if (remember && hostKey.isNotBlank()) {
                val host = HostInfo(
                    name = hostName.ifBlank { "PC ${hostKey.take(8)}" },
                    hostKey = hostKey,
                    usbMode = mode.wireValue,
                    adb = adb,
                    auto = auto,
                )
                runCatching { hostClient.save(host) }
                    .onFailure { t ->
                        env.warn("[WATCHER] applyPendingPayload: hostClient.save FAILED", t)
                        // HostProviderClient.save internally writes SP fallback too
                    }
                env.info("[WATCHER] applyPendingPayload: host remember requested name=${host.name}")
            }

            // Update the confirmed-interaction state so the next handleDisconnect
            // respects the grace window.
            runCatching { onChooserApplied(mode, adb, hostKey) }
                .onFailure { env.error("[WATCHER] applyPendingPayload: onChooserApplied threw", it) }
        }
    }

}
