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
    private val longRunnable = Runnable { onLongPress?.invoke() }

    init {
        isClickable = true   // so we always receive the whole touch sequence
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
        super.onDetachedFromWindow()
    }
}
