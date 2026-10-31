package com.rushify.mobile.ui

import androidx.compose.ui.graphics.Color
import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.CredentialFlags
import com.rushify.mobile.data.Radar
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SnaptradeStatus
import com.rushify.mobile.data.SyncStatus
import com.rushify.mobile.data.T212Account
import com.rushify.mobile.data.WatchItem
import com.rushify.mobile.data.Watchlists
import com.rushify.mobile.fixture
import java.math.BigDecimal
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

/** Phase 4: watchlists, news/radar detail, sync states, connections, change password, AI and Monzo wording. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Phase4Tests {
    private lateinit var env: TestEnv
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn() }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun d(s: String) = BigDecimal(s)
    private fun <T> await(flow: StateFlow<T>, cond: (T) -> Boolean): T = runBlocking { withTimeout(6_000) { flow.first(cond) } }
    private fun recorded() = generateSequence { env.server.takeRequest(150, TimeUnit.MILLISECONDS) }.toList()
    private val lists get() = AppJson.decodeFromString(Watchlists.serializer(), fixture("watchlists.json"))
    private val first get() = lists.watchlists[0].items[0]
    private val etf get() = lists.watchlists[0].items[1]

    // ================= watchlist display rules =================
    @Test fun valuationRatios() {
        assertEquals("—", WatchlistFormat.valuationRatio(null)); assertEquals("NM", WatchlistFormat.valuationRatio(d("0"))); assertEquals("NM", WatchlistFormat.valuationRatio(d("-3")))
        assertEquals("12.35", WatchlistFormat.valuationRatio(d("12.345"))); assertEquals("100", WatchlistFormat.valuationRatio(d("100"))); assertEquals("1235", WatchlistFormat.valuationRatio(d("1234.6")))
    }
    @Test fun roeIsPercentNumberAndNegativeIsNm() { assertEquals("23.52%", WatchlistFormat.roe(d("23.52"))); assertEquals("NM", WatchlistFormat.roe(d("-4.2"))); assertEquals("—", WatchlistFormat.roe(null)); assertEquals("0.00%", WatchlistFormat.roe(BigDecimal.ZERO)) }
    @Test fun compactMoney() {
        assertEquals("1.53T", WatchlistFormat.compactMoney(d("1533278552064"))); assertEquals("38.02B", WatchlistFormat.compactMoney(d("38015200768"))); assertEquals("12.30M", WatchlistFormat.compactMoney(d("12300000")))
        assertEquals("12.3K", WatchlistFormat.compactMoney(d("12345"))); assertEquals("950", WatchlistFormat.compactMoney(d("950"))); assertEquals("-2.50M", WatchlistFormat.compactMoney(d("-2500000"))); assertEquals("—", WatchlistFormat.compactMoney(null))
    }
    @Test fun netCashLines() { assertEquals("Net cash 38.02B", WatchlistFormat.netCashLine(d("38015200768"))); assertEquals("Net debt 2.50M", WatchlistFormat.netCashLine(d("-2500000"))); assertEquals("Net cash neutral", WatchlistFormat.netCashLine(BigDecimal.ZERO)); assertNull(WatchlistFormat.netCashLine(null)) }
    @Test fun industryHintNeedsTwoPeersAndAPositiveMedian() {
        assertEquals("ind 61.4x (2)", WatchlistFormat.industryHint(d("61.3867"), 2)); assertEquals("ind 150x (3)", WatchlistFormat.industryHint(d("150.2"), 3))
        assertNull(WatchlistFormat.industryHint(d("61"), 1)); assertNull(WatchlistFormat.industryHint(BigDecimal.ZERO, 5)); assertNull(WatchlistFormat.industryHint(null, 5))
    }
    @Test fun peShowsForwardOnlyWhenItDiffers() {
        assertEquals("62.74 / 49.68 fwd", WatchlistFormat.peText(first))
        assertEquals("62.74", WatchlistFormat.peText(first.copy(forwardPeRatio = d("62.74")))); assertEquals("62.74", WatchlistFormat.peText(first.copy(forwardPeRatio = null))); assertEquals("NM", WatchlistFormat.peText(etf))
    }
    @Test fun dividendYieldFractionsAreScaledLikeTheWeb() { assertEquals("0.67%", WatchlistFormat.dividendPct(d("0.0067"))); assertEquals("100.00%", WatchlistFormat.dividendPct(d("1"))); assertEquals("1.20%", WatchlistFormat.dividendPct(d("1.2"))); assertEquals("—", WatchlistFormat.dividendPct(null)) }
    @Test fun indexLikeTag() { assertTrue(WatchlistFormat.isIndexLike("ETF")); assertTrue(WatchlistFormat.isIndexLike("mutualfund")); assertFalse(WatchlistFormat.isIndexLike("EQUITY")); assertFalse(WatchlistFormat.isIndexLike(null)) }
    @Test fun rangeAndPrice() { assertEquals("1,259–1,575", WatchlistFormat.range52(first.week52Low, first.week52High)); assertEquals("—", WatchlistFormat.range52(null, null)); assertEquals("1,506.50 INR", WatchlistFormat.price(first.lastPrice, "INR")) }
    @Test fun fixtureItemParsesEveryMetric() {
        assertEquals(d("14.10"), first.revenueGrowthPct); assertEquals(2, first.industryPeerCount); assertNull(first.pegRatio); assertTrue(first.fetchNews); assertFalse(etf.fetchNews)
        assertEquals("ind 13.2x (2)", WatchlistFormat.industryHint(first.industryPbMedian, first.industryPeerCount))
    }

    // ================= limits =================
    @Test fun createRulesRespectCapsNamesAndUnlimited() {
        val w = lists
        assertTrue(WatchlistRules.canCreate(w, "  Tech  ")); assertFalse(WatchlistRules.canCreate(w, "   ")); assertFalse(WatchlistRules.canCreate(w, "x".repeat(65))); assertTrue(WatchlistRules.canCreate(w, "x".repeat(64)))
        val full = w.copy(maxWatchlists = 2); assertFalse(WatchlistRules.canCreate(full, "a")); assertTrue(WatchlistRules.atListCap(full)); assertTrue(WatchlistRules.canCreate(full.copy(limitsUnlimited = true), "a"))
        assertTrue(WatchlistRules.canCreate(null, "a"))
    }
    @Test fun itemCapAndCounts() {
        val l = lists.watchlists[0]
        assertFalse(WatchlistRules.atItemCap(lists, l)); assertTrue(WatchlistRules.atItemCap(lists.copy(maxItemsPerWatchlist = 2), l)); assertFalse(WatchlistRules.atItemCap(lists.copy(maxItemsPerWatchlist = 2, limitsUnlimited = true), l))
        assertEquals(1, WatchlistRules.newsCount(l)); assertTrue(WatchlistRules.hasAnyItems(lists)); assertFalse(WatchlistRules.hasAnyItems(Watchlists()))
        assertTrue(WatchlistRules.searchable("vo")); assertFalse(WatchlistRules.searchable(" v ")); assertFalse(WatchlistRules.searchable(""))
    }

    // ================= watchlist editing =================
    private fun routeLists(json: String = fixture("watchlists.json")) = env.route("GET", "/api/pulse/watchlists") { env.ok(json) }
    private fun loaded(): WatchlistsViewModel { routeLists(); val vm = WatchlistsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }; return vm }

    @Test fun createListSendsTheTrimmedNameThenRereads() {
        val vm = loaded(); env.route("POST", "/api/pulse/watchlists") { env.ok("""{"id":3,"name":"Tech ideas","items":[]}""") }
        vm.createList("  Tech ideas  "); await(vm.edit) { it.message != null }
        val post = recorded().first { it.method == "POST" }
        assertEquals("{\"name\":\"Tech ideas\"}", post.body.readUtf8()); assertTrue(env.count("GET", "/api/pulse/watchlists") >= 2)
    }
    @Test fun createIsBlockedAtTheCapWithNoRequest() {
        routeLists(AppJson.encodeToString(Watchlists.serializer(), lists.copy(maxWatchlists = 2))); val vm = WatchlistsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.createList("One more"); assertEquals("Maximum lists reached.", vm.edit.value.error); assertEquals(0, env.count("POST", "/api/pulse/watchlists"))
    }
    @Test fun blankNameIsRejectedLocally() { val vm = loaded(); vm.createList("   "); assertTrue(vm.edit.value.error!!.contains("Enter a name")); assertEquals(0, env.count("POST", "/api/pulse/watchlists")) }
    @Test fun deleteListCallsDelete() {
        val vm = loaded(); env.route("DELETE", "/api/pulse/watchlists/2") { MockResponse().setResponseCode(204) }
        vm.deleteList(2); assertEquals("List deleted.", await(vm.edit) { it.message != null }.message); assertEquals(1, env.count("DELETE", "/api/pulse/watchlists/2"))
    }
    @Test fun addSymbolSendsNameExchangeAndTheNewsChoice() {
        val vm = loaded(); env.route("POST", "/api/pulse/watchlists/2/items") { env.ok("""{"id":2,"name":"List-1","items":[]}""") }
        vm.addSymbol(lists.watchlists[1], com.rushify.mobile.data.SymbolHit("VOD.L", "Vodafone Group", "LSE"), fetchNews = true)
        await(vm.edit) { it.message != null }
        assertEquals("{\"yahoo_symbol\":\"VOD.L\",\"company_name\":\"Vodafone Group\",\"exchange\":\"LSE\",\"fetch_news\":true}", recorded().first { it.method == "POST" }.body.readUtf8())
    }
    @Test fun addSymbolIsBlockedWhenTheListIsFull() {
        val vm = loaded(); vm.state.value = vm.state.value.copy(data = vm.state.value.data!!.copy(value = lists.copy(maxItemsPerWatchlist = 2)))
        vm.addSymbol(lists.watchlists[0], com.rushify.mobile.data.SymbolHit("X"), true); assertEquals("This list is full.", vm.edit.value.error)
    }
    @Test fun toggleNewsFlipsTheCurrentValue() {
        val vm = loaded(); env.route("PATCH", "/api/pulse/watchlists/1/items/1") { env.ok("""{"id":1,"name":"India","items":[]}""") }; env.route("PATCH", "/api/pulse/watchlists/1/items/2") { env.ok("""{"id":1,"name":"India","items":[]}""") }
        vm.toggleNews(lists.watchlists[0], first); await(vm.edit) { !it.busy && it.error == null }
        assertEquals("{\"fetch_news\":false}", recorded().first { it.method == "PATCH" }.body.readUtf8())
    }
    @Test fun removeSymbolCallsDeleteOnTheItem() {
        val vm = loaded(); env.route("DELETE", "/api/pulse/watchlists/1/items/2") { MockResponse().setResponseCode(204) }
        vm.removeSymbol(lists.watchlists[0], etf); assertEquals("VUAG.L removed.", await(vm.edit) { it.message != null }.message)
    }
    @Test fun serverErrorsAreShownAndTheListStaysAsItWas() {
        val vm = loaded(); env.route("POST", "/api/pulse/watchlists") { env.ok("""{"detail":"You can have at most 5 watchlists"}""", 422) }
        vm.createList("X"); assertEquals("You can have at most 5 watchlists", await(vm.edit) { it.error != null }.error); assertEquals(2, vm.state.value.data!!.value.watchlists.size)
    }
    @Test fun onlyOneEditAtATime() {
        val vm = loaded(); env.route("DELETE", "/api/pulse/watchlists/2") { Thread.sleep(300); MockResponse().setResponseCode(204) }
        vm.deleteList(2); vm.deleteList(2); await(vm.edit) { it.message != null }; assertEquals(1, env.count("DELETE", "/api/pulse/watchlists/2"))
    }

    // ================= symbol search =================
    @Test fun searchWaitsForTwoCharactersAndDebounces() {
        val vm = loaded(); env.route("GET", "/api/portfolio/symbol-search") { env.ok("""[{"symbol":"VOD.L","name":"Vodafone","exchange":"LSE","currency":null,"type":"EQUITY"}]""") }
        vm.onQuery("v"); Thread.sleep(500); assertEquals(0, env.count("GET", "/api/portfolio/symbol-search"))
        vm.onQuery("vo"); vm.onQuery("vod")                                   // the first keystroke is superseded
        val res = await(vm.search) { it.results.isNotEmpty() }
        assertEquals("VOD.L", res.results[0].symbol)
        assertEquals(1, env.count("GET", "/api/portfolio/symbol-search")); assertTrue(env.fullPaths.any { it.endsWith("q=vod") })
    }
    @Test fun searchFailureJustClearsResults() {
        val vm = loaded(); env.route("GET", "/api/portfolio/symbol-search") { env.ok("down", 503) }
        vm.onQuery("vod"); Thread.sleep(900); assertTrue(vm.search.value.results.isEmpty()); assertFalse(vm.search.value.searching)
    }
    @Test fun clearSearchResetsEverything() { val vm = loaded(); vm.onQuery("vod"); vm.clearSearch(); assertEquals(SymbolSearchUi(), vm.search.value) }

    // ================= news and radar =================
    @Test fun newsSourceLabels() { assertEquals("Yahoo", sourceLabel("yahoo")); assertEquals("Google News", sourceLabel("google")); assertEquals("Reuters", sourceLabel("Reuters")); assertNull(sourceLabel(null)); assertNull(sourceLabel("")) }
    private val radar get() = AppJson.decodeFromString(Radar.serializer(), fixture("radar.json"))
    @Test fun radarParsesBucketsAndCoverage() {
        val r = radar; assertEquals(5, r.ratingBuckets.size); assertEquals(listOf("AAA", "BBB"), r.ratingBuckets[0].tickers); assertEquals(94, r.scoredMvPct); assertEquals(5, r.notCoveredMvPct); assertEquals(2, r.coveredPositionCount)
        assertEquals(2, r.accounts.size); assertEquals(2, r.accounts[1].positionCount)
    }
    @Test fun radarScoreBandsMatchTheWeb() {
        assertEquals(Color(0xFF22C55E), RadarColors.score(72)); assertEquals(Color(0xFF84CC16), RadarColors.score(58)); assertEquals(Color(0xFFEAB308), RadarColors.score(45)); assertEquals(Color(0xFFF97316), RadarColors.score(32)); assertEquals(Color(0xFFEF4444), RadarColors.score(31))
        assertEquals(Color(0xFF6366F1), RadarColors.bucket("index_fund")); assertEquals(Color(0xFF64748B), RadarColors.bucket("unknown")); assertEquals(Color(0xFF15803D), RadarColors.bucket("strong_buy"))
    }

    // ================= connections =================
    @Test fun credentialFlagsKeepNoSecrets() {
        val f = AppJson.decodeFromString(CredentialFlags.serializer(), fixture("credentials.json"))
        assertTrue(f.t212Invest && f.t212Isa && !f.t212Sipp && f.gemini && !f.openai && f.snaptrade)
        val again = AppJson.encodeToString(CredentialFlags.serializer(), f); assertFalse(again.contains("abcd") || again.contains("AIza") || again.contains("****"))
    }
    @Test fun t212AlertsListOnlyAccountsNeedingNewKeys() {
        val s = SyncStatus(accounts = mapOf("invest" to T212Account(true, false, null), "isa" to T212Account(true, true, "401 unauthorised"), "sipp" to T212Account(false, true, null)))
        assertEquals(listOf("Stocks & Shares ISA" to "401 unauthorised", "SIPP" to "Trading 212 rejected or expired these API keys."), ConnectionsText.t212Alerts(s)); assertTrue(ConnectionsText.t212Alerts(null).isEmpty())
    }
    @Test fun snaptradeStateText() {
        assertNull(ConnectionsText.snaptradeState(null)); assertNull(ConnectionsText.snaptradeState(SnaptradeStatus(configured = false)))
        assertTrue(ConnectionsText.snaptradeState(SnaptradeStatus(configured = true, authFailed = true))!!.contains("401"))
        assertTrue(ConnectionsText.snaptradeState(SnaptradeStatus(configured = true, needsReconnect = true))!!.contains("reconnecting"))
        assertEquals("No broker linked yet.", ConnectionsText.snaptradeState(SnaptradeStatus(configured = true, needsBrokerLink = true)))
        assertEquals("2 active connections.", ConnectionsText.snaptradeState(SnaptradeStatus(configured = true, activeConnections = 2))); assertEquals("1 active connection.", ConnectionsText.snaptradeState(SnaptradeStatus(configured = true, activeConnections = 1)))
    }
    @Test fun aiLabels() { assertEquals("OpenAI", ConnectionsText.aiProviderLabel(CredentialFlags(aiProvider = "OpenAI"))); assertEquals("Gemini", ConnectionsText.aiProviderLabel(CredentialFlags())); assertEquals("Assistants API", ConnectionsText.aiAgentLabel("assistants")); assertNull(ConnectionsText.aiAgentLabel(null)) }

    private fun routeConnections() {
        env.route("GET", "/api/portfolio/credentials") { env.ok(fixture("credentials.json")) }
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{"isa":{"configured":true,"needs_reconnect":true,"last_error":"401"}}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true,"snaptrade_active_connections_count":2}""") }
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        env.route("GET", "/api/snaptrade/connections") { env.ok("""[{"id":"c1","disabled":false,"broker_name":"AJ Bell","type":"read"},{"id":"c2","disabled":true,"broker_name":"Robinhood","type":"read"}]""") }
    }
    @Test fun connectionsViewModelLoadsEverything() {
        routeConnections(); val vm = ConnectionsViewModel(env.repo); vm.load()
        val d = await(vm.ui) { it.data != null }.data!!
        assertTrue(d.flags.snaptrade); assertEquals(2, d.connections.size); assertEquals("connected", d.monzo!!.status); assertEquals(1, ConnectionsText.t212Alerts(d.sync).size)
    }
    @Test fun onePartFailingDoesNotHideTheRest() {
        routeConnections(); env.route("GET", "/api/snaptrade/connections") { env.ok("down", 500) }; env.route("GET", "/api/monzo/status") { env.ok("down", 500) }
        val vm = ConnectionsViewModel(env.repo); vm.load(); val d = await(vm.ui) { it.data != null }.data!!
        assertTrue(d.connections.isEmpty()); assertNull(d.monzo); assertTrue(d.flags.gemini)
    }
    @Test fun credentialFailureShowsAnError() { routeConnections(); env.route("GET", "/api/portfolio/credentials") { env.ok("down", 500) }; val vm = ConnectionsViewModel(env.repo); vm.load(); assertNotNull(await(vm.ui) { it.error != null }.error) }

    // ================= change password =================
    @Test fun passwordValidationMatchesTheWeb() {
        assertEquals("New password and confirmation do not match.", PasswordRules.validate("old", "newpass12", "different"))
        assertEquals("New password must be at least 8 characters.", PasswordRules.validate("old", "short", "short"))
        assertEquals("New password must be different from your current password.", PasswordRules.validate("samepass1", "samepass1", "samepass1"))
        assertEquals("Enter your current password.", PasswordRules.validate("", "newpass12", "newpass12")); assertNull(PasswordRules.validate("old", "newpass12", "newpass12"))
    }
    @Test fun invalidInputSendsNothing() { val vm = ChangePasswordViewModel(env.repo); vm.submit("old", "short", "short"); assertTrue(vm.ui.value.error!!.contains("at least 8")); assertEquals(0, env.requests.size) }
    @Test fun successSendsTheWebFieldsWithoutAnAuthToken() {
        env.route("POST", "/api/auth/replace-password") { env.ok("""{"ok":true}""") }
        val vm = ChangePasswordViewModel(env.repo); vm.submit("oldpass1", "newpass12", "newpass12")
        assertTrue(await(vm.ui) { it.done }.done)
        val req = recorded().first { it.method == "POST" }
        assertEquals("{\"username\":\"TestUser\",\"current_password\":\"oldpass1\",\"new_password\":\"newpass12\",\"new_password_confirm\":\"newpass12\"}", req.body.readUtf8())
        assertNull(req.getHeader("Authorization")); assertNull(req.getHeader("X-No-Auth"))
    }
    @Test fun aWrongCurrentPasswordDoesNotSignTheUserOut() {
        env.route("POST", "/api/auth/replace-password") { env.ok("""{"detail":"Login failure try again."}""", 401) }
        val vm = ChangePasswordViewModel(env.repo); vm.submit("wrongpass", "newpass12", "newpass12")
        assertEquals("Current password is incorrect.", await(vm.ui) { it.error != null }.error)
        assertTrue(env.session.current.loggedIn); assertFalse(env.session.current.expired)
    }
    @Test fun aLockedAccountMessageIsShown() {
        env.route("POST", "/api/auth/replace-password") { env.ok("""{"detail":"You are locked, wait for one day and try again"}""", 403) }
        val vm = ChangePasswordViewModel(env.repo); vm.submit("x1234567", "newpass12", "newpass12"); assertTrue(await(vm.ui) { it.error != null }.error!!.contains("locked"))
    }
    @Test fun finishEndsTheSessionAndWipesTheCache() {
        runBlocking { env.dao.put(com.rushify.mobile.data.CacheEntry("k", "v", 1)) }
        val vm = ChangePasswordViewModel(env.repo); runBlocking { vm.finish() }
        assertFalse(env.session.current.loggedIn); assertTrue(env.session.current.expired); assertTrue(env.dao.map.isEmpty())
    }

    // ================= sync rules =================
    @Test fun rateLimitDetectionAndCooldownLength() {
        listOf("HTTP 429", "Rate limit exceeded", "Too many requests", "throttled", "Temporarily unavailable").forEach { assertTrue(it, SyncRules.looksRateLimited(it)) }
        assertFalse(SyncRules.looksRateLimited("bad credentials")); assertFalse(SyncRules.looksRateLimited(null))
        assertEquals(1_000L + 10_000, SyncRules.cooldownFor("boom", 1_000).untilMs); assertEquals(1_000L + 600_000, SyncRules.cooldownFor("429 slow down", 1_000).untilMs); assertTrue(SyncRules.cooldownFor("429", 0).provider)
    }
    @Test fun staleAndEmptySyncErrorsGetFriendlyText() {
        assertEquals("Sync finished with an error.", SyncRules.errorText(null)); assertEquals("Sync did not complete (stale; task or server ended without finishing). Try again.", SyncRules.errorText("Sync did not complete (stale: see history)")); assertEquals("T212 down", SyncRules.errorText("T212 down"))
    }
    private fun ui(status: SyncStatus? = null, snap: SnaptradeStatus? = null, quota: com.rushify.mobile.data.Quota? = null, cd: Map<Broker, Cooldown> = emptyMap()) = SyncUi(status = status, snaptrade = snap, quota = quota, cooldowns = cd)
    private val okQuota = com.rushify.mobile.data.Quota(3, com.rushify.mobile.data.QuotaPart(1, 2, true), com.rushify.mobile.data.QuotaPart(1, 2, true))
    @Test fun disabledReasonFollowsTheWebPriority() {
        val now = 1000L
        assertNull(SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true), null, okQuota), now))
        assertEquals(SyncRules.T212_PROVIDER, SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true, t212NeedsReconnect = true), null, okQuota, mapOf(Broker.T212 to Cooldown(now + 5, true))), now))   // cooldown beats everything
        assertEquals(SyncRules.SHORT, SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true), null, okQuota, mapOf(Broker.T212 to Cooldown(now + 5, false))), now))
        assertNull(SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true), null, okQuota, mapOf(Broker.T212 to Cooldown(now - 1, true))), now))                          // expired cooldown
        assertTrue(SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true, t212NeedsReconnect = true), null, okQuota), now)!!.contains("expired"))
        assertTrue(SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = false), null, okQuota), now)!!.contains("Add Trading 212 keys"))
        assertTrue(SyncRules.disabledReason(Broker.T212, ui(SyncStatus(t212Configured = true), null, com.rushify.mobile.data.Quota(3, com.rushify.mobile.data.QuotaPart(3, 0, false), okQuota.snaptrade)), now)!!.contains("limit reached"))
    }
    @Test fun snapTradeDisabledReasons() {
        fun r(s: SnaptradeStatus) = SyncRules.disabledReason(Broker.SNAPTRADE, ui(null, s, okQuota), 0)
        assertNull(r(SnaptradeStatus(configured = true, activeConnections = 1)))
        assertTrue(r(SnaptradeStatus(configured = false))!!.contains("not fully configured")); assertTrue(r(SnaptradeStatus(configured = true, authFailed = true))!!.contains("expired"))
        assertTrue(r(SnaptradeStatus(configured = true, needsBrokerLink = true))!!.contains("connection portal")); assertTrue(r(SnaptradeStatus(configured = true, needsReconnect = true))!!.contains("reconnecting"))
    }

    // ================= sync view model =================
    private var now = 1_000_000L
    private fun routeSync() {
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true,"snaptrade_active_connections_count":1}""") }
        env.route("GET", "/api/sync/manual-quota") { env.ok(fixture("quota.json").replace("\"remaining\":0", "\"remaining\":2").replace("\"rate_limit_allows\":false", "\"rate_limit_allows\":true")) }
        env.route("GET", "/api/sync/history") { env.ok(fixture("sync_history.json")) }
        env.route("GET", "/api/sync/manual/t212-running") { env.ok("""{"t212_manual_in_progress":false,"active_sync_log_id":null}""") }
        env.route("GET", "/api/sync/manual/snaptrade-running") { env.ok("""{"snaptrade_manual_in_progress":false,"active_sync_log_id":null}""") }
    }
    private fun syncVm(): SyncViewModel { routeSync(); val vm = SyncViewModel(env.repo, 5) { now }; vm.refresh(); await(vm.ui) { it.quota != null }; return vm }

    @Test fun aFailedSyncStartsAShortCooldownThatBlocksAndThenReleases() {
        val vm = syncVm(); env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"Internal error"}""", 500) }
        vm.sync(Broker.T212); await(vm.ui) { it.cooldowns.containsKey(Broker.T212) }
        vm.sync(Broker.T212); Thread.sleep(150); assertEquals(1, env.count("POST", "/api/sync/manual"))        // held back
        assertEquals(SyncRules.SHORT, SyncRules.disabledReason(Broker.T212, vm.ui.value, now))
        now += 11_000; vm.sync(Broker.T212); Thread.sleep(300); assertEquals(2, env.count("POST", "/api/sync/manual"))
    }
    @Test fun aRateLimitingProviderHoldsTheButtonForTenMinutes() {
        val vm = syncVm(); env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"Trading 212 rate limit exceeded (429)"}""", 502) }
        vm.sync(Broker.T212); assertTrue(await(vm.ui) { it.cooldowns.containsKey(Broker.T212) }.cooldowns.getValue(Broker.T212).provider)
        now += 120_000; assertEquals(SyncRules.T212_PROVIDER, SyncRules.disabledReason(Broker.T212, vm.ui.value, now)); now += 500_000; assertNull(SyncRules.disabledReason(Broker.T212, vm.ui.value, now))
    }
    @Test fun aFailedFinishedLogAlsoStartsACooldownAndShowsTheServerText() {
        val vm = syncVm(); env.route("POST", "/api/snaptrade/sync/manual") { env.ok("""{"sync_log_id":9}""", 202) }
        env.route("GET", "/api/sync/log/9") { env.ok("""{"id":9,"kind":"snaptrade_manual","started_at":"x","finished_at":"2026-10-17T10:00:03Z","success":false,"error_message":"Sync did not complete (stale)"}""") }
        vm.sync(Broker.SNAPTRADE); val u = await(vm.ui) { it.message?.contains("failed") == true }
        assertTrue(u.message!!.contains("Sync did not complete (stale; task or server ended without finishing)")); assertTrue(u.cooldowns.containsKey(Broker.SNAPTRADE))
    }
    @Test fun aSuccessfulSyncStartsNoCooldown() {
        val vm = syncVm(); env.route("POST", "/api/sync/manual") { env.ok("""{"sync_log_id":5}""", 202) }
        env.route("GET", "/api/sync/log/5") { env.ok("""{"id":5,"kind":"manual","started_at":"x","finished_at":"2026-10-17T10:00:03Z","success":true}""") }
        vm.sync(Broker.T212); await(vm.ui) { it.message?.contains("finished") == true }; assertTrue(vm.ui.value.cooldowns.isEmpty())
    }
    @Test fun aSyncStartedEarlierIsPickedUpAgainOnOpen() {
        routeSync(); env.route("GET", "/api/sync/manual/t212-running") { env.ok("""{"t212_manual_in_progress":true,"active_sync_log_id":77}""") }
        var polls = 0
        env.route("GET", "/api/sync/log/77") { polls++; env.ok(if (polls < 3) """{"id":77,"kind":"manual","started_at":"x","finished_at":null,"success":false}""" else """{"id":77,"kind":"manual","started_at":"x","finished_at":"2026-10-17T10:00:09Z","success":true}""") }
        val vm = SyncViewModel(env.repo, 5) { now }; vm.load()
        await(vm.ui) { it.running[Broker.T212] == true }
        val done = await(vm.ui) { it.message?.contains("finished") == true }
        assertFalse(done.running[Broker.T212] == true); assertTrue(polls >= 3)
    }
    @Test fun nothingRunningMeansNothingResumed() { routeSync(); val vm = SyncViewModel(env.repo, 5) { now }; vm.load(); await(vm.ui) { it.quota != null }; Thread.sleep(200); assertTrue(vm.ui.value.running.values.none { it }) }

    @Test fun portalReconnectSendsTheAuthorizationIdAndHandsBackAnHttpsUrl() {
        val vm = syncVm(); env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"redirect_uri":"https://app.snaptrade.com/connect?x=1"}""") }
        vm.openPortal("auth-1"); assertEquals("https://app.snaptrade.com/connect?x=1", await(vm.ui) { it.portalUrl != null }.portalUrl)
        assertEquals("{\"reconnect_authorization_id\":\"auth-1\"}", recorded().first { it.method == "POST" }.body.readUtf8()); vm.portalOpened(); assertNull(vm.ui.value.portalUrl)
    }
    @Test fun portalForANewBrokerSendsAnEmptyObject() {
        val vm = syncVm(); env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"redirect_uri":"https://x.example/c"}""") }
        vm.openPortal(null); await(vm.ui) { it.portalUrl != null }; assertEquals("{}", recorded().first { it.method == "POST" }.body.readUtf8())
    }
    @Test fun portalRejectsNonHttpAddresses() {
        val vm = syncVm(); env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"redirect_uri":"javascript:alert(1)"}""") }
        vm.openPortal(null); val u = await(vm.ui) { !it.portalBusy && it.portalError != null }; assertNull(u.portalUrl); assertEquals("Could not open SnapTrade portal", u.portalError)
    }
    @Test fun portalErrorsAreShown() { val vm = syncVm(); env.route("POST", "/api/snaptrade/portal-url") { env.ok("""{"detail":"SnapTrade is not configured"}""", 400) }; vm.openPortal(null); assertTrue(await(vm.ui) { it.portalError != null }.portalError!!.isNotBlank()) }

    // ================= AI =================
    @Test fun aiErrorMapping() {
        assertTrue(AiErrors.forThrown("HTTP 503 Service Unavailable", false, "Gemini").contains("temporarily busy")); assertTrue(AiErrors.forThrown("model is overloaded: high demand", false, null).contains("temporarily busy"))
        assertTrue(AiErrors.forThrown("google_search_retrieval is not supported", true, "Gemini").contains("Online augmentation (beta) isn't available"))
        assertFalse(AiErrors.forThrown("google_search_retrieval is not supported", false, "Gemini").contains("Online augmentation"))              // only when online mode is on
        assertEquals("Add your OpenAI API key in the web app under Settings → Keys (and select the matching provider).", AiErrors.forThrown("API key missing", false, "OpenAI"))
        assertEquals("Couldn't reach the AI service. Check your connection and try again.", AiErrors.forThrown(null, false, null)); assertEquals("Something specific", AiErrors.forThrown("Something specific", false, null))
    }
    @Test fun openAiFailureTextDetection() {
        listOf("OpenAI assistant error: boom", "OpenAI request failed: 429", "You exceeded your current quota", "Error code: rate_limit_exceeded", "invalid_api_key supplied").forEach { assertTrue(it, AiErrors.isOpenAiFailureText(it)) }
        assertFalse(AiErrors.isOpenAiFailureText("Your portfolio is 44% technology.")); assertFalse(AiErrors.isOpenAiFailureText("  "))
        assertTrue(AiErrors.openAiBanner("Error: quota exceeded, check your plan and billing").contains("Chat agent (tools)")); assertEquals(901, AiErrors.openAiBanner("z".repeat(2000)).length)
    }
    @Test fun anOpenAiErrorInsideANormalReplyBecomesAnErrorBannerNotAnAnswer() {
        env.route("GET", "/api/portfolio/credentials") { env.ok("""{"gemini_configured":false,"openai_configured":true,"ai_provider":"openai"}""") }
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"OpenAI request failed: You exceeded your current quota, please check your plan and billing."}""") }
        val vm = AiViewModel(env.repo); await(vm.ui) { it.keyReady == true }
        vm.send("hello"); val u = await(vm.ui) { it.error != null }
        assertTrue(u.error!!.contains("OpenAI blocked this request")); assertEquals(listOf("user", "assistant"), u.messages.map { it.role }); assertEquals(AiErrors.OPENAI_FAILED_BUBBLE, u.messages[1].text); assertFalse(u.suggestOnline)
    }
    @Test fun clearChatEmptiesTheThreadButKeepsTheProviderInfo() {
        env.route("GET", "/api/portfolio/credentials") { env.ok("""{"gemini_configured":true,"ai_provider":"gemini"}""") }; env.route("POST", "/api/ai/insight") { env.ok("""{"text":"hi"}""") }
        val vm = AiViewModel(env.repo); await(vm.ui) { it.keyReady == true }; vm.send("q"); await(vm.ui) { it.messages.size == 2 }
        vm.clear(); assertTrue(vm.ui.value.messages.isEmpty()); assertEquals("Gemini", vm.ui.value.providerLabel); assertEquals(true, vm.ui.value.keyReady)
    }

    // ================= Monzo =================
    @Test fun monzoStatusWordingMatchesTheWeb() {
        assertEquals("Connected", statusLabel("connected")); assertEquals("Waiting for approval in the Monzo app", statusLabel("pending_approval")); assertEquals("Needs reconnect", statusLabel("needs_reconnect")); assertEquals("Keys saved — not connected", statusLabel("not_connected")); assertEquals("Keys saved — not connected", statusLabel(null))
        val m = com.rushify.mobile.data.MonzoStatus(configured = true, status = "connected")
        assertEquals("Not set", monzoLabel(null)); assertEquals("Not set", monzoLabel(m.copy(configured = false))); assertEquals("Connected", monzoLabel(m))
        assertTrue(monzoCanSync(m)); assertTrue(monzoCanSync(m.copy(status = "pending_approval"))); assertFalse(monzoCanSync(m.copy(status = "needs_reconnect"))); assertFalse(monzoCanSync(m.copy(configured = false))); assertFalse(monzoCanSync(null))
    }
    @Test fun monzoSyncMessages() {
        assertEquals("Synced 3 accounts and 2 pots.", MonzoMessages.synced(com.rushify.mobile.data.MonzoSyncCounts(3, 2))); assertEquals("Synced 1 account and 1 pot.", MonzoMessages.synced(com.rushify.mobile.data.MonzoSyncCounts(1, 1)))
        assertEquals(MonzoMessages.UPDATED, MonzoMessages.of(RushifyRepository.MonzoSyncOutcome(null)))
        assertEquals(MonzoMessages.WAITING, MonzoMessages.of(RushifyRepository.MonzoSyncOutcome(com.rushify.mobile.data.MonzoSyncResult(status = "pending_approval"))))
    }
    @Test fun monzoSyncWithCountsRefreshesTheDashboard() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }; env.route("POST", "/api/monzo/sync") { env.ok("""{"monzo_configured":true,"status":"connected","sync":{"accounts":2,"pots":3}}""") }
        val vm = MonzoViewModel(env.repo); val refreshed = java.util.concurrent.atomic.AtomicBoolean(); vm.syncNow { refreshed.set(true) }
        assertEquals("Synced 2 accounts and 3 pots.", await(vm.sync) { it.message != null }.message); run { val end = System.nanoTime() + 6_000_000_000L; while (!refreshed.get() && System.nanoTime() < end) Thread.sleep(10) }; assertTrue(refreshed.get())
    }
    @Test fun monzoStillWaitingForApprovalDoesNotRefreshAnything() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }; env.route("POST", "/api/monzo/sync") { env.ok("""{"monzo_configured":true,"status":"pending_approval","sync":null}""") }
        val vm = MonzoViewModel(env.repo); val refreshed = java.util.concurrent.atomic.AtomicBoolean(); vm.syncNow { refreshed.set(true) }
        assertEquals(MonzoMessages.WAITING, await(vm.sync) { it.message != null }.message); assertFalse(refreshed.get())
    }
    @Test fun monzoRefreshOnOpenOnlyActsWhenTheServerRefreshed() {
        env.route("POST", "/api/monzo/refresh-if-stale") { env.ok("""{"refreshed":true}""") }; assertTrue(runBlocking { env.repo.monzoRefreshIfStale() })
        env.route("POST", "/api/monzo/refresh-if-stale") { env.ok("""{"refreshed":false}""") }; assertFalse(runBlocking { env.repo.monzoRefreshIfStale() })
        env.route("POST", "/api/monzo/refresh-if-stale") { env.ok("down", 500) }; assertFalse(runBlocking { env.repo.monzoRefreshIfStale() })
    }

    // ================= token-free calls =================
    @Test fun otherCallsStillCarryTheTokenAndDoExpireTheSessionOn401() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok("""{"detail":"Not authenticated"}""", 401) }
        runCatching { runBlocking { env.repo.call { env.repo.api.dashboard() } } }
        assertEquals("Bearer jwt-token", recorded().first { it.path!!.contains("dashboard") }.getHeader("Authorization")); assertFalse(env.session.current.loggedIn)
    }
}
