package com.tiger.usbmanager.compatibility

import android.content.Context
import androidx.core.content.edit

/** Local installation history is separate from settings and is not included in backups. */
class CompatibilitySnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("usbmanager_compatibility", Context.MODE_PRIVATE)

    fun previousPackages(): Set<String>? =
        if (preferences.contains(KEY_PACKAGES)) preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()
        else null

    fun record(packages: Set<String>) {
        preferences.edit { putStringSet(KEY_PACKAGES, packages.toSet()) }
    }

    private companion object {
        const val KEY_PACKAGES = "last_installed_packages"
    }
}
