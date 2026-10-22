// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.caldav

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    private fun base() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)

    /**
     * For authenticated calls. Redirects are never followed: the Authorization header must not be
     * replayed to a location the server chose, and Nextcloud's DAV endpoints do not redirect.
     */
    fun authenticated(): OkHttpClient = base().followRedirects(false).followSslRedirects(false).build()

    /** For the unauthenticated login handshake, where redirects (e.g. www.) are harmless. */
    fun anonymous(): OkHttpClient = base().build()
}
