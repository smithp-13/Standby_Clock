package com.standby.clock

import android.animation.TimeAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Charging frame from the "Arcade charging screen" mockup (designed at 892x412 dp, this phone's landscape size):
 * 100 dots run round a corridor between two blue walls, one per battery percent. The muncher sits on the
 * current level having eaten the dots behind it, and a ghost stands at every 25%.
 */
class ArcadeView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private var level = 0
    private var levelText = ""
    private var eatenText = ""
    private var time = 0L

    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x2B, 0x3B, 0xFF)
        strokeWidth = 5 * dp
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xD9, 0xC7) }
    private val ghostLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GHOST_RED
        strokeWidth = 32f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GHOST_RED }
    private val ghostEye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GHOST_YELLOW
        strokeWidth = 34f
    }
    private val heroLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ACCENT
        strokeWidth = 28f
    }
    private val heroFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.press_start_2p)
        textAlign = Paint.Align.RIGHT
    }

    private val rect = RectF()
    private val heroOval = RectF(HERO_CX - HERO_R, HERO_CY - HERO_R, HERO_CX + HERO_R, HERO_CY + HERO_R)
    private val heroMouth = Path()
    private val ghost = Path().apply {
        // Traced from the supplied ghost icon in a 512-unit box: dome, straight sides, zigzag hem.
        moveTo(20f, 455f)
        lineTo(20f, 250f)
        arcTo(RectF(20f, 14f, 492f, 486f), 180f, 180f)
        lineTo(492f, 455f)
        for ((x, y) in HEM) lineTo(x, y)
        close()
    }
    private val bolt = Path().apply {
        // The mockup's 12x16 bolt.
        moveTo(7.5f, 0f); lineTo(0f, 9f); lineTo(5f, 9f); lineTo(3.5f, 16f); lineTo(12f, 6f); lineTo(7f, 6f); close()
    }
    private val point = FloatArray(4)

    private val ticker = TimeAnimator().apply { setTimeListener { _, total, _ -> time = total; invalidate() } }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            level = (intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)).coerceIn(0, 100)
            levelText = "$level%"
            eatenText = "$level / 100 EATEN"
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(battery)
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    /** Animates only while on screen, so a hidden or stopped clock costs no frames. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (!ticker.isStarted) ticker.start() } else ticker.cancel()
    }

    /** Position [i] (0..99) round the dot loop: x, y, rotation and horizontal flip, as in the mockup's pos(). */
    private fun position(i: Int) {
        val l = 36 * dp
        val t = 36 * dp
        val r = width - 36 * dp
        val b = height - 36 * dp
        when {
            i < 36 -> set(l + (r - l) * i / 36, t, 0f, 1f)
            i < 50 -> set(r, t + (b - t) * (i - 36) / 14, 90f, 1f)
            i < 86 -> set(r - (r - l) * (i - 50) / 36, b, 0f, -1f)
            else -> set(l, b - (b - t) * (i - 86) / 14, -90f, 1f)
        }
    }

    private fun set(x: Float, y: Float, rotation: Float, flip: Float) {
        point[0] = x; point[1] = y; point[2] = rotation; point[3] = flip
    }

    override fun onDraw(canvas: Canvas) {
        // Outer and inner walls of the corridor the dots run in. The outer wall hugs the screen edge, its
        // corners following the M34's ~36 dp display corner radius.
        rect.set(6 * dp, 6 * dp, width - 6 * dp, height - 6 * dp)
        canvas.drawRoundRect(rect, 30 * dp, 30 * dp, wall)
        rect.set(58 * dp, 58 * dp, width - 58 * dp, height - 58 * dp)
        canvas.drawRoundRect(rect, 18 * dp, 18 * dp, wall)

        val hero = if (level >= 100) 0 else level
        val pulse = wave(900) * 0.65f + 0.35f
        for (i in 0 until 100) {
            if (i == hero) continue
            position(i)
            val eaten = i < level
            if ((i + 1) % 25 == 0) {
                drawGhost(canvas, point[0], point[1], 30 * dp, eaten)
                continue
            }
            val size = if ((i + 1) % 5 == 0) 12 * dp else 6 * dp
            val alpha = when {
                eaten -> 0.1f
                i == hero + 1 -> pulse
                else -> 1f
            }
            dot.alpha = (255 * alpha).toInt()
            canvas.drawCircle(point[0], point[1], size / 2, dot)
        }

        position(hero)
        canvas.save()
        canvas.translate(point[0], point[1])
        canvas.rotate(point[2])
        canvas.scale(point[3], 1f)
        // Lean forward so the open mouth sits right at the next coin, plus the mockup's 2 dp bob.
        canvas.translate(6 * dp + 2 * dp * (1 - wave(520)), 0f)
        drawMuncher(canvas, 36 * dp, wave(520))
        canvas.restore()

        drawInfo(canvas)
    }

    /** 1 → 0 → 1 over [periodMs], for the mockup's ease-in-out alternate animations. */
    private fun wave(periodMs: Int): Float = (cos(2 * Math.PI * (time % periodMs) / periodMs).toFloat() + 1) / 2

    /** The supplied muncher icon without its speed lines, centred on its body; [open] 1 = mouth wide. */
    private fun drawMuncher(canvas: Canvas, size: Float, open: Float) {
        canvas.save()
        canvas.scale(size / 512, size / 512)
        canvas.translate(-HERO_CX, -HERO_CY)
        val half = 6f + 28f * open
        val rad = Math.toRadians(half.toDouble())
        heroMouth.reset()
        heroMouth.moveTo(HERO_CX + 18f, HERO_CY)
        heroMouth.lineTo(HERO_CX + HERO_R * cos(rad).toFloat(), HERO_CY - HERO_R * sin(rad).toFloat())
        heroMouth.arcTo(heroOval, -half, -(360f - 2 * half))
        heroMouth.close()
        canvas.drawPath(heroMouth, heroLine)
        canvas.drawRect(338f, 148f, 366f, 176f, heroFill) // eye
        canvas.restore()
    }

    /** The supplied red ghost: solid while still ahead, a 50% outline ("disabled") once eaten. */
    private fun drawGhost(canvas: Canvas, x: Float, y: Float, size: Float, eaten: Boolean) {
        canvas.save()
        canvas.translate(x - size / 2, y - size / 2)
        canvas.scale(size / 512, size / 512)
        val alpha = if (eaten) 128 else 255
        ghostLine.alpha = alpha
        ghostEye.alpha = alpha
        if (!eaten) canvas.drawPath(ghost, ghostFill)
        canvas.drawPath(ghost, ghostLine)
        canvas.drawCircle(165f, 228f, 35f, ghostEye)
        canvas.drawCircle(348f, 228f, 35f, ghostEye)
        canvas.restore()
    }

    /** CHARGING / level / eaten, tucked in the top right corner inside the inner wall, kept small for the clock. */
    private fun drawInfo(canvas: Canvas) {
        val right = width - 76 * dp
        var y = 72 * dp
        label.color = ACCENT
        label.textSize = 6 * dp
        y += label.textSize
        canvas.drawText("CHARGING", right, y, label)
        val boltAlpha = (255 * (0.55f + 0.45f * wave(1400))).toInt()
        heroFill.alpha = boltAlpha
        canvas.save()
        canvas.translate(right - label.measureText("CHARGING") - 10 * dp, y - 8 * dp)
        canvas.scale(0.55f * dp, 0.55f * dp)
        canvas.drawPath(bolt, heroFill)
        canvas.restore()
        heroFill.alpha = 255

        label.color = Color.WHITE
        label.textSize = 14 * dp
        y += 5 * dp + label.textSize
        canvas.drawText(levelText, right, y, label)

        label.color = Color.rgb(0x8F, 0x98, 0xFF)
        label.textSize = 5 * dp
        y += 5 * dp + label.textSize
        canvas.drawText(eatenText, right, y, label)
    }

    private companion object {
        val ACCENT = Color.rgb(0xFF, 0xD4, 0x00)
        val GHOST_RED = Color.rgb(0xE1, 0x4D, 0x2A)
        val GHOST_YELLOW = Color.rgb(0xFD, 0xD0, 0x20)
        const val HERO_CX = 327f
        const val HERO_CY = 262f
        const val HERO_R = 180f
        val HEM = listOf(436f to 400f, 376f to 460f, 316f to 400f, 256f to 460f, 196f to 400f, 136f to 460f, 76f to 400f, 20f to 455f)
    }
}
