package com.standby.clock

import android.animation.TimeAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.os.BatteryManager
import android.view.View
import kotlin.math.cos
import kotlin.math.floor

/** Original-maze Pac-Man charging scene; Mario keeps its existing charging information. */
class ArcadeView(context: Context) : View(context) {
    var complete = false
        set(value) {
            if (field == value) return
            field = value
            game.setCharge(level, value)
            invalidate()
        }

    var theme = Theme.PACMAN
        set(value) { field = value; accumulatedMs = 0.0; invalidate() }

    private val dp = resources.displayMetrics.density
    private val game = PacmanGame()
    private val maze = BitmapFactory.decodeResource(resources, R.drawable.pacman_maze)
    private val sprites = BitmapFactory.decodeResource(resources, R.drawable.pacman_sprites)
    private val pixels = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.press_start_2p)
        color = Color.WHITE
    }
    private val source = Rect()
    private val target = RectF()
    private val whiteMaze = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private val bolt = context.getDrawable(R.drawable.ic_bolt)!!.mutate()
    private var level = 0
    private var etaText = "95% IN --"
    private var time = 0L
    private var lastDraw = -FRAME_MS
    private var accumulatedMs = 0.0
    private var spriteWidthScale = 1f
    private var spriteHeightScale = 1f

    private val ticker = TimeAnimator().apply {
        setTimeListener { _, total, delta ->
            time = total
            if (theme == Theme.PACMAN) {
                // Discard long gaps rather than fast-forwarding after sleep or a blocked UI thread.
                accumulatedMs += delta.coerceAtMost(100).toDouble()
                while (accumulatedMs >= TICK_MS) {
                    game.tick()
                    accumulatedMs -= TICK_MS
                }
            }
            if (total - lastDraw >= FRAME_MS) { lastDraw = total; invalidate() }
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            level = (intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)).coerceIn(0, 100)
            game.setCharge(level, complete)
            etaText = eta()
            invalidate()
        }
    }

    private fun eta(): String {
        if (level >= 95) return "95% REACHED"
        val toFull = context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        if (toFull <= 0) return "95% IN --"
        val minutes = (toFull * (95 - level) / (100 - level).coerceAtLeast(1) / 60_000).toInt()
        return if (minutes >= 60) "95% IN ${minutes / 60}H ${"%02d".format(minutes % 60)}M" else "95% IN ${minutes}M"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(battery)
        ticker.cancel()
        accumulatedMs = 0.0
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        accumulatedMs = 0.0
        lastDraw = -FRAME_MS
        if (isVisible) { if (!ticker.isStarted) ticker.start() } else ticker.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (theme == Theme.PACMAN) drawGame(canvas)
        drawInfo(canvas)
    }

    private fun drawGame(canvas: Canvas) {
        val availableWidth = width - 24 * dp
        val availableHeight = height - 24 * dp
        if (availableWidth <= 0 || availableHeight <= 0) return
        val sx = availableWidth / 224f
        val sy = availableHeight / 288f
        val oldFit = minOf((width * 0.46f - 24 * dp).coerceAtLeast(1f) / 224f, sy)
        val spriteScale = if (oldFit >= 1f) floor(oldFit) else oldFit
        spriteWidthScale = spriteScale / sx
        spriteHeightScale = spriteScale / sy
        canvas.save()
        canvas.translate(12 * dp, 12 * dp)
        canvas.scale(sx, sy)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 7f
        text.color = Color.WHITE
        canvas.drawText("1UP", 8f, 8f, text)
        canvas.drawText("HIGH SCORE", 112f, 8f, text)
        canvas.drawText(game.score.toString().padStart(2, '0'), 8f, 18f, text)
        canvas.drawText(game.highScore.toString().padStart(2, '0'), 112f, 18f, text)

        canvas.save()
        canvas.translate(0f, 24f)
        canvas.clipRect(0f, 0f, 224f, 248f)
        pixels.colorFilter = if (game.flashWhite) whiteMaze else null
        canvas.drawBitmap(maze, 0f, 0f, pixels)
        pixels.colorFilter = null
        fill.color = Color.rgb(255, 184, 174)
        for (i in game.pellets.indices) {
            val kind = game.pellets[i]
            val x = (i % PacMaze.COLS) * 8f + 4f
            val y = (i / PacMaze.COLS) * 8f + 4f
            if (kind == 1) canvas.drawRect(x - 1.25f, y - 1.25f, x + 1.25f, y + 1.25f, fill)
            else if (kind == 2 && game.tick % 24 < 12) canvas.drawCircle(x, y, 3f, fill)
        }
        if (game.fruitTicks > 0) drawSprite(canvas, 52, PacmanGame.FRUIT_X, PacmanGame.FRUIT_Y)
        if (!game.pacHidden) {
            if (game.deathFrame >= 0) {
                val opening = (game.deathFrame + 1) * (360f / 11f)
                fill.color = Color.YELLOW
                target.set(game.pac.px - 6.5f, game.pac.py - 6.5f, game.pac.px + 6.5f, game.pac.py + 6.5f)
                canvas.drawArc(target, 270f + opening / 2, 360f - opening, true, fill)
            } else {
                val mouth = MOUTH_FRAMES[(game.pacAnim / 4) % 4]
                drawSprite(canvas, game.pac.dir * 3 + mouth, game.pac.px, game.pac.py)
            }
        }
        if (!game.ghostsHidden) for (ghost in game.ghosts) {
            if (ghost === game.eatenGhost) continue
            val foot = (game.tick / 8) % 2
            val index = when {
                ghost.mode == PacmanGame.EYES || ghost.mode == PacmanGame.ENTERING -> 48 + ghost.dir
                ghost.frightened -> {
                    val flashing = game.frightTicks <= PacmanGame.FRIGHT_FLASHES * 2 * PacmanGame.FLASH_TICKS &&
                        (game.frightTicks / PacmanGame.FLASH_TICKS) % 2 == 0
                    44 + (if (flashing) 2 else 0) + foot
                }
                else -> 12 + ghost.id * 8 + ghost.dir * 2 + foot
            }
            drawSprite(canvas, index, ghost.px, ghost.py)
        }
        if (game.popupTicks > 0) {
            text.color = Color.rgb(0, 255, 255)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(game.popupText, game.popupX, game.popupY + 3, text)
        }
        if (game.ready || game.gameOver) {
            text.color = if (game.gameOver) Color.RED else Color.YELLOW
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(if (game.gameOver) "GAME OVER" else "READY!", 112f, 141f, text)
        }
        canvas.restore()
        for (i in 0 until (game.lives - 1).coerceIn(0, 5)) drawSprite(canvas, 5, 16f + i * 16f, 280f)
        drawSprite(canvas, 52, 208f, 280f)
        canvas.restore()
    }

    private fun drawSprite(canvas: Canvas, index: Int, x: Float, y: Float) {
        source.set(index % 16 * 16, index / 16 * 16, index % 16 * 16 + 16, index / 16 * 16 + 16)
        target.set(x - 8 * spriteWidthScale, y - 8 * spriteHeightScale,
            x + 8 * spriteWidthScale, y + 8 * spriteHeightScale)
        canvas.drawBitmap(sprites, source, target, pixels)
        // Only tunnel sprites repeat across the opposite edge.
        if (y in 104f..128f && (x < 8f || x > 216f)) {
            target.offset(if (x < 8f) 224f else -224f, 0f)
            canvas.drawBitmap(sprites, source, target, pixels)
        }
    }

    private fun drawInfo(canvas: Canvas) {
        val mario = theme == Theme.MARIO
        val ink = Theme.marioInk(context)
        val right = width - 24 * dp
        var y = 20 * dp
        val accent = if (mario) ink else Color.YELLOW
        val size = if (mario) 1.4f else 1f
        text.textAlign = Paint.Align.RIGHT
        text.color = accent
        text.textSize = 6 * dp * size
        y += text.textSize
        val title = if (!complete) "CHARGING" else if (mario) "COURSE CLEAR!"
            else if (game.cleared) "LEVEL CLEAR!" else "CLEARING..."
        canvas.drawText(title, right, y, text)
        if (!complete) {
            val iconSize = (12 * dp * size).toInt()
            val left = (right - text.measureText(title) - 2 * dp - iconSize).toInt()
            val top = (y - text.textSize / 2 - iconSize / 2).toInt()
            bolt.setBounds(left, top, left + iconSize, top + iconSize)
            bolt.setTint(accent)
            bolt.alpha = (255 * (0.55f + 0.45f * ((cos(2 * Math.PI * (time % 1400) / 1400) + 1) / 2))).toInt()
            bolt.draw(canvas)
        }
        text.color = if (mario) ink else Color.WHITE
        text.textSize = 14 * dp * size
        y += 5 * dp + text.textSize
        canvas.drawText("$level%", right, y, text)
        text.color = if (mario) ink else Color.rgb(143, 152, 255)
        text.textSize = 5 * dp * size
        y += 5 * dp + text.textSize
        canvas.drawText(if (complete) "CHARGING ENDED" else etaText, right, y, text)
    }

    companion object {
        private val MOUTH_FRAMES = intArrayOf(0, 1, 2, 1)
        private const val FRAME_MS = 33L
        private const val TICK_MS = 1000.0 / 60.0
    }
}
