package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.Me
import com.rushify.mobile.data.MemoryKeyValueStore
import com.rushify.mobile.data.SessionManager
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 2: symbol mappings, dashboard alerts, admin reminder. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Phase2Tests {
    private lateinit var env: TestEnv

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn() }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun <T> await(flow: StateFlow<T>, cond: (T) -> Boolean): T = runBlocking { withTimeout(6_000) { flow.first(cond) } }
    private fun requests() = generateSequence { env.server.takeRequest(150, TimeUnit.MILLISECONDS) }.toList()

    private fun routeMaps(afterSaveBody: String = fixture("ticker_maps.json")) {
        env.route("GET", "/api/portfolio/ticker-maps") { env.ok(afterSaveBody) }
        env.route("PUT", "/api/portfolio/ticker-maps") { env.ok("""{"source_key":"ZZZ","yahoo_symbol":"ZZZ.L"}""") }
        env.route("DELETE", "/api/portfolio/ticker-maps") { MockResponse().setResponseCode(204) }
    }

    // ---------------- symbols to confirm ----------------

    @Test fun savingTrimsTheSymbolSendsPutAndRefreshesEverything() {
        routeMaps(); var changed = 0
        val vm = TickerMapsViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.save("ZZZ", "  zzz.l  ") { changed++ }
        val ui = await(vm.edit) { it.message != null }
        assertEquals(MAP_SAVED_INFO, ui.message); assertEquals(1, changed)
        val put = requests().first { it.method == "PUT" }
        assertEquals("{\"source_key\":\"ZZZ\",\"yahoo_symbol\":\"zzz.l\"}", put.body.readUtf8())
        assertTrue(env.count("GET", "/api/portfolio/ticker-maps") >= 2)           // list re-read after the save
    }

    @Test fun blankSymbolIsRejectedWithTheWebMessageAndNothingIsSent() {
        routeMaps()
        val vm = TickerMapsViewModel(env.repo)
        vm.save("ZZZ", "   ")
        assertEquals(MAP_EMPTY_ERROR, vm.edit.value.error)
        assertEquals(0, env.requests.size)
    }

    @Test fun removeSendsDeleteWithTheEncodedSourceKey() {
        routeMaps(); var changed = false
        val vm = TickerMapsViewModel(env.repo)
        vm.remove("A B/C&d") { changed = true }
        await(vm.edit) { it.message != null }
        assertTrue(changed)
        val del = requests().first { it.method == "DELETE" }
        assertTrue(del.path, del.path!!.contains("source_key=A%20B%2FC%26d") || del.path!!.contains("source_key=A+B%2FC%26d"))
    }

    @Test fun failedSaveShowsTheServerErrorAndDoesNotRefresh() {
        routeMaps(); env.route("PUT", "/api/portfolio/ticker-maps") { env.ok("""{"detail":"Symbol not recognised"}""", 422) }
        var changed = false
        val vm = TickerMapsViewModel(env.repo)
        vm.save("ZZZ", "NOPE") { changed = true }
        val ui = await(vm.edit) { it.error != null }
        assertEquals("Symbol not recognised", ui.error); assertFalse(changed); assertNull(ui.savingKey)
    }

    @Test fun onlyOneSaveAtATime() {
        routeMaps(); env.route("PUT", "/api/portfolio/ticker-maps") { Thread.sleep(300); env.ok("{}") }
        val vm = TickerMapsViewModel(env.repo)
        vm.save("A", "AAA"); vm.save("B", "BBB")
        await(vm.edit) { it.message != null }
        assertEquals(1, env.count("PUT", "/api/portfolio/ticker-maps"))
    }

    @Test fun messageCanBeCleared() {
        routeMaps(); val vm = TickerMapsViewModel(env.repo)
        vm.save("ZZZ", "ZZZ.L"); await(vm.edit) { it.message != null }
        vm.clearMessage(); assertNull(vm.edit.value.message)
    }

    // ---------------- dashboard alerts ----------------

    @Test fun dashboardCarriesTheIncompleteLotCount() =
        assertEquals(1, AppJson.decodeFromString(Dashboard.serializer(), fixture("holdings_dashboard.json")).manualBasisIncompleteCount)

    @Test fun dismissPostsOnceThenReloadsTheDashboard() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("dashboard.json")) }
        env.route("POST", "/api/portfolio/ticker-attention-banner/dismiss") { MockResponse().setResponseCode(204) }
        val vm = DashboardViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        val before = env.count("GET", "/api/portfolio/dashboard")
        vm.dismissTickerAttention(); vm.dismissTickerAttention()
        Thread.sleep(400)
        assertEquals(1, env.count("POST", "/api/portfolio/ticker-attention-banner/dismiss"))
        assertTrue(env.count("GET", "/api/portfolio/dashboard") > before)
        assertFalse(vm.dismiss.value.busy); assertNull(vm.dismiss.value.error)
    }

    @Test fun failedDismissShowsAnErrorAndKeepsTheData() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("dashboard.json")) }
        env.route("POST", "/api/portfolio/ticker-attention-banner/dismiss") { env.ok("""{"detail":"nope"}""", 500) }
        val vm = DashboardViewModel(env.repo); vm.load(); await(vm.state) { it.data != null }
        vm.dismissTickerAttention()
        assertEquals("nope", await(vm.dismiss) { it.error != null }.error)
        assertTrue(vm.state.value.data != null)
    }

    // ---------------- admin reminder / role ----------------

    private fun session() = SessionManager(MemoryKeyValueStore()).also { it.setServer("http://h:1") }

    @Test fun roleIsStoredAtSignInAndIsAdminIsCaseInsensitive() {
        val s = session()
        s.saveLogin("t", Me(id = 1, username = "admin", role = "admin")); assertTrue(s.current.isAdmin)
        s.logout(); assertFalse(s.current.isAdmin); assertNull(s.current.role)
        s.saveLogin("t", Me(id = 1, username = "x", role = "ADMIN")); assertTrue(s.current.isAdmin)
        s.saveLogin("t", Me(id = 2, username = "u", role = "user")); assertFalse(s.current.isAdmin)
    }

    @Test fun roleSurvivesRestartAndIsClearedByServerChangeAndExpiry() {
        val store = MemoryKeyValueStore(); val s = SessionManager(store); s.setServer("http://h:1")
        s.saveLogin("t", Me(id = 1, username = "admin", role = "admin"))
        assertTrue(SessionManager(store).current.isAdmin)
        s.setServer("https://other"); assertFalse(s.current.isAdmin); assertFalse(SessionManager(store).current.isAdmin)
    }

    @Test fun loginStoresTheRoleTheServerReports() {
        env.session.clearServer(); env.session.setServer(env.baseUrl)
        env.route("POST", "/api/auth/login") { env.ok("""{"access_token":"jwt"}""") }
        env.route("GET", "/api/auth/me") { env.ok("""{"id":1,"email":"a@b.c","username":"admin","role":"admin","base_currency":"GBP"}""") }
        val vm = AuthViewModel(env.repo); vm.login("admin", "pw")
        runBlocking { withTimeout(5_000) { env.session.state.first { it.loggedIn } } }
        assertTrue(env.session.current.isAdmin)
    }

    @Test fun reminderWordingMentionsWebOnlyAndPersonalAccount() {
        assertTrue(ADMIN_REMINDER_SHORT.contains("web-only")); assertTrue(ADMIN_REMINDER_SHORT.contains("personal account"))
    }
}
