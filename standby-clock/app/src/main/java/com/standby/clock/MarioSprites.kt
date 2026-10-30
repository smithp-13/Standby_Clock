package com.standby.clock

import android.graphics.Color

/**
 * Pixel-art sprites for the Mario clock, one character per pixel ('.' is transparent), coloured by [PALETTE].
 * Mario's palette, head, standing body and jump pose are sampled from the user's mario_*.png; everything else is
 * drawn in the NES / Mario Maker style of the user's reference sheet.
 */
internal object MarioSprites {

    val PALETTE = mapOf(
        'R' to Color.rgb(0xF3, 0x39, 0x33), // cap and shirt, mushroom cap, flower centre
        'H' to Color.rgb(0x69, 0x4F, 0x42), // hair and shoes
        'S' to Color.rgb(0xF1, 0xC6, 0x90), // skin
        'K' to Color.rgb(0x24, 0x22, 0x20), // eyes, moustache, outlines
        'B' to Color.rgb(0x66, 0x76, 0xD5), // overalls
        'Y' to Color.rgb(0xE5, 0xA9, 0x3B), // buttons
        'U' to Color.rgb(0xF8, 0xD8, 0x78), // Koopa skin
        'L' to Color.rgb(0xB8, 0xF8, 0x18), // light green
        'G' to Color.rgb(0x00, 0xA8, 0x00), // green
        'D' to Color.rgb(0x00, 0x60, 0x00), // dark green
        'N' to Color.rgb(0xA0, 0x4A, 0x10), // brown
        'T' to Color.rgb(0xF0, 0xC0, 0x90), // tan
        'Q' to Color.rgb(0xC8, 0x4C, 0x0C), // brick
        'O' to Color.rgb(0xF8, 0xA0, 0x30), // star and flower orange
        'C' to Color.rgb(0x3C, 0xBC, 0xFC), // ice
        'W' to Color.WHITE,
    )

    /** Fire Mario: white overalls. */
    val FIRE_SWAP = mapOf('B' to Color.rgb(0xF8, 0xF8, 0xF8))

    /** Star power: Mario cycles through these every 70 ms. */
    val STAR_SWAPS = listOf(
        mapOf('R' to Color.rgb(0x00, 0xA8, 0x00), 'B' to Color.rgb(0xF8, 0xB8, 0x00)),
        mapOf('R' to Color.WHITE, 'B' to Color.rgb(0xF3, 0x39, 0x33)),
        mapOf('R' to Color.rgb(0x24, 0x22, 0x20), 'B' to Color.rgb(0xF8, 0xB8, 0x00)),
        emptyMap(),
    )

    // ---- Small Mario (16×16) --------------------------------------------------------------------------------------

    // Same head on every frame (rows 0-6 of mario_stand.png) and both hands always drawn, so nothing pops
    // between frames. Only arms and legs move.
    private val HEAD = arrayOf(
        ".....RRRRR......",
        "....RRRRRRRRR...",
        "....HHHSSKS.....",
        "...HSHSSSKSSS...",
        "...HSHHSSSKSSS..",
        "...HHSSSSKKKK...",
        ".....SSSSSSS....",
    )
    /** Stride: arms swung out front and back, legs apart. */
    private val WALK_1 = HEAD + arrayOf(
        "....RRBRRR......",
        "...RRRBRRBRR....",
        ".SSRRRBBBBRRRSS.",
        ".SS.RBYBBYBR.SS.",
        "....BBBBBBBB....",
        "...BBBBBBBBBB...",
        "..BBBB...BBBB...",
        "..HHH.....HHH...",
        ".HHHH.....HHHH..",
    )
    /** Mid step: hands at the hips, legs closing. */
    private val WALK_2 = HEAD + arrayOf(
        "....RRBRRR......",
        "...RRRBRRBRR....",
        "...RRRBBBBRRR...",
        "...SSBYBBYBSS...",
        "...SSBBBBBBSS...",
        "....BBBBBBBB....",
        "...BBBB..BBB....",
        "...HHH...HHH....",
        "..HHHH...HHHH...",
    )
    /** Passing: legs together under the body. */
    private val WALK_3 = HEAD + arrayOf(
        "....RRBRRR......",
        "...RRRBRRBRR....",
        "...RRRBBBBRRR...",
        "...SSBYBBYBSS...",
        "...SSBBBBBBSS...",
        ".....BBBBBB.....",
        ".....BBBBBB.....",
        ".....HHHHHH.....",
        "....HHHHHHHH....",
    )
    /** NES order 1-2-3-2, so the legs swing back and forth instead of snapping from wide to closed. */
    val WALK_CYCLE = arrayOf(WALK_1, WALK_2, WALK_3, WALK_2)
    val STAND = WALK_2

    /** Fist raised on top, from mario_jump.png. */
    val JUMP = arrayOf(
        "............SSS.",
        ".....RRRRR..SSS.",
        "....RRRRRRRRRSS.",
        "....HHHSSKS.RRR.",
        "...HSHSSSKSSRRR.",
        "...HSHHSSSKSSRR.",
        "...HHSSSSKKKK.R.",
        ".....SSSSSSS....",
        "....RRBRRR......",
        "...RRRBRRBRRR...",
        "..RRRRBBBB.RRR..",
        "..SSRBYBBYBR....",
        "..SSSB.BBB......",
        "..SS.BBBBBB.....",
        ".....HHH.HHH....",
        "....HHHH..HHHH..",
    )

    // ---- Super Mario (16×32): the same head on a taller body -------------------------------------------------------

    private val BIG_HEAD = HEAD + arrayOf("......SSSSS.....")
    private val BIG_TORSO = arrayOf(
        "....RRRBRRR.....",
        "...RRRRBRRRR....",
        "..RRRRRBBRRRR...",
        ".RRRRRRBBBRRRR..",
        ".RRRRRBBBBBRRRR.",
        "SSRRRBBYBBYBRRSS",
        "SSSRBBBBBBBBRSSS",
        "SSS.BBBBBBBBBSSS",
        "SS..BBBBBBBBB.SS",
        "....BBBBBBBBBB..",
        "...BBBBBBBBBBB..",
        "...BBBBBBBBBBB..",
        "...BBBBB.BBBBB..",
    )
    /** The torso with the right hand gone up into the raised fist. */
    private val BIG_TORSO_JUMP = BIG_TORSO.copyOfRange(0, 5) + arrayOf(
        "SSRRRBBYBBYBRR..",
        "SSSRBBBBBBBBR...",
        "SSS.BBBBBBBBB...",
        "SS..BBBBBBBBB...",
    ) + BIG_TORSO.copyOfRange(9, 13)
    private val BIG_LEGS_STAND = Array(8) { "..BBBBB..BBBBB.." } + arrayOf(
        "..HHHHH..HHHHH..",
        ".HHHHHH..HHHHHH.",
        ".HHHHHH..HHHHHHH",
    )
    private val BIG_LEGS_STRIDE = arrayOf(
        "...BBBB..BBBB...",
        "..BBBB....BBBB..",
        "..BBBB....BBBB..",
        ".BBBB......BBBB.",
        ".BBBB......BBBB.",
        "BBBB........BBBB",
        "BBBB........BBBB",
        "BBB..........BBB",
        "HHHH.......HHHHH",
        "HHHH.......HHHHH",
        "HHH........HHHHH",
    )
    private val BIG_LEGS_PASS = arrayOf(
        "....BBBBBBBB....",
        "....BBBBBBBB....",
    ) + Array(6) { ".....BBBBBB....." } + arrayOf(
        "....HHHHHHHH....",
        "...HHHHHHHHHH...",
        "...HHHHHHHHHH...",
    )
    private val BIG_WALK_1 = BIG_HEAD + BIG_TORSO + BIG_LEGS_STRIDE
    private val BIG_WALK_2 = BIG_HEAD + BIG_TORSO + BIG_LEGS_STAND
    private val BIG_WALK_3 = BIG_HEAD + BIG_TORSO + BIG_LEGS_PASS
    val BIG_WALK_CYCLE = arrayOf(BIG_WALK_1, BIG_WALK_2, BIG_WALK_3, BIG_WALK_2)
    val BIG_STAND = BIG_WALK_2
    val BIG_JUMP = JUMP.copyOfRange(0, 8) + BIG_TORSO_JUMP + BIG_LEGS_STRIDE

    // ---- Enemies (face left, towards Mario) -----------------------------------------------------------------------

    /** Mirrored every 200 ms so its feet shuffle. */
    val GOOMBA = arrayOf(
        "...NNNN...",
        ".NNNNNNNN.",
        "NKWNNNNWKN",
        "NNNNNNNNNN",
        ".NNTTTTNN.",
        "..TTTTTT..",
        ".KKTT.TKK.",
        "KKK...KKK.",
    )
    val GOOMBA_FLAT = arrayOf(
        ".NNNNNNNN.",
        "NKWNNNNWKN",
        "KKK....KKK",
    )
    val KOOPA = arrayOf(
        ".UU.......",
        "UUUU......",
        "UKUU......",
        "UUUU......",
        ".UU.GGGG..",
        ".UUGLGLGG.",
        "..UGGLGLGG",
        "..UWGGGGGW",
        "...WWWWWWW",
        "...UU..UU.",
        "..UUU.UUU.",
    )
    /** A stomped Koopa: the shell slides away. */
    val SHELL = arrayOf(
        "...GGGG...",
        "..GLGLGG..",
        ".GLGLGLGG.",
        ".GGLGLGGG.",
        "WWWWWWWWWW",
        ".WWWWWWWW.",
    )
    /** Rises out of its pipe and ducks back in. */
    val PIRANHA = arrayOf(
        "GG....GG",
        "GWG..GWG",
        "GGGWWGGG",
        ".GWGGWG.",
        "..GGGG..",
        "...DD...",
        "LL.DD.LL",
        "LLLDDLLL",
        ".LLDDLL.",
        "...DD...",
    )

    // ---- Ground obstacles -----------------------------------------------------------------------------------------

    /** A short pipe, smaller than Mario so he clears it with one jump or lands on top. */
    val PIPE = arrayOf(
        "KKKKKKKKKK",
        "KLLGGGGDDK",
        "KKKKKKKKKK",
        ".KLGGGGDK.",
        ".KLGGGGDK.",
        ".KLGGGGDK.",
    )
    /** Spike trap: never stood on, always jumped. */
    val SPIKE = arrayOf(
        "....K....",
        ".K.KWK.K.",
        "..KWWWK..",
        "KKWWKWWKK",
        "..KWWWK..",
        ".K.KWK.K.",
        "....K....",
    )
    val HARD_BLOCK = arrayOf(
        "TTTTTTTK",
        "TTTTTTKK",
        "TTNNNNKK",
        "TTNNNNKK",
        "TTNNNNKK",
        "TTNNNNKK",
        "TKKKKKKK",
        "KKKKKKKK",
    )
    val BRICK_BLOCK = arrayOf(
        "QQQKQQQQ",
        "QQQKQQQQ",
        "KKKKKKKK",
        "QQQQQQKQ",
        "QQQQQQKQ",
        "KKKKKKKK",
        "QQQKQQQQ",
        "QQQKQQQQ",
    )
    val ICE_BLOCK = arrayOf(
        "KKKKKKKK",
        "KCCCCCWK",
        "KCCCCWCK",
        "KCCCWCCK",
        "KCCWCCCK",
        "KCWCCCCK",
        "KWCCCCCK",
        "KKKKKKKK",
    )
    /** Springs Mario up when he lands on it. */
    val NOTE_BLOCK = arrayOf(
        "KKKKKKKK",
        "KWWWWWWK",
        "KWWWWKWK",
        "KWWWWKWK",
        "KWWWWKWK",
        "KWKKKKWK",
        "KWWWWWWK",
        "KKKKKKKK",
    )
    /** Drops away a moment after Mario stands on it. */
    val DONUT_BLOCK = arrayOf(
        ".TTTTTT.",
        "TTNNNNTT",
        "TN.NN.NT",
        "TNN..NNT",
        "TNN..NNT",
        "TN.NN.NT",
        "TTNNNNTT",
        ".TTTTTT.",
    )

    // ---- Items from the ? blocks ----------------------------------------------------------------------------------

    val MUSHROOM = arrayOf(
        "...RRRR...",
        ".RWWRRWWR.",
        "RWWWRRWWWR",
        "RRWRRRRWRR",
        "RRRRWWRRRR",
        "RRRWWWWRRR",
        ".TTTTTTTT.",
        ".TTKTTKTT.",
        ".TTKTTKTT.",
        "..TTTTTT..",
    )
    val ONE_UP = Array(MUSHROOM.size) { MUSHROOM[it].replace('R', 'G') }
    val FLOWER = arrayOf(
        "..OOOOOO..",
        ".OWWWWWWO.",
        "OWRRRRRRWO",
        ".OWWWWWWO.",
        "..OOOOOO..",
        "....GG....",
        "LL..GG..LL",
        ".LLLGGLLL.",
        "..LLGGLL..",
        "....GG....",
    )
    val STAR = arrayOf(
        "....OO....",
        "....OO....",
        "...OOOO...",
        "OOOOOOOOOO",
        ".OOOKOKOO.",
        "..OOKOKO..",
        "..OOOOOO..",
        ".OOOOOOOO.",
        ".OOO..OOO.",
        "OO......OO",
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
