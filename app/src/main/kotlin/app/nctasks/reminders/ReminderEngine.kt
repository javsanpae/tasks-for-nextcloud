// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import app.nctasks.domain.ReminderPlanner
import app.nctasks.domain.Task
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the rest of the app may ask of the reminder system. */
interface Reminders {
    /** Arms the single alarm for the next upcoming reminder (call whenever tasks changed). */
    fun reschedule(tasks: Collection<Task>)

    /** Shows what has come due since the last run, then arms the next alarm. */
    fun processDue(tasks: Collection<Task>)

    /** Removes the notification of [taskUrl] (it was completed or dismissed). */
    fun dismiss(taskUrl: String)

    /** Sign-out: no alarm, no notification, no state left behind. */
    fun cancelAll()
}

object NoReminders : Reminders {
    override fun reschedule(tasks: Collection<Task>) = Unit
    override fun processDue(tasks: Collection<Task>) = Unit
    override fun dismiss(taskUrl: String) = Unit
    override fun cancelAll() = Unit
}

/** Ports of [ReminderEngine]; Android implementations live in this package. */
interface ReminderNotifier {
    fun show(task: Task, firedAt: Instant)
    fun cancel(taskUrl: String)
    fun cancelAll()
}

interface AlarmScheduler {
    fun scheduleAt(at: Instant)
    fun cancel()
}

interface ReminderState {
    var lastProcessed: Instant?
    fun clear()
}

/**
 * Exactly one alarm is ever armed: the earliest upcoming reminder. When it fires, everything that is due
 * is shown and the next alarm is armed. This keeps AlarmManager usage tiny and makes the state trivially
 * recoverable (after reboot, time-zone change or an app update it is simply recomputed from the tasks).
 */
class ReminderEngine(
    private val zone: () -> ZoneId,
    private val clock: () -> Instant,
    private val notifier: ReminderNotifier,
    private val alarms: AlarmScheduler,
    private val state: ReminderState,
) : Reminders {
    @Synchronized
    override fun reschedule(tasks: Collection<Task>) {
        val now = clock()
        val after = state.lastProcessed ?: now.also { state.lastProcessed = it }   // never notify for the past on first run
        armNext(tasks, after, now)
    }

    @Synchronized
    override fun processDue(tasks: Collection<Task>) {
        val now = clock()
        val from = state.lastProcessed ?: now
        ReminderPlanner.due(tasks, zone(), from, now).forEach { notifier.show(it.task, it.at) }
        state.lastProcessed = now
        armNext(tasks, now, now)
    }

    @Synchronized
    override fun dismiss(taskUrl: String) = notifier.cancel(taskUrl)

    @Synchronized
    override fun cancelAll() {
        alarms.cancel()
        notifier.cancelAll()
        state.clear()
    }

    private fun armNext(tasks: Collection<Task>, after: Instant, now: Instant) {
        val next = ReminderPlanner.next(tasks, zone(), after)
        if (next == null) alarms.cancel() else alarms.scheduleAt(maxOf(next, now.plus(MIN_DELAY)))
    }

    private companion object {
        /** An alarm for "now" would fire before the clock reaches the reminder; retry a moment later instead. */
        val MIN_DELAY: Duration = Duration.ofSeconds(1)
    }
}
