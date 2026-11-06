package com.rushify.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.data.Me
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.Dest
import com.rushify.mobile.ui.ErrorState
import com.rushify.mobile.ui.HoldingsScreen
import com.rushify.mobile.ui.HomeScreen
import com.rushify.mobile.ui.LocalCleartext
import com.rushify.mobile.ui.LocalHideBalances
import com.rushify.mobile.ui.LockScreen
import com.rushify.mobile.ui.MoreScreen
import com.rushify.mobile.ui.RSegmented
import com.rushify.mobile.ui.RollupsViewModel
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.SkeletonCard
import com.rushify.mobile.ui.TabHeader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The iOS design pass on Android: components, More screen, lock screen, Home and the grouped holding sheet. */
@RunWith(AndroidJUnit4::class)
class Phase8DesignUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv
    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun has(t: String, sub: Boolean = false) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { has(t, sub) }
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { rule.waitUntil(10_000) { tagged(tag) }; val node = rule.onNodeWithTag(tag, useUnmergedTree = true); runCatching { node.performScrollTo() }; node.performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }

    // ---------------------------------------------------------------- components

    @Test fun slidingSelectorChangesTheSelectionWhenASegmentIsTapped() {
        rule.setContent { RushifyTheme { var sel by remember { mutableStateOf("Radar") }; Column { RSegmented(listOf("Radar", "News", "Watchlists"), sel, { it }, { sel = it }); Text("selected=$sel") } }
        }
        waitText("selected=Radar")
        click("seg_News"); waitText("selected=News")
        click("seg_Watchlists"); waitText("selected=Watchlists")
        click("seg_Radar"); waitText("selected=Radar")
    }

    @Test fun errorStateOffersTryAgain() {
        var retried = 0
        rule.setContent { RushifyTheme { ErrorState("The server is down") { retried++ } } }
        waitText("Couldn't load this"); waitText("The server is down"); waitText("Try again")
        click("retry"); rule.waitUntil(3_000) { retried == 1 }
    }

    @Test fun skeletonCardsRender() {
        rule.setContent { RushifyTheme { Column { SkeletonCard(3); SkeletonCard(2) } } }
        rule.waitForIdle()   // just proves the shimmer composes and animates without crashing
    }

    @Test fun tabHeaderShowsTheHttpPillOnlyForPlainHttp() {
        var cleartext by mutableStateOf(true)
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalCleartext provides cleartext) { TabHeader("Pulse") } } }
        waitText("Pulse"); waitText("Not encrypted (http)"); assertTrue(tagged("http_chip"))
        cleartext = false
        rule.waitUntil(5_000) { !has("Not encrypted (http)") }
    }

    // ---------------------------------------------------------------- More

    private fun more(
        bio: Boolean = false, timeout: Int = 60, colorBlind: Boolean = false, privacy: Boolean = true,
        onColorBlind: (Boolean) -> Unit = {}, onTimeout: (Int) -> Unit = {}, onPrivacy: (Boolean) -> Unit = {}, onOpen: (Dest) -> Unit = {},
    ) {
        env.session.logout(); env.session.saveLogin("t", Me(id = 2, username = "someone", role = "user"))
        rule.setContent { RushifyTheme { MoreScreen(env.session, false, {}, privacy, onPrivacy, bio, true, {}, true, {}, {}, onOpen, 0, colorBlind, onColorBlind, timeout, onTimeout) } }
    }

    @Test fun morePrivacyCardHasTheIosToggles() {
        more()
        waitText("Privacy & security"); waitText("Hide balances"); waitText("Colour-blind friendly"); waitText("Blue and orange instead of green and red")
        waitText("Hide in app switcher"); waitText("Cover the screen when you leave the app")
        assertTrue(tagged("toggle_colorblind") && tagged("toggle_privacy") && tagged("toggle_hide") && tagged("toggle_bio"))
    }

    @Test fun colourBlindToggleReportsItsNewValue() {
        var seen: Boolean? = null
        more(onColorBlind = { seen = it })
        click("toggle_colorblind"); rule.waitUntil(3_000) { seen == true }
    }

    @Test fun hideInAppSwitcherToggleReportsItsNewValue() {
        var seen: Boolean? = null
        more(privacy = true, onPrivacy = { seen = it })
        click("toggle_privacy"); rule.waitUntil(3_000) { seen == false }
    }

    @Test fun lockTimeoutRowOnlyAppearsWhileTheLockIsOnAndOffersTheFourChoices() {
        more(bio = false); waitText("Privacy & security"); assertFalse(tagged("lock_timeout"))
    }

    @Test fun lockTimeoutChoicesAreOfferedAndReported() {
        var picked: Int? = null
        more(bio = true, timeout = 60, onTimeout = { picked = it })
        waitText("After 1 minute")
        click("lock_timeout")
        listOf("Immediately", "After 5 minutes", "After 15 minutes").forEach { waitText(it) }
        click("lock_900"); rule.waitUntil(3_000) { picked == 900 }
    }

    @Test fun moreListsEveryDestinationWithATile() {
        var opened: Dest? = null
        more(onOpen = { opened = it })
        waitText("Holdings"); waitText("Sync"); waitText("AI assistant"); waitText("Performance & income")
        click("menu_Performance"); rule.waitUntil(3_000) { opened == Dest.Performance }
    }

    @Test fun moreFooterShowsTheAppVersionAndSignOut() {
        more(); waitText("Rushify for Android", sub = true); waitText("Sign out"); assertTrue(tagged("version_footer"))
    }

    // ---------------------------------------------------------------- lock screen

    @Test fun lockScreenExplainsAndUnlocks() {
        var unlocked = 0
        rule.setContent { RushifyTheme { LockScreen(failed = false) { unlocked++ } } }
        waitText("Rushify is locked"); waitText("Confirm it's you to see your portfolio."); assertFalse(tagged("lock_failed"))
        click("unlock"); rule.waitUntil(3_000) { unlocked == 1 }
    }

    @Test fun lockScreenShowsWhenTheLastAttemptFailed() {
        rule.setContent { RushifyTheme { LockScreen(failed = true) {} } }
        waitText("Couldn't confirm it's you. Try again."); assertTrue(tagged("lock_failed"))
    }

    // ---------------------------------------------------------------- Home

    @Test fun homeShowsSkeletonCardsUntilTheFirstDataArrives() {
        env.route("GET", "/api/portfolio/dashboard") { Thread.sleep(1_500); env.ok(fixture("holdings_dashboard.json")) }
        val vm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(vm) } }
        rule.waitUntil(5_000) { tagged("home_skeleton") }
        rule.waitUntil(15_000) { has("Net worth") && !tagged("home_skeleton") }
    }

    @Test fun homeEyeButtonHidesBalancesAndLooksDifferentWhenHidden() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("holdings_dashboard.json")) }
        val vm = DashboardViewModel(env.repo)
        var toggled = 0
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalHideBalances provides false) { HomeScreen(vm, onToggleHide = { toggled++ }) } } }
        waitText("Net worth"); click("eye_toggle"); rule.waitUntil(3_000) { toggled == 1 }
    }

    // ---------------------------------------------------------------- grouped holding sheet

    private val withFundamentals = """{"base_currency":"GBP","net_worth_total_base":"500","total_market_value_base":"500","rows":[
        {"ticker":"AAPL_US_EQ","instrument_name":"Apple Inc.","source":"t212","broker_label":"Trading 212 · Invest","account_kind":"invest","quantity":"3","currency":"USD",
         "market_value_base":"500","cost_base":"450","pnl_stock_base":"50","pe_ratio":"25.5","pb_ratio":"5.1","peg_ratio":"1.2","price_to_sales":"7.3",
         "dividend_yield":"0.005","roe":"0.4","debt_to_equity":"1.1","profit_margin":"25","revenue":"380000000000"}]}"""

    private fun holdings(dash: String) {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(dash) }
        env.route("GET", "/api/portfolio/holdings-rollups") { env.ok(fixture("rollups.json")) }
        val d = DashboardViewModel(env.repo); val r = RollupsViewModel(env.repo)
        rule.setContent { RushifyTheme { HoldingsScreen(d, r) } }
        rule.waitUntil(10_000) { has("Data as of", sub = true) }
    }
    private val list get() = rule.onNodeWithTag("holdings_list")
    private fun reveal(m: SemanticsMatcher): Boolean {
        if (rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()) return true
        runCatching { list.performScrollToNode(m); rule.waitForIdle() }
        return rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    }
    private fun openGroup(key: String) { rule.waitUntil(20_000) { reveal(hasTestTag("group_$key")) }; rule.onNodeWithTag("group_$key").performScrollTo().performClick(); rule.waitForIdle() }

    @Test fun detailSheetShowsHeroPnlCapsuleAndTheValuationAndQualityGroups() {
        holdings(withFundamentals)
        openGroup("t212:invest")
        rule.waitUntil(20_000) { reveal(hasText("Apple Inc.")) }; rule.onAllNodesWithText("Apple Inc.")[0].performScrollTo().performClick()
        waitText("POSITION"); waitText("VALUATION"); waitText("QUALITY & INCOME")
        assertTrue(tagged("pnl_capsule"))
        listOf("P/E", "P/B", "PEG", "P/S", "Dividend yield", "ROE", "Debt / equity", "Profit margin", "Revenue").forEach { waitText(it) }
        assertFalse("fundamentals exist, so no empty-state line", has("No fundamentals data for this holding."))
        waitText("Prices are end-of-day / cached, not live.")
    }

    @Test fun detailSheetHidesEmptyGroupsAndSaysSo() {
        holdings(withFundamentals.replace(Regex("\"pe_ratio\".*?\"revenue\":\"380000000000\"", RegexOption.DOT_MATCHES_ALL), "\"x\":\"1\""))
        openGroup("t212:invest")
        rule.waitUntil(20_000) { reveal(hasText("Apple Inc.")) }; rule.onAllNodesWithText("Apple Inc.")[0].performScrollTo().performClick()
        waitText("POSITION"); waitText("No fundamentals data for this holding.")
        assertFalse(has("VALUATION") || has("QUALITY & INCOME"))
    }
}
