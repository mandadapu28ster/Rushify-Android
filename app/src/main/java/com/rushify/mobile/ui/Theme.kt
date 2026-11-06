package com.rushify.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Layered dark surfaces and calmer role colours, ported from the iOS app. Gain and loss switch to blue and orange when the
 * colour-blind palette is on (More > Privacy & security); everything that reads them recomposes.
 */
object RColors {
    /** Set from the session preference at the app root. */
    var colorBlind by mutableStateOf(false)

    val Bg = Color(0xFF0B0E14)
    val Surface = Color(0xFF151B24)
    val Variant = Color(0xFF1F2733)
    val Elevated = Color(0xFF28323F)
    /** 1dp border on cards and fields: white at 7%. */
    val Hairline = Color.White.copy(alpha = 0.07f)
    val Accent = Color(0xFF6AB0FF)
    val Warn = Color(0xFFF5B84B)
    val Gain: Color get() = if (colorBlind) Color(0xFFC7E5FF) else Color(0xFF5AE8AC)
    val Loss: Color get() = if (colorBlind) Color(0xFFE0702F) else Color(0xFFFF5C61)
    val Text = Color(0xFFF0F6FC)
    val Muted = Color(0xFFAAB5C1)
    /** Categorical chart colours: none is red or green, so they stay distinct from gain and loss. */
    val Chart = listOf(
        Color(0xFF5AA9FF), Color(0xFFA78BFA), Color(0xFFF5B84B), Color(0xFFF472B6),
        Color(0xFF7DD3FC), Color(0xFFFB923C), Color(0xFF818CF8), Color(0xFFC4B5FD),
    )
}

/** Tint of each More-menu section: the icon colour, and the same colour at 16% behind it. */
object RTint {
    val Holdings = Color(0xFF5AA9FF); val Sync = Color(0xFF7DD3FC); val Ai = Color(0xFFA78BFA); val Performance = Color(0xFFF5B84B)
    val Corporate = Color(0xFF818CF8); val Connections = Color(0xFF94A3B8); val AddHolding = Color(0xFFF472B6); val Password = Color(0xFFFB923C)
}

val Mono = FontFamily.Monospace

/** When true, monetary amounts render as dots ("hide balances"). */
val LocalHideBalances = compositionLocalOf { false }

private val scheme = darkColorScheme(
    primary = RColors.Accent, onPrimary = RColors.Bg,
    background = RColors.Bg, onBackground = RColors.Text,
    surface = RColors.Surface, onSurface = RColors.Text,
    surfaceVariant = RColors.Variant, secondary = RColors.Accent, surfaceContainer = RColors.Surface, surfaceContainerHigh = RColors.Elevated, onSurfaceVariant = RColors.Muted,
    error = RColors.Loss, outline = RColors.Variant,
)

/** Largest system font scale the layouts are designed for (iOS caps Dynamic Type at accessibility 2). */
const val MAX_FONT_SCALE = 2f

@Composable
fun RushifyTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, MAX_FONT_SCALE))) {
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
}
