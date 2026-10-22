package com.audiosrt.app.subtitle

import java.util.Locale
import kotlin.math.roundToLong

object SrtWriter {

    fun write(cues: List<Cue>): String = buildString {
        var index = 1
        for (c in cues) {
            val text = c.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (text.isEmpty()) continue
            append(index++).append('\n')
            append(timestamp(c.start)).append(" --> ").append(timestamp(c.end)).append('\n')
            append(text).append("\n\n")
        }
    }

    /** 3725.5 -> "01:02:05,500" */
    fun timestamp(seconds: Double): String {
        val ms = (seconds.coerceAtLeast(0.0) * 1000).roundToLong()
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val r = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r)
    }
}
