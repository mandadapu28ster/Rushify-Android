package com.rushify.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RColors {
    val Bg = Color(0xFF0B0E14)
    val Surface = Color(0xFF161B22)
    val Variant = Color(0xFF21262D)
    val Accent = Color(0xFF58A6FF)
    val Gain = Color(0xFF00E676)
    val Loss = Color(0xFFFF5252)
    val Text = Color(0xFFF0F6FC)
    val Muted = Color(0xFF8B949E)
    val Warn = Color(0xFFFFB74D)
    val Chart = listOf(
        Color(0xFF58A6FF), Color(0xFF00E676), Color(0xFFFFB74D), Color(0xFFBA68C8),
        Color(0xFF4DD0E1), Color(0xFFFF8A65), Color(0xFFAED581), Color(0xFFF06292),
        Color(0xFF9FA8DA), Color(0xFFFFF176),
    )
}

val Mono = FontFamily.Monospace

/** When true, monetary amounts render as dots ("hide balances"). */
val LocalHideBalances = compositionLocalOf { false }

private val scheme = darkColorScheme(
    primary = RColors.Accent, onPrimary = RColors.Bg,
    background = RColors.Bg, onBackground = RColors.Text,
    surface = RColors.Surface, onSurface = RColors.Text,
    surfaceVariant = RColors.Variant, onSurfaceVariant = RColors.Muted,
    error = RColors.Loss, outline = RColors.Variant,
)

@Composable
fun RushifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelSmall = TextStyle(fontSize = 11.sp),
        ),
        content = content,
    )
}
