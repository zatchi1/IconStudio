package com.iconstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
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
    fun syncPositions(animate: Boolean = true) {
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
                    if (animate && (dx != 0f || dy != 0f)) {
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

/**
 * Drawn over a page while an item is being resized: a frame around the item with a handle on each
 * side that may be dragged (cell by cell). A tap outside the frame ends the mode.
 */
class ResizeOverlay(ctx: Context, private val item: Placed) : View(ctx) {

    var allowH = true
    var allowV = true
    var minW = 1
    var minH = 1
    /** Asks whether the item may take this cell range; returns true when it was applied. */
    var onTry: ((col: Int, row: Int, w: Int, h: Int) -> Boolean)? = null
    var onDone: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
    }

    private var active = -1   // 0 left, 1 top, 2 right, 3 bottom

    private fun gx() = paddingLeft.toFloat()
    private fun gy() = paddingTop.toFloat()
    private fun cw() = (width - paddingLeft - paddingRight) / HomeLayout.COLS.toFloat()
    private fun ch() = (height - paddingTop - paddingBottom) / HomeLayout.ROWS.toFloat()

    private fun rect(): RectF {
        val l = gx() + item.col * cw()
        val t = gy() + item.row * ch()
        return RectF(l + 3 * d, t + 3 * d, l + item.w * cw() - 3 * d, t + item.h * ch() - 3 * d)
    }

    private fun handles(r: RectF): List<Pair<Int, PointF>> {
        val out = ArrayList<Pair<Int, PointF>>()
        if (allowH) {
            out.add(0 to PointF(r.left, r.centerY()))
            out.add(2 to PointF(r.right, r.centerY()))
        }
        if (allowV) {
            out.add(1 to PointF(r.centerX(), r.top))
            out.add(3 to PointF(r.centerX(), r.bottom))
        }
        return out
    }

    private fun deleteCentre(r: RectF) = PointF(r.centerX(), r.top + 22 * d)

    override fun onDraw(c: Canvas) {
        val r = rect()
        c.drawRoundRect(r, 14 * d, 14 * d, line)
        for ((_, p) in handles(r)) {
            c.drawCircle(p.x, p.y, 8 * d, fill)
        }
        if (onDelete != null) {
            val p = deleteCentre(r)
            c.drawCircle(p.x, p.y, 14 * d, dim)
            c.drawCircle(p.x, p.y, 14 * d, line)
            val k = 5 * d
            c.drawLine(p.x - k, p.y - k, p.x + k, p.y + k, xPaint)
            c.drawLine(p.x - k, p.y + k, p.x + k, p.y - k, xPaint)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = rect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                active = -1
                val hit = 30 * d
                for ((id, p) in handles(r)) {
                    if (Math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble()) <= hit) {
                        active = id
                        parent?.requestDisallowInterceptTouchEvent(true)
                        break
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                moved = true
                val cw = cw()
                val ch = ch()
                var col = item.col
                var row = item.row
                var w = item.w
                var h = item.h
                val mw = minOf(minW, w)
                val mh = minOf(minH, h)
                when (active) {
                    0 -> {
                        val nl = Math.round((e.x - gx()) / cw).coerceIn(0, col + w - mw)
                        w = col + w - nl; col = nl
                    }
                    2 -> {
                        val nr = Math.round((e.x - gx()) / cw).coerceIn(col + mw, HomeLayout.COLS)
                        w = nr - col
                    }
                    1 -> {
                        val nt = Math.round((e.y - gy()) / ch).coerceIn(0, row + h - mh)
                        h = row + h - nt; row = nt
                    }
                    3 -> {
                        val nb = Math.round((e.y - gy()) / ch).coerceIn(row + mh, HomeLayout.ROWS)
                        h = nb - row
                    }
                }
                if ((col != item.col || row != item.row || w != item.w || h != item.h) &&
                    onTry?.invoke(col, row, w, h) == true
                ) invalidate()
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved && active < 0) {
                    val dp = deleteCentre(r)
                    if (onDelete != null &&
                        Math.hypot((e.x - dp.x).toDouble(), (e.y - dp.y).toDouble()) <= 24 * d
                    ) onDelete?.invoke()
                    else if (!r.contains(e.x, e.y)) onDone?.invoke()
                } else if (moved) {
                    onCommit?.invoke()
                }
                active = -1
            }
            MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }
}
