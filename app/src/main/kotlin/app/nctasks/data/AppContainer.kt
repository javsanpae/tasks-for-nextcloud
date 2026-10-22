// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import android.content.Context
import app.nctasks.data.auth.CredentialStore
import app.nctasks.data.auth.Credentials
import app.nctasks.data.auth.KeystoreCredentialStore
import app.nctasks.data.auth.KeystoreCrypto
import app.nctasks.data.auth.LoginFlowClient
import app.nctasks.data.caldav.CalDavClient
import app.nctasks.data.caldav.HttpClients
import app.nctasks.data.ical.TodoCodec
import app.nctasks.data.repo.EncryptedFileLocalStore
import app.nctasks.data.repo.LocalStore
import app.nctasks.data.repo.OfflineFirstRepository
import app.nctasks.data.repo.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId

/** An authenticated session. Exists only while credentials exist. */
class Session(val credentials: Credentials, val repository: TaskRepository, private val cache: LocalStore) {
    /** Stops syncing and deletes the on-disk task cache. */
    fun end() {
        repository.close()
        cache.clear()
    }
}

/** What a signed-in screen may ask of the app shell. */
interface SessionActions {
    val savedList: String?
    fun saveList(url: String?)
    suspend fun signOut(revoke: Boolean)
}

/** Manual dependency injection: small enough that a framework would only add attack surface. */
class AppContainer(
    private val store: CredentialStore,
    private val cache: LocalStore,
    private val networkTriggers: Flow<Unit> = emptyFlow(),
    private val authenticatedHttp: OkHttpClient = HttpClients.authenticated(),
    val loginFlow: LoginFlowClient = LoginFlowClient(HttpClients.anonymous()),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : SessionActions {
    constructor(context: Context) : this(
        store = KeystoreCredentialStore(context),
        // noBackupFilesDir is excluded from cloud backup and device transfer.
        cache = EncryptedFileLocalStore(File(context.noBackupFilesDir, "tasks-cache.bin"), KeystoreCrypto()),
        networkTriggers = networkAvailableFlow(context),
    )

    private val _session = MutableStateFlow(store.load()?.let(::createSession))
    val session: StateFlow<Session?> = _session.asStateFlow()

    override val savedList: String? get() = store.loadSelectedList()

    override fun saveList(url: String?) = store.saveSelectedList(url)

    /** Throws if the credentials do not give access to CalDAV task lists. */
    suspend fun verify(credentials: Credentials) {
        CalDavClient(authenticatedHttp, credentials, TodoCodec(zone())).taskLists()
    }

    fun signIn(credentials: Credentials) {
        cache.clear() // never show a previous account's tasks
        store.save(credentials)
        _session.value = createSession(credentials)
    }

    /** Forgets everything locally; [revoke] additionally asks the server to delete the app password. */
    override suspend fun signOut(revoke: Boolean) {
        val current = _session.value
        _session.value = null
        current?.end()
        cache.clear()
        store.clear()
        if (revoke && current != null) loginFlow.revoke(current.credentials)
    }

    private fun createSession(credentials: Credentials): Session {
        val codec = TodoCodec(zone())
        val client = CalDavClient(authenticatedHttp, credentials, codec)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Session(credentials, OfflineFirstRepository(client, codec, cache, scope, networkTriggers), cache)
    }
}
