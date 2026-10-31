package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.AiCredentialsStatus
import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.WatchlistRefresh
import com.rushify.mobile.fixture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regression tests for bugs B1-B5 from docs/WEB_VS_MOBILE_GAP_ANALYSIS.md. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BugFixTests {
    private lateinit var env: TestEnv

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn() }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun <T> await(flow: StateFlow<T>, cond: (T) -> Boolean): T = runBlocking { withTimeout(6_000) { flow.first(cond) } }
    private fun requestsSeen() = generateSequence { env.server.takeRequest(150, TimeUnit.MILLISECONDS) }.toList()
    private fun listsJson(name: String, price: String) =
        """{"watchlists":[{"id":1,"name":"$name","items":[{"id":1,"yahoo_symbol":"AAA","last_price":"$price","currency":"USD"}]}]}"""

    // ---------------- B1: watchlist refresh ----------------

    @Test fun b1_refreshSendsForceTrueThenRereadsListsSoNewQuotesAppear() {
        var posted = false
        env.route("GET", "/api/pulse/watchlists") { env.ok(if (posted) listsJson("India", "200.00") else listsJson("India", "100.00")) }
        env.route("POST", "/api/pulse/watchlists/refresh") { posted = true; env.ok("""{"refreshed":3,"skipped":0,"errors":[],"cache_hours":2,"all_fresh":true}""") }
        val vm = WatchlistsViewModel(env.repo); vm.load()
        assertEquals("100.00", await(vm.state) { it.data != null }.data!!.value.watchlists[0].items[0].lastPrice.toString())
        vm.forceRefresh()
        val after = await(vm.state) { it.data?.value?.watchlists?.firstOrNull()?.items?.firstOrNull()?.lastPrice?.toString() == "200.00" }
        assertEquals("200.00", after.data!!.value.watchlists[0].items[0].lastPrice.toString())
        assertEquals("Refreshed 3 symbols. Open Pulse → News and tap Refresh there for headlines.", await(vm.refreshUi) { it.message != null }.message)
        val post = requestsSeen().first { it.method == "POST" }
        assertTrue(post.path, post.path!!.contains("/api/pulse/watchlists/refresh") && post.path!!.contains("force=true"))
    }

    @Test fun b1_summaryReplyIsNotMistakenForAWatchlistAndNeverBlanksTheScreen() {
        env.route("GET", "/api/pulse/watchlists") { env.ok(listsJson("India", "100.00")) }
        env.route("POST", "/api/pulse/watchlists/refresh") { env.ok("""{"refreshed":0,"skipped":4,"errors":[],"cache_hours":2,"all_fresh":true}""") }
        val vm = WatchlistsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.forceRefresh(); await(vm.refreshUi) { it.message != null }
        assertEquals(1, vm.state.value.data!!.value.watchlists.size)
    }

    @Test fun b1_messagesMatchTheWebWording() {
        fun msg(r: WatchlistRefresh) = WatchlistRefreshMessage.of(r)
        assertEquals("Up to date (within 2h).", msg(WatchlistRefresh(refreshed = 0, allFresh = true, cacheHours = 2)))
        assertEquals("Refreshed 1 symbol. Open Pulse → News and tap Refresh there for headlines.", msg(WatchlistRefresh(refreshed = 1)))
        assertEquals("No symbols to refresh.", msg(WatchlistRefresh(refreshed = 0, allFresh = false)))
        assertTrue(msg(WatchlistRefresh(refreshed = 2, errors = listOf("x"))).endsWith("1 could not be refreshed."))
    }

    @Test fun b1_failedRefreshShowsErrorAndKeepsOldData() {
        env.route("GET", "/api/pulse/watchlists") { env.ok(listsJson("India", "100.00")) }
        env.route("POST", "/api/pulse/watchlists/refresh") { env.ok("""{"detail":"Market data provider down"}""", 502) }
        val vm = WatchlistsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.forceRefresh()
        val r = await(vm.refreshUi) { it.message != null }
        assertTrue(r.failed); assertTrue(r.message!!.contains("Market data provider down"))
        assertNotNull(vm.state.value.data)
    }

    // ---------------- B2: listed position count ----------------

    @Test fun b2_cashPropertyOtherMonzoAndSoldAreNotPositions() {
        val rows = AppJson.decodeFromString(Dashboard.serializer(), fixture("dashboard.json")).rows
        assertEquals(2, listedPositionCount(rows))                      // AAA + BBB; CASH.1, property and sold OLD excluded
    }

    @Test fun b2_manualListedStocksDoCount() {
        fun row(src: String, kind: String?, sold: String? = null) = HoldingRow(ticker = "X", source = src, assetKind = kind, soldOutAt = sold)
        assertEquals(1, listedPositionCount(listOf(row("manual", null), row("manual", "cash"), row("manual", "other"), row("monzo", "cash"), row("t212", "cash"), row("t212", null, sold = "2026-01-01"))))
    }

    // ---------------- B3: periods match the web ----------------

    @Test fun b3_periodOptionsAndDefaultsMatchTheWeb() {
        assertEquals(listOf(6, 12, 18, 24), PeriodOptions.growth("monthly")); assertEquals(listOf(3, 5, 8, 10), PeriodOptions.growth("yearly"))
        assertEquals(listOf(6, 12, 18, 24, 36), PeriodOptions.dividends("monthly")); assertEquals(listOf(3, 4, 5, 8, 10, 15), PeriodOptions.dividends("yearly"))
        assertEquals(12, PeriodOptions.default("monthly")); assertEquals(5, PeriodOptions.default("yearly"))
        assertEquals("12 mo", PeriodOptions.label("monthly", 12)); assertEquals("5 yr", PeriodOptions.label("yearly", 5))
    }

    @Test fun b3_growthYearlyAsksForFiveYearsNotTen() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        val vm = GrowthViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select("yearly"); await(vm.state) { it.data != null && vm.granularity == "yearly" }
        assertEquals(5, vm.count)
        val paths = requestsSeen().map { it.path!! }
        assertTrue(paths.toString(), paths.any { it.contains("granularity=yearly") && it.contains("count=5") })
    }

    @Test fun b3_periodsSelectionIsSentAndSurvivesButResetsWhenGranularityChanges() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        val vm = GrowthViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select(n = 24); await(vm.state) { it.data != null && vm.count == 24 }
        assertEquals("monthly", vm.granularity)
        vm.select("yearly"); assertEquals(5, vm.count)
        vm.select("monthly"); assertEquals(12, vm.count)
        val paths = requestsSeen().map { it.path!! }
        assertTrue(paths.toString(), paths.any { it.contains("granularity=monthly") && it.contains("count=24") })
    }

    @Test fun b3_benchmarksUseGranularityAndCountLikeTheWebAndRangeOnlyForDayWeek() {
        env.route("GET", "/api/portfolio/benchmarks") { env.ok(fixture("benchmarks.json")) }
        val vm = BenchmarksViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select(m = "yearly"); await(vm.state) { it.data != null && vm.mode == "yearly" }
        vm.select(m = "day"); await(vm.state) { it.data != null && vm.mode == "day" }
        val paths = requestsSeen().map { it.path!! }.filter { it.contains("benchmarks") }
        assertTrue(paths.toString(), paths.any { it.contains("granularity=monthly") && it.contains("count=12") && !it.contains("range=") })
        assertTrue(paths.toString(), paths.any { it.contains("granularity=yearly") && it.contains("count=5") && !it.contains("range=") })
        assertTrue(paths.toString(), paths.any { it.contains("range=day") && !it.contains("granularity=") && !it.contains("count=") })
    }

    @Test fun b3_dividendsDefaultToYearlyFiveYearsLikeTheWeb() {
        env.route("GET", "/api/portfolio/dividend-dashboard") { env.ok(fixture("dividends.json")) }
        val vm = DividendsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        assertEquals("yearly", vm.granularity); assertEquals(5, vm.count)
        val q = requestsSeen().map { it.path!! }.first { it.contains("dividend-dashboard") }
        assertTrue(q, q.contains("granularity=yearly") && q.contains("history_count=5"))
        vm.select(g = "monthly"); await(vm.state) { it.data != null && vm.granularity == "monthly" }
        assertEquals(12, vm.count)
    }

    @Test fun b3_cacheIsKeptPerPeriodCount() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        runBlocking {
            env.repo.growth("monthly", 12)
            assertNotNull(env.repo.growthCached("monthly", 12)); assertNull(env.repo.growthCached("monthly", 24))
        }
    }

    // ---------------- B4: AI key readiness ----------------

    private fun credsJson(provider: String?, gemini: Boolean, openai: Boolean) =
        """{"gemini_configured":$gemini,"openai_configured":$openai,${provider?.let { "\"ai_provider\":\"$it\"," } ?: ""}"gemini_api_key":"AIza****","openai_api_key":"sk-****","t212_api_secret":"****"}"""

    @Test fun b4_readinessFollowsTheSelectedProviderLikeTheWeb() {
        fun status(p: String?, g: Boolean, o: Boolean) = AppJson.decodeFromString(AiCredentialsStatus.serializer(), credsJson(p, g, o))
        assertTrue(status("gemini", true, false).ready); assertFalse(status("gemini", false, true).ready)
        assertTrue(status("openai", false, true).ready); assertFalse(status("openai", true, false).ready)
        assertTrue(status(null, true, false).ready)                         // provider defaults to Gemini
        assertEquals("OpenAI", status("OpenAI", false, true).providerLabel); assertEquals("Gemini", status(null, true, false).providerLabel)
    }

    @Test fun b4_maskedSecretsFromTheCredentialsResponseAreNeverKept() {
        val status = AppJson.decodeFromString(AiCredentialsStatus.serializer(), credsJson("gemini", true, false))
        val reEncoded = AppJson.encodeToString(AiCredentialsStatus.serializer(), status)
        assertFalse(reEncoded, reEncoded.contains("AIza") || reEncoded.contains("sk-") || reEncoded.contains("t212"))
    }

    @Test fun b4_missingKeyBlocksSendingBeforeAnyRequest() {
        env.route("GET", "/api/portfolio/credentials") { env.ok(credsJson("openai", gemini = true, openai = false)) }
        val vm = AiViewModel(env.repo)
        val s = await(vm.ui) { it.keyReady != null }
        assertEquals(false, s.keyReady); assertEquals("OpenAI", s.providerLabel); assertTrue(s.blocked)
        vm.send("hello"); Thread.sleep(200)
        assertEquals(0, env.count("POST", "/api/ai/insight")); assertTrue(vm.ui.value.messages.isEmpty())
    }

    @Test fun b4_readyKeyAllowsSend() {
        env.route("GET", "/api/portfolio/credentials") { env.ok(credsJson("gemini", gemini = true, openai = false)) }
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"hi"}""") }
        val vm = AiViewModel(env.repo); await(vm.ui) { it.keyReady == true }
        vm.send("hello"); assertEquals("hi", await(vm.ui) { it.messages.size == 2 }.messages[1].text)
    }

    @Test fun b4_failedCredentialsCheckMeansUnknownNotBlocked() {
        env.route("GET", "/api/portfolio/credentials") { env.ok("down", 503) }
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"hi"}""") }
        val vm = AiViewModel(env.repo); Thread.sleep(300)
        assertNull(vm.ui.value.keyReady); assertFalse(vm.ui.value.blocked)
        vm.send("hello"); await(vm.ui) { it.messages.size == 2 }
    }

    @Test fun b4_serverSaysNotConfiguredAlsoMarksKeyMissingAndKeepsProviderLabel() {
        env.route("GET", "/api/portfolio/credentials") { env.ok(credsJson("gemini", gemini = true, openai = false)) }
        env.route("POST", "/api/ai/insight") { env.ok("""{"detail":"Gemini API key not configured"}""", 400) }
        val vm = AiViewModel(env.repo); await(vm.ui) { it.keyReady == true }
        vm.send("hello")
        val s = await(vm.ui) { it.notConfigured }
        assertEquals(false, s.keyReady); assertEquals("Gemini", s.providerLabel)
        vm.clear(); assertEquals("Gemini", vm.ui.value.providerLabel); assertEquals(false, vm.ui.value.keyReady)
    }

    // ---------------- B5: only http(s) links are opened ----------------

    @Test fun b5_httpAndHttpsAreAllowed() {
        assertEquals("https://example.com/a?b=1", SafeUrl.httpOrNull("https://example.com/a?b=1"))
        assertEquals("http://news.example.org/", SafeUrl.httpOrNull("  http://news.example.org  "))
        assertEquals("https://example.com/", SafeUrl.httpOrNull("HTTPS://EXAMPLE.com"))
        assertNotNull(SafeUrl.httpOrNull("https://news.google.com/rss/articles/CBMi?oc=5"))
    }

    @Test fun b5_lenientButStillHttpsIsFine() {
        assertEquals("https://nohost/", SafeUrl.httpOrNull("https:///nohost"))   // OkHttp repairs the extra slash; still plain https
    }

    @Test fun b5_everyOtherSchemeAndMalformedValueIsRejected() {
        listOf(
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///etc/hosts",
            "content://com.android.contacts/contacts", "data:text/html,<script>1</script>", "market://details?id=x", "tel:+441234",
            "mailto:a@b.c", "ftp://host/file", "//evil.example/path", "example.com/path", "", "   ", "http://", "https://",
            "http://exa\nmple.com", "\u0000https://example.com",
        ).forEach { assertNull("should reject: $it", SafeUrl.httpOrNull(it)) }
        assertNull(SafeUrl.httpOrNull(null))
    }
}
