package com.tiger.usbmanager.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** All new motion honors Android's animation scale and cancels when superseded. */
internal object UiMotion {
    fun enabled() = ValueAnimator.areAnimatorsEnabled()
    fun enter(view: View, offset: Int = 12) {
        view.animate().cancel()
        if (!enabled()) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f
        view.translationY = view.context.dp(offset).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }
    fun selected(view: View) {
        view.animate().cancel()
        if (!enabled()) { view.scaleX = 1f; view.scaleY = 1f; return }
        view.scaleX = 0.91f; view.scaleY = 0.91f
        view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(0.7f)).start()
    }
    fun text(view: TextView?, value: CharSequence) {
        if (view == null || view.text == value) return
        view.text = value
        enter(view, 3)
    }
}

internal fun View.clickFeedback(radius: Int = 16) {
    foreground = RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(
        context.uiColor(com.tiger.usbmanager.R.color.usb_accent), 32)), null,
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE); cornerRadius = context.dp(radius).toFloat()
        })
}
