package com.codex.pocketledger

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class LedgerThemeOption(
    val id: String,
    val label: String,
    val primary: Color,
    val primaryContainer: Color,
    val background: Color,
    val card: Color,
)

val ledgerThemes = listOf(
    LedgerThemeOption("lavender", "淡紫色", Color(0xFF6B50A1), Color(0xFFE8DDF7), Color(0xFFF9F5FF), Color(0xFFF1EBF8)),
    LedgerThemeOption("yellow", "淡黄色", Color(0xFF83631B), Color(0xFFF6E7B2), Color(0xFFFFFBEA), Color(0xFFF8F0D5)),
    LedgerThemeOption("pink", "淡粉色", Color(0xFF9F4A73), Color(0xFFF5D9E7), Color(0xFFFFF6FA), Color(0xFFF6E3ED)),
    LedgerThemeOption("blue", "淡蓝色", Color(0xFF416FA9), Color(0xFFD9E8FA), Color(0xFFF4F8FF), Color(0xFFE1ECFA)),
    LedgerThemeOption("cyan", "淡青色", Color(0xFF267E89), Color(0xFFD6F1F2), Color(0xFFF1FCFD), Color(0xFFDEF2F3)),
    LedgerThemeOption("green", "淡绿色", Color(0xFF4A7A53), Color(0xFFDCEED9), Color(0xFFF4FBF2), Color(0xFFE1F0DF)),
)

/** Explicit light colors keep the selected pastel palette stable in dark mode. */
fun ledgerColorScheme(themeId: String): ColorScheme {
    val theme = ledgerThemes.firstOrNull { it.id == themeId } ?: ledgerThemes.first()
    return lightColorScheme(
    primary = theme.primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = theme.primaryContainer,
    onPrimaryContainer = Color(0xFF292333),
    secondary = theme.primary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = theme.primaryContainer,
    onSecondaryContainer = Color(0xFF292333),
    tertiary = theme.primary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = theme.primaryContainer,
    onTertiaryContainer = Color(0xFF292333),
    background = theme.background,
    onBackground = Color(0xFF292333),
    surface = theme.background,
    onSurface = Color(0xFF292333),
    surfaceVariant = theme.card,
    onSurfaceVariant = Color(0xFF554C60),
    surfaceContainerLowest = theme.background,
    surfaceContainerLow = theme.card,
    surfaceContainer = theme.card,
    surfaceContainerHigh = theme.primaryContainer,
    surfaceContainerHighest = theme.primaryContainer,
    outline = theme.primary,
    outlineVariant = theme.primaryContainer,
    surfaceTint = theme.primary,
)
}
