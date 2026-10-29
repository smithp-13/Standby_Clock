package com.standby.clock

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.view.View
import android.view.animation.LinearInterpolator

/** iOS-style battery: percentage, outline filled to the level, bolt and a moving shine while on power. */
class BatteryView(context: Context) : View(context) {

    private var level = 0
    private var levelText = ""
    private var plugged = false

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }
    private val nub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    // One unit-wide gradient, stretched and moved with a matrix each frame instead of reallocated.
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 1f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(140, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }
    private val shineMatrix = Matrix()
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boltEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.nunito)
        fontVariationSettings = "'wght' 800"
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.RIGHT
    }
    private val body = RectF()
    private val inner = RectF()
    private val boltPath = Path()

    private var sweep = 0f
    private val walk = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            levelText = "$level%"
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            updateWalk()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(battery)
        walk.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateWalk()
    }

    /** The shine only runs while charging and on screen, so it costs nothing behind the lock screen. */
    private fun updateWalk() {
        if (plugged && isShown) { if (!walk.isStarted) walk.start() } else walk.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val stroke = h * 0.08f
        val nubWidth = h * 0.14f
        val right = width - nubWidth
        body.set(right - h * 2.1f + stroke / 2, stroke / 2, right - stroke / 2, h - stroke / 2)
        val radius = h * 0.3f

        outline.strokeWidth = stroke
        canvas.drawRoundRect(body, radius, radius, outline)
        canvas.drawRoundRect(right + stroke * 0.5f, h * 0.33f, width.toFloat(), h * 0.67f, nubWidth, nubWidth, nub)

        inner.set(body)
        inner.inset(stroke * 1.4f, stroke * 1.4f)
        val fillRight = inner.left + inner.width() * level / 100f
        fill.color = when {
            plugged -> Color.rgb(52, 199, 89)
            level <= 20 -> Color.rgb(255, 69, 58)
            else -> Color.WHITE
        }
        val innerRadius = radius * 0.6f
        canvas.drawRoundRect(inner.left, inner.top, fillRight, inner.bottom, innerRadius, innerRadius, fill)

        if (plugged) {
            // Shine walking left to right across the filled part.
            val band = inner.width() * 0.35f
            val x = inner.left - band + (fillRight - inner.left + band) * sweep
            shineMatrix.setScale(band, 1f)
            shineMatrix.postTranslate(x, 0f)
            shine.shader.setLocalMatrix(shineMatrix)
            canvas.save()
            canvas.clipRect(inner.left, inner.top, fillRight, inner.bottom)
            canvas.drawRect(x, inner.top, x + band, inner.bottom, shine)
            canvas.restore()
            drawBolt(canvas, body.centerX(), body.centerY(), h * 0.78f)
        }

        text.textSize = h * 0.95f
        canvas.drawText(levelText, body.left - h * 0.35f, h * 0.84f, text)
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val x0 = cx - size / 2
        val y0 = cy - size / 2
        boltPath.reset()
        BOLT.forEachIndexed { i, (px, py) ->
            if (i == 0) boltPath.moveTo(x0 + px * size, y0 + py * size) else boltPath.lineTo(x0 + px * size, y0 + py * size)
        }
        boltPath.close()
        boltEdge.strokeWidth = size * 0.1f
        canvas.drawPath(boltPath, boltEdge)
        canvas.drawPath(boltPath, bolt)
    }

    private companion object {
        val BOLT = listOf(0.58f to 0f, 0.18f to 0.58f, 0.47f to 0.58f, 0.40f to 1f, 0.82f to 0.40f, 0.53f to 0.40f)
    }
}
