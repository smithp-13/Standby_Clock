package com.standby.clock

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Look of the standby clock and its charging frame; chosen on the main screen. */
enum class Theme(val label: String) {
    PACMAN("Pac-Man"),
    MARIO("Super Mario");

    companion object {
        private const val PREFS = "standby"
        private const val KEY = "theme"

        fun current(context: Context): Theme =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: PACMAN

        fun set(context: Context, theme: Theme) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.name).apply()

        /** Mario text: Material You's dark neutral grey, readable on the blue sky, the white clouds and the ? blocks. */
        fun marioInk(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_neutral1_800)
            else Color.rgb(0x30, 0x30, 0x30)

        fun materialAccent(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_200)
            else Color.rgb(0xD0, 0xBC, 0xFF)

        fun materialSecondary(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent2_200)
            else Color.rgb(0xCC, 0xC2, 0xDC)

        fun materialSurface(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_neutral1_900)
            else Color.rgb(0x21, 0x1F, 0x26)

        fun materialOutline(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_neutral2_600)
            else Color.rgb(0x49, 0x45, 0x4F)

        fun materialText(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_neutral1_50)
            else Color.rgb(0xE6, 0xE0, 0xE9)
    }
}
