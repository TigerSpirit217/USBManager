package com.tiger.usbmanager.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import com.tiger.usbmanager.R
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.CompoundButtonCompat
import android.widget.CheckBox
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun View.applySystemBarPadding(includeTop: Boolean = false, includeBottom: Boolean = false) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        view.setPadding(
            initialLeft,
            initialTop + if (includeTop) bars.top else 0,
            initialRight,
            initialBottom + if (includeBottom) bars.bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

internal fun SwitchMaterial.useUsbManagerColors() {
    setUseMaterialThemeColors(false)
    thumbTintList = AppCompatResources.getColorStateList(context, R.color.switch_thumb_tint)
    trackTintList = AppCompatResources.getColorStateList(context, R.color.switch_track_tint)
}

internal fun CheckBox.useUsbManagerColors() {
    CompoundButtonCompat.setButtonTintList(this, AppCompatResources.getColorStateList(context, R.color.control_button_tint))
}

internal fun MaterialButton.useUsbManagerPrimaryColors() {
    backgroundTintList = AppCompatResources.getColorStateList(context, R.color.primary_button_tint)
    setTextColor(context.getColor(R.color.on_usb_accent))
    rippleColor = AppCompatResources.getColorStateList(context, R.color.usb_accent_soft)
}

internal fun MaterialButton.useUsbManagerTextColors() {
    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    setTextColor(context.getColor(R.color.usb_accent))
    rippleColor = AppCompatResources.getColorStateList(context, R.color.usb_accent_soft)
    elevation = 0f
}

internal fun MaterialButton.useUsbManagerOutlinedColors() {
    useUsbManagerTextColors()
    stateListAnimator = null
    elevation = 0f
    translationZ = 0f
    strokeWidth = context.dp(1)
    strokeColor = android.content.res.ColorStateList.valueOf(context.getColor(R.color.usb_accent))
    cornerRadius = context.dp(14)
}

internal fun Context.roundedBackground(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(1), getColor(strokeColor))
    }

internal fun Context.surfaceCard(radius: Int = 22): MaterialCardView = MaterialCardView(this).apply {
    setCardBackgroundColor(getColor(R.color.bg_card))
    this.radius = dp(radius).toFloat()
    cardElevation = 0f
    strokeColor = getColor(R.color.outline)
    strokeWidth = dp(1)
}

internal fun Context.pageTitle(textValue: CharSequence): TextView = TextView(this).apply {
    text = textValue
    textSize = 24f
    setTextColor(getColor(R.color.text_primary))
    setTypeface(typeface, android.graphics.Typeface.BOLD)
}

internal fun Context.sectionLabel(textValue: CharSequence): TextView = TextView(this).apply {
    text = textValue
    textSize = 12f
    isAllCaps = true
    letterSpacing = 0.08f
    setTextColor(getColor(R.color.text_tertiary))
    setPadding(dp(4), dp(20), dp(4), dp(9))
}

internal fun Context.divider(): View = View(this).apply {
    setBackgroundColor(getColor(R.color.outline))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        marginStart = dp(16)
        marginEnd = dp(16)
    }
}

internal fun Context.toolbar(title: CharSequence, back: (() -> Unit)? = null, action: Pair<CharSequence, () -> Unit>? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(16), dp(10))
        if (back != null) addView(ImageView(this@toolbar).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
            contentDescription = getString(R.string.action_back)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { back() }
            background = roundedBackground(R.color.surface_variant, 16)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(pageTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(TextView(this@toolbar).apply {
            text = action.first
            textSize = 14f
            setTextColor(getColor(R.color.usb_accent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(R.color.accent_soft, 14)
            setOnClickListener { action.second() }
        })
    }

internal fun verticalMargins(top: Int = 0, bottom: Int = 12): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = top
        bottomMargin = bottom
    }
