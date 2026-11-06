package com.rushify.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rushify.mobile.data.MemoryKeyValueStore
import com.rushify.mobile.data.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colour tokens ported from the iOS design pass. */
class DesignTokensTest {
    @After fun reset() { RColors.colorBlind = false }

    private fun hue(c: Color): Float {
        val r = c.red; val g = c.green; val b = c.blue
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d == 0f) return 0f
        val h = when (max) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return if (h < 0) h + 360f else h
    }

    @Test fun surfacesFormALadderOfLighterLayers() {
        val ladder = listOf(RColors.Bg, RColors.Surface, RColors.Variant, RColors.Elevated).map { it.luminance() }
        assertEquals(ladder.sorted(), ladder)
        assertEquals(4, ladder.toSet().size)
    }

    @Test fun exactSurfaceAndTextValues() {
        assertEquals(Color(0xFF0B0E14), RColors.Bg); assertEquals(Color(0xFF151B24), RColors.Surface)
        assertEquals(Color(0xFF1F2733), RColors.Variant); assertEquals(Color(0xFF28323F), RColors.Elevated)
        assertEquals(Color(0xFFF0F6FC), RColors.Text); assertEquals(Color(0xFFAAB5C1), RColors.Muted)
        assertEquals(Color(0xFF6AB0FF), RColors.Accent); assertEquals(Color(0xFFF5B84B), RColors.Warn)
    }

    @Test fun hairlineIsWhiteAtSevenPercent() { assertEquals(0.07f, RColors.Hairline.alpha, 0.001f); assertEquals(Color.White.red, RColors.Hairline.red, 0f) }

    @Test fun gainAndLossAreGreenAndRedByDefault() {
        assertEquals(Color(0xFF5AE8AC), RColors.Gain); assertEquals(Color(0xFFFF5C61), RColors.Loss)
    }

    @Test fun colourBlindPaletteSwitchesToBlueAndOrangeAndBack() {
        RColors.colorBlind = true
        assertEquals(Color(0xFFC7E5FF), RColors.Gain); assertEquals(Color(0xFFE0702F), RColors.Loss)
        assertEquals("accent and warn stay put", Color(0xFF6AB0FF), RColors.Accent)
        RColors.colorBlind = false
        assertEquals(Color(0xFF5AE8AC), RColors.Gain); assertEquals(Color(0xFFFF5C61), RColors.Loss)
    }

    @Test fun gainAndLossStayDistinctInBothPalettes() {
        for (cb in listOf(false, true)) {
            RColors.colorBlind = cb
            assertNotEquals(RColors.Gain, RColors.Loss)
            assertTrue("gain is lighter than loss for colour-blind readers", cb.not() || RColors.Gain.luminance() > RColors.Loss.luminance())
        }
    }

    @Test fun categoricalChartColoursAreEightAndNeverRedOrGreen() {
        assertEquals(8, RColors.Chart.size)
        RColors.Chart.forEach { c ->
            val h = hue(c)
            assertFalse("red-ish chart colour $c (hue $h)", h <= 15f || h >= 345f)
            assertFalse("green-ish chart colour $c (hue $h)", h in 80f..170f)
        }
        assertEquals(8, RColors.Chart.toSet().size)
    }

    @Test fun benchmarkChartUsesBluePinkAndAmberLikeIos() {
        assertEquals(Color(0xFF5AA9FF), RColors.Chart[0]); assertEquals(Color(0xFFF5B84B), RColors.Chart[2]); assertEquals(Color(0xFFF472B6), RColors.Chart[3])
    }

    @Test fun sectionTintsMatchTheMoreMenu() {
        assertEquals(Color(0xFF5AA9FF), RTint.Holdings); assertEquals(Color(0xFF7DD3FC), RTint.Sync); assertEquals(Color(0xFFA78BFA), RTint.Ai)
        assertEquals(Color(0xFFF5B84B), RTint.Performance); assertEquals(Color(0xFF818CF8), RTint.Corporate); assertEquals(Color(0xFF94A3B8), RTint.Connections)
        assertEquals(Color(0xFFF472B6), RTint.AddHolding); assertEquals(Color(0xFFFB923C), RTint.Password)
    }
}

/** The three new saved settings and their defaults. */
class SettingsPrefsTest {
    private fun session(store: MemoryKeyValueStore = MemoryKeyValueStore()) = SessionManager(store)

    @Test fun defaults() {
        val s = session()
        assertFalse(s.colorBlindPalette); assertEquals(60, s.lockTimeoutSec); assertTrue("hide in app switcher is on by default", s.screenPrivacy)
        assertFalse(s.biometricLock); assertFalse(s.hideBalances)
    }

    @Test fun settingsPersistAcrossSessionObjects() {
        val store = MemoryKeyValueStore()
        session(store).apply { colorBlindPalette = true; lockTimeoutSec = 900; screenPrivacy = false }
        val again = session(store)
        assertTrue(again.colorBlindPalette); assertEquals(900, again.lockTimeoutSec); assertFalse(again.screenPrivacy)
    }

    @Test fun lockTimeoutZeroIsKeptNotTreatedAsMissing() { val s = session(); s.lockTimeoutSec = 0; assertEquals(0, s.lockTimeoutSec) }

    @Test fun turningHideInAppSwitcherOffThenOnWorks() { val s = session(); s.screenPrivacy = false; assertFalse(s.screenPrivacy); s.screenPrivacy = true; assertTrue(s.screenPrivacy) }
}

class LockRulesTest {
    private val s = 1000L
    @Test fun timeoutChoicesMatchTheIosMenu() {
        assertEquals(listOf(0, 60, 300, 900), LockRules.timeouts.map { it.first })
        assertEquals(listOf("Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes"), LockRules.timeouts.map { it.second })
        assertEquals(60, LockRules.DEFAULT_TIMEOUT_SEC)
    }

    @Test fun labelFallsBackToTheDefaultForUnknownValues() { assertEquals("After 5 minutes", LockRules.label(300)); assertEquals("After 1 minute", LockRules.label(7)) }

    @Test fun locksOnlyAfterTheTimeoutHasPassed() {
        assertFalse("59s away with a 60s timeout", LockRules.shouldLock(true, true, false, 10_000, 10_000 + 59 * s, 60))
        assertTrue("exactly 60s", LockRules.shouldLock(true, true, false, 10_000, 10_000 + 60 * s, 60))
        assertTrue("2 minutes", LockRules.shouldLock(true, true, false, 10_000, 10_000 + 120 * s, 60))
    }

    @Test fun immediatelyLocksEveryTimeTheAppWasAway() {
        assertTrue(LockRules.shouldLock(true, true, false, 10_000, 10_001, 0))
    }

    @Test fun neverLocksWithoutHavingBeenBackgrounded() {
        assertFalse("a system dialog or the unlock prompt does not stop the activity", LockRules.shouldLock(true, true, false, 0, 99_999_999, 0))
    }

    @Test fun doesNothingWhenOffSignedOutOrAlreadyLocked() {
        assertFalse(LockRules.shouldLock(false, true, false, 10_000, 99_999_999, 0))
        assertFalse(LockRules.shouldLock(true, false, false, 10_000, 99_999_999, 0))
        assertFalse(LockRules.shouldLock(true, true, true, 10_000, 99_999_999, 0))
    }

    @Test fun startsLockedOnlyWhenOnAndSignedIn() {
        assertTrue(LockRules.startsLocked(true, true)); assertFalse(LockRules.startsLocked(true, false)); assertFalse(LockRules.startsLocked(false, true))
    }
}
