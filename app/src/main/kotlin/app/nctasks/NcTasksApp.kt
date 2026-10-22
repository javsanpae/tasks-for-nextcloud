// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks

import android.app.Application
import app.nctasks.data.AppContainer

class NcTasksApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
