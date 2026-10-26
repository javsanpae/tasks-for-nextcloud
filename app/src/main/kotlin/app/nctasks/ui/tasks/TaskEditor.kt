// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.Reminder
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import app.nctasks.ui.components.Chevron
import app.nctasks.ui.components.ClockIcon
import app.nctasks.ui.components.ChevronDirection
import app.nctasks.ui.components.GroupedCard
import app.nctasks.ui.components.IosCalendar
import app.nctasks.ui.components.IosRow
import app.nctasks.ui.components.IosSegmented
import app.nctasks.ui.components.IosSwitch
import app.nctasks.ui.components.IosTextButton
import app.nctasks.ui.components.IosTextField
import app.nctasks.ui.components.IosTimePicker
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.components.SectionFooter
import app.nctasks.ui.components.SquareBadge
import app.nctasks.ui.components.iosClickable
import app.nctasks.ui.lists.listColor
import app.nctasks.ui.theme.Ios
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class Page { MAIN, REPEAT, ALERTS }
private enum class Expanded { NONE, DATE, TIME }

/** Everything the user can change in the sheet, survivable across rotation and process death. */
private class EditorState(initial: TaskDraft, defaultDate: LocalDate, defaultTime: LocalTime, val initialListUrl: String) {
    var title by mutableStateOf(initial.title)
    var notes by mutableStateOf(initial.notes)
    var hasDate by mutableStateOf(initial.due != null)
    var date by mutableStateOf(initial.due?.date ?: defaultDate)
    var hasTime by mutableStateOf(initial.due?.time != null)
    var time by mutableStateOf(initial.due?.time ?: defaultTime)
    var recurrence by mutableStateOf(initial.recurrence)
    var reminders by mutableStateOf(initial.reminders)
    var priority by mutableStateOf(initial.priority)
    var listUrl by mutableStateOf(initialListUrl)

    fun toDraft(base: TaskDraft) = TaskDraft(
        title = title.trim(),
        notes = notes,
        priority = priority,
        start = base.start,
        due = if (hasDate) TaskDate(date, if (hasTime) time else null) else null,
        recurrence = recurrence,
        reminders = reminders,
    )

    companion object {
        fun saver(initial: TaskDraft, defaultDate: LocalDate, defaultTime: LocalTime, listUrl: String) = listSaver<EditorState, Any?>(
            save = { listOf(it.title, it.notes, it.hasDate, it.date.toEpochDay(), it.hasTime, it.time.toSecondOfDay(), it.recurrence?.toRRule(), it.priority.name, it.listUrl, it.reminders.map(::encodeReminder)) },
            restore = {
                EditorState(initial, defaultDate, defaultTime, listUrl).apply {
                    title = it[0] as String; notes = it[1] as String; hasDate = it[2] as Boolean
                    date = LocalDate.ofEpochDay(it[3] as Long); hasTime = it[4] as Boolean
                    time = LocalTime.ofSecondOfDay((it[5] as Int).toLong())
                    recurrence = (it[6] as String?)?.let(Recurrence::parse)
                    priority = Priority.valueOf(it[7] as String); this.listUrl = it[8] as String
                    @Suppress("UNCHECKED_CAST")
                    reminders = (it[9] as List<String>).mapNotNull(::decodeReminder)
                }
            },
        )
    }
}

private fun encodeReminder(r: Reminder): String = when (r) {
    is Reminder.Relative -> "R,${r.offset.seconds},${r.fromStart}"
    is Reminder.Absolute -> "A,${r.at.epochSecond}"
}

private fun decodeReminder(s: String): Reminder? {
    val p = s.split(',')
    return runCatching {
        when (p[0]) {
            "R" -> Reminder.Relative(java.time.Duration.ofSeconds(p[1].toLong()), p[2].toBoolean())
            "A" -> Reminder.Absolute(java.time.Instant.ofEpochSecond(p[1].toLong()))
            else -> null
        }
    }.getOrNull()
}

/**
 * The iOS-style "New Reminder" sheet: title/notes, date & time, repeat, priority, list.
 * Rendered by the caller inside a rounded sheet container.
 */
@Composable
fun TaskEditor(
    initial: TaskDraft,
    isNew: Boolean,
    lists: List<TaskList>,
    initialList: TaskList,
    hasUnsupportedRecurrence: Boolean,
    onSave: (TaskDraft, TaskList) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onAlertAdded: () -> Unit = {},
    now: LocalDateTime = LocalDateTime.now(),
    locale: Locale = Locale.getDefault(),
) {
    val c = Ios.colors
    val defaultDate = now.toLocalDate()
    val defaultTime = now.toLocalTime().withMinute(0).withSecond(0).withNano(0).plusHours(1)
    val state = rememberSaveable(saver = EditorState.saver(initial, defaultDate, defaultTime, initialList.url)) {
        EditorState(initial, defaultDate, defaultTime, initialList.url)
    }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var expanded by rememberSaveable { mutableStateOf(Expanded.NONE) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val selectedList = lists.firstOrNull { it.url == state.listUrl } ?: initialList
    val canSave = state.title.isNotBlank()
    val canRepeat = state.hasDate || initial.start != null
    val res = LocalContext.current.resources

    Column(modifier.fillMaxSize().background(c.background).navigationBarsPadding().imePadding()) {
        // Navigation bar
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            if (page == Page.MAIN) {
                IosTextButton(stringResource(R.string.editor_cancel), onCancel, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                Text(
                    stringResource(if (isNew) R.string.editor_new_title else R.string.editor_details_title),
                    style = Ios.type.headline, color = c.label, modifier = Modifier.align(Alignment.Center),
                )
                IosTextButton(
                    stringResource(if (isNew) R.string.editor_add else R.string.editor_done),
                    { onSave(state.toDraft(initial), selectedList) },
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    bold = true, enabled = canSave,
                )
            } else {
                Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).iosClickable { page = Page.MAIN }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chevron(direction = ChevronDirection.Left, color = c.blue, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.repeat_back), style = Ios.type.body, color = c.blue)
                }
                Text(
                    stringResource(if (page == Page.ALERTS) R.string.alerts_title else R.string.repeat_title),
                    style = Ios.type.headline, color = c.label, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                if (targetState != Page.MAIN) slideInHorizontally(tween(260)) { it } + fadeIn() togetherWith slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut()
                else slideInHorizontally(tween(260)) { -it / 3 } + fadeIn() togetherWith slideOutHorizontally(tween(260)) { it } + fadeOut()
            },
            label = "editor-page",
        ) { current ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp)) {
                if (current == Page.REPEAT) {
                    RecurrenceEditor(state.recurrence, state.date, { state.recurrence = it }, locale = locale)
                } else if (current == Page.ALERTS) {
                    AlertsEditor(state.reminders, allDay = !state.hasTime, onChange = { state.reminders = it }, onAdded = onAlertAdded, locale = locale)
                } else {
                    MainPage(
                        state, isNew, lists, initial, hasUnsupportedRecurrence, canRepeat, expanded,
                        onExpand = { expanded = if (expanded == it) Expanded.NONE else it },
                        onSetExpanded = { expanded = it },
                        onOpenRepeat = { page = Page.REPEAT },
                        onOpenAlerts = { page = Page.ALERTS },
                        onDelete = onDelete?.let { { confirmDelete = true } },
                        res = res, locale = locale, now = now,
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.card,
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.editor_delete_confirm_title), style = Ios.type.headline, color = c.label) },
            text = { Text(stringResource(R.string.editor_delete_confirm_body), style = Ios.type.subhead, color = c.secondaryLabel) },
            confirmButton = { IosTextButton(stringResource(R.string.task_delete), { confirmDelete = false; onDelete?.invoke() }, color = c.red, bold = true) },
            dismissButton = { IosTextButton(stringResource(R.string.editor_cancel), { confirmDelete = false }) },
        )
    }
}

@Composable
private fun MainPage(
    state: EditorState,
    isNew: Boolean,
    lists: List<TaskList>,
    initial: TaskDraft,
    hasUnsupportedRecurrence: Boolean,
    canRepeat: Boolean,
    expanded: Expanded,
    onExpand: (Expanded) -> Unit,
    onSetExpanded: (Expanded) -> Unit,
    onOpenRepeat: () -> Unit,
    onOpenAlerts: () -> Unit,
    onDelete: (() -> Unit)?,
    res: android.content.res.Resources,
    locale: Locale,
    now: LocalDateTime,
) {
    val c = Ios.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew) runCatching { focus.requestFocus() } }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }

    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            IosTextField(
                state.title, { state.title = it }, stringResource(R.string.editor_title_hint),
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        }
        RowDivider()
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            IosTextField(
                state.notes, { state.notes = it }, stringResource(R.string.editor_notes_hint),
                singleLine = false, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        IosRow(onClick = { if (state.hasDate) onExpand(Expanded.DATE) }, minHeight = 52.dp) {
            SquareBadge(Icons.Filled.DateRange, c.red)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_date), style = Ios.type.body, color = c.label)
                if (state.hasDate) Text(state.date.format(dateFormat), style = Ios.type.caption, color = c.blue)
            }
            IosSwitch(state.hasDate, {
                state.hasDate = it
                if (it) onSetExpanded(Expanded.DATE) else {
                    state.hasTime = false; state.recurrence = state.recurrence.takeIf { _ -> initial.start != null }; onSetExpanded(Expanded.NONE)
                    if (initial.start == null) state.reminders = state.reminders.filterIsInstance<Reminder.Absolute>()
                }
            })
        }
        AnimatedVisibility(state.hasDate && expanded == Expanded.DATE, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                RowDivider()
                IosCalendar(state.date, { state.date = it }, locale = locale, today = now.toLocalDate())
            }
        }
        RowDivider(startInset = 58.dp)
        IosRow(onClick = { if (state.hasTime) onExpand(Expanded.TIME) }, minHeight = 52.dp) {
            SquareBadge(ClockIcon, c.blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_time), style = Ios.type.body, color = c.label)
                if (state.hasTime) Text(state.time.format(timeFormat), style = Ios.type.caption, color = c.blue)
            }
            IosSwitch(state.hasTime, {
                state.hasTime = it
                state.reminders = Reminder.convertAnchor(state.reminders, toAllDay = !it)
                if (it) { state.hasDate = true; onSetExpanded(Expanded.TIME) } else onSetExpanded(Expanded.NONE)
            })
        }
        AnimatedVisibility(state.hasTime && expanded == Expanded.TIME, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                RowDivider()
                IosTimePicker(state.time, { state.time = it })
            }
        }
        RowDivider(startInset = 58.dp)
        IosRow(onClick = if (canRepeat) onOpenRepeat else null, minHeight = 52.dp) {
            SquareBadge(Icons.Filled.Refresh, c.green)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.editor_repeat), style = Ios.type.body, color = if (canRepeat) c.label else c.secondaryLabel, modifier = Modifier.weight(1f))
            Text(
                state.recurrence?.let { RecurrenceText.describe(res, it, locale) } ?: stringResource(R.string.repeat_never),
                style = Ios.type.body, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f),
            )
            Spacer(Modifier.width(8.dp))
            if (canRepeat) Chevron()
        }
        val canAlert = canRepeat || state.reminders.isNotEmpty()
        RowDivider(startInset = 58.dp)
        IosRow(onClick = if (canAlert) onOpenAlerts else null, minHeight = 52.dp) {
            SquareBadge(Icons.Filled.Notifications, c.orange)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.editor_alerts), style = Ios.type.body, color = if (canAlert) c.label else c.secondaryLabel, modifier = Modifier.weight(1f))
            Text(
                when (state.reminders.size) {
                    0 -> stringResource(R.string.editor_alerts_none)
                    1 -> ReminderText.describe(res, state.reminders.single(), allDay = !state.hasTime, locale = locale)
                    else -> androidx.compose.ui.res.pluralStringResource(R.plurals.editor_alerts_count, state.reminders.size, state.reminders.size)
                },
                style = Ios.type.body, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f),
            )
            Spacer(Modifier.width(8.dp))
            if (canAlert) Chevron()
        }
    }
    if (!canRepeat) SectionFooter(stringResource(R.string.editor_repeat_needs_date))
    if (hasUnsupportedRecurrence && state.recurrence == null) SectionFooter(stringResource(R.string.editor_repeat_unsupported))
    Spacer(Modifier.height(20.dp))
    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.editor_priority), style = Ios.type.caption, color = c.secondaryLabel)
            Spacer(Modifier.height(8.dp))
            IosSegmented(
                listOf(
                    Priority.NONE to stringResource(R.string.priority_none),
                    Priority.LOW to stringResource(R.string.priority_low),
                    Priority.MEDIUM to stringResource(R.string.priority_medium),
                    Priority.HIGH to stringResource(R.string.priority_high),
                ),
                state.priority, { state.priority = it },
            )
        }
        if (isNew && lists.size > 1) {
            RowDivider()
            ListPicker(lists, state.listUrl) { state.listUrl = it.url }
        }
    }
    if (onDelete != null) {
        Spacer(Modifier.height(20.dp))
        GroupedCard(Modifier.padding(horizontal = 16.dp)) {
            IosRow(onClick = onDelete, minHeight = 50.dp) {
                Text(stringResource(R.string.editor_delete), style = Ios.type.body, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ListPicker(lists: List<TaskList>, selectedUrl: String, onSelect: (TaskList) -> Unit) {
    val c = Ios.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val writable = lists.filter { it.canWrite }
    val current = lists.firstOrNull { it.url == selectedUrl }
    IosRow(onClick = { open = true }) {
        Text(stringResource(R.string.editor_list), style = Ios.type.body, color = c.label, modifier = Modifier.weight(1f))
        if (current != null) {
            Box(Modifier.width(10.dp).height(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(listColor(current)))
            Spacer(Modifier.width(8.dp))
            Text(current.name, style = Ios.type.body, color = c.secondaryLabel, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Chevron(direction = ChevronDirection.Down, color = c.tertiaryLabel)
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card) {
            writable.forEach { list ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(list.name, style = Ios.type.body, color = c.label) },
                    onClick = { open = false; onSelect(list) },
                )
            }
        }
    }
}
