// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.nctasks.ui.theme.Ios

val CardShape = RoundedCornerShape(12.dp)

/** Row/card background that darkens slightly while pressed, like UIKit's highlighted cell state. */
fun Modifier.iosClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val highlight = Ios.colors.fill
    this
        .background(if (pressed && enabled) highlight else Color.Transparent)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** iOS "inset grouped" container: rounded card whose children are separated by hairlines. */
@Composable
fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ios.colors.card),
        content = content,
    )
}

@Composable
fun RowDivider(startInset: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(Ios.colors.separator),
    )
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Ios.type.footnote,
        color = Ios.colors.secondaryLabel,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Ios.type.footnote,
        color = Ios.colors.secondaryLabel,
        modifier = modifier.padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun IosRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 44.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = minHeight)
    val clickable = if (onClick != null) base.iosClickable(onClick = onClick) else base
    Row(
        clickable.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Rounded-square icon badge used for list and settings rows. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
fun SquareBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** UISwitch look-alike (51x31 pt, green when on). */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val track by animateColorAsState(if (checked) Ios.colors.green else Ios.colors.fill, tween(200), label = "track")
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.75f, stiffness = 600f), label = "thumb")
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            })
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(27.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** UISegmentedControl look-alike. */
@Composable
fun <T> IosSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ios.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.fill)
            .padding(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(if (isSelected) (if (c.isDark) Color(0xFF636366) else Color.White) else Color.Transparent, tween(150), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Ios.type.footnote.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = c.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Reminders-style round checkbox: ring in the list colour, filled with a check when done. */
@Composable
fun CheckCircle(
    checked: Boolean,
    color: Color,
    onToggle: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val fill by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "fill")
    val ringColor = if (checked) color else Ios.colors.tertiaryLabel
    Box(
        modifier
            .size(44.dp)
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = if (checked) "checked" else "unchecked"
            }
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, interactionSource = null, indication = null) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.Confirm else HapticFeedbackType.ContextClick)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 1.5.dp.toPx()
            drawCircle(color = ringColor, radius = (size.minDimension - stroke) / 2f, style = Stroke(width = stroke))
            if (fill > 0f) {
                drawCircle(color = color.copy(alpha = fill), radius = size.minDimension / 2f * (0.55f + 0.45f * fill))
                val s = size.minDimension
                val check = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.29f, s * 0.52f)
                    lineTo(s * 0.43f, s * 0.66f)
                    lineTo(s * 0.72f, s * 0.35f)
                }
                drawPath(check, Color.White.copy(alpha = fill), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun IosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = Ios.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textAlign: TextAlign = TextAlign.Start,
) {
    val c = Ios.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle.copy(color = c.label, textAlign = textAlign),
        cursorBrush = SolidColor(c.blue),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = textStyle, color = c.tertiaryLabel, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                inner()
            }
        },
    )
}

/** Full-width filled button, as used for the primary action on iOS onboarding screens. */
@Composable
fun IosPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val c = Ios.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) c.blue else c.blue.copy(alpha = 0.4f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Text(text, style = Ios.type.headline, color = Color.White)
        }
    }
}

@Composable
fun IosTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ios.colors.blue, bold: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        style = if (bold) Ios.type.headline else Ios.type.body,
        color = if (enabled) color else Ios.colors.secondaryLabel,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** Value-with-stepper control (UIStepper look-alike). */
@Composable
fun IosStepper(onDecrement: () -> Unit, onIncrement: () -> Unit, canDecrement: Boolean, decrementLabel: String, incrementLabel: String, modifier: Modifier = Modifier) {
    val c = Ios.colors
    Row(
        modifier
            .size(width = 94.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.fill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperHalf("−", decrementLabel, canDecrement, onDecrement, Modifier.weight(1f))
        Box(Modifier.width(0.5.dp).height(16.dp).background(c.secondaryLabel.copy(alpha = 0.5f)))
        StepperHalf("+", incrementLabel, true, onIncrement, Modifier.weight(1f))
    }
}

@Composable
private fun StepperHalf(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = Ios.type.title3.copy(fontWeight = FontWeight.Normal), color = if (enabled) Ios.colors.label else Ios.colors.secondaryLabel.copy(alpha = 0.5f))
    }
}

enum class ChevronDirection { Right, Left, Down }

/** Thin, rounded iOS-style chevron. */
@Composable
fun Chevron(
    modifier: Modifier = Modifier,
    direction: ChevronDirection = ChevronDirection.Right,
    color: Color = Ios.colors.tertiaryLabel,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier.size(if (direction == ChevronDirection.Down) 14.dp else 12.dp, if (direction == ChevronDirection.Down) 8.dp else 20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = strokeWidth.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            when (direction) {
                ChevronDirection.Right -> { moveTo(stroke / 2, stroke / 2); lineTo(w - stroke / 2, h / 2); lineTo(stroke / 2, h - stroke / 2) }
                ChevronDirection.Left -> { moveTo(w - stroke / 2, stroke / 2); lineTo(stroke / 2, h / 2); lineTo(w - stroke / 2, h - stroke / 2) }
                ChevronDirection.Down -> { moveTo(stroke / 2, stroke / 2); lineTo(w / 2, h - stroke / 2); lineTo(w - stroke / 2, stroke / 2) }
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Material "access time" glyph (the core icon set has no clock). */
val ClockIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("Clock", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
