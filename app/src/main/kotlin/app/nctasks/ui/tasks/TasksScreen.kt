// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.domain.Priority
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import app.nctasks.ui.components.CheckCircle
import app.nctasks.ui.components.Chevron
import app.nctasks.ui.components.ChevronDirection
import app.nctasks.ui.components.IosToast
import app.nctasks.ui.components.LargeTitleScaffold
import app.nctasks.ui.components.NavBarButton
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.components.iosClickable
import app.nctasks.ui.lists.listColor
import app.nctasks.ui.theme.Ios
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface EditorTarget {
    data object New : EditorTarget
    data class Existing(val taskUrl: String) : EditorTarget
}

@Composable
fun TasksScreen(
    list: TaskList,
    lists: List<TaskList>,
    tasks: List<Task>,
    showCompleted: Boolean,
    isRefreshing: Boolean,
    toast: ToastMessage?,
    syncLabel: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleShowCompleted: () -> Unit,
    onToggle: (Task) -> Unit,
    onAdd: (TaskList, TaskDraft) -> Unit,
    onEdit: (Task, TaskDraft) -> Unit,
    onDelete: (Task) -> Unit,
    onToastAction: () -> Unit,
    modifier: Modifier = Modifier,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val c = Ios.colors
    val tint = listColor(list)
    val res = LocalContext.current.resources
    var editor by rememberSaveable(stateSaver = EditorTargetSaver) { mutableStateOf<EditorTarget?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val open = remember(tasks) { TaskSorting.open(tasks) }
    val completed = remember(tasks) { TaskSorting.completed(tasks) }

    BackHandler(enabled = editor != null) { editor = null }
    BackHandler(enabled = editor == null, onBack = onBack)

    Box(modifier.fillMaxSize()) {
        LargeTitleScaffold(
            title = list.name,
            titleColor = tint,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            navLeading = {
                NavBarButton(onClick = onBack) {
                    Chevron(direction = ChevronDirection.Left, color = tint, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.back_to_lists), style = Ios.type.body, color = tint)
                }
            },
            navTrailing = {
                Box {
                    NavBarButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more), tint = tint)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.card) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (showCompleted) R.string.menu_hide_completed else R.string.menu_show_completed), style = Ios.type.body, color = c.label) },
                            onClick = { menuOpen = false; onToggleShowCompleted() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_refresh), style = Ios.type.body, color = c.label) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                    }
                }
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().background(c.background).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (list.canWrite) {
                        NavBarButton(onClick = { editor = EditorTarget.New }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.task_new), style = Ios.type.headline, color = tint)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (syncLabel != null) {
                        Text(
                            syncLabel, style = Ios.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
        ) {
            if (open.isEmpty() && (completed.isEmpty() || !showCompleted)) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.tasks_empty), style = Ios.type.title3, color = c.secondaryLabel)
                    }
                }
            }
            items(open, key = { it.url }) { task ->
                SwipeableTaskRow(task, tint, res, now, onToggle, { editor = EditorTarget.Existing(task.url) }, onDelete, list.canWrite)
            }
            if (showCompleted && completed.isNotEmpty()) {
                item(key = "completed-header") {
                    Text(
                        stringResource(R.string.tasks_completed_header, completed.size),
                        style = Ios.type.subhead, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
                items(completed, key = { it.url }) { task ->
                    SwipeableTaskRow(task, tint, res, now, onToggle, { editor = EditorTarget.Existing(task.url) }, onDelete, list.canWrite)
                }
            }
        }
        IosToast(toast?.text, toast?.actionLabel, onToastAction, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))

        val target = editor
        val editedTask = (target as? EditorTarget.Existing)?.let { t -> tasks.firstOrNull { it.url == t.taskUrl } }
        // A task deleted elsewhere while its editor is open: close the sheet.
        LaunchedEffect(target, editedTask) { if (target is EditorTarget.Existing && editedTask == null) editor = null }

        AnimatedVisibility(visible = target != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { editor = null })
        }
        AnimatedVisibility(
            visible = target != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) {
            val shown = remember(target) { target }
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(c.background),
            ) {
                when (shown) {
                    EditorTarget.New -> TaskEditor(
                        initial = TaskDraft(title = ""),
                        isNew = true,
                        lists = lists,
                        initialList = list,
                        hasUnsupportedRecurrence = false,
                        onSave = { draft, targetList -> editor = null; onAdd(targetList, draft) },
                        onCancel = { editor = null },
                        onDelete = null,
                        now = now,
                    )
                    is EditorTarget.Existing -> {
                        val task = editedTask ?: tasks.firstOrNull { it.url == shown.taskUrl }
                        if (task != null) TaskEditor(
                            initial = TaskDraft.from(task),
                            isNew = false,
                            lists = lists,
                            initialList = list,
                            hasUnsupportedRecurrence = task.hasUnsupportedRecurrence,
                            onSave = { draft, _ -> editor = null; onEdit(task, draft) },
                            onCancel = { editor = null },
                            onDelete = if (list.canWrite) ({ editor = null; onDelete(task) }) else null,
                            now = now,
                        )
                    }
                    null -> {}
                }
            }
        }
    }
}

data class ToastMessage(val text: String, val actionLabel: String? = null)

private val EditorTargetSaver = androidx.compose.runtime.saveable.Saver<EditorTarget?, String>(
    save = { t -> when (t) { null -> ""; EditorTarget.New -> "new"; is EditorTarget.Existing -> "task:" + t.taskUrl } },
    restore = { s -> when { s.isEmpty() -> null; s == "new" -> EditorTarget.New; else -> EditorTarget.Existing(s.removePrefix("task:")) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(
    task: Task,
    tint: Color,
    res: android.content.res.Resources,
    now: LocalDateTime,
    onToggle: (Task) -> Unit,
    onOpen: () -> Unit,
    onDelete: (Task) -> Unit,
    canWrite: Boolean,
) {
    // A repeating task jumps to its next date when completed; show the tick for a moment first.
    var tickShown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnDelete by rememberUpdatedState(onDelete)
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete(task)
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canWrite,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Ios.colors.red).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.task_delete), tint = Color.White)
            }
        },
    ) {
        TaskRow(
            task, tint, tickShown, res, now,
            onToggle = {
                if (!task.isCompleted && task.recurrence != null) {
                    tickShown = true
                    scope.launch { delay(450); onToggle(task); tickShown = false }
                } else {
                    onToggle(task)
                }
            },
            onOpen = onOpen,
            canWrite = canWrite,
        )
    }
}

@Composable
fun TaskRow(
    task: Task,
    tint: Color,
    tickShown: Boolean,
    res: android.content.res.Resources,
    now: LocalDateTime,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    canWrite: Boolean = true,
) {
    val c = Ios.colors
    val due = task.due?.let { DueText.format(res, it, now) }
    val checked = task.isCompleted || tickShown
    Column(Modifier.fillMaxWidth().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().iosClickable(onClick = onOpen).padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CheckCircle(
                checked = checked,
                color = tint,
                onToggle = onToggle,
                contentDescription = stringResource(if (task.isCompleted) R.string.task_mark_open else R.string.task_mark_done),
                enabled = canWrite && !tickShown,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(Modifier.weight(1f).padding(top = 11.dp, bottom = 11.dp, start = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.priority != Priority.NONE) {
                        Text(
                            stringResource(when (task.priority) {
                                Priority.HIGH -> R.string.priority_marks_high
                                Priority.MEDIUM -> R.string.priority_marks_medium
                                else -> R.string.priority_marks_low
                            }),
                            style = Ios.type.body, color = tint, modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Text(
                        task.title.ifBlank { "—" },
                        style = Ios.type.body,
                        color = if (task.isCompleted) c.secondaryLabel else c.label,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.isCompleted) TextDecoration.None else null,
                    )
                }
                if (task.notes.isNotBlank()) {
                    Text(task.notes.lineSequence().first(), style = Ios.type.subhead, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (due != null || task.recurrence != null || task.hasUnsupportedRecurrence) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (due != null) Text(due.text, style = Ios.type.subhead, color = if (due.isOverdue && !task.isCompleted) c.red else c.secondaryLabel)
                        if (task.recurrence != null || task.hasUnsupportedRecurrence) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.task_repeats),
                                tint = c.secondaryLabel,
                                modifier = Modifier.padding(start = if (due != null) 6.dp else 0.dp).size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        RowDivider(startInset = 52.dp)
    }
}
