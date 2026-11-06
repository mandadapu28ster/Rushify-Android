package com.rushify.mobile

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.HoldingsScreen
import com.rushify.mobile.ui.RollupsViewModel
import com.rushify.mobile.ui.RushifyTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 1: the Holdings screen shows what the web shows (account history, filters, closed lines, Monzo sections). */
@RunWith(AndroidJUnit4::class)
class HoldingsParityUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() {
        env = DeviceEnv(); env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("holdings_dashboard.json")) }
        env.route("GET", "/api/portfolio/holdings-rollups") { env.ok(fixture("rollups.json")) }
        val d = DashboardViewModel(env.repo); val r = RollupsViewModel(env.repo)
        rule.setContent { RushifyTheme { HoldingsScreen(d, r) } }
        rule.waitUntil(10_000) { found(hasText("Data as of", substring = true)) && env.count("GET", "/api/portfolio/holdings-rollups") >= 1 }
        Thread.sleep(400); rule.waitForIdle()
    }
    @After fun tearDown() = env.close()

    private val list get() = rule.onNodeWithTag("holdings_list")
    private fun found(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /** The list is lazy: scroll down, then up, until something matching is composed. */
    private val seenTexts = LinkedHashSet<String>()
    private fun snap() { Regex("Text = '\\[(.*?)\\]'").findAll(rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 25)).forEach { seenTexts += it.groupValues[1] } }
    private fun reveal(m: SemanticsMatcher): Boolean {
        if (found(m)) return true
        repeat(40) { list.performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.5f) }; rule.waitForIdle(); snap(); if (found(m)) return true }
        repeat(40) { list.performTouchInput { swipeDown(startY = height * 0.5f, endY = height * 0.75f) }; rule.waitForIdle(); if (found(m)) return true }
        return false
    }
    private fun see(t: String, sub: Boolean = false) {
        try { rule.waitUntil(20_000) { reveal(hasText(t, substring = sub)) } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Text not found: \"$t\". On screen: " + seenTexts.joinToString(" | ").takeLast(3800))
        }
    }
    private fun waitText(t: String, sub: Boolean = false) = see(t, sub)
    private fun gone(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { !found(hasText(t, substring = sub)) }
    private fun open(key: String) { rule.waitUntil(20_000) { reveal(hasTestTag("group_$key")) }; rule.onNodeWithTag("group_$key").performScrollTo().performClick(); rule.waitForIdle() }
    private fun tap(tag: String) { rule.waitUntil(20_000) { reveal(hasTestTag(tag)) }; rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }
    private fun tapText(t: String) { see(t); rule.onAllNodesWithText(t)[0].performScrollTo().performClick(); rule.waitForIdle() }

    @Test fun groupHeaderShowsRealisedPayoutsAndXirr() {
        waitText("Trading 212 · ISA")
        waitText("XIRR: 16.28%"); waitText("XIRR: -13.89%")
        waitText("2 positions · cash £50.00 · total qty 5 · total value £350.00")
    }

    @Test fun accountsSummaryExpandsToPerAccountFiguresAndATotal() {
        waitText("Broker accounts (local history)")
        tap("accounts_summary")
        waitText("Total (all accounts)"); waitText("Trading 212 · Cash ISA (CSV)"); waitText("Total deposits (principal)")
        waitText("Bank balance: no trades or payouts")
    }

    @Test fun exposureFilterOpenReducedKeepsOnlyReducedLines() {
        waitText("Trading 212 · ISA")
        tapText("Open (reduced)")
        waitText("Still holding shares but at least one sell fill is stored.")
        open("t212:isa")
        waitText("VUAG.L"); gone("INFY")
    }

    @Test fun closedFilterShowsClosedInstrumentsInsideTheAccount() {
        waitText("Trading 212 · ISA")
        tapText("Closed (history)")
        waitText("Exited in the broker app", sub = true)
        open("closed:t212:isa")                              // in Closed mode the account block holds only closed lines
        waitText("Closed (order history, no open quantity)"); waitText("OLDCO"); waitText("P/L (FIFO realised)")
        gone("INFY")
    }

    @Test fun closedHistoryIsAlsoShownUnderAllAndForAccountsWithNoOpenLines() {
        waitText("SnapTrade · acc-zzzz…1234")
        open("closed:snaptrade:acc-zzzzzzzzzzzzzz1234")
        waitText("ZZZ"); waitText("Bought (qty)")
    }

    @Test fun onlyOneAccountGroupIsOpenAtATime() {
        open("t212:isa"); waitText("Infosys")
        open("listed__hargreaves"); waitText("MSFT")
        gone("Infosys")                                   // opening a second group closed the first
        open("listed__hargreaves"); gone("MSFT")            // tapping the open group closes it
    }

    @Test fun showSoldTogglePutsSoldLotsBack() {
        waitText("Show sold positions (1 closed manual lot)")
        open("listed__hargreaves")
        waitText("MSFT")
        gone("OLDM")
        tap("show_sold")
        waitText("OLDM"); waitText("Sold")
    }

    @Test fun sameTickerLotsCollapseUnderOneSummaryThatExpands() {
        open("listed__hargreaves")
        waitText("2 lots · total qty 3", sub = true)
        tap("multi_MSFT")
        waitText("Lot · 2025-01-02 · id 11", sub = true)
    }

    @Test fun monzoShowsAccountsSavingsAndPotsWithBadges() {
        open("monzo")
        waitText("Accounts"); waitText("Savings & ISAs"); waitText("Pots"); waitText("Young"); waitText("Savings"); waitText("Bills (Current account)")
        waitText("Bank (synced)".uppercase())
    }

    @Test fun incompleteBasisBannerIsShown() { waitText("manual listed lot is missing a purchase date", sub = true) }

    @Test fun positionDetailShowsDividendsFeesAndExtraRatios() {
        open("st:acc-a")
        waitText("BRK.B")                                   // BRK-B on the dashboard, linked to the BRK.B rollup
        tapText("GOOGL")
        waitText("Broker cash dividends"); waitText("8 payouts"); waitText("Total (GBP): £59.73"); waitText("Broker fees")
        waitText("P/B"); waitText("PEG"); waitText("P/S"); waitText("Revenue")
    }

    @Test fun manualLotWithoutCostBasisSaysSo() {
        open("listed__hargreaves")
        tap("multi_MSFT")
        // lot 12 has no purchase date or GBP cost, lot 11 has both
        see("Lot · no date · id 12", sub = true)
        rule.onAllNodesWithText("Lot · no date · id 12", substring = true)[0].performScrollTo().performClick()
        waitText("Purchase & cost (GBP): not set", sub = true)
    }
}
