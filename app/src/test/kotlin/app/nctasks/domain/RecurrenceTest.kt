// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {
    private fun rule(s: String) = Recurrence.parse(s)!!
    private fun d(s: String) = LocalDate.parse(s)
    private fun take(rule: String, start: String, n: Int) =
        RecurrenceCalculator.occurrences(rule(rule), d(start)).take(n).map { it.toString() }.toList()

    @Test fun `parse and serialize are inverse for supported parts`() {
        val s = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=5"
        assertEquals(s, rule(s).toRRule())
        assertEquals("FREQ=MONTHLY;BYDAY=2TU,-1FR;UNTIL=20271231", rule("FREQ=MONTHLY;BYDAY=2TU,-1FR;UNTIL=20271231T000000Z").toRRule())
    }

    @Test fun `unknown parts are preserved`() {
        assertEquals("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1", rule("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1").toRRule())
    }

    @Test fun `unsupported or malformed rules parse to null`() {
        assertNull(Recurrence.parse("FREQ=HOURLY"))
        assertNull(Recurrence.parse("INTERVAL=2"))
        assertNull(Recurrence.parse("FREQ=DAILY;INTERVAL=abc"))
        assertNull(Recurrence.parse("FREQ=MONTHLY;BYMONTHDAY=0"))
        assertNull(Recurrence.parse("FREQ=DAILY;INTERVAL=0"))
        assertNotNull(Recurrence.parse("freq=daily"))
    }

    @Test fun daily() {
        assertEquals(listOf("2025-01-30", "2025-02-01", "2025-02-03"), take("FREQ=DAILY;INTERVAL=2", "2025-01-30", 3))
    }

    @Test fun `weekly on several days`() {
        // 2025-03-03 is a Monday
        assertEquals(listOf("2025-03-03", "2025-03-05", "2025-03-10", "2025-03-12"), take("FREQ=WEEKLY;BYDAY=MO,WE", "2025-03-03", 4))
    }

    @Test fun `weekly defaults to the start weekday and honours interval`() {
        assertEquals(listOf("2025-03-05", "2025-03-19", "2025-04-02"), take("FREQ=WEEKLY;INTERVAL=2", "2025-03-05", 3))
    }

    @Test fun `monthly on the 31st skips short months`() {
        assertEquals(listOf("2025-01-31", "2025-03-31", "2025-05-31"), take("FREQ=MONTHLY", "2025-01-31", 3))
    }

    @Test fun `monthly last day via negative month day`() {
        assertEquals(listOf("2025-01-31", "2025-02-28", "2025-03-31"), take("FREQ=MONTHLY;BYMONTHDAY=-1", "2025-01-31", 3))
    }

    @Test fun `monthly nth weekday`() {
        assertEquals(listOf("2025-01-14", "2025-02-11", "2025-03-11"), take("FREQ=MONTHLY;BYDAY=2TU", "2025-01-14", 3))
        assertEquals(listOf("2025-01-31", "2025-02-28"), take("FREQ=MONTHLY;BYDAY=-1FR", "2025-01-31", 2))
    }

    @Test fun yearly() {
        assertEquals(listOf("2025-07-04", "2026-07-04", "2027-07-04"), take("FREQ=YEARLY", "2025-07-04", 3))
        assertEquals(listOf("2024-02-29", "2028-02-29"), take("FREQ=YEARLY", "2024-02-29", 2))
    }

    @Test fun `count and until end the series`() {
        assertEquals(3, RecurrenceCalculator.occurrences(rule("FREQ=DAILY;COUNT=3"), d("2025-01-01")).count())
        assertEquals(listOf("2025-01-01", "2025-01-02"), take("FREQ=DAILY;UNTIL=20250102", "2025-01-01", 10))
    }

    @Test fun `nextAfter returns null when the series is over`() {
        assertNull(RecurrenceCalculator.nextAfter(rule("FREQ=DAILY;COUNT=2"), d("2025-01-01"), d("2025-01-02")))
        assertEquals(d("2025-01-02"), RecurrenceCalculator.nextAfter(rule("FREQ=DAILY"), d("2025-01-01"), d("2025-01-01")))
    }

    @Test fun `impossible rules terminate`() {
        assertEquals(0, RecurrenceCalculator.occurrences(rule("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30"), d("2025-01-01")).count())
    }

    @Test fun `week start changes biweekly grouping`() {
        val r = rule("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU;WKST=SU")
        assertEquals(DayOfWeek.SUNDAY, r.weekStart)
        assertEquals(listOf("2025-03-02", "2025-03-16"), take("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU;WKST=SU", "2025-03-02", 2))
    }
}
