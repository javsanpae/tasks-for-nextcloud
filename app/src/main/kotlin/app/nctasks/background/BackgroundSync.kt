// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nctasks.NcTasksApp
import app.nctasks.data.repo.SyncResult
import app.nctasks.reminders.allTasks
import java.util.concurrent.TimeUnit

/** Starts/stops periodic background sync with the session. */
interface BackgroundSync {
    fun start()
    fun stop()

    /** Push queued changes soon (needs a connection). */
    fun flush()
}

object NoBackgroundSync : BackgroundSync {
    override fun start() = Unit
    override fun stop() = Unit
    override fun flush() = Unit
}

class WorkManagerSync(private val context: Context) : BackgroundSync {
    override fun start() = SyncWork.ensurePeriodic(context)
    override fun stop() = SyncWork.cancelAll(context)
    override fun flush() = SyncWork.enqueueFlush(context)
}

object SyncWork {
    private const val PERIODIC = "periodic-sync"
    private const val FLUSH = "flush-changes"
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Hourly while online: pushes queued changes, pulls tasks created elsewhere, re-arms reminders. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One sync as soon as there is a connection (e.g. after completing a task from a notification). */
    fun enqueueFlush(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(FLUSH)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NcTasksApp).container
        val repo = container.session.value?.repository ?: return Result.success()
        repo.load()
        val result = repo.sync()
        container.reminders.reschedule(repo.snapshot.value.allTasks())
        return if (result == SyncResult.SUCCESS) Result.success() else Result.retry()
    }
}
