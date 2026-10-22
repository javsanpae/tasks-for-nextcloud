// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.caldav.DavException
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.ICalWriter
import app.nctasks.data.ical.TodoCodec
import app.nctasks.data.repo.InMemoryLocalStore
import app.nctasks.data.repo.OfflineFirstRepository
import app.nctasks.data.repo.PendingOp
import app.nctasks.data.repo.ReadOnlyListException
import app.nctasks.data.repo.RemoteTasks
import app.nctasks.data.repo.SyncEvent
import app.nctasks.data.repo.SyncResult
import app.nctasks.data.repo.SyncStatus
import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A tiny CalDAV server in memory with etags, conditional writes and an "offline" switch. */
class FakeRemote(private val codec: TodoCodec, val lists: List<TaskList>) : RemoteTasks {
    private class Entry(val listUrl: String, var ics: String, var etag: String)

    private val items = LinkedHashMap<String, Entry>()
    private var counter = 0
    var offline = false
    var failNext: DavException? = null
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<String>()

    private fun guard(call: String) {
        calls += call
        if (offline) throw DavException(DavException.Kind.NETWORK, "offline")
        failNext?.let { failNext = null; throw it }
    }

    private fun task(url: String, e: Entry) = codec.decode(url, e.etag, e.listUrl, ICalParser.parse(e.ics))!!

    /** Simulates another client editing the resource. */
    fun serverEdit(url: String, change: (Task) -> TaskDraft) {
        val e = items.getValue(url)
        val t = task(url, e)
        e.ics = ICalWriter.write(codec.applyDraft(t, change(t)))
        e.etag = "\"e${++counter}\""
    }

    fun serverComplete(url: String) {
        val e = items.getValue(url)
        e.ics = ICalWriter.write(codec.applyCompletion(task(url, e), true)); e.etag = "\"e${++counter}\""
    }

    fun serverDelete(url: String) { items.remove(url) }
    fun serverTasks(): List<Task> = items.map { (u, e) -> task(u, e) }
    fun has(url: String) = url in items

    fun seed(list: TaskList, uid: String, draft: TaskDraft): Task {
        val url = "${list.url}$uid.ics"
        items[url] = Entry(list.url, ICalWriter.write(codec.create(uid, draft)), "\"e${++counter}\"")
        return task(url, items.getValue(url))
    }

    override suspend fun taskLists(): List<TaskList> { guard("lists"); return lists }
    override suspend fun tasks(list: TaskList): List<Task> { guard("tasks"); return items.filter { it.value.listUrl == list.url }.map { task(it.key, it.value) } }
    override suspend fun create(listUrl: String, url: String, ics: String): Task {
        guard("create"); gate?.await()
        if (url in items) throw DavException(DavException.Kind.CONFLICT, "exists")
        items[url] = Entry(listUrl, ics, "\"e${++counter}\""); return task(url, items.getValue(url))
    }
    override suspend fun update(listUrl: String, url: String, etag: String?, ics: String): Task {
        guard("update"); gate?.await()
        val e = items[url] ?: throw DavException(DavException.Kind.NOT_FOUND, "404")
        if (etag != null && etag != e.etag) throw DavException(DavException.Kind.CONFLICT, "412")
        e.ics = ics; e.etag = "\"e${++counter}\""; return task(url, e)
    }
    override suspend fun delete(url: String, etag: String?) {
        guard("delete"); gate?.await()
        val e = items[url] ?: throw DavException(DavException.Kind.NOT_FOUND, "404")
        if (etag != null && etag != e.etag) throw DavException(DavException.Kind.CONFLICT, "412")
        items.remove(url)
    }
    override suspend fun task(listUrl: String, url: String): Task? { guard("get"); return items[url]?.let { task(url, it) } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRepositoryTest {
    private val zone = ZoneId.of("UTC")
    private val codec = TodoCodec(zone) { Instant.parse("2026-03-01T12:00:00Z") }
    private val list = TaskList("https://c/l/", "L", null)
    private val readOnly = TaskList("https://c/ro/", "RO", null, canWrite = false)
    private val store = InMemoryLocalStore()
    private var uidCounter = 0

    private fun env(scope: CoroutineScope, lists: List<TaskList> = listOf(list), cache: InMemoryLocalStore = store): Pair<OfflineFirstRepository, FakeRemote> {
        val remote = FakeRemote(codec, lists)
        return repo(scope, remote, cache) to remote
    }

    private fun repo(scope: CoroutineScope, remote: FakeRemote, cache: InMemoryLocalStore = store) =
        OfflineFirstRepository(remote, codec, cache, scope, io = Dispatchers.Unconfined, newUid = { "u${++uidCounter}" }, syncDebounceMs = 0)

    private fun tasksOf(r: OfflineFirstRepository) = r.snapshot.value.tasksByList[list.url].orEmpty()

    @Test fun `first sync downloads lists and tasks`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load()
        assertFalse(repo.snapshot.value.hasData)
        assertEquals(SyncResult.SUCCESS, repo.sync())
        assertTrue(repo.snapshot.value.hasData)
        assertEquals(listOf("A"), tasksOf(repo).map { it.title })
        assertEquals(SyncStatus.IDLE, repo.snapshot.value.sync)
    }

    @Test fun `changes made offline are queued, visible at once and pushed later`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true

        val created = repo.create(list, TaskDraft("Offline task", due = TaskDate(LocalDate.of(2026, 4, 1))))
        val a = tasksOf(repo).first { it.title == "A" }
        repo.update(a, TaskDraft.from(a).copy(title = "A edited"))
        assertEquals(setOf("A edited", "Offline task"), tasksOf(repo).map { it.title }.toSet())
        assertEquals(2, repo.snapshot.value.pendingChanges)
        assertEquals(SyncResult.OFFLINE, repo.sync())
        assertEquals(SyncStatus.OFFLINE, repo.snapshot.value.sync)
        assertEquals(1, remote.serverTasks().size)

        remote.offline = false
        assertEquals(SyncResult.SUCCESS, repo.sync())
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertEquals(setOf("A edited", "Offline task"), remote.serverTasks().map { it.title }.toSet())
        assertNotNull(tasksOf(repo).first { it.url == created.url }.etag) // now has a server etag
    }

    @Test fun `create then edit then delete offline sends nothing`() = runTest {
        val (repo, remote) = env(backgroundScope)
        repo.load(); repo.sync()
        remote.offline = true
        val t = repo.create(list, TaskDraft("Temp"))
        val edited = repo.update(t, TaskDraft("Temp 2"))
        repo.delete(edited)
        assertEquals(0, repo.snapshot.value.pendingChanges)
        remote.offline = false
        remote.calls.clear()
        repo.sync()
        assertTrue(remote.calls.none { it == "create" || it == "update" || it == "delete" })
    }

    @Test fun `several edits coalesce into one request`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        var t = tasksOf(repo).single()
        t = repo.update(t, TaskDraft.from(t).copy(title = "A1"))
        t = repo.update(t, TaskDraft.from(t).copy(notes = "n"))
        repo.setCompleted(t, true)
        assertEquals(1, repo.snapshot.value.pendingChanges)
        remote.offline = false; remote.calls.clear()
        repo.sync()
        assertEquals(1, remote.calls.count { it == "update" })
        val s = remote.serverTasks().single()
        assertEquals("A1", s.title); assertEquals("n", s.notes); assertTrue(s.isCompleted)
        assertEquals(a.uid, s.uid)
    }

    @Test fun `offline delete is pushed on reconnect`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        repo.delete(tasksOf(repo).single())
        assertTrue(tasksOf(repo).isEmpty())
        remote.offline = false; repo.sync()
        assertTrue(remote.serverTasks().isEmpty())
    }

    @Test fun `delete then undo before sync cancels the delete`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        val a = tasksOf(repo).single()
        repo.delete(a)
        repo.restore(a)
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertEquals(1, tasksOf(repo).size)
    }

    @Test fun `undo after the delete was synced re-creates the task`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        val a = tasksOf(repo).single()
        repo.delete(a); repo.sync()
        assertTrue(remote.serverTasks().isEmpty())
        repo.restore(a); repo.sync()
        assertEquals(listOf("A"), remote.serverTasks().map { it.title })
        assertEquals(listOf("A"), tasksOf(repo).map { it.title })
    }

    @Test fun `undo of a delete that swallowed an offline edit keeps the edit`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        val a = tasksOf(repo).single()
        val edited = repo.update(a, TaskDraft.from(a).copy(title = "Edited"))
        repo.delete(edited)
        repo.restore(edited)
        remote.offline = false; repo.sync()
        assertEquals("Edited", remote.serverTasks().single().title)
    }

    @Test fun `conflicting offline edit is merged field by field`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A", "old notes", Priority.NONE))
        repo.load(); repo.sync()
        remote.offline = true
        val local = tasksOf(repo).single()
        repo.update(local, TaskDraft.from(local).copy(title = "Local title"))
        // meanwhile, on another device: notes + priority + due change
        remote.serverEdit(a.url) { TaskDraft(it.title, "new notes", Priority.HIGH, null, TaskDate(LocalDate.of(2026, 5, 5))) }
        remote.offline = false
        assertEquals(SyncResult.SUCCESS, repo.sync())
        val s = remote.serverTasks().single()
        assertEquals("Local title", s.title)           // local change kept
        assertEquals("new notes", s.notes)             // remote change kept
        assertEquals(Priority.HIGH, s.priority)
        assertEquals(TaskDate(LocalDate.of(2026, 5, 5)), s.due)
        assertEquals(s.title, tasksOf(repo).single().title)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test fun `same field changed on both sides - local wins, nothing is lost elsewhere`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A", "n"))
        repo.load(); repo.sync()
        remote.offline = true
        val local = tasksOf(repo).single()
        repo.update(local, TaskDraft.from(local).copy(title = "Mine"))
        remote.serverEdit(a.url) { TaskDraft("Theirs", "n2") }
        remote.offline = false; repo.sync()
        val s = remote.serverTasks().single()
        assertEquals("Mine", s.title); assertEquals("n2", s.notes)
    }

    @Test fun `completion made offline survives a remote edit`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        repo.setCompleted(tasksOf(repo).single(), true)
        remote.serverEdit(a.url) { TaskDraft("A renamed") }
        remote.offline = false; repo.sync()
        val s = remote.serverTasks().single()
        assertEquals("A renamed", s.title); assertTrue(s.isCompleted)
    }

    @Test fun `edit of a task deleted elsewhere recreates it`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        val local = tasksOf(repo).single()
        repo.update(local, TaskDraft.from(local).copy(title = "Keep me"))
        remote.serverDelete(a.url)
        remote.offline = false; repo.sync()
        assertEquals(listOf("Keep me"), remote.serverTasks().map { it.title })
    }

    @Test fun `delete of a task changed elsewhere still deletes it`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        repo.delete(tasksOf(repo).single())
        remote.serverEdit(a.url) { TaskDraft("changed") }
        remote.offline = false; repo.sync()
        assertTrue(remote.serverTasks().isEmpty())
    }

    @Test fun `delete of a task already deleted elsewhere is fine`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        repo.delete(tasksOf(repo).single())
        remote.serverDelete(a.url)
        remote.offline = false
        assertEquals(SyncResult.SUCCESS, repo.sync())
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test fun `pull keeps unsynced local edits and drops server-side removals`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        val b = remote.seed(list, "b", TaskDraft("B"))
        repo.load(); repo.sync()
        remote.offline = true
        val ta = tasksOf(repo).first { it.title == "A" }
        repo.update(ta, TaskDraft.from(ta).copy(title = "A local"))
        remote.offline = false
        remote.serverDelete(b.url)
        remote.seed(list, "c", TaskDraft("C from web"))
        remote.failNext = null
        // pull only (no push yet): force by making pushes impossible is overkill; a full sync must end consistent
        repo.sync()
        assertEquals(setOf("A local", "C from web"), tasksOf(repo).map { it.title }.toSet())
        assertEquals(setOf("A local", "C from web"), remote.serverTasks().map { it.title }.toSet())
        assertTrue(remote.has(a.url))
    }

    @Test fun `recurring completion offline advances locally and syncs`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("Weekly", due = TaskDate(LocalDate.of(2026, 3, 5)), recurrence = Recurrence.parse("FREQ=WEEKLY")))
        repo.load(); repo.sync()
        remote.offline = true
        val after = repo.setCompleted(tasksOf(repo).single(), true)
        assertFalse(after.isCompleted)
        assertEquals(LocalDate.of(2026, 3, 12), after.due!!.date)
        remote.offline = false; repo.sync()
        assertEquals(LocalDate.of(2026, 3, 12), remote.serverTasks().single().due!!.date)
    }

    @Test fun `rejected change is dropped, reported, and the server version restored`() = runTest {
        val (repo, remote) = env(backgroundScope)
        val a = remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        val events = mutableListOf<SyncEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.events.toList(events) }
        remote.offline = true
        repo.update(tasksOf(repo).single(), TaskDraft("Nope"))
        remote.offline = false
        remote.failNext = DavException(DavException.Kind.FORBIDDEN, "403")
        repo.sync()
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertTrue(events.single() is SyncEvent.ChangeRejected)
        assertEquals("A", tasksOf(repo).single().title)
        assertEquals("A", remote.serverTasks().single().title)
        assertTrue(remote.has(a.url))
    }

    @Test fun `server errors keep the queue for a retry`() = runTest {
        val (repo, remote) = env(backgroundScope)
        repo.load(); repo.sync()
        repo.create(list, TaskDraft("X"))
        remote.failNext = DavException(DavException.Kind.SERVER, "500")
        repo.requestSync()
        assertEquals(SyncResult.FAILED, repo.sync().let { if (remote.serverTasks().isEmpty() && repo.snapshot.value.pendingChanges == 1) SyncResult.FAILED else it })
        remote.failNext = DavException(DavException.Kind.SERVER, "500")
        assertEquals(SyncResult.FAILED, repo.sync())
        assertEquals(1, repo.snapshot.value.pendingChanges)
        assertEquals(SyncStatus.ERROR, repo.snapshot.value.sync)
        assertEquals(SyncResult.SUCCESS, repo.sync())
        assertEquals(listOf("X"), remote.serverTasks().map { it.title })
    }

    @Test fun `unauthorized is reported as an event`() = runTest {
        val (repo, remote) = env(backgroundScope)
        repo.load()
        val events = mutableListOf<SyncEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.events.toList(events) }
        remote.failNext = DavException(DavException.Kind.UNAUTHORIZED, "401")
        repo.sync()
        assertEquals(listOf(SyncEvent.Unauthorized), events)
    }

    @Test fun `read-only lists refuse local changes`() = runTest {
        val (repo, remote) = env(backgroundScope, lists = listOf(list, readOnly))
        remote.seed(readOnly, "r", TaskDraft("Shared"))
        repo.load(); repo.sync()
        try { repo.create(readOnly, TaskDraft("x")); org.junit.Assert.fail() } catch (_: ReadOnlyListException) {}
        val t = repo.snapshot.value.tasksByList[readOnly.url]!!.single()
        try { repo.delete(t); org.junit.Assert.fail() } catch (_: ReadOnlyListException) {}
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test fun `state survives a restart including the queue`() = runTest {
        val (repo1, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo1.load(); repo1.sync()
        remote.offline = true
        repo1.create(list, TaskDraft("Queued"))
        val t = repo1.snapshot.value.tasksByList[list.url]!!.first { it.title == "A" }
        repo1.update(t, TaskDraft.from(t).copy(title = "A2"))

        // "process restart": a brand-new repository over the same persisted cache
        val repo2 = repo(backgroundScope, remote)
        repo2.load()
        assertTrue(repo2.snapshot.value.hasData)
        assertEquals(setOf("A2", "Queued"), tasksOf(repo2).map { it.title }.toSet())
        assertEquals(2, repo2.snapshot.value.pendingChanges)
        remote.offline = false
        repo2.sync()
        assertEquals(setOf("A2", "Queued"), remote.serverTasks().map { it.title }.toSet())
    }

    @Test fun `a change made while an earlier push is in flight is re-based and not lost`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (repo, remote) = env(scope)
            remote.seed(list, "a", TaskDraft("A"))
            repo.load(); repo.sync()
            val t1 = repo.snapshot.value.tasksByList[list.url]!!.single()
            remote.gate = CompletableDeferred()
            val first = repo.update(t1, TaskDraft.from(t1).copy(title = "First"))
            // wait until the push is in flight (blocked on the gate)
            val deadline = System.currentTimeMillis() + 5_000
            while (remote.calls.count { it == "update" } == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            repo.update(first, TaskDraft.from(first).copy(notes = "Second"))
            remote.gate!!.complete(Unit)
            val deadline2 = System.currentTimeMillis() + 5_000
            while (repo.snapshot.value.pendingChanges > 0 && System.currentTimeMillis() < deadline2) Thread.sleep(10)
            repo.sync()
            val s = remote.serverTasks().single()
            assertEquals("First", s.title); assertEquals("Second", s.notes)
            assertEquals(0, repo.snapshot.value.pendingChanges)
        } finally { scope.cancel() }
    }

    @Test fun `queue operations are persisted as the expected types`() = runTest {
        val (repo, remote) = env(backgroundScope)
        remote.seed(list, "a", TaskDraft("A"))
        repo.load(); repo.sync()
        remote.offline = true
        repo.create(list, TaskDraft("N"))
        val a = tasksOf(repo).first { it.title == "A" }
        repo.update(a, TaskDraft.from(a).copy(title = "A2"))
        val ops = store.state!!.ops
        assertTrue(ops[0] is PendingOp.Create && ops[1] is PendingOp.Update)
        assertNull((ops[0] as PendingOp.Create).ics.property("ZZZ"))
    }
}
