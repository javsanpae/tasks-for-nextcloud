// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.auth

import okhttp3.HttpUrl

/**
 * Everything needed to talk to the server. This is the only user data the app keeps, and only
 * ever in [CredentialStore]. [toString] is redacted so it cannot leak into logs by accident.
 */
class Credentials(
    val server: HttpUrl,
    val loginName: String,
    val appPassword: String,
) {
    override fun toString(): String = "Credentials(<redacted>)"
}
