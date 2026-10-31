package com.rushify.mobile.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/** Pure helpers behind the Canvas charts, kept separate so they can be unit-tested. */
object ChartMath {
    /** (most negative stack, most positive stack) across bars; always spans zero. */
    fun stackExtent(stacks: List<List<Float>>): Pair<Float, Float> {
        var lo = 0f; var hi = 0f
        stacks.forEach { st ->
            lo = minOf(lo, st.filter { it < 0 }.sum())
            hi = maxOf(hi, st.filter { it > 0 }.sum())
        }
        return lo to hi
    }

    /** Round up to 1, 2 or 5 times a power of ten so gridlines land on tidy numbers. */
    fun niceCeil(v: Float): Float {
        if (v <= 0f) return 1f
        val exp = 10.0.pow(kotlin.math.floor(log10(v.toDouble()))).toFloat()
        val f = v / exp
        val nice = when { f <= 1f -> 1f; f <= 2f -> 2f; f <= 5f -> 5f; else -> 10f }
        return nice * exp
    }

    /** Min/max over every non-null value, padded 5% so lines do not touch the frame. Flat data gets a +-1 band. */
    fun lineBounds(series: List<List<Float?>>): Pair<Float, Float> {
        val all = series.flatten().filterNotNull()
        if (all.isEmpty()) return 0f to 1f
        val lo = all.min(); val hi = all.max()
        if (hi - lo < 1e-6f) return lo - 1f to hi + 1f
        val pad = (hi - lo) * 0.05f
        return lo - pad to hi + pad
    }

    fun fraction(v: Float, lo: Float, hi: Float): Float = if (hi - lo <= 0f) 0.5f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    /** Which of `count` equal-width slots a tap at x falls into. */
    fun slotAt(x: Float, width: Float, count: Int): Int =
        if (count <= 0 || width <= 0f) -1 else (x / width * count).toInt().coerceIn(0, count - 1)

    /** Show every k-th x label so at most `max` appear. */
    fun labelStep(count: Int, max: Int = 6): Int = if (count <= max) 1 else ceil(count / max.toDouble()).toInt()

    /** Evenly spaced y values for gridlines. */
    fun ticks(lo: Float, hi: Float, n: Int = 3): List<Float> = (0..n).map { lo + (hi - lo) * it / n }

    fun isFlat(values: List<Float>) = values.isEmpty() || values.all { abs(it) < 1e-9f }
}
