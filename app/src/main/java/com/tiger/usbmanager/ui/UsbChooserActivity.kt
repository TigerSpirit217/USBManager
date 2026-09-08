package com.tiger.usbmanager.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.UsbConfigSender
import com.tiger.usbmanager.policy.UsbMode

/** One-time USB mode chooser launched by the KernelSU service. */
class UsbChooserActivity : ComponentActivity() {
    private val modes = listOf(UsbMode.CHARGING, UsbMode.MTP, UsbMode.RNDIS)
    private val optionViews = linkedMapOf<UsbMode, ModeViews>()
    private var selectedMode = UsbMode.CHARGING
    private var token = 0
    private var outcomeReported = false
    private var receiverRegistered = false
    private var closing = false
    private lateinit var chooserCard: View

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) == token) closeAnimated()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent?.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0) ?: 0
        configureWindow()
        setContentView(R.layout.activity_usb_chooser)
        bindViews()
        onBackPressedDispatcher.addCallback(this) { closeAnimated() }
        playEntranceAnimation()
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                dismissReceiver,
                IntentFilter(ModuleConstants.ACTION_DISMISS_CHOOSER),
                ContextCompat.RECEIVER_EXPORTED,
            )
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
        reportClosed()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        token = intent.getIntExtra(ModuleConstants.EXTRA_TOKEN, 0)
        outcomeReported = false
        closing = false
        selectMode(UsbMode.CHARGING, animate = false)
        playEntranceAnimation()
    }

    private fun configureWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.52f }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bindViews() {
        chooserCard = findViewById(R.id.chooser_card)
        val definitions = listOf(
            ModeDefinition(UsbMode.CHARGING, R.id.option_charging, R.drawable.ic_battery_charging),
            ModeDefinition(UsbMode.MTP, R.id.option_mtp, R.drawable.ic_folder_transfer),
            ModeDefinition(UsbMode.RNDIS, R.id.option_rndis, R.drawable.ic_network_share),
        )
        definitions.forEach { definition ->
            val card = findViewById<MaterialCardView>(definition.viewId)
            val icon = card.findViewById<ImageView>(R.id.mode_icon)
            val title = card.findViewById<TextView>(R.id.mode_title)
            val subtitle = card.findViewById<TextView>(R.id.mode_subtitle)
            val indicator = card.findViewById<View>(R.id.mode_indicator)
            icon.setImageResource(definition.iconRes)
            title.setText(definition.mode.displayRes)
            subtitle.setText(modeDescription(definition.mode))
            optionViews[definition.mode] = ModeViews(card, icon, indicator)
            card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                selectMode(definition.mode, animate = true)
            }
        }
        selectMode(UsbMode.CHARGING, animate = false)

        val adbSwitch = findViewById<SwitchCompat>(R.id.adb_switch)
        findViewById<View>(R.id.adb_option).setOnClickListener {
            adbSwitch.isChecked = !adbSwitch.isChecked
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener { closeAnimated() }
        findViewById<MaterialButton>(R.id.confirm_button).setOnClickListener {
            UsbConfigSender.apply(this, selectedMode, adbSwitch.isChecked, token)
            outcomeReported = true
            Toast.makeText(
                this,
                getString(selectedMode.displayRes) + if (adbSwitch.isChecked) " + ADB" else "",
                Toast.LENGTH_SHORT,
            ).show()
            closeAnimated(reportClose = false)
        }
    }

    private fun selectMode(mode: UsbMode, animate: Boolean) {
        selectedMode = mode
        optionViews.forEach { (itemMode, views) ->
            val selected = itemMode == mode
            val from = views.card.cardBackgroundColor.defaultColor
            val to = getColor(if (selected) R.color.usb_option_selected else R.color.usb_option_normal)
            if (animate && from != to) {
                ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
                    duration = 180
                    addUpdateListener { views.card.setCardBackgroundColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                views.card.setCardBackgroundColor(to)
            }
            views.card.strokeWidth = dp(if (selected) 2 else 1)
            views.card.strokeColor = getColor(
                if (selected) R.color.usb_accent else R.color.usb_option_border,
            )
            views.icon.setColorFilter(
                getColor(if (selected) R.color.usb_accent else R.color.usb_icon_inactive),
            )
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
        chooserCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(360)
            .setInterpolator(OvershootInterpolator(0.78f))
            .start()
    }

    private fun closeAnimated(reportClose: Boolean = true) {
        if (closing) return
        closing = true
        if (reportClose) reportClosed()
        chooserCard.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationY(dp(14).toFloat())
            .setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    private fun reportClosed() {
        if (outcomeReported) return
        outcomeReported = true
        UsbConfigSender.close(this, token)
    }

    private fun modeDescription(mode: UsbMode): Int = when (mode) {
        UsbMode.CHARGING -> R.string.chooser_mode_charging_description
        UsbMode.MTP -> R.string.chooser_mode_mtp_description
        UsbMode.RNDIS -> R.string.chooser_mode_rndis_description
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ModeDefinition(val mode: UsbMode, val viewId: Int, val iconRes: Int)
    private data class ModeViews(
        val card: MaterialCardView,
        val icon: ImageView,
        val indicator: View,
    )
}
