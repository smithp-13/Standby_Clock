package com.standby.clock

import android.animation.TimeAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
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
    private val maze by lazy { buildMaze() }
    private val sprites = BitmapFactory.decodeResource(resources, R.drawable.pacman_sprites)
    private val pixels = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.press_start_2p)
        color = Color.WHITE
    }
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.press_start_2p)
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
        else drawInfo(canvas)
    }

    /** Draw corridor boundaries from the same square-tile topology used by movement. */
    private fun buildMaze(): Bitmap {
        val bitmap = Bitmap.createBitmap(PacMaze.COLS * 8, PacMaze.ROWS * 8, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(33, 33, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            pathEffect = CornerPathEffect(3f)
        }
        val edges = linkedMapOf<Int, MutableList<Int>>()
        val incoming = mutableSetOf<Int>()
        fun edge(x1: Int, y1: Int, x2: Int, y2: Int) {
            val from = y1 * (PacMaze.COLS + 1) + x1
            val to = y2 * (PacMaze.COLS + 1) + x2
            edges.getOrPut(from) { mutableListOf() }.add(to)
            incoming.add(to)
        }
        for (y in 0 until PacMaze.ROWS) for (x in 0 until PacMaze.COLS) {
            if (!PacMaze.walkable[PacMaze.idx(x, y)]) continue
            for (d in 0..3) {
                val nx = x + PacMaze.DX[d]; val ny = y + PacMaze.DY[d]
                if (nx !in 0 until PacMaze.COLS || ny !in 0 until PacMaze.ROWS) continue
                if (PacMaze.walkable[PacMaze.idx(nx, ny)]) continue
                when (d) {
                    PacMaze.UP -> edge(x, y, x + 1, y)
                    PacMaze.LEFT -> edge(x, y + 1, x, y)
                    PacMaze.DOWN -> edge(x + 1, y + 1, x, y + 1)
                    else -> edge(x + 1, y, x + 1, y + 1)
                }
            }
        }
        // Join tile edges before rounding so the walls stay continuous at every corner.
        val walls = Path()
        while (edges.isNotEmpty()) {
            val start = edges.keys.firstOrNull { it !in incoming } ?: edges.keys.first()
            var point = start
            walls.moveTo(point % (PacMaze.COLS + 1) * 8f, point / (PacMaze.COLS + 1) * 8f)
            do {
                val from = point
                val nextEdges = edges[point] ?: break
                point = nextEdges.removeAt(0)
                if (nextEdges.isEmpty()) edges.remove(from)
                walls.lineTo(point % (PacMaze.COLS + 1) * 8f, point / (PacMaze.COLS + 1) * 8f)
            } while (point != start)
            if (point == start) walls.close()
        }
        c.save()
        c.translate(0.5f, 0.5f)
        c.drawPath(walls, line)
        c.restore()
        line.color = Color.rgb(255, 184, 255)
        c.drawLine(240f, 80f, 256f, 80f, line)
        return bitmap
    }

    private fun drawGame(canvas: Canvas) {
        val availableWidth = width - 16 * dp
        val header = 48 * dp
        val availableHeight = height - header - 8 * dp
        if (availableWidth <= 0 || availableHeight <= 0) return
        val scale = minOf(availableWidth / (PacMaze.COLS * 8), availableHeight / (PacMaze.ROWS * 8))
        val previousSize = floor((height - 24 * dp) / 288f).coerceAtLeast(1f)
        spriteWidthScale = minOf(1f, previousSize / scale)
        spriteHeightScale = spriteWidthScale
        val boardLeft = (width - PacMaze.COLS * 8 * scale) / 2
        val infoLeft = boardLeft + 8 * scale
        val scoreLabel = "LEVEL ${game.boardNumber}/10  SCORE ${game.score}"
        hud.textSize = 7 * dp
        hud.textAlign = Paint.Align.LEFT
        hud.color = Color.WHITE
        canvas.drawText(scoreLabel, infoLeft, 18 * dp, hud)
        hud.color = Color.YELLOW
        canvas.drawText("$level% CHARGED", infoLeft, 33 * dp, hud)
        val regionLabel = if (game.activeRegion >= 0)
            "REGION ${(game.boardNumber - 1) * 10 + game.activeRegion + 1}%"
            else if (game.boardNumber == 10 && game.cleared) "CHARGE COMPLETE" else "WAITING FOR CHARGE"
        val centerLabel = "$regionLabel  ·  GOAL ${game.boardNumber * 10}%"
        hud.textAlign = Paint.Align.CENTER
        val powered = game.frightTicks > 0
        val powerLabel = if (powered) "POWER-UP ${(game.frightTicks + 59) / 60}S" else "POWER-UP --"
        hud.color = Color.CYAN
        canvas.drawText(powerLabel, width / 2f, 18 * dp, hud)
        hud.color = Color.WHITE
        hud.textSize = 5.5f * dp
        canvas.drawText(centerLabel, width / 2f, 33 * dp, hud)
        canvas.save()
        canvas.translate(boardLeft,
            header + (availableHeight - PacMaze.ROWS * 8 * scale) / 2)
        canvas.scale(scale, scale)
        text.textSize = 7f
        canvas.save()
        canvas.clipRect(0f, 0f, PacMaze.COLS * 8f, PacMaze.ROWS * 8f)
        pixels.colorFilter = if (game.flashWhite) whiteMaze else null
        canvas.drawBitmap(maze, 0f, 0f, pixels)
        pixels.colorFilter = null
        fill.color = Color.rgb(255, 184, 174)
        for (i in game.pellets.indices) {
            val kind = game.pellets[i]
            fill.color = if (PacMaze.region(i) == game.activeRegion) Color.rgb(255, 235, 160)
                else Color.rgb(105, 111, 140)
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
                // Match the atlas body's 13x13 bounds and scale, including its half-pixel offset.
                target.set(game.pac.px - 7 * spriteWidthScale, game.pac.py - 7 * spriteHeightScale,
                    game.pac.px + 6 * spriteWidthScale, game.pac.py + 6 * spriteHeightScale)
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
            canvas.drawText(if (game.gameOver) "GAME OVER" else "READY!", 248f, 132f, text)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawSprite(canvas: Canvas, index: Int, x: Float, y: Float) {
        source.set(index % 16 * 16, index / 16 * 16, index % 16 * 16 + 16, index / 16 * 16 + 16)
        target.set(x - 8 * spriteWidthScale, y - 8 * spriteHeightScale,
            x + 8 * spriteWidthScale, y + 8 * spriteHeightScale)
        canvas.drawBitmap(sprites, source, target, pixels)
        // Only tunnel sprites repeat across the opposite edge.
        if (y in 96f..104f && (x < 8f || x > PacMaze.COLS * 8 - 8f)) {
            target.offset(if (x < 8f) PacMaze.COLS * 8f else -PacMaze.COLS * 8f, 0f)
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
