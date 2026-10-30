package com.tiger.usbmanager.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tiger.usbmanager.bridge.AdbAuthorizations
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the framework unpair operation, which updates the keystore and notifies adbd. */
internal class AdbAuthorizationReceiver(private val env: HookEnv) : BroadcastReceiver() {
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(context: Context) {
        val filter = IntentFilter(AdbAuthorizations.ACTION)
        // broadcastPermission checks the SENDER, which is signed with our app key.
        // system_server does not need to hold this permission. Unlike a static token,
        // this gate also authenticates requests on Android versions before sentFromUid.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, AdbAuthorizations.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(this, filter, AdbAuthorizations.PERMISSION, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbAuthorizations.ACTION || !isOrderedBroadcast) return
        val pending = goAsync()
        val completed = AtomicBoolean(false)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                pending.resultCode = AdbAuthorizations.FAILED
                pending.finish()
            }
        }
        fun finish(code: Int, devices: List<AdbAuthorizations.Device> = emptyList()) {
            if (!completed.compareAndSet(false, true)) return
            timeoutHandler.removeCallbacks(timeout)
            try {
                pending.setResult(code, null, Bundle().apply {
                    putParcelableArrayList(AdbAuthorizations.DEVICES, ArrayList(devices.map {
                        Bundle().apply {
                            putString(AdbAuthorizations.NAME, it.name)
                            putString(AdbAuthorizations.FINGERPRINT, it.fingerprint)
                        }
                    }))
                })
            } finally { pending.finish() }
        }
        timeoutHandler.postDelayed(timeout, 5_000L)
        runCatching {
            val manager = AdbServiceHook.debuggingManager()
            val handler = manager?.javaClass?.fieldOrNull("mHandler")?.get(manager) as? Handler
            if (manager == null || handler == null || !FrameworkAdbAuthorizations.supports(manager)) {
                finish(AdbAuthorizations.UNSUPPORTED)
                return
            }
            val fingerprint = intent.getStringExtra(AdbAuthorizations.FINGERPRINT)
            if (!handler.post {
                if (completed.get() || android.os.SystemClock.elapsedRealtime() >= deadline) {
                    finish(AdbAuthorizations.FAILED)
                    return@post
                }
                runCatching {
                    val before = FrameworkAdbAuthorizations.list(manager)
                    if (before.size > AdbAuthorizations.MAX_DEVICES) {
                        finish(AdbAuthorizations.LIMIT_EXCEEDED)
                    } else if (fingerprint == null) {
                        finish(AdbAuthorizations.OK, before)
                    } else if (!FrameworkAdbAuthorizations.revoke(manager, fingerprint, before)) {
                        finish(AdbAuthorizations.DISCONNECT_REQUIRED)
                    } else {
                        // unpairDevice queues work on this same handler, which then queues
                        // persistence. Read back after both, including adbd's disconnect reply.
                        if (!handler.postDelayed({
                            if (!completed.get()) runCatching {
                                val after = FrameworkAdbAuthorizations.list(manager)
                                finish(if (after.none { it.fingerprint == fingerprint }) AdbAuthorizations.OK
                                    else AdbAuthorizations.FAILED, after)
                            }.onFailure {
                                env.warn("ADB authorization verification failed", it)
                                finish(AdbAuthorizations.FAILED)
                            }
                        }, 500L)) finish(AdbAuthorizations.FAILED)
                    }
                }.onFailure {
                    env.warn("ADB authorization request failed", it)
                    finish(AdbAuthorizations.FAILED)
                }
            }) finish(AdbAuthorizations.FAILED)
        }.onFailure {
            env.warn("ADB authorization API unavailable", it)
            finish(AdbAuthorizations.UNSUPPORTED)
        }
    }
}
