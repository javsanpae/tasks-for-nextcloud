// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.domain

import app.nctasks.data.ical.ICalComponent
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * An alert on a task, stored as a VALARM. Equality ignores [raw], the original component kept so
 * unchanged alerts are written back exactly as other clients wrote them.
 */
sealed class Reminder {
    abstract val raw: ICalComponent?

    /**
     * Fires [offset] after (positive) or before (negative) the task's date. For all-day tasks the date
     * counts from 00:00 local time, so "09:00 on the day" is `+9h` and "1 day before at 09:00" is `-15h`.
     * [fromStart] anchors to the start date; otherwise to the due date (falling back to the start date).
     */
    class Relative(val offset: Duration, val fromStart: Boolean = false, override val raw: ICalComponent? = null) : Reminder() {
        override fun equals(other: Any?) = other is Relative && other.offset == offset && other.fromStart == fromStart
        override fun hashCode() = offset.hashCode() * 31 + fromStart.hashCode()
        override fun toString() = "Relative($offset${if (fromStart) ", start" else ""})"
    }

    /** A fixed point in time. Read from other clients; shown and removable, not creatable here. */
    class Absolute(val at: Instant, override val raw: ICalComponent? = null) : Reminder() {
        override fun equals(other: Any?) = other is Absolute && other.at == at
        override fun hashCode() = at.hashCode()
        override fun toString() = "Absolute($at)"
    }

    companion object {
        /** Default time of day for alerts on all-day tasks. */
        val ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0)
        const val MAX_PER_TASK = 5

        /** Alert [offset] before the task's time (0 = when it is due). */
        fun before(offset: Duration) = Relative(offset.negated())

        /** All-day alert: [daysBefore] full days before the date, at [time] of day. */
        fun allDay(daysBefore: Int, time: LocalTime = ALL_DAY_TIME) =
            Relative(Duration.ofSeconds(time.toSecondOfDay().toLong()).minus(Duration.ofDays(daysBefore.toLong())))

        /**
         * Splits an all-day offset into whole days before and time of day, or null when it cannot be
         * expressed that way (it falls on or after the next day).
         */
        fun asAllDay(offset: Duration): Pair<Int, LocalTime>? {
            if (offset >= Duration.ofDays(1)) return null
            val days = if (offset.isNegative) ((offset.negated().seconds + 86_399L) / 86_400L).toInt() else 0
            val secondsOfDay = offset.seconds + days * 86_400L
            return days to LocalTime.ofSecondOfDay(secondsOfDay)
        }

        /** Re-expresses alerts when a task switches between all-day and timed so they keep their meaning. */
        fun convertAnchor(reminders: List<Reminder>, toAllDay: Boolean): List<Reminder> = reminders.map { r ->
            if (r !is Relative) return@map r
            if (toAllDay) {
                // "N minutes/hours before" -> on the day (or N whole days before) at the default time
                val days = if (r.offset.isNegative) (r.offset.negated().seconds / 86_400L).toInt() else 0
                allDay(days).let { Relative(it.offset, r.fromStart) }
            } else {
                val parts = asAllDay(r.offset)
                if (parts == null) r else Relative(Duration.ofDays(-parts.first.toLong()), r.fromStart)
            }
        }
    }
}

/** A reminder that has come due for [task] at [at]. */
data class DueReminder(val task: Task, val at: Instant)

/** Pure scheduling rules: which reminders exist in time, which are due now, and when the next one is. */
object ReminderPlanner {
    /** Reminders older than this are dropped rather than shown late (phone was off, app was force-stopped...). */
    val MAX_AGE: Duration = Duration.ofHours(24)

    private fun anchor(task: Task, fromStart: Boolean): TaskDate? =
        if (fromStart) task.start ?: task.due else task.due ?: task.start

    fun fireTime(task: Task, reminder: Reminder, zone: ZoneId): Instant? = when (reminder) {
        is Reminder.Absolute -> reminder.at
        is Reminder.Relative -> anchor(task, reminder.fromStart)?.let { a ->
            a.date.atTime(a.time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().plus(reminder.offset)
        }
    }

    /**
     * Fire times that can still notify. A time at or before the task's last modification never fires: such
     * an alert was set up after its moment had passed (e.g. "15 minutes before" on a task due in 10 minutes),
     * and Calendar apps stay silent in that case too. Completed tasks have no reminders.
     */
    fun fireTimes(task: Task, zone: ZoneId): List<Instant> {
        if (task.isCompleted) return emptyList()
        val floor = task.modifiedAt ?: Instant.MIN
        return task.reminders.mapNotNull { fireTime(task, it, zone) }.filter { it > floor }.distinct().sorted()
    }

    /** One entry per task (its latest fire time) whose reminder became due in `(from, to]`. */
    fun due(tasks: Collection<Task>, zone: ZoneId, from: Instant, to: Instant): List<DueReminder> {
        val start = maxOf(from, to.minus(MAX_AGE))
        return tasks.mapNotNull { t ->
            fireTimes(t, zone).lastOrNull { it > start && it <= to }?.let { DueReminder(t, it) }
        }.sortedBy { it.at }
    }

    /** Earliest fire time strictly after [after]. */
    fun next(tasks: Collection<Task>, zone: ZoneId, after: Instant): Instant? =
        tasks.asSequence().flatMap { fireTimes(it, zone).asSequence() }.filter { it > after }.minOrNull()
}
