package com.tiger.usbmanager.hook

import android.content.Context
import android.os.Bundle
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.bridge.UsbBridgeContract as Contract

/** Uses LocalServices inside system_server, never the app's installed-app APIs. */
internal object SystemPackageCatalog {
    fun publish(env: HookEnv, context: Context, request: String) {
        val resolver = context.contentResolver
        val pending = resolver.call(Contract.HOST_URI, Contract.METHOD_GET_PACKAGE_REQUEST, request, null)
        if (pending?.getBoolean(Contract.KEY_RESULT) != true) return
        val result = Bundle().apply {
            putString(Contract.KEY_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
            putString(Contract.KEY_PACKAGE_REQUEST, request)
        }
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices", false, env.classLoader)
            val pmClass = Class.forName("android.content.pm.PackageManagerInternal", false, env.classLoader)
            val pm = localServices.getMethod("getService", Class::class.java).invoke(null, pmClass)
                ?: error("PackageManagerInternal unavailable")
            val snapshot = pm.javaClass.methodOrNull("getPackageList")?.invoke(pm)
                ?: error("Package list unavailable")
            try {
                val names = snapshot.javaClass.methodOrNull("getPackageNames")?.invoke(snapshot) as? List<*>
                    ?: error("Package names unavailable")
                result.putStringArrayList(Contract.KEY_PACKAGE_NAMES, ArrayList(names.filterIsInstance<String>().distinct().sorted()))
                // Resolve each known package with the system UID. The module UI
                // never enumerates apps or queries ApplicationInfo by package name.
                val packages = result.getStringArrayList(Contract.KEY_PACKAGE_NAMES).orEmpty()
                for (page in packages.chunked(Contract.PACKAGE_PAGE_SIZE)) {
                    val apps = page.mapNotNull { name ->
                        runCatching { context.packageManager.getApplicationInfo(name, 0) }.getOrNull()
                    }
                    val batch = Bundle().apply {
                        putString(Contract.KEY_BRIDGE_TOKEN, ModuleConstants.BRIDGE_TOKEN)
                        putString(Contract.KEY_PACKAGE_REQUEST, request)
                        putParcelableArrayList(Contract.KEY_PACKAGE_APPS, ArrayList(apps))
                    }
                    check(resolver.call(Contract.HOST_URI, Contract.METHOD_PUBLISH_PACKAGE_APPS, null, batch)
                        ?.getBoolean(Contract.KEY_RESULT) == true)
                }
            } finally {
                snapshot.javaClass.methodOrNull("close")?.invoke(snapshot)
            }
        }.onFailure {
            env.warn("[GAME_DND] internal package catalog unavailable", it)
            result.putString(Contract.KEY_PACKAGE_ERROR, "unavailable")
        }
        resolver.call(Contract.HOST_URI, Contract.METHOD_PUBLISH_PACKAGE_NAMES, null, result)
    }
}
