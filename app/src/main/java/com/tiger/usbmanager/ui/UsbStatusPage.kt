package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.ModuleConstants
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.CurrentUsbSnapshot
import com.tiger.usbmanager.bridge.CurrentUsbState
import com.tiger.usbmanager.policy.UsbMode

internal class UsbStatusPage(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = CurrentUsbSnapshot(null, null, null)
    private val observer = CurrentUsbState(activity, ::render)
    private var heading: TextView? = null
    private var connectionHint: TextView? = null
    private var feedback: TextView? = null
    private var modeControls: LinearLayout? = null
    private var contentRoot: LinearLayout? = null
    private val modeButtons = linkedMapOf<UsbMode, MaterialButton>()
    private val colorAnimations = mutableMapOf<UsbMode, android.animation.ValueAnimator>()
    private var lastConnection: Boolean? = null
    private var feedbackMessage = 0
    private var adbSwitch: SwitchMaterial? = null
    private var binding = false
    private var pending: CurrentUsbSnapshot? = null
    private var adbOnly = false
    private var acknowledged = false
    private var generation = 0

    fun start() = observer.start()
    fun stop() = observer.stop()
    fun destroy() {
        generation++; handler.removeCallbacksAndMessages(null)
        colorAnimations.values.forEach { it.cancel() }; colorAnimations.clear()
    }

    fun content(): LinearLayout = with(activity) {
        colorAnimations.values.forEach { it.cancel() }; colorAnimations.clear(); modeButtons.clear()
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentRoot = this
            addView(sectionLabel(getString(R.string.status_connection_section)))
            addView(surfaceCard(24).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(20), dp(18), dp(18))
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        heading = pageTitle("").apply {
                            textSize = 21f
                            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                        }
                        addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                    connectionHint = TextView(activity).apply {
                        textSize = 13f; setTextColor(uiColor(R.color.text_secondary))
                        setPadding(0, dp(14), 0, dp(6)); setLineSpacing(0f, 1.15f)
                    }
                    addView(connectionHint)
                    modeControls = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(10), 0, dp(10))
                        addView(modeButton(UsbMode.CHARGING), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
                        listOf(listOf(UsbMode.MTP, UsbMode.PTP), listOf(UsbMode.RNDIS, UsbMode.MIDI)).forEach { pair ->
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                isBaselineAligned = false
                                pair.forEachIndexed { index, mode ->
                                    addView(modeButton(mode), LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                                        if (index == 0) marginEnd = dp(8)
                                    })
                                }
                            }, verticalMargins(top = dp(8), bottom = 0))
                        }
                    }
                    addView(modeControls)
                    addView(View(activity).apply { setBackgroundColor(uiColor(R.color.outline)) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(8) })
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(activity).apply {
                                setText(R.string.status_adb_title); textSize = 15f; setTextColor(uiColor(R.color.text_primary))
                            })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        adbSwitch = SwitchMaterial(activity).apply {
                            contentDescription = getString(R.string.status_adb_title)
                            useUsbManagerColors()
                            setOnCheckedChangeListener { _, checked ->
                                if (!binding) { render(snapshot); apply(snapshot.mode ?: UsbMode.CHARGING, checked, true) }
                            }
                        }
                        addView(adbSwitch)
                    })
                    feedback = TextView(activity).apply {
                        textSize = 12f; setTextColor(uiColor(R.color.text_secondary))
                        setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.2f)
                        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                    addView(feedback)
                })
            })
            lastConnection = snapshot.connected
            render(snapshot)
        }
    }

    private fun modeButton(mode: UsbMode): MaterialButton = with(activity) {
        MaterialButton(this).apply {
            text = when (mode) {
                UsbMode.CHARGING -> getString(R.string.chooser_mode_charging)
                UsbMode.MTP -> getString(R.string.status_mode_mtp)
                UsbMode.PTP -> getString(R.string.status_mode_ptp)
                UsbMode.RNDIS -> getString(R.string.status_mode_rndis)
                UsbMode.MIDI -> "MIDI"
            }
            contentDescription = getString(mode.displayRes)
            isAllCaps = false; textSize = 13f; cornerRadius = dp(18)
            gravity = Gravity.CENTER
            insetTop = 0; insetBottom = 0
            strokeWidth = dp(1); stateListAnimator = null; elevation = 0f
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, when (mode) {
                UsbMode.CHARGING -> R.drawable.ic_battery_charging
                UsbMode.MTP -> R.drawable.ic_folder_transfer
                UsbMode.PTP -> R.drawable.ic_photo_transfer
                UsbMode.RNDIS -> R.drawable.ic_network_share
                UsbMode.MIDI -> R.drawable.ic_midi
            })
            iconSize = dp(20); iconPadding = dp(8); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setPadding(dp(10), 0, dp(10), 0)
            rippleColor = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(uiColor(R.color.usb_accent), 32))
            modeButtons[mode] = this
            setOnClickListener {
                val state = snapshot
                if (pending == null && state.connected == true && state.adb != null && state.mode != mode) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    UiMotion.selected(this)
                    apply(mode, state.adb, false)
                }
            }
        }
    }

    private fun shortMode(mode: UsbMode?): String = when (mode) {
        null -> activity.getString(R.string.status_mode_unknown)
        UsbMode.CHARGING -> activity.getString(R.string.chooser_mode_charging)
        else -> mode.name
    }

    private fun render(current: CurrentUsbSnapshot) {
        val before = snapshot
        if (pending == null && current != before) feedbackMessage = 0
        snapshot = current
        with(activity) {
            if (current.connected != lastConnection && contentRoot?.isAttachedToWindow == true && UiMotion.enabled()) {
                android.transition.TransitionManager.beginDelayedTransition(contentRoot,
                    android.transition.AutoTransition().setDuration(220))
            }
            lastConnection = current.connected
            UiMotion.text(heading, when (current.connected) {
                true -> getString(R.string.status_connected_mode, shortMode(current.mode))
                false -> getString(R.string.status_disconnected)
                null -> getString(R.string.status_unavailable)
            })
            connectionHint?.setText(if (current.connected == true) R.string.status_connected_hint else R.string.status_disconnected_hint)
            modeControls?.visibility = if (current.connected == true || pending?.connected == true && !adbOnly) View.VISIBLE else View.GONE
            modeButtons.forEach { (mode, button) ->
                val selected = current.connected == true && current.mode == mode
                val target = uiColor(if (selected) R.color.usb_option_selected else R.color.usb_option_normal)
                val previous = button.backgroundTintList?.defaultColor ?: target
                colorAnimations.remove(mode)?.cancel()
                if (previous != target && UiMotion.enabled() && button.isAttachedToWindow) {
                    colorAnimations[mode] = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), previous, target).apply {
                        duration = 180
                        addUpdateListener { button.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
                        start()
                    }
                } else button.backgroundTintList = ColorStateList.valueOf(target)
                button.strokeColor = ColorStateList.valueOf(uiColor(if (selected) R.color.usb_accent else R.color.outline))
                val tint = ColorStateList.valueOf(uiColor(if (selected) R.color.on_accent_soft else R.color.text_secondary))
                button.setTextColor(tint); button.iconTint = tint
                button.isSelected = selected
                button.isEnabled = current.connected == true && current.adb != null && pending == null
                androidx.core.view.ViewCompat.setStateDescription(button, getString(if (selected) R.string.display_selected else R.string.status_mode_available))
                if (selected && before.mode != mode) UiMotion.selected(button)
            }
            binding = true
            adbSwitch?.isChecked = current.adb == true
            binding = false
            adbSwitch?.isEnabled = current.adb != null && pending == null
            adbSwitch?.let { androidx.core.view.ViewCompat.setStateDescription(it, getString(when (current.adb) {
                true -> R.string.status_adb_enabled; false -> R.string.status_adb_disabled; null -> R.string.status_unavailable
            })) }
            val message = if (pending == null) feedbackMessage else R.string.status_applying
            feedback?.visibility = if (message == 0) View.GONE else View.VISIBLE
            UiMotion.text(feedback, if (message == 0) "" else getString(message))
        }
        verifyPending()
    }

    private fun apply(mode: UsbMode, adb: Boolean, onlyAdb: Boolean) {
        if (pending != null) return
        val request = ++generation
        adbOnly = onlyAdb
        acknowledged = false
        feedbackMessage = 0
        pending = CurrentUsbSnapshot(snapshot.connected, mode, adb)
        render(snapshot)
        handler.postDelayed({
            if (generation == request && pending != null) finish(R.string.status_apply_timeout)
        }, 12_000L)
        runCatching {
            CurrentUsbState.apply(activity, mode, adb, onlyAdb) response@{ result ->
                if (generation != request || pending == null || activity.isDestroyed) return@response
                when (result) {
                    ModuleConstants.RESULT_LIVE_APPLIED -> { acknowledged = true; observer.refresh(); verifyPending() }
                    ModuleConstants.RESULT_LIVE_FAILED -> finish(R.string.status_apply_failed)
                    else -> finish(R.string.status_runtime_unavailable)
                }
            }
        }.onFailure { finish(R.string.status_apply_failed) }
    }

    private fun verifyPending() {
        val target = pending ?: return
        if (acknowledged && snapshot.adb == target.adb &&
            (adbOnly || snapshot.connected == true && snapshot.mode == target.mode)) finish(R.string.status_applied)
    }

    private fun finish(message: Int) {
        pending = null
        generation++
        handler.removeCallbacksAndMessages(null)
        feedbackMessage = message
        render(snapshot)
    }
}
