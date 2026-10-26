// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.nctasks.data.ical.ICalComponent
import app.nctasks.domain.Priority
import app.nctasks.domain.Reminder
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidRemindersTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 4, 1, 9, 0)

    private fun task(title: String = "Pay rent", due: TaskDate? = TaskDate(LocalDate.of(2026, 4, 1), LocalTime.of(18, 0)), notes: String = "") =
        Task("https://c/l/pay.ics", "\"e\"", "https://c/l/", "pay", title, notes, false, null, Priority.NONE, null, due, null, false,
            ICalComponent("VCALENDAR"), listOf(Reminder.before(Duration.ofMinutes(15))), Instant.parse("2026-03-01T00:00:00Z"))

    @Test fun `notification shows the task title and when it is due`() {
        val n = ReminderNotifications.build(context, task(), Instant.parse("2026-04-01T17:45:00Z"), zone, now)
        assertEquals("Pay rent", n.extras.getString(Notification.EXTRA_TITLE))
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("Due Today, 6:00"))
        assertEquals(ReminderNotifications.CHANNEL_ID, n.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertEquals(Instant.parse("2026-04-01T17:45:00Z").toEpochMilli(), n.`when`)
    }

    @Test fun `overdue, date-only and dateless tasks are described sensibly`() {
        val overdue = ReminderNotifications.build(context, task(due = TaskDate(LocalDate.of(2026, 3, 30))), Instant.now(), zone, now)
        assertTrue(overdue.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Overdue · "))
        val allDay = ReminderNotifications.build(context, task(due = TaskDate(LocalDate.of(2026, 4, 2))), Instant.now(), zone, now)
        assertEquals("Due Tomorrow", allDay.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val none = ReminderNotifications.build(context, task(title = "", due = null, notes = "Call mum\nsecond line"), Instant.now(), zone, now)
        assertEquals("Untitled task", none.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Call mum", none.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun `has a Complete button wired to the non-exported receiver`() {
        val n = ReminderNotifications.build(context, task(), Instant.now(), zone, now)
        val action = n.actions.single()
        assertEquals("Complete", action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(ReminderReceiver::class.java.name, intent.component!!.className)
        assertEquals(ReminderNotifications.ACTION_COMPLETE, intent.action)
        assertEquals("https://c/l/pay.ics", intent.getStringExtra(ReminderNotifications.EXTRA_TASK_URL))
        assertTrue("immutable", shadowOf(action.actionIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun `lock screen shows no task details`() {
        val n = ReminderNotifications.build(context, task(), Instant.now(), zone, now)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion!!
        assertEquals("Task reminder", public.extras.getString(Notification.EXTRA_TITLE))
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun `notifier posts one notification per task, replaces it, and respects the permission`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notifier = AndroidReminderNotifier(context) { zone }
        notifier.show(task(), Instant.now())
        assertEquals("not granted -> nothing shown", 0, shadowOf(nm).allNotifications.size)

        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(task(), Instant.now()); notifier.show(task(title = "Pay rent again"), Instant.now())
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals("Pay rent again", shadowOf(nm).allNotifications.single().extras.getString(Notification.EXTRA_TITLE))

        notifier.cancel(task().url)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        notifier.show(task(), Instant.now()); notifier.cancelAll()
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Suppress("DEPRECATION") // Robolectric only exposes these as (deprecated) fields
    @Test fun `alarm scheduler keeps exactly one alarm pointing at the receiver`() {
        val am = context.getSystemService(AlarmManager::class.java)
        val alarms = AndroidAlarmScheduler(context)
        alarms.scheduleAt(Instant.parse("2026-04-01T09:45:00Z"))
        alarms.scheduleAt(Instant.parse("2026-04-02T10:00:00Z"))   // replaces, does not add
        val scheduled = shadowOf(am).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(Instant.parse("2026-04-02T10:00:00Z").toEpochMilli(), scheduled.single().triggerAtMs)
        assertTrue(scheduled.single().allowWhileIdle)
        val intent = shadowOf(scheduled.single().operation).savedIntent
        assertEquals(ReminderReceiver::class.java.name, intent.component!!.className)
        assertEquals(ReminderReceiver.ACTION_FIRE, intent.action)
        alarms.cancel()
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test fun `still schedules when exact alarms were revoked`() {
        val am = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AndroidAlarmScheduler(context).scheduleAt(Instant.parse("2026-04-01T09:45:00Z"))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun `state remembers when reminders were last processed and nothing else`() {
        val state = PrefsReminderState(context)
        assertNull(state.lastProcessed)
        state.lastProcessed = Instant.parse("2026-04-01T09:00:00Z")
        assertEquals(Instant.parse("2026-04-01T09:00:00Z"), PrefsReminderState(context).lastProcessed)
        state.clear()
        assertNull(PrefsReminderState(context).lastProcessed)
        assertNotNull(context)
    }

    @Test fun `the manifest receiver is not exported and handles reboot and clock changes`() {
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, ReminderReceiver::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(false, info.exported)
        val actions = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
        assertTrue(actions.any { it.activityInfo.name == ReminderReceiver::class.java.name })
        for (a in listOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            assertTrue(a, context.packageManager.queryBroadcastReceivers(Intent(a), 0).any { it.activityInfo.name == ReminderReceiver::class.java.name })
        }
    }
}
