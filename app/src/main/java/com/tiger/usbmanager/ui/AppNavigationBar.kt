package com.tiger.usbmanager.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.shape.ShapeAppearanceModel
import com.tiger.usbmanager.R
import kotlin.math.abs

/** Independently implemented capsule with a continuous indicator, tap and drag selection. */
@android.annotation.SuppressLint("ViewConstructor", "RtlHardcoded") // Programmatic view; indicator uses physical coordinates with explicit RTL mapping.
internal class AppNavigationBar(
    context: Context,
    private val floating: Boolean,
    entries: List<Pair<Int, Int>>,
    selected: Int,
    private val vertical: Boolean = false,
    private val onSelected: (Int) -> Unit,
) : FrameLayout(context) {
    private val indicator = View(context)
    private val tabs = mutableListOf<Pair<ImageView, TextView>>()
    private var material: NavigationBarView? = null
    private var index = selected
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var nativeSelection = false
    private var syncingSelection = false
    private var previewPosition: Float? = null
    private var releaseTarget: Int? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        clipChildren = false
        if (floating) {
            clipToOutline = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                ColorUtils.setAlphaComponent(context.uiColor(R.color.bg_card), 246),
                ColorUtils.setAlphaComponent(context.uiColor(R.color.surface_container), 240))).apply {
                cornerRadius = context.dp(32).toFloat()
                setStroke(context.dp(1), ColorUtils.blendARGB(context.uiColor(R.color.outline), context.uiColor(R.color.bg_card), 0.5f))
            }
            elevation = context.dp(6).toFloat()
            addView(indicator, LayoutParams(if (vertical) context.dp(56) else 0,
                if (vertical) 0 else context.dp(56), Gravity.TOP or Gravity.LEFT).apply {
                topMargin = context.dp(4); leftMargin = context.dp(4)
            })
            indicator.background = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(context.uiColor(R.color.usb_accent), 30))
                cornerRadius = context.dp(28).toFloat()
            }
            indicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val row = LinearLayout(context).apply {
                orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                setPadding(if (vertical) 0 else context.dp(4), if (vertical) context.dp(4) else 0,
                    if (vertical) 0 else context.dp(4), if (vertical) context.dp(4) else 0)
            }
            entries.forEachIndexed { position, (title, icon) ->
                row.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    isClickable = true; isFocusable = true
                    contentDescription = context.getString(title)
                    val image = ImageView(context).apply { setImageResource(icon); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    val label = TextView(context).apply { setText(title); textSize = 11f; gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    addView(image, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
                    addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
                    tabs.add(image to label)
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        select(position, true)
                        onSelected(position)
                    }
                }, if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val bar: NavigationBarView = if (vertical) NavigationRailView(context).apply {
                menuGravity = Gravity.CENTER
                itemMinimumHeight = context.dp(80)
            } else BottomNavigationView(context)
            material = bar.apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                elevation = 0f
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
                labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
                itemIconTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.uiColor(R.color.usb_accent), context.uiColor(R.color.usb_icon_inactive)))
                itemTextColor = itemIconTintList
                itemRippleColor = null
                isItemActiveIndicatorEnabled = true
                itemActiveIndicatorColor = ColorStateList.valueOf(context.uiColor(R.color.accent_soft))
                itemActiveIndicatorWidth = context.dp(64); itemActiveIndicatorHeight = context.dp(32)
                itemActiveIndicatorShapeAppearance = ShapeAppearanceModel.Builder().setAllCornerSizes(context.dp(16).toFloat()).build()
                entries.forEachIndexed { position, (title, icon) -> menu.add(0, position + 1, position, title).setIcon(icon) }
                selectedItemId = selected + 1
                setOnItemSelectedListener {
                    if (syncingSelection) return@setOnItemSelectedListener true
                    nativeSelection = true
                    try { onSelected(it.itemId - 1) } finally { nativeSelection = false }
                    true
                }
            }
            addView(material, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        select(selected, false)
    }

    fun select(position: Int, animated: Boolean, fromDrag: Boolean = false) {
        // A page finishing must not restart the capsule's independent release animation.
        if (!fromDrag && !animated && releaseTarget == position) return
        index = position
        releaseTarget = if (fromDrag && animated && UiMotion.enabled()) position else null
        if (!fromDrag) previewPosition = null
        syncMaterial(position)
        tabs.forEachIndexed { tab, (icon, label) ->
            val color = context.uiColor(if (tab == position) R.color.usb_accent else R.color.usb_icon_inactive)
            icon.imageTintList = ColorStateList.valueOf(color); label.setTextColor(color)
            (icon.parent as View).isSelected = tab == position
        }
        updateIndicator(animated)
    }

    /** Only the floating capsule follows fractional progress; ordinary tabs select the destination. */
    fun preview(position: Float) {
        if (!floating) return
        previewPosition = position
        if (dragging || releaseTarget != null) return
        if (width == 0) return
        indicator.animate().cancel()
        val physical = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) tabs.lastIndex - position else position
        moveIndicator(physical * tabSize())
        updateFloatingHighlight()
    }

    /** Ordinary selection updates as soon as a swipe or navigation request chooses a destination. */
    fun selectDestination(position: Int) {
        if (!floating) select(position, false)
    }

    /** Direct page swipes take back control from a previous bottom-bar release. */
    fun followPage() {
        releaseTarget = null
        indicator.animate().cancel()
    }

    private fun syncMaterial(position: Int) {
        material?.let {
            if (!nativeSelection && it.selectedItemId != position + 1) {
                syncingSelection = true
                try { it.selectedItemId = position + 1 } finally { syncingSelection = false }
            }
        }
    }

    private fun physical(position: Int) = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) tabs.lastIndex - position else position
    private fun tabSize() = (((if (vertical) height else width) - context.dp(8)).toFloat() / tabs.size.coerceAtLeast(1)).coerceAtLeast(0f)
    private fun indicatorPosition() = if (vertical) indicator.translationY else indicator.translationX
    private fun moveIndicator(position: Float) {
        if (vertical) indicator.translationY = position else indicator.translationX = position
    }
    private fun axis(event: MotionEvent) = if (vertical) event.y else event.x
    private fun updateIndicator(animated: Boolean) {
        if (!floating || width == 0) return
        val slot = tabSize()
        val target = physical(index) * slot
        indicator.animate().cancel()
        if (animated && UiMotion.enabled()) {
            updateFloatingHighlight()
            val motion = indicator.animate()
            if (vertical) motion.translationY(target) else motion.translationX(target)
            motion.setDuration(340)
                .setInterpolator(OvershootInterpolator(0.65f))
                .setUpdateListener { updateFloatingHighlight() }
                .withEndAction { releaseTarget = null }.start()
        } else {
            moveIndicator(target)
            updateFloatingHighlight()
        }
    }

    private fun updateFloatingHighlight() {
        val slot = tabSize()
        if (slot <= 0f) return
        val normal = context.uiColor(R.color.usb_icon_inactive)
        val active = context.uiColor(R.color.usb_accent)
        tabs.forEachIndexed { position, (icon, label) ->
            val coverage = (1f - abs(physical(position) * slot - indicatorPosition()) / slot).coerceIn(0f, 1f)
            val color = ColorUtils.blendARGB(normal, active, coverage)
            icon.imageTintList = ColorStateList.valueOf(color)
            label.setTextColor(color)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (floating) {
            // Size the child before FrameLayout measures it; changing it during layout leaves a zero-width first frame.
            val available = (MeasureSpec.getSize(if (vertical) heightMeasureSpec else widthMeasureSpec) - context.dp(8)).coerceAtLeast(0)
            if (vertical) indicator.layoutParams.height = available / tabs.size.coerceAtLeast(1)
            else indicator.layoutParams.width = available / tabs.size.coerceAtLeast(1)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (floating && !dragging && releaseTarget == null) previewPosition?.let(::preview) ?: updateIndicator(false)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!floating) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if ((if (vertical) abs(event.y - downY) else abs(event.x - downX)) > slop &&
                (if (vertical) abs(event.y - downY) > abs(event.x - downX) else abs(event.x - downX) > abs(event.y - downY))) {
                dragging = true; parent.requestDisallowInterceptTouchEvent(true); indicator.animate().cancel(); return true
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!floating || !dragging) return super.onTouchEvent(event)
        val slot = tabSize()
        if (slot <= 0f) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> dragIndicator(axis(event), slot)
            MotionEvent.ACTION_UP -> {
                dragIndicator(axis(event), slot)
                val physical = ((axis(event) - context.dp(4)) / slot).toInt().coerceIn(0, tabs.lastIndex)
                val target = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) tabs.lastIndex - physical else physical
                dragging = false; select(target, true, fromDrag = true); onSelected(target)
                performClick()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false; select(index, true, fromDrag = true)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun dragIndicator(coordinate: Float, slot: Float) {
        moveIndicator((coordinate - context.dp(4) - slot / 2).coerceIn(0f, slot * tabs.lastIndex))
        updateFloatingHighlight()
    }

    override fun onDetachedFromWindow() { indicator.animate().cancel(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
