// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.caldav

import app.nctasks.data.auth.Credentials
import app.nctasks.data.auth.await
import app.nctasks.data.ical.ICalParseException
import app.nctasks.data.ical.ICalParser
import app.nctasks.data.ical.TodoCodec
import app.nctasks.data.repo.RemoteTasks
import app.nctasks.domain.Task
import app.nctasks.domain.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.nio.charset.StandardCharsets

class DavException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [REJECTED]: the server refused this particular request (4xx) and repeating it will not help. */
    enum class Kind { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, REDIRECTED, SERVER, REJECTED, NETWORK, MALFORMED }
}

/**
 * Thin CalDAV client (RFC 4791) scoped to what a task app needs. All paths are discovered from the
 * server (current-user-principal -> calendar-home-set), so login names that differ from the user id
 * (e-mail logins, LDAP) work.
 */
class CalDavClient(
    private val http: OkHttpClient,
    private val credentials: Credentials,
    private val codec: TodoCodec,
) : RemoteTasks {
    private val authHeader = okhttp3.Credentials.basic(credentials.loginName, credentials.appPassword, StandardCharsets.UTF_8)
    private var homeSet: HttpUrl? = null

    override suspend fun taskLists(): List<TaskList> {
        val home = home()
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="$NS_CALDAV" xmlns:a="$NS_APPLE">
  <d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/><a:calendar-color/><d:current-user-privilege-set/></d:prop>
</d:propfind>"""
        val root = xmlRequest("PROPFIND", home, body, depth = "1")
        return root.childrenNamed(NS_DAV, "response").mapNotNull { parseTaskList(it, home) }
    }

    override suspend fun tasks(list: TaskList): List<Task> {
        val url = list.url.toHttpUrlOrThrow()
        val body = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="$NS_CALDAV">
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO"/></c:comp-filter></c:filter>
</c:calendar-query>"""
        val root = xmlRequest("REPORT", url, body, depth = "1")
        return root.childrenNamed(NS_DAV, "response").mapNotNull { r ->
            val prop = r.childrenNamed(NS_DAV, "propstat").firstOrNull { it.child(NS_DAV, "status")?.textTrimmed()?.contains(" 200") == true }
                ?.child(NS_DAV, "prop") ?: return@mapNotNull null
            val data = prop.child(NS_CALDAV, "calendar-data")?.text?.toString() ?: return@mapNotNull null
            val href = r.child(NS_DAV, "href")?.textTrimmed() ?: return@mapNotNull null
            val resourceUrl = resolveSameOrigin(url, href) ?: return@mapNotNull null
            try {
                codec.decode(resourceUrl.toString(), prop.child(NS_DAV, "getetag")?.textTrimmed(), list.url, ICalParser.parse(data))
            } catch (_: ICalParseException) {
                null // one malformed item must not hide the rest of the list
            }
        }
    }

    /** Creates a new resource at [url]; fails with CONFLICT if the name is taken. Returns the stored task. */
    override suspend fun create(listUrl: String, url: String, ics: String): Task =
        put(listUrl, url, ics, ifMatch = null, ifNoneMatch = "*")

    /** Overwrites [url] only if it still has [etag] (optimistic concurrency); CONFLICT otherwise. */
    override suspend fun update(listUrl: String, url: String, etag: String?, ics: String): Task =
        put(listUrl, url, ics, ifMatch = etag, ifNoneMatch = null)

    override suspend fun delete(url: String, etag: String?) {
        val target = url.toHttpUrlOrThrow()
        ensureSameOrigin(target)
        val request = Request.Builder().url(target).delete().header("If-Match", etag ?: "*").build()
        execute(request).use { }
    }

    private suspend fun put(listUrl: String, url: String, ics: String, ifMatch: String?, ifNoneMatch: String?): Task {
        val target = url.toHttpUrlOrThrow()
        ensureSameOrigin(target)
        val builder = Request.Builder().url(target)
            .put(ics.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
        ifMatch?.let { builder.header("If-Match", it) }
        ifNoneMatch?.let { builder.header("If-None-Match", it) }
        execute(builder.build()).use { }
        return fetch(listUrl, target)
    }

    /** Fetches a single task, or null when it no longer exists. */
    override suspend fun task(listUrl: String, url: String): Task? {
        val target = url.toHttpUrlOrThrow()
        ensureSameOrigin(target)
        return try {
            fetch(listUrl, target)
        } catch (e: DavException) {
            if (e.kind == DavException.Kind.NOT_FOUND) null else throw e
        }
    }

    /** The server may normalise what we stored and only reports ETags reliably on GET. */
    private suspend fun fetch(listUrl: String, url: HttpUrl): Task {
        val request = Request.Builder().url(url).get().build()
        execute(request).use { response ->
            val body = response.readLimited()
            return try {
                codec.decode(url.toString(), response.header("ETag"), listUrl, ICalParser.parse(body))
                    ?: throw DavException(DavException.Kind.MALFORMED, "Stored item is not a task")
            } catch (e: ICalParseException) {
                throw DavException(DavException.Kind.MALFORMED, "Server returned invalid calendar data", e)
            }
        }
    }

    private suspend fun home(): HttpUrl {
        homeSet?.let { return it }
        val base = credentials.server.newBuilder().addPathSegments("remote.php/dav/").build()
        val principalBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""
        val principalHref = xmlRequest("PROPFIND", base, principalBody, depth = "0")
            .find(NS_DAV, "current-user-principal")?.child(NS_DAV, "href")?.textTrimmed()
            ?: throw DavException(DavException.Kind.MALFORMED, "Server did not report a user principal")
        val principal = resolveSameOrigin(base, principalHref)
            ?: throw DavException(DavException.Kind.MALFORMED, "Unexpected principal location")
        val homeBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="$NS_CALDAV"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
        val homeHref = xmlRequest("PROPFIND", principal, homeBody, depth = "0")
            .find(NS_CALDAV, "calendar-home-set")?.child(NS_DAV, "href")?.textTrimmed()
            ?: throw DavException(DavException.Kind.MALFORMED, "Server did not report a calendar home")
        return (resolveSameOrigin(principal, homeHref)
            ?: throw DavException(DavException.Kind.MALFORMED, "Unexpected calendar home location"))
            .also { homeSet = it }
    }

    private fun parseTaskList(response: XmlElement, home: HttpUrl): TaskList? {
        val href = response.child(NS_DAV, "href")?.textTrimmed() ?: return null
        val prop = response.childrenNamed(NS_DAV, "propstat")
            .firstOrNull { it.child(NS_DAV, "status")?.textTrimmed()?.contains(" 200") == true }
            ?.child(NS_DAV, "prop") ?: return null
        if (prop.child(NS_DAV, "resourcetype")?.child(NS_CALDAV, "calendar") == null) return null
        val supportsTodo = prop.child(NS_CALDAV, "supported-calendar-component-set")
            ?.childrenNamed(NS_CALDAV, "comp")?.any { it.attributes["name"].equals("VTODO", ignoreCase = true) } == true
        if (!supportsTodo) return null
        val url = resolveSameOrigin(home, href) ?: return null
        val privileges = prop.child(NS_DAV, "current-user-privilege-set")?.childrenNamed(NS_DAV, "privilege").orEmpty()
        val canWrite = privileges.isEmpty() || privileges.any { p ->
            p.children.any { it.namespace == NS_DAV && it.name in WRITE_PRIVILEGES }
        }
        val dir = if (url.toString().endsWith("/")) url.toString() else "$url/"
        val name = prop.child(NS_DAV, "displayname")?.textTrimmed().takeUnless { it.isNullOrEmpty() }
            ?: url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
        return TaskList(dir, name, parseColor(prop.child(NS_APPLE, "calendar-color")?.textTrimmed()), canWrite)
    }

    private suspend fun xmlRequest(method: String, url: HttpUrl, body: String, depth: String): XmlElement {
        ensureSameOrigin(url)
        val request = Request.Builder().url(url)
            .method(method, body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth)
            .build()
        execute(request).use { response ->
            val text = response.readLimited()
            return try {
                Xml.parse(text)
            } catch (e: XmlParseException) {
                throw DavException(DavException.Kind.MALFORMED, "Server returned invalid XML", e)
            }
        }
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val authed = request.newBuilder().header("Authorization", authHeader).header("User-Agent", USER_AGENT).build()
        val response = try {
            http.newCall(authed).await()
        } catch (e: IOException) {
            throw DavException(DavException.Kind.NETWORK, "Network error", e)
        }
        val kind = when {
            response.isSuccessful -> null
            response.code == 401 -> DavException.Kind.UNAUTHORIZED
            response.code == 403 -> DavException.Kind.FORBIDDEN
            response.code == 404 -> DavException.Kind.NOT_FOUND
            response.code == 409 || response.code == 412 -> DavException.Kind.CONFLICT
            response.isRedirect -> DavException.Kind.REDIRECTED
            response.code >= 500 || response.code == 429 -> DavException.Kind.SERVER
            else -> DavException.Kind.REJECTED
        }
        if (kind != null) {
            response.close()
            throw DavException(kind, "HTTP ${response.code}")
        }
        response
    }

    private fun ensureSameOrigin(url: HttpUrl) {
        val server = credentials.server
        if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) {
            throw DavException(DavException.Kind.MALFORMED, "Refusing to contact a different server")
        }
    }

    /** Resolves a server-supplied href, refusing anything that points at another origin. */
    private fun resolveSameOrigin(base: HttpUrl, href: String): HttpUrl? =
        base.resolve(href)?.takeIf { it.scheme == base.scheme && it.host == base.host && it.port == base.port }

    private fun String.toHttpUrlOrThrow(): HttpUrl =
        toHttpUrlOrNull() ?: throw DavException(DavException.Kind.MALFORMED, "Invalid URL")

    private companion object {
        const val USER_AGENT = "nctasks-android"
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        val WRITE_PRIVILEGES = setOf("write", "write-content", "all", "bind")

        fun parseColor(value: String?): Int? {
            val v = value?.trim()?.removePrefix("#") ?: return null
            if (!Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(v)) return null
            return 0xFF000000.toInt() or v.take(6).toInt(16)
        }

        fun Response.readLimited(): String {
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) throw DavException(DavException.Kind.MALFORMED, "Response too large")
            return source.readString(StandardCharsets.UTF_8)
        }
    }
}
