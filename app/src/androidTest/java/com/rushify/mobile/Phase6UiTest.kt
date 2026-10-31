package com.rushify.mobile

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.MonzoViewModel
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.SyncScreen
import com.rushify.mobile.ui.SyncViewModel
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sync tab: Monzo sits with Trading 212 and SnapTrade, and there is no history list. */
@RunWith(AndroidJUnit4::class)
class Phase6SyncTabUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv
    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun has(t: String, sub: Boolean = false) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { has(t, sub) }
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun syncTab(monzoJson: String = fixture("monzo.json")) {
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true,"snaptrade_active_connections_count":1}""") }
        env.route("GET", "/api/sync/manual-quota") { env.ok(fixture("quota.json")) }
        env.route("GET", "/api/sync/history") { env.ok(fixture("sync_history.json")) }
        env.route("GET", "/api/sync/manual/t212-running") { env.ok("""{"t212_manual_in_progress":false,"active_sync_log_id":null}""") }
        env.route("GET", "/api/sync/manual/snaptrade-running") { env.ok("""{"snaptrade_manual_in_progress":false,"active_sync_log_id":null}""") }
        env.route("GET", "/api/monzo/status") { env.ok(monzoJson) }
        val vm = SyncViewModel(env.repo, 20); val monzo = MonzoViewModel(env.repo)
        rule.setContent { RushifyTheme { SyncScreen(vm, monzo, onMonzoSynced = {}) } }
    }

    @Test fun monzoHasItsOwnCardNextToTheBrokers() {
        syncTab()
        waitText("Trading 212"); waitText("SnapTrade"); waitText("Connected")
        rule.waitUntil(10_000) { tagged("broker_MONZO") && tagged("broker_T212") && tagged("broker_SNAPTRADE") }
    }

    @Test fun monzoSyncRunsFromTheSyncTab() {
        env.route("POST", "/api/monzo/sync") { MockResponse().setResponseCode(204) }
        syncTab()
        rule.waitUntil(10_000) { tagged("monzo_sync") }
        rule.onNodeWithTag("monzo_sync").performScrollTo().performClick()
        waitText("Monzo balances updated.")
        assert(env.count("POST", "/api/monzo/sync") == 1)
    }

    @Test fun monzoNotSetUpHasNoSyncButtonAndPointsToTheWeb() {
        syncTab("""{"monzo_configured":false}""")
        waitText("Not set"); waitText("Connect Monzo in the web app", sub = true)
        assert(!tagged("monzo_sync"))
    }

    @Test fun thereIsNoSyncHistoryOnTheTab() {
        syncTab()
        waitText("Manual syncs are limited to 3 per rolling 24 hours for Trading 212 and for SnapTrade. Monzo has no daily limit.")
        Thread.sleep(500)
        assert(!has("History") && !has("No syncs yet.") && !has("(scheduled)", sub = true) && !has("(manual)", sub = true))
    }
}
