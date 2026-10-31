package com.standby.clock

import android.graphics.Color

/**
 * Pixel-art sprites for the Mario clock, one character per pixel ('.' is transparent), coloured by [PALETTE].
 * Every character, enemy, block and item is the NES sprite from the Mario Wiki, taken pixel for pixel from the PNGs in
 * SMB_iCON/ (the wiki only has standing poses, so Mario's walk and jump poses change only his legs and fist). Only the
 * fireball and the smiling cloud are drawn by hand.
 */
internal object MarioSprites {

    val PALETTE = mapOf(
        'r' to Color.rgb(0xB5, 0x31, 0x20), // Mario: cap and overalls; mushroom cap; flower
        'h' to Color.rgb(0x6B, 0x6D, 0x00), // Mario: hair, shirt and shoes
        's' to Color.rgb(0xEA, 0x9E, 0x22), // Mario: skin; the items' orange
        'f' to Color.rgb(0xF7, 0xD8, 0xA5), // Fire Mario: cream
        'a' to Color.rgb(0x99, 0x4E, 0x00), // brown: Goomba, bricks
        'p' to Color.rgb(0xFF, 0xCC, 0xC5), // pink: Goomba feet, brick highlights
        'k' to Color.BLACK,
        'e' to Color.rgb(0x0D, 0x93, 0x00), // green
        'l' to Color.rgb(0x88, 0xD8, 0x00), // light green: pipe
        'W' to Color.WHITE,
        'K' to Color.rgb(0x24, 0x22, 0x20), // the smiling cloud's face
        'R' to Color.rgb(0xF3, 0x39, 0x33), // fireball and fireworks
        'O' to Color.rgb(0xF8, 0xA0, 0x30),
        'L' to Color.rgb(0xB8, 0xF8, 0x18), // flagpole ball and fireworks
        'G' to Color.rgb(0x00, 0xA8, 0x00), // flagpole, flag
        'Q' to Color.rgb(0x99, 0x4E, 0x00), // castle bricks
    )

    /** Fire Mario: the wiki's Fire Mario sprite is the Super sprite with these two colours changed. */
    val FIRE_SWAP = mapOf('r' to Color.rgb(0xF7, 0xD8, 0xA5), 'h' to Color.rgb(0xB5, 0x31, 0x20))

    /** Star power: Mario cycles through these every 70 ms. */
    val STAR_SWAPS = listOf(
        mapOf('r' to Color.rgb(0x00, 0xA8, 0x00), 'h' to Color.rgb(0xF8, 0xB8, 0x00)),
        mapOf('r' to Color.WHITE, 'h' to Color.rgb(0xB5, 0x31, 0x20)),
        mapOf('r' to Color.rgb(0x24, 0x22, 0x20), 'h' to Color.rgb(0xF8, 0xB8, 0x00)),
        emptyMap(),
    )

    // ---- Mario: 16 wide, 16 tall small and 32 tall Super; all face right -------------------------------------------

    /** Small Mario standing: the wiki sprite (12 wide) centred in 16. */
    val STAND = arrayOf(
        ".....rrrrr......",
        "....rrrrrrrrr...",
        "....hhhsshs.....",
        "...hshssshsss...",
        "...hshhssshsss..",
        "...hhsssshhhh...",
        ".....sssssss....",
        "....hhrhhh......",
        "...hhhrhhrhhh...",
        "..hhhhrrrrhhhh..",
        "..sshrsrrsrhss..",
        "..sssrrrrrrsss..",
        "..ssrrrrrrrrss..",
        "....rrr..rrr....",
        "...hhh....hhh...",
        "..hhhh....hhhh..",
    )
    /** Back foot lifted, legs together: between standing and the stride. */
    private val PASS_SMALL = arrayOf(
        ".....rrrrr......",
        "....rrrrrrrrr...",
        "....hhhsshs.....",
        "...hshssshsss...",
        "...hshhssshsss..",
        "...hhsssshhhh...",
        ".....sssssss....",
        "....hhrhhh......",
        "...hhhrhhrhhh...",
        "..hhhhrrrrhhhh..",
        "..sshrsrrsrhss..",
        "..sssrrrrrrsss..",
        "..ssrrrrrrrrss..",
        "...rrrrrr.......",
        "...hhhhhhh......",
        "...hhh.hhhh.....",
    )
    /** Mid-stride, feet apart. */
    private val STRIDE_SMALL = arrayOf(
        ".....rrrrr......",
        "....rrrrrrrrr...",
        "....hhhsshs.....",
        "...hshssshsss...",
        "...hshhssshsss..",
        "...hhsssshhhh...",
        ".....sssssss....",
        "....hhrhhh......",
        "...hhhrhhrhhh...",
        "..hhhhrrrrhhhh..",
        "..sshrsrrsrhss..",
        "..sssrrrrrrsss..",
        "..ssrrrrrrrrss..",
        "..rrrr....rrrr..",
        "..hhhh....hhhh..",
        ".hhh........hhh.",
    )
    /** Standing, lifting a foot, stride, lifting a foot: the legs swing instead of snapping from wide to closed. */
    val WALK_CYCLE = arrayOf(STAND, PASS_SMALL, STRIDE_SMALL, PASS_SMALL)
    /** Small Mario in the air, legs apart. */
    val JUMP = arrayOf(
        ".....rrrrr......",
        "....rrrrrrrrr...",
        "....hhhsshs.....",
        "...hshssshsss...",
        "...hshhssshsss..",
        "...hhsssshhhh...",
        ".....sssssss....",
        "....hhrhhh......",
        "...hhhrhhrhhh...",
        "..hhhhrrrrhhhh..",
        "..sshrsrrsrhss..",
        "..sssrrrrrrsss..",
        "..ssrrrrrrrrss..",
        "..rrr......rrr..",
        ".hhhh......hhhh.",
        "hhh..........hhh",
    )

    /** Super Mario standing: the wiki sprite. Fire Mario is the same with [FIRE_SWAP]. */
    val BIG_STAND = arrayOf(
        "......rrrrr.....",
        "....rrrrrrs.....",
        "...rrrrrrss.....",
        "...rrrrrrrrrrr..",
        "...hhhsshsss....",
        "..hsshsshhssss..",
        "..hsshhssssssss.",
        ".hhsshhssshssss.",
        ".hhssssshhhhhh..",
        ".hhhssssshhhhh..",
        "...hhssssssss...",
        "....rsssssh.....",
        "....hrhhhhrh....",
        "...hhrhhhhrhh...",
        "..hhhrhhhhrhhh..",
        ".hhhhrhhhhrhhhh.",
        ".hhhrrhhhhrrhhh.",
        "hhhhrrhhhhrrhhhh",
        "hhhhrrrrrrrrhhhh",
        "hhhhrsrrrrsrhhhh",
        "ssssrrrrrrrrssss",
        "ssssrrrrrrrrssss",
        ".sssrrrrrrrrsss.",
        ".ssrrrrrrrrrrss.",
        "..rrrrrrrrrrrr..",
        ".rrrrrr..rrrrrr.",
        ".rrrrr....rrrrr.",
        ".rrrrr....rrrrr.",
        "..hhhh....hhhh..",
        "..hhhh....hhhh..",
        "hhhhhh....hhhhhh",
        "hhhhhh....hhhhhh",
    )
    private val BIG_PASS = arrayOf(
        "......rrrrr.....",
        "....rrrrrrs.....",
        "...rrrrrrss.....",
        "...rrrrrrrrrrr..",
        "...hhhsshsss....",
        "..hsshsshhssss..",
        "..hsshhssssssss.",
        ".hhsshhssshssss.",
        ".hhssssshhhhhh..",
        ".hhhssssshhhhh..",
        "...hhssssssss...",
        "....rsssssh.....",
        "....hrhhhhrh....",
        "...hhrhhhhrhh...",
        "..hhhrhhhhrhhh..",
        ".hhhhrhhhhrhhhh.",
        ".hhhrrhhhhrrhhh.",
        "hhhhrrhhhhrrhhhh",
        "hhhhrrrrrrrrhhhh",
        "hhhhrsrrrrsrhhhh",
        "ssssrrrrrrrrssss",
        "ssssrrrrrrrrssss",
        ".sssrrrrrrrrsss.",
        ".ssrrrrrrrrrrss.",
        "..rrrrrrrrrrrr..",
        ".rrrrrr..rrrrrr.",
        ".rrrrr....rrrrr.",
        ".rrrrr....rrrrr.",
        "..hhhh....hhhh..",
        "..hhhh....hhhh..",
        "..........hhhhhh",
        "..........hhhhhh",
    )
    private val BIG_STRIDE = arrayOf(
        "......rrrrr.....",
        "....rrrrrrs.....",
        "...rrrrrrss.....",
        "...rrrrrrrrrrr..",
        "...hhhsshsss....",
        "..hsshsshhssss..",
        "..hsshhssssssss.",
        ".hhsshhssshssss.",
        ".hhssssshhhhhh..",
        ".hhhssssshhhhh..",
        "...hhssssssss...",
        ".....hhhss......",
        "....rrrrhhr...s.",
        "...hhhhrrhhr.sss",
        "..hhhhhhrrhrhsss",
        ".hhhhhhhrrhhrsss",
        ".hhhhhhhrrhhrhsh",
        ".hhhhhhrrrhhrhh.",
        "hhhhhhrrrsrhsh..",
        "hhhhrrrrrrrrr...",
        "sssssrrrrrrrr...",
        "sssssrrrrrrrr..h",
        "ssssrrrrrrrrr.hh",
        ".sssrrrrrrrhhhhh",
        "...rhrrrrrrhhhhh",
        "..hrrhrrrrrhhhhh",
        "hhhrrrhhrrrhhhhh",
        "hhhhrrrr...hhhhh",
        "hhhhhr..........",
        ".hhh............",
        ".hhh............",
        "..hhh...........",
    )
    val BIG_WALK_CYCLE = arrayOf(BIG_STAND, BIG_PASS, BIG_STRIDE, BIG_PASS)
    /** Super Mario in the air. */
    val BIG_JUMP = arrayOf(
        "......rrrrr.....",
        "....rrrrrrs.....",
        "...rrrrrrss.....",
        "...rrrrrrrrrrr..",
        "...hhhsshsss....",
        "..hsshsshhssss..",
        "..hsshhssssssss.",
        ".hhsshhssshssss.",
        ".hhssssshhhhhh..",
        ".hhhssssshhhhh..",
        "...hhssssssss...",
        ".....hhhss......",
        "....rrrrhhr...s.",
        "...hhhhrrhhr.sss",
        "..hhhhhhrrhrhsss",
        ".hhhhhhhrrhhrsss",
        ".hhhhhhhrrhhrhsh",
        ".hhhhhhrrrhhrhh.",
        "hhhhhhrrrsrhsh..",
        "hhhhrrrrrrrrr...",
        "sssssrrrrrrrr...",
        "sssssrrrrrrrr..h",
        "ssssrrrrrrrrr.hh",
        ".sssrrrrrrrhhhhh",
        "...rhrrrrrrhhhhh",
        "..hrrhrrrrrhhhhh",
        "hhhrrrhhrrrhhhhh",
        "hhhhrrrr...hhhhh",
        "hhhhhr..........",
        ".hhh............",
        ".hhh............",
        "..hhh...........",
    )

    // ---- Enemies (face left, towards Mario) -----------------------------------------------------------------------

    /** Mirrored every 200 ms so its feet shuffle. */
    val GOOMBA = arrayOf(
        "......aaaa......",
        ".....aaaaaa.....",
        "....aaaaaaaa....",
        "...aaaaaaaaaa...",
        "..akkaaaaaakka..",
        ".aaapkaaaakpaaa.",
        ".aaapkkkkkkpaaa.",
        "aaaapkpaapkpaaaa",
        "aaaapppaapppaaaa",
        "aaaaaaaaaaaaaaaa",
        ".aaaappppppaaaa.",
        "....pppppppp....",
        "....ppppppppkk..",
        "...kkpppppkkkkk.",
        "...kkkpppkkkkkk.",
        "....kkk..kkkkk..",
    )
    val GOOMBA_FLAT = arrayOf(
        ".aaapkaaaakpaaa.",
        ".aaapkkkkkkpaaa.",
        "aaaapppaapppaaaa",
        "aaaaaaaaaaaaaaaa",
        "....pppppppp....",
        "...kkkpppkkkkkk.",
    )
    val KOOPA = arrayOf(
        "...W............",
        "..WWW...........",
        "..WWWs..........",
        ".seWWss.........",
        ".seWWss.........",
        ".seWWss.........",
        ".sWWWss.........",
        "sssWsss.........",
        "sesssss.........",
        "ssssss..eeeee...",
        "sss.ss.eseeese..",
        "ss..ss.eeseseee.",
        "ss.ssWeeeeseWWe.",
        ".s.ssWeeeseseWe.",
        "...ssWseseeeses.",
        "..ssWWeseeeeese.",
        "...sWeseseeeses.",
        "...sWseeeseseee.",
        "....Weeeeeseeee.",
        "....WWeeeseseWWW",
        "...ssWWWseeWWW..",
        "..sssssWWWWWsss.",
        ".sssss......ssss",
    )
    /** A stomped Koopa: the shell slides away and knocks out whatever it meets. */
    val SHELL = arrayOf(
        ".....seeees.....",
        "....eessssee....",
        "...eeseeeesee...",
        "...eseeeeeese...",
        "..eseeeeeeeese..",
        "..seseeeeeeses..",
        ".seeeseeeeseees.",
        ".eeeeesssseeeee.",
        "WWWeeseeeeseeWWW",
        "WWWWseeeeeesWWWW",
        "...WWeeeeeeWW...",
        "....WWeeeeWW....",
        ".....WWWWWW.....",
        "......WWWW......",
    )
    /** Fire-proof: a fireball is wasted on it, so it has to be stomped. */
    val BUZZY = arrayOf(
        "......kkkk......",
        "....kkkkkkkk....",
        "...kkkkkkkkkk...",
        "..kkkkkkkaakkk..",
        ".kkkkkkkkapakk..",
        ".kkkkkkkkkaakk..",
        "aaaakkkkkkkkkkk.",
        ".kkaakkkkkkkkkk.",
        ".kkkakkkkkkkkkk.",
        "kkpkakkkkkkkkkk.",
        "kkkkakkkkkkkkkk.",
        "kkkkakkkkkkkkkk.",
        ".kkpaakkkkkaaaaa",
        "..pppaakkaaappp.",
        ".pppp.aaaa..pppp",
    )
    val BUZZY_SHELL = arrayOf(
        "......kkkk......",
        "....kkkkkkkk....",
        "...kkkkaakkkk...",
        "..kkkkappakkkk..",
        "..kkkkkaakkkkk..",
        "..kkkkkkkkkkkk..",
        ".kkkkkkkkkkkkkk.",
        ".kkkkkkkkkkkkkk.",
        ".kkkkkkkkkkkkkk.",
        ".kkkkkkkkkkkkkk.",
        ".kkkkkkkkkkkkkk.",
        ".kkkkkkkkkkkkkk.",
        "aaaaakkkkkkaaaaa",
        "....aaakkaaa....",
        "......aaaa......",
    )
    /** Rises out of its pipe and ducks back in. */
    val PIRANHA = arrayOf(
        "..e..........e..",
        ".esWW......WWse.",
        ".eeW........Wee.",
        ".seeWW....WWees.",
        "eeesW......Wseee",
        "seeeeWW..WWeeees",
        "eeseeW....Weesee",
        "eeeee......eeeee",
        "eseeeeW..Weeeese",
        "eeeeseW..Weseeee",
        ".seeeee..eeeees.",
        ".eeseee..eeesee.",
        "..eeees..seeee..",
        "...eseeeeeese...",
        ".....eseese.....",
        "ss.....ss.....ss",
        "sess...ss...sses",
        ".sess..ss..sses.",
        ".ssess.ss.ssess.",
        "..sses.ss.sess..",
        "..sssessssesss..",
        "...ssssssssss...",
        "......ssss......",
    )

    // ---- Ground obstacles -----------------------------------------------------------------------------------------

    /** Two of the same block side by side: the course's obstacles are two bricks long, as in the game. */
    private fun wide(grid: Array<String>) = Array(grid.size) { grid[it] + grid[it] }

    /** A short warp pipe two bricks wide. Mario clears it with one jump or lands on top. */
    val PIPE = arrayOf(
        "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk",
        "kllllllllllllllllllllllllllllllk",
        "keeeeelllllleeeeeeeeeeeeeeeeeeek",
        "kllleellllllelleeeeeeeeeelelellk",
        "kllleellllllelleeeeeeeeeeelelllk",
        "kllleellllllelleeeeeeeeeelelellk",
        "kllleellllllelleeeeeeeeeeelelllk",
        "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk",
        "..kkkkkkkkkkkkkkkkkkkkkkkkkkkk..",
        "..kllleelllllelleeeeeeeelelllk..",
        "..kllleelllllelleeeeeeeeelellk..",
        "..kllleelllllelleeeeeeeelelllk..",
        "..kllleelllllelleeeeeeeeelellk..",
        "..kllleelllllelleeeeeeeelelllk..",
        "..kllleelllllelleeeeeeeeelellk..",
        "..kllleelllllelleeeeeeeelelllk..",
    )
    /** One hard block, the flagpole's base. */
    val HARD_TILE = arrayOf(
        "appppppppppppppk",
        "pappppppppppppkk",
        "ppappppppppppkkk",
        "pppappppppppkkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "ppppaaaaaaaakkkk",
        "pppkkkkkkkkkakkk",
        "ppkkkkkkkkkkkakk",
        "pkkkkkkkkkkkkkak",
        "kkkkkkkkkkkkkkka",
    )
    val HARD_BLOCK = wide(HARD_TILE)
    val BRICK_BLOCK = wide(arrayOf(
        "pppppppppppppppp",
        "aaaaaaakaaaaaaak",
        "aaaaaaakaaaaaaak",
        "kkkkkkkkkkkkkkkk",
        "aaakaaaaaaakaaaa",
        "aaakaaaaaaakaaaa",
        "aaakaaaaaaakaaaa",
        "kkkkkkkkkkkkkkkk",
        "aaaaaaakaaaaaaak",
        "aaaaaaakaaaaaaak",
        "aaaaaaakaaaaaaak",
        "kkkkkkkkkkkkkkkk",
        "aaakaaaaaaakaaaa",
        "aaakaaaaaaakaaaa",
        "aaakaaaaaaakaaaa",
        "kkkkkkkkkkkkkkkk",
    ))

    // ---- Items from the ? blocks ----------------------------------------------------------------------------------

    val MUSHROOM = arrayOf(
        "......ssss......",
        ".....ssssrr.....",
        "....ssssrrrr....",
        "...sssssrrrrr...",
        "..sssssssrrrss..",
        ".ssrrrsssssssss.",
        ".srrrrrssssssss.",
        "ssrrrrrsssssrrss",
        "ssrrrrrsssssrrrs",
        "sssrrrsssssssrrs",
        "ssssssssssssssss",
        ".srrrWWWWWWrrrs.",
        "....WWWWWWWW....",
        "....WWWWWWsW....",
        "....WWWWWWsW....",
        ".....WWWWsW.....",
    )
    val ONE_UP = arrayOf(
        "......ssss......",
        ".....ssssee.....",
        "....sssseeee....",
        "...ssssseeeee...",
        "..ssssssseeess..",
        ".sseeesssssssss.",
        ".seeeeessssssss.",
        "sseeeeessssseess",
        "sseeeeessssseees",
        "ssseeesssssssees",
        "ssssssssssssssss",
        ".seeeWWWWWWeees.",
        "....WWWWWWWW....",
        "....WWWWWWsW....",
        "....WWWWWWsW....",
        ".....WWWWsW.....",
    )
    val FLOWER = arrayOf(
        "....WWWWWWWW....",
        "..WWWWWWWWWWWW..",
        ".WWWssssssssWWW.",
        "WWsssrrrrrrsssWW",
        "WWsssrrrrrrsssWW",
        ".WWWssssssssWWW.",
        "..WWWWWWWWWWWW..",
        "....WWWWWWWW....",
        ".......ee.......",
        "eee....ee....eee",
        ".eee...ee...eee.",
        ".eeee..ee..eeee.",
        "..eeee.ee.eeee..",
        "..eeee.ee.eeee..",
        "...eeeeeeeeee...",
        "......eeee......",
    )
    val STAR = arrayOf(
        ".......ss.......",
        ".......ss.......",
        "......ssss......",
        "......ssss......",
        ".....ssssss.....",
        ".ssssssssssssss.",
        ".sssssrssrsssss.",
        "..ssssrssrssss..",
        "...sssrssrsss...",
        "....ssssssss....",
        "....ssssssss....",
        "...ssssssssss...",
        "...ssssssssss...",
        "...ssss..ssss...",
        "..sss......sss..",
        "..ss........ss..",
    )
    val FIREBALL = arrayOf(
        ".OO.",
        "ORRO",
        "ORRO",
        ".OO.",
    )

    /** The smiling cloud drifting across the sky. */
    val CLOUD_FACE = arrayOf(
        "....WWWWWW....",
        "..WWWWWWWWWW..",
        ".WWWWWWWWWWWW.",
        "WWWWKWWWWKWWWW",
        "WWWWKWWWWKWWWW",
        "WWWWWWWWWWWWWW",
        "WWKWWWWWWWWKWW",
        ".WWKKKKKKKKWW.",
        "..WWWWWWWWWW..",
        "....WWWWWW....",
    )
}
