// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.repo

import app.nctasks.data.caldav.DavException
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SyncStatus { IDLE, SYNCING, OFFLINE, ERROR }

enum class SyncResult { SUCCESS, OFFLINE, FAILED }

/** Everything the UI shows, always available instantly from the local copy. */
data class Snapshot(
    val lists: List<TaskList> = emptyList(),
    val tasksByList: Map<String, List<Task>> = emptyMap(),
    /** Changes made on this device that the server has not acknowledged yet. */
    val pendingChanges: Int = 0,
    val sync: SyncStatus = SyncStatus.IDLE,
    /** False until a cache was loaded or the first sync finished. */
    val hasData: Boolean = false,
)

sealed interface SyncEvent {
    /** The server no longer accepts our credentials. */
    data object Unauthorized : SyncEvent

    /** The server refused a queued change; it was dropped and the server's version restored. */
    data class ChangeRejected(val kind: DavException.Kind) : SyncEvent
}

class ReadOnlyListException : Exception("List is read-only")

/**
 * Offline-first task storage: reads and writes hit the local copy immediately; changes are queued
 * and pushed to the server when possible.
 */
interface TaskRepository {
    val snapshot: StateFlow<Snapshot>
    val events: Flow<SyncEvent>

    /** Loads the local cache and starts background syncing. Call once. */
    suspend fun load()

    /** Pushes pending changes and pulls the server state. */
    suspend fun sync(): SyncResult

    /** Schedules a (debounced) background sync; [pull] also downloads server changes. */
    fun requestSync(pull: Boolean = false)

    suspend fun create(list: TaskList, draft: TaskDraft): Task
    suspend fun update(task: Task, draft: TaskDraft): Task
    suspend fun setCompleted(task: Task, completed: Boolean): Task
    suspend fun delete(task: Task)

    /** Undoes a [delete]. */
    suspend fun restore(task: Task): Task

    fun close()
}
