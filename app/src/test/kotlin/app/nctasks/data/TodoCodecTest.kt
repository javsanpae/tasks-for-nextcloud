// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.ICalWriter
import app.nctasks.data.ical.TodoCodec
import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TodoCodecTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = Instant.parse("2025-03-01T12:00:00Z")
    private val codec = TodoCodec(zone) { now }

    private fun ics(vararg todoLines: String) =
        ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nextcloud Tasks\r\nBEGIN:VTODO\r\nUID:abc\r\n" +
            todoLines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n")

    private fun decode(text: String): Task = codec.decode("https://x/t.ics", "\"e\"", "https://x/l/", ICalParser.parse(text))!!
    private fun roundTrip(c: app.nctasks.data.ical.ICalComponent) = ICalParser.parse(ICalWriter.write(c)).component("VTODO")!!

    @Test fun `decodes a typical Nextcloud task`() {
        val t = decode(ics("SUMMARY:Buy milk\\, eggs", "DESCRIPTION:L1\\nL2", "STATUS:NEEDS-ACTION", "PRIORITY:1",
            "DUE;TZID=Europe/Madrid:20250310T180000", "RRULE:FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("Buy milk, eggs", t.title)
        assertEquals("L1\nL2", t.notes)
        assertFalse(t.isCompleted)
        assertEquals(Priority.HIGH, t.priority)
        assertEquals(TaskDate(LocalDate.of(2025, 3, 10), LocalTime.of(18, 0)), t.due)
        assertNotNull(t.recurrence)
        assertFalse(t.hasUnsupportedRecurrence)
    }

    @Test fun `converts UTC and foreign zones into the device zone`() {
        assertEquals(TaskDate(LocalDate.of(2025, 3, 10), LocalTime.of(10, 0)), decode(ics("DUE:20250310T090000Z")).due) // CET = UTC+1
        assertEquals(TaskDate(LocalDate.of(2025, 3, 10), LocalTime.of(14, 0)), decode(ics("DUE;TZID=America/New_York:20250310T090000")).due) // EDT (UTC-4) = 13:00Z = 14:00 CET
    }

    @Test fun `date only and floating values`() {
        assertEquals(TaskDate(LocalDate.of(2025, 3, 10)), decode(ics("DUE;VALUE=DATE:20250310")).due)
        assertEquals(TaskDate(LocalDate.of(2025, 3, 10), LocalTime.of(9, 30)), decode(ics("DUE:20250310T093000")).due)
    }

    @Test fun `completed detection`() {
        assertTrue(decode(ics("STATUS:COMPLETED")).isCompleted)
        assertTrue(decode(ics("PERCENT-COMPLETE:100")).isCompleted)
        assertFalse(decode(ics("STATUS:IN-PROCESS", "PERCENT-COMPLETE:40")).isCompleted)
    }

    @Test fun `unsupported rrule is flagged and not lost`() {
        val t = decode(ics("DTSTART:20250101T100000", "RRULE:FREQ=HOURLY;INTERVAL=3"))
        assertNull(t.recurrence)
        assertTrue(t.hasUnsupportedRecurrence)
        val updated = roundTrip(codec.applyDraft(t, TaskDraft.from(t).copy(title = "x")))
        assertEquals("FREQ=HOURLY;INTERVAL=3", updated.property("RRULE")!!.value)
    }

    @Test fun `create produces a valid new task`() {
        val draft = TaskDraft("Pay rent", "monthly", Priority.MEDIUM, null, TaskDate(LocalDate.of(2025, 4, 1)),
            Recurrence.parse("FREQ=MONTHLY"))
        val todo = roundTrip(codec.create("uid-1", draft))
        assertEquals("uid-1", todo.property("UID")!!.value)
        assertEquals("Pay rent", todo.property("SUMMARY")!!.value)
        assertEquals("NEEDS-ACTION", todo.property("STATUS")!!.value)
        assertEquals("5", todo.property("PRIORITY")!!.value)
        assertEquals("DATE", todo.property("DUE")!!.param("VALUE"))
        assertEquals("20250401", todo.property("DUE")!!.value)
        assertEquals("FREQ=MONTHLY", todo.property("RRULE")!!.value)
        assertEquals("20250301T120000Z", todo.property("DTSTAMP")!!.value)
        assertNull(todo.property("DTSTART"))
        assertNull(todo.property("DESCRIPTION")?.takeIf { it.value.isEmpty() })
    }

    @Test fun `timed new tasks are floating local times`() {
        val todo = roundTrip(codec.create("u", TaskDraft("t", due = TaskDate(LocalDate.of(2025, 4, 1), LocalTime.of(9, 5)))))
        assertEquals("20250401T090500", todo.property("DUE")!!.value)
        assertNull(todo.property("DUE")!!.param("TZID"))
    }

    @Test fun `editing preserves foreign data and untouched properties`() {
        val original = ics("SUMMARY:Old", "DUE;TZID=Europe/Madrid:20250310T180000", "X-APPLE-SORT-ORDER:42", "RELATED-TO:parent",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM")
        val t = decode(original)
        val todo = roundTrip(codec.applyDraft(t, TaskDraft.from(t).copy(title = "New")))
        assertEquals("New", todo.property("SUMMARY")!!.value)
        assertEquals("42", todo.property("X-APPLE-SORT-ORDER")!!.value)
        assertEquals("parent", todo.property("RELATED-TO")!!.value)
        assertEquals("-PT15M", todo.component("VALARM")!!.property("TRIGGER")!!.value)
        assertEquals("20250310T180000", todo.property("DUE")!!.value)
        assertEquals("Europe/Madrid", todo.property("DUE")!!.param("TZID"))
        assertEquals("1", todo.property("SEQUENCE")!!.value)
        assertEquals("20250301T120000Z", todo.property("LAST-MODIFIED")!!.value)
    }

    @Test fun `changing a UTC due time keeps UTC encoding`() {
        val t = decode(ics("DUE:20250310T090000Z"))
        val todo = roundTrip(codec.applyDraft(t, TaskDraft.from(t).copy(due = TaskDate(LocalDate.of(2025, 3, 11), LocalTime.of(11, 0)))))
        assertEquals("20250311T100000Z", todo.property("DUE")!!.value)
    }

    @Test fun `changing a zoned due time keeps the original zone`() {
        val t = decode(ics("DUE;TZID=America/New_York:20250310T090000"))
        val todo = roundTrip(codec.applyDraft(t, TaskDraft.from(t).copy(due = TaskDate(LocalDate.of(2025, 3, 10), LocalTime.of(16, 0)))))
        assertEquals("America/New_York", todo.property("DUE")!!.param("TZID"))
        assertEquals("20250310T110000", todo.property("DUE")!!.value)
    }

    @Test fun `clearing fields removes the properties`() {
        val t = decode(ics("SUMMARY:x", "DESCRIPTION:n", "PRIORITY:1", "DUE:20250310T090000Z", "RRULE:FREQ=DAILY"))
        val todo = roundTrip(codec.applyDraft(t, TaskDraft("x")))
        listOf("DESCRIPTION", "PRIORITY", "DUE", "RRULE").forEach { assertNull(it, todo.property(it)) }
    }

    @Test fun `completing a plain task`() {
        val t = decode(ics("SUMMARY:x", "DUE;VALUE=DATE:20250310"))
        val todo = roundTrip(codec.applyCompletion(t, true))
        assertEquals("COMPLETED", todo.property("STATUS")!!.value)
        assertEquals("100", todo.property("PERCENT-COMPLETE")!!.value)
        assertEquals("20250301T120000Z", todo.property("COMPLETED")!!.value)
        assertEquals("20250310", todo.property("DUE")!!.value)
    }

    @Test fun `reopening clears completion fields`() {
        val t = decode(ics("STATUS:COMPLETED", "PERCENT-COMPLETE:100", "COMPLETED:20250301T100000Z"))
        val todo = roundTrip(codec.applyCompletion(t, false))
        assertEquals("NEEDS-ACTION", todo.property("STATUS")!!.value)
        assertNull(todo.property("COMPLETED"))
        assertNull(todo.property("PERCENT-COMPLETE"))
    }

    @Test fun `completing a repeating task advances it instead`() {
        val t = decode(ics("DTSTART;VALUE=DATE:20250303", "DUE;VALUE=DATE:20250305", "RRULE:FREQ=WEEKLY;BYDAY=WE"))
        val todo = roundTrip(codec.applyCompletion(t, true))
        assertEquals("NEEDS-ACTION", todo.property("STATUS")!!.value)
        assertEquals("20250312", todo.property("DUE")!!.value)
        assertEquals("20250310", todo.property("DTSTART")!!.value)
        assertNull(todo.property("COMPLETED"))
    }

    @Test fun `advancing keeps the time of day and decrements count`() {
        val t = decode(ics("DUE:20250305T093000", "RRULE:FREQ=DAILY;COUNT=3"))
        val todo = roundTrip(codec.applyCompletion(t, true))
        assertEquals("20250306T093000", todo.property("DUE")!!.value)
        assertEquals("FREQ=DAILY;COUNT=2", todo.property("RRULE")!!.value)
    }

    @Test fun `last occurrence completes the task for real`() {
        val t = decode(ics("DUE;VALUE=DATE:20250305", "RRULE:FREQ=DAILY;COUNT=1"))
        assertEquals("COMPLETED", roundTrip(codec.applyCompletion(t, true)).property("STATUS")!!.value)
        val u = decode(ics("DUE;VALUE=DATE:20250305", "RRULE:FREQ=DAILY;UNTIL=20250305"))
        assertEquals("COMPLETED", roundTrip(codec.applyCompletion(u, true)).property("STATUS")!!.value)
    }

    @Test fun `repeating task without any date just completes`() {
        val t = decode(ics("RRULE:FREQ=DAILY"))
        assertEquals("COMPLETED", roundTrip(codec.applyCompletion(t, true)).property("STATUS")!!.value)
    }
}
