// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nctasks.NcTasksApp
import app.nctasks.data.repo.Snapshot
import app.nctasks.domain.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Not exported: only our own alarm, the notification's Complete button and system broadcasts that mean
 * "the alarm may be gone or wrong" (boot, app update, clock or time zone change) reach it.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NcTasksApp
        val pending = goAsync()   // null only when invoked outside a real broadcast (tests)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeout(BUDGET_MS) { handle(app, intent) }
            } catch (_: TimeoutCancellationException) {
                // The queue and the cache are persisted; the next run / background sync picks it up.
            } finally {
                pending?.finish()
            }
        }
    }

    internal suspend fun handle(app: NcTasksApp, intent: Intent) {
        val container = app.container
        val session = container.session.value ?: return          // signed out: nothing to remind about
        val repo = session.repository
        repo.load()
        if (intent.action == ReminderNotifications.ACTION_COMPLETE) {
            val url = intent.getStringExtra(ReminderNotifications.EXTRA_TASK_URL) ?: return
            container.reminders.dismiss(url)
            val task = repo.snapshot.value.allTasks().firstOrNull { it.url == url }
            if (task != null && !task.isCompleted) {
                repo.setCompleted(task, true)          // local + queued; a repeating task moves to its next date
                container.backgroundSync.flush()       // push it even if this process is killed right after
            }
            container.reminders.reschedule(repo.snapshot.value.allTasks())
        } else {
            container.reminders.processDue(repo.snapshot.value.allTasks())
        }
    }

    companion object {
        const val ACTION_FIRE = "app.nctasks.action.REMINDER_FIRE"
        private const val BUDGET_MS = 8_000L
    }
}

internal fun Snapshot.allTasks(): List<Task> = tasksByList.values.flatten()
