package com.tiger.usbmanager.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
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
import com.tiger.usbmanager.withDisplayLanguage
import com.tiger.usbmanager.auth.RecognitionSettings
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/** One-time chooser shown when the phone is connected to a USB host. */
open class UsbChooserActivity : ComponentActivity() {
    protected open val editsDefaultConfiguration: Boolean = false
    private val optionViews = linkedMapOf<UsbMode, ModeViews>()
    private val optionColorAnimations = mutableMapOf<UsbMode, ValueAnimator>()
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplaySettings.wrapContext(newBase.withDisplayLanguage()))
        applyOverrideConfiguration(DisplaySettings.nightConfiguration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DisplaySettings.apply(this)
        super.onCreate(savedInstanceState)
        if (editsDefaultConfiguration) ModuleSettings.init(this)
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0
        setContentView(R.layout.activity_usb_chooser)
        configureWindow()
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
        optionColorAnimations.values.forEach { it.cancel() }
        chooserCard.animate().cancel()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (closing) return
        val mode = selectedMode
        val adbEnabled = adbSwitch.isChecked
        chooserCard.animate().cancel()
        // Android selects layout-land for a landscape window. Rebind without closing
        // the USB session or resetting the user's unconfirmed selection.
        setContentView(R.layout.activity_usb_chooser)
        bindViews()
        adbSwitch.isChecked = adbEnabled
        selectMode(mode, animate = false)
        configureWindow()
    }

    private fun configureWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.55f }
        window.setGravity(Gravity.CENTER)
        // Configure after setContentView: floating-window initialization resets the
        // width to WRAP_CONTENT. MATCH_PARENT then uses the safe window frame,
        // rather than forcing the full display width across a landscape cutout.
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bindViews() {
        optionColorAnimations.values.forEach { it.cancel() }
        optionColorAnimations.clear()
        optionViews.clear()
        chooserCard = findViewById(R.id.chooser_card)
        applyXmlPalette(chooserCard)
        adbSwitch = findViewById(R.id.adb_switch)
        adbSwitch.useUsbManagerColors()
        chooserCard.setCardBackgroundColor(uiColor(R.color.usb_surface))
        findViewById<TextView>(R.id.chooser_heading).setTextColor(uiColor(R.color.usb_text_primary))
        findViewById<TextView>(R.id.chooser_subtitle).setTextColor(uiColor(R.color.usb_text_secondary))
        findViewById<TextView>(R.id.chooser_footer_hint).setTextColor(uiColor(R.color.usb_text_tertiary))
        findViewById<MaterialCardView>(R.id.adb_option).setCardBackgroundColor(uiColor(R.color.usb_adb_background))
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
            card.findViewById<TextView>(R.id.mode_title).apply {
                setText(definition.mode.displayRes); setTextColor(uiColor(R.color.usb_text_primary))
            }
            card.findViewById<TextView>(R.id.mode_subtitle).setTextColor(uiColor(R.color.usb_text_secondary))
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
                        getString(R.string.auth_saved_config, getString(selectedMode.displayRes),
                            if (adbSwitch.isChecked) getString(R.string.auth_adb_enabled) else ""),
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

    private fun applyXmlPalette(view: View) {
        // Also covers API 26–29, where Android does not support ResourcesLoader.
        if (view is ImageView) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(uiColor(R.color.usb_accent))
            if (view.background != null) view.background = roundedBackground(R.color.accent_soft, 16)
        }
        if (view is TextView && view !is MaterialButton) {
            listOf(R.color.usb_text_primary, R.color.usb_text_secondary, R.color.usb_text_tertiary).firstOrNull {
                view.currentTextColor == getColor(it)
            }?.let { view.setTextColor(uiColor(it)) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyXmlPalette(view.getChildAt(i))
    }

    private fun selectMode(mode: UsbMode, animate: Boolean) {
        selectedMode = mode
        optionViews.forEach { (itemMode, views) ->
            optionColorAnimations.remove(itemMode)?.cancel()
            val selected = itemMode == mode
            val target = uiColor(if (selected) R.color.usb_option_selected else R.color.usb_option_normal)
            val current = views.card.cardBackgroundColor.defaultColor
            if (animate && UiMotion.enabled() && current != target) {
                optionColorAnimations[itemMode] = ValueAnimator.ofObject(ArgbEvaluator(), current, target).apply {
                    duration = 180
                    addUpdateListener { views.card.setCardBackgroundColor(it.animatedValue as Int) }
                    start()
                }
            } else views.card.setCardBackgroundColor(target)
            views.card.strokeWidth = dp(if (selected) 2 else 1)
            views.card.strokeColor = uiColor(if (selected) R.color.usb_accent else R.color.usb_option_border)
            views.icon.setColorFilter(uiColor(if (selected) R.color.usb_accent else R.color.usb_icon_inactive))
            views.indicator.isSelected = selected
            views.indicator.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (selected) uiColor(R.color.usb_accent) else android.graphics.Color.TRANSPARENT)
                if (!selected) setStroke(dp(2), uiColor(R.color.usb_indicator_inactive))
            }
            views.indicator.animate()
                .scaleX(if (selected) 1f else 0.72f)
                .scaleY(if (selected) 1f else 0.72f)
                .alpha(if (selected) 1f else 0.55f)
                .setDuration(if (animate && UiMotion.enabled()) 180 else 0)
                .start()
        }
    }

    private fun playEntranceAnimation() {
        chooserCard.animate().cancel()
        if (!UiMotion.enabled()) {
            chooserCard.alpha = 1f; chooserCard.scaleX = 1f; chooserCard.scaleY = 1f; chooserCard.translationY = 0f
            return
        }
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
        chooserCard.animate().cancel()
        if (!UiMotion.enabled()) { finish(); return }
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
