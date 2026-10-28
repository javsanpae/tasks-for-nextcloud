// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.domain

import app.nctasks.data.ical.ICalComponent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A date with an optional time of day, expressed in the device's time zone. */
data class TaskDate(val date: LocalDate, val time: LocalTime? = null) : Comparable<TaskDate> {
    val hasTime: Boolean get() = time != null

    fun toDateTime(): LocalDateTime = date.atTime(time ?: LocalTime.MAX)

    override fun compareTo(other: TaskDate): Int = toDateTime().compareTo(other.toDateTime())
}

/** Task priority as iCalendar (RFC 5545) defines it and the Nextcloud Tasks web app shows it: 0 is unset, 1 the most urgent, 9 the least. */
object Priority {
    const val NONE = 0
    const val HIGH = 1
    const val MEDIUM = 5
    const val LOW = 9
    val range = 0..9

    /** Values outside 0..9 are invalid in iCalendar and treated as unset. */
    fun fromIcal(value: Int): Int = value.takeIf { it in range } ?: NONE
}

data class TaskList(
    /** Absolute collection URL, with trailing slash. Doubles as the stable identifier. */
    val url: String,
    val name: String,
    /** ARGB, or null when the server did not provide one. */
    val color: Int?,
    val canWrite: Boolean = true,
)

data class Task(
    /** Absolute URL of the .ics resource; null for tasks that are not on the server yet. */
    val url: String,
    val etag: String?,
    val listUrl: String,
    val uid: String,
    val title: String,
    val notes: String,
    val isCompleted: Boolean,
    val completedAt: Instant?,
    val priority: Int,
    val start: TaskDate?,
    val due: TaskDate?,
    /** Null when the task does not repeat or uses a rule the app cannot represent. */
    val recurrence: Recurrence?,
    /** True when an RRULE exists that [recurrence] could not represent (kept untouched on edit). */
    val hasUnsupportedRecurrence: Boolean,
    /** The full VCALENDAR as received; edits are applied on top of it to preserve unknown data. */
    val ical: ICalComponent,
    /** Alerts that notify; those other clients wrote in a form this app cannot edit stay in [ical] untouched. */
    val reminders: List<Reminder> = emptyList(),
    /** When the task was last changed (LAST-MODIFIED); alerts due before this never fire. */
    val modifiedAt: Instant? = null,
)

/** What the editor produces; applied on top of an existing [Task] (or used to create a new one). */
data class TaskDraft(
    val title: String,
    val notes: String = "",
    val priority: Int = Priority.NONE,
    val start: TaskDate? = null,
    val due: TaskDate? = null,
    val recurrence: Recurrence? = null,
    val reminders: List<Reminder> = emptyList(),
) {
    companion object {
        fun from(task: Task) = TaskDraft(task.title, task.notes, task.priority, task.start, task.due, task.recurrence, task.reminders)
    }
}
