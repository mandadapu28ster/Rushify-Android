package com.rushify.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when the server connection is plain http: shows the small "Not encrypted" pill on the tab screens. */
val LocalCleartext = compositionLocalOf { false }

/** The Pulse mark on the dark background: green rise, red fall, green rise, with an accent arrowhead. Drawn on a 108-unit grid. */
@Composable
fun RushifyMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        val area = Path().apply { moveTo(24f * k, 72f * k); lineTo(42f * k, 48f * k); lineTo(58f * k, 68f * k); lineTo(77f * k, 39f * k); lineTo(77f * k, 86f * k); lineTo(24f * k, 86f * k); close() }
        drawPath(area, RColors.Accent.copy(alpha = 0.12f))
        val line = Stroke(9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun seg(a: Offset, b: Offset, c: Color) = drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }, c, style = line)
        seg(o(24f, 72f), o(42f, 48f), RColors.Gain); seg(o(42f, 48f), o(58f, 68f), RColors.Loss); seg(o(58f, 68f), o(77f, 39f), RColors.Gain)
        val head = Path().apply { moveTo(82.2f * k, 44.8f * k); lineTo(80f * k, 34f * k); lineTo(69.2f * k, 36.2f * k) }
        drawPath(head, RColors.Accent, style = Stroke(8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Dark background with a soft accent glow from the top edge, used by connect, sign in and the lock screen. */
@Composable
fun AuthBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxSize().background(RColors.Bg).drawBehind {
            drawRect(Brush.radialGradient(listOf(RColors.Accent.copy(alpha = 0.20f), Color.Transparent), center = Offset(size.width / 2f, 0f), radius = 460.dp.toPx()))
        },
    ) { content() }
}

/** Auth-screen field: 52dp, surface fill, hairline border that turns accent when focused; the placeholder is the prompt (no floating label). */
@Composable
fun RField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value, onValueChange, enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        textStyle = TextStyle(color = RColors.Text, fontSize = 16.sp), cursorBrush = SolidColor(RColors.Accent),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(RColors.Surface)
                    .border(if (focused) 1.5.dp else 1.dp, if (focused) RColors.Accent else RColors.Hairline, shape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = RColors.Muted, fontSize = 16.sp)
                inner()
            }
        },
    )
}

/** Small "Not encrypted (http)" capsule, shown at the top of the tab screens instead of a full-width banner. */
@Composable
fun HttpPill(modifier: Modifier = Modifier) {
    Text(
        "Not encrypted (http)", color = RColors.Warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.testTag("http_chip").clip(CircleShape).background(RColors.Warn.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Large title for the three tab roots, with the HTTP pill at the start and an optional action at the end. */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier, titleTag: String? = null, trailing: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            if (LocalCleartext.current) HttpPill()
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Text(title, color = RColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
    }
}

/** Shown instead of the app while locked. The tabs behind it are not composed at all. */
@Composable
fun LockScreen(failed: Boolean, onUnlock: () -> Unit) {
    AuthBackground {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            RushifyMark(72.dp)
            Spacer(Modifier.height(14.dp))
            Text("Rushify is locked", color = RColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Muted("Confirm it's you to see your portfolio.")
            if (failed) { Spacer(Modifier.height(12.dp)); Banner("Couldn't confirm it's you. Try again.", RColors.Loss, Modifier.testTag("lock_failed")) }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().testTag("unlock")) { Text("Unlock") }
        }
    }
}
