package com.rushify.mobile

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.data.Me
import com.rushify.mobile.ui.CorporateScreen
import com.rushify.mobile.ui.CorporateViewModel
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.Dest
import com.rushify.mobile.ui.HomeScreen
import com.rushify.mobile.ui.MoreScreen
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.TickerMapsViewModel
import okhttp3.mockwebserver.MockResponse
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase2UiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isEmpty() }

    // ---------------- Symbols to confirm: edit and remove ----------------

    private fun showCorporate(onChanged: () -> Unit = {}) {
        env.route("GET", "/api/corporate-actions") { env.ok("[]") }
        env.route("GET", "/api/portfolio/ticker-maps") { env.ok(fixture("ticker_maps.json")) }
        env.route("PUT", "/api/portfolio/ticker-maps") { env.ok("{}") }
        env.route("DELETE", "/api/portfolio/ticker-maps") { MockResponse().setResponseCode(204) }
        val c = CorporateViewModel(env.repo); val t = TickerMapsViewModel(env.repo)
        rule.setContent { RushifyTheme { CorporateScreen(c, t, onBack = {}, onMapsChanged = onChanged) } }
    }

    @Test fun symbolRowsShowTheCurrentMatchAndAnEditableField() {
        showCorporate()
        waitText("Match in use: AAA.L"); waitText("Match in use: —"); waitText("YOUR MAP")
        rule.onNodeWithTag("map_input_AAA_EQ").assertIsEnabled()
        waitText("Enter the listing your broker uses", sub = true)
    }

    @Test fun savingANewSymbolSendsItAndShowsTheWebMessage() {
        var changed = 0
        showCorporate { changed++ }
        waitText("Match in use: —")
        rule.onNodeWithTag("map_input_ZZZ").performScrollTo().performTextInput("ZZZ.L")
        rule.onNodeWithTag("map_save_ZZZ").performScrollTo().performClick()
        waitText("Latest portfolio tracking will include this shortly", sub = true)
        assert(env.count("PUT", "/api/portfolio/ticker-maps") == 1); assert(changed == 1)
    }

    @Test fun savingAnEmptySymbolShowsTheErrorWithoutCallingTheServer() {
        showCorporate()
        waitText("Match in use: AAA.L")
        rule.onNodeWithTag("map_input_AAA_EQ").performScrollTo().performTextClearance()
        rule.onNodeWithTag("map_save_AAA_EQ").performScrollTo().performClick()
        waitText("Add the listing symbol you want us to use", sub = true)
        assert(env.count("PUT", "/api/portfolio/ticker-maps") == 0)
    }

    @Test fun removingMyMapAsksFirstThenDeletes() {
        showCorporate()
        waitText("YOUR MAP")
        rule.onNodeWithTag("map_remove_AAA_EQ").performScrollTo().performClick()
        waitText("Remove your map?")
        rule.onNodeWithText("Cancel").performClick()
        Thread.sleep(300); assert(env.count("DELETE", "/api/portfolio/ticker-maps") == 0)
        rule.onNodeWithTag("map_remove_AAA_EQ").performScrollTo().performClick()
        rule.onNodeWithTag("map_remove_ok").performClick()
        waitText("Latest portfolio tracking will include this shortly", sub = true)
        assert(env.count("DELETE", "/api/portfolio/ticker-maps") == 1)
    }

    @Test fun serverErrorOnSaveIsShown() {
        showCorporate()
        env.route("PUT", "/api/portfolio/ticker-maps") { env.ok("""{"detail":"Symbol not recognised"}""", 422) }
        waitText("Match in use: —")
        rule.onNodeWithTag("map_input_ZZZ").performScrollTo().performTextInput("NOPE")
        rule.onNodeWithTag("map_save_ZZZ").performScrollTo().performClick()
        waitText("Symbol not recognised")
    }

    // ---------------- Home alerts ----------------

    private fun showHome(dashboardJson: String, onOpen: (Dest) -> Unit = {}, isAdmin: Boolean = false) {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(dashboardJson) }
        env.route("POST", "/api/portfolio/ticker-attention-banner/dismiss") { MockResponse().setResponseCode(204) }
        val vm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(vm, onOpen = onOpen, isAdmin = isAdmin) } }
    }

    private fun openAlerts() { rule.waitUntil(10_000) { rule.onAllNodesWithTag("section_toggle_alerts").fetchSemanticsNodes().isNotEmpty() }; rule.onNodeWithTag("section_toggle_alerts").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle() }

    private val alertsDashboard = """{"base_currency":"GBP","net_worth_total_base":"100","rows":[{"ticker":"A","source":"t212","market_value_base":"1"}],
        "ticker_map_attention_count":2,"manual_basis_incomplete_count":3}"""

    @Test fun homeShowsBothAlertsWithActions() {
        showHome(alertsDashboard)
        waitText("Alerts & reminders"); waitText("3 incomplete lots · 2 tickers to confirm")   // collapsed summary
        openAlerts(); waitText("3 manual listed lots are missing a purchase date", sub = true)
        waitText("Listings need your attention"); waitText("2 unresolved tickers", sub = true)
        waitText("Review symbols"); waitText("Dismiss")
    }

    @Test fun reviewSymbolsOpensCorporateActions() {
        var opened: Dest? = null
        showHome(alertsDashboard, onOpen = { opened = it })
        openAlerts(); waitText("Review symbols")
        rule.onNodeWithTag("review_symbols").performScrollTo().performClick()
        rule.waitUntil(3_000) { opened == Dest.Corporate }
    }

    @Test fun dismissCallsTheServerOnce() {
        showHome(alertsDashboard)
        openAlerts(); waitText("Dismiss")
        rule.onNodeWithTag("dismiss_symbols").performScrollTo().performClick()
        rule.waitUntil(5_000) { env.count("POST", "/api/portfolio/ticker-attention-banner/dismiss") == 1 }
    }

    @Test fun noAlertsMeansNoAlertsCard() {
        showHome("""{"base_currency":"GBP","net_worth_total_base":"100","rows":[{"ticker":"A","source":"t212","market_value_base":"1"}]}""")
        waitText("Net worth"); gone("Alerts & reminders")
    }

    @Test fun singularWordingForOneIncompleteLot() {
        showHome("""{"base_currency":"GBP","net_worth_total_base":"1","rows":[{"ticker":"A","source":"t212"}],"manual_basis_incomplete_count":1}""")
        waitText("1 incomplete lot"); openAlerts(); waitText("1 manual listed lot is missing", sub = true); gone("Listings need your attention")
    }

    // ---------------- Admin reminder ----------------

    @Test fun adminSeesTheWebOnlyReminderOnHome() {
        showHome(alertsDashboard, isAdmin = true)
        waitText("Admin tools are web-only", sub = true); waitText("personal account", sub = true)
    }

    @Test fun regularUserSeesNoAdminReminderOnHome() {
        showHome(alertsDashboard, isAdmin = false)
        waitText("Alerts & reminders"); gone("Admin tools are web-only", sub = true)
    }

    private fun showMore(role: String) {
        env.session.logout(); env.session.saveLogin("t", Me(id = 1, username = "someone", role = role))
        rule.setContent {
            RushifyTheme {
                MoreScreen(env.session, false, {}, false, {}, false, false, {}, true, {}, {}, {}, 0)
            }
        }
    }

    @Test fun adminSeesTheReminderCardAtTheTopOfMore() { showMore("admin"); waitText("Admin account"); waitText("Admin tools are web-only", sub = true) }
    @Test fun regularUserSeesNoReminderCardOnMore() { showMore("user"); waitText("AI assistant"); gone("Admin account") }
}
