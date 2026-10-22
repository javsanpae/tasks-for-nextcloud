// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nctasks.R
import app.nctasks.ui.components.GroupedCard
import app.nctasks.ui.components.IosPrimaryButton
import app.nctasks.ui.components.IosTextButton
import app.nctasks.ui.components.IosTextField
import app.nctasks.ui.components.RowDivider
import app.nctasks.ui.theme.Ios

@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onManualMode: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAppPasswordChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ios.colors
    Box(modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            AppMark()
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.login_title), style = Ios.type.largeTitle, color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.login_subtitle), style = Ios.type.callout, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(32.dp))
            when (state.phase) {
                LoginPhase.IDLE -> Form(state, onServerChange, onContinue, onManualMode, onUsernameChange, onAppPasswordChange, onSubmitManual)
                LoginPhase.STARTING -> Waiting(stringResource(R.string.login_waiting_title), null, onCancel, showCancel = false)
                LoginPhase.WAITING_FOR_BROWSER -> Waiting(stringResource(R.string.login_waiting_title), stringResource(R.string.login_waiting_body), onCancel, showCancel = true)
                LoginPhase.VERIFYING -> Waiting(stringResource(R.string.login_verifying), null, onCancel, showCancel = false)
            }
            Spacer(Modifier.weight(1f, fill = true).height(24.dp))
            Text(
                stringResource(R.string.login_privacy),
                style = Ios.type.footnote,
                color = c.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun Form(
    state: LoginUiState,
    onServerChange: (String) -> Unit,
    onContinue: () -> Unit,
    onManualMode: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAppPasswordChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
) {
    val c = Ios.colors
    val submit = if (state.manualMode) onSubmitManual else onContinue
    GroupedCard {
        FieldRow(stringResource(R.string.login_server_label)) {
            IosTextField(
                state.server, onServerChange, stringResource(R.string.login_server_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (state.manualMode) ImeAction.Next else ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onContinue() }),
            )
        }
        if (state.manualMode) {
            RowDivider()
            FieldRow(stringResource(R.string.login_username)) {
                IosTextField(
                    state.username, onUsernameChange, "",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                )
            }
            RowDivider()
            FieldRow(stringResource(R.string.login_app_password)) {
                IosTextField(
                    state.appPassword, onAppPasswordChange, "",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onSubmitManual() }),
                )
            }
        }
    }
    if (state.manualMode) {
        Text(stringResource(R.string.login_manual_hint), style = Ios.type.footnote, color = c.secondaryLabel, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    }
    state.error?.let {
        Text(
            stringResource(errorText(it)),
            style = Ios.type.subhead,
            color = c.red,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    Spacer(Modifier.height(20.dp))
    IosPrimaryButton(
        stringResource(if (state.manualMode) R.string.login_manual_continue else R.string.login_continue),
        onClick = submit,
        enabled = state.server.isNotBlank(),
    )
    Spacer(Modifier.height(8.dp))
    IosTextButton(
        stringResource(if (state.manualMode) R.string.login_browser_toggle else R.string.login_manual_toggle),
        onClick = { onManualMode(!state.manualMode) },
    )
}

@Composable
private fun FieldRow(label: String, field: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = Ios.type.caption, color = Ios.colors.secondaryLabel)
        Spacer(Modifier.height(2.dp))
        field()
    }
}

@Composable
private fun Waiting(title: String, body: String?, onCancel: () -> Unit, showCancel: Boolean) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = c.blue, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Text(title, style = Ios.type.headline, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (body != null) Text(body, style = Ios.type.callout, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        if (showCancel) IosTextButton(stringResource(R.string.login_cancel), onCancel)
    }
}

/** The app mark: a blue rounded square with a ringed checkmark. */
@Composable
private fun AppMark() {
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0082C9)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp)) {
            val stroke = 4.5.dp.toPx()
            drawCircle(Color.White, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.71f, size.height * 0.36f)
            }
            drawPath(path, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun errorText(e: LoginError): Int = when (e) {
    LoginError.EMPTY_URL -> R.string.login_error_empty_url
    LoginError.INVALID_URL -> R.string.login_error_invalid_url
    LoginError.INSECURE_URL -> R.string.login_error_insecure_url
    LoginError.NOT_NEXTCLOUD -> R.string.login_error_not_nextcloud
    LoginError.NETWORK -> R.string.login_error_network
    LoginError.REJECTED -> R.string.login_error_rejected
    LoginError.NO_TASKS_ACCESS -> R.string.login_error_no_tasks
    LoginError.TIMEOUT -> R.string.login_error_timeout
    LoginError.GENERIC -> R.string.login_error_generic
}
