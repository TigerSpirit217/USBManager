package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.tiger.usbmanager.withDisplayLanguage

/** Honor language order while keeping Chinese resources solely in values. */
abstract class LocalizedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DisplaySettings.apply(this)
        super.onCreate(savedInstanceState)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplaySettings.wrapContext(newBase.withDisplayLanguage()))
        applyOverrideConfiguration(DisplaySettings.nightConfiguration())
    }
}
