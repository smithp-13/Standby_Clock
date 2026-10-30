package com.standby.clock

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Charging frame from the "Arcade charging screen" mockup: 100 points run round a corridor between two walls,
 * one per battery percent. The hero sits on the current level having collected the points behind it.
 * The corridor follows the display's rounded corners and bends round the camera punch-hole; the points are
 * spaced evenly along that real path, so the hero glides and turns smoothly instead of hopping.
 * Pac-Man theme: neon dots, the muncher and red ghosts at every 25%. Super Mario theme: a coin per 1%, a star
 * every 5% and a Goomba every 10% that Mario stomps flat; Mario uses the user's sprites if present.
 */
class ArcadeView(context: Context) : View(context) {

    var theme = Theme.PACMAN
        set(value) { field = value; applyTheme(); invalidate() }

    private val dp = resources.displayMetrics.density
    private var level = 0
    private var shownLevel = -1f // animated towards level, so the hero glides to the next point
    private var levelText = ""
    private var etaText = ""
    private var time = 0L
    private var lastFrame = 0L

    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * dp }
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
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sprite = Paint().apply { isFilterBitmap = false } // keep pixel art crisp
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.press_start_2p)
        textAlign = Paint.Align.RIGHT
    }

    private val rect = RectF()
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
    private val star = Path().apply {
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 1f else 0.45f
            val a = Math.toRadians(-90.0 + k * 36)
            val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    private val bolt = context.getDrawable(R.drawable.ic_bolt)!!.mutate() // Material Symbols bolt
    private var marioFrames: List<Bitmap> = emptyList()
    private var marioJump: Bitmap? = null

    private val glide = ValueAnimator().apply {
        duration = 900
        interpolator = DecelerateInterpolator()
        addUpdateListener { shownLevel = it.animatedValue as Float }
    }

    // ~30 fps is plenty for this pixel animation and halves the drawing cost of a full-screen 60 fps loop.
    private val ticker = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            time = total
            if (total - lastFrame >= FRAME_MS) { lastFrame = total; invalidate() }
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val next = (intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)).coerceIn(0, 100)
            if (shownLevel < 0) shownLevel = next.toFloat()
            else if (next != level) glide.apply { cancel(); setFloatValues(shownLevel, next.toFloat()); start() }
            level = next
            levelText = "$level%"
            etaText = eta()
            invalidate()
        }
    }

    init { applyTheme() }

    private fun applyTheme() {
        wall.color = if (theme == Theme.MARIO) BRICK else Color.rgb(0x2B, 0x3B, 0xFF)
        wall.strokeWidth = (if (theme == Theme.MARIO) 6 else 5) * dp
        if (theme == Theme.MARIO && marioFrames.isEmpty()) {
            marioFrames = loadSprites("mario_run_1", "mario_run_2")
            marioJump = loadSprites("mario_jump").firstOrNull()
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

    /** Optional user-supplied sprites, looked up by name so the app builds without them. */
    private fun loadSprites(vararg names: String): List<Bitmap> = names.mapNotNull { name ->
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(resources, id)
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
        glide.cancel()
        super.onDetachedFromWindow()
    }

    /** Animates only while on screen, so a hidden or stopped clock costs no frames. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (!ticker.isStarted) ticker.start() } else ticker.cancel()
    }

    // The camera punch-hole and the display's corner radius come from the window insets.
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        cutouts.clear()
        insets.displayCutout?.boundingRects?.let { cutouts.addAll(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { cornerRadius = it.radius / dp }
        }
        buildPaths()
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildPaths()

    /** A rounded rect inset by [inset] dp whose corners follow the display corners, minus a round notch bump. */
    private fun ringPath(out: Path, inset: Float, notchGap: Float) {
        out.reset()
        val r = (cornerRadius - inset).coerceAtLeast(18f) * dp
        out.addRoundRect(inset * dp, inset * dp, width - inset * dp, height - inset * dp, r, r, Path.Direction.CW)
        for (c in cutouts) {
            // The punch-hole is round, so the bump is a stadium hugging it: fully rounded ends.
            val g = notchGap * dp
            val bump = RectF(c.left - g, c.top - g, c.right + g, c.bottom + g)
            val round = minOf(bump.width(), bump.height()) / 2
            out.op(Path().apply { addRoundRect(bump, round, round, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        }
    }

    private fun buildPaths() {
        if (width == 0) return
        ringPath(outerWall, 6f, 3f)
        ringPath(track, 32f, 13f) // corridor centre line; bends further round the notch to stay between the walls
        trackMeasure.setPath(track, true)
        trackLength = trackMeasure.length
        // Start at the top-left like the mockup's index 0, running clockwise along the top edge.
        var best = Float.MAX_VALUE
        for (k in 0 until 400) {
            trackMeasure.getPosTan(trackLength * k / 400, pos, null)
            val score = pos[0] + pos[1] * 4
            if (score < best) { best = score; trackStart = trackLength * k / 400 }
        }
        trackMeasure.getPosTan(trackStart, pos, tan)
        trackDir = if (tan[0] >= 0) 1f else -1f
        innerRect.set(58 * dp, 58 * dp, width - 58 * dp, height - 58 * dp)
    }

    /** Point [p] (0..100, fractional) along the track into pos/tan; tan points the way the hero runs. */
    private fun at(p: Float) {
        var d = trackStart + trackDir * trackLength * p / 100f
        d = ((d % trackLength) + trackLength) % trackLength
        trackMeasure.getPosTan(d, pos, tan)
        tan[0] *= trackDir; tan[1] *= trackDir
    }

    override fun onDraw(canvas: Canvas) {
        if (trackLength == 0f) return
        if (theme == Theme.MARIO) {
            // No frame: the Mario scene runs edge to edge, only the charging info sits in the top right corner.
            drawInfo(canvas)
            return
        }
        canvas.drawPath(outerWall, wall)
        canvas.drawRoundRect(innerRect, 28 * dp, 28 * dp, wall)

        val heroAt = if (shownLevel >= 100f) 0f else shownLevel.coerceAtLeast(0f)
        val pulse = wave(900) * 0.65f + 0.35f
        for (i in 0 until 100) {
            if (abs(i - heroAt) < 0.5f) continue
            at(i.toFloat())
            val collected = i < heroAt
            val next = !collected && i - heroAt < 1.5f
            if (theme == Theme.MARIO) drawMarioItem(canvas, i, collected, heroAt, if (next) pulse else 1f)
            else if ((i + 1) % 25 == 0) drawGhost(canvas, pos[0], pos[1], 22 * dp, collected)
            else drawNeonDot(canvas, pos[0], pos[1], (i + 1) % 5 == 0, if (collected) 0.1f else if (next) pulse else 1f)
        }

        at(heroAt)
        val angle = Math.toDegrees(atan2(tan[1], tan[0]).toDouble()).toFloat()
        val leftward = angle > 90 || angle < -90
        canvas.save()
        canvas.translate(pos[0], pos[1])
        if (theme == Theme.MARIO) {
            canvas.scale(if (leftward) -1f else 1f, 1f) // Mario stays upright and faces where he runs
            val hop = stompHop(heroAt)
            canvas.translate(0f, -2 * dp * wave(260) - hop)
            drawMario(canvas, 30 * dp, inAir = hop > 0)
        } else {
            // Follow the path; when heading left, mirror instead of turning upside down so the eye stays on top.
            if (leftward) { canvas.rotate(angle - 180); canvas.scale(-1f, 1f) } else canvas.rotate(angle)
            canvas.translate(2 * dp * (1 - wave(520)), 0f) // the mockup's 2 dp bob
            drawMuncher(canvas, 36 * dp, wave(520))
        }
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

    /** Coin per 1%, star every 5%, Goomba every 10%; collected items fade, Goombas are squashed flat. */
    private fun drawMarioItem(canvas: Canvas, i: Int, collected: Boolean, heroAt: Float, alpha: Float) {
        val x = pos[0]; val y = pos[1]
        when {
            (i + 1) % 10 == 0 -> {
                // Squash as Mario lands on it: 0 = standing, 1 = flat.
                val squash = ((heroAt - i + 0.4f) / 0.6f).coerceIn(0f, 1f)
                drawGoomba(canvas, x, y, 22 * dp, squash)
            }
            (i + 1) % 5 == 0 -> {
                fill.color = STAR_YELLOW
                fill.alpha = if (collected) 40 else (255 * alpha).toInt()
                canvas.save(); canvas.translate(x, y)
                canvas.rotate((time % 2400) / 2400f * 20 - 10)
                val s = 9 * dp
                canvas.scale(s, s)
                canvas.drawPath(star, fill)
                canvas.restore()
            }
            else -> {
                val turn = abs(cos(2 * Math.PI * ((time + i * 90) % 1200) / 1200)).toFloat().coerceAtLeast(0.15f)
                fill.color = COIN_GOLD
                fill.alpha = if (collected) 25 else (255 * alpha).toInt()
                val s = 4 * dp
                rect.set(x - s * turn, y - s * 1.3f, x + s * turn, y + s * 1.3f)
                canvas.drawOval(rect, fill)
            }
        }
    }

    /** Mario hops as he reaches a Goomba, for the stomp. */
    private fun stompHop(heroAt: Float): Float {
        val nextGoomba = (((heroAt.toInt() + 1) / 10) * 10 + 9).toFloat()
        val toGo = nextGoomba - heroAt
        return if (toGo in 0f..0.8f) (sin(Math.PI * (1 - toGo / 0.8f)).toFloat()) * 10 * dp else 0f
    }

    private fun drawGoomba(canvas: Canvas, x: Float, y: Float, size: Float, squash: Float) {
        val h = size * (1 - 0.7f * squash)
        val bottom = y + size / 2
        fill.alpha = if (squash >= 1f) 110 else 255
        fill.color = GOOMBA_BROWN
        rect.set(x - size / 2, bottom - h, x + size / 2, bottom - h * 0.25f)
        canvas.drawArc(rect, 180f, 180f, true, fill) // mushroom cap
        rect.set(x - size / 2, bottom - h * 0.6f, x + size / 2, bottom - h * 0.25f)
        canvas.drawRect(rect, fill)
        fill.color = GOOMBA_TAN
        rect.set(x - size * 0.28f, bottom - h * 0.3f, x + size * 0.28f, bottom)
        canvas.drawRect(rect, fill) // body/feet
        if (squash < 1f) {
            fill.color = Color.WHITE
            canvas.drawCircle(x - size * 0.15f, bottom - h * 0.55f, size * 0.08f, fill)
            canvas.drawCircle(x + size * 0.15f, bottom - h * 0.55f, size * 0.08f, fill)
        }
    }

    /** The user's Mario sprite (two-frame run or jump), or a plain placeholder until the images are added. */
    private fun drawMario(canvas: Canvas, size: Float, inAir: Boolean = false) {
        val frame = if (inAir) (marioJump ?: marioFrames.firstOrNull())
        else marioFrames.getOrNull(((time / 130) % marioFrames.size.coerceAtLeast(1)).toInt())
        if (frame != null) {
            val w = size * frame.width / frame.height
            rect.set(-w / 2, -size / 2, w / 2, size / 2)
            canvas.drawBitmap(frame, null, rect, sprite)
            return
        }
        fill.alpha = 255
        fill.color = Color.rgb(0xE5, 0x25, 0x21)
        canvas.drawRect(-size * 0.3f, -size / 2, size * 0.3f, -size * 0.2f, fill) // cap
        fill.color = Color.rgb(0xFC, 0xBC, 0xB0)
        canvas.drawRect(-size * 0.25f, -size * 0.2f, size * 0.25f, size * 0.05f, fill) // face
        fill.color = Color.rgb(0x20, 0x38, 0xEC)
        canvas.drawRect(-size * 0.3f, size * 0.05f, size * 0.3f, size / 2, fill) // overalls
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

    /** The supplied red ghost, smaller than the muncher as in the game: solid ahead, 50% outline once eaten. */
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

    /** Just CHARGING, the level, and the time left to 95%, in the top right corner clear of the digits. */
    private fun drawInfo(canvas: Canvas) {
        val mario = theme == Theme.MARIO
        val ink = Theme.marioInk(context)
        // Mario has no wall, so the info goes right into the top right corner, in dark grey for the white clouds.
        val right = width - (if (mario) 24 else 66) * dp
        var y = (if (mario) 20 else 70) * dp
        val accent = if (mario) ink else ACCENT
        val scale = if (mario) 1.4f else 1f // Mario has the whole corner to itself, so the info is bigger
        label.color = accent
        label.textSize = 6 * dp * scale
        y += label.textSize
        canvas.drawText("CHARGING", right, y, label)
        val size = (12 * dp * scale).toInt()
        val left = (right - label.measureText("CHARGING") - 2 * dp - size).toInt()
        val top = (y - label.textSize / 2 - size / 2).toInt()
        bolt.setBounds(left, top, left + size, top + size)
        bolt.setTint(accent)
        bolt.alpha = (255 * (0.55f + 0.45f * wave(1400))).toInt()
        bolt.draw(canvas)

        label.color = if (mario) ink else Color.WHITE
        label.textSize = 14 * dp * scale
        y += 5 * dp + label.textSize
        canvas.drawText(levelText, right, y, label)

        label.color = if (mario) ink else Color.rgb(0x8F, 0x98, 0xFF)
        label.textSize = 5 * dp * scale
        y += 5 * dp + label.textSize
        canvas.drawText(etaText, right, y, label)
    }

    private companion object {
        const val FRAME_MS = 33L
        val ACCENT = Color.rgb(0xFF, 0xD4, 0x00)
        val GHOST_RED = Color.rgb(0xE1, 0x4D, 0x2A)
        val GHOST_YELLOW = Color.rgb(0xFD, 0xD0, 0x20)
        val BRICK = Color.rgb(0xC8, 0x4C, 0x0C)
        val COIN_GOLD = Color.rgb(0xF8, 0xB8, 0x00)
        val STAR_YELLOW = Color.rgb(0xFF, 0xE0, 0x40)
        val GOOMBA_BROWN = Color.rgb(0xA0, 0x4A, 0x10)
        val GOOMBA_TAN = Color.rgb(0xF0, 0xC0, 0x90)
        const val HERO_CX = 327f
        const val HERO_CY = 262f
        const val HERO_R = 180f
        val HEM = listOf(436f to 400f, 376f to 460f, 316f to 400f, 256f to 460f, 196f to 400f, 136f to 460f, 76f to 400f, 20f to 455f)
    }
}
