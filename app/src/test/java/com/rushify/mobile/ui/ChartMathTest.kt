package com.rushify.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test fun stackExtentSplitsPositiveAndNegativeSums() {
        val (lo, hi) = ChartMath.stackExtent(listOf(listOf(100f, -20f, 5f), listOf(-300f, 40f), listOf(876f, -58f, 12f)))
        assertEquals(-300f, lo, 0.001f); assertEquals(888f, hi, 0.001f)
    }
    @Test fun stackExtentAlwaysSpansZero() {
        assertEquals(0f to 0f, ChartMath.stackExtent(emptyList()))
        assertEquals(0f to 5f, ChartMath.stackExtent(listOf(listOf(5f))))
        assertEquals(-5f to 0f, ChartMath.stackExtent(listOf(listOf(-5f))))
    }
    @Test fun niceCeilUsesOneTwoFive() {
        assertEquals(1f, ChartMath.niceCeil(0.7f)); assertEquals(2f, ChartMath.niceCeil(1.3f)); assertEquals(5f, ChartMath.niceCeil(3.2f))
        assertEquals(10f, ChartMath.niceCeil(7f)); assertEquals(500f, ChartMath.niceCeil(436f)); assertEquals(20_000f, ChartMath.niceCeil(13_000f))
    }
    @Test fun niceCeilHandlesZeroAndNegative() { assertEquals(1f, ChartMath.niceCeil(0f)); assertEquals(1f, ChartMath.niceCeil(-4f)) }
    @Test fun lineBoundsPadAndIgnoreNulls() {
        val (lo, hi) = ChartMath.lineBounds(listOf(listOf(100f, null, 110f), listOf(null, 90f)))
        assertEquals(89f, lo, 0.001f); assertEquals(111f, hi, 0.001f)
    }
    @Test fun lineBoundsFlatDataGetsABand() {
        val (lo, hi) = ChartMath.lineBounds(listOf(listOf(100f, 100f)))
        assertTrue(lo < 100f && hi > 100f)
    }
    @Test fun lineBoundsEmpty() = assertEquals(0f to 1f, ChartMath.lineBounds(listOf(emptyList(), listOf(null))))
    @Test fun fractionClampsAndHandlesDegenerateRange() {
        assertEquals(0.5f, ChartMath.fraction(5f, 0f, 10f)); assertEquals(0f, ChartMath.fraction(-3f, 0f, 10f)); assertEquals(1f, ChartMath.fraction(99f, 0f, 10f))
        assertEquals(0.5f, ChartMath.fraction(1f, 2f, 2f))
    }
    @Test fun slotAtMapsTapsToBars() {
        assertEquals(0, ChartMath.slotAt(0f, 300f, 3)); assertEquals(1, ChartMath.slotAt(150f, 300f, 3)); assertEquals(2, ChartMath.slotAt(299f, 300f, 3))
        assertEquals(2, ChartMath.slotAt(999f, 300f, 3)); assertEquals(0, ChartMath.slotAt(-50f, 300f, 3)); assertEquals(-1, ChartMath.slotAt(5f, 300f, 0))
    }
    @Test fun labelStepKeepsAtMostSixLabels() {
        assertEquals(1, ChartMath.labelStep(5)); assertEquals(2, ChartMath.labelStep(12)); assertEquals(16, ChartMath.labelStep(93))
        assertTrue((0 until 93 step ChartMath.labelStep(93)).count() <= 6)
    }
    @Test fun ticksAreEvenlySpaced() = assertEquals(listOf(0f, 10f, 20f, 30f), ChartMath.ticks(0f, 30f, 3))
    @Test fun flatDetection() { assertTrue(ChartMath.isFlat(listOf(0f, 0f))); assertFalse(ChartMath.isFlat(listOf(0f, 1f))) }
    @Test fun compactAxisLabels() {
        assertEquals("950", Fmt.compact(950.0)); assertEquals("1.2k", Fmt.compact(1200.0)); assertEquals("20k", Fmt.compact(20000.0))
        assertEquals("-10k", Fmt.compact(-10000.0)); assertEquals("3.4M", Fmt.compact(3_400_000.0)); assertEquals("112k", Fmt.compact(112_345.0))
    }
}
