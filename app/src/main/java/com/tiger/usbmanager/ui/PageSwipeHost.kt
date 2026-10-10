package com.tiger.usbmanager.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isEmpty
import com.google.android.material.slider.Slider
import com.google.android.material.slider.RangeSlider
import kotlin.math.abs
import kotlin.math.roundToInt

/** Three persistent pages move with the finger; progress also drives the navigation indicator. */
@android.annotation.SuppressLint("ViewConstructor")
internal class PageSwipeHost(
    context: Context,
    initialPage: Int,
    private val progress: (Float) -> Unit,
    private val selected: (Int) -> Unit,
    private val swipeStarted: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var page = initialPage
    private var position = initialPage.toFloat()
    private var downPosition = position
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var blocked = false
    private var velocity: VelocityTracker? = null
    private var settling: ValueAnimator? = null
    private var generation = 0
    private val direction get() = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f

    fun setPage(target: Int, animated: Boolean = true) {
        if (isEmpty()) return
        val destination = target.coerceIn(0, childCount - 1)
        cancelScroll()
        if (!animated || !UiMotion.enabled() || abs(position - destination) < 0.001f) {
            finish(destination); return
        }
        val request = generation
        settling = ValueAnimator.ofFloat(position, destination.toFloat()).apply {
            duration = (180f + 100f * abs(position - destination)).coerceAtMost(350f).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { position = it.animatedValue as Float; render() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (request == generation) { settling = null; finish(destination) }
                }
            })
            start()
        }
    }

    fun cancelScroll() {
        generation++
        settling?.cancel(); settling = null
    }

    private fun finish(destination: Int) {
        page = destination; position = destination.toFloat()
        render()
        selected(destination)
    }

    private fun render() {
        val pageWidth = width
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                translationX = (i - position) * pageWidth * direction
                visibility = if (abs(i - position) < 1.01f) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = if (abs(i - position) < 0.001f)
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        progress(position)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; downPosition = position
            horizontal = settling != null
            if (horizontal) swipeStarted()
            blocked = !horizontal && touchesHorizontalControl(this, downX, downY)
            cancelScroll()
            velocity?.recycle(); velocity = VelocityTracker.obtain()
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
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !blocked && !horizontal) {
            val dx = abs(event.x - downX); val dy = abs(event.y - downY)
            if (dy > slop && dy > dx) blocked = true
            else if (dx > slop && dx > dy * 1.5f) {
                horizontal = true
                swipeStarted()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return horizontal
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!horizontal) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!blocked && width > 0) {
                    position = (downPosition - (event.x - downX) / width * direction).coerceIn(0f, (childCount - 1).toFloat())
                    render()
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                val distance = abs(dx) >= maxOf(context.dp(48).toFloat(), width * 0.22f)
                val fling = abs(dx) >= context.dp(24) && abs(vx) >= context.dp(600) && abs(vx) > abs(vy) * 1.5f && vx * dx > 0f
                val start = downPosition.roundToInt()
                val target = if (!blocked && (distance || fling)) start + if (dx * direction < 0) 1 else -1 else start
                horizontal = false; parent?.requestDisallowInterceptTouchEvent(false)
                setPage(target)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                horizontal = false; parent?.requestDisallowInterceptTouchEvent(false)
                setPage(page)
            }
        }
        return true
    }

    private fun touchesHorizontalControl(view: View, x: Float, y: Float): Boolean {
        if (view is SwitchCompat || view is SeekBar || view is HorizontalScrollView || view is Slider || view is RangeSlider) return true
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val childX = x + view.scrollX - child.left - child.translationX
            val childY = y + view.scrollY - child.top - child.translationY
            if (childX >= 0 && childX < child.width && childY >= 0 && childY < child.height &&
                touchesHorizontalControl(child, childX, childY)) return true
        }
        return false
    }

    override fun onDetachedFromWindow() {
        cancelScroll(); velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
