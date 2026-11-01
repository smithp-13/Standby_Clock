package com.standby.clock

import org.junit.Assert.*
import org.junit.Test

class PacmanGameTest {
    @Test fun energizersGiveFifteenSecondsAndRefreshTheChaseOnEveryBoard() {
        val energize = PacmanGame::class.java.getDeclaredMethod("startFrightened").apply { isAccessible = true }
        val timer = PacmanGame::class.java.getDeclaredField("frightTicks").apply { isAccessible = true }
        for (percent in 0..90 step 10) {
            val game = PacmanGame()
            game.setCharge(percent, false)
            energize.invoke(game)
            assertEquals(15 * 60, game.frightTicks)
            assertTrue(game.ghosts.all { it.frightened })
            timer.setInt(game, 90)
            energize.invoke(game)
            assertEquals(15 * 60, game.frightTicks)
        }
    }

    @Test fun eachPercentUnlocksOnlyItsOwnRegionInOrder() {
        val game = PacmanGame()
        game.setCharge(1, false)
        val eat = PacmanGame::class.java.getDeclaredMethod("eatAt", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        val later = game.pellets.indices.first { game.pellets[it] != 0 && PacMaze.region(it) == 1 }
        val original = game.pellets[later]
        eat.invoke(game, later % PacMaze.COLS, later / PacMaze.COLS)
        assertEquals(original, game.pellets[later])
        for (i in game.pellets.indices) if (PacMaze.region(i) == 0 && game.pellets[i] != 0)
            eat.invoke(game, i % PacMaze.COLS, i / PacMaze.COLS)
        assertEquals(-1, game.activeRegion)
        eat.invoke(game, later % PacMaze.COLS, later / PacMaze.COLS)
        assertEquals(original, game.pellets[later])
        game.setCharge(2, false)
        assertEquals(1, game.activeRegion)
        eat.invoke(game, later % PacMaze.COLS, later / PacMaze.COLS)
        assertEquals(0, game.pellets[later])
    }

    @Test fun openingAtDifferentBatteryLevelsSelectsTheChargingBand() {
        for (percent in intArrayOf(0, 25, 50, 75, 99, 100)) {
            val game = PacmanGame()
            game.setCharge(percent, false)
            assertEquals("battery $percent", 0, game.eaten)
            assertEquals((percent / 10 + 1).coerceAtMost(10), game.boardNumber)
            assertEquals(PacMaze.totalPellets, game.pellets.count { it != 0 })
        }
    }

    @Test fun originalMazeHasAllConsumablesReachable() {
        assertTrue(PacMaze.COLS > PacMaze.ROWS * 2)
        assertTrue(PacMaze.totalPellets > 244)
        assertEquals(4, PacMaze.pellets0.count { it == 2 })
        for (i in PacMaze.pellets0.indices) if (PacMaze.pellets0[i] != 0) assertTrue(PacMaze.walkable[i])
        assertEquals(PacMaze.idx(PacMaze.COLS - 1, PacMaze.TUNNEL_ROW), PacMaze.neighbor(0, PacMaze.TUNNEL_ROW, PacMaze.LEFT))
        assertEquals(PacMaze.idx(0, PacMaze.TUNNEL_ROW), PacMaze.neighbor(PacMaze.COLS - 1, PacMaze.TUNNEL_ROW, PacMaze.RIGHT))
    }

    @Test fun pelletProgressCannotCompleteBeforeTheTenPercentMilestone() {
        val game = PacmanGame()
        game.setCharge(9, false)
        assertEquals(0, game.eaten)
        repeat(15_000) { game.tick() }
        assertTrue(game.eaten > 0)
        assertTrue(game.eaten <= game.quota)
        assertFalse(game.cleared)
        assertEquals(PacMaze.totalPellets - game.eaten, game.pellets.count { it != 0 })
        game.setCharge(10, false)
        repeat(150_000) { if (game.boardNumber == 1) game.tick() }
        assertEquals(2, game.boardNumber)
        assertEquals(0, game.quota)
    }

    @Test fun chargeCompletionClearsAndResumingChargeStartsAFreshBoard() {
        val game = PacmanGame()
        game.setCharge(99, false)
        game.setCharge(100, true)
        repeat(150_000) { if (game.scene != PacmanGame.CLEARED) game.tick() }
        assertEquals(PacmanGame.CLEARED, game.scene)
        assertEquals(PacMaze.totalPellets, game.eaten)
        assertTrue(game.pellets.all { it == 0 })
        game.setCharge(50, false)
        assertEquals(PacmanGame.READY, game.scene)
        assertEquals(0, game.eaten)
        assertEquals(PacMaze.totalPellets, game.pellets.count { it != 0 })
        assertEquals(6, game.boardNumber)
    }

    @Test fun newGameDoesNotInheritHouseCountersFromPreviousRun() {
        val game = PacmanGame()
        game.setCharge(0, false)
        val counters = PacmanGame::class.java.getDeclaredField("personal").apply { isAccessible = true }
        (counters.get(game) as IntArray).fill(99)
        val scene = PacmanGame::class.java.getDeclaredField("scene").apply { isAccessible = true }
        scene.setInt(game, PacmanGame.GAME_OVER)
        repeat(200) { game.tick() }
        assertArrayEquals(intArrayOf(0, 0, 0, 0), counters.get(game) as IntArray)
    }

    @Test fun inkyTargetRetainsNegativeCoordinatesOutsideTheMaze() {
        val game = PacmanGame()
        game.setCharge(0, false)
        PacmanGame::class.java.getDeclaredField("chasing").apply { isAccessible = true }.setBoolean(game, true)
        game.pac.px = 84f // tile (10,1), pointing up
        game.pac.py = 12f
        game.pac.dir = PacMaze.UP
        game.ghosts[0].px = 36f // tile (4,20)
        game.ghosts[0].py = 164f
        val method = PacmanGame::class.java.getDeclaredMethod("targetOf", PacmanGame.Ghost::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val target = method.invoke(game, game.ghosts[2], 10, 10) as Long
        assertEquals(12, (target shr 32).toInt())
        assertEquals(-22, target.toInt())
    }
}
