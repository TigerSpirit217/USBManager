package com.tiger.usbmanager.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.AdbAuthorizations

class AdbAuthorizationsActivity : LocalizedActivity() {
    private lateinit var content: LinearLayout
    private lateinit var refresh: MaterialButton
    private var busy = false
    private var generation = 0
    private var confirmation: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_page))
            applySystemBarPadding(includeHorizontal = true)
            addView(toolbar(getString(R.string.adb_auth_title), back = { finish() }).apply {
                applySystemBarPadding(includeTop = true)
            })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
            applySystemBarPadding(includeBottom = true)
            addView(message(getString(R.string.adb_auth_description)), verticalMargins())
            refresh = MaterialButton(this@AdbAuthorizationsActivity).apply {
                setText(R.string.adb_auth_refresh); isAllCaps = false
                useUsbManagerOutlinedColors()
                setOnClickListener { load() }
            }
            addView(refresh, verticalMargins())
            content = LinearLayout(this@AdbAuthorizationsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(content)
        }
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!busy) load()
    }

    private fun load(fingerprint: String? = null) {
        if (busy) return
        busy = true
        refresh.isEnabled = false
        val request = ++generation
        content.removeAllViews()
        content.addView(ProgressBar(this), verticalMargins(top = dp(12)))
        content.addView(message(getString(if (fingerprint == null) R.string.adb_auth_loading else R.string.adb_auth_revoking)))
        AdbAuthorizations.request(applicationContext, fingerprint) { result ->
            if (request != generation || isFinishing || isDestroyed) return@request
            busy = false
            refresh.isEnabled = true
            content.removeAllViews()
            when (result.code) {
                AdbAuthorizations.OK -> {
                    if (fingerprint != null) content.addView(message(getString(R.string.adb_auth_revoked)), verticalMargins())
                    content.addView(sectionLabel(getString(R.string.adb_auth_count, result.devices.size)))
                    if (result.devices.isEmpty()) content.addView(message(getString(R.string.adb_auth_empty)))
                    result.devices.forEach { content.addView(deviceCard(it), verticalMargins()) }
                }
                AdbAuthorizations.UNSUPPORTED -> content.addView(message(getString(R.string.adb_auth_unsupported)))
                AdbAuthorizations.LIMIT_EXCEEDED -> content.addView(message(getString(R.string.adb_auth_limit)))
                AdbAuthorizations.DISCONNECT_REQUIRED -> content.addView(message(getString(R.string.adb_auth_disconnect_required)))
                else -> content.addView(message(getString(if (fingerprint == null) R.string.adb_auth_load_failed
                    else R.string.adb_auth_revoke_failed)))
            }
        }
    }

    private fun deviceCard(device: AdbAuthorizations.Device): View = surfaceCard().apply {
        addView(LinearLayout(this@AdbAuthorizationsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            val label = device.name.ifBlank { getString(R.string.adb_auth_unknown_host) }
            addView(TextView(this@AdbAuthorizationsActivity).apply {
                text = label; textSize = 16f
                setTextColor(uiColor(R.color.text_primary))
            })
            addView(TextView(this@AdbAuthorizationsActivity).apply {
                text = getString(R.string.adb_auth_fingerprint, device.fingerprint)
                textSize = 12f; setTextColor(uiColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            addView(MaterialButton(this@AdbAuthorizationsActivity).apply {
                setText(R.string.adb_auth_revoke); isAllCaps = false
                contentDescription = getString(R.string.adb_auth_revoke_host, label)
                useUsbManagerOutlinedColors()
                setOnClickListener {
                    if (!busy && confirmation?.isShowing != true) {
                        confirmation = MaterialAlertDialogBuilder(this@AdbAuthorizationsActivity)
                            .setTitle(R.string.adb_auth_revoke)
                            .setMessage(getString(R.string.adb_auth_confirm, label, device.fingerprint))
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .setPositiveButton(R.string.adb_auth_revoke) { _, _ -> load(device.fingerprint) }
                            .showUsbDialog()
                    }
                }
            }, verticalMargins(bottom = 0))
        })
    }

    private fun message(value: String) = TextView(this).apply {
        text = value; textSize = 13f
        setTextColor(uiColor(R.color.text_secondary))
        setLineSpacing(0f, 1.15f)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    override fun onDestroy() {
        generation++
        confirmation?.dismiss()
        super.onDestroy()
    }
}
