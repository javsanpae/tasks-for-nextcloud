// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data.repo

import app.nctasks.domain.Task
import app.nctasks.domain.TaskList

/** The server side of sync. Failures are reported as [app.nctasks.data.caldav.DavException]. */
interface RemoteTasks {
    suspend fun taskLists(): List<TaskList>
    suspend fun tasks(list: TaskList): List<Task>
    /** Creates the resource at [url]; CONFLICT if it already exists. */
    suspend fun create(listUrl: String, url: String, ics: String): Task
    /** Overwrites only if [etag] still matches (when non-null); CONFLICT otherwise. */
    suspend fun update(listUrl: String, url: String, etag: String?, ics: String): Task
    /** Deletes only if [etag] still matches (when non-null); CONFLICT otherwise, NOT_FOUND if already gone. */
    suspend fun delete(url: String, etag: String?)
    suspend fun task(listUrl: String, url: String): Task?
}
