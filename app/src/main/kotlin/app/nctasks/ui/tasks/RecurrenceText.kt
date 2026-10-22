// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import android.content.res.Resources
import app.nctasks.R
import app.nctasks.domain.Frequency
import app.nctasks.domain.Recurrence
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Human-readable description of a [Recurrence], e.g. "Every 2 weeks on Mon, Wed, 5 times". */
object RecurrenceText {
    fun describe(res: Resources, r: Recurrence, locale: Locale = Locale.getDefault()): String {
        val unitPlural = when (r.frequency) {
            Frequency.DAILY -> R.plurals.repeat_unit_daily
            Frequency.WEEKLY -> R.plurals.repeat_unit_weekly
            Frequency.MONTHLY -> R.plurals.repeat_unit_monthly
            Frequency.YEARLY -> R.plurals.repeat_unit_yearly
        }
        val unitOne = when (r.frequency) {
            Frequency.DAILY -> R.string.repeat_unit_one_daily
            Frequency.WEEKLY -> R.string.repeat_unit_one_weekly
            Frequency.MONTHLY -> R.string.repeat_unit_one_monthly
            Frequency.YEARLY -> R.string.repeat_unit_one_yearly
        }
        var text = if (r.interval == 1) {
            res.getString(R.string.repeat_summary_every_one, res.getString(unitOne))
        } else {
            res.getString(R.string.repeat_summary_every_many, r.interval, res.getQuantityString(unitPlural, r.interval))
        }
        if (r.frequency == Frequency.WEEKLY && r.byDay.isNotEmpty()) {
            val days = r.byDay.sortedBy { (it.day.value - r.weekStart.value + 7) % 7 }
                .joinToString(", ") { it.day.getDisplayName(TextStyle.SHORT, locale) }
            text = res.getString(R.string.repeat_summary_on_weekdays, text, days)
        }
        if (r.frequency == Frequency.MONTHLY || r.frequency == Frequency.YEARLY) {
            val nth = r.byDay.firstOrNull { it.ordinal != null }
            if (nth != null) {
                val ordinal = ordinalName(res, nth.ordinal!!)
                text = res.getString(R.string.repeat_summary_nth_weekday, text, ordinal, nth.day.getDisplayName(TextStyle.FULL, locale))
            } else if (r.byMonthDay == listOf(-1)) {
                text = res.getString(R.string.repeat_summary_last_day, text)
            }
        }
        r.until?.let { text = res.getString(R.string.repeat_summary_until, text, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))) }
        r.count?.let { text = res.getString(R.string.repeat_summary_count, text, it) }
        return text
    }

    fun ordinalName(res: Resources, ordinal: Int): String {
        if (ordinal < 0) return res.getString(R.string.ordinal_last)
        val names = res.getStringArray(R.array.ordinals)
        return names.getOrElse(ordinal - 1) { names.last() }
    }
}
