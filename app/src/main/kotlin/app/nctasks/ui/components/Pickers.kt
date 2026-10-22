// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.ui.theme.Ios
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Inline month calendar in the style of iOS's compact date picker. */
@Composable
fun IosCalendar(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    locale: Locale = Locale.getDefault(),
) {
    val c = Ios.colors
    var month by rememberSaveable(selected.year, selected.monthValue) { mutableStateOf(YearMonth.from(selected)) }
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val monthTitle = remember(month, locale) { month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) } }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(monthTitle, style = Ios.type.headline, color = c.label, modifier = Modifier.weight(1f).padding(start = 4.dp))
            NavChevron(ChevronDirection.Left, stringResource(R.string.calendar_previous_month)) { month = month.minusMonths(1) }
            NavChevron(ChevronDirection.Right, stringResource(R.string.calendar_next_month)) { month = month.plusMonths(1) }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val day = DayOfWeek.of(((firstDay.value - 1 + i) % 7) + 1)
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = Ios.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val lead = ((month.atDay(1).dayOfWeek.value - firstDay.value) + 7) % 7
        val weeks = (lead + month.lengthOfMonth() + 6) / 7
        for (row in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1.15f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            val isSelected = date == selected
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) c.blue else Color.Transparent)
                                    .clickable(role = Role.Button) { onSelect(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    dayNumber.toString(),
                                    style = Ios.type.body.copy(fontWeight = if (isSelected || date == today) FontWeight.SemiBold else FontWeight.Normal),
                                    color = when {
                                        isSelected -> Color.White
                                        date == today -> c.blue
                                        else -> c.label
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavChevron(direction: ChevronDirection, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(direction = direction, color = Ios.colors.blue, strokeWidth = 2.5.dp)
    }
}

private val RowHeight = 36.dp
private const val VisibleRows = 5

/** One scroll wheel (UIPickerView component) that snaps to rows and reports the centred row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val fling = rememberSnapFlingBehavior(state)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selectedIndex) {
        if (!state.isScrollInProgress && state.firstVisibleItemIndex != selectedIndex) state.scrollToItem(selectedIndex)
    }
    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrollInProgress) -1 else state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { if (it >= 0) currentOnSelect(it) }
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = modifier
            .height(RowHeight * VisibleRows)
            .semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = RowHeight * (VisibleRows / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(items.size) { i ->
            Box(Modifier.height(RowHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(items[i], style = Ios.type.title3.copy(fontWeight = FontWeight.Normal), color = Ios.colors.label)
            }
        }
    }
}

/** Hour/minute wheels (plus AM/PM when [is24Hour] is false) with the iOS selection band. */
@Composable
fun IosTimePicker(
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    is24Hour: Boolean = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current),
) {
    val c = Ios.colors
    val hours = remember(is24Hour) { if (is24Hour) (0..23).map { "%02d".format(it) } else (1..12).map { it.toString() } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    val meridiem = listOf(stringResource(R.string.time_am), stringResource(R.string.time_pm))
    val hourIndex = if (is24Hour) time.hour else (time.hour + 11) % 12
    val isPm = time.hour >= 12
    Box(modifier.fillMaxWidth().height(RowHeight * VisibleRows), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(RowHeight)
                .clip(RoundedCornerShape(9.dp))
                .background(c.fill),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
            Wheel(hours, hourIndex, { i ->
                val h = if (is24Hour) i else (i + 1) % 12 + if (isPm) 12 else 0
                onChange(time.withHour(h))
            }, stringResource(R.string.time_hour), Modifier.width(72.dp))
            Wheel(minutes, time.minute, { onChange(time.withMinute(it)) }, stringResource(R.string.time_minute), Modifier.width(72.dp))
            if (!is24Hour) {
                Wheel(meridiem, if (isPm) 1 else 0, { i ->
                    val base = time.hour % 12
                    onChange(time.withHour(base + if (i == 1) 12 else 0))
                }, stringResource(R.string.time_meridiem), Modifier.width(72.dp))
            }
        }
    }
}
