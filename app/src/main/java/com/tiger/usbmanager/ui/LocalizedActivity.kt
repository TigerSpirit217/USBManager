package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.Context
import com.tiger.usbmanager.withDisplayLanguage

/** Honor language order while keeping Chinese resources solely in values. */
abstract class LocalizedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withDisplayLanguage())
    }
}
