package com.tiger.usbmanager.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.TextViewCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.R

internal class AppearancePage(private val activity: Activity, private val themeChanged: () -> Unit, private val barChanged: () -> Unit) {
    private var colorRow: LinearLayout? = null
    private var colorAnimation: ValueAnimator? = null
    private var colorGeneration = 0
    private var pendingChange: Runnable? = null
    private var pendingThemeChange = false
    private var page: LinearLayout? = null

    fun content() = with(activity) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            page = this
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    colorGeneration++; colorAnimation?.cancel()
                }
            })
            addView(sectionLabel(getString(R.string.display_section)))
            addView(surfaceCard().apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(themeModeRow())
                    addView(divider())
                    addView(switchRow(R.string.display_bar_floating, DisplaySettings.floating()) {
                        DisplaySettings.setFloating(it); afterSwitchMotion(barChanged)
                    })
                    addView(divider())
                    addView(switchRow(R.string.display_dynamic,
                        DisplaySettings.dynamic() && DisplaySettings.dynamicAvailable(), DisplaySettings.dynamicAvailable()) {
                        DisplaySettings.setDynamic(it); animateColorRow(!it)
                    })
                    colorRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = if (DisplaySettings.dynamic() && DisplaySettings.dynamicAvailable()) View.GONE else View.VISIBLE
                        addView(divider())
                        addView(row(R.string.display_theme_color, DisplaySettings.hex(DisplaySettings.seed())) {
                            ThemeColorDialog(activity) { DisplaySettings.setSeed(it); themeChanged() }.show()
                        })
                    }
                    addView(colorRow)
                })
            })

        }
    }

    /** BearRun's three icon segments, with this app's shape and current theme colors. */
    private fun themeModeRow() = with(activity) {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    setText(R.string.display_theme_mode); textSize = 15f; setTextColor(uiColor(R.color.text_primary))
                })
                addView(TextView(activity).apply {
                    setText(DisplaySettings.mode().label); textSize = 12f
                    setTextColor(uiColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            val group = RadioGroup(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = roundedBackground(R.color.surface_variant, 16, R.color.outline)
            }
            DisplaySettings.Mode.entries.forEach { mode ->
                group.addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    buttonDrawable = null
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
                    contentDescription = getString(mode.label)
                    tooltipText = getString(mode.label)
                    setCompoundDrawablesWithIntrinsicBounds(0, when (mode) {
                        DisplaySettings.Mode.SYSTEM -> R.drawable.ic_theme_system
                        DisplaySettings.Mode.LIGHT -> R.drawable.ic_theme_light
                        DisplaySettings.Mode.DARK -> R.drawable.ic_theme_dark
                    }, 0, 0)
                    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(uiColor(R.color.on_usb_accent), uiColor(R.color.usb_icon_inactive))))
                    val selector = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), roundedBackground(R.color.usb_accent, 14))
                        addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
                    }
                    background = RippleDrawable(ColorStateList.valueOf(uiColor(R.color.accent_soft)), selector,
                        roundedBackground(R.color.bg_card, 14))
                    isChecked = DisplaySettings.mode() == mode
                    setOnClickListener {
                        if (DisplaySettings.mode() != mode) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            DisplaySettings.setMode(mode); themeChanged()
                        }
                    }
                }, RadioGroup.LayoutParams(dp(48), dp(48)))
            }
            addView(group)
        }
    }

    private fun switchRow(title: Int, checked: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit) = with(activity) {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(12))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    setText(title); textSize = 15f; setTextColor(uiColor(R.color.text_primary))
                })
                if (title == R.string.display_dynamic) addView(TextView(activity).apply {
                    setText(R.string.display_dynamic_hint); textSize = 12f
                    setTextColor(uiColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val toggle = SwitchMaterial(activity).apply {
                useUsbManagerColors(); contentDescription = getString(title)
                isChecked = checked; isEnabled = enabled
                setOnCheckedChangeListener { _, value -> changed(value) }
            }
            addView(toggle)
            isClickable = enabled; isFocusable = enabled
            if (enabled) { clickFeedback(); setOnClickListener { toggle.toggle() } } else alpha = 0.5f
        }
    }

    private fun afterSwitchMotion(change: () -> Unit, updateTheme: Boolean = false) {
        val root = page ?: return
        pendingChange?.let(root::removeCallbacks)
        pendingThemeChange = pendingThemeChange || updateTheme
        val action = Runnable {
            pendingChange = null
            if (!activity.isDestroyed && !activity.isFinishing) {
                if (pendingThemeChange) { pendingThemeChange = false; themeChanged() } else change()
            }
        }
        pendingChange = action
        val scale = runCatching { android.provider.Settings.Global.getFloat(activity.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f).coerceAtLeast(0f)
        root.postDelayed(action, if (UiMotion.enabled()) (300L * scale).toLong() + 32L else 0L)
    }

    private fun animateColorRow(expanded: Boolean) {
        val host = colorRow ?: return
        val request = ++colorGeneration
        colorAnimation?.cancel()
        val startHeight = if (host.visibility == View.GONE) 0 else host.height
        host.visibility = View.VISIBLE
        host.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        host.measure(View.MeasureSpec.makeMeasureSpec((host.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val fullHeight = host.measuredHeight
        val endHeight = if (expanded) fullHeight else 0
        val startAlpha = if (startHeight == 0) 0f else host.alpha
        if (!UiMotion.enabled() || fullHeight == 0) {
            host.visibility = if (expanded) View.VISIBLE else View.GONE
            host.alpha = 1f; host.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            themeChanged(); return
        }
        afterSwitchMotion(themeChanged, updateTheme = true)
        colorAnimation = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = 300L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animation ->
                val height = animation.animatedValue as Int
                host.layoutParams = host.layoutParams.apply { this.height = height }
                host.alpha = startAlpha + ((if (expanded) 1f else 0f) - startAlpha) * animation.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (request != colorGeneration) return
                    colorAnimation = null
                    host.visibility = if (expanded) View.VISIBLE else View.GONE
                    host.alpha = 1f; host.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            })
            start()
        }
    }

    private fun row(title: Int, value: String, click: () -> Unit) = with(activity) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true
            setPadding(dp(16), dp(14), dp(16), dp(14)); clickFeedback()
            addView(TextView(activity).apply { setText(title); textSize = 15f; setTextColor(uiColor(R.color.text_primary)) })
            addView(TextView(activity).apply {
                text = value; textSize = 12f; setTextColor(uiColor(R.color.usb_text_secondary)); setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { click() }
        }
    }
}
