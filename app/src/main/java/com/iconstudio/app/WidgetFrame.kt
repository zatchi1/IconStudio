package com.iconstudio.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Holds a widget and detects a long press even when the widget's own views handle touches,
 * so a widget can be dragged like any other item.
 */
class WidgetFrame(ctx: Context) : FrameLayout(ctx) {

    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private var downTime = 0L
    private val longRunnable = Runnable {
        fired = true
        // The widget's own views got the touch-down: cancel them, so releasing the finger after
        // the long press can never also "click" something inside the widget.
        val c = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        cancelChildren(c)
        c.recycle()
        onLongPress?.invoke()
    }

    private fun cancelChildren(c: MotionEvent) {
        super.dispatchTouchEvent(c)
    }

    init {
        isClickable = true   // so we always receive the whole touch sequence
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (fired) {
            // After the long press the rest of this touch belongs to nobody.
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.downTime
                downX = e.x
                downY = e.y
                postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(longRunnable)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRunnable)
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longRunnable)
        fired = false
        super.onDetachedFromWindow()
    }
}
