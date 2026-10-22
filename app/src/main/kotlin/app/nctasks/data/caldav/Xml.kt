// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.caldav

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

internal const val NS_DAV = "DAV:"
internal const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
internal const val NS_APPLE = "http://apple.com/ns/ical/"

class XmlElement(val namespace: String, val name: String) {
    val children = ArrayList<XmlElement>()
    val text = StringBuilder()
    val attributes = HashMap<String, String>()

    fun child(ns: String, name: String): XmlElement? = children.firstOrNull { it.namespace == ns && it.name == name }

    fun childrenNamed(ns: String, name: String): List<XmlElement> = children.filter { it.namespace == ns && it.name == name }

    /** Depth-first search for the first descendant, skipping nothing. */
    fun find(ns: String, name: String): XmlElement? {
        for (c in children) {
            if (c.namespace == ns && c.name == name) return c
            c.find(ns, name)?.let { return it }
        }
        return null
    }

    fun textTrimmed(): String = text.toString().trim()
}

class XmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Namespace-aware XML to element-tree parser, hardened against XXE and entity expansion. */
internal object Xml {
    private const val MAX_DEPTH = 64

    fun parse(xml: String): XmlElement {
        // WebDAV/CalDAV responses never contain a DTD. Rejecting them outright removes the whole
        // class of XXE / billion-laughs attacks independent of the parser implementation.
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw XmlParseException("DTDs are not allowed")
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val handler = TreeHandler()
        try {
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(xml)))
        } catch (e: XmlParseException) {
            throw e
        } catch (e: Exception) {
            throw XmlParseException("Malformed XML", e)
        }
        return handler.root ?: throw XmlParseException("Empty document")
    }

    private class TreeHandler : DefaultHandler() {
        var root: XmlElement? = null
        private val stack = ArrayList<XmlElement>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (stack.size >= MAX_DEPTH) throw XmlParseException("XML nesting too deep")
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            val element = XmlElement(uri.orEmpty(), name)
            if (attributes != null) for (i in 0 until attributes.length) element.attributes[attributes.getLocalName(i).orEmpty().ifEmpty { attributes.getQName(i) }] = attributes.getValue(i)
            stack.lastOrNull()?.children?.add(element)
            if (root == null) root = element
            stack.add(element)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            stack.removeLastOrNull()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.text?.append(ch, start, length)
        }

        override fun error(e: org.xml.sax.SAXParseException) = throw e
        override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
    }
}
