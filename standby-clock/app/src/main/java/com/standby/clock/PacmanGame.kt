package com.standby.clock

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** The original level-1 maze (28x31 tiles, 8 px each). Direction order is the original's tie-break order. */
object PacMaze {
    const val COLS = 28
    const val ROWS = 31
    const val UP = 0
    const val LEFT = 1
    const val DOWN = 2
    const val RIGHT = 3
    const val TUNNEL_ROW = 14
    val DX = intArrayOf(0, -1, 0, 1)
    val DY = intArrayOf(-1, 0, 1, 0)

    fun opposite(dir: Int) = dir xor 2
    fun idx(x: Int, y: Int) = y * COLS + x

    val TEXT = arrayOf(
        "############################",
        "#............##............#",
        "#.####.#####.##.#####.####.#",
        "#o####.#####.##.#####.####o#",
        "#.####.#####.##.#####.####.#",
        "#..........................#",
        "#.####.##.########.##.####.#",
        "#.####.##.########.##.####.#",
        "#......##....##....##......#",
        "######.##### ## #####.######",
        "     #.##### ## #####.#     ",
        "     #.##          ##.#     ",
        "     #.## ###--### ##.#     ",
        "######.## #      # ##.######",
        "      .   #      #   .      ",
        "######.## #      # ##.######",
        "     #.## ######## ##.#     ",
        "     #.##          ##.#     ",
        "     #.## ######## ##.#     ",
        "######.## ######## ##.######",
        "#............##............#",
        "#.####.#####.##.#####.####.#",
        "#.####.#####.##.#####.####.#",
        "#o..##.......  .......##..o#",
        "###.##.##.########.##.##.###",
        "###.##.##.########.##.##.###",
        "#......##....##....##......#",
        "#.##########.##.##########.#",
        "#.##########.##.##########.#",
        "#..........................#",
        "############################",
    )

    /** 0 none, 1 dot, 2 energizer: the full board. */
    val pellets0 = IntArray(COLS * ROWS)
    /** Tiles Pac-Man and the ghosts can walk (the ghost house and its door are not part of it). */
    val walkable = BooleanArray(COLS * ROWS)
    val totalPellets: Int

    init {
        var total = 0
        for (y in 0 until ROWS) for (x in 0 until COLS) {
            when (TEXT[y][x]) {
                '.' -> { pellets0[idx(x, y)] = 1; total++ }
                'o' -> { pellets0[idx(x, y)] = 2; total++ }
            }
        }
        totalPellets = total
        // Everything reachable from Pac-Man's start without crossing a wall or the door.
        val queue = IntArray(COLS * ROWS)
        var head = 0
        var tail = 0
        queue[tail++] = idx(13, 23)
        walkable[idx(13, 23)] = true
        while (head < tail) {
            val c = queue[head++]
            val cx = c % COLS
            val cy = c / COLS
            for (d in 0..3) {
                var nx = cx + DX[d]
                val ny = cy + DY[d]
                if (ny < 0 || ny >= ROWS) continue
                if (nx < 0 || nx >= COLS) {
                    if (cy != TUNNEL_ROW) continue
                    nx = (nx + COLS) % COLS
                }
                val ch = TEXT[ny][nx]
                if (ch == '#' || ch == '-' || walkable[idx(nx, ny)]) continue
                walkable[idx(nx, ny)] = true
                queue[tail++] = idx(nx, ny)
            }
        }
    }

    /** Tile index one step from (x, y) in [dir], wrapping through the tunnel, or -1 when it is not walkable. */
    fun neighbor(x: Int, y: Int, dir: Int): Int {
        var nx = x + DX[dir]
        val ny = y + DY[dir]
        if (ny < 0 || ny >= ROWS) return -1
        if (nx < 0 || nx >= COLS) {
            if (y != TUNNEL_ROW) return -1
            nx = (nx + COLS) % COLS
        }
        val i = idx(nx, ny)
        return if (walkable[i]) i else -1
    }
}

/**
 * The original arcade Pac-Man, level 1, played by an autopilot. Rules follow the gameplay reference: the four ghost
 * targeting formulas, up-left-down-right tie-break, scatter/chase waves, frightened mode, house release counters,
 * Cruise Elroy, the 10/50 dot pauses, fruit at 70 and 170 and the tunnel slowdown. Tile movement turns only at tile
 * centres (an approximation of the original's cornering) and the ghosts' random turns use a plain LCG, not the ROM's.
 *
 * The clock adaptation plays the full maze automatically while charging; every pellet is active.
 */
class PacmanGame {

    open class Actor {
        var px = 0f
        var py = 0f
        var dir = PacMaze.LEFT
        var stopped = false
    }

    class Ghost(val id: Int) : Actor() {
        var mode = IN_HOUSE
        var frightened = false
        var next = PacMaze.LEFT
        var reverse = false
        var bob = 0.5f
    }

    val pac = Actor()
    val ghosts = Array(4) { Ghost(it) }
    val pellets = IntArray(PacMaze.COLS * PacMaze.ROWS)

    var scene = READY
        private set
    var tick = 0
        private set
    var score = 0
        private set
    var highScore = 0
        private set
    var lives = START_LIVES
        private set
    var eaten = 0
        private set
    var frightTicks = 0
        private set
    var pacAnim = 0
        private set
    var pauseTicks = 0
        private set
    var fruitTicks = 0
        private set
    var popupText = ""
        private set
    var popupX = 0f
        private set
    var popupY = 0f
        private set
    var popupTicks = 0
        private set
    var eatenGhost: Ghost? = null
        private set
    private var sceneTicks = 0

    /** All pellets are available throughout the game. */
    var quota = 0
        private set
    private var complete = false
    private var level = 0
    private var started = false

    // Scatter/chase wave clock, release counters and the other level-1 state.
    private var waveIdx = 0
    private var waveTicks = WAVES[0]
    private var chasing = false
    private var chain = 0
    private var noDotTicks = 0
    private var globalMode = false
    private var globalCounter = 0
    private val personal = IntArray(4)
    private var elroySuppressed = false
    private var fruitSpawned1 = false
    private var fruitSpawned2 = false
    private var completeTicks = 0
    private var assist = false
    private var rng = 12345

    // Scratch space for the autopilot's searches.
    private val pacDist = IntArray(PacMaze.COLS * PacMaze.ROWS)
    private val ghostDist = IntArray(PacMaze.COLS * PacMaze.ROWS)
    private val first = IntArray(PacMaze.COLS * PacMaze.ROWS)
    private val order = IntArray(PacMaze.COLS * PacMaze.ROWS)
    private val sources = IntArray(4)
    private var wander = -1

    val flashWhite: Boolean
        get() = scene == CLEARING && sceneTicks >= CLEAR_HOLD &&
            ((sceneTicks - CLEAR_HOLD) / FLASH_TICKS) % 2 == 0 && sceneTicks < CLEAR_HOLD + 8 * FLASH_TICKS
    val ghostsHidden: Boolean
        get() = (scene == CLEARING && sceneTicks >= GHOST_HIDE) || scene == CLEARED ||
            (scene == DYING && sceneTicks >= DEATH_HOLD)
    val pacHidden: Boolean
        get() = scene == EAT_PAUSE || (scene == DYING && deathFrame >= 11)
    /** -1 while Pac-Man is still drawn normally, then 0..10 through the death animation. */
    val deathFrame: Int
        get() = if (scene != DYING || sceneTicks < DEATH_HOLD) -1 else (sceneTicks - DEATH_HOLD) / DEATH_STEP
    val ready: Boolean get() = scene == READY
    val gameOver: Boolean get() = scene == GAME_OVER
    val cleared: Boolean get() = scene == CLEARED || scene == CLEARING

    /** Battery level and whether charging has finished; the maze is cleared only when it has. */
    fun setCharge(percent: Int, finished: Boolean) {
        val wasComplete = complete
        level = percent.coerceIn(0, 100)
        complete = finished
        quota = PacMaze.totalPellets
        if (!started) { started = true; newGame(); return }
        if (wasComplete && !finished && (scene == CLEARED || scene == CLEARING)) newGame()
    }

    private fun newGame() {
        scene = READY
        System.arraycopy(PacMaze.pellets0, 0, pellets, 0, pellets.size)
        eaten = 0
        score = 0
        lives = START_LIVES
        fruitSpawned1 = false
        fruitSpawned2 = false
        fruitTicks = 0
        elroySuppressed = false
        globalMode = false
        globalCounter = 0
        personal.fill(0)
        completeTicks = 0
        assist = false
        wander = -1
        resetActors()
        startReady()
    }

    private fun startReady() {
        scene = READY
        sceneTicks = READY_TICKS
    }

    /** Eats the first [target] pellets with nothing else on the board, so a half-charged phone shows a half-eaten maze. */
    private fun fastForward(target: Int) {
        resetActors()
        for (g in ghosts) g.mode = IN_HOUSE
        var guard = 0
        while (eaten < target && guard++ < 60_000) {
            movePac()
            // This prefill only advances movement, not play()'s fruit collision handler.
            // A spawned cherry would otherwise keep the autopilot circling it forever.
            fruitTicks = 0
        }
        frightTicks = 0
        fruitTicks = 0
        pauseTicks = 0
        scene = READY
    }

    /** Everyone back to the start positions: the same each life and board. */
    private fun resetActors() {
        pac.px = 112f; pac.py = 188f; pac.dir = PacMaze.LEFT; pac.stopped = false
        pauseTicks = 0
        frightTicks = 0
        chain = 0
        waveIdx = 0
        waveTicks = WAVES[0]
        chasing = false
        noDotTicks = 0
        fruitTicks = 0
        popupTicks = 0
        eatenGhost = null
        for (g in ghosts) {
            g.frightened = false
            g.reverse = false
            g.stopped = false
            when (g.id) {
                0 -> { g.px = 112f; g.py = 92f; g.dir = PacMaze.LEFT; g.mode = ACTIVE }
                1 -> { g.px = 112f; g.py = 116f; g.dir = PacMaze.DOWN; g.mode = IN_HOUSE }
                2 -> { g.px = 96f; g.py = 116f; g.dir = PacMaze.UP; g.mode = IN_HOUSE }
                else -> { g.px = 128f; g.py = 116f; g.dir = PacMaze.UP; g.mode = IN_HOUSE }
            }
            g.bob = if (g.id == 1) 0.5f else -0.5f
        }
        plan(ghosts[0])
    }

    fun tick() {
        tick++
        if (popupTicks > 0) popupTicks--
        when (scene) {
            READY -> if (--sceneTicks <= 0) scene = PLAYING
            PLAYING -> play()
            EAT_PAUSE -> {
                for (g in ghosts) if (g.mode == EYES || g.mode == ENTERING) moveGhost(g)
                if (--sceneTicks <= 0) { scene = PLAYING; eatenGhost = null }
            }
            DYING -> dying()
            CLEARING -> clearing()
            CLEARED -> if (!complete) newGame()
            GAME_OVER -> if (--sceneTicks <= 0) newGame()
        }
    }

    private fun play() {
        if (frightTicks > 0) { if (--frightTicks == 0) for (g in ghosts) g.frightened = false } else advanceWave()
        noDotTicks++
        checkRelease()
        if (fruitTicks > 0) fruitTicks--
        if (complete && eaten < PacMaze.totalPellets && ++completeTicks > ASSIST_TICKS && !assist) sendGhostsHome()
        movePac()
        if (scene != PLAYING) return
        for (g in ghosts) moveGhost(g)
        if (fruitTicks > 0 && abs(pac.px - FRUIT_X) <= 6f && abs(pac.py - FRUIT_Y) <= 4f) eatFruit()
        collide()
    }

    // ---- scoring, pellets, fruit --------------------------------------------------------------------------

    private fun addScore(points: Int) {
        val before = score
        score += points
        if (score > highScore) highScore = score
        if (before < EXTRA_LIFE && score >= EXTRA_LIFE) lives++
    }

    private fun eatAt(tx: Int, ty: Int) {
        val i = PacMaze.idx(tx, ty)
        val kind = pellets[i]
        if (kind == 0 || eaten >= quota) return
        pellets[i] = 0
        eaten++
        addScore(if (kind == 2) 50 else 10)
        pauseTicks = if (kind == 2) 3 else 1
        if (kind == 2) startFrightened()
        onDotEaten()
        if (eaten == FRUIT_FIRST && !fruitSpawned1) { fruitSpawned1 = true; fruitTicks = FRUIT_TICKS }
        if (eaten == FRUIT_SECOND && !fruitSpawned2) { fruitSpawned2 = true; fruitTicks = FRUIT_TICKS }
        if (eaten >= PacMaze.totalPellets) { scene = CLEARING; sceneTicks = 0 }
    }

    private fun eatFruit() {
        fruitTicks = 0
        addScore(FRUIT_POINTS)
        popupText = FRUIT_POINTS.toString()
        popupX = FRUIT_X
        popupY = FRUIT_Y
        popupTicks = 120
    }

    private fun startFrightened() {
        frightTicks = FRIGHT_TICKS
        chain = 0
        for (g in ghosts) {
            if (g.mode == EYES || g.mode == ENTERING) continue
            g.frightened = true
            if (g.mode == ACTIVE) g.reverse = true
        }
    }

    // ---- ghost house ---------------------------------------------------------------------------------------

    private fun onDotEaten() {
        noDotTicks = 0
        if (globalMode) {
            globalCounter++
            when (globalCounter) {
                7 -> releaseIfWaiting(1)
                17 -> releaseIfWaiting(2)
                32 -> if (ghosts[3].mode == IN_HOUSE) { release(ghosts[3]); globalMode = false }
            }
        } else {
            ghosts.firstOrNull { it.id != 0 && it.mode == IN_HOUSE }?.let { personal[it.id]++ }
        }
    }

    private fun checkRelease() {
        if (assist) return
        val waiting = ghosts.firstOrNull { it.id != 0 && it.mode == IN_HOUSE } ?: return
        if (!globalMode && personal[waiting.id] >= PERSONAL_LIMIT[waiting.id]) release(waiting)
        else if (noDotTicks >= RELEASE_TIMEOUT) { release(waiting); noDotTicks = 0 }
    }

    private fun releaseIfWaiting(id: Int) { if (ghosts[id].mode == IN_HOUSE) release(ghosts[id]) }

    private fun release(g: Ghost) { g.mode = LEAVING }

    /** After a long stall the ghosts leave the maze so the last pellets can be eaten the moment charging ends. */
    private fun sendGhostsHome() {
        assist = true
        for (g in ghosts) {
            g.mode = IN_HOUSE
            g.frightened = false
            g.reverse = false
            g.px = if (g.id == 2) 96f else if (g.id == 3) 128f else 112f
            g.py = 116f
        }
    }

    // ---- waves ---------------------------------------------------------------------------------------------

    private fun advanceWave() {
        if (waveTicks < 0) return
        if (--waveTicks > 0) return
        waveIdx++
        chasing = waveIdx % 2 == 1
        waveTicks = if (waveIdx < WAVES.size) WAVES[waveIdx] else -1
        for (g in ghosts) if (g.mode == ACTIVE) g.reverse = true
    }

    // ---- Pac-Man -------------------------------------------------------------------------------------------

    private fun movePac() {
        if (pauseTicks > 0) { pauseTicks--; return }
        if (pac.stopped) pacArrive()
        if (pac.stopped) return
        val base = BASE_SPEED * (if (frightTicks > 0) 0.90f else 0.80f)
        pacAnim++
        travel(pac, base) { pacArrive() }
    }

    private fun pacArrive() {
        val tx = tileX(pac.px)
        val ty = tileY(pac.py)
        eatAt(tx, ty)
        if (scene != PLAYING && scene != READY) return
        pac.dir = autopilot(tx, ty)
        pac.stopped = PacMaze.neighbor(tx, ty, pac.dir) < 0
    }

    private fun collide() {
        val ptx = tileX(pac.px)
        val pty = tileY(pac.py)
        for (g in ghosts) {
            if (g.mode != ACTIVE) continue
            val close = (tileX(g.px) == ptx && tileY(g.py) == pty) || abs(g.px - pac.px) + abs(g.py - pac.py) < 4f
            if (!close) continue
            if (g.frightened) eatGhost(g) else die()
            return
        }
    }

    private fun eatGhost(g: Ghost) {
        val points = 200 shl chain.coerceAtMost(3)
        chain++
        addScore(points)
        g.mode = EYES
        g.frightened = false
        g.reverse = false
        plan(g)
        eatenGhost = g
        popupText = points.toString()
        popupX = g.px
        popupY = g.py
        popupTicks = EAT_PAUSE_TICKS
        scene = EAT_PAUSE
        sceneTicks = EAT_PAUSE_TICKS
    }

    private fun die() {
        lives--
        scene = DYING
        sceneTicks = 0
    }

    private fun dying() {
        if (++sceneTicks < DEATH_TOTAL) return
        if (lives > 0) {
            resetActors()
            globalMode = true
            globalCounter = 0
            elroySuppressed = true
            startReady()
        } else {
            scene = GAME_OVER
            sceneTicks = GAME_OVER_TICKS
        }
    }

    private fun clearing() {
        if (++sceneTicks >= CLEAR_HOLD + 8 * FLASH_TICKS + CLEAR_END) scene = CLEARED
    }

    // ---- the autopilot -------------------------------------------------------------------------------------

    /**
     * Picks the direction to leave the tile (tx, ty) by. It only walks tiles it can reach before any dangerous ghost
     * (a breadth-first search against the ghosts' own distances), and takes the nearest of: a frightened ghost, an
     * energizer when a ghost is close, the fruit, a pellet it may still eat. With nothing left to eat until the phone
     * charges further it wanders the already-eaten lanes; with no safe tile at all it runs from the nearest ghost.
     */
    private fun autopilot(tx: Int, ty: Int): Int {
        val me = PacMaze.idx(tx, ty)
        var danger = 0
        for (g in ghosts) if (g.mode == ACTIVE && !g.frightened) {
            val t = tileIndex(g)
            if (t >= 0) sources[danger++] = t
        }
        if (danger == 0) java.util.Arrays.fill(ghostDist, FAR) else ghostBfs(danger)

        val allowEat = eaten < quota
        var count = pacBfs(me, false)
        var d = -1
        if (frightTicks > 90) d = findFirst(count) { t -> ghosts.any { it.mode == ACTIVE && it.frightened && tileIndex(it) == t } }
        if (d < 0 && allowEat && danger > 0 && ghostDist[me] <= 8) d = findFirst(count) { t -> pellets[t] == 2 }
        if (d < 0 && fruitTicks > 0) d = findFirst(count) { t -> t == PacMaze.idx(13, 17) || t == PacMaze.idx(14, 17) }
        if (d < 0 && allowEat) d = findFirst(count) { t -> pellets[t] != 0 }
        if (d >= 0) return d

        // Nothing to eat yet: keep to lanes that are already clear, so no pellet is passed over uneaten.
        count = pacBfs(me, true)
        if (wander >= 0 && wander != me && pacDist[wander] > 0) return first[wander]
        wander = randomFar(count)
        if (wander >= 0) return first[wander]
        count = pacBfs(me, false)
        wander = randomFar(count)
        if (wander >= 0) return first[wander]

        var best = pac.dir
        var bestDist = -1
        for (dir in 0..3) {
            val n = PacMaze.neighbor(tx, ty, dir)
            if (n >= 0 && ghostDist[n] > bestDist) { bestDist = ghostDist[n]; best = dir }
        }
        return best
    }

    /** Breadth-first search from Pac-Man over tiles he would reach before any ghost does. Fills order, pacDist, first. */
    private fun pacBfs(me: Int, avoidPellets: Boolean): Int {
        java.util.Arrays.fill(pacDist, -1)
        var head = 0
        var tail = 0
        order[tail++] = me
        pacDist[me] = 0
        while (head < tail) {
            val c = order[head++]
            val cx = c % PacMaze.COLS
            val cy = c / PacMaze.COLS
            for (d in 0..3) {
                val n = PacMaze.neighbor(cx, cy, d)
                if (n < 0 || pacDist[n] >= 0) continue
                val steps = pacDist[c] + 1
                if (ghostDist[n] <= steps + 1) continue
                if (avoidPellets && pellets[n] != 0) continue
                pacDist[n] = steps
                first[n] = if (c == me) d else first[c]
                order[tail++] = n
            }
        }
        return tail
    }

    private inline fun findFirst(count: Int, match: (Int) -> Boolean): Int {
        for (k in 1 until count) if (match(order[k])) return first[order[k]]
        return -1
    }

    /** A random reachable tile at least five steps away, or -1. */
    private fun randomFar(count: Int): Int {
        var far = 0
        for (k in 1 until count) if (pacDist[order[k]] >= 5) far++
        if (far == 0) return -1
        var pick = nextInt(far)
        for (k in 1 until count) if (pacDist[order[k]] >= 5 && pick-- == 0) return order[k]
        return -1
    }

    /** Every dangerous ghost's walking distance to each tile (multi-source breadth-first search). */
    private fun ghostBfs(count: Int) {
        java.util.Arrays.fill(ghostDist, FAR)
        var head = 0
        var tail = 0
        for (s in 0 until count) { order[tail++] = sources[s]; ghostDist[sources[s]] = 0 }
        while (head < tail) {
            val c = order[head++]
            val cx = c % PacMaze.COLS
            val cy = c / PacMaze.COLS
            for (d in 0..3) {
                val n = PacMaze.neighbor(cx, cy, d)
                if (n < 0 || ghostDist[n] != FAR) continue
                ghostDist[n] = ghostDist[c] + 1
                order[tail++] = n
            }
        }
    }

    // ---- ghosts --------------------------------------------------------------------------------------------

    private fun moveGhost(g: Ghost) {
        when (g.mode) {
            IN_HOUSE -> {
                g.py += g.bob
                if (g.py <= 112f) g.bob = 0.5f else if (g.py >= 120f) g.bob = -0.5f
                g.dir = if (g.bob < 0) PacMaze.UP else PacMaze.DOWN
            }
            LEAVING -> leaveHouse(g)
            ENTERING -> {
                g.py += EYES_SPEED
                if (g.py >= 116f) { g.py = 116f; g.mode = LEAVING; g.frightened = false }
            }
            else -> travel(g, ghostSpeed(g)) { ghostArrive(g) }
        }
    }

    private fun leaveHouse(g: Ghost) {
        if (abs(g.px - 112f) > 0.5f) {
            g.px += if (g.px < 112f) HOUSE_SPEED else -HOUSE_SPEED
            g.dir = if (g.px < 112f) PacMaze.RIGHT else PacMaze.LEFT
            return
        }
        g.px = 112f
        g.dir = PacMaze.UP
        g.py -= HOUSE_SPEED
        if (g.py > 92f) return
        g.py = 92f
        g.mode = ACTIVE
        g.dir = PacMaze.LEFT
        g.stopped = false
        if (g.id == 3) elroySuppressed = false
        plan(g)
    }

    private fun ghostSpeed(g: Ghost): Float {
        if (g.mode == EYES) return EYES_SPEED
        val tunnel = tileY(g.py) == PacMaze.TUNNEL_ROW && (tileX(g.px) <= 5 || tileX(g.px) >= 22)
        return BASE_SPEED * when {
            g.frightened -> if (tunnel) 0.40f else 0.50f
            tunnel -> 0.40f
            g.id == 0 && elroyLevel() == 2 -> 0.85f
            g.id == 0 && elroyLevel() == 1 -> 0.80f
            else -> 0.75f
        }
    }

    /** Cruise Elroy: Blinky speeds up when few consumables remain, and not at all until Clyde leaves after a death. */
    private fun elroyLevel(): Int {
        if (elroySuppressed) return 0
        val remaining = PacMaze.totalPellets - eaten
        return if (remaining <= 10) 2 else if (remaining <= 20) 1 else 0
    }

    private fun ghostArrive(g: Ghost) {
        val tx = tileX(g.px)
        val ty = tileY(g.py)
        if (g.mode == EYES && ty == 11 && (tx == 13 || tx == 14)) {
            g.mode = ENTERING
            g.px = 112f
            g.dir = PacMaze.DOWN
            return
        }
        if (g.reverse) { g.reverse = false; g.dir = PacMaze.opposite(g.dir) } else g.dir = g.next
        plan(g)
    }

    /** Chooses the exit to take at the tile ahead of [g], the way the original looks one tile ahead. */
    private fun plan(g: Ghost) {
        val horizontal = g.dir == PacMaze.LEFT || g.dir == PacMaze.RIGHT
        val pos = if (horizontal) g.px else g.py
        val sign = if (g.dir == PacMaze.RIGHT || g.dir == PacMaze.DOWN) 1 else -1
        val u = (pos - 4f) / 8f
        val k = if (sign > 0) floor(u + EPS).toInt() + 1 else ceil(u - EPS).toInt() - 1
        var ax = tileX(g.px)
        var ay = tileY(g.py)
        if (horizontal) ax = (k + PacMaze.COLS) % PacMaze.COLS else ay = k
        if (ay < 0 || ay >= PacMaze.ROWS) { g.next = PacMaze.opposite(g.dir); return }
        g.next = chooseDir(g, ax, ay)
    }

    private fun chooseDir(g: Ghost, tx: Int, ty: Int): Int {
        val opposite = PacMaze.opposite(g.dir)
        val roaming = g.mode == ACTIVE && !g.frightened
        if (g.mode == ACTIVE && g.frightened) {
            var options = 0
            for (d in 0..3) if (d != opposite && PacMaze.neighbor(tx, ty, d) >= 0) options++
            if (options == 0) return opposite
            var pick = nextInt(options)
            for (d in 0..3) if (d != opposite && PacMaze.neighbor(tx, ty, d) >= 0 && pick-- == 0) return d
        }
        var tgtX = 0
        var tgtY = 0
        if (g.mode == EYES) { tgtX = 13; tgtY = 11 } else targetOf(g, tx, ty).let { tgtX = (it shr 32).toInt(); tgtY = it.toInt() }
        var best = opposite
        var bestDist = Int.MAX_VALUE
        for (d in 0..3) {
            if (d == opposite) continue
            val n = PacMaze.neighbor(tx, ty, d)
            if (n < 0) continue
            if (d == PacMaze.UP && roaming && NO_UP.any { it.first == tx && it.second == ty }) continue
            val dx = n % PacMaze.COLS - tgtX
            val dy = n / PacMaze.COLS - tgtY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) { bestDist = dist; best = d }
        }
        return best
    }

    /** Signed coordinates occupy separate words: Inky's target can be far outside the maze. */
    private fun targetOf(g: Ghost, tx: Int, ty: Int): Long {
        val ptx = tileX(pac.px)
        val pty = tileY(pac.py)
        val chase = chasing || (g.id == 0 && elroyLevel() > 0)
        var x: Int
        var y: Int
        if (!chase) {
            x = SCATTER_X[g.id]; y = SCATTER_Y[g.id]
        } else when (g.id) {
            0 -> { x = ptx; y = pty }
            1 -> {
                x = ptx + 4 * PacMaze.DX[pac.dir]; y = pty + 4 * PacMaze.DY[pac.dir]
                if (pac.dir == PacMaze.UP) x -= 4 // the original's overflow quirk
            }
            2 -> {
                var qx = ptx + 2 * PacMaze.DX[pac.dir]
                val qy = pty + 2 * PacMaze.DY[pac.dir]
                if (pac.dir == PacMaze.UP) qx -= 2
                x = 2 * qx - tileX(ghosts[0].px); y = 2 * qy - tileY(ghosts[0].py)
            }
            else -> {
                val dx = tx - ptx
                val dy = ty - pty
                if (dx * dx + dy * dy >= 64) { x = ptx; y = pty } else { x = SCATTER_X[3]; y = SCATTER_Y[3] }
            }
        }
        return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }

    // ---- shared movement helpers ---------------------------------------------------------------------------

    /**
     * Moves [a] by [dist] px along its direction, stopping at every tile centre it crosses to call [arrive], which
     * may change its direction (or set [Actor.stopped]). The tunnel wraps from one side to the other.
     */
    private inline fun travel(a: Actor, dist: Float, arrive: () -> Unit) {
        var left = dist
        var guard = 0
        while (left > 1e-4f && !a.stopped && guard++ < 4) {
            val horizontal = a.dir == PacMaze.LEFT || a.dir == PacMaze.RIGHT
            val pos = if (horizontal) a.px else a.py
            val sign = if (a.dir == PacMaze.RIGHT || a.dir == PacMaze.DOWN) 1 else -1
            val u = (pos - 4f) / 8f
            val k = if (sign > 0) floor(u + EPS).toInt() + 1 else ceil(u - EPS).toInt() - 1
            val center = k * 8f + 4f
            val gap = abs(center - pos)
            if (left >= gap) {
                left -= gap
                if (horizontal) {
                    a.px = center
                    if (k < 0) a.px += PacMaze.COLS * 8f else if (k >= PacMaze.COLS) a.px -= PacMaze.COLS * 8f
                } else a.py = center
                arrive()
            } else {
                if (horizontal) a.px += sign * left else a.py += sign * left
                left = 0f
            }
        }
    }

    private fun tileX(px: Float) = floor(px / 8f).toInt().coerceIn(0, PacMaze.COLS - 1)
    private fun tileY(py: Float) = floor(py / 8f).toInt().coerceIn(0, PacMaze.ROWS - 1)

    private fun tileIndex(a: Actor): Int {
        val i = PacMaze.idx(tileX(a.px), tileY(a.py))
        return if (PacMaze.walkable[i]) i else -1
    }

    private fun nextInt(bound: Int): Int {
        rng = rng * 1103515245 + 12345
        return ((rng ushr 16) and 0x7FFF) % bound
    }

    companion object {
        const val IN_HOUSE = 0
        const val LEAVING = 1
        const val ACTIVE = 2
        const val EYES = 3
        const val ENTERING = 4

        const val READY = 0
        const val PLAYING = 1
        const val EAT_PAUSE = 2
        const val DYING = 3
        const val CLEARING = 4
        const val CLEARED = 5
        const val GAME_OVER = 6

        const val FRIGHT_TICKS = 360       // 6 s on board 1
        const val FRIGHT_FLASHES = 5
        const val FLASH_TICKS = 14

        private const val EPS = 1e-3f
        private const val FAR = 10_000
        private const val BASE_SPEED = 75.75f / 60f // px per tick at 100%
        private const val EYES_SPEED = 2f
        private const val HOUSE_SPEED = 0.6f
        private const val START_LIVES = 3
        private const val EXTRA_LIFE = 10_000
        private const val READY_TICKS = 150
        private const val EAT_PAUSE_TICKS = 60
        private const val DEATH_HOLD = 60
        private const val DEATH_STEP = 8
        private const val DEATH_TOTAL = DEATH_HOLD + 11 * DEATH_STEP + 40
        private const val GAME_OVER_TICKS = 180
        private const val GHOST_HIDE = 60
        private const val CLEAR_HOLD = 90
        private const val CLEAR_END = 30
        private const val RELEASE_TIMEOUT = 240 // 4 s without a dot, boards 1 to 4
        private const val ASSIST_TICKS = 45 * 60
        private const val FRUIT_FIRST = 70
        private const val FRUIT_SECOND = 170
        private const val FRUIT_TICKS = 570
        private const val FRUIT_POINTS = 100    // cherries
        const val FRUIT_X = 112f
        const val FRUIT_Y = 140f

        /** Scatter, chase, scatter, chase, scatter, chase, scatter, chase (level 1), in ticks; -1 never ends. */
        private val WAVES = intArrayOf(7 * 60, 20 * 60, 7 * 60, 20 * 60, 5 * 60, 20 * 60, 5 * 60, -1)
        private val PERSONAL_LIMIT = intArrayOf(0, 0, 30, 60)
        private val SCATTER_X = intArrayOf(25, 2, 27, 0)
        private val SCATTER_Y = intArrayOf(-3, -3, 31, 31)
        private val NO_UP = listOf(12 to 11, 15 to 11, 12 to 23, 15 to 23)
    }
}
