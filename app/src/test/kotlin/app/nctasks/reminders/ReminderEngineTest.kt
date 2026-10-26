// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import app.nctasks.data.ical.ICalComponent
import app.nctasks.domain.Priority
import app.nctasks.domain.Reminder
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderEngineTest {
    private val zone = ZoneId.of("UTC")
    private var now = Instant.parse("2026-04-01T08:00:00Z")

    private class Notifier : ReminderNotifier {
        val shown = mutableListOf<Pair<String, Instant>>(); val cancelled = mutableListOf<String>(); var allCancelled = false
        override fun show(task: Task, firedAt: Instant) { shown += task.url to firedAt }
        override fun cancel(taskUrl: String) { cancelled += taskUrl }
        override fun cancelAll() { allCancelled = true }
    }
    private class Alarms : AlarmScheduler {
        var armed: Instant? = null; var cancels = 0
        override fun scheduleAt(at: Instant) { armed = at }
        override fun cancel() { armed = null; cancels++ }
    }
    private class State : ReminderState {
        override var lastProcessed: Instant? = null
        override fun clear() { lastProcessed = null }
    }

    private val notifier = Notifier(); private val alarms = Alarms(); private val state = State()
    private val engine = ReminderEngine({ zone }, { now }, notifier, alarms, state)

    private fun task(url: String, dueAt: String, vararg reminders: Reminder, done: Boolean = false, modified: String = "2026-03-01T00:00:00Z"): Task {
        val due = Instant.parse(dueAt).atZone(zone)
        return Task(url, null, "l", url, url, "", done, null, Priority.NONE, null, TaskDate(due.toLocalDate(), due.toLocalTime()), null, false,
            ICalComponent("VCALENDAR"), reminders.toList(), Instant.parse(modified))
    }
    private val tenAm = "2026-04-01T10:00:00Z"
    private val quarterBefore = Reminder.before(Duration.ofMinutes(15))

    @Test fun `arms the alarm for the next reminder and never notifies for the past on first run`() {
        engine.reschedule(listOf(task("a", tenAm, quarterBefore)))
        assertEquals(Instant.parse("2026-04-01T09:45:00Z"), alarms.armed)
        assertEquals(now, state.lastProcessed)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test fun `no upcoming reminders means no alarm`() {
        engine.reschedule(listOf(task("a", tenAm), task("b", tenAm, quarterBefore, done = true)))
        assertNull(alarms.armed)
    }

    @Test fun `when the alarm fires the due reminder is shown and the next one is armed`() {
        val tasks = listOf(task("a", tenAm, quarterBefore), task("b", "2026-04-01T12:00:00Z", Reminder.before(Duration.ZERO)))
        engine.reschedule(tasks)
        now = Instant.parse("2026-04-01T09:45:00Z")
        engine.processDue(tasks)
        assertEquals(listOf("a" to Instant.parse("2026-04-01T09:45:00Z")), notifier.shown)
        assertEquals(Instant.parse("2026-04-01T12:00:00Z"), alarms.armed)
        assertEquals(now, state.lastProcessed)
    }

    @Test fun `a reminder is shown only once`() {
        val tasks = listOf(task("a", tenAm, quarterBefore))
        engine.reschedule(tasks)
        now = Instant.parse("2026-04-01T09:45:01Z"); engine.processDue(tasks)
        now = Instant.parse("2026-04-01T09:50:00Z"); engine.processDue(tasks)
        engine.processDue(tasks)
        assertEquals(1, notifier.shown.size)
        assertNull(alarms.armed)
    }

    @Test fun `several reminders for one task give one notification`() {
        val tasks = listOf(task("a", tenAm, Reminder.before(Duration.ofHours(1)), quarterBefore, Reminder.before(Duration.ZERO)))
        engine.reschedule(tasks)
        now = Instant.parse("2026-04-01T10:01:00Z"); engine.processDue(tasks)
        assertEquals(1, notifier.shown.size)
        assertEquals(Instant.parse("2026-04-01T10:00:00Z"), notifier.shown.single().second)
    }

    @Test fun `reminders added late for a moment already past stay silent`() {
        engine.reschedule(emptyList())
        now = Instant.parse("2026-04-01T09:50:00Z")
        // edited just now (modified = now) with an alert that should have fired at 09:45
        val tasks = listOf(task("a", tenAm, quarterBefore, modified = "2026-04-01T09:50:00Z"))
        engine.reschedule(tasks)
        assertNull(alarms.armed)
        now = Instant.parse("2026-04-01T09:55:00Z"); engine.processDue(tasks)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test fun `missed reminders are delivered late after a reboot but not after a day`() {
        val tasks = listOf(task("a", tenAm, Reminder.before(Duration.ZERO)))
        engine.reschedule(tasks)                                      // lastProcessed = 08:00
        now = Instant.parse("2026-04-01T10:30:00Z"); engine.processDue(tasks)   // phone was off at 10:00
        assertEquals(1, notifier.shown.size)

        val old = listOf(task("b", "2026-04-02T10:00:00Z", Reminder.before(Duration.ZERO)))
        now = Instant.parse("2026-04-03T12:00:00Z"); engine.processDue(old)     // 26 h late
        assertEquals(1, notifier.shown.size)
    }

    @Test fun `a reminder due this very second is retried a moment later instead of looping`() {
        val tasks = listOf(task("a", "2026-04-01T08:00:00Z", Reminder.before(Duration.ZERO)))   // exactly now
        engine.reschedule(listOf(task("x", tenAm)))                    // lastProcessed = 07:59:59.x
        state.lastProcessed = now.minusSeconds(1)
        engine.reschedule(tasks)
        assertEquals(now.plusSeconds(1), alarms.armed)
        now = now.plusSeconds(1); engine.processDue(tasks)
        assertEquals(1, notifier.shown.size)
    }

    @Test fun `cancelAll removes alarm, notifications and state`() {
        engine.reschedule(listOf(task("a", tenAm, quarterBefore)))
        engine.cancelAll()
        assertNull(alarms.armed); assertTrue(notifier.allCancelled); assertNull(state.lastProcessed)
    }

    @Test fun `dismiss cancels one notification`() {
        engine.dismiss("a")
        assertEquals(listOf("a"), notifier.cancelled)
    }

    @Test fun `all-day reminders fire at the chosen local time`() {
        now = Instant.parse("2026-04-01T05:00:00Z")                     // before 09:00 CEST (= 07:00Z)
        val zone = ZoneId.of("Europe/Madrid")
        val engine = ReminderEngine({ zone }, { now }, notifier, alarms, state)
        val t = Task("a", null, "l", "a", "a", "", false, null, Priority.NONE, null, TaskDate(LocalDate.of(2026, 4, 2)), null, false,
            ICalComponent("VCALENDAR"), listOf(Reminder.allDay(1, LocalTime.of(9, 0))), Instant.parse("2026-03-01T00:00:00Z"))
        engine.reschedule(listOf(t))
        assertEquals(Instant.parse("2026-04-01T07:00:00Z"), alarms.armed)   // 09:00 CEST on the 1st
    }
}
