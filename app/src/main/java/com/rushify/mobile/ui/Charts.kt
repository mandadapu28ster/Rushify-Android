package com.rushify.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `fadedFrom`: segments at this index and later are drawn translucent (estimates). `faded` fades the whole bar. */
data class StackedBar(val label: String, val values: List<Float>, val faded: Boolean = false, val fadedFrom: Int = Int.MAX_VALUE)
data class ChartLine(val name: String, val color: Color, val values: List<Float?>, val dashed: Boolean = false)

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Muted(text)
    }
}

/**
 * Bars stacked from a zero line: positives upwards, negatives downwards. Tap a bar to select it.
 * `faded` bars (forecasts) are drawn translucent with an outline so estimates are never mistaken for actuals.
 */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>, colors: List<Color>, selected: Int?, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, height: Dp = 190.dp, description: String = "Bar chart",
    averageLine: Float? = null, zoneFromIndex: Int? = null,
) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = RColors.Muted, fontSize = 10.sp)
    Canvas(
        modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
            .pointerInput(bars.size) { detectTapGestures { o -> onSelect(ChartMath.slotAt(o.x - 0f, size.width.toFloat(), bars.size)) } },
    ) {
        if (bars.isEmpty()) return@Canvas
        val leftPad = 38.dp.toPx(); val bottomPad = 20.dp.toPx(); val topPad = 6.dp.toPx()
        val plotW = size.width - leftPad; val plotH = size.height - bottomPad - topPad
        val (negRaw, posRaw) = ChartMath.stackExtent(bars.map { it.values })
        val top = ChartMath.niceCeil(posRaw.coerceAtLeast(0.0001f)); val bottom = if (negRaw < 0) -ChartMath.niceCeil(-negRaw) else 0f
        fun y(v: Float) = topPad + plotH * (1f - ChartMath.fraction(v, bottom, top))
        ChartMath.ticks(bottom, top, 3).forEach { t ->
            drawLine(RColors.Variant, Offset(leftPad, y(t)), Offset(size.width, y(t)), 1.dp.toPx())
            val l = measurer.measure(Fmt.compact(t.toDouble()), axis)
            drawText(l, topLeft = Offset(leftPad - l.size.width - 4.dp.toPx(), y(t) - l.size.height / 2f))
        }
        val slot = plotW / bars.size; val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
        val step = ChartMath.labelStep(bars.size)
        zoneFromIndex?.takeIf { it in bars.indices }?.let { z -> drawRect(RColors.Muted.copy(alpha = 0.10f), Offset(leftPad + slot * z, topPad), Size(size.width - leftPad - slot * z, plotH)) }
        bars.forEachIndexed { i, b ->
            val x = leftPad + slot * i + (slot - barW) / 2f
            var posTop = 0f; var negTop = 0f
            b.values.forEachIndexed { k, v ->
                val c = colors[k % colors.size].copy(alpha = if (b.faded || k >= b.fadedFrom) 0.45f else 1f)
                if (v >= 0) { val y0 = y(posTop); val y1 = y(posTop + v); drawRect(c, Offset(x, y1), Size(barW, (y0 - y1).coerceAtLeast(0f))); posTop += v }
                else { val y0 = y(negTop); val y1 = y(negTop + v); drawRect(c, Offset(x, y0), Size(barW, (y1 - y0).coerceAtLeast(0f))); negTop += v }
            }
            if (b.faded) drawRect(RColors.Muted, Offset(x, y(posTop)), Size(barW, (y(0f) - y(posTop)).coerceAtLeast(1f)), style = Stroke(1.dp.toPx()))
            if (selected == i) drawRect(RColors.Text.copy(alpha = 0.14f), Offset(leftPad + slot * i, topPad), Size(slot, plotH))
            if (i % step == 0) {
                val l = measurer.measure(b.label.replace(" 20", " '"), axis)
                drawText(l, topLeft = Offset(leftPad + slot * i + slot / 2f - l.size.width / 2f, size.height - bottomPad + 4.dp.toPx()))
            }
        }
        drawLine(RColors.Muted, Offset(leftPad, y(0f)), Offset(size.width, y(0f)), 1.dp.toPx())
        averageLine?.let { a -> drawLine(RColors.Warn, Offset(leftPad, y(a)), Offset(size.width, y(a)), 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
    }
}

/** Multi-series line chart. Tap or drag to move the cursor; the caller shows the values for `selected`. */
@Composable
fun LineChart(
    xLabels: List<String>, lines: List<ChartLine>, selected: Int?, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, height: Dp = 190.dp, description: String = "Line chart",
) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = RColors.Muted, fontSize = 10.sp)
    Canvas(
        modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
            .pointerInput(xLabels.size) { detectTapGestures { o -> onSelect(ChartMath.slotAt(o.x, size.width.toFloat(), xLabels.size)) } }
            .pointerInput(xLabels.size) { detectHorizontalDragGestures { change, _ -> onSelect(ChartMath.slotAt(change.position.x, size.width.toFloat(), xLabels.size)) } },
    ) {
        val n = xLabels.size
        if (n < 2) return@Canvas
        val leftPad = 42.dp.toPx(); val bottomPad = 20.dp.toPx(); val topPad = 6.dp.toPx()
        val plotW = size.width - leftPad - 6.dp.toPx(); val plotH = size.height - bottomPad - topPad
        val (lo, hi) = ChartMath.lineBounds(lines.map { it.values })
        fun x(i: Int) = leftPad + plotW * i / (n - 1)
        fun y(v: Float) = topPad + plotH * (1f - ChartMath.fraction(v, lo, hi))
        ChartMath.ticks(lo, hi, 3).forEach { t ->
            drawLine(RColors.Variant, Offset(leftPad, y(t)), Offset(size.width, y(t)), 1.dp.toPx())
            val l = measurer.measure(Fmt.compact(t.toDouble()), axis)
            drawText(l, topLeft = Offset(leftPad - l.size.width - 4.dp.toPx(), y(t) - l.size.height / 2f))
        }
        lines.forEach { ln ->
            val path = Path(); var started = false
            ln.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, ln.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = if (ln.dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
        }
        val step = ChartMath.labelStep(n)
        for (i in 0 until n step step) {
            val l = measurer.measure(xLabels[i], axis)
            val lx = (x(i) - l.size.width / 2f).coerceIn(leftPad - 8.dp.toPx(), size.width - l.size.width.toFloat())
            drawText(l, topLeft = Offset(lx, size.height - bottomPad + 4.dp.toPx()))
        }
        selected?.takeIf { it in 0 until n }?.let { s ->
            drawLine(RColors.Text.copy(alpha = 0.5f), Offset(x(s), topPad), Offset(x(s), topPad + plotH), 1.dp.toPx())
            lines.forEach { ln -> ln.values.getOrNull(s)?.let { drawCircle(ln.color, 4.dp.toPx(), Offset(x(s), y(it))) } }
        }
    }
}


/** One horizontal stacked bar: segments are proportional to value within `maxValue`. */
@Composable
fun HBar(segments: List<Pair<Float, Color>>, maxValue: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Row(modifier.fillMaxWidth().height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(RColors.Variant)) {
        val total = segments.sumOf { it.first.toDouble() }.toFloat()
        val scale = if (maxValue <= 0f) 0f else (total / maxValue).coerceIn(0f, 1f)
        if (scale > 0f && total > 0f) {
            Row(Modifier.fillMaxWidth(scale)) { segments.filter { it.first > 0f }.forEach { (v, c) -> Box(Modifier.weight(v / total).height(height).background(c)) } }
        }
    }
}
