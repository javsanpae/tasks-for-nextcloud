// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.ical

import java.time.Duration

/** RFC 5545 DURATION values such as `-PT15M`, `P1W`, `-P1DT9H` (nominal days/weeks are taken as 24 h). */
object Iso8601Duration {
    private val PATTERN = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    fun parse(value: String): Duration? {
        val m = PATTERN.matchEntire(value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        if (w.isEmpty() && d.isEmpty() && h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        return runCatching {
            val total = Duration.ofDays(7 * (w.toLongOrNull() ?: 0) + (d.toLongOrNull() ?: 0))
                .plusHours(h.toLongOrNull() ?: 0).plusMinutes(min.toLongOrNull() ?: 0).plusSeconds(s.toLongOrNull() ?: 0)
            if (sign == "-") total.negated() else total
        }.getOrNull()
    }

    fun format(duration: Duration): String {
        if (duration.isZero) return "PT0S"
        val abs = duration.abs()
        val total = abs.seconds   // plain arithmetic: Duration.to*Part() needs Android 12
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        val sb = StringBuilder(if (duration.isNegative) "-P" else "P")
        if (days > 0) sb.append(days).append('D')
        if (hours > 0 || minutes > 0 || seconds > 0) {
            sb.append('T')
            if (hours > 0) sb.append(hours).append('H')
            if (minutes > 0) sb.append(minutes).append('M')
            if (seconds > 0) sb.append(seconds).append('S')
        }
        return sb.toString()
    }
}
