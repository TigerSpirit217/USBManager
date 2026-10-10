package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.tiger.usbmanager.withDisplayLanguage

/** Honor language order while keeping Chinese resources solely in values. */
abstract class LocalizedActivity : Activity() {
    override fun setContentView(view: android.view.View) {
        fun preserveScroll(node: android.view.View) {
            if (node is android.widget.ScrollView && node.id == android.view.View.NO_ID) node.id = com.tiger.usbmanager.R.id.responsive_scroll
            else if (node is android.view.ViewGroup) for (i in 0 until node.childCount) preserveScroll(node.getChildAt(i))
        }
        preserveScroll(view)
        super.setContentView(responsiveScreen(view))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        DisplaySettings.apply(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        DisplaySettings.applyOrientation(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplaySettings.wrapContext(newBase.withDisplayLanguage()))
        applyOverrideConfiguration(DisplaySettings.nightConfiguration())
    }
}
