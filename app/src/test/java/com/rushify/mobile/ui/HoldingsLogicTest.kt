package com.rushify.mobile.ui

import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.RollupLine
import com.rushify.mobile.data.Rollups
import com.rushify.mobile.fixture
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldingsLogicTest {
    private val rows = AppJson.decodeFromString(Dashboard.serializer(), fixture("holdings_dashboard.json")).rows
    private val rollups = AppJson.decodeFromString(Rollups.serializer(), fixture("rollups.json"))
    private val lines = rollups.lines
    private fun row(ticker: String, acc: String? = null) = rows.first { it.ticker == ticker && (acc == null || it.snaptradeAccountId == acc) }
    private fun d(s: String) = BigDecimal(s)
    private fun groups(ex: ExposureFilter = ExposureFilter.All, q: String = "", showSold: Boolean = false, sort: HoldingSort = HoldingSort.Value) =
        HoldingGroups.build(rows, q, sort, rollups, ex, showSold)

    // ---- tickers ----
    @Test fun stripsVenueSuffixes() {
        assertEquals("INFY", HoldingsLogic.stripT212VenueSuffix("INFY_US_EQ")); assertEquals("VUAG", HoldingsLogic.stripT212VenueSuffix("VUAG_EQ"))
        assertEquals("VOD", HoldingsLogic.stripT212VenueSuffix("VOD_L_EQ")); assertEquals("ABC", HoldingsLogic.stripT212VenueSuffix("ABC_WBAG_EQ"))
        assertEquals("GOOGL", HoldingsLogic.stripT212VenueSuffix("GOOGL")); assertEquals("", HoldingsLogic.stripT212VenueSuffix(null))
    }
    @Test fun displayTickerUsesRollupElseStrips() {
        assertEquals("INFY", HoldingsLogic.displayTicker(row("INFY_US_EQ"), null))
        assertEquals("VUAG.L", HoldingsLogic.displayTicker(row("VUAG_EQ"), lines.first { it.displayTicker == "VUAG.L" }))
        assertEquals("GOOGL", HoldingsLogic.displayTicker(row("GOOGL", "acc-a"), null))
        assertEquals("MSFT", HoldingsLogic.displayTicker(row("MSFT"), null))
    }
    @Test fun classTickersMatchAcrossDashAndDot() = assertTrue(HoldingsLogic.rowTickerMatchesRollup("BRK-B", lines.first { it.brokerTicker == "BRK.B" }))
    @Test fun unrelatedTickersDoNotMatch() = assertFalse(HoldingsLogic.rowTickerMatchesRollup("AAPL_US_EQ", lines.first { it.displayTicker == "INFY" }))

    // ---- matching ----
    @Test fun rowsMatchTheirOwnAccountOnly() {
        assertEquals("snaptrade:acc-a", HoldingsLogic.findRollupForRow(row("GOOGL", "acc-a"), lines)!!.accountRef)
        assertEquals("snaptrade:acc-b", HoldingsLogic.findRollupForRow(row("GOOGL", "acc-b"), lines)!!.accountRef)
        assertEquals("t212:invest", HoldingsLogic.findRollupForRow(row("AAPL_US_EQ"), lines)!!.accountRef)
        assertEquals("t212:isa", HoldingsLogic.findRollupForRow(row("INFY_US_EQ"), lines)!!.accountRef)
    }
    @Test fun manualLotsMatchById() {
        assertEquals("manual:11", HoldingsLogic.findRollupForRow(rows.first { it.manualHoldingId == 11 }, lines)!!.accountRef)
        assertNull(HoldingsLogic.findRollupForRow(rows.first { it.manualHoldingId == 12 }, lines))
    }
    @Test fun cashAndNonBrokerRowsNeverMatch() {
        assertNull(HoldingsLogic.findRollupForRow(row("CASH.t212.1"), lines)); assertNull(HoldingsLogic.findRollupForRow(row("MZ1"), lines))
    }
    @Test fun bestRankedRollupWinsWhenSeveralMatch() {
        val extra = RollupLine(source = "t212", accountRef = "t212:isa", brokerTicker = "INFY_X_EQ", displayTicker = "INFY", exposure = "open_full", fillRowCount = 99)
        assertEquals("INFY_US_EQ", HoldingsLogic.findRollupForRow(row("INFY_US_EQ"), lines + extra)!!.brokerTicker)   // exact broker ticker beats more fills
    }
    @Test fun exposureOfTheMatchedRollupIsReported() {
        assertEquals("open_partial", HoldingsLogic.findRollupForRow(row("VUAG_EQ"), lines)!!.exposure)
        assertNull(HoldingsLogic.findRollupForRow(row("VUAG_EQ"), lines, ExposureFilter.OpenFull))
    }

    // ---- exposure filter ----
    @Test fun allKeepsEverything() = assertEquals(rows.size, HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.All, false).size)
    @Test fun openNoSellsKeepsOnlyFullyOpenBrokerLinesAndHidesCash() {
        val kept = HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.OpenFull, false).map { it.ticker }
        assertTrue(kept.containsAll(listOf("INFY_US_EQ", "AAPL_US_EQ", "GOOGL", "BRK-B")))
        assertFalse(kept.contains("VUAG_EQ")); assertFalse(kept.any { it.startsWith("CASH.") })
        assertTrue(kept.contains("MZ3") || kept.contains("NWRE.1"))      // non broker rows stay (Monzo cash is hidden, property stays)
    }
    @Test fun openReducedKeepsOnlyReducedBrokerLines() {
        val broker = HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.OpenPartial, false).filter { HoldingsLogic.isBrokerListedEquityRow(it) }.map { it.ticker }
        assertEquals(listOf("VUAG_EQ"), broker)
    }
    @Test fun closedModeHidesBrokerListedCashAndOpenManualLots() {
        val kept = HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.Closed, false)
        assertTrue(kept.none { HoldingsLogic.isBrokerListedEquityRow(it) || HoldingsLogic.isBrokerCash(it) })
        assertTrue(kept.none { it.manualHoldingId in listOf(11, 12, 13) })
        assertTrue(kept.any { it.ticker == "NWRE.1" })
    }
    @Test fun soldManualLotsOnlyShowWhenAsked() {
        assertTrue(HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.Closed, false).none { it.manualHoldingId == 13 })
        assertTrue(HoldingsLogic.applyExposureFilter(rows, lines, ExposureFilter.Closed, true).any { it.manualHoldingId == 13 })
    }
    @Test fun soldRule() {
        assertTrue(HoldingsLogic.isManualListedSold(rows.first { it.manualHoldingId == 13 }))
        assertFalse(HoldingsLogic.isManualListedSold(rows.first { it.manualHoldingId == 11 }))
        assertTrue(HoldingsLogic.isManualListedSold(HoldingRow(ticker = "X", source = "manual", quantity = BigDecimal.ZERO)))
        assertFalse(HoldingsLogic.isManualListedSold(HoldingRow(ticker = "X", source = "t212", soldOutAt = "2026-01-01")))
    }

    // ---- groups: order, titles, metrics ----
    @Test fun groupOrderMatchesTheWeb() {
        val names = groups().map { it.name }
        assertEquals(listOf("Trading 212 · Invest", "Trading 212 · ISA"), names.take(2))
        assertTrue(names.indexOf("AJ Bell · LISA · SnapTrade") in 2..4)
        val kinds = groups().map { it.kind }
        assertEquals(kinds.sortedBy { listOf(GroupKind.T212, GroupKind.SNAPTRADE, GroupKind.CSV, GroupKind.MONZO, GroupKind.MANUAL_LISTED, GroupKind.MANUAL_PROPERTY, GroupKind.MANUAL_CASH, GroupKind.MANUAL_OTHER, GroupKind.OTHER).indexOf(it) }, kinds)
    }
    @Test fun sameLabelAccountsStaySeparate() = assertEquals(2, groups().count { it.name == "AJ Bell · LISA · SnapTrade" && it.rows.isNotEmpty() })
    @Test fun accountMetricsAreAttachedByRef() {
        val isa = groups().first { it.accountRef == "t212:isa" }
        assertEquals(d("-644.93"), isa.account!!.realisedGainBaseSum); assertEquals(d("0.1628"), isa.account!!.xirrAnnual)
        assertEquals("snaptrade:acc-b", groups().first { it.accountRef == "snaptrade:acc-b" }.account!!.accountRef)
    }
    @Test fun brokerGroupCountsExcludeCashButShowIt() {
        val isa = groups().first { it.accountRef == "t212:isa" }
        assertEquals(2, isa.positionCount); assertEquals(d("50"), isa.cashValue); assertEquals(d("350"), isa.totalValue); assertEquals(d("5"), isa.totalPnl)
        assertEquals(d("5"), isa.totalQty)
    }
    @Test fun aGroupWithNoListedRowsShowsZeroUnrealisedNotADash() {
        assertEquals(0, BigDecimal.ZERO.compareTo(groups().first { it.accountRef == "snaptrade:acc-zzzzzzzzzzzzzz1234" }.totalPnl))
    }
    @Test fun manualGroupsHaveWebTitlesAndBadges() {
        val g = groups().filter { it.kind.name.startsWith("MANUAL") }
        assertEquals(listOf("Hargreaves" to "Manual listed", "Property (manual)" to "Property", "Zopa Bank · Cash ISA" to "Cash savings", "Other assets (manual)" to "Other assets"), g.map { it.name to it.badge })
    }
    @Test fun monzoIsOneGroupWithABadge() { val m = groups().single { it.kind == GroupKind.MONZO }; assertEquals("Monzo", m.name); assertEquals("Bank (synced)", m.badge); assertEquals(4, m.rows.size) }
    @Test fun soldLotsAppearOnlyWithTheToggle() {
        assertTrue(groups().first { it.kind == GroupKind.MANUAL_LISTED }.rows.none { it.manualHoldingId == 13 })
        assertTrue(groups(showSold = true).first { it.kind == GroupKind.MANUAL_LISTED }.rows.any { it.manualHoldingId == 13 })
    }

    // ---- closed instruments ----
    @Test fun closedLinesAttachToTheirAccountAndAreDeduped() {
        val closed = groups().first { it.accountRef == "t212:isa" }.closed
        assertEquals(1, closed.size); assertEquals(5, closed[0].fillRowCount)               // two OLDCO lines collapse, the one with more fills wins
    }
    @Test fun accountWithOnlyClosedHistoryStillGetsABlock() {
        val g = groups().first { it.accountRef == "snaptrade:acc-zzzzzzzzzzzzzz1234" }
        assertTrue(g.rows.isEmpty()); assertEquals(1, g.closed.size); assertEquals("SnapTrade · acc-zzzz…1234", g.name)
    }
    @Test fun closedCardsHiddenInOpenFilters() {
        assertTrue(groups(ExposureFilter.OpenFull).all { it.closed.isEmpty() }); assertTrue(groups(ExposureFilter.OpenPartial).all { it.closed.isEmpty() })
        assertTrue(groups(ExposureFilter.All).any { it.closed.isNotEmpty() })
    }
    @Test fun closedModeShowsClosedInstrumentsAndNoOpenBrokerRows() {
        val g = groups(ExposureFilter.Closed)
        assertTrue(g.filter { it.brokerLike }.all { it.rows.isEmpty() }); assertEquals(2, g.count { it.closed.isNotEmpty() })
    }
    @Test fun searchAlsoFiltersClosedInstruments() {
        assertEquals(1, groups(q = "oldco").flatMap { it.closed }.size); assertTrue(groups(q = "zzzz-nothing").isEmpty())
    }

    // ---- items / primary metrics ----
    @Test fun sameTickerLinesCollapseUnderOneSummary() {
        val extra = row("INFY_US_EQ").copy(quantity = d("1"), marketValueBase = d("10"))
        val g = HoldingGroups.build(rows + extra, "", HoldingSort.Value, rollups).first { it.accountRef == "t212:isa" }
        val items = HoldingGroups.items(g, rollups, ExposureFilter.All)
        val multi = items.filterIsInstance<HoldingItem.Multi>().single()
        assertEquals("INFY", multi.ticker); assertEquals(2, multi.rows.size)
        assertTrue(items.filterIsInstance<HoldingItem.Single>().any { it.row.assetKind == "cash" })   // cash stays a single line
    }
    @Test fun manualLotsOfOneTickerGroupToo() {
        val g = groups().first { it.kind == GroupKind.MANUAL_LISTED }
        assertEquals(2, (HoldingGroups.items(g, rollups, ExposureFilter.All).single() as HoldingItem.Multi).rows.size)
    }
    @Test fun nonListedGroupsAreNeverNested() = assertTrue(groups().filter { it.kind == GroupKind.MONZO || it.kind == GroupKind.MANUAL_CASH }.all { g -> HoldingGroups.items(g, rollups, ExposureFilter.All).all { it is HoldingItem.Single } })
    @Test fun firstLineOfASplitPositionCarriesTheFees() {
        val extra = row("INFY_US_EQ").copy(quantity = d("1"), marketValueBase = d("10"), currency = "EUR")
        val ordered = listOf(row("INFY_US_EQ"), extra)
        val primary = HoldingGroups.primaryMetricRows(ordered, lines, ExposureFilter.All)
        assertEquals(1, primary.size); assertEquals(HoldingsLogic.rowSlotId(ordered[0]), primary.values.single())
        assertNotEquals(HoldingsLogic.rowSlotId(ordered[0]), HoldingsLogic.rowSlotId(ordered[1]))
    }
    private fun assertNotEquals(a: Any, b: Any) = assertFalse(a == b)

    // ---- account summaries ----
    @Test fun summariesAreOrderedCsvMonzoSnaptradeT212() = assertEquals(listOf("csv", "monzo", "snaptrade", "snaptrade", "t212", "t212"), AccountSummaries.build(rollups, rows).map { it.source })
    @Test fun monzoAppearsOnceEvenThoughServerAndDashboardBothKnowAboutIt() {
        val monzo = AccountSummaries.build(rollups, rows).filter { it.source == "monzo" }
        assertEquals(1, monzo.size); assertEquals("Monzo · 4 balances", monzo[0].label); assertEquals(d("1385.04"), monzo[0].monzoTotal)
    }
    @Test fun monzoLineIsBuiltFromDashboardRowsWhenServerSendsNone() {
        val noMonzo = Rollups(rollups.lines, rollups.accounts.filter { it.source != "monzo" })
        val m = AccountSummaries.build(noMonzo, rows).single { it.source == "monzo" }
        assertEquals("Monzo · 4 balances", m.label); assertEquals(d("1385.04"), m.monzoTotal!!.setScale(2))
    }
    @Test fun monzoNeverCountsInTheTotals() {
        val withMonzoNoise = Rollups(rollups.lines, rollups.accounts.map { if (it.source == "monzo") it.copy(realisedGainBaseSum = d("999"), listedInvestmentsMvBaseSum = d("999999")) else it })
        val t = AccountSummaries.totals(AccountSummaries.build(withMonzoNoise, rows))!!
        assertEquals(d("-1608.42"), t.realised.setScale(2)); assertEquals(d("69138.65"), t.investments.setScale(2))
    }
    @Test fun summaryLabelsMatchTheWebFormats() {
        val labels = AccountSummaries.build(rollups, rows).map { it.label }
        assertTrue(labels.contains("Trading 212 · Cash ISA (CSV)")); assertTrue(labels.contains("Trading 212 · ISA")); assertTrue(labels.contains("AJ Bell · LISA · SnapTrade")); assertTrue(labels.contains("Monzo · 4 balances"))
    }
    @Test fun totalsExcludeMonzoAndSumEveryColumn() {
        val t = AccountSummaries.totals(AccountSummaries.build(rollups, rows))!!
        assertEquals(d("-1608.42"), t.realised.setScale(2)); assertEquals(d("18829.33"), t.unrealised.setScale(2)); assertEquals(d("69138.65"), t.investments.setScale(2))
        assertEquals(d("1924.90"), t.payout.setScale(2))
    }
    @Test fun noAccountsMeansNoTotalsRow() = assertNull(AccountSummaries.totals(AccountSummaries.build(Rollups(), rows.filter { it.source == "monzo" })))
    @Test fun accountLabelsFromRefs() {
        assertEquals("Trading 212 · INVEST", HoldingsLogic.formatRollupAccountLabel("t212:invest"))
        assertEquals("Lightyear · Invest (CSV)", HoldingsLogic.formatRollupAccountLabel("csv:lightyear:invest"))
        assertEquals("Freetrade Plus · SIPP (CSV)", HoldingsLogic.formatRollupAccountLabel("csv:freetrade-plus:sipp"))
        assertEquals("SnapTrade · 1234", HoldingsLogic.formatRollupAccountLabel("snaptrade:1234"))
    }

    // ---- Monzo ----
    @Test fun monzoSectionsAreOrderedAndSortedByValue() {
        val s = HoldingsLogic.monzoSections(rows.filter { it.source == "monzo" })
        assertEquals(listOf("Accounts", "Savings & ISAs", "Pots"), s.map { it.first })
        assertEquals(listOf("Monzo · Current account", "Monzo · Young account"), s[0].second.map { it.instrumentName })   // 181.04 before 104.00
        assertEquals("Savings", HoldingsLogic.monzoKindBadge("savings")); assertEquals("Pot", HoldingsLogic.monzoKindBadge("pot")); assertNull(HoldingsLogic.monzoKindBadge("current"))
        assertEquals("Bills (Current account)", HoldingsLogic.monzoRowName(rows.first { it.ticker == "MZ4" }))
    }

    // ---- misc ----
    @Test fun abbreviationsLikeTheWeb() {
        assertEquals("1.23T", Fmt.abbreviate(d("1234000000000"))); assertEquals("4.50B", Fmt.abbreviate(d("4500000000"))); assertEquals("12.30M", Fmt.abbreviate(d("12300000")))
        assertEquals("950", Fmt.abbreviate(d("950"))); assertEquals("—", Fmt.abbreviate(null))
    }
    @Test fun rollupExposureLabels() { assertEquals("Open (synced)", HoldingsLogic.rollupExposureLabel("open_full")); assertEquals("Open (reduced)", HoldingsLogic.rollupExposureLabel("open_partial")); assertEquals("Closed (history)", HoldingsLogic.rollupExposureLabel("closed_history")) }
    @Test fun exposureHintsMatchTheWeb() = assertTrue(ExposureFilter.entries.all { it.hint.isNotBlank() } && ExposureFilter.entries.map { it.label } == listOf("All", "Open (no sells)", "Open (reduced)", "Closed (history)"))
}
