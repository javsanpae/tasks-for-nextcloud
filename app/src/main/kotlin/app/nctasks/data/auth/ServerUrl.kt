// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface ServerUrlResult {
    data class Valid(val url: HttpUrl) : ServerUrlResult
    data object Empty : ServerUrlResult
    data object Invalid : ServerUrlResult
    /** Plain http:// is refused: credentials and tasks must never travel unencrypted. */
    data object Insecure : ServerUrlResult
}

object ServerUrl {
    private val STRIP_FROM = listOf("/index.php", "/remote.php", "/apps/", "/login", "/ocs/", "/status.php")

    /** Turns whatever a person types ("cloud.example.com/", "https://x.org/nextcloud/index.php/login") into a base URL. */
    fun normalize(input: String): ServerUrlResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ServerUrlResult.Empty
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> return ServerUrlResult.Insecure
            "://" in trimmed -> return ServerUrlResult.Invalid
            else -> "https://$trimmed"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return ServerUrlResult.Invalid
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return ServerUrlResult.Invalid
        var path = parsed.encodedPath
        for (marker in STRIP_FROM) {
            val i = path.indexOf(marker)
            if (i >= 0) path = path.substring(0, i)
        }
        path = path.trimEnd('/')
        val base = parsed.newBuilder().encodedPath(if (path.isEmpty()) "/" else "$path/").query(null).fragment(null).build()
        return ServerUrlResult.Valid(base)
    }
}
