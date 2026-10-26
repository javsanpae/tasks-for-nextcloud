// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.tasks

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.nctasks.R
import app.nctasks.domain.Reminder
import app.nctasks.ui.components.GroupedCard
import app.nctasks.ui.components.IosRow
import app.nctasks.ui.components.IosSegmented
import app.nctasks.ui.components.IosStepper
import app.nctasks.ui.components.IosTextButton
import app.nctasks.ui.components.IosTimePicker
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.components.SectionFooter
import app.nctasks.ui.components.SectionHeader
import app.nctasks.ui.theme.Ios
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

private enum class Unit(val minutes: Long, val label: Int) {
    MINUTES(1, R.string.alerts_custom_unit_minutes),
    HOURS(60, R.string.alerts_custom_unit_hours),
    DAYS(24 * 60, R.string.alerts_custom_unit_days),
    WEEKS(7 * 24 * 60, R.string.alerts_custom_unit_weeks),
}

/**
 * The "Alerts" page: tick the preset times you want, see alerts set elsewhere, or build a custom one.
 * [allDay] tasks (a date without a time) alert at a time of day on the date or some days before it.
 */
@Composable
fun AlertsEditor(
    reminders: List<Reminder>,
    allDay: Boolean,
    onChange: (List<Reminder>) -> kotlin.Unit,
    onAdded: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
    notificationsEnabled: Boolean = rememberNotificationsEnabled(),
) {
    val context = LocalContext.current
    val res = context.resources
    val presets = remember(allDay) { ReminderText.presets(allDay) }
    val others = reminders.filter { it !in presets }
    val full = reminders.size >= Reminder.MAX_PER_TASK

    fun add(r: Reminder) {
        if (r in reminders || full) return
        onChange(reminders + r)
        onAdded()
    }

    Column(modifier.fillMaxWidth()) {
        if (!notificationsEnabled) {
            GroupedCard(Modifier.padding(horizontal = 16.dp)) {
                IosRow(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) {
                    Text(stringResource(R.string.alerts_notifications_off), style = Ios.type.subhead, color = Ios.colors.red)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        GroupedCard(Modifier.padding(horizontal = 16.dp)) {
            presets.forEachIndexed { i, preset ->
                val on = preset in reminders
                CheckRow(
                    ReminderText.describe(res, preset, allDay, locale), selected = on,
                    onClick = { if (on) onChange(reminders - preset) else add(preset) },
                )
                if (i < presets.lastIndex) RowDivider()
            }
        }
        if (others.isNotEmpty()) {
            SectionHeader(stringResource(R.string.alerts_other))
            GroupedCard(Modifier.padding(horizontal = 16.dp)) {
                others.forEachIndexed { i, r ->
                    CheckRow(ReminderText.describe(res, r, allDay, locale), selected = true, onClick = { onChange(reminders - r) })
                    if (i < others.lastIndex) RowDivider()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        CustomAlert(allDay, enabled = !full, onAdd = ::add)
        if (full) SectionFooter(stringResource(R.string.alerts_limit, Reminder.MAX_PER_TASK))
    }
}

@Composable
private fun CustomAlert(allDay: Boolean, enabled: Boolean, onAdd: (Reminder) -> kotlin.Unit) {
    val c = Ios.colors
    var open by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableIntStateOf(if (allDay) 3 else 45) }
    var unit by rememberSaveable { mutableStateOf(Unit.MINUTES) }
    var timeSeconds by rememberSaveable { mutableIntStateOf(Reminder.ALL_DAY_TIME.toSecondOfDay()) }

    GroupedCard(Modifier.padding(horizontal = 16.dp)) {
        IosRow(onClick = if (enabled) ({ open = !open }) else null) {
            Text(stringResource(R.string.alerts_custom), style = Ios.type.body, color = if (enabled) c.blue else c.secondaryLabel)
        }
        if (open && enabled) {
            RowDivider()
            val resources = LocalContext.current.resources
            val preview = if (allDay) Reminder.allDay(amount, LocalTime.ofSecondOfDay(timeSeconds.toLong()))
            else Reminder.before(Duration.ofMinutes(amount * unit.minutes))
            if (allDay) {
                IosRow {
                    Text(stringResource(R.string.alerts_custom_days_before), style = Ios.type.body, color = c.label, modifier = Modifier.weight(1f))
                    Text("$amount", style = Ios.type.body, color = c.secondaryLabel)
                    Spacer(Modifier.width(12.dp))
                    Stepper({ amount = (amount - 1).coerceAtLeast(0) }, { amount = (amount + 1).coerceAtMost(365) }, amount > 0)
                }
                RowDivider()
                IosRow {
                    Text(stringResource(R.string.alerts_custom_time), style = Ios.type.body, color = c.label, modifier = Modifier.weight(1f))
                    Text(ReminderText.describe(resources, preview, allDay = true), style = Ios.type.body, color = c.blue)
                }
                IosTimePicker(LocalTime.ofSecondOfDay(timeSeconds.toLong()), { timeSeconds = it.toSecondOfDay() })
            } else {
                IosRow {
                    Text(ReminderText.describe(resources, preview, allDay = false), style = Ios.type.body, color = c.label, modifier = Modifier.weight(1f))
                    Stepper({ amount = (amount - 1).coerceAtLeast(1) }, { amount = (amount + 1).coerceAtMost(999) }, amount > 1)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    IosSegmented(Unit.entries.map { it to stringResource(it.label) }, unit, { unit = it })
                }
                Spacer(Modifier.height(8.dp))
            }
            RowDivider()
            IosRow(onClick = {
                onAdd(preview)
                open = false
            }) {
                Text(stringResource(R.string.alerts_custom_add), style = Ios.type.headline, color = c.blue)
            }
        }
    }
}

@Composable
private fun Stepper(onDecrement: () -> kotlin.Unit, onIncrement: () -> kotlin.Unit, canDecrement: Boolean) {
    IosStepper(onDecrement, onIncrement, canDecrement, stringResource(R.string.alerts_decrease), stringResource(R.string.alerts_increase))
}

/** Whether the system will show our notifications; re-checked when the user returns from Settings. */
@Composable
fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    return enabled
}

