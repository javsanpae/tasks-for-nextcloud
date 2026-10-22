// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import android.content.res.Resources
import app.nctasks.R
import app.nctasks.data.repo.SyncStatus

/** Short status line for the toolbar ("Offline · 2 changes waiting"), or null when everything is in sync. */
object SyncText {
    fun label(res: Resources, status: SyncStatus, pending: Int): String? {
        val waiting = if (pending > 0) res.getQuantityString(R.plurals.sync_waiting, pending, pending) else null
        val head = when (status) {
            SyncStatus.OFFLINE -> res.getString(R.string.sync_offline)
            SyncStatus.ERROR -> res.getString(R.string.sync_error)
            SyncStatus.SYNCING -> if (pending > 0) res.getString(R.string.sync_syncing) else null
            SyncStatus.IDLE -> null
        }
        return when {
            head != null && waiting != null -> res.getString(R.string.sync_head_waiting, head, waiting)
            head != null -> head
            else -> waiting
        }
    }
}
