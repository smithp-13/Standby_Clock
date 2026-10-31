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
 * Charging frame from the "Arcade charging screen" mockup: 100 points run round a corridor between two walls,
 * one per battery percent. The hero sits on the current level having collected the points behind it.
 * The corridor follows the display's rounded corners and bends round the camera punch-hole; the points are
 * spaced evenly along that real path, so the hero glides and turns smoothly instead of hopping.
 * Pac-Man theme: neon dots, the muncher and red ghosts at every 25%. Super Mario theme: no frame, only the
 * charging info in the top right corner (the scene itself is MarioSceneView).
 */
class ArcadeView(context: Context) : View(context) {

    /**
     * The phone has stopped charging (full, or its own limit): the info reads COURSE CLEAR / LEVEL CLEAR instead of
     * CHARGING, and the Pac-Man frame plays its ending: the muncher runs the rest of the maze eating every dot, the
     * ghosts vanish and the maze flashes white and blue, as at the end of an arcade level.
     */
    var complete = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) endStart = NOT_ENDING
            else {
                endFrom = if (shownLevel < 0) level.toFloat() else shownLevel
                runMs = ((100f - endFrom) * RUN_MS_PER_PERCENT).coerceIn(RUN_MIN_MS, RUN_MAX_MS)
                glide.cancel()
                // Opened with charging already ended: show the finished maze at once.
                endStart = if (isAttachedToWindow) time else -(runMs + FLASH_MS).toLong() - 1
            }
            invalidate()
        }
    private var endStart = NOT_ENDING // ticker time the ending began; NOT_ENDING while charging
    private var endFrom = 0f
    private var runMs = 0f

    var theme = Theme.PACMAN
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private var level = 0
    private var levelText = ""
    private var eatenText = ""
    private var time = 0L

    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * dp
        color = Color.rgb(0x2B, 0x3B, 0xFF)
    }
    private val dotCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xF1, 0xE8) }
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowSmall = glow(9 * dp)
    private val glowBig = glow(16 * dp)
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

    private val innerRect = RectF()
    private val outerWall = Path()
    private val track = Path()
    private val trackMeasure = PathMeasure()
    private var trackLength = 0f
    private var trackStart = 0f
    private var trackDir = 1f
    private val cutouts = mutableListOf<Rect>()
    private var cornerRadius = 36f // display corner radius in dp; replaced by the real value from the insets
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
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
    private val bolt = context.getDrawable(R.drawable.ic_bolt)!!.mutate() // Material Symbols bolt

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

    /** Time left to 95%, from Android's own estimate to full, scaled to the 95% mark. */
    private fun eta(): String {
        if (level >= 95) return "95% REACHED"
        val toFull = context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        if (toFull <= 0) return "95% IN --"
        val minutes = (toFull * (95 - level) / (100 - level).coerceAtLeast(1) / 60_000).toInt()
        return if (minutes >= 60) "95% IN ${minutes / 60}H ${"%02d".format(minutes % 60)}M" else "95% IN ${minutes}M"
    }

    private fun glow(radius: Float) = RadialGradient(0f, 0f, radius,
        intArrayOf(Color.argb(170, 0xFF, 0xA0, 0x70), Color.argb(60, 0xFF, 0x70, 0x40), Color.TRANSPARENT),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

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
        if (trackLength == 0f) return
        if (theme == Theme.MARIO) {
            // No frame: the Mario scene runs edge to edge, only the charging info sits in the top right corner.
            drawInfo(canvas)
            return
        }
        // The ending: run out the maze, then flash it (white and blue, four times) once the last dot is eaten.
        val ending = endStart != NOT_ENDING
        val sinceEnd = if (ending) (time - endStart).toFloat() else 0f
        val cleared = ending && sinceEnd >= runMs
        val flashing = cleared && sinceEnd - runMs < FLASH_MS
        wall.color = if (flashing && ((sinceEnd - runMs) / FLASH_STEP_MS).toInt() % 2 == 0) Color.WHITE else MAZE_BLUE
        canvas.drawPath(outerWall, wall)
        canvas.drawRoundRect(innerRect, 28 * dp, 28 * dp, wall)

        val heroAt = when {
            ending -> endFrom + (100f - endFrom) * (sinceEnd / runMs).coerceIn(0f, 1f)
            shownLevel >= 100f -> 0f
            else -> shownLevel.coerceAtLeast(0f)
        }
        val pulse = wave(900) * 0.65f + 0.35f
        for (i in 0 until 100) {
            if (abs(i - heroAt) < 0.5f) continue
            at(i.toFloat())
            val collected = i < heroAt
            val next = !collected && i - heroAt < 1.5f
            if ((i + 1) % 25 == 0) { if (!cleared) drawGhost(canvas, pos[0], pos[1], 22 * dp, collected) }
            else drawNeonDot(canvas, pos[0], pos[1], (i + 1) % 5 == 0, if (collected) 0.1f else if (next) pulse else 1f)
        }

        position(hero)
        canvas.save()
        canvas.translate(pos[0], pos[1])
        // Follow the path; when heading left, mirror instead of turning upside down so the eye stays on top.
        if (leftward) { canvas.rotate(angle - 180); canvas.scale(-1f, 1f) } else canvas.rotate(angle)
        canvas.translate(2 * dp * (1 - wave(520)), 0f) // the mockup's 2 dp bob
        drawMuncher(canvas, 36 * dp, wave(520))
        canvas.restore()

        drawInfo(canvas)
    }

    /** 1 → 0 → 1 over [periodMs], for the mockup's ease-in-out alternate animations. */
    private fun wave(periodMs: Int): Float = (cos(2 * Math.PI * (time % periodMs) / periodMs).toFloat() + 1) / 2

    private fun drawNeonDot(canvas: Canvas, x: Float, y: Float, big: Boolean, alpha: Float) {
        canvas.save()
        canvas.translate(x, y)
        dotGlow.shader = if (big) glowBig else glowSmall
        dotGlow.alpha = (255 * alpha).toInt()
        canvas.drawCircle(0f, 0f, if (big) 16 * dp else 9 * dp, dotGlow)
        dotCore.alpha = (255 * alpha).toInt()
        canvas.drawCircle(0f, 0f, if (big) 6 * dp else 3 * dp, dotCore)
        canvas.restore()
    }

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
        val ended = complete
        val title = if (!ended) "CHARGING" else if (mario) "COURSE CLEAR!" else "LEVEL CLEAR!"
        canvas.drawText(title, right, y, label)
        if (!ended) {
            val size = (12 * dp * scale).toInt()
            val left = (right - label.measureText(title) - 2 * dp - size).toInt()
            val top = (y - label.textSize / 2 - size / 2).toInt()
            bolt.setBounds(left, top, left + size, top + size)
            bolt.setTint(accent)
            bolt.alpha = (255 * (0.55f + 0.45f * wave(1400))).toInt()
            bolt.draw(canvas)
        }

        label.color = Color.WHITE
        label.textSize = 14 * dp
        y += 5 * dp + label.textSize
        canvas.drawText(levelText, right, y, label)

        label.color = Color.rgb(0x8F, 0x98, 0xFF)
        label.textSize = 5 * dp
        y += 5 * dp + label.textSize
        canvas.drawText(if (ended) "CHARGING ENDED" else etaText, right, y, label)
    }

    private companion object {
        const val FRAME_MS = 33L
        const val NOT_ENDING = Long.MIN_VALUE
        const val RUN_MS_PER_PERCENT = 45f // the muncher's speed while it clears the rest of the maze
        const val RUN_MIN_MS = 600f
        const val RUN_MAX_MS = 3_500f
        const val FLASH_STEP_MS = 250f
        const val FLASH_MS = 8 * FLASH_STEP_MS
        val MAZE_BLUE = Color.rgb(0x2B, 0x3B, 0xFF)
        val ACCENT = Color.rgb(0xFF, 0xD4, 0x00)
        val GHOST_RED = Color.rgb(0xE1, 0x4D, 0x2A)
        val GHOST_YELLOW = Color.rgb(0xFD, 0xD0, 0x20)
        const val HERO_CX = 327f
        const val HERO_CY = 262f
        const val HERO_R = 180f
        val HEM = listOf(436f to 400f, 376f to 460f, 316f to 400f, 256f to 460f, 196f to 400f, 136f to 460f, 76f to 400f, 20f to 455f)
    }
}
