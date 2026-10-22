// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nctasks.data.AppContainer
import app.nctasks.data.auth.Credentials
import app.nctasks.data.auth.LoginException
import app.nctasks.data.auth.LoginSession
import app.nctasks.data.auth.ServerUrl
import app.nctasks.data.auth.ServerUrlResult
import app.nctasks.data.caldav.DavException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

enum class LoginError { EMPTY_URL, INVALID_URL, INSECURE_URL, NOT_NEXTCLOUD, NETWORK, REJECTED, NO_TASKS_ACCESS, TIMEOUT, GENERIC }

enum class LoginPhase { IDLE, STARTING, WAITING_FOR_BROWSER, VERIFYING }

data class LoginUiState(
    val server: String = "",
    val phase: LoginPhase = LoginPhase.IDLE,
    val error: LoginError? = null,
    val manualMode: Boolean = false,
    val username: String = "",
    val appPassword: String = "",
    /** One-shot: set when the UI should open this URL in a Custom Tab, then cleared. */
    val openBrowser: String? = null,
) {
    val isBusy: Boolean get() = phase != LoginPhase.IDLE
}

class LoginViewModel(
    private val container: AppContainer,
    private val pollIntervalMs: Long = 2_000,
    private val pollTimeoutMs: Long = 20 * 60_000,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onServerChange(value: String) = _state.update { it.copy(server = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onAppPasswordChange(value: String) = _state.update { it.copy(appPassword = value, error = null) }
    fun setManualMode(manual: Boolean) = _state.update { it.copy(manualMode = manual, error = null) }
    fun onBrowserOpened() = _state.update { it.copy(openBrowser = null) }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = LoginPhase.IDLE, openBrowser = null) }
    }

    fun start() {
        if (_state.value.isBusy) return
        val url = parseServer() ?: return
        job = viewModelScope.launch {
            try {
                _state.update { it.copy(phase = LoginPhase.STARTING, error = null) }
                val session = container.loginFlow.start(url)
                _state.update { it.copy(phase = LoginPhase.WAITING_FOR_BROWSER, openBrowser = session.loginUrl.toString()) }
                val credentials = pollUntilApproved(session)
                    ?: return@launch fail(LoginError.TIMEOUT)
                finish(credentials)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(map(e))
            }
        }
    }

    /** Fallback for servers where Login Flow v2 is unavailable: user pastes an app password they created. */
    fun submitManual() {
        if (_state.value.isBusy) return
        val url = parseServer() ?: return
        val s = _state.value
        if (s.username.isBlank() || s.appPassword.isEmpty()) return _state.update { it.copy(error = LoginError.REJECTED) }
        job = viewModelScope.launch {
            try {
                finish(Credentials(url, s.username.trim(), s.appPassword))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(map(e))
            }
        }
    }

    private suspend fun pollUntilApproved(session: LoginSession): Credentials? {
        var waited = 0L
        var failures = 0
        while (waited < pollTimeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            try {
                container.loginFlow.poll(session)?.let { return it }
                failures = 0
            } catch (e: LoginException) {
                // Transient network trouble (e.g. the phone switched networks) should not abort the flow.
                if (e.kind != LoginException.Kind.NETWORK || ++failures > MAX_POLL_FAILURES) throw e
            }
        }
        return null
    }

    private suspend fun finish(credentials: Credentials) {
        _state.update { it.copy(phase = LoginPhase.VERIFYING, openBrowser = null) }
        container.verify(credentials)
        container.signIn(credentials)
        // The password lives on only in the Keystore-encrypted store from here on.
        _state.value = LoginUiState(server = _state.value.server)
    }

    private fun parseServer(): HttpUrl? = when (val r = ServerUrl.normalize(_state.value.server)) {
        is ServerUrlResult.Valid -> r.url
        ServerUrlResult.Empty -> null.also { _state.update { it.copy(error = LoginError.EMPTY_URL) } }
        ServerUrlResult.Invalid -> null.also { _state.update { it.copy(error = LoginError.INVALID_URL) } }
        ServerUrlResult.Insecure -> null.also { _state.update { it.copy(error = LoginError.INSECURE_URL) } }
    }

    private fun fail(error: LoginError) = _state.update { it.copy(phase = LoginPhase.IDLE, error = error, openBrowser = null, appPassword = "") }

    private fun map(e: Exception): LoginError = when (e) {
        is LoginException -> when (e.kind) {
            LoginException.Kind.NOT_NEXTCLOUD -> LoginError.NOT_NEXTCLOUD
            LoginException.Kind.NETWORK -> LoginError.NETWORK
            LoginException.Kind.INSECURE -> LoginError.INSECURE_URL
            LoginException.Kind.UNAUTHORIZED -> LoginError.REJECTED
            LoginException.Kind.MALFORMED -> LoginError.GENERIC
        }
        is DavException -> when (e.kind) {
            DavException.Kind.UNAUTHORIZED -> LoginError.REJECTED
            DavException.Kind.NETWORK -> LoginError.NETWORK
            DavException.Kind.NOT_FOUND, DavException.Kind.FORBIDDEN -> LoginError.NO_TASKS_ACCESS
            else -> LoginError.GENERIC
        }
        else -> LoginError.GENERIC
    }

    override fun onCleared() {
        job?.cancel()
    }

    private companion object {
        const val MAX_POLL_FAILURES = 5
    }
}
