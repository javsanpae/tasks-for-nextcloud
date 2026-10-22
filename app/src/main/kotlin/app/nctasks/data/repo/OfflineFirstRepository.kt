// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.repo

import app.nctasks.data.caldav.DavException
import app.nctasks.data.ical.ICalComponent
import app.nctasks.data.ical.ICalParseException
import app.nctasks.data.ical.ICalWriter
import app.nctasks.data.ical.TodoCodec
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local-first repository with a persistent change queue.
 *
 * State (lists, tasks, queue) lives in memory behind [lock] and is persisted after each change.
 * Network calls are made *without* holding [lock], so the UI never waits for the server. At most
 * one queued operation is "in flight"; later changes to the same task are queued behind it (never
 * merged into it) and re-based onto the server's answer once it arrives.
 */
class OfflineFirstRepository(
    private val remote: RemoteTasks,
    private val codec: TodoCodec,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val triggers: Flow<Unit> = emptyFlow(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newUid: () -> String = { UUID.randomUUID().toString() },
    private val syncDebounceMs: Long = 300,
) : TaskRepository {
    private val lock = Mutex()
    private val syncLock = Mutex()

    // Guarded by [lock].
    private var lists: List<TaskList> = emptyList()
    private var tasks = LinkedHashMap<String, Task>()
    private val queue = ArrayList<PendingOp>()
    private var inFlight: PendingOp? = null
    private var hasData = false
    private var status = SyncStatus.IDLE

    private val _snapshot = MutableStateFlow(Snapshot())
    override val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
    override val events: Flow<SyncEvent> = _events.asSharedFlow()

    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val pullWanted = AtomicBoolean(false)
    private var started = false

    @Volatile private var closed = false

    // ---- lifecycle ------------------------------------------------------------------------

    override suspend fun load() {
        val persisted = withContext(io) { store.load() }
        lock.withLock {
            if (persisted != null && !hasData) {
                lists = persisted.lists
                tasks = LinkedHashMap<String, Task>().also { m ->
                    persisted.tasks.forEach { t -> codec.decode(t.url, t.etag, t.listUrl, t.ics)?.let { m[it.url] = it } }
                }
                queue.clear()
                queue.addAll(persisted.ops)
                hasData = true
            }
            publishLocked()
        }
        if (!started) {
            started = true
            scope.launch {
                for (ignored in syncRequests) {
                    delay(syncDebounceMs)
                    syncNow(pull = pullWanted.getAndSet(false))
                }
            }
            scope.launch { triggers.collect { requestSync(pull = true) } }
        }
    }

    override fun requestSync(pull: Boolean) {
        if (pull) pullWanted.set(true)
        syncRequests.trySend(Unit)
    }

    override fun close() {
        closed = true
        syncRequests.close()
        scope.cancel()
    }

    // ---- local changes --------------------------------------------------------------------

    override suspend fun create(list: TaskList, draft: TaskDraft): Task = mutate {
        if (!list.canWrite) throw ReadOnlyListException()
        val uid = newUid()
        val ics = codec.create(uid, draft)
        val url = "${list.url}$uid.ics"
        val task = codec.decode(url, null, list.url, ics) ?: throw ICalParseException("invalid task")
        tasks[url] = task
        queue += PendingOp.Create(url, list.url, ics)
        task
    }

    override suspend fun update(task: Task, draft: TaskDraft): Task = edit(task) { codec.applyDraft(it, draft) }

    override suspend fun setCompleted(task: Task, completed: Boolean): Task = edit(task) { codec.applyCompletion(it, completed) }

    private suspend fun edit(task: Task, change: (Task) -> ICalComponent): Task = mutate {
        val cur = tasks[task.url] ?: return@mutate task
        requireWritable(cur.listUrl)
        val ics = change(cur)
        val updated = codec.decode(cur.url, cur.etag, cur.listUrl, ics) ?: throw ICalParseException("invalid task")
        tasks[cur.url] = updated
        val i = indexOfQueued(cur.url)
        when (val existing = queue.getOrNull(i)) {
            is PendingOp.Create -> queue[i] = existing.copy(ics = ics)
            is PendingOp.Update -> queue[i] = existing.copy(ics = ics)
            else -> queue += PendingOp.Update(cur.url, cur.listUrl, cur.etag, cur.ical, ics)
        }
        updated
    }

    override suspend fun delete(task: Task) = mutate {
        val cur = tasks[task.url] ?: return@mutate
        requireWritable(cur.listUrl)
        tasks.remove(cur.url)
        val i = indexOfQueued(cur.url)
        when (val existing = queue.getOrNull(i)) {
            // Never reached the server: forget it entirely.
            is PendingOp.Create -> queue.removeAt(i)
            is PendingOp.Update -> queue[i] = PendingOp.Delete(cur.url, cur.listUrl, existing.baseEtag, existing.baseIcs)
            else -> queue += PendingOp.Delete(cur.url, cur.listUrl, cur.etag, cur.ical)
        }
    }

    override suspend fun restore(task: Task): Task = mutate {
        val i = queue.indexOfLast { it.url == task.url && it is PendingOp.Delete && it !== inFlight }
        if (i >= 0) {
            // The delete never left the device: just cancel it.
            val cancelled = queue.removeAt(i) as PendingOp.Delete
            tasks[task.url] = task
            val base = cancelled.baseIcs
            // The delete may have swallowed an unsynced edit; queue that edit again.
            if (base != null && ICalWriter.write(base) != ICalWriter.write(task.ical)) {
                queue += PendingOp.Update(task.url, task.listUrl, cancelled.etag, base, task.ical)
            }
            task
        } else {
            val fresh = task.copy(etag = null)
            tasks[fresh.url] = fresh
            queue += PendingOp.Create(fresh.url, fresh.listUrl, fresh.ical)
            fresh
        }
    }

    /** Index of the newest queued op for [url] that may still be changed (not the one in flight), or -1. */
    private fun indexOfQueued(url: String): Int = queue.indexOfLast { it.url == url && it !== inFlight }

    private fun requireWritable(listUrl: String) {
        if (lists.firstOrNull { it.url == listUrl }?.canWrite == false) throw ReadOnlyListException()
    }

    private suspend fun <T> mutate(block: () -> T): T {
        val result = lock.withLock {
            val r = block()
            persistAndPublishLocked()
            r
        }
        requestSync(pull = false)
        return result
    }

    // ---- sync -----------------------------------------------------------------------------

    override suspend fun sync(): SyncResult = syncNow(pull = true)

    private suspend fun syncNow(pull: Boolean): SyncResult = syncLock.withLock {
        setStatus(SyncStatus.SYNCING)
        val result = try {
            when (flush()) {
                Flush.DONE -> if (pull) pull() else SyncResult.SUCCESS
                Flush.OFFLINE -> SyncResult.OFFLINE
                Flush.ERROR -> SyncResult.FAILED
            }
        } catch (e: DavException) {
            classify(e)
        }
        setStatus(
            when (result) {
                SyncResult.SUCCESS -> SyncStatus.IDLE
                SyncResult.OFFLINE -> SyncStatus.OFFLINE
                SyncResult.FAILED -> SyncStatus.ERROR
            },
        )
        result
    }

    private fun classify(e: DavException): SyncResult = when (e.kind) {
        DavException.Kind.NETWORK -> SyncResult.OFFLINE
        DavException.Kind.UNAUTHORIZED -> { _events.tryEmit(SyncEvent.Unauthorized); SyncResult.FAILED }
        else -> SyncResult.FAILED
    }

    private enum class Flush { DONE, OFFLINE, ERROR }

    private suspend fun flush(): Flush {
        while (true) {
            val op = lock.withLock { queue.firstOrNull().also { inFlight = it } } ?: return Flush.DONE
            try {
                val server = push(op)
                lock.withLock { completeLocked(op, server) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { lock.withLock { inFlight = null } }
                throw e
            } catch (e: DavException) {
                when (e.kind) {
                    DavException.Kind.NETWORK -> { releaseInFlight(); return Flush.OFFLINE }
                    DavException.Kind.SERVER, DavException.Kind.REDIRECTED -> { releaseInFlight(); return Flush.ERROR }
                    DavException.Kind.UNAUTHORIZED -> {
                        releaseInFlight()
                        _events.tryEmit(SyncEvent.Unauthorized)
                        return Flush.ERROR
                    }
                    else -> {
                        // Retrying cannot help (e.g. read-only share): drop the change, the next pull restores the server's version.
                        lock.withLock { dropLocked(op) }
                        _events.tryEmit(SyncEvent.ChangeRejected(e.kind))
                    }
                }
            }
        }
    }

    private suspend fun releaseInFlight() = lock.withLock { inFlight = null }

    /** Sends [op]; returns the server's resulting task (null for deletes). */
    private suspend fun push(op: PendingOp): Task? = when (op) {
        is PendingOp.Create -> try {
            remote.create(op.listUrl, op.url, ICalWriter.write(op.ics))
        } catch (e: DavException) {
            // Already there: an earlier attempt succeeded but its answer was lost.
            if (e.kind == DavException.Kind.CONFLICT) remote.task(op.listUrl, op.url) ?: throw e else throw e
        }
        is PendingOp.Update -> pushUpdate(op)
        is PendingOp.Delete -> {
            try {
                remote.delete(op.url, op.etag)
            } catch (e: DavException) {
                when (e.kind) {
                    DavException.Kind.NOT_FOUND -> Unit // already gone
                    DavException.Kind.CONFLICT -> try {
                        remote.delete(op.url, null) // changed elsewhere, but deleting is what the user asked for
                    } catch (e2: DavException) {
                        if (e2.kind != DavException.Kind.NOT_FOUND) throw e2
                    }
                    else -> throw e
                }
            }
            null
        }
    }

    private suspend fun pushUpdate(op: PendingOp.Update): Task {
        val base = codec.decode(op.url, op.baseEtag, op.listUrl, op.baseIcs)
        val local = codec.decode(op.url, null, op.listUrl, op.ics)
        if (base == null || local == null) throw DavException(DavException.Kind.MALFORMED, "Unreadable queued change")
        var etag = op.baseEtag
        var ics = op.ics
        var attempts = 0
        while (true) {
            try {
                return remote.update(op.listUrl, op.url, etag, ICalWriter.write(ics))
            } catch (e: DavException) {
                // Servers answer 404 or 412 when the task was deleted elsewhere; keep the user's work either way.
                if (e.kind == DavException.Kind.NOT_FOUND) return remote.create(op.listUrl, op.url, ICalWriter.write(op.ics))
                if (e.kind != DavException.Kind.CONFLICT || ++attempts > MAX_MERGE_ATTEMPTS) throw e
                // Changed on the server since we last saw it: merge field by field and try again.
                val server = remote.task(op.listUrl, op.url)
                    ?: return remote.create(op.listUrl, op.url, ICalWriter.write(op.ics)) // deleted elsewhere: keep the user's work
                ics = codec.merge(server, base, local)
                etag = server.etag
            }
        }
    }

    /** Applies the server's answer for [op]; later queued changes to the same task are re-based onto it. */
    private suspend fun completeLocked(op: PendingOp, server: Task?) {
        queue.indexOfFirst { it === op }.takeIf { it >= 0 }?.let { queue.removeAt(it) }
        inFlight = null
        if (server != null) {
            var hasLater = false
            for (j in queue.indices) {
                val q = queue[j]
                if (q.url != op.url) continue
                hasLater = true
                queue[j] = when (q) {
                    is PendingOp.Update -> q.copy(baseEtag = server.etag, baseIcs = server.ical)
                    is PendingOp.Delete -> q.copy(etag = server.etag, baseIcs = server.ical)
                    is PendingOp.Create -> q
                }
            }
            val local = tasks[op.url]
            if (local != null) tasks[op.url] = if (hasLater) local.copy(etag = server.etag) else server
        }
        persistAndPublishLocked()
    }

    private suspend fun dropLocked(op: PendingOp) {
        queue.indexOfFirst { it === op }.takeIf { it >= 0 }?.let { queue.removeAt(it) }
        inFlight = null
        persistAndPublishLocked()
    }

    private suspend fun pull(): SyncResult {
        val remoteLists = remote.taskLists()
        val fetched: List<Pair<TaskList, List<Task>?>> = coroutineScope {
            remoteLists.map { list ->
                async {
                    list to try {
                        remote.tasks(list)
                    } catch (e: DavException) {
                        // One unreadable list must not hide the others, but connectivity/auth problems abort the sync.
                        if (e.kind == DavException.Kind.NETWORK || e.kind == DavException.Kind.UNAUTHORIZED) throw e
                        null
                    }
                }
            }.awaitAll()
        }
        lock.withLock {
            val pendingUrls = queue.map { it.url }.toSet()
            val deletePending = queue.filterIsInstance<PendingOp.Delete>().map { it.url }.toSet()
            val merged = LinkedHashMap<String, Task>()
            for ((list, serverTasks) in fetched) {
                if (serverTasks == null) {
                    tasks.values.filter { it.listUrl == list.url }.forEach { merged[it.url] = it }
                    continue
                }
                for (s in serverTasks) {
                    if (s.url in deletePending) continue
                    // Unsynced local edits win over the server copy until they are pushed.
                    merged[s.url] = if (s.url in pendingUrls) tasks[s.url] ?: s else s
                }
            }
            // Tasks created offline do not exist on the server yet.
            queue.filterIsInstance<PendingOp.Create>().forEach { c -> tasks[c.url]?.let { merged[it.url] = it } }
            val listUrls = remoteLists.map { it.url }.toSet()
            queue.removeAll { it.listUrl !in listUrls && it !== inFlight }
            lists = remoteLists
            tasks = merged
            hasData = true
            persistAndPublishLocked()
        }
        return SyncResult.SUCCESS
    }

    // ---- state plumbing -------------------------------------------------------------------

    private suspend fun setStatus(s: SyncStatus) = lock.withLock { status = s; publishLocked() }

    private fun publishLocked() {
        _snapshot.value = Snapshot(
            lists = lists,
            tasksByList = tasks.values.groupBy { it.listUrl },
            pendingChanges = queue.size,
            sync = status,
            hasData = hasData,
        )
    }

    private suspend fun persistAndPublishLocked() {
        val state = PersistedState(lists, tasks.values.map { StoredTask(it.url, it.etag, it.listUrl, it.ical) }, queue.toList())
        if (!closed) withContext(io) { store.save(state) } // after sign-out nothing may be written back
        publishLocked()
    }

    private companion object {
        const val MAX_MERGE_ATTEMPTS = 3
    }
}
