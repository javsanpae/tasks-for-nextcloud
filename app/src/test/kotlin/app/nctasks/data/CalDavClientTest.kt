// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.auth.Credentials
import app.nctasks.data.caldav.CalDavClient
import app.nctasks.data.caldav.DavException
import app.nctasks.data.caldav.HttpClients
import app.nctasks.data.ical.TodoCodec
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class CalDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val creds = Credentials(server.url("/"), "alice@example.com", "app-pass")
        client = CalDavClient(HttpClients.authenticated(), creds, TodoCodec(ZoneId.of("UTC")))
    }

    @After fun tearDown() = server.close()

    private fun xml(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/xml").body(body).build()

    private fun enqueueDiscovery(homeHref: String = "/remote.php/dav/calendars/alice/") {
        server.enqueue(xml(207, """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop>
            <d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
        server.enqueue(xml(207, """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop>
            <c:calendar-home-set><d:href>$homeHref</d:href></c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
    }

    private val listing = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
 <d:response><d:href>/remote.php/dav/calendars/alice/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/personal/</d:href><d:propstat><d:prop><d:displayname>Personal</d:displayname>
   <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop><d:displayname>Shopping</d:displayname><a:calendar-color>#FF9500FF</a:calendar-color>
   <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/alice/shared/</d:href><d:propstat><d:prop><d:displayname>Shared (read only)</d:displayname>
   <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test fun `discovers home then lists only task collections`() = runTest {
        enqueueDiscovery()
        server.enqueue(xml(207, listing))
        val lists = client.taskLists()
        assertEquals(listOf("Shopping", "Shared (read only)"), lists.map { it.name })
        assertEquals(0xFFFF9500.toInt(), lists[0].color)
        assertEquals(true, lists[0].canWrite)
        assertEquals(false, lists[1].canWrite)
        assertEquals(server.url("/remote.php/dav/calendars/alice/tasks/").toString(), lists[0].url)

        val first = server.takeRequest()
        assertEquals("PROPFIND", first.method)
        assertEquals("/remote.php/dav/", first.url.encodedPath)
        assertTrue(first.headers["Authorization"]!!.startsWith("Basic "))
        server.takeRequest()
        val third = server.takeRequest()
        assertEquals("1", third.headers["Depth"])
    }

    @Test fun `home set is discovered once`() = runTest {
        enqueueDiscovery(); server.enqueue(xml(207, listing)); server.enqueue(xml(207, listing))
        client.taskLists(); client.taskLists()
        assertEquals(4, server.requestCount)
    }

    @Test fun `fetches tasks and skips malformed items`() = runTest {
        val ok = "BEGIN:VCALENDAR\nBEGIN:VTODO\nUID:a\nSUMMARY:One\nEND:VTODO\nEND:VCALENDAR"
        server.enqueue(xml(207, """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:response><d:href>/remote.php/dav/calendars/alice/tasks/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag><c:calendar-data><![CDATA[$ok]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/calendars/alice/tasks/b.ics</d:href><d:propstat><d:prop><d:getetag>"e2"</d:getetag><c:calendar-data>garbage</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""))
        val list = app.nctasks.domain.TaskList(server.url("/remote.php/dav/calendars/alice/tasks/").toString(), "T", null)
        val tasks = client.tasks(list)
        assertEquals(1, tasks.size)
        assertEquals("One", tasks[0].title)
        assertEquals("\"e1\"", tasks[0].etag)
        assertEquals(server.url("/remote.php/dav/calendars/alice/tasks/a.ics").toString(), tasks[0].url)
        val req = server.takeRequest()
        assertEquals("REPORT", req.method)
        assertTrue(req.body!!.utf8().contains("VTODO"))
    }

    @Test fun `create sends If-None-Match and returns the stored task`() = runTest {
        val listUrl = server.url("/remote.php/dav/calendars/alice/tasks/").toString()
        server.enqueue(MockResponse.Builder().code(201).build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"new\"")
            .body("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:u1\r\nSUMMARY:Hi\r\nEND:VTODO\r\nEND:VCALENDAR\r\n").build())
        val task = client.create(listUrl, "${listUrl}u1.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")
        assertEquals("\"new\"", task.etag)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("*", put.headers["If-None-Match"])
        assertEquals("/remote.php/dav/calendars/alice/tasks/u1.ics", put.url.encodedPath)
        assertTrue(put.headers["Content-Type"]!!.startsWith("text/calendar"))
    }

    @Test fun `update sends If-Match and maps 412 to CONFLICT`() = runTest {
        server.enqueue(MockResponse.Builder().code(412).build())
        try {
            client.update(server.url("/l/").toString(), server.url("/l/a.ics").toString(), "\"old\"", "x")
            fail()
        } catch (e: DavException) {
            assertEquals(DavException.Kind.CONFLICT, e.kind)
        }
        assertEquals("\"old\"", server.takeRequest().headers["If-Match"])
    }

    @Test fun `delete is conditional`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.delete(server.url("/l/a.ics").toString(), "\"e\"")
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("\"e\"", r.headers["If-Match"])
    }

    @Test fun `maps status codes`() = runTest {
        suspend fun kindFor(code: Int): DavException.Kind {
            server.enqueue(MockResponse.Builder().code(code).build())
            return try { client.delete(server.url("/l/a.ics").toString(), null); error("no exception") } catch (e: DavException) { e.kind }
        }
        assertEquals(DavException.Kind.UNAUTHORIZED, kindFor(401))
        assertEquals(DavException.Kind.FORBIDDEN, kindFor(403))
        assertEquals(DavException.Kind.NOT_FOUND, kindFor(404))
        assertEquals(DavException.Kind.SERVER, kindFor(500))
    }

    @Test fun `does not follow redirects and never replays credentials elsewhere`() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/steal").toString()).build())
            try { client.delete(server.url("/l/a.ics").toString(), null); fail() } catch (e: DavException) {
                assertEquals(DavException.Kind.REDIRECTED, e.kind)
            }
            assertEquals(0, other.requestCount)
        } finally { other.close() }
    }

    @Test fun `refuses hrefs that point at another origin`() = runTest {
        enqueueDiscovery(homeHref = "https://evil.example/remote.php/dav/calendars/alice/")
        try { client.taskLists(); fail() } catch (e: DavException) {
            assertEquals(DavException.Kind.MALFORMED, e.kind)
        }
    }

    @Test fun `refuses explicit urls on another origin`() = runTest {
        try { client.delete("https://evil.example/x.ics", null); fail() } catch (e: DavException) {
            assertEquals(DavException.Kind.MALFORMED, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `rejects XML with a DOCTYPE (XXE and entity expansion)`() = runTest {
        server.enqueue(xml(207, """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&a;</d:multistatus>"""))
        try { client.taskLists(); fail() } catch (e: DavException) {
            assertEquals(DavException.Kind.MALFORMED, e.kind)
        }
    }

    @Test fun `network failures are reported as NETWORK`() = runTest {
        server.close()
        try { client.delete(server.url("/l/a.ics").toString(), null); fail() } catch (e: DavException) {
            assertEquals(DavException.Kind.NETWORK, e.kind)
        }
    }

}
