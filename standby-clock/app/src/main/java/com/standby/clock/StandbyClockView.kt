package com.standby.clock

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.os.Build
import java.util.Calendar

/** iOS StandBy "big numerals" style: heavy rounded digits that overlap, with a two-dot colon. */
class StandbyClockView(context: Context) : View(context) {

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.nunito)
        fontVariationSettings = "'wght' 1000"
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.materialSecondary(context) }
    private val digitColors = intArrayOf(Theme.materialAccent(context), Theme.materialSecondary(context))
    private val bounds = Rect()
    private val centers = FloatArray(4)

    /** Text size as a fraction of the view height, before shrinking to fit the width. */
    var heightFactor = 0.9f
        set(value) { field = value; invalidate() }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    // Digits on screen ("1535") and the ones they are rolling away from when the minute changes.
    private var shown = ""
    private var hourDigits = 2
    private var previous = ""
    private var previousHourDigits = 2
    private var progress = 1f
    private val roll = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        interpolator = DecelerateInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(ticker, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        update()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(ticker)
        roll.cancel()
        super.onDetachedFromWindow()
    }

    private fun update() {
        val now = Calendar.getInstance()
        val hour = if (DateFormat.is24HourFormat(context)) "%02d".format(now[Calendar.HOUR_OF_DAY])
        else (now[Calendar.HOUR].takeIf { it != 0 } ?: 12).toString()
        val next = hour + "%02d".format(now[Calendar.MINUTE])
        if (next == shown) return
        previous = shown
        previousHourDigits = hourDigits
        shown = next
        hourDigits = hour.length
        if (previous.isEmpty()) invalidate() else roll.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty()) return
        // Size from the height (leaving room for the date and icons), then shrink to fit the width.
        digitPaint.textSize = height * heightFactor
        val slots = (shown.length - 1) * OVERLAP + COLON_GAP
        val fit = width * 0.96f / ((slots + 1) * digitPaint.measureText("0"))
        if (fit < 1f) digitPaint.textSize *= fit
        digitPaint.getTextBounds("0", 0, 1, bounds)
        val baseline = (height + bounds.height()) / 2f
        val shift = bounds.height() * 0.3f

        // Changed digits roll: the old one fades out upwards while the new one fades in from below.
        val sameLayout = previous.length == shown.length
        fun unchanged(i: Int) = sameLayout && previous[i] == shown[i]
        if (progress < 1f) drawDigits(canvas, previous, previousHourDigits, baseline) { i ->
            if (unchanged(i)) null else (1 - progress) to -shift * progress
        }
        val colonX = drawDigits(canvas, shown, hourDigits, baseline) { i ->
            if (progress >= 1f || unchanged(i)) 1f to 0f else progress to shift * (1 - progress)
        }

        val radius = digitPaint.textSize * 0.075f
        canvas.drawCircle(colonX, baseline - bounds.height() * 0.68f, radius, dotPaint)
        canvas.drawCircle(colonX, baseline - bounds.height() * 0.3f, radius, dotPaint)
    }

    /** Draws [text] with a per-digit (alpha, dy); null skips the digit. Returns the colon's x. */
    private fun drawDigits(
        canvas: Canvas, text: String, hourLen: Int, baseline: Float, style: (Int) -> Pair<Float, Float>?,
    ): Float {
        val advance = digitPaint.measureText("0")
        val step = advance * OVERLAP
        val start = (width - ((text.length - 1) * OVERLAP + COLON_GAP) * advance) / 2f
        for (i in text.indices) centers[i] = start + i * step + if (i >= hourLen) COLON_GAP * advance else 0f
        for (i in text.indices) {
            val (alpha, dy) = style(i) ?: continue
            val color = digitColors[i % 2]
            digitPaint.color = color
            digitPaint.alpha = (Color.alpha(color) * alpha).toInt()
            canvas.drawText(text, i, i + 1, centers[i], baseline + dy, digitPaint)
        }
        return (centers[hourLen - 1] + centers[hourLen]) / 2f
    }

    private companion object {
        const val OVERLAP = 0.72f   // neighbouring digits share ~28% of their width
        const val COLON_GAP = 0.3f  // extra space between hours and minutes, in digit widths
    }
}
