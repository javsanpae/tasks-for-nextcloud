// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import android.content.res.Resources
import app.nctasks.R
import app.nctasks.domain.Priority
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TaskSorting {
    private val rank = mapOf(Priority.HIGH to 3, Priority.MEDIUM to 2, Priority.LOW to 1, Priority.NONE to 0)

    /** Open tasks: soonest due first, undated last; then priority, then title. */
    fun open(tasks: List<Task>): List<Task> = tasks.filterNot { it.isCompleted }.sortedWith(
        compareBy<Task>({ it.due == null }, { it.due?.toDateTime() }, { -rank.getValue(it.priority) }, { it.title.lowercase() }),
    )

    /** Completed tasks: most recently completed first. */
    fun completed(tasks: List<Task>): List<Task> =
        tasks.filter { it.isCompleted }.sortedWith(compareByDescending<Task> { it.completedAt }.thenBy { it.title.lowercase() })
}

data class DueLabel(val text: String, val isOverdue: Boolean)

object DueText {
    fun format(
        res: Resources,
        due: TaskDate,
        now: LocalDateTime = LocalDateTime.now(),
        locale: Locale = Locale.getDefault(),
    ): DueLabel {
        val today = now.toLocalDate()
        val dateText = when (due.date) {
            today -> res.getString(R.string.due_today)
            today.plusDays(1) -> res.getString(R.string.due_tomorrow)
            today.minusDays(1) -> res.getString(R.string.due_yesterday)
            else -> due.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        val text = due.time?.let {
            res.getString(R.string.due_with_time, dateText, it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
        } ?: dateText
        val overdue = if (due.hasTime) due.toDateTime().isBefore(now) else due.date.isBefore(today)
        return DueLabel(text, overdue)
    }
}

