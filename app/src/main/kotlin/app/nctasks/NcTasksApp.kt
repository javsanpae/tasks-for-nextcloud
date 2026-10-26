// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks

import android.app.Application
import app.nctasks.data.AppContainer
import app.nctasks.reminders.ReminderNotifications

open class NcTasksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.ensureChannel(this)
    }

    open val container: AppContainer by lazy { AppContainer(this) }
}
