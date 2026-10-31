package com.rushify.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.AiScreen
import com.rushify.mobile.ui.AiViewModel
import com.rushify.mobile.ui.BenchmarksViewModel
import com.rushify.mobile.ui.CorporateScreen
import com.rushify.mobile.ui.CorporateViewModel
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.DividendsViewModel
import com.rushify.mobile.ui.GrowthViewModel
import com.rushify.mobile.ui.LocalHideBalances
import com.rushify.mobile.ui.MonzoViewModel
import com.rushify.mobile.ui.PerformanceScreen
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.TickerMapsViewModel
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewScreensUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun waitText(text: String, substring: Boolean = false) =
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun performanceRoutes() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        env.route("GET", "/api/portfolio/benchmarks") { env.ok(fixture("benchmarks.json")) }
        env.route("GET", "/api/portfolio/dividend-dashboard") { env.ok(fixture("dividends.json")) }
    }

    private fun showPerformance(onBack: () -> Unit = {}, hidden: Boolean = false) {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("holdings_dashboard.json")) }
        val g = GrowthViewModel(env.repo); val b = BenchmarksViewModel(env.repo); val d = DividendsViewModel(env.repo)
        val dash = com.rushify.mobile.ui.DashboardViewModel(env.repo); val eq = com.rushify.mobile.ui.EquityTrendViewModel(env.repo)
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalHideBalances provides hidden) { PerformanceScreen(g, b, d, dash, eq, onBack) } } }
    }

    // ---- Performance ----

    @Test fun growthShowsLatestPeriodAndAttributionNumbers() {
        performanceRoutes(); showPerformance()
        waitText("Growth attribution")
        waitText("+£500.00"); waitText("+£376.48"); waitText("+£830.19")      // Oct 2026 price, interaction and total
        rule.onNodeWithTag("growth_selected").assertExists()
    }

    @Test fun tappingABarSelectsThatPeriod() {
        performanceRoutes(); showPerformance()
        waitText("Growth attribution")
        rule.onNodeWithContentDescription("Growth attribution chart").performTouchInput { click(Offset(width * 0.12f, height / 2f)) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Aug 2026").fetchSemanticsNodes().isNotEmpty() }
        waitText("+£85.00")                                   // Aug total growth
    }

    @Test fun benchmarksTabShowsReturnsAndPnlSplit() {
        performanceRoutes(); showPerformance()
        rule.onNodeWithText("Benchmarks").performClick()
        waitText("Return vs FTSE 100 and S&P 100")
        waitText("+7.33%"); waitText("+2.95%"); waitText("+3.90%")
        waitText("Where the period P/L came from")
        waitText("+£849.00")
    }

    @Test fun dividendsTabShowsTotalsEstimateAndTopPayers() {
        performanceRoutes(); showPerformance()
        rule.onNodeWithText("Dividends").performClick()
        waitText("Cash dividends")
        waitText("Oct 2026 (so far)")
        waitText("£182.83"); waitText("£436.10")              // received (shown) and forward estimate
        waitText("AAA")                                       // top contributor row for the selected period
    }

    @Test fun hideBalancesHidesChartsAndAmounts() {
        performanceRoutes(); showPerformance(hidden = true)
        waitText("Charts are hidden while balances are hidden.")
        assert(rule.onAllNodesWithText("+£830.19").fetchSemanticsNodes().isEmpty())
    }

    @Test fun serverErrorShowsRetryInsteadOfBlankScreen() {
        env.route("GET", "/api/portfolio/growth") { env.ok("down", 503) }
        showPerformance()
        waitText("Retry")
    }

    @Test fun backChevronCallsOnBack() {
        performanceRoutes()
        var backed = false
        showPerformance(onBack = { backed = true })
        rule.onNodeWithTag("back").performClick()
        rule.waitUntil(3_000) { backed }
    }

    // ---- Monzo ----

    private fun showMonzoCard() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        val m = MonzoViewModel(env.repo)
        rule.setContent { RushifyTheme { androidx.compose.foundation.layout.Column { com.rushify.mobile.ui.MonzoSyncCard(m) {} } } }
        m.load()
    }

    @Test fun monzoSyncReportsSuccessEvenWithEmptyBody() {
        env.route("POST", "/api/monzo/sync") { MockResponse().setResponseCode(204) }
        showMonzoCard()
        waitText("Connected")
        rule.onNodeWithTag("monzo_sync").performClick()
        waitText("Monzo balances updated.")
        assert(env.count("POST", "/api/monzo/sync") == 1)
    }

    @Test fun monzoSyncFailureIsShown() {
        env.route("POST", "/api/monzo/sync") { env.ok("""{"detail":"Monzo needs reauthorisation"}""", 502) }
        showMonzoCard()
        waitText("Connected")
        rule.onNodeWithTag("monzo_sync").performClick()
        waitText("reauthorisation", substring = true)
    }

    // ---- Corporate actions ----

    private fun showCorporate(actions: String = fixture("corporate.json")) {
        env.route("GET", "/api/corporate-actions") { env.ok(actions) }
        env.route("GET", "/api/portfolio/ticker-maps") { env.ok(fixture("ticker_maps.json")) }
        val c = CorporateViewModel(env.repo); val t = TickerMapsViewModel(env.repo)
        rule.setContent { RushifyTheme { CorporateScreen(c, t, onBack = {}) } }
    }

    @Test fun pendingActionShowsDetailsAndSymbolsToConfirm() {
        showCorporate()
        waitText("Stock split"); waitText("10:1"); waitText("2026-10-20")
        waitText("Symbols to confirm"); waitText("Match in use: AAA.L"); waitText("Match in use: —")
        waitText("Resolved")
    }

    @Test fun confirmAsksFirstThenSendsPatch() {
        env.route("PATCH", "/api/corporate-actions/3") { env.ok("""{"id":3,"status":"confirmed","action_type":"stock_split","ticker":"AAA","details":{},"created_at":"2026-10-17T09:00:00Z"}""") }
        showCorporate()
        waitText("Stock split")
        rule.onNodeWithTag("confirm_3").performClick()
        waitText("updates your portfolio data", substring = true)
        assert(env.count("PATCH", "/api/corporate-actions/3") == 0)    // nothing sent before the dialog is accepted
        rule.onNodeWithTag("confirm_dialog_ok").performClick()
        waitText("Action confirmed.")
        assert(env.count("PATCH", "/api/corporate-actions/3") == 1)
    }

    @Test fun cancellingTheDialogSendsNothing() {
        showCorporate()
        waitText("Stock split")
        rule.onNodeWithTag("dismiss_3").performClick()
        waitText("will be ignored", substring = true)
        rule.onNodeWithText("Cancel").performClick()
        Thread.sleep(300)
        assert(env.count("PATCH", "/api/corporate-actions/3") == 0)
    }

    @Test fun failedResolveShowsError() {
        env.route("PATCH", "/api/corporate-actions/3") { env.ok("""{"detail":"Not found"}""", 404) }
        showCorporate()
        waitText("Stock split")
        rule.onNodeWithTag("dismiss_3").performClick()
        rule.onNodeWithTag("confirm_dialog_ok").performClick()
        waitText("Could not update: Not found")
    }

    @Test fun emptyListShowsNothingToReview() {
        showCorporate("[]")
        waitText("Nothing to review")
    }

    // ---- AI ----

    private fun creds(provider: String = "gemini", gemini: Boolean = true, openai: Boolean = false) =
        """{"gemini_configured":$gemini,"openai_configured":$openai,"ai_provider":"$provider","gemini_api_key":"AIza****"}"""

    private fun showAi(credsJson: String = creds()): AiViewModel {
        env.route("GET", "/api/portfolio/credentials") { env.ok(credsJson) }
        val vm = AiViewModel(env.repo); rule.setContent { RushifyTheme { AiScreen(vm) {} } }; return vm
    }

    @Test fun chatSendsAndRendersMarkdownReplyWithDisclaimer() {
        env.route("POST", "/api/ai/insight") { env.ok(fixture("ai.json")) }
        showAi()
        waitText("not financial advice", substring = true)
        rule.onNodeWithTag("ai_input").performTextInput("Which sectors?")
        rule.onNodeWithTag("ai_send").performClick()
        waitText("Which sectors?")
        waitText("heavily", substring = true)
        waitText("• Tech is 44%", substring = true)
        waitText("Answer used local data only", substring = true)    // suggest online augmentation chip
    }

    @Test fun notConfiguredShowsGuidanceAndDisablesComposer() {
        env.route("POST", "/api/ai/insight") { env.ok("""{"detail":"Gemini API key not configured"}""", 400) }
        showAi()
        rule.onNodeWithTag("ai_input").performTextInput("hello")
        rule.onNodeWithTag("ai_send").performClick()
        waitText("No Gemini API key saved yet.")
        waitText("Settings → Keys", substring = true)
        rule.onNodeWithTag("ai_send").assertIsNotEnabled()
        rule.onNodeWithTag("ai_input").assertIsNotEnabled()
    }

    @Test fun serverErrorKeepsTheDraftSoNothingIsLost() {
        env.route("POST", "/api/ai/insight") { env.ok("""{"detail":"Upstream timeout"}""", 502) }
        showAi()
        rule.onNodeWithTag("ai_input").performTextInput("my long question")
        rule.onNodeWithTag("ai_send").performClick()
        waitText("Upstream timeout")
        waitText("my long question")                                  // restored into the text box
        rule.onNodeWithTag("ai_send").assertIsEnabled()
    }

    @Test fun sendIsDisabledForBlankInput() {
        showAi()
        rule.onNodeWithTag("ai_send").assertIsNotEnabled()
        rule.onNodeWithTag("ai_input").performTextInput("   ")
        rule.onNodeWithTag("ai_send").assertIsNotEnabled()
    }

    // ---- B4: key readiness is checked up front, per provider ----

    @Test fun missingGeminiKeyIsShownBeforeAnySend() {
        showAi(creds(gemini = false))
        waitText("No Gemini API key saved yet.")
        rule.onNodeWithTag("ai_input").assertIsNotEnabled()
        rule.onNodeWithTag("ai_send").assertIsNotEnabled()
        assert(env.count("POST", "/api/ai/insight") == 0)
    }

    @Test fun openAiUserSeesOpenAiWording() {
        showAi(creds(provider = "openai", gemini = true, openai = false))
        waitText("No OpenAI API key saved yet.")
        waitText("OpenAI API key", substring = true)
    }

    @Test fun readyProviderLeavesComposerEnabled() {
        showAi(creds(provider = "openai", gemini = false, openai = true))
        rule.onNodeWithTag("ai_input").performTextInput("hello")
        rule.onNodeWithTag("ai_send").assertIsEnabled()
    }
}
