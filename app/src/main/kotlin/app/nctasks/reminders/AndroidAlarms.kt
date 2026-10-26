// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

/** Arms one alarm that wakes [ReminderReceiver]. Exact when allowed, otherwise as close as Android lets us. */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun scheduleAt(at: Instant) {
        val alarm = manager ?: return
        val pi = pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        } else {
            // Exact alarms were revoked in system settings: still fire, possibly a few minutes late.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    override fun cancel() {
        val alarm = manager ?: return
        pendingIntent(PendingIntent.FLAG_NO_CREATE)?.let { alarm.cancel(it); it.cancel() }
    }
}

/** The only reminder bookkeeping on disk: when we last looked. Contains no task data. */
class PrefsReminderState(context: Context) : ReminderState {
    private val prefs = context.applicationContext.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)

    override var lastProcessed: Instant?
        get() = prefs.getLong(KEY, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY) else putLong(KEY, value.toEpochMilli()) }.apply() }

    override fun clear() { prefs.edit().clear().apply() }

    private companion object { const val KEY = "last_processed_ms" }
}
