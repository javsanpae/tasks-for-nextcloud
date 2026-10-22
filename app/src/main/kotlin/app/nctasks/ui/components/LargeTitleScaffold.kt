// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nctasks.ui.theme.Ios

/**
 * iOS "large title" screen: a 44 pt navigation bar whose centred title fades in once the large
 * title has scrolled away, above a scrolling list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Ios.colors.label,
    navLeading: @Composable () -> Unit = {},
    navTrailing: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyListScope.() -> Unit,
) {
    val c = Ios.colors
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }
    Column(modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { navLeading() }
            Text(
                title,
                style = Ios.type.headline,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).alpha(if (collapsed) 1f else 0f),
            )
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { navTrailing() }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(if (collapsed) c.separator else Color.Transparent))
        val list: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
                item(key = "large-title", contentType = "title") {
                    Text(
                        title,
                        style = Ios.type.largeTitle,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp).semantics { heading() },
                    )
                }
                content()
            }
        }
        Box(Modifier.weight(1f)) {
            if (onRefresh != null) {
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() }
            } else {
                list()
            }
        }
        bottomBar()
    }
}

/** A text/icon button in the nav bar. */
@Composable
fun NavBarButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .iosClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Dark pill used for transient messages ("Task deleted · Undo"). */
@Composable
fun IosToast(message: String?, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        var last by remember { androidx.compose.runtime.mutableStateOf<String?>(message) }
        if (message != null) last = message
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (Ios.colors.isDark) Color(0xFF2C2C2E) else Color(0xFF3A3A3C))
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(last.orEmpty(), style = Ios.type.subhead, color = Color.White, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp))
            if (actionLabel != null) IosTextButton(actionLabel, onAction, color = Color(0xFF64B5FF), bold = true)
        }
    }
}
