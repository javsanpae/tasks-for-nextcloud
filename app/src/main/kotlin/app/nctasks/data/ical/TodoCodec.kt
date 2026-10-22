// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.ical

import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.RecurrenceCalculator
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** An iCalendar DATE / DATE-TIME value together with how it was encoded on the wire. */
sealed interface IcsDate {
    data class Date(val date: LocalDate) : IcsDate
    data class Floating(val dateTime: LocalDateTime) : IcsDate
    data class Zoned(val dateTime: LocalDateTime, val zone: ZoneId) : IcsDate
    data class Utc(val instant: Instant) : IcsDate
}

/**
 * Converts between VTODO components and [Task]s.
 *
 * Every write starts from the component the server gave us and touches only the properties whose
 * value actually changed, so foreign data (alarms, attachments, subtask links, X- properties) is
 * preserved byte for byte.
 */
class TodoCodec(
    private val zone: ZoneId,
    private val clock: () -> Instant = Instant::now,
) {
    fun decode(url: String, etag: String?, listUrl: String, calendar: ICalComponent): Task? {
        val todo = calendar.component("VTODO") ?: return null
        val uid = todo.property("UID")?.value ?: return null
        val rrule = todo.property("RRULE")?.value
        val recurrence = rrule?.let(Recurrence::parse)
        val status = todo.property("STATUS")?.value?.uppercase()
        val percent = todo.property("PERCENT-COMPLETE")?.value?.toIntOrNull()
        val completedAt = todo.property("COMPLETED")?.let(::parseDate)?.let(::toInstant)
        return Task(
            url = url,
            etag = etag,
            listUrl = listUrl,
            uid = uid,
            title = todo.property("SUMMARY")?.value?.let(ICalText::unescape).orEmpty(),
            notes = todo.property("DESCRIPTION")?.value?.let(ICalText::unescape).orEmpty(),
            isCompleted = status == "COMPLETED" || percent == 100 || (status == null && completedAt != null),
            completedAt = completedAt,
            priority = Priority.fromIcal(todo.property("PRIORITY")?.value?.toIntOrNull() ?: 0),
            start = todo.property("DTSTART")?.let(::parseDate)?.let(::toTaskDate),
            due = todo.property("DUE")?.let(::parseDate)?.let(::toTaskDate),
            recurrence = recurrence,
            hasUnsupportedRecurrence = rrule != null && recurrence == null,
            ical = calendar,
        )
    }

    fun create(uid: String, draft: TaskDraft): ICalComponent {
        val now = clock()
        var todo = ICalComponent(
            "VTODO",
            listOf(
                prop("UID", uid),
                prop("CREATED", formatUtc(now)),
                prop("DTSTAMP", formatUtc(now)),
                prop("LAST-MODIFIED", formatUtc(now)),
                prop("STATUS", "NEEDS-ACTION"),
            ),
        )
        todo = applyDraftTo(todo, draft, previous = null)
        return ICalComponent(
            "VCALENDAR",
            listOf(prop("VERSION", "2.0"), prop("PRODID", PRODID), prop("CALSCALE", "GREGORIAN")),
            listOf(todo),
        )
    }

    fun applyDraft(task: Task, draft: TaskDraft): ICalComponent =
        mutateTodo(task.ical) { applyDraftTo(it, draft, previous = task) }

    /**
     * Marks the task completed or open again. Completing a repeating task moves it to its next
     * occurrence (dates shifted, still open) like the official Tasks app; only when the series has
     * no further occurrence is it actually completed.
     */
    fun applyCompletion(task: Task, completed: Boolean): ICalComponent = mutateTodo(task.ical) { todo ->
        val rule = task.recurrence
        val anchor = task.due ?: task.start
        if (completed && rule != null && anchor != null) {
            val next = RecurrenceCalculator.nextAfter(rule, anchor.date, anchor.date)
            if (next != null) return@mutateTodo advance(todo, task, rule, anchor, next)
        }
        setCompletionProps(todo, completed)
    }

    private fun setCompletionProps(todo: ICalComponent, completed: Boolean): ICalComponent {
        val now = clock()
        return if (completed) {
            todo.withProperty("STATUS", prop("STATUS", "COMPLETED"))
                .withProperty("PERCENT-COMPLETE", prop("PERCENT-COMPLETE", "100"))
                .withProperty("COMPLETED", prop("COMPLETED", formatUtc(now)))
        } else {
            todo.withProperty("STATUS", prop("STATUS", "NEEDS-ACTION"))
                .withProperty("PERCENT-COMPLETE", null)
                .withProperty("COMPLETED", null)
        }
    }

    /**
     * Three-way, field-level merge for a change made offline ([local], derived from [base]) that
     * meets a server copy edited by someone else in the meantime. A field changed locally wins; every
     * field the user did not touch keeps the server's value. The result starts from the server's
     * component so all foreign properties are the server's.
     */
    fun merge(server: Task, base: Task, local: Task): ICalComponent {
        fun <T> pick(b: T, l: T, s: T) = if (l != b) l else s
        val draft = TaskDraft(
            title = pick(base.title, local.title, server.title),
            notes = pick(base.notes, local.notes, server.notes),
            priority = pick(base.priority, local.priority, server.priority),
            start = pick(base.start, local.start, server.start),
            due = pick(base.due, local.due, server.due),
            recurrence = pick(base.recurrence, local.recurrence, server.recurrence),
        )
        val completed = pick(base.isCompleted, local.isCompleted, server.isCompleted)
        return mutateTodo(server.ical) { todo ->
            val withFields = applyDraftTo(todo, draft, previous = server)
            if (completed != server.isCompleted) setCompletionProps(withFields, completed) else withFields
        }
    }

    private fun advance(todo: ICalComponent, task: Task, rule: Recurrence, anchor: TaskDate, next: LocalDate): ICalComponent {
        val days = ChronoUnit.DAYS.between(anchor.date, next)
        fun shift(date: TaskDate?) = date?.copy(date = date.date.plusDays(days))
        var t = todo
        t = setDate(t, "DTSTART", shift(task.start), task.ical.todo().property("DTSTART")?.let(::parseDate))
        t = setDate(t, "DUE", shift(task.due), task.ical.todo().property("DUE")?.let(::parseDate))
        if (rule.count != null) {
            t = t.withProperty("RRULE", prop("RRULE", rule.copy(count = rule.count - 1).toRRule()))
        }
        return t.withProperty("STATUS", prop("STATUS", "NEEDS-ACTION"))
            .withProperty("PERCENT-COMPLETE", null)
            .withProperty("COMPLETED", null)
    }

    private fun applyDraftTo(todo: ICalComponent, draft: TaskDraft, previous: Task?): ICalComponent {
        var t = todo
        val title = draft.title.trim()
        if (previous == null || title != previous.title) t = t.withProperty("SUMMARY", prop("SUMMARY", ICalText.escape(title)))
        if (previous == null || draft.notes != previous.notes) {
            t = t.withProperty("DESCRIPTION", draft.notes.takeIf { it.isNotBlank() }?.let { prop("DESCRIPTION", ICalText.escape(it)) })
        }
        if (previous == null || draft.priority != previous.priority) {
            t = t.withProperty("PRIORITY", draft.priority.takeIf { it != Priority.NONE }?.let { prop("PRIORITY", it.ical.toString()) })
        }
        if (previous == null || draft.start != previous.start) {
            t = setDate(t, "DTSTART", draft.start, todo.property("DTSTART")?.let(::parseDate))
        }
        if (previous == null || draft.due != previous.due) {
            t = setDate(t, "DUE", draft.due, todo.property("DUE")?.let(::parseDate))
        }
        // An unsupported RRULE is never overwritten unless the user explicitly picked a new one.
        if (previous == null || draft.recurrence != previous.recurrence) {
            t = t.withProperty("RRULE", draft.recurrence?.let { prop("RRULE", it.toRRule()) })
        }
        return t
    }

    private fun setDate(todo: ICalComponent, name: String, value: TaskDate?, old: IcsDate?): ICalComponent {
        if (value == null) return todo.withProperty(name, null)
        if (old != null && toTaskDate(old) == value) return todo
        return todo.withProperty(name, encodeDate(name, value, old))
    }

    private fun encodeDate(name: String, value: TaskDate, old: IcsDate?): ICalProperty {
        val time = value.time ?: return ICalProperty(name, listOf("VALUE" to "DATE"), DATE.format(value.date))
        val local = value.date.atTime(time)
        return when (old) {
            is IcsDate.Utc -> prop(name, formatUtc(local.atZone(zone).toInstant()))
            is IcsDate.Zoned -> {
                val converted = local.atZone(zone).withZoneSameInstant(old.zone).toLocalDateTime()
                ICalProperty(name, listOf("TZID" to old.zone.id), DATE_TIME.format(converted))
            }
            else -> prop(name, DATE_TIME.format(local))
        }
    }

    private fun parseDate(p: ICalProperty): IcsDate? = runCatching {
        val v = p.value.trim()
        when {
            p.param("VALUE").equals("DATE", true) || v.length == 8 -> IcsDate.Date(LocalDate.parse(v, DATE))
            v.endsWith("Z", true) -> IcsDate.Utc(LocalDateTime.parse(v.dropLast(1), DATE_TIME).toInstant(ZoneOffset.UTC))
            else -> {
                val local = LocalDateTime.parse(v, DATE_TIME)
                val tz = p.param("TZID")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                if (tz != null) IcsDate.Zoned(local, tz) else IcsDate.Floating(local)
            }
        }
    }.getOrNull()

    private fun toTaskDate(d: IcsDate): TaskDate = when (d) {
        is IcsDate.Date -> TaskDate(d.date)
        is IcsDate.Floating -> TaskDate(d.dateTime.toLocalDate(), d.dateTime.toLocalTime().truncatedToMinutes())
        is IcsDate.Zoned -> d.dateTime.atZone(d.zone).withZoneSameInstant(zone).let { TaskDate(it.toLocalDate(), it.toLocalTime().truncatedToMinutes()) }
        is IcsDate.Utc -> ZonedDateTime.ofInstant(d.instant, zone).let { TaskDate(it.toLocalDate(), it.toLocalTime().truncatedToMinutes()) }
    }

    private fun toInstant(d: IcsDate): Instant = when (d) {
        is IcsDate.Date -> d.date.atStartOfDay(zone).toInstant()
        is IcsDate.Floating -> d.dateTime.atZone(zone).toInstant()
        is IcsDate.Zoned -> d.dateTime.atZone(d.zone).toInstant()
        is IcsDate.Utc -> d.instant
    }

    private fun LocalTime.truncatedToMinutes(): LocalTime = truncatedTo(ChronoUnit.MINUTES)

    private fun mutateTodo(calendar: ICalComponent, change: (ICalComponent) -> ICalComponent): ICalComponent {
        val old = calendar.todo()
        val now = formatUtc(clock())
        val sequence = (old.property("SEQUENCE")?.value?.toIntOrNull() ?: 0) + 1
        val updated = change(old)
            .withProperty("DTSTAMP", prop("DTSTAMP", now))
            .withProperty("LAST-MODIFIED", prop("LAST-MODIFIED", now))
            .withProperty("SEQUENCE", prop("SEQUENCE", sequence.toString()))
        return calendar.withChild(old, updated)
    }

    private fun ICalComponent.todo(): ICalComponent =
        component("VTODO") ?: throw ICalParseException("Missing VTODO")

    private fun prop(name: String, value: String) = ICalProperty(name, emptyList(), value)

    private fun formatUtc(i: Instant): String = DATE_TIME_Z.format(i.atOffset(ZoneOffset.UTC))

    companion object {
        const val PRODID = "-//nctasks//Open Source Nextcloud Tasks for Android//EN"
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val DATE_TIME_Z = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
