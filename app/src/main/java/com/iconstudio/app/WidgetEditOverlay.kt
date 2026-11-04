package com.iconstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Edit mode for ONE widget, drawn over its page. It lives as long as the page shows the widget
 * (the activity re-attaches it whenever the page is rebuilt), so the frame never vanishes by
 * itself. Works in fractional cell units:
 *  - drag the 8 handles to change width and height independently,
 *  - drag inside the frame to move the widget freely,
 *  - toolbar next to the frame: snap-to-grid on/off, delete, done.
 * Every touch inside the frame, a handle or the toolbar is swallowed, so nothing underneath
 * (the widget itself, other icons, the page pager) can react to it.
 */
class WidgetEditOverlay(ctx: Context, private val item: Placed) : View(ctx) {

    var allowH = true
    var allowV = true
    /** Smallest size in cells (from the widget's own minimum resize size). */
    var minW = 0.5f
    var minH = 0.5f
    var snap = false

    /** Asks the owner to apply a free rectangle (cell units); true when it was accepted. */
    var tryApply: ((x: Float, y: Float, w: Float, h: Float) -> Boolean)? = null
    var onCommit: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onToggleSnap: (() -> Unit)? = null
    /** Press and hold inside the frame without moving: pick the widget up to move it to another page. */
    var onPickUp: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val scrim = Paint().apply { color = 0x99000000.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 7f * d
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }

    // What the current touch started on.
    private val NONE = 0
    private val HANDLE = 1
    private val BODY = 2
    private val BUTTON = 3
    private val OUTSIDE = 4
    private var mode = NONE
    private var handle = -1      // 0 left, 1 top, 2 right, 3 bottom, 4 TL, 5 TR, 6 BR, 7 BL
    private var button = -1      // 0 snap, 1 delete, 2 done

    private var downX = 0f
    private var downY = 0f
    private var x0 = 0f
    private var y0 = 0f
    private var w0 = 0f
    private var h0 = 0f
    private var moved = false
    private var pickedUp = false

    private val pickUp = Runnable {
        if (mode == BODY && !moved) {
            pickedUp = true
            onPickUp?.invoke()
        }
    }

    private fun cw() = width / HomeLayout.COLS.toFloat()
    private fun ch() = height / HomeLayout.ROWS.toFloat()

    private fun rect(): RectF {
        val cw = cw()
        val ch = ch()
        return RectF(item.fx * cw, item.fy * ch, (item.fx + item.fw) * cw, (item.fy + item.fh) * ch)
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
        if (allowH && allowV) {
            out.add(4 to PointF(r.left, r.top))
            out.add(5 to PointF(r.right, r.top))
            out.add(6 to PointF(r.right, r.bottom))
            out.add(7 to PointF(r.left, r.bottom))
        }
        return out
    }

    /** Centres of the three toolbar buttons, kept off the widget and inside the screen. */
    private fun buttons(r: RectF): List<PointF> {
        val gap = 52f * d
        val half = 20f * d
        var cx = r.centerX()
        val minCx = 8f * d + half + gap
        val maxCx = width - 8f * d - half - gap
        cx = if (minCx <= maxCx) cx.coerceIn(minCx, maxCx) else width / 2f
        val above = r.top - 14f * d - half
        val below = r.bottom + 14f * d + half
        val cy = when {
            above - half >= 4f * d -> above
            below + half <= height - 4f * d -> below
            else -> (r.top + 8f * d + half).coerceAtMost(height - half - 4f * d)
        }
        return listOf(PointF(cx - gap, cy), PointF(cx, cy), PointF(cx + gap, cy))
    }

    override fun onDraw(c: Canvas) {
        val r = rect()
        // Dim everything except the selected widget, so it stands out from the rest.
        c.drawRect(0f, 0f, width.toFloat(), r.top, scrim)
        c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), scrim)
        c.drawRect(0f, r.top, r.left, r.bottom, scrim)
        c.drawRect(r.right, r.top, width.toFloat(), r.bottom, scrim)

        c.drawRoundRect(r, 10 * d, 10 * d, glow)
        c.drawRoundRect(r, 10 * d, 10 * d, line)
        for ((_, p) in handles(r)) {
            c.drawCircle(p.x, p.y, 10 * d, dark)
            c.drawCircle(p.x, p.y, 8 * d, fill)
        }

        val b = buttons(r)
        val rad = 20f * d
        // 0: snap to grid (filled when on)
        run {
            val p = b[0]
            c.drawCircle(p.x, p.y, rad, if (snap) fill else dark)
            c.drawCircle(p.x, p.y, rad, line)
            val col = if (snap) 0xFF1C1C1E.toInt() else Color.WHITE
            ink.color = col
            val s = 6.5f * d
            val g = 2.2f * d
            for (ix in 0..1) for (iy in 0..1) {
                val l = p.x - s + ix * (s + g / 2)
                val t = p.y - s + iy * (s + g / 2)
                c.drawRoundRect(l, t, l + s - g / 2, t + s - g / 2, 1.5f * d, 1.5f * d, ink.also { it.style = Paint.Style.FILL })
            }
            ink.style = Paint.Style.STROKE
            ink.color = Color.WHITE
        }
        // 1: delete (trash can)
        run {
            val p = b[1]
            c.drawCircle(p.x, p.y, rad, red)
            c.drawCircle(p.x, p.y, rad, line)
            val s = 7f * d
            c.drawLine(p.x - s, p.y - s * 0.55f, p.x + s, p.y - s * 0.55f, ink)
            c.drawLine(p.x - s * 0.35f, p.y - s * 0.95f, p.x + s * 0.35f, p.y - s * 0.95f, ink)
            val body = Path().apply {
                moveTo(p.x - s * 0.7f, p.y - s * 0.55f)
                lineTo(p.x - s * 0.55f, p.y + s)
                lineTo(p.x + s * 0.55f, p.y + s)
                lineTo(p.x + s * 0.7f, p.y - s * 0.55f)
            }
            c.drawPath(body, ink)
        }
        // 2: done (check mark)
        run {
            val p = b[2]
            c.drawCircle(p.x, p.y, rad, fill)
            c.drawCircle(p.x, p.y, rad, line)
            val s = 7f * d
            ink.color = 0xFF1C1C1E.toInt()
            ink.strokeWidth = 2.6f * d
            val tick = Path().apply {
                moveTo(p.x - s, p.y + 0.5f * d)
                lineTo(p.x - s * 0.3f, p.y + s * 0.7f)
                lineTo(p.x + s, p.y - s * 0.7f)
            }
            c.drawPath(tick, ink)
            ink.color = Color.WHITE
            ink.strokeWidth = 2f * d
        }
    }

    private fun quant(v: Float): Float =
        if (snap) Math.round(v).toFloat() else Math.round(v * 100f) / 100f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = rect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false
                pickedUp = false
                handle = -1
                button = -1
                x0 = item.fx; y0 = item.fy; w0 = item.fw; h0 = item.fh
                removeCallbacks(pickUp)

                // 1) toolbar buttons (large touch targets)
                val bs = buttons(r)
                for (i in bs.indices) {
                    if (Math.hypot((e.x - bs[i].x).toDouble(), (e.y - bs[i].y).toDouble()) <= 26 * d) {
                        button = i
                    }
                }
                if (button >= 0) {
                    mode = BUTTON
                } else {
                    // 2) the nearest resize handle within reach
                    var best = Double.MAX_VALUE
                    for ((id, p) in handles(r)) {
                        val dist = Math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble())
                        if (dist <= 30 * d && dist < best) {
                            best = dist
                            handle = id
                        }
                    }
                    mode = when {
                        handle >= 0 -> HANDLE
                        r.contains(e.x, e.y) -> BODY
                        else -> OUTSIDE
                    }
                }
                if (mode != OUTSIDE) parent?.requestDisallowInterceptTouchEvent(true)
                if (mode == BODY && onPickUp != null) {
                    postDelayed(pickUp, ViewConfiguration.getLongPressTimeout().toLong() + 150L)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pickedUp) return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (!moved && Math.abs(dx) <= slop && Math.abs(dy) <= slop) return true
                when (mode) {
                    BODY -> {
                        if (!moved) removeCallbacks(pickUp)
                        moved = true
                        moveBody(dx / cw(), dy / ch())
                    }
                    HANDLE -> {
                        moved = true
                        resizeBy(dx / cw(), dy / ch())
                    }
                    OUTSIDE -> moved = true
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(pickUp)
                parent?.requestDisallowInterceptTouchEvent(false)
                val m = mode
                mode = NONE
                if (pickedUp) {
                    pickedUp = false
                    return true
                }
                when (m) {
                    BUTTON -> {
                        val bs = buttons(r)
                        val p = bs.getOrNull(button)
                        if (p != null &&
                            Math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble()) <= 30 * d
                        ) when (button) {
                            0 -> { onToggleSnap?.invoke(); invalidate() }
                            1 -> onDelete?.invoke()
                            2 -> onDone?.invoke()
                        }
                    }
                    BODY, HANDLE -> if (moved) onCommit?.invoke()
                    OUTSIDE -> if (!moved) onDone?.invoke()
                    else -> {}
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pickUp)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (moved && (mode == BODY || mode == HANDLE)) onCommit?.invoke()
                mode = NONE
                pickedUp = false
            }
        }
        return true
    }

    /** Free move. If the full move is blocked, slide along one axis so it never just sticks. */
    private fun moveBody(dxc: Float, dyc: Float) {
        val maxX = (HomeLayout.COLS - w0).coerceAtLeast(0f)
        val maxY = (HomeLayout.ROWS - h0).coerceAtLeast(0f)
        val nx = quant((x0 + dxc).coerceIn(0f, maxX)).coerceIn(0f, maxX)
        val ny = quant((y0 + dyc).coerceIn(0f, maxY)).coerceIn(0f, maxY)
        val apply = tryApply ?: return
        if (nx == item.fx && ny == item.fy) return
        if (apply(nx, ny, w0, h0)) { invalidate(); return }
        if (ny != item.fy && apply(item.fx, ny, w0, h0)) { invalidate(); return }
        if (nx != item.fx && apply(nx, item.fy, w0, h0)) invalidate()
    }

    private fun resizeBy(dxc: Float, dyc: Float) {
        val movesL = handle == 0 || handle == 4 || handle == 7
        val movesR = handle == 2 || handle == 5 || handle == 6
        val movesT = handle == 1 || handle == 4 || handle == 5
        val movesB = handle == 3 || handle == 6 || handle == 7
        var l = x0
        var t = y0
        var r = x0 + w0
        var b = y0 + h0
        val mw = minOf(minW, w0)
        val mh = minOf(minH, h0)
        if (allowH) {
            if (movesL) l = quant((x0 + dxc).coerceIn(0f, x0 + w0 - mw)).coerceIn(0f, x0 + w0 - mw)
            if (movesR) r = quant((x0 + w0 + dxc).coerceIn(x0 + mw, HomeLayout.COLS.toFloat()))
                .coerceIn(x0 + mw, HomeLayout.COLS.toFloat())
        }
        if (allowV) {
            if (movesT) t = quant((y0 + dyc).coerceIn(0f, y0 + h0 - mh)).coerceIn(0f, y0 + h0 - mh)
            if (movesB) b = quant((y0 + h0 + dyc).coerceIn(y0 + mh, HomeLayout.ROWS.toFloat()))
                .coerceIn(y0 + mh, HomeLayout.ROWS.toFloat())
        }
        val nw = r - l
        val nh = b - t
        if (nw < mw - 0.001f || nh < mh - 0.001f) return
        if (l == item.fx && t == item.fy && nw == item.fw && nh == item.fh) return
        if (tryApply?.invoke(l, t, nw, nh) == true) invalidate()
    }
}
