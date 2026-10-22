// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.nctasks.data.ical.ICalComponent
import app.nctasks.domain.Priority
import app.nctasks.domain.Recurrence
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.domain.TaskDraft
import app.nctasks.domain.TaskList
import app.nctasks.ui.lists.ListsScreen
import app.nctasks.ui.login.LoginError
import app.nctasks.ui.login.LoginPhase
import app.nctasks.ui.login.LoginScreen
import app.nctasks.ui.login.LoginUiState
import app.nctasks.ui.tasks.TaskEditor
import app.nctasks.ui.tasks.TasksScreen
import app.nctasks.ui.tasks.ToastMessage
import app.nctasks.ui.theme.Ios
import app.nctasks.ui.theme.IosTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Renders the real screens to PNGs under build/screenshots for visual review (no emulator needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-notnight-xxhdpi")
class ScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val now = LocalDateTime.of(2026, 3, 12, 10, 30)
    private val today = now.toLocalDate()
    private val personal = TaskList("https://c/personal/", "Personal", 0xFF0082C9.toInt())
    private val shopping = TaskList("https://c/shopping/", "Shopping", 0xFFFF9500.toInt())
    private val work = TaskList("https://c/work/", "Work", 0xFF34C759.toInt())
    private val readOnly = TaskList("https://c/shared/", "Family (shared)", 0xFFAF52DE.toInt(), canWrite = false)
    private val lists = listOf(personal, shopping, work, readOnly)

    private fun task(title: String, due: TaskDate? = null, notes: String = "", done: Boolean = false, priority: Priority = Priority.NONE, rrule: String? = null) =
        Task("https://c/personal/${title.hashCode()}.ics", "\"e\"", personal.url, title, title, notes, done, if (done) Instant.parse("2026-03-10T10:00:00Z") else null,
            priority, null, due, rrule?.let(Recurrence::parse), false, ICalComponent("VCALENDAR"))

    private val tasks = listOf(
        task("Renew passport", TaskDate(today.minusDays(3)), "Bring two photos and the old passport", priority = Priority.HIGH),
        task("Call the dentist", TaskDate(today, LocalTime.of(16, 0))),
        task("Water the plants", TaskDate(today.plusDays(1)), rrule = "FREQ=WEEKLY;BYDAY=TH"),
        task("Pay rent", TaskDate(today.plusDays(19)), priority = Priority.MEDIUM, rrule = "FREQ=MONTHLY"),
        task("Read “Designing Data-Intensive Applications”", notes = "Chapter 5 next"),
        task("Book flights", done = true),
        task("Cancel old subscription", done = true),
    )

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent { IosTheme(dark = dark) { content() } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun login() = shot("01-login") {
        LoginScreen(LoginUiState(server = "cloud.example.com"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun loginError() = shot("02-login-error") {
        LoginScreen(LoginUiState(server = "cloud.nope.example", error = LoginError.NOT_NEXTCLOUD), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun loginWaiting() = shot("03-login-waiting") {
        LoginScreen(LoginUiState(server = "cloud.example.com", phase = LoginPhase.WAITING_FOR_BROWSER), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun loginManual() = shot("04-login-manual") {
        LoginScreen(LoginUiState(server = "cloud.example.com", manualMode = true, username = "alice", appPassword = "abcd"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun listsScreen() = shot("05-lists") {
        ListsScreen(lists, { l -> if (l == personal) 5 else 2 }, false, false, {}, {}, {})
    }

    @Test fun listsOffline() = shot("05b-lists-offline") {
        ListsScreen(lists, { l -> if (l == personal) 5 else 2 }, false, false, {}, {}, {}, syncLabel = "Offline · 2 changes waiting")
    }

    @Test fun listsDark() = shot("06-lists-dark", dark = true) {
        ListsScreen(lists, { l -> if (l == personal) 5 else 2 }, false, false, {}, {}, {})
    }

    @Composable private fun TasksContent(list: TaskList = personal, showCompleted: Boolean = true, toast: ToastMessage? = null, items: List<Task> = tasks, syncLabel: String? = null) =
        TasksScreen(list, lists, items, showCompleted, false, toast, syncLabel, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, now = now)

    @Test fun tasksScreen() = shot("07-tasks") { TasksContent() }

    @Test fun tasksDark() = shot("08-tasks-dark", dark = true) { TasksContent(toast = ToastMessage("Task deleted", "Undo")) }

    @Test fun tasksOffline() = shot("09b-tasks-offline") { TasksContent(syncLabel = "Offline · 2 changes waiting") }

    @Test fun tasksEmpty() = shot("09-tasks-empty") { TasksContent(list = shopping, items = emptyList()) }

    @Composable private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Ios.colors.fill)) {
            Box(Modifier.fillMaxSize().padding12().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Ios.colors.background)) { content() }
        }
    }

    private fun Modifier.padding12() = this.then(Modifier.padding(top = 36.dp))

    @Composable private fun Editor(initial: TaskDraft = TaskDraft(""), isNew: Boolean = true) = Sheet {
        TaskEditor(initial, isNew, lists, personal, false, { _, _ -> }, {}, if (isNew) null else ({}), now = now)
    }

    @Test fun editorNew() = shot("10-editor-new") { Editor(TaskDraft("Buy oat milk")) }

    @Test fun editorWithDateOpen() {
        rule.setContent { IosTheme { Editor(TaskDraft("Plan the trip")) } }
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/11-editor-date.png")
    }

    @Test fun editorWithTimeOpen() {
        rule.setContent { IosTheme { Editor(TaskDraft("Standup", due = TaskDate(today.plusDays(1), LocalTime.of(9, 30)))) } }
        rule.onNodeWithText("Time").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/12-editor-time.png")
    }

    @Test fun editorExisting() = shot("13-editor-existing") {
        Editor(TaskDraft("Pay rent", "Transfer from the joint account", Priority.MEDIUM, null, TaskDate(today.plusDays(19)), Recurrence.parse("FREQ=MONTHLY")), isNew = false)
    }

    private fun repeatPage(name: String, rrule: String, dark: Boolean = false) {
        rule.setContent { IosTheme(dark = dark) { Editor(TaskDraft("Water the plants", due = TaskDate(today.plusDays(1)), recurrence = Recurrence.parse(rrule))) } }
        rule.onNodeWithText("Repeat").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun repeatWeekly() = repeatPage("14-repeat-weekly", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TH,SA")
    @Test fun repeatMonthlyEnds() = repeatPage("15-repeat-monthly", "FREQ=MONTHLY;BYDAY=2TH;UNTIL=20261231")
    @Test fun repeatDark() = repeatPage("16-repeat-dark", "FREQ=DAILY;COUNT=10", dark = true)
}
