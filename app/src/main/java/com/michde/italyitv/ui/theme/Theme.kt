package com.michde.italyitv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val InkBg = Color(0xFF0A0C11)
val InkSurface = Color(0xFF141925)
val InkSurfaceHigh = Color(0xFF1C2432)
val InkSurfaceHighest = Color(0xFF26303F)
val Ivory = Color(0xFFF5F1E7)
val IvoryDim = Color(0xFFBCC3D0)
val Amber = Color(0xFFF4B13A)
val AmberInk = Color(0xFF2A1B04)
val SignRed = Color(0xFFD6452F)
val InkOutline = Color(0xFF3C4759)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = AmberInk,
    primaryContainer = InkSurfaceHigh,
    onPrimaryContainer = Ivory,
    secondary = Amber,
    onSecondary = AmberInk,
    tertiary = SignRed,
    onTertiary = Ivory,
    background = InkBg,
    onBackground = Ivory,
    surface = InkSurface,
    onSurface = Ivory,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = IvoryDim,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerHighest = InkSurfaceHighest,
    surfaceContainerLow = InkBg,
    surfaceContainerLowest = InkBg,
    outline = InkOutline,
    outlineVariant = InkSurfaceHigh,
    error = SignRed,
    onError = Ivory,
)

private val AppTypography = Typography().run {
    val cond = FontFamily.SansSerif
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFamily = cond, color = Ivory),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFamily = cond, color = Ivory),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium, fontFamily = cond, color = Ivory),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = cond, color = Ivory),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFamily = cond),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = IvoryDim),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
