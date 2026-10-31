package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.CorporateAction
import com.rushify.mobile.data.Dividends
import com.rushify.mobile.fixture
import java.math.BigDecimal
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewFeaturesViewModelTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn() }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun <T> await(flow: StateFlow<T>, cond: (T) -> Boolean): T = runBlocking { withTimeout(6_000) { flow.first(cond) } }
    private fun settle() = Thread.sleep(250)

    // ---- growth / benchmarks / dividends ----

    @Test fun growthLoadsAndCachesPerGranularity() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        val vm = GrowthViewModel(env.repo); vm.load()
        assertEquals(3, await(vm.state) { it.data != null }.data!!.value.periods.size)
        assertEquals(BigDecimal("830.19"), vm.state.value.data!!.value.periods.last().totalGrowthBase)
        runBlocking { assertNotNull(env.repo.growthCached("monthly", 12)); assertNull(env.repo.growthCached("yearly", 5)) }
    }

    @Test fun growthSendsGranularityAndCount() {
        env.route("GET", "/api/portfolio/growth") { env.ok(fixture("growth.json")) }
        val vm = GrowthViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select("yearly"); await(vm.state) { it.data != null && vm.granularity == "yearly" }
        settle()
        val paths = generateSequence { env.server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.path!! }.filter { it.contains("growth") }.toList()
        assertTrue(paths.toString(), paths.any { it.contains("granularity=monthly") && it.contains("count=12") })
        assertTrue(paths.toString(), paths.any { it.contains("granularity=yearly") && it.contains("count=5") })
    }

    @Test fun slowReplyForOldParameterNeverOverwritesNewSelection() {
        // Monthly answers slowly and yearly quickly; the late monthly body must be ignored.
        env.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path!!.contains("granularity=monthly")) env.ok(fixture("growth.json")).setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                else env.ok(fixture("growth.json").replace("\"granularity\":\"monthly\"", "\"granularity\":\"yearly\""))
        }
        val vm = GrowthViewModel(env.repo); vm.load(); vm.select("yearly")
        await(vm.state) { it.data?.value?.granularity == "yearly" }
        Thread.sleep(900)
        assertEquals("yearly", vm.state.value.data!!.value.granularity)
    }

    @Test fun benchmarksRangeSwitchReloads() {
        env.route("GET", "/api/portfolio/benchmarks") { env.ok(fixture("benchmarks.json")) }
        val vm = BenchmarksViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select(m = "week"); settle()
        assertEquals("week", vm.mode)
        runBlocking { assertNotNull(env.repo.benchmarksCached("week", 12)) }
    }

    @Test fun benchmarkNullIndexValuesAreTolerated() {
        env.route("GET", "/api/portfolio/benchmarks") { env.ok(fixture("benchmarks.json")) }
        val vm = BenchmarksViewModel(env.repo); vm.load()
        assertNull(await(vm.state) { it.data != null }.data!!.value.series.last().sp100Index)
    }

    @Test fun dividendsSymbolFilterIsSentAndCachedSeparately() {
        env.route("GET", "/api/portfolio/dividend-dashboard") { env.ok(fixture("dividends.json")) }
        val vm = DividendsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.select(sym = "AAA"); await(vm.state) { it.data != null && vm.symbol == "AAA" }; settle()
        runBlocking { assertNotNull(env.repo.dividendsCached("yearly", 5, "AAA")); assertNotNull(env.repo.dividendsCached("yearly", 5, null)) }
        val q = generateSequence { env.server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
        assertTrue(q.toString(), q.any { it.contains("symbol=AAA") })
    }

    @Test fun dividendMathSummaryAndContributors() {
        val d = AppJson.decodeFromString(Dividends.serializer(), fixture("dividends.json"))
        val s = DividendMath.summary(d)
        assertEquals(BigDecimal("182.83"), s.received); assertEquals(BigDecimal("436.10"), s.forecast)
        val top = DividendMath.topContributors(d.series[2], 5)
        assertEquals(listOf("AAA", "BBB"), top.map { it.first })
        assertEquals(BigDecimal("25.16"), top[0].second)          // actual 20.00 + forecast 5.16
        assertEquals("Next 6m", DividendMath.axisLabel(d.series[3])); assertEquals("Aug 2026", DividendMath.axisLabel(d.series[0]))
    }

    // ---- Monzo ----

    @Test fun monzoStatusLoads() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        val vm = MonzoViewModel(env.repo); vm.load()
        val m = await(vm.state) { it.data != null }.data!!.value
        assertTrue(m.configured); assertEquals("Connected", statusLabel(m.status)); assertEquals(4, m.balanceRows)
    }

    @Test fun monzoSyncSuccessRefreshesStatusAndDashboard() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        env.route("POST", "/api/monzo/sync") { MockResponse().setResponseCode(204) }   // empty body must not crash
        val vm = MonzoViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        var dashboardRefreshed = false
        vm.syncNow { dashboardRefreshed = true }
        val s = await(vm.sync) { it.message != null }
        assertFalse(s.message, s.failed); assertTrue(dashboardRefreshed)
        assertEquals(1, env.count("POST", "/api/monzo/sync"))
    }

    @Test fun monzoSyncWithJsonBodyAlsoSucceeds() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        env.route("POST", "/api/monzo/sync") { env.ok("""{"ok":true,"synced":4}""") }
        val vm = MonzoViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.syncNow()
        assertFalse(await(vm.sync) { it.message != null }.failed)
    }

    @Test fun monzoSyncFailureShowsError() {
        env.route("GET", "/api/monzo/status") { env.ok(fixture("monzo.json")) }
        env.route("POST", "/api/monzo/sync") { env.ok("""{"detail":"Monzo needs reauthorisation"}""", 502) }
        val vm = MonzoViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.syncNow()
        val s = await(vm.sync) { it.message != null }
        assertTrue(s.failed); assertTrue(s.message!!.contains("reauthorisation"))
    }


    // ---- Corporate actions ----

    @Test fun corporateListSplitsPendingFromResolved() {
        env.route("GET", "/api/corporate-actions") { env.ok(fixture("corporate.json")) }
        val vm = CorporateViewModel(env.repo); vm.load()
        val list = await(vm.state) { it.data != null }.data!!.value
        assertEquals(listOf(3), pending(list).map { it.id })
    }

    @Test fun emptyCorporateListIsFine() {
        env.route("GET", "/api/corporate-actions") { env.ok("[]") }
        val vm = CorporateViewModel(env.repo); vm.load()
        assertTrue(await(vm.state) { it.data != null }.data!!.value.isEmpty())
    }

    @Test fun confirmSendsPatchWithStatusAndReloads() {
        var served = 0
        env.route("GET", "/api/corporate-actions") { served++; env.ok(if (served == 1) fixture("corporate.json") else "[]") }
        env.route("PATCH", "/api/corporate-actions/3") { env.ok("""{"id":3,"status":"confirmed","action_type":"stock_split","ticker":"AAA","details":{},"created_at":"2026-10-17T09:00:00Z"}""") }
        val vm = CorporateViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.resolve(3, "confirmed")
        await(vm.resolve) { it.message == "Action confirmed." }
        await(vm.state) { it.data?.value?.isEmpty() == true }
        val patch = generateSequence { env.server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.first { it.method == "PATCH" }
        assertEquals("{\"status\":\"confirmed\"}", patch.body.readUtf8())
    }

    @Test fun dismissAndFailureAreReported() {
        env.route("GET", "/api/corporate-actions") { env.ok(fixture("corporate.json")) }
        env.route("PATCH", "/api/corporate-actions/3") { env.ok("""{"detail":"Not found"}""", 404) }
        val vm = CorporateViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.resolve(3, "dismissed")
        val r = await(vm.resolve) { it.message != null }
        assertTrue(r.failed); assertTrue(r.message!!.contains("Not found"))
        assertNull(r.busyId)
    }

    @Test fun detailLinesAreReadable() {
        val a = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CorporateAction.serializer()), fixture("corporate.json")).first()
        val lines = detailLines(a.details).toMap()
        assertEquals("10:1", lines["Ratio"]); assertEquals("2026-10-20", lines["Effective date"]); assertEquals("25", lines["New quantity"]); assertEquals("adr, manual_check", lines["Flags"])
        assertEquals("Stock split", actionTypeLabel("stock_split"))
    }

    @Test fun tickerMapsAttentionLoads() {
        env.route("GET", "/api/portfolio/ticker-maps") { env.ok(fixture("ticker_maps.json")) }
        val vm = TickerMapsViewModel(env.repo); vm.load()
        val items = await(vm.state) { it.data != null }.data!!.value.attentionItems
        assertEquals(2, items.size); assertNull(items[1].yahooSymbol); assertTrue(items[0].hasUserMap)
    }
}
