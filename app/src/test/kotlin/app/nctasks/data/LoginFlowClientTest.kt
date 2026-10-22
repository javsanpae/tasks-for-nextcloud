// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.auth.LoginException
import app.nctasks.data.auth.LoginFlowClient
import app.nctasks.data.auth.LoginSession
import app.nctasks.data.caldav.HttpClients
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LoginFlowClientTest {
    private lateinit var server: MockWebServer
    private val client = LoginFlowClient(HttpClients.anonymous())

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Test fun `start posts to login v2 and refuses non-https URLs from the server`() = runTest {
        server.enqueue(json(200, """{"poll":{"token":"t","endpoint":"http://x/poll"},"login":"http://x/login"}"""))
        try { client.start(server.url("/")); fail() } catch (e: LoginException) {
            assertEquals(LoginException.Kind.INSECURE, e.kind)
        }
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/index.php/login/v2", req.url.encodedPath)
    }

    @Test fun `start parses a valid response`() = runTest {
        server.enqueue(json(200, """{"poll":{"token":"secret","endpoint":"https://cloud.example.com/login/v2/poll"},"login":"https://cloud.example.com/login/v2/flow/abc"}"""))
        val s = client.start(server.url("/"))
        assertEquals("https://cloud.example.com/login/v2/flow/abc", s.loginUrl.toString())
        assertTrue("token must not be printable", !s.toString().contains("secret"))
    }

    @Test fun `start on a non-Nextcloud server`() = runTest {
        server.enqueue(json(404, "nope"))
        try { client.start(server.url("/")); fail() } catch (e: LoginException) {
            assertEquals(LoginException.Kind.NOT_NEXTCLOUD, e.kind)
        }
        server.enqueue(json(200, "<html>hello</html>"))
        try { client.start(server.url("/")); fail() } catch (e: LoginException) {
            assertEquals(LoginException.Kind.NOT_NEXTCLOUD, e.kind)
        }
    }

    private fun session() = LoginSession("https://cloud.example.com/".toHttpUrl(), "https://cloud.example.com/l".toHttpUrl(), server.url("/poll"), "tok")

    @Test fun `poll returns null until approved then credentials`() = runTest {
        server.enqueue(json(404, ""))
        assertNull(client.poll(session()))
        server.enqueue(json(200, """{"server":"https://cloud.example.com","loginName":"alice","appPassword":"abcd-efgh"}"""))
        val c = client.poll(session())!!
        assertEquals("alice", c.loginName)
        assertEquals("abcd-efgh", c.appPassword)
        assertTrue(!c.toString().contains("abcd"))
        server.takeRequest()
        val req = server.takeRequest()
        assertEquals("token=tok", req.body!!.utf8())
    }

    @Test fun `poll refuses a non-https server in the result`() = runTest {
        server.enqueue(json(200, """{"server":"http://cloud.example.com","loginName":"a","appPassword":"b"}"""))
        try { client.poll(session()); fail() } catch (e: LoginException) {
            assertEquals(LoginException.Kind.INSECURE, e.kind)
        }
    }

    @Test fun `revoke is best effort and sends OCS header`() = runTest {
        server.enqueue(json(200, "{}"))
        val creds = app.nctasks.data.auth.Credentials(server.url("/"), "alice", "pw")
        client.revoke(creds)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/ocs/v2.php/core/apppassword", req.url.encodedPath)
        assertEquals("true", req.headers["OCS-APIRequest"])
        assertNotNull(req.headers["Authorization"])
        server.close()
        client.revoke(creds) // must not throw when the server is unreachable
    }
}
