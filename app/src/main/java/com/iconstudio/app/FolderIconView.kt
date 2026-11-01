package com.iconstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/** Folder icon: a rounded square with up to 9 mini app icons inside. */
class FolderIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }

    /** Own copies of the drawables, so changing their bounds can't affect other views. */
    var icons: List<Drawable> = emptyList()
        set(value) {
            field = value.take(9).map { it.constantState?.newDrawable()?.mutate() ?: it }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.24f, w * 0.24f, bg)
        val pad = w * 0.12f
        val gap = w * 0.05f
        val cell = (w - 2 * pad - 2 * gap) / 3f
        icons.forEachIndexed { i, d ->
            val r = i / 3
            val c = i % 3
            val l = (pad + c * (cell + gap)).toInt()
            val t = (pad + r * (cell + gap)).toInt()
            d.setBounds(l, t, (l + cell).toInt(), (t + cell).toInt())
            d.draw(canvas)
        }
    }
}
