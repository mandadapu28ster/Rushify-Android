package com.rushify.mobile

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.AiScreen
import com.rushify.mobile.ui.AiViewModel
import com.rushify.mobile.ui.ChangePasswordScreen
import com.rushify.mobile.ui.ChangePasswordViewModel
import com.rushify.mobile.ui.ConnectionsScreen
import com.rushify.mobile.ui.ConnectionsViewModel
import com.rushify.mobile.ui.NewsViewModel
import com.rushify.mobile.ui.PulseScreen
import com.rushify.mobile.ui.RadarViewModel
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.SyncScreen
import com.rushify.mobile.ui.SyncViewModel
import com.rushify.mobile.ui.WatchlistsViewModel
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 4: Pulse (radar, news, watchlists), Sync states, Connections, Change password, AI. */
@RunWith(AndroidJUnit4::class)
class Phase4UiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun has(t: String, sub: Boolean = false) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { has(t, sub) }
    private fun gone(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { !has(t, sub) }
    private fun tapTag(tag: String) { rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }; rule.onNodeWithTag(tag).performScrollTo().performClick() }

    // ---------------------------------------------------------------- Pulse

    private val newsJson = """{"groups":[
      {"key":"__market__","symbol":null,"label":"Market & macro","headline_count":1,"items":[{"id":1,"source":"yahoo","title":"Markets rally on rate hopes","url":"https://news.example.com/a","publisher":"Reuters","published_at":"2026-10-30T10:00:00Z","summary":"Stocks rose on Friday."}]},
      {"key":"AAA","symbol":"AAA","label":"Alpha Corp","headline_count":2,"items":[{"id":2,"source":"google","title":"Alpha beats estimates","url":"https://news.example.com/b","publisher":"TipRanks","published_at":"2026-10-30T09:00:00Z","summary":"Alpha reported strong earnings for the quarter."},{"id":3,"source":"yahoo","title":"Alpha raises guidance","url":"javascript:alert(1)","publisher":"Investing.com","published_at":"2026-10-29T09:00:00Z"}]}],
      "last_synced_at":"2026-10-31T09:00:00Z","stale":true,"cached_only":true,"symbols":["AAA"]}"""

    private fun pulse(tab: String, news: String = newsJson, lists: String = fixture("watchlists.json")) {
        env.route("GET", "/api/pulse/radar") { env.ok(fixture("radar.json")) }
        env.route("GET", "/api/pulse/news") { env.ok(news) }
        env.route("GET", "/api/pulse/watchlists") { env.ok(lists) }
        val r = RadarViewModel(env.repo); val n = NewsViewModel(env.repo); val w = WatchlistsViewModel(env.repo)
        rule.setContent { RushifyTheme { PulseScreen(r, n, w) } }
        if (tab != "Radar") rule.onNodeWithText(tab).performClick()
    }

    @Test fun radarShowsCoverageBucketsScopesAndDisclaimer() {
        pulse("Radar")
        waitText("MV coverage: 94% scored equities · 1% index/funds · 5% not scored")
        waitText("All listed holdings · 3 holdings"); waitText("Trading 212 · ISA · 2 holdings")
        waitText("2 of 3 positions with enough fundamentals", sub = true)
        waitText("VALUE"); waitText("43")
        tapTag("bucket_strong_buy"); waitText("AAA, BBB")
        waitText("Scores are heuristic quality tilts", sub = true)
    }
    @Test fun radarBucketWithNoHoldingsIsNotExpandable() { pulse("Radar"); waitText("Weak"); tapTag("bucket_sell"); Thread.sleep(300); assert(!has("0% MV · 0 holdings ▸")) }

    @Test fun newsHasCompanyPillsSourcesSummariesAndStatusLines() {
        pulse("News")
        waitText("Portfolio news"); waitText("All (3)"); waitText("Alpha Corp (2)"); waitText("Market & macro (1)")
        waitText("Showing cached headlines only (live fetch disabled).")
        waitText("Updated", sub = true); waitText("tap Refresh for latest", sub = true)
    }
    @Test fun filteringToOneCompanyShowsItsHeadlinesWithSourceAndSummary() {
        pulse("News"); waitText("Alpha Corp (2)")
        tapTag("news_filter_AAA")
        waitText("Alpha beats estimates"); waitText("Alpha reported strong earnings for the quarter."); waitText("Google News · TipRanks", sub = true)
        gone("Markets rally on rate hopes")
        tapTag("news_filter_all"); waitText("Markets rally on rate hopes")
    }
    @Test fun emptyNewsExplainsWhatToDo() { pulse("News", news = """{"groups":[],"symbols":["AAA","BBB"]}"""); waitText("No headlines in the current window for AAA, BBB", sub = true) }
    @Test fun emptyNewsWithNoSymbolsSaysSo() { pulse("News", news = """{"groups":[],"symbols":[]}"""); waitText("No symbols to fetch yet", sub = true) }

    private fun openList(id: Int) { tapTag("watchlist_$id"); }

    @Test fun watchlistShowsTheFullMetricSet() {
        pulse("Watchlists"); waitText("India"); rule.onNodeWithText("India").performClick()
        waitText("PIDILITIND.NS"); waitText("1,506.50 INR"); waitText("+2.29%")
        waitText("62.74 / 49.68 fwd"); waitText("ind 61.4x (2)"); waitText("ind 13.2x (2)")
        waitText("Net cash 38.02B"); waitText("1.53T"); waitText("1,259–1,575"); waitText("0.67%"); waitText("23.52%"); waitText("16.77%")
        waitText("INDEX / FUND"); waitText("Net debt 2.50M"); waitText("NM")
    }
    @Test fun creatingAListSendsTheNameAndShowsAMessage() {
        env.route("POST", "/api/pulse/watchlists") { env.ok("""{"id":3,"name":"Tech","items":[]}""") }
        pulse("Watchlists"); waitText("New watchlist name")
        rule.onNodeWithTag("watch_new_name").performTextInput("Tech"); rule.onNodeWithTag("watch_create").performClick()
        waitText("List created."); assert(env.count("POST", "/api/pulse/watchlists") == 1)
    }
    @Test fun atTheListCapCreationIsOff() {
        pulse("Watchlists", lists = fixture("watchlists.json").replace("\"max_watchlists\": 5", "\"max_watchlists\": 2"))
        waitText("Maximum lists reached"); rule.onNodeWithTag("watch_create").assertIsNotEnabled()
    }
    @Test fun searchingAndAddingASymbol() {
        env.route("GET", "/api/portfolio/symbol-search") { env.ok("""[{"symbol":"VOD.L","name":"Vodafone Group","exchange":"LSE","currency":null,"type":"EQUITY"}]""") }
        env.route("POST", "/api/pulse/watchlists/2/items") { env.ok("""{"id":2,"name":"List-1","items":[]}""") }
        pulse("Watchlists"); waitText("List-1"); rule.onNodeWithText("List-1").performClick()
        tapTag("watch_add_2")
        rule.onNodeWithTag("watch_search").performTextInput("vod")
        waitText("Vodafone Group"); waitText("VOD.L · LSE")
        tapTag("hit_VOD.L")
        waitText("VOD.L added."); assert(env.count("POST", "/api/pulse/watchlists/2/items") == 1)
    }
    @Test fun togglingNewsAndRemovingASymbol() {
        env.route("PATCH", "/api/pulse/watchlists/1/items/1") { env.ok("""{"id":1,"name":"India","items":[]}""") }
        env.route("DELETE", "/api/pulse/watchlists/1/items/2") { MockResponse().setResponseCode(204) }
        pulse("Watchlists"); waitText("India"); rule.onNodeWithText("India").performClick()
        tapTag("watch_news_PIDILITIND.NS"); rule.waitUntil(5_000) { env.count("PATCH", "/api/pulse/watchlists/1/items/1") == 1 }
        tapTag("watch_remove_VUAG.L"); waitText("VUAG.L removed.")
    }
    @Test fun deletingAListAsksFirst() {
        env.route("DELETE", "/api/pulse/watchlists/2") { MockResponse().setResponseCode(204) }
        pulse("Watchlists"); waitText("List-1"); rule.onNodeWithText("List-1").performClick()
        tapTag("watch_delete_2"); waitText("Delete watchlist?"); rule.onNodeWithText("Cancel").performClick(); Thread.sleep(300); assert(env.count("DELETE", "/api/pulse/watchlists/2") == 0)
        tapTag("watch_delete_2"); rule.onNodeWithTag("watch_delete_ok").performClick(); waitText("List deleted."); assert(env.count("DELETE", "/api/pulse/watchlists/2") == 1)
    }

    // ---------------------------------------------------------------- Sync states

    private fun sync(t212: String = """{"t212_configured":true,"accounts":{}}""", snap: String = """{"snaptrade_configured":true,"snaptrade_active_connections_count":1}""") {
        env.route("GET", "/api/sync/status") { env.ok(t212) }
        env.route("GET", "/api/snaptrade/status") { env.ok(snap) }
        env.route("GET", "/api/sync/manual-quota") { env.ok("""{"max_per_24h":3,"t212":{"used_in_window":1,"remaining":2,"rate_limit_allows":true},"snaptrade":{"used_in_window":1,"remaining":2,"rate_limit_allows":true}}""") }
        env.route("GET", "/api/sync/history") { env.ok("[]") }
        env.route("GET", "/api/sync/manual/t212-running") { env.ok("""{"t212_manual_in_progress":false,"active_sync_log_id":null}""") }
        env.route("GET", "/api/sync/manual/snaptrade-running") { env.ok("""{"snaptrade_manual_in_progress":false,"active_sync_log_id":null}""") }
        val vm = SyncViewModel(env.repo, 20)
        rule.setContent { RushifyTheme { SyncScreen(vm) } }
    }

    @Test fun healthyBrokersCanSync() { sync(); waitText("1 active broker connection · daily sync"); rule.onNodeWithTag("sync_T212").assertIsEnabled(); rule.onNodeWithTag("sync_SNAPTRADE").assertIsEnabled() }
    @Test fun expiredTrading212LinkExplainsAndBlocksSync() {
        sync(t212 = """{"t212_configured":true,"t212_needs_reconnect":true,"accounts":{"isa":{"configured":true,"needs_reconnect":true,"last_error":"401 Unauthorized"}}}""")
        waitText("Broker link expired", sub = true); waitText("Stocks & Shares ISA: 401 Unauthorized"); rule.onNodeWithTag("sync_T212").assertIsNotEnabled(); waitText("Trading 212 link expired", sub = true)
    }
    @Test fun snapTradeNeedsABrokerLinkOffersThePortal() {
        env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"redirect_uri":"https://app.snaptrade.com/connect"}""") }
        sync(snap = """{"snaptrade_configured":true,"snaptrade_needs_broker_link":true}""")
        waitText("Connect a brokerage", sub = true); rule.onNodeWithTag("sync_SNAPTRADE").assertIsNotEnabled()
        rule.onNodeWithTag("snaptrade_link_btn").performScrollTo().performClick()
        rule.waitUntil(5_000) { env.count("POST", "/api/snaptrade/portal-url") == 1 }
    }
    @Test fun snapTradeReconnectNamesTheBrokerAndSendsItsAuthorizationId() {
        env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"redirect_uri":"https://app.snaptrade.com/reconnect"}""") }
        sync(snap = """{"snaptrade_configured":true,"snaptrade_needs_reconnect":true,"snaptrade_disabled":[{"id":"auth-9","broker_name":"Robinhood"}]}""")
        waitText("Broker link disabled or expired (Robinhood)", sub = true)
        rule.onNodeWithTag("snaptrade_reconnect_btn").performScrollTo().performClick()
        rule.waitUntil(5_000) { env.fullPaths.any { it.contains("portal-url") } }
    }
    @Test fun snapTradeAuthFailureBlocksSync() {
        sync(snap = """{"snaptrade_configured":true,"snaptrade_auth_failed":true}""")
        waitText("SnapTrade session expired", sub = true); rule.onNodeWithTag("sync_SNAPTRADE").assertIsNotEnabled()
    }
    @Test fun aFailedSyncHoldsTheButtonBackWithAnExplanation() {
        sync(); env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"Internal error"}""", 500) }
        waitText("1 active broker connection", sub = true)
        tapTag("sync_T212"); rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("confirm_sync")).fetchSemanticsNodes().isNotEmpty() }; rule.onNodeWithTag("confirm_sync").performClick()
        waitText("Please wait a few seconds before syncing again.")
        rule.onNodeWithTag("sync_T212").assertIsNotEnabled()
    }

    // ---------------------------------------------------------------- Connections

    @Test fun connectionsShowsStatusWithoutAnyKey() {
        env.route("GET", "/api/portfolio/credentials") { env.ok(fixture("credentials.json")) }
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{"isa":{"configured":true,"needs_reconnect":true,"last_error":"401"}}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true,"snaptrade_active_connections_count":2}""") }
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        env.route("GET", "/api/snaptrade/connections") { env.ok("""[{"id":"c1","disabled":false,"broker_name":"AJ Bell"},{"id":"c2","disabled":true,"broker_name":"Robinhood"}]""") }
        val vm = ConnectionsViewModel(env.repo); rule.setContent { RushifyTheme { ConnectionsScreen(vm) {} } }
        waitText("Trading 212"); waitText("Invest"); waitText("Stocks & Shares ISA"); waitText("SIPP"); waitText("Not set")
        waitText("API keys rejected or expired", sub = true)
        waitText("2 active connections.", sub = true); waitText("AJ Bell"); waitText("Robinhood"); waitText("Reconnect"); waitText("Connected")
        waitText("Gemini key"); waitText("OpenAI key")
        assert(!has("AIza", sub = true) && !has("****", sub = true))        // masked secrets are never shown
    }

    // ---------------------------------------------------------------- Change password

    private fun password(): ChangePasswordViewModel { val vm = ChangePasswordViewModel(env.repo); rule.setContent { RushifyTheme { ChangePasswordScreen(vm) {} } }; return vm }
    private fun fill(cur: String, new: String, conf: String) {
        rule.onNodeWithTag("pw_current").performTextInput(cur); rule.onNodeWithTag("pw_new").performTextInput(new); rule.onNodeWithTag("pw_confirm").performTextInput(conf)
    }

    @Test fun submitStaysOffUntilEveryFieldIsFilled() { password(); rule.onNodeWithTag("pw_submit").assertIsNotEnabled(); fill("oldpass1", "newpass12", "newpass12"); rule.onNodeWithTag("pw_submit").assertIsEnabled() }
    @Test fun mismatchAndShortPasswordsAreCaughtBeforeAnyRequest() {
        password(); fill("oldpass1", "newpass12", "different1"); rule.onNodeWithTag("pw_submit").performClick(); waitText("New password and confirmation do not match.")
        assert(env.count("POST", "/api/auth/replace-password") == 0)
    }
    @Test fun successShowsTheMessage() {
        env.route("POST", "/api/auth/replace-password") { env.ok("""{"ok":true}""") }
        password(); fill("oldpass1", "newpass12", "newpass12"); rule.onNodeWithTag("pw_submit").performClick()
        waitText("Password updated. Please sign in with your new password."); assert(env.count("POST", "/api/auth/replace-password") == 1)
    }
    @Test fun aWrongCurrentPasswordShowsAnErrorAndKeepsTheSession() {
        env.route("POST", "/api/auth/replace-password") { env.ok("""{"detail":"Login failure try again."}""", 401) }
        password(); fill("wrongpass", "newpass12", "newpass12"); rule.onNodeWithTag("pw_submit").performClick()
        waitText("Current password is incorrect."); assert(env.session.current.loggedIn)
    }

    // ---------------------------------------------------------------- AI

    @Test fun clearChatAndStandardModeNote() {
        env.route("GET", "/api/portfolio/credentials") { env.ok("""{"gemini_configured":true,"ai_provider":"gemini"}""") }
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"Hello there"}""") }
        val vm = AiViewModel(env.repo); rule.setContent { RushifyTheme { AiScreen(vm) {} } }
        waitText("Standard mode: replies use your Rushify portfolio snapshot only", sub = true)
        assert(!has("Clear chat"))
        rule.onNodeWithTag("ai_input").performTextInput("Hi"); rule.onNodeWithTag("ai_send").performClick()
        waitText("Hello there"); waitText("Clear chat")
        rule.onNodeWithTag("ai_clear").performClick(); gone("Hello there"); gone("Clear chat")
    }
    @Test fun anOpenAiErrorReplyIsShownAsAnErrorNotAnAnswer() {
        env.route("GET", "/api/portfolio/credentials") { env.ok("""{"openai_configured":true,"ai_provider":"openai"}""") }
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"OpenAI request failed: You exceeded your current quota, please check your plan and billing."}""") }
        val vm = AiViewModel(env.repo); rule.setContent { RushifyTheme { AiScreen(vm) {} } }
        waitText("Standard mode", sub = true)
        rule.onNodeWithTag("ai_input").performTextInput("Hi"); rule.onNodeWithTag("ai_send").performClick()
        waitText("OpenAI blocked this request (quota or rate limit)", sub = true); waitText("Couldn't generate a reply", sub = true)
    }
}
