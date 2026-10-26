// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.ical

/**
 * Minimal, lossless RFC 5545 content-line tree.
 *
 * Values are kept exactly as they appear on the wire (still escaped); helpers convert on demand.
 * Everything the app does not understand (VALARM, VTIMEZONE, X- properties, ...) is therefore
 * written back untouched when a task is edited, so other clients' data is never lost.
 */
class ICalProperty(
    val name: String,
    val params: List<Pair<String, String>>,
    val value: String,
) {
    fun param(key: String): String? =
        params.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
}

class ICalComponent(
    val name: String,
    val properties: List<ICalProperty> = emptyList(),
    val components: List<ICalComponent> = emptyList(),
) {
    fun property(name: String): ICalProperty? =
        properties.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun component(name: String): ICalComponent? =
        components.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Replaces every [name] property with [property] (or removes them when it is null). */
    fun withProperty(name: String, property: ICalProperty?): ICalComponent {
        val kept = properties.filterNot { it.name.equals(name, ignoreCase = true) }
        if (property == null) return ICalComponent(this.name, kept, components)
        val index = properties.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        val result = kept.toMutableList()
        result.add(if (index in 0..result.size) index else result.size, property)
        return ICalComponent(this.name, result, components)
    }

    fun withComponents(components: List<ICalComponent>): ICalComponent = ICalComponent(name, properties, components)

    fun withChild(old: ICalComponent, new: ICalComponent): ICalComponent =
        ICalComponent(name, properties, components.map { if (it === old) new else it })
}

class ICalParseException(message: String) : Exception(message)

object ICalParser {
    private const val MAX_INPUT_CHARS = 2_000_000
    private const val MAX_DEPTH = 8

    /** Parses the first top-level component (normally VCALENDAR). */
    fun parse(text: String): ICalComponent {
        if (text.length > MAX_INPUT_CHARS) throw ICalParseException("iCalendar data too large")
        val root = ArrayList<ICalComponent>()
        val stack = ArrayList<Builder>()
        for (line in unfold(text)) {
            if (line.isEmpty()) continue
            val prop = parseLine(line)
            when {
                prop.name.equals("BEGIN", true) -> {
                    if (stack.size >= MAX_DEPTH) throw ICalParseException("iCalendar nesting too deep")
                    stack.add(Builder(prop.value.trim().uppercase()))
                }
                prop.name.equals("END", true) -> {
                    val done = stack.removeLastOrNull() ?: throw ICalParseException("Unbalanced END")
                    if (!done.name.equals(prop.value.trim(), true)) throw ICalParseException("Mismatched END")
                    val component = done.build()
                    if (stack.isEmpty()) root.add(component) else stack.last().components.add(component)
                }
                else -> (stack.lastOrNull() ?: throw ICalParseException("Property outside component"))
                    .properties.add(prop)
            }
        }
        if (stack.isNotEmpty()) throw ICalParseException("Unterminated component")
        return root.firstOrNull() ?: throw ICalParseException("No component found")
    }

    private class Builder(val name: String) {
        val properties = ArrayList<ICalProperty>()
        val components = ArrayList<ICalComponent>()
        fun build() = ICalComponent(name, properties, components)
    }

    private fun unfold(text: String): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var started = false
        for (raw in text.split("\r\n", "\n", "\r")) {
            if (started && raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) {
                current.append(raw, 1, raw.length)
            } else {
                if (started) lines.add(current.toString())
                current.setLength(0)
                current.append(raw)
                started = true
            }
        }
        if (started) lines.add(current.toString())
        return lines
    }

    private fun parseLine(line: String): ICalProperty {
        var i = 0
        var inQuote = false
        var valueStart = -1
        val segments = ArrayList<String>()
        var segStart = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuote = !inQuote
                !inQuote && c == ';' && valueStart < 0 -> {
                    segments.add(line.substring(segStart, i)); segStart = i + 1
                }
                !inQuote && c == ':' -> {
                    segments.add(line.substring(segStart, i)); valueStart = i + 1; break
                }
            }
            i++
        }
        if (valueStart < 0) throw ICalParseException("Malformed content line")
        val params = segments.drop(1).map { seg ->
            val eq = seg.indexOf('=')
            if (eq < 0) seg.uppercase() to "" else seg.substring(0, eq).uppercase() to seg.substring(eq + 1).trim('"')
        }
        return ICalProperty(segments[0].trim().uppercase(), params, line.substring(valueStart))
    }
}

object ICalWriter {
    private const val FOLD_OCTETS = 75

    fun write(root: ICalComponent): String {
        val out = StringBuilder()
        writeComponent(root, out)
        return out.toString()
    }

    private fun writeComponent(c: ICalComponent, out: StringBuilder) {
        out.append("BEGIN:").append(c.name).append("\r\n")
        for (p in c.properties) writeLine(formatProperty(p), out)
        for (child in c.components) writeComponent(child, out)
        out.append("END:").append(c.name).append("\r\n")
    }

    private fun formatProperty(p: ICalProperty): String {
        val sb = StringBuilder(p.name)
        for ((k, v) in p.params) {
            sb.append(';').append(k)
            if (v.isNotEmpty()) {
                sb.append('=')
                if (v.any { it == ';' || it == ':' || it == ',' }) sb.append('"').append(v).append('"') else sb.append(v)
            }
        }
        return sb.append(':').append(p.value).toString()
    }

    /** Folds at 75 octets without splitting UTF-8 sequences. */
    private fun writeLine(line: String, out: StringBuilder) {
        var octets = 0
        var limit = FOLD_OCTETS
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (octets + size > limit) {
                out.append("\r\n ")
                octets = 0
                limit = FOLD_OCTETS - 1
            }
            out.appendCodePoint(cp)
            octets += size
            i += Character.charCount(cp)
        }
        out.append("\r\n")
    }
}

object ICalText {
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) when (c) {
            '\\' -> sb.append("\\\\")
            ';' -> sb.append("\\;")
            ',' -> sb.append("\\,")
            '\n' -> sb.append("\\n")
            '\r' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun unescape(text: String): String {
        if ('\\' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
