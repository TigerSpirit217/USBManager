package com.tiger.usbmanager.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.slider.Slider
import com.google.android.material.slider.RangeSlider
import kotlin.math.abs

/** Intercept horizontal page gestures while leaving vertical scrolling and draggable controls alone. */
@android.annotation.SuppressLint("ViewConstructor")
internal class PageSwipeHost(context: Context, private val switchPage: (Int) -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var blocked = false
    private var velocity: VelocityTracker? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y
            horizontal = false
            blocked = touchesHorizontalControl(this, downX, downY)
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) blocked = true
        velocity?.addMovement(event)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle(); velocity = null
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !blocked) {
            val dx = abs(event.x - downX)
            val dy = abs(event.y - downY)
            if (dy > slop && dy > dx) blocked = true
            else if (dx > slop && dx > dy * 1.5f) {
                horizontal = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return horizontal
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!horizontal) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                val distance = abs(dx) >= maxOf(context.dp(48).toFloat(), width * 0.18f)
                val fling = abs(dx) >= context.dp(24) && abs(vx) >= context.dp(600) &&
                    abs(vx) > abs(vy) * 1.5f && vx * dx > 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!blocked && abs(dx) > abs(dy) * 1.5f && (distance || fling)) switchPage(if (dx < 0) 1 else -1)
                horizontal = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                horizontal = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun touchesHorizontalControl(view: View, x: Float, y: Float): Boolean {
        if (view is SwitchCompat || view is SeekBar || view is HorizontalScrollView || view is Slider || view is RangeSlider) return true
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val childX = x + view.scrollX - child.left - child.translationX
                val childY = y + view.scrollY - child.top - child.translationY
                if (childX >= 0 && childX < child.width && childY >= 0 && childY < child.height &&
                    touchesHorizontalControl(child, childX, childY)) return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
