// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nctasks.R
import app.nctasks.data.AppContainer
import app.nctasks.data.Session
import app.nctasks.ui.components.IosTextButton
import app.nctasks.ui.lists.ListsScreen
import app.nctasks.ui.login.LoginScreen
import app.nctasks.ui.login.LoginViewModel
import app.nctasks.ui.tasks.SyncText
import app.nctasks.ui.tasks.TasksScreen
import app.nctasks.ui.tasks.TasksViewModel
import app.nctasks.ui.tasks.ToastMessage
import app.nctasks.ui.tasks.UiError
import app.nctasks.ui.theme.Ios
import kotlinx.coroutines.delay

@Composable
fun NcTasksRoot(container: AppContainer) {
    val session by container.session.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = session,
        contentKey = { it != null },
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
        label = "auth",
    ) { current ->
        if (current == null) LoginRoute(container) else SignedInRoute(container, current)
    }
}

@Composable
private fun LoginRoute(container: AppContainer) {
    val vm: LoginViewModel = viewModel(factory = factory { LoginViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.openBrowser) {
        val url = state.openBrowser ?: return@LaunchedEffect
        // A Custom Tab is a real browser: the app never sees the user's password, 2FA or SSO.
        runCatching { CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(context as Activity, Uri.parse(url)) }
        vm.onBrowserOpened()
    }
    LoginScreen(
        state = state,
        onServerChange = vm::onServerChange,
        onContinue = vm::start,
        onCancel = vm::cancel,
        onManualMode = vm::setManualMode,
        onUsernameChange = vm::onUsernameChange,
        onAppPasswordChange = vm::onAppPasswordChange,
        onSubmitManual = vm::submitManual,
    )
}

@Composable
private fun SignedInRoute(container: AppContainer, session: Session) {
    val vm: TasksViewModel = viewModel(key = System.identityHashCode(session).toString(), factory = factory { TasksViewModel(session.repository, container) })
    DisposableEffect(vm) { onDispose { vm.wipe() } }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    val errorText = state.error?.let { stringResource(errorRes(it)) }
    val deletedText = stringResource(R.string.task_deleted)
    val undo = stringResource(R.string.task_undo)
    val toast = when {
        errorText != null -> ToastMessage(errorText)
        state.undoDelete != null -> ToastMessage(deletedText, undo)
        else -> null
    }
    LaunchedEffect(state.error, state.undoDelete) {
        if (state.error != null || state.undoDelete != null) {
            delay(5_000)
            vm.dismissError(); vm.dismissUndo()
        }
    }

    val syncLabel = SyncText.label(LocalContext.current.resources, state.sync, state.pendingChanges)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }

    val selected = state.selectedList
    AnimatedContent(
        targetState = selected?.url,
        transitionSpec = {
            if (targetState != null) slideInHorizontally(tween(300)) { it } + fadeIn() togetherWith slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut()
            else slideInHorizontally(tween(300)) { -it / 3 } + fadeIn() togetherWith slideOutHorizontally(tween(300)) { it } + fadeOut()
        },
        label = "lists-tasks",
    ) { url ->
        val list = state.lists.firstOrNull { it.url == url }
        if (list == null) {
            ListsScreen(
                lists = state.lists,
                openCount = { l -> state.tasks(l).count { !it.isCompleted } },
                isLoading = state.isLoading,
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh() },
                onOpen = vm::selectList,
                onSignOut = { confirmSignOut = true },
                syncLabel = syncLabel,
            )
        } else {
            TasksScreen(
                list = list,
                lists = state.lists,
                tasks = state.tasks(list),
                showCompleted = state.showCompleted,
                isRefreshing = state.isRefreshing,
                toast = toast,
                syncLabel = syncLabel,
                onBack = { vm.selectList(null) },
                onRefresh = { vm.refresh() },
                onToggleShowCompleted = { vm.setShowCompleted(!state.showCompleted) },
                onToggle = vm::toggle,
                onAdd = vm::add,
                onEdit = vm::edit,
                onDelete = vm::delete,
                onToastAction = { if (state.undoDelete != null) vm.undoDelete() else vm.dismissError() },
            )
        }
    }
    if (confirmSignOut) {
        val c = Ios.colors
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = c.card,
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.sign_out_title), style = Ios.type.headline, color = c.label) },
            text = { Text(stringResource(R.string.sign_out_body), style = Ios.type.subhead, color = c.secondaryLabel) },
            confirmButton = { IosTextButton(stringResource(R.string.menu_sign_out), { confirmSignOut = false; vm.signOut() }, color = c.red, bold = true) },
            dismissButton = { IosTextButton(stringResource(R.string.editor_cancel), { confirmSignOut = false }) },
        )
    }
}

private fun errorRes(e: UiError): Int = when (e) {
    UiError.OFFLINE -> R.string.error_offline
    UiError.REJECTED -> R.string.error_rejected
    UiError.SERVER -> R.string.error_server
    UiError.READ_ONLY -> R.string.error_read_only
    UiError.GENERIC -> R.string.error_generic
}

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}
