// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.ical.ICalParseException
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.ICalText
import app.nctasks.data.ical.ICalWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICalendarTest {
    private val sample = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:1\r\nSUMMARY:Hello\r\n World\r\n" +
        "DUE;TZID=Europe/Madrid:20250310T180000\r\nX-FOO;X-P=\"a;b:c\":bar\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    @Test fun `unfolds lines and parses parameters`() {
        val todo = ICalParser.parse(sample).component("VTODO")!!
        assertEquals("HelloWorld", todo.property("SUMMARY")!!.value)
        assertEquals("Europe/Madrid", todo.property("DUE")!!.param("TZID"))
        assertEquals("a;b:c", todo.property("X-FOO")!!.param("X-P"))
        assertEquals("bar", todo.property("X-FOO")!!.value)
    }

    @Test fun `round trip keeps unknown properties and nested components`() {
        val withAlarm = sample.replace("END:VTODO", "BEGIN:VALARM\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VTODO")
        val out = ICalWriter.write(ICalParser.parse(withAlarm))
        val again = ICalParser.parse(out).component("VTODO")!!
        assertEquals("bar", again.property("X-FOO")!!.value)
        assertEquals("-PT15M", again.component("VALARM")!!.property("TRIGGER")!!.value)
        assertEquals("a;b:c", again.property("X-FOO")!!.param("X-P"))
    }

    @Test fun `folds long lines at 75 octets without splitting multibyte characters`() {
        val text = "é".repeat(100)
        val out = ICalWriter.write(ICalParser.parse("BEGIN:A\r\nSUMMARY:$text\r\nEND:A\r\n"))
        out.split("\r\n").forEach { line -> assertTrue(line.toByteArray().size <= 75) }
        assertEquals(text, ICalParser.parse(out).property("SUMMARY")!!.value)
    }

    @Test fun `accepts bare LF line endings`() {
        assertEquals("1", ICalParser.parse("BEGIN:A\nUID:1\nEND:A\n").property("UID")!!.value)
    }

    @Test fun `escapes and unescapes text`() {
        val raw = "a, b; c\\d\nline2"
        assertEquals("a\\, b\\; c\\\\d\\nline2", ICalText.escape(raw))
        assertEquals(raw, ICalText.unescape(ICalText.escape(raw)))
        assertFalse(ICalText.escape("x\r\ny").contains('\r'))
    }

    @Test(expected = ICalParseException::class) fun `rejects unbalanced input`() {
        ICalParser.parse("BEGIN:A\r\nUID:1\r\n")
    }

    @Test(expected = ICalParseException::class) fun `rejects mismatched end`() {
        ICalParser.parse("BEGIN:A\r\nEND:B\r\n")
    }

    @Test(expected = ICalParseException::class) fun `rejects excessive nesting`() {
        ICalParser.parse((1..20).joinToString("") { "BEGIN:A\r\n" })
    }

    @Test(expected = ICalParseException::class) fun `rejects oversized input`() {
        ICalParser.parse("BEGIN:A\r\nX:" + "a".repeat(2_100_000) + "\r\nEND:A\r\n")
    }
}
