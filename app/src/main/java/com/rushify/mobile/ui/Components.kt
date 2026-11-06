package com.rushify.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.math.BigDecimal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Surface card: 16dp corners, 1dp hairline border, no shadow. Tappable cards ease to 98.5% and 75% opacity while pressed. */
@Composable
fun RCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.985f else 1f, tween(120, easing = RMotion.EaseOut), label = "cardScale")
    val alpha by animateFloatAsState(if (pressed && onClick != null) 0.75f else 1f, tween(120, easing = RMotion.EaseOut), label = "cardAlpha")
    val base = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .clip(shape).background(RColors.Surface).border(1.dp, RColors.Hairline, shape)
    Box(if (onClick != null) base.clickable(interactionSource = source, indication = null, onClick = onClick) else base) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = RColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(text, color = RColors.Muted, fontSize = 13.sp, modifier = modifier, textAlign = align)
}

@Composable
fun Num(text: String, modifier: Modifier = Modifier, color: Color = RColors.Text, size: Int = 14, bold: Boolean = false) {
    Text(text, color = color, style = TextStyle(fontFeatureSettings = "tnum"), fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = modifier)
}

fun pnlColor(v: BigDecimal?): Color = when (v.signColorIsGain()) {
    true -> RColors.Gain
    false -> RColors.Loss
    null -> RColors.Text
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = RColors.Text) {
    Column(modifier) {
        Muted(label)
        Num(value, color = color, size = 16, bold = true)
    }
}

/** "As of" label plus the stale/offline banner demanded by the plan. */
@Composable
fun AsOfBar(asOfMillis: Long?, error: String?, hasData: Boolean, loading: Boolean, label: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        if (error != null && hasData) {
            Banner("Offline or server error — showing saved data. $error", RColors.Warn)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = label ?: asOfMillis?.let { "Updated ${Fmt.stampMillis(it)}" } ?: ""
            if (text.isNotEmpty()) Muted(text)
            if (loading) { Spacer(Modifier.padding(start = 8.dp)); Muted("refreshing…") }
        }
    }
}

@Composable
fun Banner(text: String, color: Color = RColors.Warn, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

/** Independent toggle: accent text on a faint accent fill with an accent outline when selected, muted on grey otherwise. 34dp tall. */
@Composable
fun Chip(text: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    val tick = rememberTick()
    val shape = RoundedCornerShape(50)
    Box(
        (if (tag != null) Modifier.testTag(tag) else Modifier).heightIn(min = 34.dp).clip(shape)
            .background(if (selected) RColors.Accent.copy(alpha = 0.22f) else RColors.Variant)
            .border(1.dp, if (selected) RColors.Accent.copy(alpha = 0.35f) else Color.Transparent, shape)
            .clickable { tick(); onClick() }.padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) RColors.Accent else RColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** Capsule track with a thumb that glides to the selected segment (Pulse and Performance tabs). */
@Composable
fun <T> RSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, tagPrefix: String = "seg_") {
    val tick = rememberTick()
    val reduce = rememberReduceMotion()
    val index = options.indexOf(selected).coerceAtLeast(0)
    Box(modifier.fillMaxWidth().clip(CircleShape).background(RColors.Variant).padding(3.dp)) {
        BoxWithConstraints {
            val segment = maxWidth / options.size
            val x by animateDpAsState(segment * index, if (reduce) tween(RMotion.FADE_MS) else tween(RMotion.SNAPPY_MS, easing = RMotion.EaseOut), label = "segThumb")
            Box(Modifier.offset(x = x).width(segment).height(34.dp).clip(CircleShape).background(RColors.Elevated).border(1.dp, RColors.Hairline, CircleShape))
            Row {
                options.forEach { o ->
                    Box(
                        Modifier.width(segment).height(34.dp).clip(CircleShape).testTag(tagPrefix + label(o))
                            .clickable { if (o != selected) { tick(); onSelect(o) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(label(o), color = if (o == selected) RColors.Text else RColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** First-load placeholder: a surface card of grey bars with a soft light band sweeping across (no sweep under Reduce motion). */
@Composable
fun SkeletonCard(lines: Int, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX",
    )
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(RColors.Surface).border(1.dp, RColors.Hairline, shape)
            .drawWithContent {
                drawContent()
                if (!reduce) {
                    val band = size.width * 0.6f
                    val start = size.width * 1.6f * sweep - band / 2 + size.width / 2
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), startX = start, endX = start + band))
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).background(RColors.Variant))
            repeat(lines) { i ->
                val h = if (i == 0) 30.dp else 12.dp
                val bar = Modifier.height(h).clip(RoundedCornerShape(6.dp)).background(if (i == 0) RColors.Variant.copy(alpha = 0.7f) else RColors.Variant)
                Box(if (i == lines - 1 && i > 0) bar.width(180.dp) else bar.fillMaxWidth())
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("!", color = RColors.Warn, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Couldn't load this", color = RColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Muted(message, align = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("Try again") }
    }
}

// ---------- Charts (plain Canvas, no chart library) ----------

data class Slice(val label: String, val value: Float, val color: Color)

@Composable
fun Donut(slices: List<Slice>, modifier: Modifier = Modifier, size: Dp = 140.dp, thickness: Dp = 22.dp) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    Canvas(modifier.size(size).semantics { contentDescription = "Allocation chart" }) {
        val stroke = thickness.toPx()
        val inset = stroke / 2
        var start = -90f
        slices.forEach { s ->
            val sweep = 360f * s.value / total
            drawArc(s.color, start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(inset, inset), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
            start += sweep
        }
    }
}

/** Horizontal weight bar used for sector allocation. */
@Composable
fun WeightBar(label: String, pctText: String, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = RColors.Text, fontSize = 13.sp)
            Num(pctText, color = RColors.Muted, size = 12)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(RColors.Variant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

/** Health gauge, 1.00 to 5.00, drawn as a 240 degree arc. */
@Composable
fun HealthGauge(score: Float, max: Float, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val frac = ((score - 1f) / (max - 1f)).coerceIn(0f, 1f)
    val color = when { frac < 0.4f -> RColors.Loss; frac < 0.65f -> RColors.Warn; else -> RColors.Gain }
    Box(modifier.size(size).semantics { contentDescription = "Health score $score of $max" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(RColors.Variant, 150f, 240f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 150f, 240f * frac, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Num(String.format(java.util.Locale.UK, "%.2f", score), size = 28, bold = true, color = color)
            Muted("of ${max.toInt()}.00")
        }
    }
}

/** Five (or n) axis radar, scores 0..100, labels drawn on the canvas. */
@Composable
fun RadarChart(labels: List<String>, scores: List<Int>, modifier: Modifier = Modifier, size: Dp = 280.dp) {
    val n = labels.size.coerceAtLeast(3)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = RColors.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
    Canvas(modifier.size(size).semantics { contentDescription = "Radar chart: " + labels.zip(scores).joinToString { "${it.first} ${it.second}" } }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.width / 2 - 44.dp.toPx()
        fun pt(i: Int, f: Float, extra: Float = 0f): Offset {
            val a = (-PI / 2 + 2 * PI * i / n).toFloat()
            return Offset(c.x + (r * f + extra) * cos(a), c.y + (r * f + extra) * sin(a))
        }
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { ring ->
            val p = Path()
            for (i in 0 until n) { val o = pt(i, ring); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            p.close()
            if (ring == 1f) drawPath(p, RColors.Muted, style = Stroke(1.2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) else drawPath(p, RColors.Variant, style = Stroke(1.dp.toPx()))
        }
        for (i in 0 until n) drawLine(RColors.Variant, c, pt(i, 1f), 1.dp.toPx())
        val poly = Path()
        scores.forEachIndexed { i, s ->
            val o = pt(i, (s / 100f).coerceIn(0f, 1f))
            if (i == 0) poly.moveTo(o.x, o.y) else poly.lineTo(o.x, o.y)
        }
        poly.close()
        drawPath(poly, RColors.Accent.copy(alpha = 0.25f))
        drawPath(poly, RColors.Accent, style = Stroke(2.dp.toPx()))
        scores.forEachIndexed { i, s -> drawCircle(RColors.Accent, 3.5.dp.toPx(), pt(i, (s / 100f).coerceIn(0f, 1f))) }
        labels.forEachIndexed { i, l ->
            val layout = measurer.measure("$l\n${scores.getOrNull(i) ?: 0}", labelStyle)
            val o = pt(i, 1f, 22.dp.toPx())
            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
        }
    }
}
