// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nctasks.data.SessionActions
import app.nctasks.data.repo.ReadOnlyListException
import app.nctasks.data.repo.SyncEvent
import app.nctasks.data.repo.SyncResult
import app.nctasks.data.repo.SyncStatus
import app.nctasks.data.repo.TaskRepository
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UiError { OFFLINE, SERVER, READ_ONLY, REJECTED, GENERIC }

data class TasksUiState(
    val lists: List<TaskList> = emptyList(),
    val tasksByList: Map<String, List<Task>> = emptyMap(),
    val selectedListUrl: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val showCompleted: Boolean = false,
    val sync: SyncStatus = SyncStatus.IDLE,
    /** Changes made here that the server has not confirmed yet. */
    val pendingChanges: Int = 0,
    val error: UiError? = null,
    /** The task that was just deleted, so the UI can offer "Undo". */
    val undoDelete: Task? = null,
) {
    val selectedList: TaskList? get() = lists.firstOrNull { it.url == selectedListUrl }
    fun tasks(list: TaskList): List<Task> = tasksByList[list.url].orEmpty()
}

/** Maps the repository's snapshot to UI state and forwards user actions; all data lives in the repository. */
class TasksViewModel(
    private val repo: TaskRepository,
    private val app: SessionActions,
) : ViewModel() {
    private data class Flags(
        val selectedListUrl: String? = null,
        val initialSyncDone: Boolean = false,
        val isRefreshing: Boolean = false,
        val showCompleted: Boolean = false,
        val error: UiError? = null,
        val undoDelete: Task? = null,
        val wiped: Boolean = false,
    )

    private val flags = MutableStateFlow(Flags(selectedListUrl = app.savedList))

    val state: StateFlow<TasksUiState> = combine(repo.snapshot, flags) { snap, f ->
        if (f.wiped) return@combine TasksUiState(isLoading = false)
        TasksUiState(
            lists = snap.lists,
            tasksByList = snap.tasksByList,
            selectedListUrl = f.selectedListUrl,
            isLoading = !snap.hasData && !f.initialSyncDone,
            isRefreshing = f.isRefreshing,
            showCompleted = f.showCompleted,
            sync = snap.sync,
            pendingChanges = snap.pendingChanges,
            error = f.error,
            undoDelete = f.undoDelete,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    init {
        viewModelScope.launch {
            repo.events.collect { event ->
                when (event) {
                    SyncEvent.Unauthorized -> app.signOut(revoke = false) // password revoked on the server
                    is SyncEvent.ChangeRejected -> flags.update { it.copy(error = UiError.REJECTED) }
                }
            }
        }
        viewModelScope.launch {
            repo.load()
            repo.sync()
            flags.update { it.copy(initialSyncDone = true) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            flags.update { it.copy(isRefreshing = true) }
            val result = repo.sync()
            flags.update {
                it.copy(
                    isRefreshing = false,
                    error = when (result) {
                        SyncResult.SUCCESS -> it.error
                        SyncResult.OFFLINE -> UiError.OFFLINE
                        SyncResult.FAILED -> UiError.SERVER
                    },
                )
            }
        }
    }

    /** The app came to the foreground: pick up changes made elsewhere and flush anything queued. */
    fun onForeground() = repo.requestSync(pull = true)

    fun selectList(list: TaskList?) {
        app.saveList(list?.url)
        flags.update { it.copy(selectedListUrl = list?.url) }
    }

    fun setShowCompleted(show: Boolean) = flags.update { it.copy(showCompleted = show) }

    fun dismissError() = flags.update { it.copy(error = null) }

    fun dismissUndo() = flags.update { it.copy(undoDelete = null) }

    fun add(list: TaskList, draft: TaskDraft) = run { repo.create(list, draft) }

    fun edit(task: Task, draft: TaskDraft) = run { repo.update(task, draft) }

    fun toggle(task: Task) = run { repo.setCompleted(task, !task.isCompleted) }

    fun delete(task: Task) = run {
        repo.delete(task)
        flags.update { it.copy(undoDelete = task) }
    }

    fun undoDelete() {
        val task = flags.value.undoDelete ?: return
        flags.update { it.copy(undoDelete = null) }
        run { repo.restore(task) }
    }

    /** Drops every task from view when the session ends. */
    fun wipe() = flags.update { it.copy(wiped = true) }

    fun signOut() {
        viewModelScope.launch { app.signOut(revoke = true) }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ReadOnlyListException) {
                flags.update { it.copy(error = UiError.READ_ONLY) }
            } catch (_: Exception) {
                flags.update { it.copy(error = UiError.GENERIC) }
            }
        }
    }
}
