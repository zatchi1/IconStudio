package com.iconstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/** Everything the user can change in the editor, in size-independent units. */
data class EditorState(
    val zoom: Float,
    val tx: Float,
    val ty: Float,
    val angle: Float,
    val bg: Int
)

/**
 * A square crop frame. The image can be dragged, pinch-zoomed and rotated underneath it;
 * what is visible inside the frame is exactly what becomes the icon.
 */
class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BG_TRANSPARENT = 0
        const val BG_BLACK = 1
        const val BG_WHITE = 2
        const val BG_GRAY = 3

        private const val MIN_ZOOM = 0.1f
        private const val MAX_ZOOM = 8f
    }

    private var src: Bitmap? = null

    /** 1 = image just covers the frame. */
    var zoom = 1f
        private set

    /** Image centre offset from frame centre, as a fraction of the frame size. */
    private var txN = 0f
    private var tyN = 0f

    /** Degrees, -180..180, clockwise. */
    var angle = 0f
        set(value) {
            field = value
            invalidate()
        }

    var bgMode = BG_TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    /** Frame shape: width / height. 1 = square icon; other values match a bigger tile. */
    var aspect = 1f
        set(value) {
            val v = value.coerceIn(0.2f, 5f)
            if (v == field) return
            field = v
            requestLayout()
            invalidate()
        }

    /** Corner radius of the preview as a fraction of the short side (matches the home screen clip). */
    var cornerFrac = 0.24f
        set(value) {
            field = value
            invalidate()
        }

    private val clipPath = android.graphics.Path()

    /** Called whenever the zoom changes (pinch or code). */
    var onZoomChanged: ((Float) -> Unit)? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()
    private val checkerPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return true
                val newZoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val f = newZoom / zoom
                val cx = w / 2f + txN * w
                val cy = h / 2f + tyN * h
                val nx = detector.focusX + (cx - detector.focusX) * f
                val ny = detector.focusY + (cy - detector.focusY) * f
                txN = (nx - w / 2f) / w
                tyN = (ny - h / 2f) / h
                zoom = newZoom
                onZoomChanged?.invoke(zoom)
                invalidate()
                return true
            }
        })

    init {
        // 24px checkerboard tile used to show transparency.
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val p = Paint()
        p.color = 0xFF2B2B2B.toInt()
        c.drawRect(0f, 0f, 24f, 24f, p)
        p.color = 0xFF3C3C3C.toInt()
        c.drawRect(0f, 0f, 12f, 12f, p)
        c.drawRect(12f, 12f, 24f, 24f, p)
        checkerPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxSize = min(MeasureSpec.getSize(widthMeasureSpec), (340 * resources.displayMetrics.density).toInt())
        val maxH = (380 * resources.displayMetrics.density).toInt()
        var w = maxSize
        var h = (w / aspect).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).toInt()
        }
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    /** Sets the zoom from outside (slider), keeping the image centre where it is. */
    fun setZoomValue(z: Float) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    /** Rotated size of the image relative to the frame: (w/rw, h/rh) scale factors, and the cover base. */
    private fun fitFactors(): Triple<Float, Float, Float>? {
        val b = src ?: return null
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val a = Math.toRadians(angle.toDouble())
        val c = Math.abs(Math.cos(a)).toFloat()
        val sn = Math.abs(Math.sin(a)).toFloat()
        val rw = b.width * c + b.height * sn
        val rh = b.width * sn + b.height * c
        val base = maxOf(w / b.width, h / b.height)
        return Triple(w / rw / base, h / rh / base, base)
    }

    /** Whole image visible inside the frame (empty bands may remain). */
    fun fitInside() {
        val f = fitFactors() ?: return
        setZoomValue(minOf(f.first, f.second))
        txN = 0f; tyN = 0f
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    /** Frame completely covered by the image (parts of the image are cropped). */
    fun fillFrame() {
        val f = fitFactors() ?: return
        setZoomValue(maxOf(f.first, f.second))
        txN = 0f; tyN = 0f
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    fun centerImage() {
        txN = 0f; tyN = 0f
        invalidate()
    }

    /** Moves the image by a fraction of the frame size (fine positioning with buttons). */
    fun nudge(dx: Float, dy: Float) {
        txN += dx; tyN += dy
        invalidate()
    }

    fun minZoom() = MIN_ZOOM
    fun maxZoom() = MAX_ZOOM

    fun hasSource(): Boolean = src != null

    /** Shows [bitmap]; when [keepTransform] is false zoom/position/rotation are reset. */
    fun setSource(bitmap: Bitmap, keepTransform: Boolean) {
        src = bitmap
        if (!keepTransform) resetTransform()
        invalidate()
    }

    fun resetTransform() {
        zoom = 1f
        txN = 0f
        tyN = 0f
        angle = 0f
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    fun getState() = EditorState(zoom, txN, tyN, angle, bgMode)

    fun setState(s: EditorState) {
        zoom = s.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        txN = s.tx
        tyN = s.ty
        angle = s.angle
        bgMode = s.bg
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    private fun bgColor(): Int = ImageUtils.bgColor(bgMode)

    private fun buildMatrix(out: Matrix, w: Float, h: Float) {
        val b = src ?: return
        ImageUtils.buildMatrix(out, b.width, b.height, w, h, getState())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = cornerFrac * minOf(w, h)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, rad, rad, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)
        if (bgMode == BG_TRANSPARENT) {
            canvas.drawRect(0f, 0f, w, h, checkerPaint)
        } else {
            canvas.drawColor(bgColor())
        }
        src?.let {
            buildMatrix(drawMatrix, w, h)
            canvas.drawBitmap(it, drawMatrix, bmpPaint)
        }
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)
        canvas.restore()
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, rad, rad, borderPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (src == null) return false
        scaleDetector.onTouchEvent(e)

        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var fx = 0f
        var fy = 0f
        var count = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            fx += e.getX(i)
            fy += e.getY(i)
            count++
        }
        if (count == 0) return true
        fx /= count
        fy /= count

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = fx
                lastY = fy
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w > 0f && h > 0f) {
                    txN += (fx - lastX) / w
                    tyN += (fy - lastY) / h
                }
                lastX = fx
                lastY = fy
                invalidate()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /** Renders exactly what is inside the frame (long side = [outSize]); transparent if chosen. */
    fun renderOutput(outSize: Int): Bitmap? {
        val b = src ?: return null
        return ImageUtils.render(b, getState(), aspect, outSize)
    }
}
