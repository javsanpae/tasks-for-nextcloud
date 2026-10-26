// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import android.content.res.Resources
import app.nctasks.R
import app.nctasks.domain.Reminder
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Human-readable alert descriptions: "15 minutes before", "On the day at 9:00 AM", "2 days before at 9:00 AM". */
object ReminderText {
    /** [allDay]: the task has a date but no time, so offsets count from midnight. */
    fun describe(
        res: Resources,
        reminder: Reminder,
        allDay: Boolean,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when (reminder) {
        is Reminder.Absolute -> res.getString(
            R.string.alert_on_instant,
            reminder.at.atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)),
        )
        is Reminder.Relative -> {
            val allDayParts = if (allDay) Reminder.asAllDay(reminder.offset) else null
            when {
                allDayParts != null -> {
                    val (days, time) = allDayParts
                    val clock = time.format(timeFormat(locale))
                    if (days == 0) res.getString(R.string.alert_on_day, clock)
                    else res.getQuantityString(R.plurals.alert_days_before_at, days, days, clock)
                }
                reminder.offset.isZero -> res.getString(R.string.alert_at_time)
                reminder.offset.isNegative -> res.getString(R.string.alert_before, amount(res, reminder.offset.negated()))
                else -> res.getString(R.string.alert_after, amount(res, reminder.offset))
            }
        }
    }

    /** "1 week", "2 days", "3 hours", "15 minutes": the largest unit that divides the duration exactly. */
    fun amount(res: Resources, d: Duration): String {
        val minutes = d.toMinutes().coerceAtLeast(1)
        return when {
            minutes % (7 * 24 * 60) == 0L -> res.getQuantityString(R.plurals.alert_unit_weeks, (minutes / (7 * 24 * 60)).toInt(), (minutes / (7 * 24 * 60)).toInt())
            minutes % (24 * 60) == 0L -> res.getQuantityString(R.plurals.alert_unit_days, (minutes / (24 * 60)).toInt(), (minutes / (24 * 60)).toInt())
            minutes % 60 == 0L -> res.getQuantityString(R.plurals.alert_unit_hours, (minutes / 60).toInt(), (minutes / 60).toInt())
            else -> res.getQuantityString(R.plurals.alert_unit_minutes, minutes.toInt(), minutes.toInt())
        }
    }

    /** The alerts offered as one-tap choices. */
    fun presets(allDay: Boolean): List<Reminder> =
        if (allDay) listOf(Reminder.allDay(0), Reminder.allDay(1), Reminder.allDay(2), Reminder.allDay(7))
        else listOf(0L, 5, 10, 15, 30).map { Reminder.before(Duration.ofMinutes(it)) } +
            listOf(1L, 2).map { Reminder.before(Duration.ofHours(it)) } +
            listOf(1L, 2).map { Reminder.before(Duration.ofDays(it)) } +
            Reminder.before(Duration.ofDays(7))

    private fun timeFormat(locale: Locale) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

}
