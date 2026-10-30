package com.standby.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.standby.clock.MarioSprites as S
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Super Mario clock face with 2D parallax scrolling background and sequential block-punching animation:
 * - The scene is drawn into a low-resolution buffer, one buffer pixel per Mario pixel, and scaled up unfiltered,
 *   so sky, hills, bricks and sprites are all real pixel art on the same grid.
 * - Mario walks with a 3-frame NES-style cycle (1-2-3-2) timed by the distance he covers, so his feet match the ground.
 * - The course scrolls in pipes, Piranha Plants, Goombas, Koopas, spike traps and hard, brick, ice, note and donut
 *   blocks. Mario jumps over or onto them, stomps enemies, waits for a Piranha Plant to duck, and is knocked back to
 *   small if something hurts him.
 * - Mario starts small. A punched ? block pops a mushroom (Super Mario), then a fire flower (Fire Mario, who throws
 *   fireballs at enemies), then a star (invincible) or a 1-Up. Each power-up wears off one level every few minutes.
 * - When the time changes, Mario walks under the block that changed and punches it to the new value, hour first, then
 *   walks back to center.
 * - Each tap on a block punches it one higher; 5 s after the last tap Mario punches both blocks back to the real time,
 *   one step per punch, the shortest way round.
 */
class MarioSceneView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density
    private val pixel = context.resources.getFont(R.font.press_start_2p)
    private val paint = Paint() // no anti-aliasing: everything it draws lands on the pixel grid
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = pixel; textAlign = Paint.Align.CENTER; color = Theme.marioInk(context)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = pixel; textAlign = Paint.Align.CENTER; color = BLOCK_LIGHT }
    private val popup = Paint().apply { typeface = pixel; textAlign = Paint.Align.CENTER; color = Color.WHITE; textSize = 8f }
    private val sprite = Paint().apply { isFilterBitmap = false }
    private val rect = RectF()

    private var shownHours = ""
    private var shownMinutes = ""

    private var marioX = 0f
    private var marioY = 0f // 0 on ground, 1 at peak of jump
    private var inAir = false
    private var facingRight = true
    private var hourBump = 0f
    private var minuteBump = 0f

    private var animTime = 0L
    private var lastFrameTime = 0L
    private var worldDistance = 0f

    // Pixel grid: one buffer pixel is `unit` screen pixels; small Mario is exactly 16 buffer pixels tall.
    private var unit = 1f
    private var buffer: Bitmap? = null
    private val bufferCanvas = Canvas()
    private var bw = 0
    private var bh = 0
    private var ground = 0
    private var brick = 1
    private var block = 0
    private var blockTop = 0
    private var hourLeft = 0
    private var minuteLeft = 0

    // Walking and jumping over obstacles, in screen pixels and milliseconds.
    private var feet = 0f // Mario's feet above the ground
    private var vy = 0f // upward speed
    private var lastMarioX = 0f
    private var stepDistance = 0f
    private var waiting = false // in front of a Piranha Plant that is out of its pipe
    private val obstacles = mutableListOf<Obstacle>()
    private var nextSpawn = 0f

    private enum class Kind(val grid: Array<String>, val solid: Boolean, val enemy: Boolean, val weight: Int) {
        PIPE(S.PIPE, true, false, 2),
        PIRANHA(S.PIPE, true, false, 2), // a pipe with a Piranha Plant in it
        GOOMBA(S.GOOMBA, false, true, 3),
        KOOPA(S.KOOPA, false, true, 2),
        SHELL(S.SHELL, false, false, 0),
        SPIKE(S.SPIKE, false, false, 1),
        HARD(S.HARD_BLOCK, true, false, 1),
        BRICK(S.BRICK_BLOCK, true, false, 1),
        ICE(S.ICE_BLOCK, true, false, 1),
        NOTE(S.NOTE_BLOCK, true, false, 1),
        DONUT(S.DONUT_BLOCK, true, false, 1),
    }

    private class Obstacle(var x: Float, var kind: Kind) {
        var squashedAt = -1L // Goomba stomped flat
        var defeatedAt = -1L // hit by a fireball or the star: flips over and falls
        var touchedAt = -1L // donut: Mario stood on it; note: last spring
        var plantGone = false
        val phase = Random.nextLong(PLANT_CYCLE_MS)
        val w get() = kind.grid[0].length
    }

    // Power-ups. Items and fireballs live in buffer pixels, in screen space; y is the bottom's height above ground.
    private enum class Power { SMALL, BIG, FIRE }
    private var power = Power.SMALL
    private var powerSince = 0L
    private var changeFrom: Power? = null // growing or shrinking
    private var changeAt = 0L
    private var starUntil = 0L
    private var safeUntil = 0L // flashing after a hit
    private var lastFire = 0L
    private var oneUpAt = -1L

    private enum class ItemKind(val grid: Array<String>) { MUSHROOM(S.MUSHROOM), FLOWER(S.FLOWER), STAR(S.STAR), ONE_UP(S.ONE_UP) }
    private class Item(val kind: ItemKind, var x: Float, var y: Float, val dir: Float, val bornAt: Long) {
        var vx = 0f
        var vy = 0f
        var free = false // out of the block
    }
    private val items = mutableListOf<Item>()

    private class Fireball(var x: Float, var y: Float, val vx: Float) {
        var vy = 0f
    }
    private val fireballs = mutableListOf<Fireball>()

    var plugged = false
        set(value) {
            field = value
            layoutScene()
            invalidate()
        }

    var previewMode = false

    private var currentAnim: Animator? = null

    // Block punches run one after another: taps count a block up, the real time is punched back in after.
    private class Punch(val isHour: Boolean, val value: String)
    private val punches = ArrayDeque<Punch>()
    private var punching: Punch? = null
    private var revertPending = false
    private val revert = Runnable { revertPending = false; reconcile() }

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (marioX == 0f && w > 0) { marioX = w / 2f; lastMarioX = marioX }
        layoutScene()
    }

    /** Places ground, bricks and ? blocks on the pixel grid. */
    private fun layoutScene() {
        if (width == 0) return
        // Small enough, with the blocks high enough, that 32-pixel Super Mario walks and jumps under the ? blocks.
        unit = height * 0.14f / 16
        bw = ceil(width / unit).toInt()
        bh = ceil(height / unit).toInt()
        if (buffer?.let { it.width != bw || it.height != bh } != false) {
            buffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bufferCanvas.setBitmap(it) }
        }
        ground = (bh * if (plugged) 0.82f else 0.84f).roundToInt()
        brick = ceil((bh - ground) / 2f).toInt()
        block = minOf(bh * 0.36f, bw * 0.24f).roundToInt()
        val gap = (block * 0.35f).roundToInt()
        blockTop = (bh * if (plugged) 0.06f else 0.05f).roundToInt()
        hourLeft = bw / 2 - gap / 2 - block
        minuteLeft = bw / 2 + gap / 2
    }

    /** Centre of the first row of ground bricks, in view pixels; the notification icons sit there. */
    fun brickRowCenter(): Float = (ground + brick / 2f) * unit

    // ~30 fps continuous walking and parallax scrolling loop
    private val runAnim = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            if (total - lastFrameTime >= 33) {
                val dt = if (lastFrameTime > 0) (total - lastFrameTime).coerceAtMost(100) else 33
                lastFrameTime = total
                animTime = total
                step(dt.toFloat())
                invalidate()
            }
        }
    }

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.standby.clock.TEST_MARIO_HOUR" -> punch(isHour = true)
                "com.standby.clock.TEST_MARIO_MINUTE" -> punch(isHour = false)
                "com.standby.clock.TEST_MARIO_BOTH" -> { punch(isHour = true); punch(isHour = false) }
                else -> update(animate = true)
            }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction("com.standby.clock.TEST_MARIO_HOUR")
            addAction("com.standby.clock.TEST_MARIO_MINUTE")
            addAction("com.standby.clock.TEST_MARIO_BOTH")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(ticker, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(ticker, filter)
        }
        update(animate = false)
        if (!runAnim.isStarted) runAnim.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revert)
        revertPending = false
        punches.clear()
        punching = null
        currentAnim?.cancel()
        currentAnim = null
        runAnim.cancel()
        context.unregisterReceiver(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (!runAnim.isStarted) runAnim.start()
        } else {
            runAnim.cancel()
        }
    }

    private val marioH get() = if (power == Power.SMALL) 16 else 32
    private val starred get() = animTime < starUntil

    /** Piranha Plant height out of its pipe, 0..1: up 1.5 s, down 2 s, half a second each way. */
    private fun plantRise(o: Obstacle): Float {
        if (o.kind != Kind.PIRANHA || o.plantGone) return 0f
        val t = (animTime + o.phase) % PLANT_CYCLE_MS
        return when {
            t < 500 -> t / 500f
            t < 2000 -> 1f
            t < 2500 -> 1 - (t - 2000) / 500f
            else -> 0f
        }
    }

    private fun donutFalling(o: Obstacle) = o.kind == Kind.DONUT && o.touchedAt >= 0 && animTime - o.touchedAt > 500

    private fun walkSpeed(o: Obstacle): Float = when {
        o.squashedAt >= 0 || o.defeatedAt >= 0 -> 0f
        o.kind == Kind.SHELL -> SHELL_SPEED * dp
        o.kind.enemy -> GOOMBA_SPEED * dp
        else -> 0f
    }

    /**
     * One frame of walking. The world scrolls while Mario walks in place; during a block punch it holds still and he
     * walks on screen.
     */
    private fun step(dt: Float) {
        if (width == 0) return
        val u = unit
        val changing = changeFrom != null && animTime - changeAt < CHANGE_MS
        if (!changing) changeFrom = null
        val scroll = if (currentAnim == null && !inAir && !waiting && !changing) dt * WALK_SPEED * dp else 0f
        worldDistance += scroll
        val marioVel = (marioX - lastMarioX) / dt
        stepDistance += scroll + abs(marioX - lastMarioX)
        lastMarioX = marioX

        if (nextSpawn == 0f) nextSpawn = worldDistance + width * 0.8f
        if (nextSpawn - worldDistance < width + 12 * u) {
            val kinds = Kind.entries.filter { it.weight > 0 }
            var r = Random.nextInt(kinds.sumOf { it.weight })
            obstacles += Obstacle(nextSpawn, kinds.first { r -= it.weight; r < 0 })
            nextSpawn += width * (0.45f + 0.45f * Random.nextFloat())
        }
        for (o in obstacles) o.x -= dt * walkSpeed(o)
        obstacles.removeAll {
            it.x - worldDistance + it.w * u < -12 * u ||
                it.squashedAt >= 0 && animTime - it.squashedAt > 500 ||
                it.defeatedAt >= 0 && animTime - it.defeatedAt > 700 ||
                donutFalling(it) && animTime - it.touchedAt > 1300
        }
        stepItems(dt)
        stepFireballs(dt)
        // Power wears off one level at a time: Fire → Super → small.
        if (power != Power.SMALL && animTime - powerSince > POWER_MS) setPower(if (power == Power.FIRE) Power.BIG else Power.SMALL)
        if (inAir || changing) return // the block punch, or the grow/shrink, owns Mario for now

        val left = marioX - 5 * u
        val right = marioX + 5 * u
        fun sx(o: Obstacle) = o.x - worldDistance
        fun over(o: Obstacle) = sx(o) < right && sx(o) + o.w * u > left
        val base = obstacles.filter { it.kind.solid && it.defeatedAt < 0 && !donutFalling(it) && over(it) }
            .maxByOrNull { it.kind.grid.size }
        val support = (base?.kind?.grid?.size ?: 0) * u
        if (vy != 0f || feet > support) {
            vy -= GRAVITY * u * dt
            feet += vy * dt
            if (feet <= support && vy < 0) {
                feet = support
                vy = 0f
                if (base?.kind == Kind.NOTE) { base.touchedAt = animTime; vy = jumpSpeed(JUMP_HEIGHT + 8) }
            }
        }
        if (vy == 0f && base?.kind == Kind.DONUT && base.touchedAt < 0) base.touchedAt = animTime

        // Contact: enemies are stomped (or knocked out by the star), spikes and Piranha Plants hurt.
        for (o in obstacles) {
            if (o.squashedAt >= 0 || o.defeatedAt >= 0 || !over(o)) continue
            when {
                o.kind.enemy && feet < o.kind.grid.size * u -> when {
                    starred -> o.defeatedAt = animTime
                    vy < 0f -> stomp(o)
                    else -> hurt()
                }
                o.kind == Kind.SPIKE && feet < o.kind.grid.size * u -> if (!starred) hurt()
                plantRise(o) > 0.3f && feet <= (S.PIPE.size + S.PIRANHA.size * plantRise(o)) * u ->
                    if (starred) o.plantGone = true else hurt()
            }
        }
        if (vy != 0f) return
        feet = support

        // Fire Mario throws a fireball at the next enemy or Piranha Plant ahead.
        val dir = if (facingRight) 1f else -1f
        if (power == Power.FIRE && animTime - lastFire > 700 && fireballs.size < 2 && obstacles.any {
                (it.kind.enemy && it.squashedAt < 0 && it.defeatedAt < 0 || plantRise(it) > 0.3f) &&
                    dir * (sx(it) - marioX) in 0f..60 * u
            }) {
            lastFire = animTime
            fireballs += Fireball(marioX / u + dir * 6, feet / u + marioH * 0.6f, dir * FIREBALL_SPEED)
        }

        waiting = false
        for (o in obstacles) {
            if (o.squashedAt >= 0 || o.defeatedAt >= 0 || o.kind == Kind.SHELL || donutFalling(o)) continue
            if (starred && o.kind.enemy) continue // plough straight through
            val dist = dir * (sx(o) + o.w * u / 2 - marioX)
            if (dist <= 0f) continue
            val edge = dist - (o.w / 2f + 5) * u
            // Piranha Plant out of its pipe: wait in front of it until it ducks back in.
            if (plantRise(o) > 0f && edge < 8 * u && !starred) {
                waiting = currentAnim == null
                break
            }
            val closing = dir * (marioVel + scroll / dt + walkSpeed(o))
            if (closing <= 0f) continue
            val height = o.kind.grid.size + 4f
            val go = if (o.kind.enemy) dist <= closing * 2 * jumpSpeed(height) / (GRAVITY * u) * 0.9f
            else edge <= closing * dt * 2 + u
            if (go) {
                vy = jumpSpeed(height)
                break
            }
        }
    }

    private fun stomp(o: Obstacle) {
        if (o.kind == Kind.KOOPA) o.kind = Kind.SHELL else o.squashedAt = animTime
        vy = jumpSpeed(6f)
    }

    /** Knocked back to small Mario (or just bounced, if already small), then a moment of safety. */
    private fun hurt() {
        if (animTime < safeUntil) return
        safeUntil = animTime + 2_000
        if (power != Power.SMALL) setPower(Power.SMALL)
        vy = jumpSpeed(6f)
    }

    private fun setPower(next: Power) {
        if (next == power) return
        changeFrom = power
        changeAt = animTime
        power = next
        powerSince = animTime
    }

    /**
     * Take-off speed for a jump [height] Mario pixels high, from where his feet are now, capped so his head never
     * rises into the ? blocks.
     */
    private fun jumpSpeed(height: Float): Float {
        val room = (ground - marioH - blockTop - block - 1) - feet / unit
        return unit * sqrt(2 * GRAVITY * minOf(height, room).coerceAtLeast(1f))
    }

    /** The punched block's item, chosen by Mario's state: mushroom → flower → star or 1-Up. One at a time. */
    private fun spawnItem(isHour: Boolean) {
        if (items.isNotEmpty()) return
        val kind = when {
            power == Power.SMALL -> ItemKind.MUSHROOM
            power == Power.BIG -> ItemKind.FLOWER
            Random.nextBoolean() -> ItemKind.STAR
            else -> ItemKind.ONE_UP
        }
        val left = if (isHour) hourLeft else minuteLeft
        items += Item(kind, left + block / 2f, (ground - blockTop - kind.grid.size).toFloat(), if (isHour) -1f else 1f, animTime)
    }

    /** Items rise out of the block, hop off its side, land, and slide (the star bounces) towards Mario. */
    private fun stepItems(dt: Float) {
        val mx = marioX / unit
        val mFeet = feet / unit
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            val h = item.kind.grid.size
            val blockTopAboveGround = (ground - blockTop).toFloat()
            if (!item.free) {
                val p = ((animTime - item.bornAt) / 500f).coerceAtMost(1f)
                item.y = blockTopAboveGround - h + h * p
                if (p >= 1f) { item.free = true; item.vx = item.dir * 0.08f; item.vy = 0.06f }
                continue
            }
            item.vy -= GRAVITY * dt
            item.y += item.vy * dt
            item.x += item.vx * dt
            if (item.y <= 0f) {
                item.y = 0f
                item.vx = sign(mx - item.x) * 0.04f
                item.vy = if (item.kind == ItemKind.STAR) 0.07f else 0f
            }
            val touching = abs(item.x - mx) < 10 && item.y < mFeet + marioH && item.y + h > mFeet
            if (touching && !inAir) {
                when (item.kind) {
                    ItemKind.MUSHROOM -> setPower(Power.BIG)
                    ItemKind.FLOWER -> setPower(Power.FIRE)
                    ItemKind.STAR -> starUntil = animTime + STAR_MS
                    ItemKind.ONE_UP -> oneUpAt = animTime
                }
                powerSince = animTime
                it.remove()
            } else if (item.x < -12 || item.x > bw + 12 || animTime - item.bornAt > 20_000) {
                it.remove()
            }
        }
    }

    /** Fireballs bounce along the ground and knock out the first enemy or Piranha Plant they reach. */
    private fun stepFireballs(dt: Float) {
        val it = fireballs.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.x += f.vx * dt
            f.vy -= GRAVITY * dt
            f.y += f.vy * dt
            if (f.y <= 0f) { f.y = 0f; f.vy = 0.05f }
            val hit = obstacles.firstOrNull { o ->
                val left = (o.x - worldDistance) / unit
                o.squashedAt < 0 && o.defeatedAt < 0 && o.kind != Kind.SHELL && f.x in left..left + o.w &&
                    f.y < o.kind.grid.size + S.PIRANHA.size * plantRise(o)
            }
            when {
                hit != null -> {
                    if (hit.kind.enemy) hit.defeatedAt = animTime
                    else if (plantRise(hit) > 0.3f) hit.plantGone = true
                    it.remove()
                }
                f.x < -8 || f.x > bw + 8 -> it.remove()
            }
        }
    }

    private val is24 get() = DateFormat.is24HourFormat(context)

    private fun hourText(hour: Int) = if (is24) "%02d".format(hour) else hour.toString()

    /** The real hour (24 h, or 1..12) and minute. */
    private fun realTime(): Pair<Int, Int> {
        val now = Calendar.getInstance()
        val hour = if (is24) now[Calendar.HOUR_OF_DAY] else (now[Calendar.HOUR].takeIf { it != 0 } ?: 12)
        return hour to now[Calendar.MINUTE]
    }

    private fun update(animate: Boolean) {
        if (!animate || !isShown || width == 0) {
            val (hour, minute) = realTime()
            shownHours = hourText(hour)
            shownMinutes = "%02d".format(minute)
            invalidate()
            return
        }
        // While the user is punching blocks, the revert after they stop brings the real time back.
        if (revertPending || punching != null) return
        reconcile()
    }

    /** Value a block shows once every queued punch has landed. */
    private fun target(isHour: Boolean): String =
        punches.lastOrNull { it.isHour == isHour }?.value
            ?: punching?.takeIf { it.isHour == isHour }?.value
            ?: if (isHour) shownHours else shownMinutes

    /** A tap on a block: one more punch counting it up. [REVERT_MS] after the last tap the real time is punched back. */
    fun punch(isHour: Boolean) {
        val now = target(isHour).toIntOrNull() ?: return
        val next = if (!isHour) "%02d".format((now + 1) % 60)
        else hourText(if (is24) (now + 1) % 24 else now % 12 + 1)
        punches.addLast(Punch(isHour, next))
        removeCallbacks(revert)
        revertPending = true
        postDelayed(revert, REVERT_MS)
        startPunches()
    }

    /** Punches both blocks to the real time, hour first, one step per punch, each the shortest way round. */
    private fun reconcile() {
        val (hour, minute) = realTime()
        stepTo(isHour = true, real = hour, span = if (is24) 24 else 12)
        stepTo(isHour = false, real = minute, span = 60)
        startPunches()
    }

    private fun stepTo(isHour: Boolean, real: Int, span: Int) {
        var value = (target(isHour).toIntOrNull() ?: return) % span // 12-hour values run 1..12: 12 counts as 0
        val up = ((real % span) - value + span) % span
        if (up == 0) return
        val step = if (up <= span / 2) 1 else -1
        repeat(minOf(up, span - up)) {
            value = (value + step + span) % span
            punches.addLast(Punch(isHour, when {
                !isHour -> "%02d".format(value)
                is24 -> hourText(value)
                else -> hourText(if (value == 0) 12 else value)
            }))
        }
    }

    private fun startPunches() {
        if (punching != null || punches.isEmpty()) return
        currentAnim?.cancel() // only ever the walk back to the middle here
        nextPunch()
    }

    /** Walks under the block (if not there already), punches it, then the next punch; walks back when done. */
    private fun nextPunch() {
        val p = punches.removeFirstOrNull()
        if (p == null) {
            punching = null
            returnCenter()
            return
        }
        punching = p
        val x = ((if (p.isHour) hourLeft else minuteLeft) + block / 2f) * unit
        currentAnim = walkTo(x, if (abs(x - marioX) < unit) 0L else 350L) {
            currentAnim = jumpAndPunch(p.isHour, p.value) { nextPunch() }.also { it.start() }
        }.also { it.start() }
    }

    private fun returnCenter() {
        // A minute ticked over while punching, with no revert to come: punch it in before leaving.
        val (hour, minute) = realTime()
        if (!revertPending && (shownHours != hourText(hour) || shownMinutes != "%02d".format(minute))) {
            reconcile()
            if (punching != null) return
        }
        currentAnim = walkTo(width / 2f, 350L) {
            facingRight = true
            currentAnim = null
        }.also { it.start() }
    }

    /** Walks Mario from current position to [destX] over [duration] ms, facing the direction of travel. */
    private fun walkTo(destX: Float, duration: Long, onDone: () -> Unit): ValueAnimator {
        val startX = marioX
        facingRight = destX >= startX
        return ValueAnimator.ofFloat(startX, destX).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                marioX = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return // a new punch took over from where Mario stands
                    marioX = destX
                    onDone()
                }
            })
        }
    }

    /** Leaps straight up to hit the target block at apex, bumps it, updates digit, pops an item, and lands. */
    private fun jumpAndPunch(targetIsHour: Boolean, newValue: String, onDone: () -> Unit): ValueAnimator {
        inAir = true
        facingRight = true
        var hit = false
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                marioY = sin(Math.PI * p).toFloat()
                val bump = if (p in 0.42f..0.85f) sin(Math.PI * (p - 0.42f) / 0.43f).toFloat() else 0f
                if (targetIsHour) hourBump = bump else minuteBump = bump
                if (p >= 0.5f) {
                    if (targetIsHour) shownHours = newValue else shownMinutes = newValue
                    if (!hit) { hit = true; spawnItem(targetIsHour) }
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    marioY = 0f
                    inAir = false
                    if (targetIsHour) hourBump = 0f else minuteBump = 0f
                    invalidate()
                    if (!cancelled) onDone()
                }
            })
        }
    }

    /** true for the hour block, false for the minute block, null anywhere else (in view pixels). */
    fun blockAt(x: Float, y: Float): Boolean? {
        val size = block * unit
        val top = blockTop * unit
        if (width == 0 || y < top - size * 0.2f || y > top + size * 1.1f) return null
        return when (x) {
            in hourLeft * unit..hourLeft * unit + size -> true
            in minuteLeft * unit..minuteLeft * unit + size -> false
            else -> null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            blockAt(event.x, event.y)?.let { punch(isHour = it) }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val buf = buffer ?: return
        drawScene(bufferCanvas)
        rect.set(0f, 0f, bw * unit, bh * unit)
        canvas.drawBitmap(buf, null, rect, sprite)

        // Digits on top at full resolution: Press Start 2P is pixel art already and stays crisp.
        drawDigits(canvas, hourLeft, hourBump, shownHours)
        drawDigits(canvas, minuteLeft, minuteBump, shownMinutes)
    }

    /** The whole scene in buffer pixels (one per Mario pixel). */
    private fun drawScene(c: Canvas) {
        val w = bw.toFloat(); val h = bh.toFloat()
        val px = dp / unit // buffer pixels per dp
        c.drawColor(SKY)

        // 1. Drifting clouds across the sky, and the smiling cloud
        val cloudDist = (animTime * 0.02f * dp + worldDistance * 0.15f) / unit
        val cloudSpan = w + 400 * px
        val c1x = ((w * 0.10f - cloudDist) % cloudSpan + cloudSpan) % cloudSpan - 150 * px
        drawCloud(c, c1x, h * 0.10f, h * 0.08f)
        val c2x = ((w * 0.65f - cloudDist * 0.85f) % cloudSpan + cloudSpan) % cloudSpan - 150 * px
        drawCloud(c, c2x, h * 0.20f, h * 0.06f)
        val c3x = ((w * 1.20f - cloudDist * 1.15f) % cloudSpan + cloudSpan) % cloudSpan - 150 * px
        drawCloud(c, c3x, h * 0.07f, h * 0.09f)
        val faceSpan = bw + 2 * S.CLOUD_FACE[0].length
        val faceX = (bw - (cloudDist * 0.6f).toInt() % faceSpan + faceSpan) % faceSpan - S.CLOUD_FACE[0].length
        drawGrid(c, S.CLOUD_FACE, faceX, (h * 0.32f + 2 * sin(animTime / 600.0)).roundToInt(), false)

        // 2. Parallax green hills (0.35x speed)
        val g = ground.toFloat()
        val hillDist = worldDistance * 0.35f / unit
        val hillSpan = maxOf(w * 1.4f, 1800f * px)
        val h1x = ((w * 0.05f - hillDist) % hillSpan + hillSpan) % hillSpan - 200 * px
        drawHill(c, h1x, g, w * 0.22f, h * 0.28f)
        val h2x = ((w * 0.75f - hillDist) % hillSpan + hillSpan) % hillSpan - 200 * px
        drawHill(c, h2x, g, w * 0.14f, h * 0.18f)

        // 3. Parallax green bushes (0.65x speed)
        val bushDist = worldDistance * 0.65f / unit
        val bushSpan = maxOf(w * 1.2f, 1400f * px)
        val b1x = ((w * 0.38f - bushDist) % bushSpan + bushSpan) % bushSpan - 150 * px
        drawBush(c, b1x, g, h * 0.06f)
        val b2x = ((w * 0.95f - bushDist) % bushSpan + bushSpan) % bushSpan - 150 * px
        drawBush(c, b2x, g, h * 0.05f)

        // 4. Ground: seamlessly scrolling bricks (1.0x speed) extending edge to edge
        drawGround(c)

        // 5. Items rising out of the ? blocks (behind them), then the blocks, bumped independently
        for (item in items) {
            drawGrid(c, item.kind.grid, (item.x - item.kind.grid[0].length / 2f).roundToInt(),
                ground - item.y.roundToInt() - item.kind.grid.size, false)
        }
        drawBlock(c, hourLeft, blockTop - (hourBump * block * 0.18f).roundToInt())
        drawBlock(c, minuteLeft, blockTop - (minuteBump * block * 0.18f).roundToInt())

        // 6. The course: Piranha Plants (clipped by their pipe), pipes, enemies, hazards and blocks
        for (o in obstacles) {
            val x = ((o.x - worldDistance) / unit).roundToInt()
            val rise = plantRise(o)
            if (rise > 0f) {
                c.save()
                c.clipRect(0, 0, bw, ground - S.PIPE.size)
                drawGrid(c, S.PIRANHA, x + 1, ground - S.PIPE.size - (S.PIRANHA.size * rise).roundToInt(), false)
                c.restore()
            }
            val grid = if (o.squashedAt >= 0) S.GOOMBA_FLAT else o.kind.grid
            val top = ground - grid.size
            when {
                o.defeatedAt >= 0 -> {
                    val t = (animTime - o.defeatedAt).toFloat()
                    drawGrid(c, grid, x, top - 6 + (t * t * 0.00005f).roundToInt(), false, flipV = true)
                }
                donutFalling(o) -> drawGrid(c, grid, x, top + ((animTime - o.touchedAt - 500) * 0.04f).roundToInt(), false)
                o.kind == Kind.DONUT && o.touchedAt >= 0 -> drawGrid(c, grid, x + ((animTime / 50) % 2).toInt(), top, false)
                o.kind == Kind.NOTE && animTime - o.touchedAt < 150 -> drawGrid(c, grid, x, top + 1, false)
                o.kind == Kind.GOOMBA && o.squashedAt < 0 -> drawGrid(c, grid, x, top, (animTime / 200) % 2 == 0L)
                else -> drawGrid(c, grid, x, top, false)
            }
        }

        // 7. Fireballs
        for (f in fireballs) {
            drawGrid(c, S.FIREBALL, f.x.roundToInt() - 2, ground - f.y.roundToInt() - S.FIREBALL.size,
                (animTime / 100) % 2 == 0L)
        }

        // 8. Mario walking, jumping, leaping into a block, or growing / shrinking
        val change = changeFrom
        val big = if (change != null) {
            if ((animTime - changeAt) / 100 % 2 == 0L) change != Power.SMALL else power != Power.SMALL
        } else power != Power.SMALL
        val height = if (big) 32 else 16
        val maxRise = (ground - height - (blockTop + block)) * unit * 0.95f
        val rise = if (inAir) maxOf(feet, marioY * maxRise) else feet
        val airborne = inAir || vy != 0f
        val step = ((stepDistance / (4 * unit)).toInt()) % S.WALK_CYCLE.size
        val frame = when {
            big && airborne -> S.BIG_JUMP
            big && waiting -> S.BIG_STAND
            big -> S.BIG_WALK_CYCLE[step]
            airborne -> S.JUMP
            waiting -> S.STAND
            else -> S.WALK_CYCLE[step]
        }
        val swap = when {
            starred -> S.STAR_SWAPS[((animTime / 70) % S.STAR_SWAPS.size).toInt()]
            power == Power.FIRE && big -> S.FIRE_SWAP
            else -> null
        }
        val top = ground - height - (rise / unit).roundToInt()
        val flashing = animTime < safeUntil && (animTime / 60) % 2 == 0L
        if (!flashing) drawGrid(c, frame, (marioX / unit).roundToInt() - 8, top, !facingRight, swap = swap)

        // 9. "1UP" rising from Mario's head
        if (oneUpAt >= 0 && animTime - oneUpAt < 1_200) {
            c.drawText("1UP", marioX / unit, top - 2 - (animTime - oneUpAt) / 100f, popup)
        }
    }

    /** Draws a pixel-art [grid] with its top-left at buffer pixel ([left], [top]), optionally mirrored, flipped or recoloured. */
    private fun drawGrid(c: Canvas, grid: Array<String>, left: Int, top: Int, mirror: Boolean,
                         flipV: Boolean = false, swap: Map<Char, Int>? = null) {
        for ((row, line) in grid.withIndex()) {
            for ((col, ch) in line.withIndex()) {
                paint.color = swap?.get(ch) ?: S.PALETTE[ch] ?: continue
                val x = left + if (mirror) line.length - 1 - col else col
                val y = top + if (flipV) grid.size - 1 - row else row
                c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, r: Float) {
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, r, paint)
        canvas.drawCircle(x + r, y - r * 0.4f, r * 1.1f, paint)
        canvas.drawCircle(x + r * 2, y, r, paint)
        canvas.drawRect(x, y, x + r * 2, y + r, paint)
    }

    private fun drawHill(canvas: Canvas, x: Float, ground: Float, width: Float, height: Float) {
        paint.color = HILL
        rect.set(x, ground - height, x + width, ground + height)
        canvas.drawArc(rect, 180f, 180f, true, paint)
        paint.color = HILL_DARK
        val r = height * 0.08f
        canvas.drawCircle(x + width * 0.35f, ground - height * 0.45f, r, paint)
        canvas.drawCircle(x + width * 0.65f, ground - height * 0.25f, r, paint)
    }

    private fun drawBush(canvas: Canvas, x: Float, ground: Float, r: Float) {
        paint.color = BUSH
        for (k in 0 until 3) {
            canvas.drawCircle(x + k * r * 1.2f, ground - r * 0.3f, r, paint)
        }
    }

    /** Brick rows in buffer pixels: 1-pixel highlight and mortar, so the pattern stays crisp. */
    private fun drawGround(canvas: Canvas) {
        val size = brick.toFloat()
        val groundOffset = ((worldDistance / unit) % size).roundToInt().toFloat()
        var row = 0
        var y = ground.toFloat()
        while (y < bh) {
            val rowShift = if (row % 2 == 0) 0f else -(brick / 2).toFloat()
            var x = -size + rowShift - groundOffset
            while (x < bw + size) {
                paint.color = BRICK
                canvas.drawRect(x, y, x + size, y + size, paint)
                paint.color = BRICK_LIGHT
                canvas.drawRect(x, y, x + size, y + 1, paint)
                paint.color = Color.BLACK
                canvas.drawRect(x + size - 1, y, x + size, y + size, paint)
                canvas.drawRect(x, y + size - 1, x + size, y + size, paint)
                x += size
            }
            y += size; row++
        }
    }

    private fun drawBlock(canvas: Canvas, left: Int, top: Int) {
        val l = left.toFloat(); val t = top.toFloat(); val s = block.toFloat()
        paint.color = Color.BLACK
        canvas.drawRect(l - 1, t - 1, l + s + 1, t + s + 1, paint)
        paint.color = BLOCK_GOLD
        canvas.drawRect(l, t, l + s, t + s, paint)
        paint.color = BLOCK_LIGHT
        canvas.drawRect(l, t, l + s, t + 1, paint)
        canvas.drawRect(l, t, l + 1, t + s, paint)
        paint.color = BLOCK_DARK
        val rivet = maxOf(1, (block * 0.07f).roundToInt())
        val inset = (block * 0.12f).roundToInt()
        for ((rx, ry) in listOf(inset to inset, block - inset - rivet to inset, inset to block - inset - rivet,
            block - inset - rivet to block - inset - rivet)) {
            canvas.drawRect(l + rx, t + ry, l + rx + rivet, t + ry + rivet, paint)
        }
    }

    /** Dark grey digits centred on a ? block, at full resolution, moving with the block's bump. */
    private fun drawDigits(canvas: Canvas, left: Int, bump: Float, value: String) {
        val size = block * unit
        val l = left * unit
        val top = (blockTop - (bump * block * 0.18f).roundToInt()) * unit
        text.textSize = size * 0.36f
        shadow.textSize = text.textSize
        val baseline = top + size / 2 + text.textSize / 2
        canvas.drawText(value, l + size / 2 + size * 0.03f, baseline + size * 0.03f, shadow)
        canvas.drawText(value, l + size / 2, baseline, text)
    }

    private companion object {
        val SKY = Color.rgb(0x5C, 0x94, 0xFC)
        val HILL = Color.rgb(0x00, 0xA8, 0x00)
        val HILL_DARK = Color.rgb(0x00, 0x60, 0x00)
        val BUSH = Color.rgb(0x80, 0xD0, 0x10)
        val BRICK = Color.rgb(0xC8, 0x4C, 0x0C)
        val BRICK_LIGHT = Color.rgb(0xFC, 0x98, 0x38)
        val BLOCK_GOLD = Color.rgb(0xF8, 0xB8, 0x00)
        val BLOCK_LIGHT = Color.rgb(0xFC, 0xE0, 0xA8)
        val BLOCK_DARK = Color.rgb(0x88, 0x40, 0x00)

        const val REVERT_MS = 5_000L // idle time after the last tap before the real time is punched back
        const val WALK_SPEED = 0.2f // dp per ms
        const val GOOMBA_SPEED = 0.03f // dp per ms, Goombas and Koopas
        const val SHELL_SPEED = 0.25f // dp per ms
        const val FIREBALL_SPEED = 0.12f // buffer pixels per ms
        const val GRAVITY = 2.6e-4f // Mario pixels per ms², scaled by the unit
        const val JUMP_HEIGHT = 10f // Mario pixels
        const val PLANT_CYCLE_MS = 4_000L
        const val CHANGE_MS = 800L // grow / shrink flicker
        const val POWER_MS = 3 * 60_000L // each power-up level lasts this long
        const val STAR_MS = 8_000L
    }
}
