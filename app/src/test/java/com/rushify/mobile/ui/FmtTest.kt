package com.rushify.mobile.ui

import java.math.BigDecimal
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    private fun d(s: String) = BigDecimal(s)

    @Test fun gbpMoneyUsesSymbolAndGrouping() = assertEquals("£126,677.59", Fmt.money(d("126677.59260671739729500"), "GBP"))
    @Test fun negativeMoney() = assertEquals("-£1,594.59", Fmt.money(d("-1594.589302181201796"), "GBP"))
    @Test fun usdAndEur() { assertEquals("$349.21", Fmt.money(d("349.21"), "USD")); assertEquals("€1.50", Fmt.money(d("1.5"), "EUR")) }
    @Test fun otherCurrencyUsesCode() = assertEquals("1,506.50 INR", Fmt.money(d("1506.5"), "INR"))
    @Test fun nullIsDash() = assertEquals("—", Fmt.money(null, "GBP"))
    @Test fun hideBalancesMasksAmounts() { assertEquals("••••", Fmt.money(d("5"), "GBP", hide = true)); assertEquals("••••", Fmt.signedMoney(d("5"), "GBP", hide = true)) }
    @Test fun signedMoneyShowsPlus() { assertEquals("+£23,438.45", Fmt.signedMoney(d("23438.453"), "GBP")); assertEquals("£0.00", Fmt.signedMoney(BigDecimal.ZERO, "GBP")) }
    @Test fun roundingIsHalfUpOnBigDecimal() { assertEquals("£0.01", Fmt.money(d("0.005"), "GBP")); assertEquals("£1,000.00", Fmt.money(d("999.995"), "GBP")) }
    @Test fun noFloatingPointDrift() = assertEquals("£0.30", Fmt.money(d("0.1").add(d("0.2")), "GBP"))
    @Test fun xirrFractionToPercent() = assertEquals("+10.79%", Fmt.percentFromFraction(d("0.10791569"), signed = true))
    @Test fun percentAsIs() = assertEquals("44.4%", Fmt.percent(d("44.418840380681663911568"), 1))
    @Test fun quantityTrimsTrailingZeros() { assertEquals("0.4318", Fmt.quantity(d("0.43181800"))); assertEquals("114.519", Fmt.quantity(d("114.519041"))); assertEquals("48", Fmt.quantity(d("48.00000000"))); assertEquals("1,247", Fmt.quantity(d("1247"))) }
    @Test fun stampFormatsInstantInZone() = assertEquals("17 Oct, 11:55", Fmt.stamp("2026-10-17T11:55:49.778767Z", ZoneOffset.UTC))
    @Test fun stampAcceptsNaiveUtcTimestamps() = assertEquals("17 Oct, 11:55", Fmt.stamp("2026-10-17T11:55:49.778767", ZoneOffset.UTC))
    @Test fun stampOfGarbageIsDash() = assertEquals("—", Fmt.stamp("not a date", ZoneOffset.UTC))
    @Test fun pluralHelper() { assertEquals("1 position", Fmt.plural(1, "position")); assertEquals("2 positions", Fmt.plural(2, "position")) }
    @Test fun unrealisedPercentIsExactDecimal() = assertEquals(d("22.70"), unrealisedPct(d("23438.45"), d("103239.14")))
    @Test fun unrealisedPercentGuardsZeroCost() = assertEquals(null, unrealisedPct(d("1"), BigDecimal.ZERO))
}
