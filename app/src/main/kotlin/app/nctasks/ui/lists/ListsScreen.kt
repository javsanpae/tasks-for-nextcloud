// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.domain.TaskList
import app.nctasks.ui.components.Chevron
import app.nctasks.ui.components.GroupedCard
import app.nctasks.ui.components.IconBadge
import app.nctasks.ui.components.IosRow
import app.nctasks.ui.components.LargeTitleScaffold
import app.nctasks.ui.components.NavBarButton
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.components.SectionFooter
import app.nctasks.ui.theme.Ios
import androidx.compose.foundation.layout.ColumnScope

fun listColor(list: TaskList): Color = list.color?.let { Color(it) } ?: Color(0xFF0082C9)

@Composable
fun ListsScreen(
    lists: List<TaskList>,
    openCount: (TaskList) -> Int,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TaskList) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    syncLabel: String? = null,
) {
    val c = Ios.colors
    var menuOpen by remember { mutableStateOf(false) }
    LargeTitleScaffold(
        title = stringResource(R.string.lists_title),
        modifier = modifier,
        navTrailing = {
            Box {
                NavBarButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more), tint = c.blue)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.card) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_refresh), style = Ios.type.body, color = c.label) },
                        onClick = { menuOpen = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_sign_out), style = Ios.type.body, color = c.red) },
                        onClick = { menuOpen = false; onSignOut() },
                    )
                }
            }
        },
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        when {
            isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.secondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            lists.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.lists_empty_title), style = Ios.type.title3, color = c.secondaryLabel)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.lists_empty_body), style = Ios.type.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
                }
            }
            else -> item {
              Column {
                GroupedCard(Modifier.padding(horizontal = 16.dp)) {
                    lists.forEachIndexed { index, list ->
                        ListRow(list, openCount(list), onClick = { onOpen(list) })
                        if (index < lists.lastIndex) RowDivider(startInset = 58.dp)
                    }
                }
                if (syncLabel != null) SectionFooter(syncLabel)
              }
            }
        }
    }
}

@Composable
private fun ColumnScope.ListRow(list: TaskList, count: Int, onClick: () -> Unit) {
    val c = Ios.colors
    IosRow(onClick = onClick, minHeight = 52.dp) {
        IconBadge(Icons.AutoMirrored.Filled.List, listColor(list), size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(list.name, style = Ios.type.body, color = c.label, maxLines = 1)
            if (!list.canWrite) Text(stringResource(R.string.lists_read_only), style = Ios.type.caption, color = c.secondaryLabel)
        }
        Text(count.toString(), style = Ios.type.body, color = c.secondaryLabel)
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}
