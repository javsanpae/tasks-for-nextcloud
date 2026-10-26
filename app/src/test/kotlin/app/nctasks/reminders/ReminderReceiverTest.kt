// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.nctasks.NcTasksApp
import app.nctasks.background.BackgroundSync
import app.nctasks.data.AppContainer
import app.nctasks.data.auth.CredentialStore
import app.nctasks.data.auth.Credentials
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.TodoCodec
import app.nctasks.data.repo.InMemoryLocalStore
import app.nctasks.data.repo.PendingOp
import app.nctasks.data.repo.PersistedState
import app.nctasks.data.repo.StoredTask
import app.nctasks.domain.Task
import app.nctasks.domain.TaskList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** Replaces the production container so the receiver can be driven without a server or the Keystore. */
class TestApp : NcTasksApp() {
    override val container: AppContainer get() = current

    companion object { lateinit var current: AppContainer }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class ReminderReceiverTest {
    private class FakeCredentials(private var creds: Credentials?) : CredentialStore {
        override fun load() = creds
        override fun save(credentials: Credentials) { creds = credentials }
        override fun loadSelectedList(): String? = null
        override fun saveSelectedList(url: String?) = Unit
        override fun clear() { creds = null }
    }
    private class RecordingReminders : Reminders {
        val processed = mutableListOf<List<String>>(); val rescheduled = mutableListOf<List<String>>(); val dismissed = mutableListOf<String>()
        override fun reschedule(tasks: Collection<Task>) { rescheduled += tasks.map { it.title } }
        override fun processDue(tasks: Collection<Task>) { processed += tasks.map { it.title } }
        override fun dismiss(taskUrl: String) { dismissed += taskUrl }
        override fun cancelAll() = Unit
    }
    private class RecordingSync : BackgroundSync {
        var flushes = 0
        override fun start() = Unit
        override fun stop() = Unit
        override fun flush() { flushes++ }
    }

    private val zone = ZoneId.of("UTC")
    private val list = TaskList("https://cloud.example.test/dav/l/", "L", null)
    private val cache = InMemoryLocalStore()
    private val reminders = RecordingReminders()
    private val sync = RecordingSync()

    private fun ics(vararg lines: String) = ICalParser.parse(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:pay\r\nSUMMARY:Pay rent\r\nLAST-MODIFIED:20260301T000000Z\r\n" +
            lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n")

    private fun container(signedIn: Boolean = true, vararg todoLines: String): AppContainer {
        cache.save(PersistedState(listOf(list), listOf(StoredTask(list.url + "pay.ics", "\"e1\"", list.url, ics(*todoLines))), emptyList()))
        val creds = Credentials("https://cloud.example.test/".toHttpUrl(), "alice", "pw")
        return AppContainer(FakeCredentials(creds.takeIf { signedIn }), cache, reminders = reminders, backgroundSync = sync, zone = { zone })
    }

    private fun fire(action: String?, url: String? = null) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        ReminderReceiver().handle(app, Intent(action).also { if (url != null) it.putExtra(ReminderNotifications.EXTRA_TASK_URL, url) })
    }

    @Before fun reset() { TestApp.current = container(true, "DUE:20260401T180000Z", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M", "END:VALARM") }

    @Test fun `the alarm, boot and clock changes process due reminders`() {
        for (action in listOf(ReminderReceiver.ACTION_FIRE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            reminders.processed.clear()
            fire(action)
            assertEquals(action, listOf(listOf("Pay rent")), reminders.processed)
        }
    }

    @Test fun `Complete from the notification completes the task, queues the change and syncs`() {
        val url = list.url + "pay.ics"
        fire(ReminderNotifications.ACTION_COMPLETE, url)
        val task = TestApp.current.session.value!!.repository.snapshot.value.tasksByList.getValue(list.url).single()
        assertTrue(task.isCompleted)
        assertEquals(listOf(url), reminders.dismissed)
        assertEquals(1, sync.flushes)
        val ops = cache.state!!.ops
        assertTrue("change is persisted for the sync: $ops", ops.size == 1 && ops[0] is PendingOp.Update)
        assertEquals("COMPLETED", (ops[0] as PendingOp.Update).ics.component("VTODO")!!.property("STATUS")!!.value)
        assertTrue(reminders.rescheduled.isNotEmpty())
        assertTrue("no firing on a complete tap", reminders.processed.isEmpty())
    }

    @Test fun `completing a repeating task moves it to its next date instead`() {
        TestApp.current = container(true, "DUE:20260401T180000Z", "RRULE:FREQ=DAILY", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M", "END:VALARM")
        fire(ReminderNotifications.ACTION_COMPLETE, list.url + "pay.ics")
        val task = TestApp.current.session.value!!.repository.snapshot.value.tasksByList.getValue(list.url).single()
        assertFalse(task.isCompleted)
        assertEquals(java.time.LocalDate.of(2026, 4, 2), task.due!!.date)
        assertEquals(1, task.reminders.size)
    }

    @Test fun `an unknown or already completed task is left alone`() {
        fire(ReminderNotifications.ACTION_COMPLETE, "https://cloud.example.test/dav/l/nope.ics")
        assertEquals(0, sync.flushes)
        assertTrue(cache.state!!.ops.isEmpty())
    }

    @Test fun `when signed out nothing happens`() {
        TestApp.current = container(signedIn = false)
        fire(ReminderReceiver.ACTION_FIRE)
        fire(ReminderNotifications.ACTION_COMPLETE, list.url + "pay.ics")
        assertTrue(reminders.processed.isEmpty() && reminders.dismissed.isEmpty())
        assertEquals(0, sync.flushes)
    }
}
