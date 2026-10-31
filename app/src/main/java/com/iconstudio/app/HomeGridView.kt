package com.iconstudio.app

import android.content.Context
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * One home page: a COLS x ROWS grid. Children are positioned by [LP] (cell + span) and
 * the page is a drop target for dragged apps.
 */
class HomeGridView(ctx: Context) : ViewGroup(ctx) {

    class LP(val col: Int, val row: Int, val w: Int, val h: Int) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    var pageIndex = 0
    var onDropItem: ((Placed, Int, Int, Int) -> Unit)? = null
    var onEdge: ((Int) -> Unit)? = null
    var onDragEnded: (() -> Unit)? = null

    init {
        setOnDragListener { _, e -> handleDrag(e) }
    }

    private fun gridW() = width - paddingLeft - paddingRight
    private fun gridH() = height - paddingTop - paddingBottom

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LP

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0, 1, 1)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams = LP(0, 0, 1, 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight) / HomeLayout.COLS
        val ch = (h - paddingTop - paddingBottom) / HomeLayout.ROWS
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            c.measure(
                View.MeasureSpec.makeMeasureSpec(cw * lp.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ch * lp.h, View.MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l - paddingLeft - paddingRight) / HomeLayout.COLS
        val ch = (b - t - paddingTop - paddingBottom) / HomeLayout.ROWS
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            val x = paddingLeft + lp.col * cw
            val y = paddingTop + lp.row * ch
            c.layout(x, y, x + lp.w * cw, y + lp.h * ch)
        }
    }

    private fun handleDrag(e: DragEvent): Boolean {
        val item = e.localState as? Placed
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return item != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                val edge = 40 * resources.displayMetrics.density
                if (e.x < edge) onEdge?.invoke(-1) else if (e.x > width - edge) onEdge?.invoke(1)
                return true
            }
            DragEvent.ACTION_DROP -> {
                if (item == null || gridW() <= 0 || gridH() <= 0) return false
                val cw = gridW() / HomeLayout.COLS.toFloat()
                val ch = gridH() / HomeLayout.ROWS.toFloat()
                val col = ((e.x - paddingLeft) / cw - item.w / 2f).roundToInt()
                    .coerceIn(0, HomeLayout.COLS - item.w)
                val row = ((e.y - paddingTop) / ch - item.h / 2f).roundToInt()
                    .coerceIn(0, HomeLayout.ROWS - item.h)
                onDropItem?.invoke(item, pageIndex, col, row)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                onDragEnded?.invoke()
                return true
            }
        }
        return true
    }
}
