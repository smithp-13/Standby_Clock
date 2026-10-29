package com.standby.clock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnEnd

/**
 * macOS-style genie: a snapshot of a view is warped with a bitmap mesh and poured into a target rect.
 * Rows nearest the target start first and narrow to the target's width, so the shape funnels down
 * into it while the rows above follow.
 */
class GenieView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val from = RectF()
    private val to = RectF()
    private var progress = 0f
    private var startAlpha = 1f
    private val verts = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Pours [snapshot], currently drawn at [source] with [alpha], into [target]; calls [done] at the end. */
    fun play(snapshot: Bitmap, source: RectF, target: RectF, alpha: Float, done: () -> Unit) {
        bitmap = snapshot
        from.set(source)
        to.set(target)
        startAlpha = alpha
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            doOnEnd {
                bitmap = null
                snapshot.recycle()
                invalidate()
                done()
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        var i = 0
        for (r in 0..ROWS) {
            val v = r.toFloat() / ROWS
            // The bottom row (nearest the icon row) moves from the start; the top row from halfway.
            val t = ((progress - (1 - v) * 0.5f) / 0.5f).coerceIn(0f, 1f)
            val ease = t * t * (3 - 2 * t)
            val y = lerp(from.top + from.height() * v, to.centerY(), ease)
            val left = lerp(from.left, to.left, ease)
            val right = lerp(from.right, to.right, ease)
            for (c in 0..COLS) {
                verts[i++] = lerp(left, right, c.toFloat() / COLS)
                verts[i++] = y
            }
        }
        paint.alpha = (255 * startAlpha * (1 - progress * progress)).toInt()
        canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val COLS = 12
        const val ROWS = 24
    }
}
