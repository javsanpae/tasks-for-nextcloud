// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.domain

import app.nctasks.data.ical.ICalComponent
import app.nctasks.data.ical.Iso8601Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderTest {
    private val zone = ZoneId.of("Europe/Madrid")   // UTC+1 in January

    private fun task(
        due: TaskDate?, vararg reminders: Reminder, start: TaskDate? = null, done: Boolean = false, modified: String? = null,
    ) = Task("u", null, "l", "uid", "T", "", done, null, Priority.NONE, start, due, null, false, ICalComponent("VCALENDAR"),
        reminders.toList(), modified?.let(Instant::parse))

    private val at9 = TaskDate(LocalDate.of(2026, 1, 15), LocalTime.of(9, 0))    // 08:00Z

    @Test fun `duration parsing and formatting`() {
        assertEquals(Duration.ofMinutes(-15), Iso8601Duration.parse("-PT15M"))
        assertEquals(Duration.ofDays(7), Iso8601Duration.parse("P1W"))
        assertEquals(Duration.ofHours(-15), Iso8601Duration.parse("-PT15H"))
        assertEquals(Duration.ofHours(-39), Iso8601Duration.parse("-P1DT15H"))
        assertEquals(Duration.ofSeconds(90), Iso8601Duration.parse("PT1M30S"))
        assertEquals(Duration.ZERO, Iso8601Duration.parse("PT0S"))
        assertNull(Iso8601Duration.parse("P"))
        assertNull(Iso8601Duration.parse("15M"))
        assertNull(Iso8601Duration.parse("-PTM"))
        listOf(Duration.ofMinutes(-15), Duration.ofHours(-15), Duration.ofDays(-2).plusHours(9), Duration.ofDays(8), Duration.ZERO, Duration.ofSeconds(-61))
            .forEach { assertEquals(it, Iso8601Duration.parse(Iso8601Duration.format(it))) }
        assertEquals("-PT15M", Iso8601Duration.format(Duration.ofMinutes(-15)))
        assertEquals("-P1DT1H", Iso8601Duration.format(Duration.ofHours(-25)))
        assertEquals("PT9H", Iso8601Duration.format(Duration.ofHours(9)))
    }

    @Test fun `all-day offsets split into days before and time of day`() {
        assertEquals(0 to LocalTime.of(9, 0), Reminder.asAllDay(Duration.ofHours(9)))
        assertEquals(1 to LocalTime.of(9, 0), Reminder.asAllDay(Duration.ofHours(-15)))
        assertEquals(2 to LocalTime.of(18, 30), Reminder.asAllDay(Duration.ofDays(-2).plusHours(18).plusMinutes(30)))
        assertEquals(1 to LocalTime.MIDNIGHT, Reminder.asAllDay(Duration.ofDays(-1)))
        assertNull(Reminder.asAllDay(Duration.ofHours(30)))
        listOf(0 to LocalTime.of(9, 0), 1 to LocalTime.of(7, 15), 7 to LocalTime.MIDNIGHT).forEach { (d, t) ->
            assertEquals(d to t, Reminder.asAllDay(Reminder.allDay(d, t).offset))
        }
    }

    @Test fun `switching between all-day and timed keeps the meaning`() {
        val toTimed = Reminder.convertAnchor(listOf(Reminder.allDay(1), Reminder.allDay(0)), toAllDay = false)
        assertEquals(listOf(Reminder.before(Duration.ofDays(1)), Reminder.before(Duration.ZERO)), toTimed)
        val toAllDay = Reminder.convertAnchor(listOf(Reminder.before(Duration.ofMinutes(15)), Reminder.before(Duration.ofDays(2))), toAllDay = true)
        assertEquals(listOf(Reminder.allDay(0), Reminder.allDay(2)), toAllDay)
        val abs = Reminder.Absolute(Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(listOf<Reminder>(abs), Reminder.convertAnchor(listOf(abs), true))
    }

    @Test fun `fire time of a timed task is relative to its due time`() {
        val t = task(at9, Reminder.before(Duration.ofMinutes(15)))
        assertEquals(Instant.parse("2026-01-15T07:45:00Z"), ReminderPlanner.fireTimes(t, zone).single())
    }

    @Test fun `fire time of an all-day task counts from local midnight`() {
        val t = task(TaskDate(LocalDate.of(2026, 1, 15)), Reminder.allDay(0), Reminder.allDay(1))
        assertEquals(listOf(Instant.parse("2026-01-14T08:00:00Z"), Instant.parse("2026-01-15T08:00:00Z")), ReminderPlanner.fireTimes(t, zone))
    }

    @Test fun `anchor falls back between start and due`() {
        val onlyStart = task(null, Reminder.before(Duration.ofMinutes(10)), start = at9)
        assertEquals(Instant.parse("2026-01-15T07:50:00Z"), ReminderPlanner.fireTimes(onlyStart, zone).single())
        val both = task(TaskDate(LocalDate.of(2026, 1, 20), LocalTime.of(9, 0)), Reminder.Relative(Duration.ZERO, fromStart = true), start = at9)
        assertEquals(Instant.parse("2026-01-15T08:00:00Z"), ReminderPlanner.fireTimes(both, zone).single())
        assertTrue(ReminderPlanner.fireTimes(task(null, Reminder.before(Duration.ZERO)), zone).isEmpty()) // no date at all
    }

    @Test fun `absolute alerts need no date`() {
        val t = task(null, Reminder.Absolute(Instant.parse("2026-02-01T10:00:00Z")))
        assertEquals(Instant.parse("2026-02-01T10:00:00Z"), ReminderPlanner.fireTimes(t, zone).single())
    }

    @Test fun `completed tasks never notify`() {
        assertTrue(ReminderPlanner.fireTimes(task(at9, Reminder.before(Duration.ZERO), done = true), zone).isEmpty())
    }

    @Test fun `an alert whose moment passed before the task was last edited stays silent`() {
        val r = Reminder.before(Duration.ofMinutes(15))   // fires 07:45Z
        assertTrue(ReminderPlanner.fireTimes(task(at9, r, modified = "2026-01-15T07:50:00Z"), zone).isEmpty())
        assertEquals(1, ReminderPlanner.fireTimes(task(at9, r, modified = "2026-01-15T07:00:00Z"), zone).size)
    }

    @Test fun `due picks one entry per task inside the window`() {
        val t = task(at9, Reminder.before(Duration.ofHours(1)), Reminder.before(Duration.ofMinutes(15)), Reminder.before(Duration.ZERO))
        val from = Instant.parse("2026-01-15T06:00:00Z")
        // 07:00Z and 07:45Z are due by 07:50Z; the notification is for the latest one
        val due = ReminderPlanner.due(listOf(t), zone, from, Instant.parse("2026-01-15T07:50:00Z"))
        assertEquals(listOf(Instant.parse("2026-01-15T07:45:00Z")), due.map { it.at })
        assertTrue(ReminderPlanner.due(listOf(t), zone, Instant.parse("2026-01-15T07:50:00Z"), Instant.parse("2026-01-15T07:55:00Z")).isEmpty()) // already processed
    }

    @Test fun `stale reminders are not shown late`() {
        val t = task(at9, Reminder.before(Duration.ZERO))   // 08:00Z
        val now = Instant.parse("2026-01-16T09:00:00Z")      // 25 h later
        assertTrue(ReminderPlanner.due(listOf(t), zone, Instant.parse("2026-01-10T00:00:00Z"), now).isEmpty())
        assertEquals(1, ReminderPlanner.due(listOf(t), zone, Instant.parse("2026-01-10T00:00:00Z"), Instant.parse("2026-01-16T07:59:00Z")).size)
    }

    @Test fun `next is the earliest future fire time over all tasks`() {
        val a = task(at9, Reminder.before(Duration.ofMinutes(15)))
        val b = task(TaskDate(LocalDate.of(2026, 1, 14)), Reminder.allDay(0))   // 08:00Z on the 14th
        val now = Instant.parse("2026-01-13T00:00:00Z")
        assertEquals(Instant.parse("2026-01-14T08:00:00Z"), ReminderPlanner.next(listOf(a, b), zone, now))
        assertEquals(Instant.parse("2026-01-15T07:45:00Z"), ReminderPlanner.next(listOf(a, b), zone, Instant.parse("2026-01-14T08:00:00Z")))
        assertNull(ReminderPlanner.next(listOf(a, b), zone, Instant.parse("2026-01-16T00:00:00Z")))
        assertNull(ReminderPlanner.next(emptyList(), zone, now))
    }

    @Test fun `equality ignores the raw component`() {
        assertEquals(Reminder.before(Duration.ofMinutes(5)), Reminder.Relative(Duration.ofMinutes(-5), false, ICalComponent("VALARM")))
        assertTrue(Reminder.before(Duration.ofMinutes(5)) != Reminder.Relative(Duration.ofMinutes(-5), true))
    }
}
