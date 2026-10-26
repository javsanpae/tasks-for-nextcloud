// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.ICalWriter
import app.nctasks.data.ical.TodoCodec
import app.nctasks.domain.Reminder
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RemindersCodecTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val codec = TodoCodec(zone) { Instant.parse("2026-03-01T12:00:00Z") }

    private fun ics(vararg todoLines: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:abc\r\nSUMMARY:T\r\nLAST-MODIFIED:20260201T100000Z\r\n" +
            todoLines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n"

    private fun decode(text: String): Task = codec.decode("u", "\"e\"", "l", ICalParser.parse(text))!!
    private fun todoOf(c: app.nctasks.data.ical.ICalComponent) = ICalParser.parse(ICalWriter.write(c)).component("VTODO")!!
    private val due = TaskDate(LocalDate.of(2026, 4, 1), LocalTime.of(9, 0))

    @Test fun `decodes alarms written by Nextcloud, Thunderbird and this app`() {
        val t = decode(ics(
            "DUE:20260401T090000Z",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:x", "TRIGGER;RELATED=END:-PT15M", "END:VALARM",
            "BEGIN:VALARM", "ACTION:AUDIO", "TRIGGER:-P1D", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20260331T080000Z", "END:VALARM",
        ))
        assertEquals(3, t.reminders.size)
        assertEquals(Reminder.Relative(Duration.ofMinutes(-15), fromStart = false), t.reminders[0])
        assertEquals(Reminder.Relative(Duration.ofDays(-1), fromStart = true), t.reminders[1])   // no RELATED = start
        assertEquals(Reminder.Absolute(Instant.parse("2026-03-31T08:00:00Z")), t.reminders[2])
        assertEquals(Instant.parse("2026-02-01T10:00:00Z"), t.modifiedAt)
    }

    @Test fun `ignores alarms it cannot represent`() {
        val t = decode(ics("BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER:-PT5M", "SUMMARY:s", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:garbage", "END:VALARM", "BEGIN:VALARM", "ACTION:DISPLAY", "END:VALARM"))
        assertTrue(t.reminders.isEmpty())
    }

    @Test fun `new reminders are written as VALARM anchored to the due date`() {
        val draft = TaskDraft("T", due = due, reminders = listOf(Reminder.before(Duration.ofMinutes(15)), Reminder.before(Duration.ZERO), Reminder.allDay(1)))
        val todo = todoOf(codec.create("u1", draft))
        val alarms = todo.components.filter { it.name == "VALARM" }
        assertEquals(3, alarms.size)
        assertEquals("DISPLAY", alarms[0].property("ACTION")!!.value)
        assertEquals("-PT15M", alarms[0].property("TRIGGER")!!.value)
        assertEquals("END", alarms[0].property("TRIGGER")!!.param("RELATED"))
        assertEquals("PT0S", alarms[1].property("TRIGGER")!!.value)
        assertEquals("-PT15H", alarms[2].property("TRIGGER")!!.value)
        // and they read back identically
        val back = codec.decode("u", null, "l", ICalParser.parse(ICalWriter.write(codec.create("u1", draft))))!!
        assertEquals(draft.reminders, back.reminders)
    }

    @Test fun `alarm anchored to start when the task has no due date`() {
        val todo = todoOf(codec.create("u", TaskDraft("T", start = due, reminders = listOf(Reminder.before(Duration.ofMinutes(5))))))
        assertNull(todo.components.single().property("TRIGGER")!!.param("RELATED"))
    }

    @Test fun `absolute reminders are written as UTC date-time`() {
        val todo = todoOf(codec.create("u", TaskDraft("T", reminders = listOf(Reminder.Absolute(Instant.parse("2026-05-01T07:30:00Z"))))))
        val trigger = todo.components.single().property("TRIGGER")!!
        assertEquals("DATE-TIME", trigger.param("VALUE")); assertEquals("20260501T073000Z", trigger.value)
    }

    @Test fun `editing reminders keeps other alarms and unchanged ones byte for byte`() {
        val t = decode(ics(
            "DUE:20260401T090000Z",
            "BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER:-PT5M", "SUMMARY:mail me", "ATTENDEE:mailto:a@b.c", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M", "X-WR-ALARMUID:keep-me", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT1H", "END:VALARM",
        ))
        // remove the 1h alert, add a 1-day alert
        val newList = listOf(t.reminders[0], Reminder.before(Duration.ofDays(1)))
        val todo = todoOf(codec.applyDraft(t, TaskDraft.from(t).copy(reminders = newList)))
        val alarms = todo.components.filter { it.name == "VALARM" }
        assertEquals(3, alarms.size)
        assertEquals("EMAIL", alarms[0].property("ACTION")!!.value)                 // foreign alarm untouched
        assertEquals("mail me", alarms[0].property("SUMMARY")!!.value)
        assertEquals("keep-me", alarms[1].property("X-WR-ALARMUID")!!.value)        // unchanged alert kept as written
        assertEquals("-P1D", alarms[2].property("TRIGGER")!!.value)
    }

    @Test fun `saving without touching reminders does not rewrite alarms`() {
        val t = decode(ics("DUE:20260401T090000Z", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "X-FOO:bar", "END:VALARM"))
        val todo = todoOf(codec.applyDraft(t, TaskDraft.from(t).copy(title = "renamed")))
        assertEquals("bar", todo.component("VALARM")!!.property("X-FOO")!!.value)
    }

    @Test fun `removing all reminders removes only the ones we manage`() {
        val t = decode(ics("DUE:20260401T090000Z", "BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER:-PT5M", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM"))
        val todo = todoOf(codec.applyDraft(t, TaskDraft.from(t).copy(reminders = emptyList())))
        assertEquals(listOf("EMAIL"), todo.components.map { it.property("ACTION")!!.value })
    }

    @Test fun `at most five alerts are written`() {
        val many = (1..8).map { Reminder.before(Duration.ofMinutes(it.toLong())) }
        assertEquals(5, todoOf(codec.create("u", TaskDraft("T", due = due, reminders = many))).components.count { it.name == "VALARM" })
    }

    @Test fun `a reminder change made offline merges with a remote title change`() {
        val base = decode(ics("DUE:20260401T090000Z"))
        val local = codec.decode("u", null, "l", codec.applyDraft(base, TaskDraft.from(base).copy(reminders = listOf(Reminder.before(Duration.ofMinutes(10))))))!!
        val server = codec.decode("u", "\"e2\"", "l", codec.applyDraft(base, TaskDraft.from(base).copy(title = "Renamed remotely")))!!
        val merged = codec.decode("u", null, "l", codec.merge(server, base, local))!!
        assertEquals("Renamed remotely", merged.title)
        assertEquals(listOf<Reminder>(Reminder.before(Duration.ofMinutes(10))), merged.reminders)
    }

    @Test fun `completing a repeating task keeps its reminders so they fire for the next occurrence`() {
        val t = decode(ics("DUE:20260401T090000Z", "RRULE:FREQ=DAILY", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M", "END:VALARM"))
        val next = codec.decode("u", null, "l", codec.applyCompletion(t, true))!!
        assertEquals(1, next.reminders.size)
        assertEquals(LocalDate.of(2026, 4, 2), next.due!!.date)
    }
}
