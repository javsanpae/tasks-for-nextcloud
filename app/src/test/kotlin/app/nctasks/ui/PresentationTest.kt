// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui

import androidx.test.core.app.ApplicationProvider
import app.nctasks.data.ical.ICalComponent
import app.nctasks.domain.Priority
import app.nctasks.domain.Task
import app.nctasks.domain.TaskDate
import app.nctasks.ui.tasks.DueText
import app.nctasks.ui.tasks.TaskSorting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresentationTest {
    private val now = LocalDateTime.of(2026, 3, 12, 10, 30)
    private val today = now.toLocalDate()
    private fun t(title: String, due: TaskDate? = null, p: Int = Priority.NONE, done: Boolean = false, at: String? = null) =
        Task("u/$title", null, "l", title, title, "", done, at?.let(Instant::parse), p, null, due, null, false, ICalComponent("VCALENDAR"))

    @Test fun `open tasks sort by due date then priority then title, undated last`() {
        val sorted = TaskSorting.open(listOf(
            t("undated"), t("b-later", TaskDate(today.plusDays(2))), t("a-low", TaskDate(today), Priority.LOW),
            t("z-high", TaskDate(today), Priority.HIGH), t("done", done = true),
        )).map { it.title }
        assertEquals(listOf("z-high", "a-low", "b-later", "undated"), sorted)
    }

    @Test fun `priority sorts 1 first and unset last`() {
        val sorted = TaskSorting.open(listOf(t("none"), t("nine", p = 9), t("one", p = 1), t("five", p = 5))).map { it.title }
        assertEquals(listOf("one", "five", "nine", "none"), sorted)
    }

    @Test fun `completed tasks sort newest first`() {
        val sorted = TaskSorting.completed(listOf(
            t("old", done = true, at = "2026-01-01T00:00:00Z"), t("new", done = true, at = "2026-03-01T00:00:00Z"), t("open"),
        )).map { it.title }
        assertEquals(listOf("new", "old"), sorted)
    }

    @Test fun `due labels are relative and flag overdue`() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals("Today", DueText.format(res, TaskDate(today), now, Locale.US).text)
        assertEquals("Tomorrow", DueText.format(res, TaskDate(today.plusDays(1)), now, Locale.US).text)
        assertEquals("Yesterday", DueText.format(res, TaskDate(today.minusDays(1)), now, Locale.US).text)
        assertFalse(DueText.format(res, TaskDate(today), now, Locale.US).isOverdue) // all-day today is not overdue
        assertTrue(DueText.format(res, TaskDate(today.minusDays(1)), now, Locale.US).isOverdue)
        val timed = DueText.format(res, TaskDate(today, LocalTime.of(9, 0)), now, Locale.US)
        assertTrue(timed.isOverdue) // 09:00 has passed at 10:30
        assertTrue(timed.text.startsWith("Today, 9:00"))
        assertEquals("Mar 20, 2026", DueText.format(res, TaskDate(LocalDate.of(2026, 3, 20)), now, Locale.US).text)
    }
}
