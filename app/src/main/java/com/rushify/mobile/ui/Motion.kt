package com.rushify.mobile.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/** The iOS motion system: soft no-bounce springs, quick fades, and a light tick on selections. */
object RMotion {
    const val SNAPPY_MS = 280
    const val SMOOTH_MS = 350
    const val QUICK_MS = 150
    const val FADE_MS = 180
    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    fun <T> expand() = spring<T>(dampingRatio = 0.9f, stiffness = 140f)
}

/** True when the user turned animations off (Developer options or Remove animations). Shimmer and movement are skipped. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/** Light selection feedback for chips, segmented controls and collapsibles. */
@Composable
fun rememberTick(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}
