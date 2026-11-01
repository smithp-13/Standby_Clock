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
    }
}
