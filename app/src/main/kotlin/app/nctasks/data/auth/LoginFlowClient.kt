// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LoginException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NOT_NEXTCLOUD, NETWORK, INSECURE, MALFORMED, UNAUTHORIZED }
}

/** A pending Login Flow v2 handshake. [token] is a one-time secret and is never persisted. */
class LoginSession(val server: HttpUrl, val loginUrl: HttpUrl, val pollUrl: HttpUrl, internal val token: String) {
    override fun toString(): String = "LoginSession(<redacted>)"
}

/**
 * Nextcloud Login Flow v2: the user signs in inside their own browser (so the app never sees their
 * real password, 2FA or SSO) and the server hands back a revocable, app-specific password.
 * See https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/
 */
class LoginFlowClient(
    private val http: OkHttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    suspend fun start(server: HttpUrl): LoginSession {
        val url = server.newBuilder().addPathSegments("index.php/login/v2").build()
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).header("User-Agent", userAgent).build()
        val json = call(request) { code, body ->
            if (code == 404 || code == 405 || code == 403) throw LoginException(LoginException.Kind.NOT_NEXTCLOUD, "Not a Nextcloud server")
            if (code !in 200..299) throw LoginException(LoginException.Kind.NOT_NEXTCLOUD, "HTTP $code")
            parse(body)
        }
        return try {
            val poll = json.getJSONObject("poll")
            val login = requireHttps(json.getString("login"))
            val endpoint = requireHttps(poll.getString("endpoint"))
            LoginSession(server, login, endpoint, poll.getString("token"))
        } catch (e: JSONException) {
            throw LoginException(LoginException.Kind.NOT_NEXTCLOUD, "Unexpected response", e)
        }
    }

    /** Returns null while the user has not finished signing in yet. */
    suspend fun poll(session: LoginSession): Credentials? {
        val body = FormBody.Builder().add("token", session.token).build()
        val request = Request.Builder().url(session.pollUrl).post(body).header("User-Agent", userAgent).build()
        return call(request) { code, text ->
            when {
                code == 404 -> null
                code !in 200..299 -> throw LoginException(LoginException.Kind.MALFORMED, "HTTP $code")
                else -> try {
                    val json = parse(text)
                    Credentials(requireHttps(json.getString("server")), json.getString("loginName"), json.getString("appPassword"))
                } catch (e: JSONException) {
                    throw LoginException(LoginException.Kind.MALFORMED, "Unexpected response", e)
                }
            }
        }
    }

    /** Best effort: tells the server to delete the app password this device was given. */
    suspend fun revoke(credentials: Credentials) {
        val url = credentials.server.newBuilder().addPathSegments("ocs/v2.php/core/apppassword").build()
        val request = Request.Builder().url(url).delete()
            .header("Authorization", okhttp3.Credentials.basic(credentials.loginName, credentials.appPassword, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", userAgent)
            .build()
        runCatching { call(request) { _, _ -> } }
    }

    private fun requireHttps(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
        if (url == null || !url.isHttps) throw LoginException(LoginException.Kind.INSECURE, "Server returned a non-HTTPS URL")
        return url
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw LoginException(LoginException.Kind.NOT_NEXTCLOUD, "Response is not JSON", e)
    }

    private suspend fun <T> call(request: Request, handle: (Int, String) -> T): T = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw LoginException(LoginException.Kind.NETWORK, "Network error", e)
        }
        response.use {
            val source = it.body.source()
            source.request(MAX_BODY + 1)
            if (source.buffer.size > MAX_BODY) throw LoginException(LoginException.Kind.MALFORMED, "Response too large")
            handle(it.code, source.readUtf8())
        }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Open Tasks for Nextcloud (Android)"
        const val MAX_BODY = 256L * 1024
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) response.close() else cont.resume(response)
        }
    })
}
