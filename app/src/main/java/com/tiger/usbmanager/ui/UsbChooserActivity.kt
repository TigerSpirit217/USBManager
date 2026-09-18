package com.tiger.usbmanager.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.auth.RecognitionSettings
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/** One-time chooser shown when the phone is connected to a USB host. */
open class UsbChooserActivity : ComponentActivity() {
    protected open val editsDefaultConfiguration: Boolean = false
    private val optionViews = linkedMapOf<UsbMode, ModeViews>()
    private var selectedMode = UsbMode.MTP
    private var token = 0
    private var outcomeReported = false
    private var receiverRegistered = false
    private var closing = false
    private lateinit var chooserCard: MaterialCardView
    private lateinit var adbSwitch: SwitchMaterial

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) == token) closeAnimated("dismissed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (editsDefaultConfiguration) ModuleSettings.init(this)
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0
        configureWindow()
        setContentView(R.layout.activity_usb_chooser)
        bindViews()
        applyIntentSelection()
        onBackPressedDispatcher.addCallback(this) { closeAnimated("dismissed") }
        playEntranceAnimation()
    }

    override fun onStart() {
        super.onStart()
        if (editsDefaultConfiguration) return
        runCatching {
            val filter = IntentFilter(ModuleConstants.ACTION_DISMISS_CHOOSER)
            ContextCompat.registerReceiver(this, dismissReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(dismissReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!editsDefaultConfiguration) reportOutcome("dismissed")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        token = intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0)
        outcomeReported = false
        closing = false
        applyIntentSelection()
        playEntranceAnimation()
    }

    private fun configureWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.55f }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bindViews() {
        chooserCard = findViewById(R.id.chooser_card)
        adbSwitch = findViewById(R.id.adb_switch)
        adbSwitch.useUsbManagerColors()
        val definitions = listOf(
            ModeDefinition(UsbMode.CHARGING, R.id.option_charging, R.drawable.ic_battery_charging, R.string.chooser_mode_charging_description),
            ModeDefinition(UsbMode.MTP, R.id.option_mtp, R.drawable.ic_folder_transfer, R.string.chooser_mode_mtp_description),
            ModeDefinition(UsbMode.PTP, R.id.option_ptp, R.drawable.ic_photo_transfer, R.string.chooser_mode_ptp_description),
            ModeDefinition(UsbMode.RNDIS, R.id.option_rndis, R.drawable.ic_network_share, R.string.chooser_mode_rndis_description),
            ModeDefinition(UsbMode.MIDI, R.id.option_midi, R.drawable.ic_midi, R.string.chooser_mode_midi_description),
        )
        definitions.forEach { definition ->
            val card = findViewById<MaterialCardView>(definition.viewId)
            val icon = card.findViewById<ImageView>(R.id.mode_icon)
            val indicator = card.findViewById<View>(R.id.mode_indicator)
            icon.setImageResource(definition.iconRes)
            card.findViewById<TextView>(R.id.mode_title).setText(definition.mode.displayRes)
            card.findViewById<TextView>(R.id.mode_subtitle).setText(definition.descriptionRes)
            optionViews[definition.mode] = ModeViews(card, icon, indicator)
            card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                selectMode(definition.mode, animate = true)
            }
        }

        if (editsDefaultConfiguration) {
            findViewById<TextView>(R.id.chooser_heading).setText(R.string.settings_default_usb_config)
            findViewById<TextView>(R.id.chooser_subtitle).setText(R.string.settings_default_usb_subtitle)
            findViewById<TextView>(R.id.chooser_footer_hint).setText(R.string.settings_default_usb_hint)
        }

        findViewById<View>(R.id.adb_option).setOnClickListener {
            adbSwitch.isChecked = !adbSwitch.isChecked
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        findViewById<MaterialButton>(R.id.cancel_button).apply {
            useUsbManagerTextColors()
            setOnClickListener { closeAnimated("cancelled") }
        }
        findViewById<MaterialButton>(R.id.confirm_button).apply {
            useUsbManagerPrimaryColors()
            setOnClickListener {
                if (editsDefaultConfiguration) {
                    ModuleSettings.prefs().edit()
                        .putString(ModuleSettings.KEY_DEFAULT_MODE, selectedMode.wireValue)
                        .putBoolean(ModuleSettings.KEY_DEFAULT_ADB, adbSwitch.isChecked)
                        .apply()
                    setResult(RESULT_OK)
                } else {
                    RecognitionSettings.recordChooserSelection(this@UsbChooserActivity, selectedMode, adbSwitch.isChecked)
                    UsbConfigSender.apply(this@UsbChooserActivity, selectedMode, adbSwitch.isChecked)
                    reportOutcome("confirmed")
                    Toast.makeText(
                        this@UsbChooserActivity,
                        getString(selectedMode.displayRes) + if (adbSwitch.isChecked) " + ADB" else "",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                closeAnimated(null)
            }
        }
    }

    private fun applyIntentSelection() {
        val mode = if (editsDefaultConfiguration) UsbMode.fromWire(ModuleSettings.defaultMode())
            else UsbMode.fromWire(intent?.getStringExtra(ModuleConstants.EXTRA_USB_MODE))
        val rawAdb = if (editsDefaultConfiguration) ModuleSettings.defaultAdb()
            else intent?.getBooleanExtra(ModuleConstants.EXTRA_ADB_ENABLED, false) ?: false
        val caller = callingActivity
        val trustedPublisher = caller == null || caller.packageName == ModuleConstants.MODULE_PACKAGE
        adbSwitch.isChecked = rawAdb && trustedPublisher
        selectMode(mode, animate = false)
    }

    private fun selectMode(mode: UsbMode, animate: Boolean) {
        selectedMode = mode
        optionViews.forEach { (itemMode, views) ->
            val selected = itemMode == mode
            val target = getColor(if (selected) R.color.usb_option_selected else R.color.usb_option_normal)
            val current = views.card.cardBackgroundColor.defaultColor
            if (animate && current != target) {
                ValueAnimator.ofObject(ArgbEvaluator(), current, target).apply {
                    duration = 180
                    addUpdateListener { views.card.setCardBackgroundColor(it.animatedValue as Int) }
                    start()
                }
            } else views.card.setCardBackgroundColor(target)
            views.card.strokeWidth = dp(if (selected) 2 else 1)
            views.card.strokeColor = getColor(if (selected) R.color.usb_accent else R.color.usb_option_border)
            views.icon.setColorFilter(getColor(if (selected) R.color.usb_accent else R.color.usb_icon_inactive))
            views.indicator.isSelected = selected
            views.indicator.animate()
                .scaleX(if (selected) 1f else 0.72f)
                .scaleY(if (selected) 1f else 0.72f)
                .alpha(if (selected) 1f else 0.55f)
                .setDuration(if (animate) 180 else 0)
                .start()
        }
    }

    private fun playEntranceAnimation() {
        chooserCard.alpha = 0f
        chooserCard.scaleX = 0.94f
        chooserCard.scaleY = 0.94f
        chooserCard.translationY = dp(24).toFloat()
        chooserCard.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(360).setInterpolator(OvershootInterpolator(0.78f)).start()
    }

    private fun closeAnimated(outcome: String?) {
        if (closing) return
        closing = true
        if (outcome != null && !editsDefaultConfiguration) reportOutcome(outcome)
        chooserCard.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(14).toFloat())
            .setDuration(170).setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }.start()
    }

    private fun reportOutcome(outcome: String) {
        if (outcomeReported) return
        outcomeReported = true
        UsbConfigSender.sendChooserClosed(this, token, outcome)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ModeDefinition(val mode: UsbMode, val viewId: Int, val iconRes: Int, val descriptionRes: Int)
    private data class ModeViews(val card: MaterialCardView, val icon: ImageView, val indicator: View)
}
