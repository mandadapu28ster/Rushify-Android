package com.rushify.mobile.ui

import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.Dividends
import com.rushify.mobile.data.DividendLayer
import com.rushify.mobile.data.DividendPoint
import com.rushify.mobile.data.Growth
import com.rushify.mobile.data.GrowthBroker
import com.rushify.mobile.data.GrowthPeriod
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.ManualWealthSeries
import com.rushify.mobile.data.WealthAsset
import com.rushify.mobile.data.WealthSnapshot
import com.rushify.mobile.fixture
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsLogicTest {
    private fun d(s: String) = BigDecimal(s)
    private fun row(ticker: String = "T", source: String = "t212", label: String? = null, kind: String? = null, acct: String? = null, mv: String? = null,
                    cost: String? = null, pnl: String? = null, sector: String? = null, name: String? = null, manualBroker: String? = null, qty: String? = null) =
        HoldingRow(ticker = ticker, source = source, brokerLabel = label, assetKind = kind, accountKind = acct, marketValueBase = mv?.let(::d), costBase = cost?.let(::d),
            pnlStockBase = pnl?.let(::d), sector = sector, instrumentName = name, manualBrokerName = manualBroker, quantity = qty?.let(::d))
    private val holdings = AppJson.decodeFromString(Dashboard.serializer(), fixture("holdings_dashboard.json")).rows

    // ======================= institution keys =======================
    @Test fun institutionKeysStripAccountWrappers() {
        assertEquals("Trading 212", Custodian.institutionKey(row(label = "Trading 212 · ISA")))
        assertEquals("Trading 212", Custodian.institutionKey(row(source = "csv", label = "Trading 212 · Cash ISA (CSV)")))
        assertEquals("AJ Bell", Custodian.institutionKey(row(source = "snaptrade", label = "AJ Bell · LISA")))
        assertEquals("AJ Bell", Custodian.institutionKey(row(source = "snaptrade", label = "AJ Bell · My nickname")))      // snaptrade always aggregates on institution
        assertEquals("Lightyear", Custodian.institutionKey(row(source = "csv", label = "Lightyear · Invest (CSV)")))
        assertEquals("Zopa Bank", Custodian.institutionKey(row(source = "manual", manualBroker = "Zopa Bank")))
        assertEquals("Trading 212", Custodian.institutionKey(row(source = "manual", manualBroker = "Trading 212 Cash ISA")))
        assertEquals("Hargreaves Lansdown", Custodian.institutionKey(row(source = "manual", manualBroker = "Hargreaves Lansdown · ISA")))
    }
    @Test fun specialInstitutionNames() {
        assertEquals("Property", Custodian.institutionKey(row(kind = "real_estate"))); assertEquals("Other assets", Custodian.institutionKey(row(kind = "other")))
        assertEquals("No institution name", Custodian.institutionKey(row(source = "manual", label = "Manual entry")))
        assertEquals("Trading 212", Custodian.institutionKey(row(source = "t212", label = null))); assertEquals("SnapTrade", Custodian.institutionKey(row(source = "snaptrade", label = " ")))
        assertEquals("Other", Custodian.institutionKey(row(source = "", label = null)))
    }
    @Test fun listedWrapperLabels() {
        listOf("isa" to "ISA", "sipp" to "SIPP", "invest" to "Invest", "lisa" to "LISA", "lifetime_isa" to "LISA", "cash_isa" to "ISA", "junior_isa" to "JISA", "junior_sipp" to "JSIPP", "jsipp" to "JSIPP", "zzz" to "Other", "" to "Other")
            .forEach { (k, want) -> assertEquals(k, want, Custodian.listedAccountLabel(row(acct = k))) }
        assertEquals("", Custodian.listedAccountLabel(row(kind = "cash")))
    }
    @Test fun cashBucketLabels() {
        assertEquals("Joint account", Custodian.cashBucketLabel(row(source = "monzo", kind = "cash", acct = "joint")))
        assertEquals("Cash ISA", Custodian.cashBucketLabel(row(source = "csv", kind = "cash", acct = "cash_isa")))
        assertEquals("some thing", Custodian.cashBucketLabel(row(source = "t212", kind = "cash", acct = "some_thing")))
        assertEquals("Cash ISA", Custodian.cashBucketLabel(row(source = "manual", kind = "cash", name = "Zopa Bank · Cash ISA")))
        assertEquals("Savings account", Custodian.cashBucketLabel(row(source = "manual", kind = "cash", name = "A · B · Savings account")))
        assertEquals("Plain", Custodian.cashBucketLabel(row(source = "manual", kind = "cash", name = "Plain"))); assertEquals("Other", Custodian.cashBucketLabel(row(source = "manual", kind = "cash")))
        assertEquals("", Custodian.cashBucketLabel(row(kind = null)))
    }

    // ======================= market value fallbacks =======================
    @Test fun quotedValueWins() = assertEquals(d("5"), Custodian.marketValue(row(mv = "5", cost = "9")))
    @Test fun nonPositiveValueOfBrokerRowIsZero() = assertEquals(0, BigDecimal.ZERO.compareTo(Custodian.marketValue(row(mv = "0", cost = "9"))))
    @Test fun openManualLotFallsBackToCostThenAveragePrice() {
        val manual = HoldingRow(ticker = "X", source = "manual", manualHoldingId = 1, quantity = d("2"))
        assertEquals(d("30"), Custodian.marketValue(manual.copy(costBase = d("30"))))
        assertEquals(d("25"), Custodian.marketValue(manual.copy(costBaseGbp = d("25"))))
        assertEquals(0, d("16").compareTo(Custodian.marketValue(manual.copy(avgPrice = d("10"), fxToBase = d("0.8")))))
        assertEquals(0, d("20").compareTo(Custodian.marketValue(manual.copy(avgPrice = d("10")))))
        assertEquals(0, BigDecimal.ZERO.compareTo(Custodian.marketValue(manual.copy(soldOutAt = "2026-01-01", costBase = d("30")))))
    }

    // ======================= net worth by broker =======================
    @Test fun brokerRowsAreSortedAndTotalledLikeTheWeb() {
        val r = Custodian.build(holdings)
        assertEquals(listOf("Property", "Zopa Bank", "Trading 212", "Other assets", "Monzo", "AJ Bell", "Hargreaves"), r.map { it.name })
        val t212 = r.first { it.name == "Trading 212" }
        assertEquals(d("340"), t212.equity); assertEquals(d("5050"), t212.cash); assertEquals(d("5390"), t212.total)
    }
    @Test fun listedAndCashAreSplitByAccountType() {
        val t212 = Custodian.build(holdings).first { it.name == "Trading 212" }
        assertEquals(listOf("ISA" to d("300"), "Invest" to d("40")), t212.listedByAccount.map { it.account to it.value })
        assertEquals(listOf("Other" to d("5000"), "ISA" to d("50")), t212.cashByAccount.map { it.account to it.value })
        assertEquals(d("300"), t212.wrappers["ISA"]); assertEquals(d("40"), t212.wrappers["Invest"]); assertEquals(BigDecimal.ZERO, t212.wrappers["SIPP"])
    }
    @Test fun aSingleOtherSplitIsHiddenButStillStacked() {
        val aj = Custodian.build(holdings).first { it.name == "AJ Bell" }
        assertTrue(aj.listedByAccount.isEmpty()); assertTrue(aj.cashByAccount.isEmpty()); assertEquals(d("530"), aj.wrappers["Other"])
        assertEquals(d("550"), aj.total)
    }
    @Test fun monzoCashIsBucketedByAccountType() {
        val m = Custodian.build(holdings).first { it.name == "Monzo" }
        assertEquals(setOf("Current account", "Young account", "Savings", "Pot"), m.cashByAccount.map { it.account }.toSet()); assertEquals(d("1385.04"), m.cash)
    }
    @Test fun zeroValueRowsAreSkippedSoSoldLotsAddNothing() = assertEquals(d("180"), Custodian.build(holdings).first { it.name == "Hargreaves" }.total)
    @Test fun grandTotalMatchesTheDashboardRowsWithValue() =
        assertEquals(holdings.fold(BigDecimal.ZERO) { a, r -> a + Custodian.marketValue(r) }, Custodian.build(holdings).fold(BigDecimal.ZERO) { a, r -> a + r.total })
    @Test fun brokerPickerLabelsIncludeOpenAndSoldManualBrokers() {
        val labels = Custodian.listedEquityBrokerLabels(holdings)
        assertTrue(labels.containsAll(listOf("AJ Bell · LISA", "Hargreaves", "Trading 212 · ISA", "Trading 212 · Invest")))
        assertFalse(labels.contains("Monzo")); assertEquals(labels.sortedWith(String.CASE_INSENSITIVE_ORDER), labels)
    }

    // ======================= sectors =======================
    @Test fun sectorsAggregateListedRowsOnly() {
        val s = Sectors.build(AppJson.decodeFromString(Dashboard.serializer(), fixture("dashboard.json")).rows)
        assertEquals(listOf("Technology", "Health", "Unknown"), s.map { it.sector })
        assertEquals(d("600.30"), s[0].value); assertEquals(d("500.00"), s[0].cost); assertEquals(d("100.30"), s[0].gain); assertEquals(d("20.06"), s[0].gainPct)
        assertEquals(d("59.94"), s[0].allocPct); assertEquals(d("100.00"), s.fold(BigDecimal.ZERO) { a, x -> a + x.allocPct }.setScale(0, java.math.RoundingMode.HALF_UP).setScale(2))
    }
    @Test fun sectorHoldingsMergeLotsAndShowShareOfSector() {
        val rows = listOf(row("aaa", sector = "Tech", mv = "30", cost = "20", pnl = "10"), row("AAA", source = "snaptrade", sector = "Tech", mv = "10", cost = "10", pnl = "0"), row("BBB", sector = "Tech", mv = "60", cost = "50", pnl = "10"))
        val tech = Sectors.build(rows).single()
        assertEquals(listOf("BBB", "AAA"), tech.holdings.map { it.ticker }); assertEquals(d("40"), tech.holdings[1].value); assertEquals(d("40.00"), tech.holdings[1].shareOfSectorPct)
        assertEquals(d("100"), tech.value); assertEquals(d("20"), tech.gain)
    }
    @Test fun sectorsIgnoreCashMonzoAndNonListed() {
        val rows = listOf(row("CASH.1", kind = "cash", mv = "5"), row("M", source = "monzo", mv = "5"), row("P", kind = "real_estate", mv = "5"), row("Z", mv = "0"), row("OK", mv = "2", sector = "S"))
        assertEquals(listOf("S"), Sectors.build(rows).map { it.sector })
    }
    @Test fun sectorGainPctIsNullWithoutCost() = assertNull(Sectors.build(listOf(row("A", mv = "5", sector = "S"))).single().gainPct)

    // ======================= position bars =======================
    @Test fun positionBarsMergeBrokersSortByValueAndKeepCostAndGain() {
        val bars = PositionBars.build(listOf(row("GOOGL", source = "snaptrade", mv = "300", cost = "200"), row("googl", mv = "100", cost = "150"), row("AAPL", mv = "500", cost = "400"), row("CASH.x", kind = "cash", mv = "9")))
        assertEquals(listOf("AAPL", "GOOGL"), bars.map { it.ticker })
        assertEquals(d("400"), bars[1].value); assertEquals(d("350"), bars[1].cost); assertEquals(d("50"), bars[1].pnl); assertEquals(d("100"), bars[0].pnl)
    }
    @Test fun positionBarFallsBackToRowPnlWhenCostIsMissing() = assertEquals(d("7"), PositionBars.build(listOf(row("A", mv = "50", pnl = "7"))).single().pnl)
    @Test fun positionBarTieBreaksByTicker() = assertEquals(listOf("A", "B"), PositionBars.build(listOf(row("B", mv = "5"), row("A", mv = "5"))).map { it.ticker })
    @Test fun positionBarsStripVenueSuffixes() = assertEquals(listOf("INFY"), PositionBars.build(listOf(row("INFY_US_EQ", mv = "5"))).map { it.ticker })

    // ======================= growth =======================
    private val growth = AppJson.decodeFromString(Growth.serializer(), fixture("growth.json"))
    @Test fun growthSummaryAddsUpAndComponentsReconcile() {
        val s = GrowthMath.summary(growth.periods)
        assertEquals(d("654.94"), s.total); assertEquals(d("299.50"), s.price); assertEquals(d("376.48"), s.interaction); assertEquals(d("17.00"), s.income); assertEquals(d("-38.04"), s.currency)
        assertEquals(s.total, s.price + s.interaction + s.income + s.currency)
    }
    @Test fun sharesOfTotalUseOneDecimal() {
        val s = GrowthMath.summary(growth.periods)
        assertEquals("45.7% of total", s.share(s.price)); assertEquals("57.5% of total", s.share(s.interaction)); assertEquals("-5.8% of total", s.share(s.currency))
    }
    @Test fun shareIsADashWhenTheTotalIsAboutZero() = assertEquals("—", GrowthMath.summary(listOf(GrowthPeriod(totalGrowthBase = BigDecimal.ZERO, priceGainBase = d("5")))).share(d("5")))
    @Test fun stackSumNoteOnlyAppearsBeyondRounding() {
        val ok = GrowthPeriod(priceGainBase = d("10"), interactionGainBase = d("1"), incomeBase = d("1"), currencyGainBase = d("-2"), totalGrowthBase = d("10.01"))
        assertEquals(d("10"), GrowthMath.stackSum(ok)); assertFalse(GrowthMath.stackDiffers(ok)); assertTrue(GrowthMath.stackDiffers(ok.copy(totalGrowthBase = d("10.5"))))
    }
    @Test fun interactionNoteExplainsTheRemainder() = assertTrue(GrowthMath.INTERACTION_NOTE.contains("remainder") && GrowthMath.INTERACTION_NOTE.contains("Interaction"))

    // ======================= benchmarks =======================
    @Test fun percentagePointGapAndWording() {
        assertEquals(d("4.38"), BenchmarkMath.points(d("7.33"), d("2.95"))); assertEquals("+4.38 pp", BenchmarkMath.pointsText(d("4.38"))); assertEquals("-1.20 pp", BenchmarkMath.pointsText(d("-1.2")))
        assertNull(BenchmarkMath.points(null, d("1"))); assertEquals("—", BenchmarkMath.pointsText(null))
        assertEquals("Portfolio total return exceeded FTSE 100 over the same dates.", BenchmarkMath.verdict(d("1"), "FTSE 100"))
        assertEquals("Portfolio total return trailed S&P 100 (^OEX) over the same dates.", BenchmarkMath.verdict(d("-1"), "S&P 100 (^OEX)")); assertNull(BenchmarkMath.verdict(null, "x"))
        assertEquals("+0.00 pp", BenchmarkMath.pointsText(BigDecimal.ZERO))
    }

    // ======================= non-equity manual gain =======================
    private fun dash(json: String) = AppJson.decodeFromString(Dashboard.serializer(), json)
    @Test fun nonEquityGainShownWithBlendedAnnualPercent() {
        val (gain, pct) = NonEquityGain.of(dash("""{"non_equity_manual_gain_total_base":"63757.56","non_equity_manual_basis_lines":6,"non_equity_manual_annualized_pct":"0.11836804"}"""))!!
        assertEquals(d("63757.56"), gain); assertEquals(0, d("11.836804").compareTo(pct))
    }
    @Test fun nonEquityGainHiddenWithoutLinesOrWhenImmaterialOrMissing() {
        assertNull(NonEquityGain.of(dash("""{"non_equity_manual_gain_total_base":"5","non_equity_manual_basis_lines":0}""")))
        assertNull(NonEquityGain.of(dash("""{"non_equity_manual_gain_total_base":"0","non_equity_manual_basis_lines":2}""")))
        assertNull(NonEquityGain.of(dash("""{"non_equity_manual_basis_lines":2}""")))
        assertNull(NonEquityGain.of(dash("{}")))
    }

    // ======================= wealth over time =======================
    private fun asset(id: Int, kind: String, eligible: Boolean = true, show: Boolean = true, vararg snaps: Pair<String, String>) =
        WealthAsset(id, "Line $id", kind, show, snaps.size, eligible, false, snaps.map { WealthSnapshot(it.first, d(it.second), "manual") })
    private val wealth = ManualWealthSeries(assets = listOf(
        asset(1, "real_estate", true, true, "2025-01-01" to "100", "2026-01-01" to "120"),
        asset(2, "cash", true, true, "2025-06-01" to "10"),
        asset(3, "cash", false, true, "2025-02-01" to "99"),
        asset(4, "other", true, false, "2025-03-01" to "7"),
    ))
    @Test fun sectionOnlyAppearsWhenSomeLineQualifies() {
        assertTrue(WealthMath.visible(wealth)); assertFalse(WealthMath.visible(null)); assertFalse(WealthMath.visible(ManualWealthSeries(assets = listOf(asset(3, "cash", false)))))
        assertEquals(listOf(1, 2), WealthMath.eligible(wealth).map { it.manualHoldingId }); assertEquals(1, WealthMath.ineligibleCount(wealth))
    }
    @Test fun valuesCarryForwardFromTheLastSnapshotOnOrBeforeEachDate() {
        val c = WealthMath.chart(wealth, WealthFilter.All)
        assertEquals(listOf("2025-01-01", "2025-06-01", "2026-01-01"), c.dates)
        assertEquals(listOf(d("100"), d("100"), d("120")), c.lines[0].second); assertEquals(listOf(BigDecimal.ZERO, d("10"), d("10")), c.lines[1].second)
    }
    @Test fun filtersByKind() {
        assertEquals(listOf("Line 1"), WealthMath.chart(wealth, WealthFilter.Property).lines.map { it.first }); assertEquals(listOf("Line 2"), WealthMath.chart(wealth, WealthFilter.Cash).lines.map { it.first })
        assertTrue(WealthMath.chart(wealth, WealthFilter.Other).lines.isEmpty())
    }

    // ======================= listed equity per broker =======================
    private fun pt(label: String, mv: String?, inv: String?, vararg b: Triple<String, String, String>) =
        EquityPoint(label, mv?.let(::d), inv?.let(::d), b.map { GrowthBroker(it.first, d(it.second), d(it.third)) })
    private val labels = listOf("A", "B")
    private val points = listOf(
        pt("Jan", "300", "250", Triple("A", "100", "80"), Triple("B", "200", "170")),
        pt("Feb", "330", "260", Triple("A", "130", "90"), Triple("B", "200", "170")),
    )
    @Test fun allBrokersSelectedUsesTheAggregateFields() {
        val s = EquityTrend.series(points, setOf("A", "B"), labels, totals = true)
        assertEquals(listOf("MV", "Book", "A · mv", "A · book", "B · mv", "B · book"), s.lines.map { it.name })
        assertEquals(listOf(d("300"), d("330")), s.lines[0].values); assertEquals(d("30"), s.deltaMv)
    }
    @Test fun partialSelectionSumsTheSelectedBrokersAndSaysSo() {
        val s = EquityTrend.series(points, setOf("A"), labels, totals = true)
        assertEquals(listOf("MV (selected)", "Book (selected)", "A · mv", "A · book"), s.lines.map { it.name })
        assertEquals(listOf(d("100"), d("130")), s.lines[0].values); assertEquals(listOf(d("80"), d("90")), s.lines[1].values); assertEquals(d("30"), s.deltaMv)
    }
    @Test fun bookLinesAreDashedAndTotalsCanBeHidden() {
        val s = EquityTrend.series(points, setOf("A", "B"), labels, totals = false)
        assertTrue(s.lines.none { it.isTotal }); assertTrue(s.lines.filter { it.name.endsWith("book") }.all { it.dashed }); assertTrue(s.lines.filter { it.name.endsWith("mv") }.none { it.dashed })
        assertEquals(d("30"), s.deltaMv)
    }
    @Test fun emptySelectionFallsBackToEveryBroker() = assertEquals(6, EquityTrend.series(points, emptySet(), labels, true).lines.size)
    @Test fun missingBrokerSliceGivesAGapNotAZero() {
        val s = EquityTrend.series(listOf(pt("Jan", "10", "9", Triple("A", "10", "9")), pt("Feb", "20", "18", Triple("A", "20", "18"), Triple("B", "5", "4"))), setOf("A", "B"), labels, false)
        assertNull(s.lines.first { it.name == "B · mv" }.values[0]); assertEquals(d("5"), s.lines.first { it.name == "B · mv" }.values[1])
    }
    @Test fun partialDetection() { assertTrue(EquityTrend.isPartial(setOf("A"), labels)); assertFalse(EquityTrend.isPartial(setOf("A", "B"), labels)); assertFalse(EquityTrend.isPartial(emptySet(), labels)) }
    @Test fun noPointsNoDelta() = assertNull(EquityTrend.series(emptyList(), setOf("A"), labels, true).deltaMv)

    // ======================= dividend stacks =======================
    @Test fun stacksKeepActualAndEstimateApartPerTicker() {
        val s = DividendStacks.build(AppJson.decodeFromString(Dividends.serializer(), fixture("dividends.json")).series)
        assertEquals(listOf("AAA", "BBB"), s.tickers)
        val oct = s.bars[2]
        assertEquals(listOf(d("20.00"), d("7.67")), oct.actual); assertEquals(listOf(d("5.16"), BigDecimal.ZERO), oct.forecast)
        assertTrue(s.bars.last().forecastZone); assertFalse(oct.forecastZone)
    }
    @Test fun averageIsTheMeanOfBarsOutsideTheForecastZone() = assertEquals(d("60.94"), DividendStacks.build(AppJson.decodeFromString(Dividends.serializer(), fixture("dividends.json")).series).average)
    @Test fun noHistoryMeansNoAverage() = assertNull(DividendStacks.build(listOf(DividendPoint("Next", "forecast_forward", d("5"), null, true, emptyList()))).average)
    @Test fun smallerPayersCollapseIntoOther() {
        val layers = (1..8).map { DividendLayer("T$it", "actual", BigDecimal(it)) }
        val s = DividendStacks.build(listOf(DividendPoint("Jan", "historical", d("36"), null, false, layers)))
        assertEquals(listOf("T8", "T7", "T6", "T5", "T4", "Other"), s.tickers)
        assertEquals(d("6"), s.bars[0].actual.last())                      // T1 + T2 + T3
        assertEquals(d("36"), s.bars[0].actual.fold(BigDecimal.ZERO, BigDecimal::add))
    }
    @Test fun nullAmountsCountAsZero() = assertEquals(BigDecimal.ZERO, DividendStacks.build(listOf(DividendPoint("Jan", "historical", null, null, false, listOf(DividendLayer("A", "actual", null))))).bars[0].total)
}
