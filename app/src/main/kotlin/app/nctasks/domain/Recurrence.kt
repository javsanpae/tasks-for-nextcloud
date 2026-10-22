// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A weekday in BYDAY, optionally with an ordinal ("2TU" = second Tuesday, "-1FR" = last Friday). */
data class WeekdayRule(val day: DayOfWeek, val ordinal: Int? = null)

/**
 * The subset of RFC 5545 RRULE the app can create, edit and advance. Parts it cannot interpret
 * (BYSETPOS, BYYEARDAY, ...) are kept verbatim in [extra] so they survive a round trip.
 */
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val byDay: List<WeekdayRule> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val count: Int? = null,
    val until: LocalDate? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val extra: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(interval >= 1) { "interval must be >= 1" }
        require(count == null || count >= 1) { "count must be >= 1" }
    }

    fun toRRule(): String {
        val parts = ArrayList<String>()
        parts += "FREQ=${frequency.name}"
        if (interval != 1) parts += "INTERVAL=$interval"
        if (byDay.isNotEmpty()) parts += "BYDAY=" + byDay.joinToString(",") { (it.ordinal?.toString() ?: "") + DAY_CODES.getValue(it.day) }
        if (byMonthDay.isNotEmpty()) parts += "BYMONTHDAY=" + byMonthDay.joinToString(",")
        if (byMonth.isNotEmpty()) parts += "BYMONTH=" + byMonth.joinToString(",")
        if (weekStart != DayOfWeek.MONDAY) parts += "WKST=${DAY_CODES.getValue(weekStart)}"
        count?.let { parts += "COUNT=$it" }
        until?.let { parts += "UNTIL=%04d%02d%02d".format(it.year, it.monthValue, it.dayOfMonth) }
        extra.forEach { (k, v) -> parts += "$k=$v" }
        return parts.joinToString(";")
    }

    companion object {
        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val CODE_DAYS = DAY_CODES.entries.associate { it.value to it.key }

        /** Returns null when the rule uses a frequency the app does not support (e.g. HOURLY). */
        fun parse(rrule: String): Recurrence? {
            val map = LinkedHashMap<String, String>()
            for (part in rrule.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                map[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim()
            }
            val freq = runCatching { Frequency.valueOf((map.remove("FREQ") ?: return null).uppercase()) }.getOrNull() ?: return null
            return runCatching {
                val interval = map.remove("INTERVAL")?.toInt() ?: 1
                val count = map.remove("COUNT")?.toInt()
                val until = map.remove("UNTIL")?.let(::parseUntil)
                val byDay = map.remove("BYDAY")?.split(',')?.filter { it.isNotBlank() }?.map(::parseWeekday).orEmpty()
                val byMonthDay = map.remove("BYMONTHDAY")?.split(',')?.map { it.trim().toInt() }.orEmpty()
                val byMonth = map.remove("BYMONTH")?.split(',')?.map { it.trim().toInt() }.orEmpty()
                require(byMonthDay.all { it != 0 && it in -31..31 })
                require(byMonth.all { it in 1..12 })
                val wkst = map.remove("WKST")?.let { CODE_DAYS.getValue(it.uppercase()) } ?: DayOfWeek.MONDAY
                Recurrence(freq, interval, byDay, byMonthDay, byMonth, count, until, wkst, map.toList())
            }.getOrNull()
        }

        private fun parseWeekday(token: String): WeekdayRule {
            val t = token.trim().uppercase()
            val day = CODE_DAYS.getValue(t.takeLast(2))
            val ord = t.dropLast(2).takeIf { it.isNotEmpty() }?.toInt()
            require(ord == null || (ord != 0 && ord in -53..53))
            return WeekdayRule(day, ord)
        }

        private fun parseUntil(v: String): LocalDate {
            val d = v.take(8)
            return LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
        }
    }
}

object RecurrenceCalculator {
    private const val MAX_EMPTY_PERIODS = 1000

    /** All occurrence dates of [rule] in order, the first being on or after [start]. */
    fun occurrences(rule: Recurrence, start: LocalDate): Sequence<LocalDate> = sequence {
        var produced = 0
        var period = 0L
        var emptyStreak = 0
        while (true) {
            val dates = datesInPeriod(rule, start, period).filter { !it.isBefore(start) }.sorted()
            for (d in dates) {
                if (rule.until != null && d.isAfter(rule.until)) return@sequence
                yield(d)
                produced++
                if (rule.count != null && produced >= rule.count) return@sequence
            }
            if (dates.isEmpty()) {
                if (++emptyStreak > MAX_EMPTY_PERIODS) return@sequence
            } else {
                emptyStreak = 0
            }
            period += rule.interval
        }
    }

    /** First occurrence strictly after [after], or null when the series has ended. */
    fun nextAfter(rule: Recurrence, start: LocalDate, after: LocalDate): LocalDate? =
        occurrences(rule, start).firstOrNull { it.isAfter(after) }

    private fun datesInPeriod(rule: Recurrence, start: LocalDate, period: Long): List<LocalDate> {
        val monthFilter: (LocalDate) -> Boolean = { rule.byMonth.isEmpty() || it.monthValue in rule.byMonth }
        return when (rule.frequency) {
            Frequency.DAILY -> {
                val d = start.plusDays(period)
                listOf(d).filter { matchesLimits(rule, it) }
            }
            Frequency.WEEKLY -> {
                val weekStart = start.with(TemporalAdjusters.previousOrSame(rule.weekStart)).plusWeeks(period)
                val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }
                days.map { dow -> weekStart.plusDays(((dow.value - rule.weekStart.value + 7) % 7).toLong()) }
                    .filter(monthFilter)
            }
            Frequency.MONTHLY -> {
                val ym = YearMonth.from(start).plusMonths(period)
                daysInMonth(rule, ym, start).filter(monthFilter)
            }
            Frequency.YEARLY -> {
                val year = start.year + period.toInt()
                val months = rule.byMonth.ifEmpty { listOf(start.monthValue) }
                months.flatMap { daysInMonth(rule, YearMonth.of(year, it), start) }
            }
        }
    }

    private fun matchesLimits(rule: Recurrence, d: LocalDate): Boolean =
        (rule.byMonth.isEmpty() || d.monthValue in rule.byMonth) &&
            (rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }) &&
            (rule.byMonthDay.isEmpty() || rule.byMonthDay.any { resolveMonthDay(YearMonth.from(d), it) == d.dayOfMonth })

    private fun resolveMonthDay(ym: YearMonth, n: Int): Int? {
        val len = ym.lengthOfMonth()
        val day = if (n > 0) n else len + n + 1
        return day.takeIf { it in 1..len }
    }

    private fun daysInMonth(rule: Recurrence, ym: YearMonth, start: LocalDate): List<LocalDate> {
        val fromMonthDays = rule.byMonthDay.mapNotNull { resolveMonthDay(ym, it) }.map { ym.atDay(it) }
        val fromWeekdays = rule.byDay.flatMap { wd ->
            val all = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { it.dayOfWeek == wd.day }
            when (val ord = wd.ordinal) {
                null -> all
                else -> listOfNotNull(if (ord > 0) all.getOrNull(ord - 1) else all.getOrNull(all.size + ord))
            }
        }
        return when {
            rule.byMonthDay.isNotEmpty() && rule.byDay.isNotEmpty() -> fromMonthDays.filter { d -> rule.byDay.any { it.day == d.dayOfWeek } }
            rule.byMonthDay.isNotEmpty() -> fromMonthDays
            rule.byDay.isNotEmpty() -> fromWeekdays
            else -> listOfNotNull(resolveMonthDay(ym, start.dayOfMonth)?.takeIf { it == start.dayOfMonth }?.let(ym::atDay))
        }
    }
}
