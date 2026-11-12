package com.iconstudio.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/*
 * Sizes that are decided while the view is MEASURED, never afterwards.
 *
 * The launcher used to resize icons and tiles from a layout-change listener. A layout-parameter
 * change made while the pager's RecyclerView is laying out is silently dropped (RecyclerView
 * defers requestLayout during its own layout), so after coming back with the Home button the
 * icons/tiles kept their first, unfitted size until something else forced another layout pass
 * (e.g. pressing Home a second time). Doing it in onMeasure makes the very first frame correct.
 */

/** An app / folder cell: one fixed-size icon above its name. */
class FitIconLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    /** The icon edge in px, the same for every icon; 0 keeps the size from the layout file. */
    var maxIcon = 0

    private val d = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val icon = findViewById<View>(R.id.icon)
        if (maxIcon > 0 && icon != null) {
            var s = maxIcon
            // A cell of fixed height (home page): shrink only when icon + name do not fit in it.
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                val room = MeasureSpec.getSize(heightMeasureSpec) - (24 * d).toInt()
                s = minOf(maxIcon, room.coerceAtLeast((28 * d).toInt()))
            }
            val lp = icon.layoutParams
            lp.width = s
            lp.height = s
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * A big tile: gets exactly the w:h shape of its size (2x2 is a square, 4x3 a 4:3 rectangle), as
 * large as fits inside the cells it covers, centred. The editor frame has the same shape.
 */
class FitTileLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    /** Cells wide / cells high. */
    var ratio = 1f

    private val d = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tile = findViewById<View>(R.id.tile)
        val label = findViewById<View>(R.id.label)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (tile != null && label != null && w > 0 && h > 0 && ratio > 0f) {
            val labelH = if (label.visibility == View.VISIBLE) (20 * d).toInt() else 0
            val aw = w - paddingLeft - paddingRight
            val ah = h - paddingTop - paddingBottom - labelH
            if (aw > 0 && ah > 0) {
                var tw = aw
                var th = (tw / ratio).toInt()
                if (th > ah) {
                    th = ah
                    tw = (th * ratio).toInt()
                }
                val lp = tile.layoutParams as LayoutParams
                lp.width = tw
                lp.height = th
                lp.weight = 0f
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
