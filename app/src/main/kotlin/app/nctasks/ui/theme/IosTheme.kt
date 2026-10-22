// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** iOS system colors (UIColor.system*), light and dark variants. */
@Immutable
class IosColors(
    val background: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val blue: Color,
    val red: Color,
    val orange: Color,
    val green: Color,
    val isDark: Boolean,
)

private val LightColors = IosColors(
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF8A8A8E),
    tertiaryLabel = Color(0xFFC7C7CC),
    separator = Color(0xFFD1D1D6),
    fill = Color(0x2E787880),
    blue = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    green = Color(0xFF34C759),
    isDark = false,
)

private val DarkColors = IosColors(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF8E8E93),
    tertiaryLabel = Color(0xFF636366),
    separator = Color(0xFF38383A),
    fill = Color(0x5C787880),
    blue = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    green = Color(0xFF30D158),
    isDark = true,
)

/** iOS text styles (Dynamic Type "large" sizes). */
@Immutable
class IosTypography {
    private val family = FontFamily.SansSerif
    val largeTitle = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.37.sp)
    val title3 = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val body = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp)
    val callout = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.3).sp)
    val subhead = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp)
    val footnote = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp)
}

private val LocalIosColors = staticCompositionLocalOf { LightColors }
private val LocalIosTypography = staticCompositionLocalOf { IosTypography() }

object Ios {
    val colors: IosColors @Composable @ReadOnlyComposable get() = LocalIosColors.current
    val type: IosTypography @Composable @ReadOnlyComposable get() = LocalIosTypography.current
}

@Composable
fun IosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = colors.blue, background = colors.background, surface = colors.card, onSurface = colors.label, onBackground = colors.label)
    } else {
        lightColorScheme(primary = colors.blue, background = colors.background, surface = colors.card, onSurface = colors.label, onBackground = colors.label)
    }
    CompositionLocalProvider(LocalIosColors provides colors, LocalIosTypography provides IosTypography()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
