package com.rushify.mobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's primary button (same name as Material's, so existing call sites pick it up): 48dp, 12dp corners, accent fill with
 * dark text, grey when disabled. Pass `colors` for a variant.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = RColors.Accent, contentColor = RColors.Bg,
        disabledContainerColor = RColors.Variant, disabledContentColor = RColors.Muted,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, colors = colors,
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { ProvideTextStyle(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) { content() } }
}

/** Secondary action: accent text on a faint accent fill, 44dp. `destructive` uses the loss colour instead. */
@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val tone = if (destructive) RColors.Loss else RColors.Accent
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.copy(alpha = 0.14f), contentColor = tone,
            disabledContainerColor = RColors.Variant, disabledContentColor = RColors.Muted,
        ),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { ProvideTextStyle(TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) { content() } }
}
