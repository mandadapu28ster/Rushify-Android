package com.rushify.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.BenchmarksViewModel
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.DividendsViewModel
import com.rushify.mobile.ui.EquityTrendViewModel
import com.rushify.mobile.ui.GrowthViewModel
import com.rushify.mobile.ui.HomeScreen
import com.rushify.mobile.ui.LocalHideBalances
import com.rushify.mobile.ui.PerformanceScreen
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.WealthViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 3: Home sections and the Performance charts show the web's numbers. */
@RunWith(AndroidJUnit4::class)
class Phase3UiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun has(t: String, sub: Boolean = false) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { has(t, sub) }
    private fun gone(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { !has(t, sub) }
    private fun tagged(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    /** Opens a Home section (collapsed by default, like the web on a phone). */
    private fun expand(id: String) { rule.waitUntil(10_000) { tagged("section_toggle_$id") }; rule.onNodeWithTag("section_toggle_$id").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitUntil(10_000) { tagged("section_body_$id") } }

    // ---------------------------------------------------------------- Home

    private fun home(dash: String = fixture("holdings_dashboard.json"), wealthJson: String? = null, hidden: Boolean = false) {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(dash) }
        wealthJson?.let { w -> env.route("GET", "/api/portfolio/manual-wealth-series") { env.ok(w) } }
        val vm = DashboardViewModel(env.repo); val wealth = WealthViewModel(env.repo)
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalHideBalances provides hidden) { HomeScreen(vm, wealth = wealth) } } }
    }

    @Test fun sectorsDrillIntoHoldingsAndBack() {
        home(); expand("pies"); waitText("Portfolio by sector")
        rule.onNodeWithTag("sector_Unknown").performScrollTo().performClick()
        waitText("Portfolio / Unknown", sub = true); waitText("INFY"); waitText("MSFT")
        waitText("of sector", sub = true)
        rule.onNodeWithTag("sector_back").performScrollTo().performClick()
        waitText("Tap a sector to see its holdings.", sub = true)
    }

    @Test fun positionSizeListsTickersLargestFirstWithCostAndGain() {
        home(); expand("positions"); waitText("Position size: cost vs gain/loss")
        waitText("GOOGL"); waitText("Cost", sub = true)
        assert(has("Blue = value that is cost", sub = true))
    }

    @Test fun netWorthByBrokerShowsOneRowPerInstitutionAndExpands() {
        home(); expand("custodian"); waitText("Net worth by broker")
        waitText("Property"); waitText("Trading 212"); waitText("AJ Bell"); waitText("Monzo")
        rule.onNodeWithTag("custodian_Trading 212").performScrollTo().performClick()
        waitText("Listed · ISA"); waitText("Cash · ISA"); waitText("Grand total")
    }

    @Test fun nonEquityGainLineAppearsOnlyWhenThereAreLines() {
        home("""{"base_currency":"GBP","net_worth_total_base":"1","rows":[{"ticker":"A","source":"t212"}],"non_equity_manual_gain_total_base":"63757.56","non_equity_manual_basis_lines":6,"non_equity_manual_annualized_pct":"0.11836804"}""")
        waitText("Non-equity manual wealth gain/loss (GBP)"); waitText("+£63,757.56 · 11.84% p.a. blended")
    }
    @Test fun noNonEquityLineWithoutBasisLines() {
        home("""{"base_currency":"GBP","net_worth_total_base":"1","rows":[{"ticker":"A","source":"t212"}],"non_equity_manual_gain_total_base":"5","non_equity_manual_basis_lines":0}""")
        waitText("Net worth"); gone("Non-equity manual wealth gain/loss", sub = true)
    }

    @Test fun investmentsCardExplainsRealisedDividendsAndTheBrokerManualSplit() {
        home("""{"base_currency":"GBP","net_worth_total_base":"10","total_market_value_base":"10","broker_listed_market_value_base":"7","manual_listed_market_value_base":"3","dividend_income_broker_cash_base":"2","rows":[{"ticker":"A","source":"t212"}]}""")
        waitText("Listed value: broker £7.00 · manual £3.00"); waitText("Realised P/L is equity only, excluding dividends", sub = true); waitText("broker payouts including cash interest", sub = true)
    }

    @Test fun assetClassNamesMatchTheWeb() { home(); expand("pies"); waitText("Equities & funds"); waitText("Property (net)") }

    @Test fun freshnessNoteIsShown() { home(); waitText("Data freshness"); expand("freshness"); waitText("12-24 hours", sub = true) }

    @Test fun wealthSectionAppearsOnlyWhenALineQualifiesAndFiltersByKind() {
        home(wealthJson = fixture("wealth.json")); waitText("Wealth over time"); expand("wealth")
        waitText("1 line still building history.")
        rule.onNodeWithTag("wealth_filter_Property").performScrollTo().performClick()
        waitText("Living Home"); gone("Zopa Bank · Cash ISA")
        rule.onNodeWithTag("wealth_filter_Other").performScrollTo().performClick()
        waitText("No lines match this filter.")
    }
    @Test fun wealthSectionHiddenWhenNothingIsEligible() {
        home(wealthJson = """{"base_currency":"GBP","assets":[{"manual_holding_id":1,"label":"Home","asset_kind":"real_estate","show_on_wealth_chart":true,"snapshot_count":2,"chart_eligible":false,"snapshots":[]}]}""")
        waitText("Net worth by broker"); Thread.sleep(500); assert(!has("Wealth over time"))
    }
    @Test fun wealthIsNotEvenRequestedWithoutManualNonListedLines() {
        home("""{"base_currency":"GBP","net_worth_total_base":"1","rows":[{"ticker":"A","source":"t212","market_value_base":"1"}]}""", wealthJson = fixture("wealth.json"))
        waitText("Net worth"); Thread.sleep(600); assert(env.count("GET", "/api/portfolio/manual-wealth-series") == 0)
    }

    // ---------------------------------------------------------------- Home accordion (like the web's HomeCollapsible)

    @Test fun sectionsStartCollapsedShowingTheirSummary() {
        home(); waitText("Net worth by broker")
        assert(!tagged("section_body_custodian") && !tagged("section_body_pies") && !tagged("section_body_positions") && !tagged("section_body_freshness"))
        rule.waitUntil(10_000) { tagged("section_summary_custodian") }
        rule.onNodeWithTag("section_summary_custodian", useUnmergedTree = true).assertTextContains("institutions", substring = true)
        rule.onNodeWithTag("section_summary_positions", useUnmergedTree = true).assertTextContains("bars ordered by market value", substring = true)
    }

    @Test fun openingASectionClosesTheOneThatWasOpen() {
        home(); expand("pies")
        expand("positions")
        rule.waitUntil(10_000) { !tagged("section_body_pies") }
        assert(tagged("section_body_positions") && tagged("section_summary_pies"))
        expand("custodian")
        rule.waitUntil(10_000) { !tagged("section_body_positions") }
        assert(tagged("section_body_custodian"))
    }

    @Test fun tappingTheOpenSectionAgainCollapsesIt() {
        home(); expand("freshness")
        rule.onNodeWithTag("section_toggle_freshness").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitUntil(10_000) { !tagged("section_body_freshness") }
        assert(tagged("section_summary_freshness"))
    }

    @Test fun investmentsOpensAndClosesOnItsOwn() {
        home("""{"base_currency":"GBP","net_worth_total_base":"10","total_market_value_base":"10","total_pnl_base":"2","total_realised_pnl_base":"-1","portfolio_xirr_annual":"0.1","rows":[{"ticker":"A","source":"t212","market_value_base":"10","instrument_name":"A","sector":"Technology"}]}"""); expand("pies")
        assert(tagged("section_body_investments"))
        rule.onNodeWithTag("section_toggle_investments").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitUntil(10_000) { !tagged("section_body_investments") }
        assert(tagged("section_body_pies"))
        rule.onNodeWithTag("section_summary_investments", useUnmergedTree = true).assertTextContains("Listed value: £10.00 · Realised P/L: -£1.00 · Unrealised P/L: +£2.00 · XIRR: +10.00%")
    }

    // ---------------------------------------------------------------- Performance

    private fun performance(hidden: Boolean = false) {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("holdings_dashboard.json")) }
        env.routeP("GET", "/api/portfolio/growth") { path -> env.ok(if (path.contains("equity_series_step=daily")) fixture("growth_daily.json") else fixture("growth.json")) }
        env.route("GET", "/api/portfolio/benchmarks") { env.ok(fixture("benchmarks.json")) }
        env.route("GET", "/api/portfolio/dividend-dashboard") { env.ok(fixture("dividends.json")) }
        val g = GrowthViewModel(env.repo); val b = BenchmarksViewModel(env.repo); val d = DividendsViewModel(env.repo)
        val dash = DashboardViewModel(env.repo); val eq = EquityTrendViewModel(env.repo)
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalHideBalances provides hidden) { PerformanceScreen(g, b, d, dash, eq) {} } } }
    }

    @Test fun growthShowsSummaryCardsWithSharesAndTheInteractionNote() {
        performance(); waitText("Growth attribution")
        waitText("+£654.94")                                   // total change over the three periods
        waitText("45.7% of total"); waitText("57.5% of total"); waitText("-5.8% of total")
        waitText("Note on Interaction:", sub = true)
        waitText("Price (× FX₀)"); waitText("Interaction"); waitText("Income (dividends)"); waitText("Currency (FX)")
    }

    @Test fun equityCardOffersAccountsAndStartsOnTheFirstBrokerLikeTheWeb() {
        performance(); waitText("Listed equity over time")
        waitText("Accounts"); waitText("AJ Bell · LISA"); waitText("Hargreaves"); waitText("Select all")
        waitText("Totals ✓")
    }

    @Test fun switchingToDailyFetchesDailyPointsForTheSelectedBrokers() {
        performance(); waitText("Listed equity over time")
        rule.onNodeWithText("Daily").performScrollTo().performClick()
        rule.waitUntil(10_000) { env.fullPaths.any { it.contains("equity_series_step=daily") && it.contains("compact=true") } }
        val p = env.fullPaths.first { it.contains("equity_series_step=daily") }
        assert(p.contains("equity_brokers=")) { p }
        waitText("Δ MV (first → last)")
    }

    @Test fun selectAllShowsPerBrokerLinesAndTheDeltaIsTheTotalMovement() {
        performance(); waitText("Listed equity over time")
        rule.onNodeWithText("Daily").performScrollTo().performClick()
        rule.onNodeWithText("Select all").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)   // chip sits in a horizontally scrolling row
        waitText("+£200.00")
    }

    @Test fun benchmarksShowPercentagePointGapsAndTheFourMetricCards() {
        performance(); rule.onNodeWithText("Benchmarks").performClick()
        waitText("Portfolio vs the indices"); waitText("+4.38 pp"); waitText("+3.43 pp")
        waitText("Portfolio total return exceeded FTSE 100 over the same dates.")
        waitText("Period total Δ (GBP)"); waitText("Price effect (GBP, est.)"); waitText("Currency / FX effect (GBP, est.)"); waitText("Interaction (est.)")
    }

    @Test fun dividendsShowTickerLegendAverageAndHowToRead() {
        performance(); rule.onNodeWithText("Dividends").performClick()
        waitText("Cash dividends"); waitText("AAA"); waitText("BBB"); waitText("Average £61")
        rule.onNodeWithText("How to read this chart").performScrollTo().performClick()
        waitText("Estimates: Trailing 12-month average", sub = true)
    }

    @Test fun chartsAreHiddenWhenBalancesAreHidden() {
        performance(hidden = true); waitText("Charts are hidden while balances are hidden.")
        assert(!has("+£654.94"))
    }
}
