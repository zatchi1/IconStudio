package com.iconstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.max

object ImageUtils {

    /**
     * Decodes an image (PNG/JPG/...) from a content Uri, downsampled so its longest side is at
     * most [maxDim] pixels, and applies the EXIF orientation. Runs entirely on the device.
     * Returns null if the image cannot be read.
     */
    fun decodeSampled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val cr = ctx.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val orientation = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            val longest = max(bmp.width, bmp.height)
            if (longest > maxDim) {
                val f = maxDim.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * f).toInt()),
                    max(1, (bmp.height * f).toInt()),
                    true
                )
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // ------------------------------------------------------------------ icon rendering
    // One single place that turns (source image + editor state + frame shape) into pixels.
    // The editor preview, the saved icon and the launcher all use it, so they always agree.

    private val renderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun bgColor(mode: Int): Int = when (mode) {
        EditorView.BG_BLACK -> Color.BLACK
        EditorView.BG_WHITE -> Color.WHITE
        EditorView.BG_GRAY -> 0xFF3A3A3A.toInt()
        else -> Color.TRANSPARENT
    }

    /** Maps the source image into a frame of [w] x [h] pixels (cover scale * zoom, rotated, moved). */
    fun buildMatrix(out: Matrix, srcW: Int, srcH: Int, w: Float, h: Float, s: EditorState) {
        val base = maxOf(w / srcW, h / srcH)
        val sc = base * s.zoom
        out.reset()
        out.postTranslate(-srcW / 2f, -srcH / 2f)
        out.postRotate(s.angle)
        out.postScale(sc, sc)
        out.postTranslate(w / 2f + s.tx * w, h / 2f + s.ty * h)
    }

    /** Frame size in pixels for a frame shape [aspect] (width / height) whose long side is [outSize]. */
    fun frameSize(aspect: Float, outSize: Int): Pair<Int, Int> {
        val a = aspect.coerceIn(0.2f, 5f)
        val ow = if (a >= 1f) outSize else (outSize * a).toInt().coerceAtLeast(1)
        val oh = if (a >= 1f) (outSize / a).toInt().coerceAtLeast(1) else outSize
        return ow to oh
    }

    /** Renders exactly what the editor frame shows, for a frame of shape [aspect]. Never stretches. */
    fun render(src: Bitmap, s: EditorState, aspect: Float, outSize: Int): Bitmap {
        val (ow, oh) = frameSize(aspect, outSize)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (s.bg != EditorView.BG_TRANSPARENT) c.drawColor(bgColor(s.bg))
        val m = Matrix()
        buildMatrix(m, src.width, src.height, ow.toFloat(), oh.toFloat(), s)
        c.drawBitmap(src, m, renderPaint)
        return out
    }
}
