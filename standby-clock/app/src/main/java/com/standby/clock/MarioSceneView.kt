package com.standby.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
    private var inAir = false // a block punch owns Mario: its jump is stepped by the punch animator
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
    private var holdUntil = 0L // while rising before this, "A" is held and gravity is lighter: the longer, the higher
    private var velX = 0f // Mario's own speed along the screen, px/ms: he accelerates and skids instead of snapping
    private var scrollVel = 0f // the world's speed, px/ms: it builds up on the walk and slides to a stop
    private var scrollTarget = 0f
    private var hardMode = false // second quest, unlocked by clearing a level: Buzzy Beetles for Goombas, faster enemies
    private var dyingAt = -1L // small Mario was hit: he freezes, hops up and falls off the screen
    private var deathLaunched = false
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
        BUZZY(S.BUZZY, false, true, 1), // fireproof; every Goomba is one in the second quest
        BUZZY_SHELL(S.BUZZY_SHELL, false, false, 0),
        HARD(S.HARD_BLOCK, true, false, 1),
        BRICK(S.BRICK_BLOCK, true, false, 1),
    }

    private class Obstacle(var x: Float, var kind: Kind) {
        var squashedAt = -1L // Goomba stomped flat
        var defeatedAt = -1L // hit by a fireball, the star or a shell: flips over and falls
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

    private var currentAnim: Animator? = null

    // The level ends when the phone stops charging (full, or capped by the phone): Mario jumps at the flagpole, slides
    // down it, walks to the castle and goes in, the castle flag rises and fireworks go off.
    private enum class Finale { NONE, APPROACH, JUMP, SLIDE, WALK, ENTER, DONE }
    private var finale = Finale.NONE
    private var finaleAt = 0L
    private var poleWorld = 0f // the flagpole's x in world pixels (same space as an obstacle's x)
    private var flagLow = 0f // 0 = flag at the top of the pole, 1 = at the bottom
    private var castleFlag = 0f // 0 = victory flag down, 1 = raised
    private var marioAlpha = 255
    private var instantDone = false
    private var fireworks = 0 // bursts: the clock's last minute digit if it is 1, 3 or 6, as on the game's timer
    private var fireworksAt = 0L

    /** True once the phone has stopped charging; the clock sets it from the battery state. */
    var complete = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) resetFinale()
            else if (!isAttachedToWindow) instantDone = true // opened already full: show the finished level at once
        }

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
        // Small enough, with the blocks high enough, that 32-pixel Super Mario walks and jumps under the ? blocks, with
        // room left to clear the game's 16-pixel blocks and pipes.
        unit = height * 0.14f / 16
        bw = ceil(width / unit).toInt()
        bh = ceil(height / unit).toInt()
        if (buffer?.let { it.width != bw || it.height != bh } != false) {
            buffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bufferCanvas.setBitmap(it) }
        }
        ground = (bh * if (plugged) 0.82f else 0.84f).roundToInt()
        brick = ceil((bh - ground) / 2f).toInt()
        block = minOf(bh * 0.32f, bw * 0.24f).roundToInt()
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
                "com.standby.clock.TEST_MARIO_FINISH" -> complete = !complete
                else -> update(animate = true)
            }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The adb test broadcasts come from the shell uid, so only debug builds listen for them (and export the
        // receiver to do so); release builds take the system's time broadcasts only.
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            if (debug) {
                addAction("com.standby.clock.TEST_MARIO_HOUR")
                addAction("com.standby.clock.TEST_MARIO_MINUTE")
                addAction("com.standby.clock.TEST_MARIO_BOTH")
                addAction("com.standby.clock.TEST_MARIO_FINISH")
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(ticker, filter, if (debug) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(ticker, filter)
        }
        update(animate = false)
        // The frame loop is started by onVisibilityAggregated, so a hidden scene (Pac-Man theme) never runs it.
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

    /**
     * Piranha Plant height out of its pipe, 0..1: up 1.5 s, down 2 s, half a second each way. It never comes out while
     * Mario stands next to the pipe or on its rim.
     */
    private fun plantRise(o: Obstacle): Float {
        if (o.kind != Kind.PIRANHA || o.plantGone) return 0f
        if (abs(o.x - worldDistance + o.w * unit / 2 - marioX) < (o.w / 2 + PLANT_SHY) * unit) return 0f
        val t = (animTime + o.phase) % PLANT_CYCLE_MS
        return when {
            t < 500 -> t / 500f
            t < 2000 -> 1f
            t < 2500 -> 1 - (t - 2000) / 500f
            else -> 0f
        }
    }

    private fun isShell(o: Obstacle) = o.kind == Kind.SHELL || o.kind == Kind.BUZZY_SHELL

    private fun walkSpeed(o: Obstacle): Float = when {
        o.squashedAt >= 0 || o.defeatedAt >= 0 -> 0f
        isShell(o) -> SHELL_SPEED * dp
        o.kind.enemy -> GOOMBA_SPEED * dp * if (hardMode) HARD_SPEEDUP else 1f
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
        if (instantDone && width > 0) { instantDone = false; skipToDone() }
        // The world builds up speed on the walk and slides to a stop (friction) when Mario halts. The finale sets it outright.
        scrollTarget = when (finale) {
            Finale.SLIDE, Finale.ENTER, Finale.DONE -> 0f
            Finale.JUMP, Finale.WALK -> WALK_SPEED * dp
            else -> if (currentAnim == null && !inAir && !waiting && !changing && dyingAt < 0) WALK_SPEED * dp else 0f
        }
        scrollVel = if (finale.ordinal >= Finale.JUMP.ordinal) scrollTarget else when {
            scrollVel < scrollTarget -> minOf(scrollTarget, scrollVel + ACCEL * dp * dt)
            else -> maxOf(scrollTarget, scrollVel - FRICTION * dp * dt)
        }
        val scroll = scrollVel * dt
        worldDistance += scroll
        val marioVel = (marioX - lastMarioX) / dt
        stepDistance += scroll + abs(marioX - lastMarioX)
        lastMarioX = marioX

        if (nextSpawn == 0f) nextSpawn = worldDistance + width * 0.8f
        if (finale == Finale.NONE && nextSpawn - worldDistance < width + 16 * u) {
            val kinds = Kind.entries.filter { it.weight > 0 }
            var r = Random.nextInt(kinds.sumOf { it.weight })
            val kind = kinds.first { r -= it.weight; r < 0 }
            obstacles += Obstacle(nextSpawn, if (hardMode && kind == Kind.GOOMBA) Kind.BUZZY else kind)
            nextSpawn += width * (0.45f + 0.45f * Random.nextFloat())
        }
        for (o in obstacles) o.x -= dt * walkSpeed(o)
        // A kicked shell wipes out every enemy it runs into.
        for (s in obstacles) if (isShell(s)) for (e in obstacles) {
            if (e.kind.enemy && e.squashedAt < 0 && e.defeatedAt < 0 && s.x < e.x + e.w * u && s.x + s.w * u > e.x) e.defeatedAt = animTime
        }
        obstacles.removeAll {
            it.x - worldDistance + it.w * u < -16 * u ||
                it.squashedAt >= 0 && animTime - it.squashedAt > 500 ||
                it.defeatedAt >= 0 && animTime - it.defeatedAt > 700
        }
        stepItems(dt)
        stepFireballs(dt)
        // Power wears off one level at a time: Fire → Super → small.
        if (power != Power.SMALL && animTime - powerSince > POWER_MS) setPower(if (power == Power.FIRE) Power.BIG else Power.SMALL)
        if (complete && finale == Finale.NONE && currentAnim == null && punching == null && punches.isEmpty() &&
            !revertPending && !inAir && vy == 0f && dyingAt < 0) startFinale()
        if (finale.ordinal >= Finale.JUMP.ordinal) { stepFinale(); return }
        if (dyingAt >= 0) { stepDeath(dt); return }
        if (inAir || changing) return // the block punch, or the grow/shrink, owns Mario for now

        val left = marioX - 5 * u
        val right = marioX + 5 * u
        fun sx(o: Obstacle) = o.x - worldDistance
        fun over(o: Obstacle) = sx(o) < right && sx(o) + o.w * u > left
        val base = obstacles.filter { it.kind.solid && it.defeatedAt < 0 && over(it) }
            .maxByOrNull { it.kind.grid.size }
        val support = (base?.kind?.grid?.size ?: 0) * u
        if (vy != 0f || feet > support) {
            // Holding "A" while rising lightens gravity, so a longer press is a higher jump; falling is always heavy.
            vy -= GRAVITY * u * dt * if (vy > 0f && animTime < holdUntil) HOLD_GRAVITY else 1f
            feet += vy * dt
            if (feet <= support && vy < 0) {
                feet = support
                vy = 0f
            }
        }

        // Contact: enemies are stomped (or knocked out by the star), Piranha Plants hurt.
        for (o in obstacles) {
            if (o.squashedAt >= 0 || o.defeatedAt >= 0 || !over(o)) continue
            when {
                o.kind.enemy && feet < o.kind.grid.size * u -> when {
                    starred -> o.defeatedAt = animTime
                    vy < 0f -> stomp(o)
                    else -> hurt()
                }
                plantRise(o) > 0.3f && feet <= (S.PIPE.size + S.PIRANHA.size * plantRise(o)) * u ->
                    if (starred) o.plantGone = true else hurt()
            }
        }
        if (dyingAt >= 0) return
        if (vy != 0f) return
        feet = support
        // The flagpole is near enough that one jump from here ends with Mario's hands on it.
        if (finale == Finale.APPROACH && support == 0f &&
            poleWorld - worldDistance - marioX <= WALK_SPEED * dp * JUMP_MS + REACH * u) beginJump()

        // Fire Mario throws a fireball at the next enemy or Piranha Plant ahead.
        val dir = if (facingRight) 1f else -1f
        if (power == Power.FIRE && animTime - lastFire > 700 && fireballs.size < 2 && obstacles.any {
                (it.kind.enemy && it.kind != Kind.BUZZY && it.squashedAt < 0 && it.defeatedAt < 0 || plantRise(it) > 0.3f) &&
                    dir * (sx(it) - marioX) in 0f..60 * u
            }) {
            lastFire = animTime
            fireballs += Fireball(marioX / u + dir * 6, feet / u + marioH * 0.6f, dir * FIREBALL_SPEED)
        }

        waiting = false
        for (o in obstacles) {
            if (o.squashedAt >= 0 || o.defeatedAt >= 0 || isShell(o)) continue
            if (starred && o.kind.enemy) continue // plough straight through
            val dist = dir * (sx(o) + o.w * u / 2 - marioX)
            if (dist <= 0f) continue
            val edge = dist - (o.w / 2f + 5) * u
            // Piranha Plant out of its pipe: stop in front of it until it ducks back in. Once Mario is close enough the
            // plant stays down for good, so he can walk up to the pipe and jump it.
            if (plantRise(o) > 0f && edge < PLANT_WAIT * u && !starred) {
                waiting = currentAnim == null
                break
            }
            val closing = dir * (marioVel + scrollVel + walkSpeed(o))
            if (closing <= 0f) continue
            val height = o.kind.grid.size + 4f
            val plan = planJump(height)
            val go = if (o.kind.enemy) dist <= closing * plan.airMs * 0.9f else edge <= closing * dt * 2 + u
            if (go) {
                launch(plan)
                break
            }
        }
    }

    /** A jump worked out for a given apex: take-off speed, how long "A" is held, and the time in the air. */
    private class Jump(val v0: Float, val holdMs: Float, val airMs: Float)

    /** Height reached rising for [holdMs] under the light gravity, then under the heavy. */
    private fun apex(v0: Float, holdMs: Float, gHold: Float, gFall: Float): Float {
        val t = minOf(holdMs, v0 / gHold)
        val v1 = v0 - gHold * t
        return v0 * t - 0.5f * gHold * t * t + v1 * v1 / (2 * gFall)
    }

    /**
     * Plans a jump [height] Mario pixels high from where his feet are. Mario always takes off at the speed that carries
     * a fully held jump to the highest point he has room for (his head must not rise into the ? blocks); how long the
     * button is held then sets the apex, between half that height and all of it. Lower than half is a tap: no hold.
     * With [limit] off the apex is exactly [height] (the block punch, which wants to reach the block).
     */
    private fun planJump(height: Float, limit: Boolean = true): Jump {
        val room = (ground - marioH - blockTop - block - 1) - feet / unit
        val full = if (limit) room.coerceAtLeast(2f) else height
        val want = if (limit) minOf(height, room).coerceAtLeast(1f) else height
        val gFall = GRAVITY * unit
        val gHold = gFall * HOLD_GRAVITY
        if (want <= full / 2) {
            val v0 = sqrt(2 * gFall * want * unit)
            return Jump(v0, 0f, 2 * v0 / gFall)
        }
        val v0 = sqrt(2 * gHold * full * unit)
        var lo = 0f
        var hi = v0 / gHold
        repeat(18) {
            val mid = (lo + hi) / 2
            if (apex(v0, mid, gHold, gFall) < want * unit) lo = mid else hi = mid
        }
        val hold = (lo + hi) / 2
        val top = apex(v0, hold, gHold, gFall)
        val rise = minOf(hold, v0 / gHold) + (v0 - gHold * minOf(hold, v0 / gHold)) / gFall
        return Jump(v0, hold, rise + sqrt(2 * top / gFall))
    }

    private fun launch(plan: Jump) {
        vy = plan.v0
        holdUntil = animTime + plan.holdMs.toLong()
    }

    /** Small Mario's death: he freezes, hops up, falls off the screen and comes back at the checkpoint. */
    private fun stepDeath(dt: Float) {
        val t = animTime - dyingAt
        feet += vy * dt
        if (t < DEATH_FREEZE_MS) return
        if (!deathLaunched) {
            deathLaunched = true
            val plan = planJump(DEATH_HOP, limit = false)
            launch(plan)
        }
        vy -= GRAVITY * unit * dt * if (vy > 0f && animTime < holdUntil) HOLD_GRAVITY else 1f
        if (feet < -(bh - ground + 20) * unit) respawn()
    }

    /** Pole height in buffer pixels: a little above the ? blocks' underside, so Mario's grab stays between them. */
    private val poleH get() = ground - blockTop - block + 8

    /** Mario's grab height on the pole: his hands just under its top. */
    private fun grabFeet() = (poleH - 4 - marioH) * unit

    private fun startFinale() {
        val clear = obstacles.maxOfOrNull { it.x + it.w * unit } ?: worldDistance
        // Past the last obstacle, and off the right edge so the pole scrolls in.
        poleWorld = maxOf(worldDistance + width + 8 * unit, clear + 60 * unit)
        finale = Finale.APPROACH
        finaleAt = animTime
        setFireworks()
    }

    /** As in the game: fireworks only if the last digit of the clock is 1, 3 or 6, that many bursts. */
    private fun setFireworks() {
        val digit = Calendar.getInstance()[Calendar.MINUTE] % 10
        fireworks = if (digit == 1 || digit == 3 || digit == 6) digit else 0
    }

    private fun beginJump() {
        finale = Finale.JUMP
        finaleAt = animTime
        facingRight = true
        waiting = false
        vy = 0f
    }

    private fun stepFinale() {
        val t = (animTime - finaleAt).toFloat()
        when (finale) {
            Finale.JUMP -> {
                val p = (t / JUMP_MS).coerceAtMost(1f)
                feet = grabFeet() * (1 - (1 - p) * (1 - p)) // rises fast, easing into the grab
                if (p >= 1f) {
                    // Hands on the pole, whatever the frame timing did to the approach.
                    worldDistance = poleWorld - marioX - REACH * unit
                    finale = Finale.SLIDE
                    finaleAt = animTime
                }
            }
            Finale.SLIDE -> {
                val p = (t / SLIDE_MS).coerceAtMost(1f)
                feet = grabFeet() * (1 - p)
                flagLow = p
                if (t >= SLIDE_MS + 350) { finale = Finale.WALK; finaleAt = animTime; feet = 0f }
            }
            Finale.WALK -> {
                feet = 0f
                if (worldDistance + marioX >= doorWorld()) { finale = Finale.ENTER; finaleAt = animTime }
            }
            Finale.ENTER -> {
                marioAlpha = (255 * (1 - t / ENTER_MS)).toInt().coerceIn(0, 255)
                if (t >= ENTER_MS) { finale = Finale.DONE; finaleAt = animTime; fireworksAt = animTime; hardMode = true }
            }
            Finale.DONE -> castleFlag = (t / 1_200f).coerceAtMost(1f)
            else -> Unit
        }
    }

    private fun doorWorld() = poleWorld + CASTLE_GAP * unit

    /** Opened with the phone already finished charging: the level is over, Mario is in the castle. */
    private fun skipToDone() {
        obstacles.clear()
        items.clear()
        fireballs.clear()
        marioX = width / 2f
        lastMarioX = marioX
        poleWorld = worldDistance + marioX - CASTLE_GAP * unit
        flagLow = 1f
        castleFlag = 1f
        marioAlpha = 0
        feet = 0f
        vy = 0f
        finale = Finale.DONE
        finaleAt = animTime - 1_200
        fireworksAt = animTime
        hardMode = true
        setFireworks()
    }

    /** Charging resumed or the charger came out: a new level starts from the middle of the screen. */
    private fun resetFinale() {
        finale = Finale.NONE
        flagLow = 0f
        castleFlag = 0f
        marioAlpha = 255
        instantDone = false
        obstacles.clear()
        nextSpawn = 0f
        marioX = width / 2f
        lastMarioX = marioX
        feet = 0f
        vy = 0f
        waiting = false
        facingRight = true
    }

    /** A stomp squashes a Goomba, turns a Koopa or Buzzy Beetle into its shell, and bounces Mario off it. */
    private fun stomp(o: Obstacle) {
        when (o.kind) {
            Kind.KOOPA -> o.kind = Kind.SHELL
            Kind.BUZZY -> o.kind = Kind.BUZZY_SHELL
            else -> o.squashedAt = animTime
        }
        launch(planJump(STOMP_BOUNCE))
    }

    /**
     * One hit shrinks Super or Fire Mario to small, with a couple of seconds of flashing safety; small Mario loses a
     * life.
     */
    private fun hurt() {
        if (animTime < safeUntil) return
        if (power == Power.SMALL) { die(); return }
        safeUntil = animTime + 2_000
        setPower(Power.SMALL)
        launch(planJump(6f))
    }

    private fun die() {
        dyingAt = animTime
        deathLaunched = false
        vy = 0f
        holdUntil = 0
        waiting = false
        currentAnim?.cancel()
        currentAnim = null
        // The punch he was on his way to makes starts again from the checkpoint.
        punching?.let { punches.addFirst(it) }
        punching = null
    }

    /** Back at the checkpoint: the middle of the screen, small, with the enemies nearby cleared away. */
    private fun respawn() {
        dyingAt = -1
        marioX = width / 2f
        lastMarioX = marioX
        feet = 0f
        vy = 0f
        scrollVel = 0f
        velX = 0f
        facingRight = true
        safeUntil = animTime + 2_500
        obstacles.removeAll { (it.x - worldDistance) in marioX - 60 * unit..marioX + 90 * unit }
        startPunches()
    }

    private fun setPower(next: Power) {
        if (next == power) return
        changeFrom = power
        changeAt = animTime
        power = next
        powerSince = animTime
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
                o.squashedAt < 0 && o.defeatedAt < 0 && !isShell(o) && f.x in left..left + o.w &&
                    f.y < o.kind.grid.size + S.PIRANHA.size * plantRise(o)
            }
            when {
                hit != null -> {
                    // A Buzzy Beetle shrugs fireballs off.
                    if (hit.kind.enemy && hit.kind != Kind.BUZZY) hit.defeatedAt = animTime
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
        if (!animate || !isShown || width == 0 || finale != Finale.NONE) {
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
        if (finale != Finale.NONE) return // the level is ending: the blocks just show the time
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
        currentAnim = walkTo(x) {
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
        currentAnim = walkTo(width / 2f) {
            facingRight = true
            currentAnim = null
        }.also { it.start() }
    }

    /**
     * Walks Mario to [destX] with momentum: he speeds up from rest, runs if it is far, brakes to stop on the spot and
     * skids when he has to turn round. The animator is only the frame clock; it ends when he has arrived.
     */
    private fun walkTo(destX: Float, onDone: () -> Unit): ValueAnimator {
        var last = animTime
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10_000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val dt = (animTime - last).toFloat()
                if (dt <= 0f) return@addUpdateListener
                last = animTime
                val dist = abs(destX - marioX)
                val dir = if (destX >= marioX) 1f else -1f
                facingRight = dir > 0
                val walk = WALK_SPEED * dp
                val top = walk * if (dist > RUN_DISTANCE * unit) RUN_FACTOR else 1f
                val brake = velX * velX / (2 * FRICTION * dp)
                velX = when {
                    velX * dir < 0f -> velX + dir * SKID * dp * dt // turning round: skid to a stop first
                    velX * dir > 0f && dist <= brake -> velX - dir * FRICTION * dp * dt // slide to a halt on the spot
                    else -> (velX + dir * ACCEL * dp * dt).coerceIn(-top, top)
                }
                marioX += velX * dt
                val arrived = dist < unit && abs(velX) < walk * 0.25f || (destX - marioX) * dir < 0f
                if (arrived) {
                    marioX = destX
                    velX = 0f
                    anim.end()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true; velX = 0f }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return // a new punch took over from where Mario stands
                    marioX = destX
                    velX = 0f
                    onDone()
                }
            })
        }
    }

    /**
     * A full-height jump from under a block: Mario holds "A" until his head meets the block, which bumps (and pops an
     * item and the new digit), stops his rise and drops him back down.
     */
    private fun jumpAndPunch(targetIsHour: Boolean, newValue: String, onDone: () -> Unit): ValueAnimator {
        inAir = true
        facingRight = true
        val reach = (ground - blockTop - block - marioH).toFloat() // feet height at which his head meets the block
        var last = animTime
        var hitAt = -1L
        launch(planJump(reach + 3, limit = false))
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4_000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val dt = (animTime - last).toFloat()
                if (dt <= 0f) return@addUpdateListener
                last = animTime
                vy -= GRAVITY * unit * dt * if (vy > 0f && animTime < holdUntil) HOLD_GRAVITY else 1f
                feet += vy * dt
                if (hitAt < 0 && feet >= reach * unit) {
                    hitAt = animTime
                    feet = reach * unit
                    vy = -BONK * unit
                    holdUntil = 0
                    if (targetIsHour) shownHours = newValue else shownMinutes = newValue
                    spawnItem(targetIsHour)
                }
                if (hitAt >= 0) {
                    val bump = sin(Math.PI * ((animTime - hitAt) / BUMP_MS).coerceAtMost(1f)).toFloat()
                    if (targetIsHour) hourBump = bump else minuteBump = bump
                }
                if (feet <= 0f && vy < 0f) {
                    feet = 0f
                    vy = 0f
                    anim.end()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    inAir = false
                    vy = 0f
                    feet = maxOf(feet, 0f)
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

    // Only the ? blocks take touches; anywhere else falls through to the clock behind, which hides the card.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && blockAt(event.x, event.y) == null) return false
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
                drawGrid(c, S.PIRANHA, x + (o.w - S.PIRANHA[0].length) / 2, ground - S.PIPE.size - (S.PIRANHA.size * rise).roundToInt(), false)
                c.restore()
            }
            val grid = if (o.squashedAt >= 0) S.GOOMBA_FLAT else o.kind.grid
            val top = ground - grid.size
            when {
                o.defeatedAt >= 0 -> {
                    val t = (animTime - o.defeatedAt).toFloat()
                    drawGrid(c, grid, x, top - 6 + (t * t * 0.00005f).roundToInt(), false, flipV = true)
                }
                (o.kind == Kind.GOOMBA || o.kind == Kind.BUZZY) && o.squashedAt < 0 ->
                    drawGrid(c, grid, x, top, (animTime / 200) % 2 == 0L)
                else -> drawGrid(c, grid, x, top, false)
            }
        }

        if (finale != Finale.NONE) drawFinale(c)

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
        val rise = feet
        val dying = dyingAt >= 0
        val airborne = inAir || vy != 0f || dying || finale == Finale.JUMP || finale == Finale.SLIDE
        // Braking from a walk (or turning round) he plants his feet and slides, in the standing pose.
        val skidding = !airborne && finale == Finale.NONE &&
            (scrollVel > WALK_SPEED * dp * 0.3f && scrollTarget == 0f || velX * (if (facingRight) 1f else -1f) < 0f)
        val step = ((stepDistance / (STEP_PX * unit)).toInt()) % S.WALK_CYCLE.size
        val frame = when {
            big && airborne -> S.BIG_JUMP
            big && (waiting || skidding) -> S.BIG_STAND
            big -> S.BIG_WALK_CYCLE[step]
            airborne -> S.JUMP
            waiting || skidding -> S.STAND
            else -> S.WALK_CYCLE[step]
        }
        val swap = when {
            starred -> S.STAR_SWAPS[((animTime / 70) % S.STAR_SWAPS.size).toInt()]
            power == Power.FIRE && big -> S.FIRE_SWAP
            else -> null
        }
        val top = ground - height - (rise / unit).roundToInt()
        val flashing = animTime < safeUntil && (animTime / 60) % 2 == 0L
        if (!flashing && marioAlpha > 0) {
            drawGrid(c, frame, (marioX / unit).roundToInt() - 8, top, !facingRight, swap = swap, alpha = marioAlpha)
        }

        // 9. "1UP" rising from Mario's head
        if (oneUpAt >= 0 && animTime - oneUpAt < 1_200) {
            c.drawText("1UP", marioX / unit, top - 2 - (animTime - oneUpAt) / 100f, popup)
        }
    }

    /** The flagpole with its flag, the castle, and (once Mario is in) fireworks over it. */
    private fun drawFinale(c: Canvas) {
        val g = ground
        val pole = ((poleWorld - worldDistance) / unit).roundToInt()
        val door = ((doorWorld() - worldDistance) / unit).roundToInt()
        val top = g - poleH
        if (pole in -12..bw + 12) {
            paint.color = S.PALETTE['G']!!
            c.drawRect(pole.toFloat(), top.toFloat(), pole + 2f, g.toFloat(), paint)
            paint.color = S.PALETTE['L']!!
            c.drawRect(pole - 1f, top - 3f, pole + 3f, top + 1f, paint) // the ball on top
            drawGrid(c, S.HARD_TILE, pole - 7, g - 16, false)
            // The flag hangs left of the pole and slides down it with Mario.
            val fy = top + 3 + ((poleH - 17) * flagLow).roundToInt()
            for (r in 0 until 7) {
                val w = 8 - r
                paint.color = Color.WHITE
                c.drawRect((pole - w).toFloat(), (fy + r).toFloat(), pole.toFloat(), fy + r + 1f, paint)
            }
            paint.color = S.PALETTE['G']!!
            c.drawRect(pole - 4f, fy + 2f, pole - 2f, fy + 4f, paint)
            // Grabbing the pole near its top is worth the most, as in the game: 5000, beside Mario as he slides.
            if (finale == Finale.SLIDE) c.drawText("5000", pole + 18f, g - feet / unit - marioH / 2f, popup)
        }
        if (door in -30..bw + 30) drawCastle(c, door)
        if (finale == Finale.DONE && fireworks > 0) drawFireworks(c, (animTime - fireworksAt) / 1000f)
    }

    private fun brickWall(c: Canvas, left: Int, top: Int, w: Int, h: Int) {
        paint.color = S.PALETTE['Q']!!
        c.drawRect(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat(), paint)
        paint.color = Color.BLACK
        var row = 0
        var y = top
        while (y < top + h) {
            c.drawRect(left.toFloat(), y.toFloat(), (left + w).toFloat(), y + 1f, paint)
            var x = left + if (row % 2 == 0) 0 else 4
            while (x < left + w) {
                if (x > left) c.drawRect(x.toFloat(), y.toFloat(), x + 1f, minOf(y + 4, top + h).toFloat(), paint)
                x += 8
            }
            y += 4
            row++
        }
    }

    /** The castle with its door on [cx]: a wall, a narrower tower, battlements, two windows and the victory flag. */
    private fun drawCastle(c: Canvas, cx: Int) {
        val g = ground
        brickWall(c, cx - 12, g - 44, 24, 18)
        brickWall(c, cx - 24, g - 26, 48, 26)
        paint.color = S.PALETTE['Q']!!
        // Battlements: the wall's, except where the tower stands, then the tower's.
        for (k in listOf(0, 1, 4, 5)) c.drawRect((cx - 24 + k * 8).toFloat(), g - 29f, (cx - 20 + k * 8).toFloat(), g - 26f, paint)
        for (k in 0 until 3) c.drawRect((cx - 12 + k * 8).toFloat(), g - 47f, (cx - 8 + k * 8).toFloat(), g - 44f, paint)
        paint.color = Color.BLACK
        c.drawRect(cx - 6f, g - 40f, cx - 3f, g - 35f, paint)
        c.drawRect(cx + 3f, g - 40f, cx + 6f, g - 35f, paint)
        c.drawRect(cx - 5f, g - 14f, cx + 5f, g.toFloat(), paint) // the door
        c.drawRect(cx - 4f, g - 16f, cx + 4f, g - 14f, paint)
        if (castleFlag > 0f) {
            val fy = (g - 48 - 8 * castleFlag).roundToInt()
            paint.color = Color.WHITE
            c.drawRect(cx.toFloat(), (g - 57).toFloat(), cx + 1f, (g - 47).toFloat(), paint)
            for (r in 0 until 5) c.drawRect((cx - 5 + r / 2).toFloat(), (fy + r).toFloat(), cx.toFloat(), fy + r + 1f, paint)
            paint.color = S.PALETTE['G']!!
            c.drawRect(cx - 3f, fy + 1f, cx - 1f, fy + 3f, paint)
        }
    }

    /** A burst every second, alternating sides, beyond the ? blocks so the time stays readable. */
    private fun drawFireworks(c: Canvas, seconds: Float) {
        val k = seconds.toInt()
        if (k >= fireworks) return
        val p = seconds - k
        val side = if (k % 2 == 0) -1 else 1
        val cx = bw / 2 + side * (block + block / 6 + 20 + (k * 7) % 11)
        val cy = ground - 52 - (k * 5) % 14
        val colors = intArrayOf(S.PALETTE['R']!!, Color.WHITE, S.PALETTE['O']!!, S.PALETTE['L']!!)
        val color = colors[k % colors.size]
        for (i in 0 until 16) {
            val inner = i % 2 == 1 // a second, smaller ring between the spokes
            val a = Math.toRadians(i * 22.5)
            val r = (3 + 11 * p) * if (inner) 0.55f else 1f
            paint.color = color
            paint.alpha = (255 * (1 - p * p)).toInt()
            val x = (cx + r * Math.cos(a)).roundToInt()
            val y = (cy + r * Math.sin(a)).roundToInt()
            val size = if (inner) 1f else 2f
            c.drawRect(x.toFloat(), y.toFloat(), x + size, y + size, paint)
        }
    }

    /** Draws a pixel-art [grid] with its top-left at buffer pixel ([left], [top]), optionally mirrored, flipped or recoloured. */
    private fun drawGrid(c: Canvas, grid: Array<String>, left: Int, top: Int, mirror: Boolean,
                         flipV: Boolean = false, swap: Map<Char, Int>? = null, alpha: Int = 255) {
        for ((row, line) in grid.withIndex()) {
            for ((col, ch) in line.withIndex()) {
                paint.color = swap?.get(ch) ?: S.PALETTE[ch] ?: continue
                if (alpha < 255) paint.alpha = alpha
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
        // The wiki's block colours (see the ? block, brick and hard block sprites), so the ground matches the obstacles.
        val BRICK = Color.rgb(0x99, 0x4E, 0x00)
        val BRICK_LIGHT = Color.rgb(0xFF, 0xCC, 0xC5)
        val BLOCK_GOLD = Color.rgb(0xEA, 0x9E, 0x22)
        val BLOCK_LIGHT = Color.rgb(0xFC, 0xE0, 0xA8)
        val BLOCK_DARK = Color.rgb(0x99, 0x4E, 0x00)

        const val REVERT_MS = 5_000L // idle time after the last tap before the real time is punched back
        const val WALK_SPEED = 0.2f // dp per ms
        const val RUN_FACTOR = 1.7f // holding B: Mario runs when the way is long
        const val RUN_DISTANCE = 70f // Mario pixels
        const val ACCEL = 9e-4f // dp per ms²: from rest to a walk in about 220 ms
        const val FRICTION = 12e-4f // dp per ms²: coasting to a stop
        const val SKID = 20e-4f // dp per ms²: braking against the direction he wants to go
        const val GOOMBA_SPEED = 0.03f // dp per ms, Goombas, Koopas and Buzzy Beetles
        const val HARD_SPEEDUP = 1.4f // second quest: certain enemies are faster
        const val SHELL_SPEED = 0.25f // dp per ms
        const val FIREBALL_SPEED = 0.12f // buffer pixels per ms
        const val GRAVITY = 2.6e-4f // Mario pixels per ms², scaled by the unit
        const val HOLD_GRAVITY = 0.5f // share of gravity left while "A" is held and Mario is still rising
        const val STOMP_BOUNCE = 12f // Mario pixels, bouncing off an enemy
        const val BONK = 0.02f // buffer pixels per ms: how hard a block knocks Mario back down
        const val BUMP_MS = 260f // a punched block's hop
        const val DEATH_FREEZE_MS = 400L
        const val DEATH_HOP = 20f // Mario pixels
        const val PLANT_SHY = 6f // Mario pixels from the pipe's edge inside which its plant stays down
        const val PLANT_WAIT = 14f // Mario pixels short of a plant that is out, where he stops
        const val STEP_PX = 6f // Mario pixels of ground per walk frame (the NES cycle), so a planted foot does not slide
        const val JUMP_MS = 550f // the leap at the flagpole
        const val SLIDE_MS = 1_100f
        const val ENTER_MS = 350f
        const val REACH = 7f // Mario pixels from his centre to the pole when he grabs it
        const val CASTLE_GAP = 58f // Mario pixels from the pole to the castle door
        const val PLANT_CYCLE_MS = 4_000L
        const val CHANGE_MS = 800L // grow / shrink flicker
        const val POWER_MS = 3 * 60_000L // each power-up level lasts this long
        const val STAR_MS = 8_000L
    }
}
