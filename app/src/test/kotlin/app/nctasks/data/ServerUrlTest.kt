// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import app.nctasks.data.auth.ServerUrl
import app.nctasks.data.auth.ServerUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun ok(input: String): String = (ServerUrl.normalize(input) as ServerUrlResult.Valid).url.toString()

    @Test fun `adds https and trailing slash`() = assertEquals("https://cloud.example.com/", ok("cloud.example.com"))

    @Test fun `strips well known suffixes but keeps subfolder`() {
        assertEquals("https://example.com/nextcloud/", ok("https://example.com/nextcloud/index.php/login"))
        assertEquals("https://example.com/", ok("https://example.com/apps/files/?dir=/"))
        assertEquals("https://example.com/nc/", ok(" https://example.com/nc/remote.php/dav "))
    }

    @Test fun `keeps ports`() = assertEquals("https://example.com:8443/", ok("example.com:8443"))

    @Test fun `refuses plain http`() = assertTrue(ServerUrl.normalize("http://example.com") is ServerUrlResult.Insecure)

    @Test fun `rejects junk`() {
        assertTrue(ServerUrl.normalize("") is ServerUrlResult.Empty)
        assertTrue(ServerUrl.normalize("   ") is ServerUrlResult.Empty)
        assertTrue(ServerUrl.normalize("ftp://example.com") is ServerUrlResult.Invalid)
        assertTrue(ServerUrl.normalize("https://user:pw@example.com") is ServerUrlResult.Invalid)
        assertTrue(ServerUrl.normalize("not a url") is ServerUrlResult.Invalid)
    }
}
