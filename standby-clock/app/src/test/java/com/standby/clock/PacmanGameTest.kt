package com.standby.clock

import org.junit.Assert.*
import org.junit.Test

class PacmanGameTest {
    @Test fun openingAtDifferentBatteryLevelsStartsWithAllPelletsAvailable() {
        for (percent in intArrayOf(0, 25, 50, 75, 99, 100)) {
            val game = PacmanGame()
            game.setCharge(percent, false)
            assertEquals("battery $percent", 0, game.eaten)
            assertEquals(244, game.quota)
            assertEquals(244, game.pellets.count { it != 0 })
        }
    }

    @Test fun originalMazeHasAllConsumablesReachable() {
        assertEquals(240, PacMaze.pellets0.count { it == 1 })
        assertEquals(4, PacMaze.pellets0.count { it == 2 })
        for (i in PacMaze.pellets0.indices) if (PacMaze.pellets0[i] != 0) assertTrue(PacMaze.walkable[i])
        assertEquals(PacMaze.idx(27, 14), PacMaze.neighbor(0, 14, PacMaze.LEFT))
        assertEquals(PacMaze.idx(0, 14), PacMaze.neighbor(27, 14, PacMaze.RIGHT))
    }

    @Test fun pelletsRemainActiveRegardlessOfBatteryLevel() {
        val game = PacmanGame()
        game.setCharge(50, false)
        assertEquals(0, game.eaten)
        repeat(3_000) { game.tick() }
        assertTrue(game.eaten > 0)
        assertEquals(244, game.quota)
        assertEquals(244 - game.eaten, game.pellets.count { it != 0 })
    }

    @Test fun chargeCompletionClearsAndResumingChargeStartsAFreshBoard() {
        val game = PacmanGame()
        game.setCharge(99, false)
        game.setCharge(100, true)
        repeat(60_000) { if (game.scene != PacmanGame.CLEARED) game.tick() }
        assertEquals(PacmanGame.CLEARED, game.scene)
        assertEquals(244, game.eaten)
        assertTrue(game.pellets.all { it == 0 })
        game.setCharge(50, false)
        assertEquals(PacmanGame.READY, game.scene)
        assertEquals(0, game.eaten)
        assertEquals(244, game.pellets.count { it != 0 })
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
