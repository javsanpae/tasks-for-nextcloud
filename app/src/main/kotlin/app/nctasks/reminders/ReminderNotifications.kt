// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.nctasks.MainActivity
import app.nctasks.R
import app.nctasks.domain.Task
import app.nctasks.ui.tasks.DueText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderNotifications {
    const val CHANNEL_ID = "task_reminders"
    const val ACTION_COMPLETE = "app.nctasks.action.COMPLETE_TASK"
    const val EXTRA_TASK_URL = "app.nctasks.extra.TASK_URL"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.notif_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** Stable per task, so a second reminder for the same task replaces the first instead of stacking. */
    fun idFor(taskUrl: String): Int = taskUrl.hashCode()

    /**
     * Title = the task, text = when it is due, one action to complete it without opening the app.
     * On the lock screen only a generic line is shown (task titles can be private).
     */
    fun build(context: Context, task: Task, firedAt: Instant, zone: ZoneId, now: LocalDateTime = LocalDateTime.now(zone)): Notification {
        val title = task.title.ifBlank { context.getString(R.string.notif_untitled) }
        val dueText = (task.due ?: task.start)?.let { date ->
            val label = DueText.format(context.resources, date, now)
            context.getString(if (label.isOverdue) R.string.notif_overdue else R.string.notif_due, label.text)
        } ?: task.notes.lineSequence().firstOrNull().orEmpty().ifBlank { context.getString(R.string.notif_reminder) }

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Explicit, non-exported receiver + immutable intent: nothing else can trigger or alter it.
        val complete = PendingIntent.getBroadcast(
            context, idFor(task.url),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_COMPLETE).putExtra(EXTRA_TASK_URL, task.url),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(context.getString(R.string.notif_reminder))
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(title)
            .setContentText(dueText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(dueText))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setWhen(firedAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_action_complete), complete)
            .build()
    }
}

class AndroidReminderNotifier(
    private val context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ReminderNotifier {
    @SuppressLint("MissingPermission") // checked below
    override fun show(task: Task, firedAt: Instant) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ReminderNotifications.ensureChannel(context)
        NotificationManagerCompat.from(context).notify(ReminderNotifications.idFor(task.url), ReminderNotifications.build(context, task, firedAt, zone()))
    }

    override fun cancel(taskUrl: String) = NotificationManagerCompat.from(context).cancel(ReminderNotifications.idFor(taskUrl))

    override fun cancelAll() = NotificationManagerCompat.from(context).cancelAll()
}
