// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.auth

import android.content.Context
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** The only place (besides the encrypted task cache) where the app persists anything about the user. */
interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials)
    /** Remembers which list the user last opened (a URL containing their user id, so encrypted too). */
    fun loadSelectedList(): String?
    fun saveSelectedList(url: String?)
    fun clear()
}

/**
 * Credentials are encrypted with [crypto] (an Android Keystore key, hardware backed where available)
 * and only ciphertext is written to app-private storage, which is excluded from cloud backup and
 * device-to-device transfer.
 */
class KeystoreCredentialStore(context: Context, private val crypto: Crypto = KeystoreCrypto()) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Credentials? {
        val json = decrypt(KEY_CREDENTIALS) ?: return null
        return try {
            val o = JSONObject(json)
            Credentials(o.getString("server").toHttpUrlOrNull() ?: return null, o.getString("login"), o.getString("secret"))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun save(credentials: Credentials) {
        val json = JSONObject()
            .put("server", credentials.server.toString())
            .put("login", credentials.loginName)
            .put("secret", credentials.appPassword)
            .toString()
        encrypt(KEY_CREDENTIALS, json)
    }

    override fun loadSelectedList(): String? = decrypt(KEY_LIST)

    override fun saveSelectedList(url: String?) {
        if (url == null) prefs.edit().remove(KEY_LIST).commit() else encrypt(KEY_LIST, url)
    }

    override fun clear() {
        prefs.edit().clear().commit()
        crypto.destroyKey()
    }

    private fun encrypt(slot: String, plain: String) {
        val blob = crypto.encrypt(plain.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(slot, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    private fun decrypt(slot: String): String? {
        val encoded = prefs.getString(slot, null) ?: return null
        val blob = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return crypto.decrypt(blob)?.toString(Charsets.UTF_8)
    }

    private companion object {
        const val PREFS = "secure_session"
        const val KEY_CREDENTIALS = "credentials"
        const val KEY_LIST = "selected_list"
    }
}
