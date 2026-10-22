// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.domain.Frequency
import app.nctasks.domain.Recurrence
import app.nctasks.domain.WeekdayRule
import app.nctasks.ui.components.GroupedCard
import app.nctasks.ui.components.IosCalendar
import app.nctasks.ui.components.IosRow
import app.nctasks.ui.components.IosStepper
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.components.SectionFooter
import app.nctasks.ui.components.SectionHeader
import app.nctasks.ui.theme.Ios
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private enum class MonthlyMode { DATE, WEEKDAY, LAST_DAY }
private enum class EndMode { NEVER, AFTER, ON_DATE }

/**
 * The "Repeat" page: frequency, interval, weekdays / monthly pattern and end condition, modelled on
 * the Nextcloud Tasks web app and iOS Reminders. [anchor] is the task's date, which seeds defaults.
 */
@Composable
fun RecurrenceEditor(
    value: Recurrence?,
    anchor: LocalDate,
    onChange: (Recurrence?) -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
) {
    val res = LocalContext.current.resources
    Column(modifier.fillMaxWidth()) {
        GroupedCard(Modifier.padding(horizontal = 16.dp)) {
            val options = listOf(
                null to R.string.repeat_never,
                Frequency.DAILY to R.string.repeat_daily,
                Frequency.WEEKLY to R.string.repeat_weekly,
                Frequency.MONTHLY to R.string.repeat_monthly,
                Frequency.YEARLY to R.string.repeat_yearly,
            )
            options.forEachIndexed { i, (freq, label) ->
                CheckRow(stringResource(label), selected = value?.frequency == freq, onClick = { onChange(withFrequency(value, freq, anchor)) })
                if (i < options.lastIndex) RowDivider()
            }
        }
        if (value != null) {
            Spacer(Modifier.height(16.dp))
            GroupedCard(Modifier.padding(horizontal = 16.dp)) {
                IosRow {
                    Text(stringResource(R.string.repeat_every), style = Ios.type.body, color = Ios.colors.label)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${value.interval} " + pluralStringResource(unitPlural(value.frequency), value.interval),
                        style = Ios.type.body, color = Ios.colors.secondaryLabel,
                    )
                    Spacer(Modifier.width(12.dp))
                    IosStepper(
                        onDecrement = { onChange(value.copy(interval = (value.interval - 1).coerceAtLeast(1))) },
                        onIncrement = { onChange(value.copy(interval = (value.interval + 1).coerceAtMost(99))) },
                        canDecrement = value.interval > 1,
                        decrementLabel = stringResource(R.string.repeat_decrease),
                        incrementLabel = stringResource(R.string.repeat_increase),
                    )
                }
            }
            if (value.frequency == Frequency.WEEKLY) {
                SectionHeader(stringResource(R.string.repeat_on_days))
                GroupedCard(Modifier.padding(horizontal = 16.dp)) { WeekdayPicker(value, anchor, onChange, locale) }
            }
            if (value.frequency == Frequency.MONTHLY) {
                Spacer(Modifier.height(16.dp))
                MonthlyOptions(value, anchor, onChange, locale, res)
            }
            SectionHeader(stringResource(R.string.repeat_ends))
            EndOptions(value, anchor, onChange, locale)
            SectionFooter(RecurrenceText.describe(res, value, locale))
        }
    }
}

private fun unitPlural(f: Frequency) = when (f) {
    Frequency.DAILY -> R.plurals.repeat_unit_daily
    Frequency.WEEKLY -> R.plurals.repeat_unit_weekly
    Frequency.MONTHLY -> R.plurals.repeat_unit_monthly
    Frequency.YEARLY -> R.plurals.repeat_unit_yearly
}

private fun withFrequency(old: Recurrence?, f: Frequency?, anchor: LocalDate): Recurrence? {
    if (f == null) return null
    if (old?.frequency == f) return old
    return Recurrence(
        frequency = f,
        interval = old?.interval ?: 1,
        byDay = if (f == Frequency.WEEKLY) listOf(WeekdayRule(anchor.dayOfWeek)) else emptyList(),
        count = old?.count,
        until = old?.until,
    )
}

@Composable
private fun CheckRow(label: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    IosRow(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Ios.type.body, color = Ios.colors.label)
            if (subtitle != null) Text(subtitle, style = Ios.type.caption, color = Ios.colors.secondaryLabel)
        }
        if (selected) CheckMark()
    }
}

@Composable
private fun CheckMark() {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.10f, s * 0.55f); lineTo(s * 0.38f, s * 0.82f); lineTo(s * 0.92f, s * 0.20f)
        }
        drawPath(path, Color(0xFF007AFF), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun WeekdayPicker(value: Recurrence, anchor: LocalDate, onChange: (Recurrence?) -> Unit, locale: Locale) {
    val first = WeekFields.of(locale).firstDayOfWeek
    val selected = value.byDay.map { it.day }.toSet().ifEmpty { setOf(anchor.dayOfWeek) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0 until 7) {
            val day = DayOfWeek.of(((first.value - 1 + i) % 7) + 1)
            val on = day in selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (on) Ios.colors.blue else Ios.colors.fill)
                    .semantics { this.selected = on; contentDescription = day.getDisplayName(TextStyle.FULL, locale) }
                    .clickable(role = Role.Checkbox) {
                        val next = if (on) selected - day else selected + day
                        if (next.isNotEmpty()) onChange(value.copy(byDay = next.sortedBy { (it.value - first.value + 7) % 7 }.map { WeekdayRule(it) }))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = Ios.type.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Color.White else Ios.colors.label,
                )
            }
        }
    }
}

@Composable
private fun MonthlyOptions(value: Recurrence, anchor: LocalDate, onChange: (Recurrence?) -> Unit, locale: Locale, res: android.content.res.Resources) {
    val mode = when {
        value.byDay.any { it.ordinal != null } -> MonthlyMode.WEEKDAY
        value.byMonthDay == listOf(-1) -> MonthlyMode.LAST_DAY
        else -> MonthlyMode.DATE
    }
    val ordinal = monthlyOrdinal(anchor)
    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        CheckRow(
            stringResource(R.string.repeat_monthly_by_date), mode == MonthlyMode.DATE,
            { onChange(value.copy(byDay = emptyList(), byMonthDay = emptyList())) },
            subtitle = stringResource(R.string.repeat_monthly_day_n, anchor.dayOfMonth),
        )
        RowDivider()
        CheckRow(
            stringResource(R.string.repeat_monthly_by_weekday), mode == MonthlyMode.WEEKDAY,
            { onChange(value.copy(byDay = listOf(WeekdayRule(anchor.dayOfWeek, ordinal)), byMonthDay = emptyList())) },
            subtitle = "${RecurrenceText.ordinalName(res, ordinal)} ${anchor.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}".replaceFirstChar { it.titlecase(locale) },
        )
        RowDivider()
        CheckRow(
            stringResource(R.string.repeat_monthly_last_day), mode == MonthlyMode.LAST_DAY,
            { onChange(value.copy(byDay = emptyList(), byMonthDay = listOf(-1))) },
        )
    }
}

/** The fifth weekday of a month is expressed as "last", since "fifth" does not exist in every month. */
private fun monthlyOrdinal(date: LocalDate): Int = ((date.dayOfMonth - 1) / 7 + 1).let { if (it >= 5) -1 else it }

@Composable
private fun EndOptions(value: Recurrence, anchor: LocalDate, onChange: (Recurrence?) -> Unit, locale: Locale) {
    val mode = when {
        value.count != null -> EndMode.AFTER
        value.until != null -> EndMode.ON_DATE
        else -> EndMode.NEVER
    }
    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        CheckRow(stringResource(R.string.repeat_end_never), mode == EndMode.NEVER, { onChange(value.copy(count = null, until = null)) })
        RowDivider()
        IosRow(onClick = { if (mode != EndMode.AFTER) onChange(value.copy(count = 5, until = null)) }) {
            Text(stringResource(R.string.repeat_end_after), style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.weight(1f))
            if (mode == EndMode.AFTER) {
                val count = value.count ?: 1
                Text(pluralStringResource(R.plurals.repeat_occurrences, count, count), style = Ios.type.body, color = Ios.colors.secondaryLabel)
                Spacer(Modifier.width(12.dp))
                IosStepper(
                    onDecrement = { onChange(value.copy(count = (count - 1).coerceAtLeast(1))) },
                    onIncrement = { onChange(value.copy(count = (count + 1).coerceAtMost(999))) },
                    canDecrement = count > 1,
                    decrementLabel = stringResource(R.string.repeat_occurrences_decrease),
                    incrementLabel = stringResource(R.string.repeat_occurrences_increase),
                )
            }
        }
        RowDivider()
        IosRow(onClick = { if (mode != EndMode.ON_DATE) onChange(value.copy(count = null, until = defaultUntil(value, anchor))) }) {
            Text(stringResource(R.string.repeat_end_on), style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.weight(1f))
            if (mode == EndMode.ON_DATE) {
                val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
                Text(value.until!!.format(formatter), style = Ios.type.body, color = Ios.colors.blue)
            }
        }
        if (mode == EndMode.ON_DATE) {
            RowDivider()
            IosCalendar(selected = value.until!!, onSelect = { onChange(value.copy(until = it)) }, locale = locale)
        }
    }
}

private fun defaultUntil(value: Recurrence, anchor: LocalDate): LocalDate = when (value.frequency) {
    Frequency.DAILY -> anchor.plusWeeks(2)
    Frequency.WEEKLY -> anchor.plusMonths(2)
    Frequency.MONTHLY -> anchor.plusYears(1)
    Frequency.YEARLY -> anchor.plusYears(5)
}
