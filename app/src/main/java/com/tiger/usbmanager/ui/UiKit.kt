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
import androidx.core.widget.CompoundButtonCompat
import android.widget.CheckBox
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun View.applySystemBarPadding(includeTop: Boolean = false, includeBottom: Boolean = false, includeHorizontal: Boolean = false, includeIme: Boolean = false) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val bottom = if (includeIme) maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom) else bars.bottom
        view.setPadding(
            initialLeft + if (includeHorizontal) bars.left else 0,
            initialTop + if (includeTop) bars.top else 0,
            initialRight + if (includeHorizontal) bars.right else 0,
            initialBottom + if (includeBottom) bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

internal fun SwitchMaterial.useUsbManagerColors() {
    setUseMaterialThemeColors(false)
    thumbTintList = context.controlColors()
    trackTintList = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(context.uiColor(R.color.usb_indicator_inactive), 56),
            androidx.core.graphics.ColorUtils.setAlphaComponent(context.uiColor(R.color.usb_accent), 107),
            androidx.core.graphics.ColorUtils.setAlphaComponent(context.uiColor(R.color.usb_indicator_inactive), 97)))
}

internal fun Context.controlColors() = android.content.res.ColorStateList(
    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
    intArrayOf(uiColor(R.color.usb_indicator_inactive), uiColor(R.color.usb_accent), uiColor(R.color.usb_icon_inactive)))

internal fun CheckBox.useUsbManagerColors() {
    CompoundButtonCompat.setButtonTintList(this, context.controlColors())
}

internal fun MaterialButton.useUsbManagerPrimaryColors() {
    backgroundTintList = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(context.uiColor(R.color.usb_indicator_inactive), context.uiColor(R.color.usb_accent_pressed), context.uiColor(R.color.usb_accent)))
    setTextColor(context.uiColor(R.color.on_usb_accent))
    iconTint = android.content.res.ColorStateList.valueOf(context.uiColor(R.color.on_usb_accent))
    rippleColor = android.content.res.ColorStateList.valueOf(context.uiColor(R.color.usb_accent_soft))
}

internal fun MaterialButton.useUsbManagerTextColors() {
    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    setTextColor(context.uiColor(R.color.usb_accent))
    rippleColor = android.content.res.ColorStateList.valueOf(context.uiColor(R.color.usb_accent_soft))
    elevation = 0f
}

internal fun MaterialButton.useUsbManagerOutlinedColors() {
    useUsbManagerTextColors()
    stateListAnimator = null
    elevation = 0f
    translationZ = 0f
    strokeWidth = context.dp(1)
    strokeColor = android.content.res.ColorStateList.valueOf(context.uiColor(R.color.usb_accent))
    cornerRadius = context.dp(14)
}

internal fun Context.roundedBackground(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(uiColor(color))
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(1), uiColor(strokeColor))
    }

internal fun Context.surfaceCard(radius: Int = 22): MaterialCardView = MaterialCardView(this).apply {
    setCardBackgroundColor(uiColor(R.color.bg_card))
    this.radius = dp(radius).toFloat()
    cardElevation = 0f
    strokeColor = uiColor(R.color.outline)
    strokeWidth = dp(1)
}

internal fun Context.pageTitle(textValue: CharSequence): TextView = TextView(this).apply {
    text = textValue
    textSize = 24f
    setTextColor(uiColor(R.color.text_primary))
    setTypeface(typeface, android.graphics.Typeface.BOLD)
}

internal fun Context.sectionLabel(textValue: CharSequence): TextView = TextView(this).apply {
    text = textValue
    textSize = 12f
    isAllCaps = true
    letterSpacing = 0.08f
    setTextColor(uiColor(R.color.text_tertiary))
    setPadding(dp(4), dp(20), dp(4), dp(9))
}

internal fun Context.divider(): View = View(this).apply {
    setBackgroundColor(uiColor(R.color.outline))
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
            imageTintList = android.content.res.ColorStateList.valueOf(uiColor(R.color.text_primary))
            contentDescription = getString(R.string.action_back)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { back() }
            background = roundedBackground(R.color.surface_variant, 16)
            clickFeedback(16)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(pageTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(TextView(this@toolbar).apply {
            text = action.first
            textSize = 14f
            setTextColor(uiColor(R.color.usb_accent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(R.color.accent_soft, 14)
            clickFeedback(14)
            setOnClickListener { action.second() }
        })
    }

internal fun verticalMargins(top: Int = 0, bottom: Int = 12): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = top
        bottomMargin = bottom
    }

internal fun androidx.appcompat.app.AlertDialog.applyUsbDialogColors() {
    // Preserve Material's corner shape and insets while explicitly resolving the chosen mode.
    fun tintSurface(drawable: android.graphics.drawable.Drawable?) {
        when (drawable) {
            is android.graphics.drawable.InsetDrawable -> tintSurface(drawable.drawable)
            is com.google.android.material.shape.MaterialShapeDrawable -> {
                drawable.fillColor = android.content.res.ColorStateList.valueOf(context.uiColor(R.color.bg_card))
                drawable.elevation = 0f
            }
            is GradientDrawable -> drawable.setColor(context.uiColor(R.color.bg_card))
        }
    }
    tintSurface(window?.decorView?.background)
    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(context.uiColor(R.color.text_primary))
    findViewById<TextView>(android.R.id.message)?.setTextColor(context.uiColor(R.color.text_body))
    listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE,
        android.content.DialogInterface.BUTTON_NEUTRAL).forEach { which ->
        getButton(which)?.setTextColor(context.uiColor(R.color.usb_accent))
    }
    listView?.apply {
        fun tintRows() {
            for (i in 0 until childCount) (getChildAt(i) as? android.widget.CheckedTextView)?.apply {
                checkMarkTintList = context.controlColors()
                setTextColor(context.uiColor(R.color.text_primary))
            }
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tintRows() }
        tintRows()
    }
}

internal fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showUsbDialog(): androidx.appcompat.app.AlertDialog =
    create().apply {
        setOnShowListener {
            applyUsbDialogColors()
            window?.decorView?.let { UiMotion.enter(it, 0) }
        }
        show()
    }
