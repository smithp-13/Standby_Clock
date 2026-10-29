package com.standby.clock

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun file(context: Context) = File(context.filesDir, "events.log")

    @Synchronized
    fun append(context: Context, line: String) {
        val f = file(context)
        if (f.length() > MAX_BYTES) f.writeText("")
        f.appendText("${fmt.format(Date())} $line\n")
    }

    @Synchronized
    fun read(context: Context, maxChars: Int = 20_000): String {
        val f = file(context)
        if (!f.exists()) return ""
        val text = f.readText()
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    @Synchronized
    fun clear(context: Context) = file(context).writeText("")
}
