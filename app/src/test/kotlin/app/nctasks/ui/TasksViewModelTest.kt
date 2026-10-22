// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui

import app.nctasks.data.SessionActions
import app.nctasks.data.caldav.DavException
import app.nctasks.data.ical.ICalComponent
import app.nctasks.data.repo.ReadOnlyListException
import app.nctasks.data.repo.Snapshot
import app.nctasks.data.repo.SyncEvent
import app.nctasks.data.repo.SyncResult
import app.nctasks.data.repo.SyncStatus
import app.nctasks.data.repo.TaskRepository
import app.nctasks.domain.Priority
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import app.nctasks.ui.tasks.TasksViewModel
import app.nctasks.ui.tasks.UiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val list = TaskList("https://c/l1/", "L1", null)
    private fun task(id: String, done: Boolean = false) =
        Task("https://c/l1/$id.ics", "\"$id\"", list.url, id, id, "", done, null, Priority.NONE, null, null, null, false, ICalComponent("VCALENDAR"))

    private class FakeRepo(var syncResult: SyncResult = SyncResult.SUCCESS) : TaskRepository {
        val state = MutableStateFlow(Snapshot())
        val eventFlow = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        var failWith: Exception? = null
        override val snapshot: StateFlow<Snapshot> = state
        override val events: Flow<SyncEvent> = eventFlow
        override suspend fun load() { calls += "load" }
        override suspend fun sync(): SyncResult { calls += "sync"; return syncResult }
        override fun requestSync(pull: Boolean) { calls += "request($pull)" }
        override suspend fun create(list: TaskList, draft: TaskDraft): Task { failWith?.let { throw it }; calls += "create:${draft.title}"; return Task("u", null, list.url, "x", draft.title, "", false, null, Priority.NONE, null, null, null, false, ICalComponent("VCALENDAR")) }
        override suspend fun update(task: Task, draft: TaskDraft): Task { calls += "update:${draft.title}"; return task }
        override suspend fun setCompleted(task: Task, completed: Boolean): Task { calls += "complete:$completed"; return task }
        override suspend fun delete(task: Task) { calls += "delete:${task.title}" }
        override suspend fun restore(task: Task): Task { calls += "restore:${task.title}"; return task }
        override fun close() {}
    }

    private class FakeApp(override var savedList: String? = null) : SessionActions {
        val saved = mutableListOf<String?>()
        val signOuts = mutableListOf<Boolean>()
        override fun saveList(url: String?) { saved += url; savedList = url }
        override suspend fun signOut(revoke: Boolean) { signOuts += revoke }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `loads the cache then syncs on start, and shows loading until data exists`() {
        val repo = FakeRepo()
        val vm = TasksViewModel(repo, FakeApp())
        assertEquals(listOf("load", "sync"), repo.calls)
        assertFalse(vm.state.value.isLoading) // initial sync finished
    }

    @Test fun `cached data is shown immediately and sync status and pending count are exposed`() {
        val repo = FakeRepo()
        repo.state.value = Snapshot(listOf(list), mapOf(list.url to listOf(task("a"))), pendingChanges = 2, sync = SyncStatus.OFFLINE, hasData = true)
        val vm = TasksViewModel(repo, FakeApp(savedList = list.url))
        val s = vm.state.value
        assertEquals(1, s.tasks(list).size)
        assertEquals(2, s.pendingChanges); assertEquals(SyncStatus.OFFLINE, s.sync)
        assertEquals(list, s.selectedList)
    }

    @Test fun `a saved list that no longer exists leaves the lists screen showing`() {
        val repo = FakeRepo()
        repo.state.value = Snapshot(listOf(list), hasData = true)
        assertNull(TasksViewModel(repo, FakeApp(savedList = "https://c/gone/")).state.value.selectedList)
    }

    @Test fun `user actions are forwarded to the repository`() {
        val repo = FakeRepo()
        val vm = TasksViewModel(repo, FakeApp())
        val t = task("a")
        vm.add(list, TaskDraft("New")); vm.edit(t, TaskDraft("Ed")); vm.toggle(t); vm.toggle(task("b", done = true)); vm.delete(t)
        assertTrue(repo.calls.containsAll(listOf("create:New", "update:Ed", "complete:true", "complete:false", "delete:a")))
        assertEquals("a", vm.state.value.undoDelete?.title)
        vm.undoDelete()
        assertTrue("restore:a" in repo.calls)
        assertNull(vm.state.value.undoDelete)
    }

    @Test fun `refreshing while offline tells the user`() {
        val repo = FakeRepo(SyncResult.OFFLINE)
        val vm = TasksViewModel(repo, FakeApp())
        vm.refresh()
        assertEquals(UiError.OFFLINE, vm.state.value.error)
        assertFalse(vm.state.value.isRefreshing)
        vm.dismissError(); assertNull(vm.state.value.error)
    }

    @Test fun `read-only errors are surfaced`() {
        val repo = FakeRepo().apply { failWith = ReadOnlyListException() }
        val vm = TasksViewModel(repo, FakeApp())
        vm.add(list, TaskDraft("x"))
        assertEquals(UiError.READ_ONLY, vm.state.value.error)
    }

    @Test fun `unauthorized event signs out without revoking, rejected change shows a message`() {
        val repo = FakeRepo(); val app = FakeApp()
        val vm = TasksViewModel(repo, app)
        repo.eventFlow.tryEmit(SyncEvent.Unauthorized)
        assertEquals(listOf(false), app.signOuts)
        repo.eventFlow.tryEmit(SyncEvent.ChangeRejected(DavException.Kind.FORBIDDEN))
        assertEquals(UiError.REJECTED, vm.state.value.error)
    }

    @Test fun `foreground triggers a pulling sync, selection is remembered, wipe empties the state`() {
        val repo = FakeRepo(); val app = FakeApp()
        repo.state.value = Snapshot(listOf(list), mapOf(list.url to listOf(task("a"))), hasData = true)
        val vm = TasksViewModel(repo, app)
        vm.onForeground(); assertTrue("request(true)" in repo.calls)
        vm.selectList(list); vm.selectList(null)
        assertEquals(listOf<String?>(list.url, null), app.saved)
        vm.wipe()
        assertTrue(vm.state.value.lists.isEmpty()); assertTrue(vm.state.value.tasksByList.isEmpty())
    }

    @Test fun `sign out asks for revocation`() {
        val app = FakeApp()
        TasksViewModel(FakeRepo(), app).signOut()
        assertEquals(listOf(true), app.signOuts)
    }
}
