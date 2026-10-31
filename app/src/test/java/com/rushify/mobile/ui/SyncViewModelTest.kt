package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.fixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: SyncViewModel

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined) // real-time delay() for polling
        env = TestEnv(); env.signedIn()
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true}""") }
        env.route("GET", "/api/sync/manual-quota") { env.ok(fixture("quota.json")) }
        env.route("GET", "/api/sync/history") { env.ok(fixture("sync_history.json")) }
        vm = SyncViewModel(env.repo, pollDelayMs = 5)
    }

    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun await(cond: (SyncUi) -> Boolean) = runBlocking { withTimeout(5_000) { vm.ui.first(cond) } }

    @Test fun loadsQuotaStatusAndHistory() {
        vm.refresh()
        val s = await { it.quota != null && it.history.isNotEmpty() }
        assertEquals(0, s.quota!!.snaptrade.remaining)
        assertEquals(12, s.history.first().id)      // newest first
    }

    @Test fun quotaReached429ShowsMessageAndDoesNotRun() {
        env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"Daily manual sync limit reached"}""", 429) }
        vm.sync(Broker.T212)
        val s = await { it.message != null }
        assertTrue(s.message!!.contains("limit"))
        assertFalse(s.running[Broker.T212] == true)
    }

    @Test fun conflict409SwitchesToRunningStateWithoutASecondPost() {
        env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"A sync is already running"}""", 409) }
        vm.sync(Broker.T212)
        val s = await { it.message != null }
        assertTrue(s.message!!.contains("already running"))
        assertEquals(1, env.count("POST", "/api/sync/manual"))
    }

    @Test fun accepted202PollsUntilFinishedThenReportsSuccess() {
        env.route("POST", "/api/sync/manual") { env.ok("""{"sync_log_id":99}""", 202) }
        var polls = 0
        env.route("GET", "/api/sync/log/99") {
            polls++
            if (polls < 3) env.ok("""{"id":99,"kind":"manual","started_at":"2026-10-17T10:00:00Z","finished_at":null,"success":false,"error_message":null}""")
            else env.ok("""{"id":99,"kind":"manual","started_at":"2026-10-17T10:00:00Z","finished_at":"2026-10-17T10:00:09Z","success":true,"error_message":null}""")
        }
        vm.sync(Broker.T212)
        val s = await { it.message?.contains("finished") == true }
        assertTrue(polls >= 3)
        assertFalse(s.running[Broker.T212] == true)
    }

    @Test fun failedSyncSurfacesServerErrorText() {
        env.route("POST", "/api/snaptrade/sync/manual") { env.ok("""{"sync_log_id":5}""", 202) }
        env.route("GET", "/api/sync/log/5") { env.ok("""{"id":5,"kind":"snaptrade_manual","started_at":"2026-10-17T10:00:00Z","finished_at":"2026-10-17T10:00:03Z","success":false,"error_message":"Broker auth failed"}""") }
        vm.sync(Broker.SNAPTRADE)
        val s = await { it.message?.contains("failed") == true }
        assertTrue(s.message!!.contains("Broker auth failed"))
    }

    @Test fun sendsFullRefreshFalseByDefault() {
        env.route("POST", "/api/sync/manual") { env.ok("""{"sync_log_id":1}""", 202) }
        env.route("GET", "/api/sync/log/1") { env.ok("""{"id":1,"kind":"manual","started_at":"x","finished_at":"2026-10-17T10:00:03Z","success":true}""") }
        vm.sync(Broker.T212)
        await { it.message != null }
        val body = env.server.takeRequest().let { var r = it; while (r.method != "POST") r = env.server.takeRequest(); r.body.readUtf8() }
        assertTrue(body.contains("\"order_history_full_refresh\":false"))
    }

    @Test fun kindLabels() {
        assertEquals("Trading 212 (manual)", SyncKindLabel.of("manual"))
        assertEquals("Trading 212 (scheduled)", SyncKindLabel.of("scheduled"))
        assertEquals("SnapTrade (manual)", SyncKindLabel.of("snaptrade_manual"))
        assertEquals("SnapTrade (scheduled)", SyncKindLabel.of("snaptrade_scheduled"))
    }
}
