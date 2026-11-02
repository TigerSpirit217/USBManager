package com.tiger.usbmanager.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import androidx.core.os.BundleCompat
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.auth.RecognitionSettings
import com.tiger.usbmanager.auth.RootAuthManager
import com.tiger.usbmanager.auth.AuthResult
import java.util.concurrent.Executors

/**
 * ContentProvider in the module app process, queried by system_server via
 * [ContentResolver.call]. It serves settings, the pending-apply mailbox, and runs
 * root authentication in the app mount namespace on behalf of system_server.
 */
class HostProvider : ContentProvider() {
    private val authLock = Any()
    private val authWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "usb-auth-app-worker").apply { isDaemon = true }
    }
    private var authSession = 0L
    private var authResult: AuthResult? = null

    /** A single session owns the request; the lock makes claiming and completion atomic. */
    private val applyLock = Any()
    private var pendingApply: PendingApplyPayload? = null
    private var pendingWrittenAt = 0L
    private var chooserToken = 0
    private var chooserConfig: Bundle? = null
    private val applyResults = linkedMapOf<String, Int>()
    private lateinit var runtimePrefs: android.content.SharedPreferences
    private var bootCount = -1
    private val packageLock = Any()
    private var packageRequest = ""
    private var packageNamesResult: Bundle? = null
    private val packageApplications = arrayListOf<ApplicationInfo>()

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ModuleSettings.init(ctx)
        runtimePrefs = ctx.getSharedPreferences("usbmanager_runtime", android.content.Context.MODE_PRIVATE)
        bootCount = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        // Notification sessions remain usable after process death, but never after reboot.
        if (bootCount >= 0 && runtimePrefs.getInt("boot", -2) == bootCount) {
            chooserToken = runtimePrefs.getInt("token", 0)
            if (chooserToken > 0) chooserConfig = Bundle().apply {
                putString(UsbBridgeContract.KEY_MODE, runtimePrefs.getString("mode", "none"))
                putBoolean(UsbBridgeContract.KEY_ADB, runtimePrefs.getBoolean("adb", false))
            }
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return runCatching {
            // ----- UID authorization (authoritative gate, applies to ALL methods). -----
            val callingUid = android.os.Binder.getCallingUid()
            if (callingUid != android.os.Process.SYSTEM_UID &&
                callingUid != android.os.Process.myUid()
            ) {
                Log.w(TAG, "call($method) blocked: uid=$callingUid not authorized")
                return@runCatching null
            }

            // Package names are private to the module UI; only system_server can
            // publish a snapshot and only the app can begin/read a request.
            when (method) {
                UsbBridgeContract.METHOD_BEGIN_PACKAGE_NAMES,
                UsbBridgeContract.METHOD_GET_PACKAGE_NAMES,
                UsbBridgeContract.METHOD_GET_PACKAGE_APPS ->
                    if (callingUid != android.os.Process.myUid()) return@runCatching null
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY,
                UsbBridgeContract.METHOD_GET_CHOOSER ->
                    if (callingUid != android.os.Process.myUid()) return@runCatching null
                UsbBridgeContract.METHOD_GET_PACKAGE_REQUEST,
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_NAMES,
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_APPS ->
                    if (callingUid != android.os.Process.SYSTEM_UID) return@runCatching null
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY,
                UsbBridgeContract.METHOD_BEGIN_CHOOSER,
                UsbBridgeContract.METHOD_SET_APPLY_RESULT ->
                    if (callingUid != android.os.Process.SYSTEM_UID) return@runCatching null
            }

            // Mutating operations additionally require the shared BRIDGE_TOKEN.
            when (method) {
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY,
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY,
                UsbBridgeContract.METHOD_START_AUTH,
                UsbBridgeContract.METHOD_GET_AUTH_RESULT,
                UsbBridgeContract.METHOD_CANCEL_AUTH,
                UsbBridgeContract.METHOD_BEGIN_PACKAGE_NAMES,
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_NAMES,
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_APPS,
                -> {
                    val tok = extras?.getString(UsbBridgeContract.KEY_BRIDGE_TOKEN)
                    if (tok != ModuleConstants.BRIDGE_TOKEN) {
                        Log.w(TAG, "call($method) blocked: bridge-token mismatch")
                        return@runCatching null
                    }
                }
            }
            when (method) {
                UsbBridgeContract.METHOD_GET_SETTINGS -> handleGetSettings()
                UsbBridgeContract.METHOD_BEGIN_PACKAGE_NAMES -> synchronized(packageLock) {
                    val request = extras?.getString(UsbBridgeContract.KEY_PACKAGE_REQUEST).orEmpty()
                    if (request.isEmpty() || request.length > 64) return@synchronized null
                    packageRequest = request
                    packageNamesResult = null
                    packageApplications.clear()
                    Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
                }
                UsbBridgeContract.METHOD_GET_PACKAGE_REQUEST -> synchronized(packageLock) {
                    Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, arg == packageRequest && packageRequest.isNotEmpty()) }
                }
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_NAMES -> synchronized(packageLock) {
                    if (extras?.getString(UsbBridgeContract.KEY_PACKAGE_REQUEST) != packageRequest) return@synchronized null
                    packageNamesResult = Bundle(extras).apply { putBoolean(UsbBridgeContract.KEY_PACKAGE_READY, true) }
                    Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
                }
                UsbBridgeContract.METHOD_GET_PACKAGE_NAMES -> synchronized(packageLock) {
                    if (arg == packageRequest) packageNamesResult?.let(::Bundle) ?: Bundle() else null
                }
                UsbBridgeContract.METHOD_PUBLISH_PACKAGE_APPS -> synchronized(packageLock) {
                    if (extras == null || extras.getString(UsbBridgeContract.KEY_PACKAGE_REQUEST) != packageRequest)
                        return@synchronized null
                    val apps = BundleCompat.getParcelableArrayList(extras, UsbBridgeContract.KEY_PACKAGE_APPS, ApplicationInfo::class.java)
                        ?: return@synchronized null
                    if (apps.size > UsbBridgeContract.PACKAGE_PAGE_SIZE) return@synchronized null
                    packageApplications.addAll(apps)
                    Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
                }
                UsbBridgeContract.METHOD_GET_PACKAGE_APPS -> synchronized(packageLock) {
                    if (arg != packageRequest) return@synchronized null
                    val start = (extras?.getInt(UsbBridgeContract.KEY_PACKAGE_PAGE, 0) ?: 0).toLong() * UsbBridgeContract.PACKAGE_PAGE_SIZE
                    val page = if (start < 0 || start >= packageApplications.size) arrayListOf()
                        else ArrayList(packageApplications.subList(start.toInt(),
                            minOf(start.toInt() + UsbBridgeContract.PACKAGE_PAGE_SIZE, packageApplications.size)))
                    Bundle().apply { putParcelableArrayList(UsbBridgeContract.KEY_PACKAGE_APPS, page) }
                }
                UsbBridgeContract.METHOD_PUT_PENDING_APPLY -> handlePutPendingApply(extras)
                UsbBridgeContract.METHOD_GET_AND_CLEAR_PENDING_APPLY -> handleGetAndClearPendingApply(arg)
                UsbBridgeContract.METHOD_BEGIN_CHOOSER -> synchronized(applyLock) {
                    val token = extras?.getInt(UsbBridgeContract.KEY_CHOOSER_TOKEN) ?: 0
                    if (token <= 0) return@synchronized null
                    // Commit before activity launch so reclaiming the app cannot lose the session.
                    if (!runtimePrefs.edit().putInt("boot", bootCount).putInt("token", token)
                            .putString("mode", extras?.getString(UsbBridgeContract.KEY_MODE))
                            .putBoolean("adb", extras?.getBoolean(UsbBridgeContract.KEY_ADB) ?: false).commit())
                        return@synchronized null
                    pendingApply = null
                    chooserToken = token
                    chooserConfig = Bundle(extras)
                    applyResults.replaceAll { _, result -> if (result == 0) ModuleConstants.RESULT_LIVE_FAILED else result }
                    Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
                }
                UsbBridgeContract.METHOD_GET_CHOOSER -> synchronized(applyLock) {
                    if (chooserToken > 0 && arg == chooserToken.toString()) chooserConfig?.let(::Bundle) else null
                }
                UsbBridgeContract.METHOD_CLOSE_CHOOSER -> synchronized(applyLock) {
                    if (arg == chooserToken.toString()) {
                        runtimePrefs.edit().clear().commit()
                        chooserToken = 0; chooserConfig = null; pendingApply = null
                        applyResults.replaceAll { _, result -> if (result == 0) ModuleConstants.RESULT_LIVE_FAILED else result }
                    }
                    Bundle()
                }
                UsbBridgeContract.METHOD_SET_APPLY_RESULT -> synchronized(applyLock) {
                    val result = extras?.getInt(UsbBridgeContract.KEY_APPLY_RESULT)
                    if (arg != null && applyResults[arg] == 0 && result in setOf(ModuleConstants.RESULT_LIVE_APPLIED, ModuleConstants.RESULT_LIVE_FAILED))
                        applyResults[arg] = checkNotNull(result)
                    Bundle()
                }
                UsbBridgeContract.METHOD_GET_APPLY_RESULT -> synchronized(applyLock) {
                    if (applyResults[arg] == 0 && android.os.SystemClock.elapsedRealtime() - pendingWrittenAt >= 12_000L) {
                        applyResults[checkNotNull(arg)] = ModuleConstants.RESULT_LIVE_FAILED
                        if (pendingApply?.requestId == arg) pendingApply = null
                    }
                    Bundle().apply { putInt(UsbBridgeContract.KEY_APPLY_RESULT, applyResults[arg] ?: ModuleConstants.RESULT_LIVE_FAILED) }
                }
                UsbBridgeContract.METHOD_START_AUTH -> handleStartAuth(extras)
                UsbBridgeContract.METHOD_GET_AUTH_RESULT -> handleGetAuthResult(extras)
                UsbBridgeContract.METHOD_CANCEL_AUTH -> handleCancelAuth(extras)
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "call($method) failed", it)
        }.getOrNull()
    }

    private fun handleStartAuth(extras: Bundle?): Bundle {
        val id = extras?.getLong(UsbBridgeContract.KEY_AUTH_SESSION, 0L) ?: 0L
        val ctx = context
        if (id == 0L || ctx == null || !RecognitionSettings.isEnabled(ctx))
            return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, false) }
        synchronized(authLock) {
            authSession = id
            authResult = null
        }
        // Provider binder threads return immediately. Root and the USB handshake
        // run in the app's mount namespace, where KernelSU exposes `su`.
        authWorker.execute {
            if (synchronized(authLock) { authSession != id }) return@execute
            val result = runCatching { RootAuthManager.recognize(ctx) }
                .getOrElse { AuthResult("FAILED", detail = it.message.orEmpty()) }
            synchronized(authLock) {
                if (authSession == id) authResult = result
            }
            Log.i(TAG, "recognition session=$id outcome=${result.status} host=${result.id.take(12)}")
        }
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    private fun handleGetAuthResult(extras: Bundle?): Bundle = synchronized(authLock) {
        val id = extras?.getLong(UsbBridgeContract.KEY_AUTH_SESSION, 0L) ?: 0L
        val result = if (id == authSession) authResult else null
        Bundle().apply {
            putBoolean(UsbBridgeContract.KEY_AUTH_READY, result != null)
            if (result != null) {
                putString(UsbBridgeContract.KEY_AUTH_STATUS, result.status)
                putString(UsbBridgeContract.KEY_AUTH_ID, result.id)
                putString(UsbBridgeContract.KEY_AUTH_LABEL, result.label)
                putString(UsbBridgeContract.KEY_AUTH_MODE, result.mode?.wireValue)
                putBoolean(UsbBridgeContract.KEY_AUTH_ADB, result.adb)
                putString(UsbBridgeContract.KEY_AUTH_DETAIL, result.detail)
            }
        }
    }

    private fun handleCancelAuth(extras: Bundle?): Bundle {
        val id = extras?.getLong(UsbBridgeContract.KEY_AUTH_SESSION, 0L) ?: 0L
        val cancelled = synchronized(authLock) {
            if (id != 0L && id == authSession) { authSession = 0L; authResult = null; true } else false
        }
        if (cancelled) context?.let { ctx ->
            val revision = RecognitionSettings.schemeRevision(ctx)
            Thread {
                runCatching { RootAuthManager.restoreIfCurrent(ctx, revision) }
                    .onFailure { Log.w(TAG, "Scheme recovery failed", it) }
            }.apply { name = "usb-scheme-recovery"; isDaemon = true; start() }
        }
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, true) }
    }

    // ---- Pending-apply fallback channel ----

    private fun handlePutPendingApply(extras: Bundle?): Bundle {
        val json = extras?.getString(UsbBridgeContract.KEY_PENDING_JSON)
            ?: return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, false) }
        require(json.length <= 2048)
        val payload = PendingApplyPayload.fromJson(json)
        val accepted = synchronized(applyLock) {
            if (chooserToken == 0 || payload.chooserToken != chooserToken || !payload.confirmed) false
            else if (applyResults.containsKey(payload.requestId)) true
            else if (applyResults.values.any { it == 0 }) false
            else {
                while (applyResults.size >= 16) applyResults.remove(applyResults.keys.first())
                applyResults[payload.requestId] = 0
                pendingApply = payload
                pendingWrittenAt = android.os.SystemClock.elapsedRealtime()
                true
            }
        }
        if (accepted) runCatching { context?.contentResolver?.notifyChange(UsbBridgeContract.PENDING_APPLY_URI, null) }
            .onFailure { Log.w(TAG, "USB request notification failed", it) }
        return Bundle().apply { putBoolean(UsbBridgeContract.KEY_RESULT, accepted) }
    }

    private fun handleGetAndClearPendingApply(id: String?): Bundle = synchronized(applyLock) {
        val payload = pendingApply
        val result = Bundle()
        if (payload != null && (id == null || id == payload.requestId)) {
            pendingApply = null
            if (payload.chooserToken == chooserToken && android.os.SystemClock.elapsedRealtime() - pendingWrittenAt < 12_000L)
                result.putString(UsbBridgeContract.KEY_RESULT, payload.toJson())
            else applyResults[payload.requestId] = ModuleConstants.RESULT_LIVE_FAILED
        }
        result
    }

    private fun handleGetSettings(): Bundle {
        return Bundle().apply {
            putString(UsbBridgeContract.KEY_MODE, ModuleSettings.defaultMode())
            putBoolean(UsbBridgeContract.KEY_GAME_DND_ENABLED, ModuleSettings.gameDndEnabled())
            putStringArrayList(UsbBridgeContract.KEY_GAME_DND_PACKAGES, ArrayList(ModuleSettings.gameDndPackages()))
            putBoolean(UsbBridgeContract.KEY_GAME_DND_USE_DEFAULT, ModuleSettings.gameDndUsesDefault())
            putBoolean(UsbBridgeContract.KEY_ADB, ModuleSettings.defaultAdb())
            putBoolean(
                UsbBridgeContract.KEY_DISCONNECT_AUTO_OFF,
                ModuleSettings.disconnectAutoOffAdb(),
            )
            putBoolean(
                UsbBridgeContract.KEY_CHOOSER_WHILE_LOCKED,
                ModuleSettings.chooserWhileLocked(),
            )
            val authEnabled = RecognitionSettings.isEnabled(requireNotNull(context))
            putBoolean(UsbBridgeContract.KEY_AUTH_ENABLED, authEnabled)
            putString(UsbBridgeContract.KEY_AUTH_BACKEND, RecognitionSettings.backend(requireNotNull(context)))
            putLong(UsbBridgeContract.KEY_AUTH_TRANSITION_UNTIL, RecognitionSettings.transitionUntil(requireNotNull(context)))
        }
    }

    // ---- Standard ContentProvider plumbing (not used; data exposed via call()) ----

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        @Suppress("unused")
        const val TAG = "HostProvider"
    }
}
