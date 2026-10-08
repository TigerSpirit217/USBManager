package com.tiger.usbmanager.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import com.tiger.usbmanager.ModuleConstants
import java.util.UUID
import androidx.core.os.BundleCompat
import java.util.concurrent.locks.ReentrantLock

data class CatalogApp(val packageName: String, val label: String, val applicationInfo: ApplicationInfo? = null) {
    val isSystem: Boolean
        get() = ((applicationInfo?.flags ?: 0) and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}

/** Called on a worker thread. Only system_server obtains the package snapshot. */
object InstalledPackageCatalog {
    // HostProvider has one request mailbox. Serialize readers through the whole
    // request, including metadata, so the main screen and app picker cannot replace
    // each other's in-flight snapshots. Waiting readers remain cancellable.
    private val requestLock = ReentrantLock()

    fun loadPackageNames(context: Context): Set<String> = withRequest(context) { result, _ ->
        result.getStringArrayList(UsbBridgeContract.KEY_PACKAGE_NAMES).orEmpty().toSet().also {
            // Never advance the compatibility baseline with an empty/invalid reply.
            check(context.packageName in it) { "Incomplete system package catalog" }
        }
    }

    fun load(context: Context): List<CatalogApp> = withRequest(context) { result, request ->
        val names = result.getStringArrayList(UsbBridgeContract.KEY_PACKAGE_NAMES).orEmpty()
        val infos = mutableMapOf<String, ApplicationInfo>()
        var page = 0
        do {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val pageArgs = Bundle().apply { putInt(UsbBridgeContract.KEY_PACKAGE_PAGE, page++) }
            val batch = context.contentResolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_PACKAGE_APPS, request, pageArgs)
                ?: error("Application metadata unavailable")
            val apps = BundleCompat.getParcelableArrayList(batch, UsbBridgeContract.KEY_PACKAGE_APPS, ApplicationInfo::class.java).orEmpty()
            apps.forEach { infos[it.packageName] = it }
        } while (apps.size == UsbBridgeContract.PACKAGE_PAGE_SIZE)
        check(names.isEmpty() || infos.isNotEmpty()) { "System catalog requires module update/reboot" }
        names.mapNotNull { name ->
            val info = infos[name] ?: return@mapNotNull null
            // Passing the supplied ApplicationInfo loads APK resources without
            // performing an app-side installed-app or package-info query.
            val label = runCatching { info.loadLabel(context.packageManager).toString().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: name
            CatalogApp(name, label, info)
        }
    }

    private fun <T> withRequest(context: Context, read: (Bundle, String) -> T): T {
        requestLock.lockInterruptibly()
        try {
            return awaitRequest(context, read)
        } finally {
            requestLock.unlock()
        }
    }

    private fun <T> awaitRequest(context: Context, read: (Bundle, String) -> T): T {
        val request = UUID.randomUUID().toString()
        val extras = Bundle().apply {
            putString(UsbBridgeContract.KEY_PACKAGE_REQUEST, request)
            putString(UsbBridgeContract.KEY_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
        }
        val resolver = context.contentResolver
        check(resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_BEGIN_PACKAGE_NAMES, null, extras)
            ?.getBoolean(UsbBridgeContract.KEY_RESULT) == true)
        context.sendBroadcast(Intent(ModuleConstants.ACTION_REQUEST_PACKAGE_NAMES).apply {
            setPackage(ModuleConstants.SYSTEM_SERVER_PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtras(extras)
        })
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val result = resolver.call(UsbBridgeContract.HOST_URI, UsbBridgeContract.METHOD_GET_PACKAGE_NAMES, request, null)
            if (result?.getBoolean(UsbBridgeContract.KEY_PACKAGE_READY) == true) {
                check(result.getString(UsbBridgeContract.KEY_PACKAGE_ERROR).isNullOrEmpty())
                return read(result, request)
            }
            Thread.sleep(250L)
        }
        error("Package catalog request timed out")
    }
}
