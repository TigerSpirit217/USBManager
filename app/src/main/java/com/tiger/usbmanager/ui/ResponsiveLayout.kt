package com.tiger.usbmanager.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tiger.usbmanager.R

/** Near-square windows favor the vertical layout, including unfolded and split-screen windows. */
internal fun Context.landscape(): Boolean = with(resources.configuration) {
    if (screenWidthDp > 0 && screenHeightDp > 0) screenWidthDp.toLong() * 5 > screenHeightDp.toLong() * 6
    else orientation == Configuration.ORIENTATION_LANDSCAPE
}
internal fun Context.twoColumns() = landscape() && resources.configuration.screenWidthDp >= 600 && resources.configuration.fontScale <= 1.35f

/** Bound long rows on wide displays without losing the safe-window height or scrolling. */
internal fun Context.responsiveScreen(content: View): View = object : FrameLayout(this) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.layoutParams.width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(if (landscape()) 960 else 840))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}.apply {
    setBackgroundColor(uiColor(R.color.bg_page))
    addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
}

internal fun Context.verticalContent(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    views.forEach { addView(it) }
}

internal fun Context.adaptiveColumns(first: View, second: View) = LinearLayout(this).apply {
    orientation = if (twoColumns()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    isBaselineAligned = false
    listOf(first, second).forEachIndexed { index, view ->
        addView(view, if (twoColumns()) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index == 0) marginEnd = dp(16)
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

internal fun android.view.Window.fitLandscapeDialog(maxWidthDp: Int = 640) {
    if (context.landscape()) setLayout(context.dp(minOf(maxWidthDp, (context.resources.configuration.screenWidthDp - 36).coerceAtLeast(1))),
        ViewGroup.LayoutParams.WRAP_CONTENT)
}
