// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.auth.Credentials
import app.nctasks.data.auth.ServerUrl
import app.nctasks.data.auth.ServerUrlResult
import app.nctasks.data.caldav.CalDavClient
import app.nctasks.data.caldav.DavException
import app.nctasks.data.caldav.HttpClients
import app.nctasks.data.ical.ICalWriter
import app.nctasks.data.ical.TodoCodec
import app.nctasks.data.repo.InMemoryLocalStore
import app.nctasks.data.repo.OfflineFirstRepository
import app.nctasks.data.repo.SyncResult
import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.Reminder
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * End-to-end check against a real Nextcloud, driving the offline-first repository over the real CalDAV
 * client. Skipped unless NC_URL, NC_USER and NC_APP_PASSWORD are set in the environment (never commit
 * them). Creates only items titled "[nctasks-test] ..." and deletes them (unless NC_KEEP=1).
 *
 *   NC_URL=cloud.example.com NC_USER=alice NC_APP_PASSWORD=xxxx ./gradlew :app:testDebugUnitTest --tests '*LiveNextcloudTest*' -i
 */
class LiveNextcloudTest {
    private val prefix = "[nctasks-test]"

    @Test fun fullLifecycle() = runBlocking {
        val url = System.getenv("NC_URL")
        val user = System.getenv("NC_USER")
        val pass = System.getenv("NC_APP_PASSWORD")
        assumeTrue("live credentials not provided", !url.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        val server = (ServerUrl.normalize(url!!) as ServerUrlResult.Valid).url
        val codec = TodoCodec(ZoneId.of("Europe/Madrid"))
        val client = CalDavClient(HttpClients.authenticated(), Credentials(server, user!!, pass!!), codec)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = OfflineFirstRepository(client, codec, InMemoryLocalStore(), scope, io = Dispatchers.Unconfined)

        repo.load()
        assertEquals(SyncResult.SUCCESS, repo.sync())
        val lists = repo.snapshot.value.lists
        println("LIVE: ${lists.size} task list(s): " + lists.joinToString { "${it.name}${if (it.canWrite) "" else " (ro)"}" })
        assertTrue("expected at least one task list", lists.isNotEmpty())
        val list = System.getenv("NC_LIST")?.let { n -> lists.first { it.name == n } } ?: lists.first { it.canWrite }
        println("LIVE: using list '${list.name}'")
        suspend fun onServer() = client.tasks(list)
        fun local(uid: String) = repo.snapshot.value.tasksByList[list.url].orEmpty().first { it.uid == uid }

        try {
            val due = LocalDate.now().plusDays(1)
            val plain = repo.create(list, TaskDraft("$prefix plain", "notes, with; special\nchars é", Priority.HIGH, null, TaskDate(due)))
            val repeating = repo.create(list, TaskDraft("$prefix weekly", due = TaskDate(due, LocalTime.of(9, 30)), recurrence = Recurrence.parse("FREQ=WEEKLY;BYDAY=${due.dayOfWeek.name.take(2)}")))
            assertEquals(2, repo.snapshot.value.pendingChanges)
            assertEquals(SyncResult.SUCCESS, repo.sync())
            assertEquals(0, repo.snapshot.value.pendingChanges)

            val onServer = onServer().associateBy { it.uid }
            val p = onServer.getValue(plain.uid)
            assertEquals("$prefix plain", p.title)
            assertEquals("notes, with; special\nchars é", p.notes)
            assertEquals(Priority.HIGH, p.priority)
            assertEquals(TaskDate(due), p.due)
            assertNotNull(onServer.getValue(repeating.uid).recurrence)
            assertNotNull("local copy picked up the server etag", local(plain.uid).etag)

            // edit -> complete -> reopen, coalesced into pushes
            repo.update(local(plain.uid), TaskDraft.from(local(plain.uid)).copy(title = "$prefix plain (edited)"))
            repo.sync()
            assertEquals("$prefix plain (edited)", onServer().first { it.uid == plain.uid }.title)
            repo.setCompleted(local(plain.uid), true); repo.sync()
            val done = onServer().first { it.uid == plain.uid }
            assertTrue(done.isCompleted); assertNotNull(done.completedAt)
            repo.setCompleted(local(plain.uid), false); repo.sync()
            assertFalse(onServer().first { it.uid == plain.uid }.isCompleted)

            // repeating: completing advances the date by a week and stays open
            repo.setCompleted(local(repeating.uid), true); repo.sync()
            val advanced = onServer().first { it.uid == repeating.uid }
            assertFalse(advanced.isCompleted)
            assertEquals(TaskDate(due.plusWeeks(1), LocalTime.of(9, 30)), advanced.due)

            // stale etag is rejected by the server
            val stale = runCatching { client.update(list.url, p.url, p.etag, ICalWriter.write(codec.applyDraft(p, TaskDraft.from(p).copy(title = "$prefix stale")))) }
            assertEquals(DavException.Kind.CONFLICT, (stale.exceptionOrNull() as? DavException)?.kind)

            // CONFLICT MERGE on the real server: another client changes notes; we change the title without having pulled
            val theirs = onServer().first { it.uid == plain.uid }
            client.update(list.url, theirs.url, theirs.etag, ICalWriter.write(codec.applyDraft(theirs, TaskDraft.from(theirs).copy(notes = "changed elsewhere"))))
            repo.update(local(plain.uid), TaskDraft.from(local(plain.uid)).copy(title = "$prefix merged title"))
            assertEquals(SyncResult.SUCCESS, repo.sync())
            val merged = onServer().first { it.uid == plain.uid }
            assertEquals("$prefix merged title", merged.title)
            assertEquals("changed elsewhere", merged.notes)
            assertEquals(Priority.HIGH, merged.priority)

            // delete + undo
            repo.delete(local(plain.uid)); repo.sync()
            assertFalse(onServer().any { it.uid == plain.uid })
            val restored = repo.restore(done.copy(url = done.url)); repo.sync()
            assertTrue(onServer().any { it.uid == restored.uid })
        } finally {
            if (System.getenv("NC_KEEP") == "1") {
                println("LIVE: NC_KEEP=1 -> leaving '$prefix' tasks in place")
            } else {
                val current: List<Task> = onServer().filter { it.title.startsWith(prefix) }
                current.forEach { runCatching { client.delete(it.url, null) } }
                println("LIVE: cleaned up ${current.size} test task(s)")
            }
            scope.cancel()
        }
    }

    private fun liveClient(): Triple<CalDavClient, TodoCodec, app.nctasks.domain.TaskList>? {
        val url = System.getenv("NC_URL"); val user = System.getenv("NC_USER"); val pass = System.getenv("NC_APP_PASSWORD")
        if (url.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) return null
        val server = (ServerUrl.normalize(url) as ServerUrlResult.Valid).url
        val codec = TodoCodec(ZoneId.of("Europe/Madrid"))
        val client = CalDavClient(HttpClients.authenticated(), Credentials(server, user, pass), codec)
        val list = runBlocking { client.taskLists().first { it.canWrite } }
        return Triple(client, codec, list)
    }

    /** Alerts written by this app must be stored as standard VALARMs that Nextcloud accepts and returns. */
    @Test fun remindersRoundTrip() = runBlocking {
        val (client, codec, list) = liveClient() ?: return@runBlocking assumeTrue("live credentials not provided", false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = OfflineFirstRepository(client, codec, InMemoryLocalStore(), scope, io = Dispatchers.Unconfined)
        repo.load(); repo.sync()
        try {
            val due = TaskDate(LocalDate.now().plusDays(2), LocalTime.of(18, 0))
            val created = repo.create(list, TaskDraft("$prefix alerts", due = due, reminders = listOf(
                Reminder.before(Duration.ofMinutes(15)), Reminder.before(Duration.ofDays(1)), Reminder.before(Duration.ZERO))))
            val allDay = repo.create(list, TaskDraft("$prefix alerts all-day", due = TaskDate(LocalDate.now().plusDays(3)), reminders = listOf(Reminder.allDay(1), Reminder.allDay(0))))
            assertEquals(SyncResult.SUCCESS, repo.sync())
            val onServer = client.tasks(list).associateBy { it.uid }
            val t = onServer.getValue(created.uid)
            println("LIVE: server stored alarms for timed task:\n" + ICalWriter.write(t.ical).lines().dropWhile { !it.startsWith("BEGIN:VALARM") }.takeWhile { it.isNotBlank() }.joinToString("\n"))
            assertEquals(setOf<Reminder>(Reminder.before(Duration.ofMinutes(15)), Reminder.before(Duration.ofDays(1)), Reminder.before(Duration.ZERO)), t.reminders.toSet())
            assertEquals(setOf<Reminder>(Reminder.allDay(1), Reminder.allDay(0)), onServer.getValue(allDay.uid).reminders.toSet())

            // editing the title must not disturb the alarms; removing one alert removes exactly that VALARM
            val local = repo.snapshot.value.tasksByList.getValue(list.url).first { it.uid == created.uid }
            repo.update(local, TaskDraft.from(local).copy(title = "$prefix alerts (renamed)", reminders = local.reminders.filterNot { it == Reminder.before(Duration.ofDays(1)) }))
            repo.sync()
            val after = client.tasks(list).first { it.uid == created.uid }
            assertEquals("$prefix alerts (renamed)", after.title)
            assertEquals(2, after.reminders.size)
        } finally {
            if (System.getenv("NC_KEEP") != "1") client.tasks(list).filter { it.title.startsWith(prefix) }.forEach { runCatching { client.delete(it.url, null) } }
            else println("LIVE: NC_KEEP=1 -> left test tasks in place")
            scope.cancel()
        }
    }

    /** Prints every "[nctasks-test]" task with its raw alarms, e.g. after adding an alert in the web UI. */
    @Test fun printTestTasks() = runBlocking {
        if (System.getenv("NC_PRINT") != "1") return@runBlocking assumeTrue("set NC_PRINT=1", false)
        val (client, _, list) = liveClient() ?: return@runBlocking assumeTrue("live credentials not provided", false)
        for (t in client.tasks(list).filter { it.title.startsWith(prefix) }) {
            val alarms = t.ical.component("VTODO")!!.components.filter { it.name == "VALARM" }
            println("LIVE: '${t.title}' due=${t.due} -> decoded reminders=${t.reminders}")
            alarms.forEach { println("LIVE:    raw VALARM: " + it.properties.joinToString(" | ") { p -> p.name + p.params.joinToString("") { (k, v) -> ";$k=$v" } + ":" + p.value }) }
        }
    }
}
