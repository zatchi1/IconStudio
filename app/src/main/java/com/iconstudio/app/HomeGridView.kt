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

    class LP(var col: Int, var row: Int, var w: Int, var h: Int) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    var pageIndex = 0
    var onDropItem: ((Placed, Int, Int, Int, Boolean) -> Unit)? = null   // item, page, col, row, inCentre
    var onEdge: ((Int, Int) -> Unit)? = null   // (dx, dy); (0,0) = not at an edge
    var onDragEnded: (() -> Unit)? = null
    /** Dragged item is over cell (col,row) of this page; [inCentre] = right over the middle of it. */
    var onHover: ((Placed, Int, Int, Int, Boolean) -> Unit)? = null
    /** Long press on an empty part of the page. */
    var onEmptyLongPress: (() -> Unit)? = null

    init {
        setOnDragListener { _, e -> handleDrag(e) }
        setOnLongClickListener {
            onEmptyLongPress?.invoke()
            true
        }
    }

    /** The view showing [p] on this page, if any. */
    fun viewOf(p: Placed): View? {
        for (i in 0 until childCount) if (getChildAt(i).tag === p) return getChildAt(i)
        return null
    }

    /**
     * Moves the children to the cells their [Placed] now say, sliding them there
     * (so the other icons visibly make room while something is dragged).
     */
    fun syncPositions() {
        val oldX = HashMap<View, Float>()
        val oldY = HashMap<View, Float>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            oldX[c] = c.left + c.translationX
            oldY[c] = c.top + c.translationY
            c.animate().cancel()
            val p = c.tag as? Placed ?: continue
            if (p.page != pageIndex) continue
            val lp = c.layoutParams as LP
            lp.col = p.col; lp.row = p.row; lp.w = p.w; lp.h = p.h
        }
        requestLayout()
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    val dx = (oldX[c] ?: c.left.toFloat()) - c.left
                    val dy = (oldY[c] ?: c.top.toFloat()) - c.top
                    if (dx != 0f || dy != 0f) {
                        c.translationX = dx
                        c.translationY = dy
                        c.animate().translationX(0f).translationY(0f).setDuration(190).start()
                    } else {
                        c.translationX = 0f
                        c.translationY = 0f
                    }
                }
                return true
            }
        })
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
                val edge = 28 * resources.displayMetrics.density
                val dx = if (e.x < edge) -1 else if (e.x > width - edge) 1 else 0
                val dy = if (dx != 0) 0 else if (e.y < edge) -1 else if (e.y > height - edge) 1 else 0
                onEdge?.invoke(dx, dy)
                if (item != null && gridW() > 0 && gridH() > 0) {
                    val t = target(e, item)
                    onHover?.invoke(item, pageIndex, t.first, t.second, t.third)
                }
                return true
            }
            DragEvent.ACTION_DROP -> {
                if (item == null || gridW() <= 0 || gridH() <= 0) return false
                val t = target(e, item)
                onDropItem?.invoke(item, pageIndex, t.first, t.second, t.third)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                onDragEnded?.invoke()
                return true
            }
        }
        return true
    }

    /** (col, row, inCentre) the dragged [item] would land on for the finger position in [e]. */
    private fun target(e: DragEvent, item: Placed): Triple<Int, Int, Boolean> {
        val cw = gridW() / HomeLayout.COLS.toFloat()
        val ch = gridH() / HomeLayout.ROWS.toFloat()
        val col = ((e.x - paddingLeft) / cw - item.w / 2f).roundToInt()
            .coerceIn(0, HomeLayout.COLS - item.w)
        val row = ((e.y - paddingTop) / ch - item.h / 2f).roundToInt()
            .coerceIn(0, HomeLayout.ROWS - item.h)
        val fx = (e.x - paddingLeft) / cw
        val fy = (e.y - paddingTop) / ch
        val centre = item.w == 1 && item.h == 1 &&
            (fx - Math.floor(fx.toDouble())) in 0.25..0.75 &&
            (fy - Math.floor(fy.toDouble())) in 0.2..0.8
        // For a 1x1 item the cell under the finger is the target (not the rounded-off one).
        if (item.w == 1 && item.h == 1) {
            return Triple(
                Math.floor(fx.toDouble()).toInt().coerceIn(0, HomeLayout.COLS - 1),
                Math.floor(fy.toDouble()).toInt().coerceIn(0, HomeLayout.ROWS - 1),
                centre
            )
        }
        return Triple(col, row, centre)
    }
}
