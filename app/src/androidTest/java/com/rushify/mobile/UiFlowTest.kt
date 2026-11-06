package com.rushify.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.AuthViewModel
import com.rushify.mobile.ui.Broker
import com.rushify.mobile.ui.ConnectScreen
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.HoldingsScreen
import com.rushify.mobile.ui.HomeScreen
import com.rushify.mobile.ui.LocalHideBalances
import com.rushify.mobile.ui.LoginScreen
import com.rushify.mobile.ui.RushifyTheme
import com.rushify.mobile.ui.SyncScreen
import com.rushify.mobile.ui.SyncViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv

    @Before fun setUp() { env = DeviceEnv() }
    @After fun tearDown() = env.close()

    private fun waitText(text: String, substring: Boolean = false) =
        rule.waitUntil(8_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun routeAll() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("dashboard.json")) }
        env.route("GET", "/api/sync/status") { env.ok("""{"t212_configured":true,"accounts":{}}""") }
        env.route("GET", "/api/snaptrade/status") { env.ok("""{"snaptrade_configured":true}""") }
        env.route("GET", "/api/sync/manual-quota") { env.ok(fixture("quota.json")) }
        env.route("GET", "/api/sync/history") { env.ok(fixture("sync_history.json")) }
        env.route("GET", "/api/portfolio/holdings-rollups") { env.ok("""{"lines":[],"accounts":[]}""") }
    }

    // ---- connect / login ----

    @Test fun invalidPortShowsErrorAndDoesNotCallServer() {
        val screenVm = AuthViewModel(env.repo)
        rule.setContent { RushifyTheme { ConnectScreen(screenVm, "") } }
        rule.onNodeWithTag("server_url").performTextInput("http://90.255.245.88:99999")
        rule.onNodeWithTag("connect").performClick()
        waitText("Port must be between 1 and 65535")
        assert(env.requests.isEmpty())
    }

    @Test fun httpAddressAsksForUnencryptedConfirmationFirst() {
        env.route("GET", "/api/health") { env.ok("""{"status":"ok","app":"rushify","version":"0.2.2"}""") }
        val screenVm = AuthViewModel(env.repo)
        rule.setContent { RushifyTheme { ConnectScreen(screenVm, "") } }
        rule.onNodeWithTag("server_url").performTextInput(env.baseUrl)
        rule.onNodeWithTag("connect").performClick()
        waitText("Traffic is not encrypted")
        assert(env.count("GET", "/api/health") == 0)
        rule.onNodeWithTag("accept_http").performClick()
        rule.waitUntil(8_000) { env.count("GET", "/api/health") == 1 }
    }

    @Test fun lockedAccountShowsMessageAndDisablesForm() {
        env.session.setServer(env.baseUrl)
        env.route("POST", "/api/auth/login") { env.ok("""{"detail":"You are locked, wait for one day and try again"}""", 403) }
        val screenVm = AuthViewModel(env.repo)
        rule.setContent { RushifyTheme { LoginScreen(screenVm, env.baseUrl, expired = false) } }
        rule.onNodeWithTag("username").performTextInput("someone")
        rule.onNodeWithTag("password").performTextInput("pw")
        rule.onNodeWithTag("sign_in").performClick()
        waitText("You are locked", substring = true)
        rule.onNodeWithTag("sign_in").assertIsNotEnabled()
        rule.onNodeWithTag("username").assertIsNotEnabled()
        assert(env.count("POST", "/api/auth/login") == 1)
    }

    @Test fun wrongPasswordShowsServerMessageAndFormStaysUsable() {
        env.session.setServer(env.baseUrl)
        env.route("POST", "/api/auth/login") { env.ok("""{"detail":"Login failure try again."}""", 401) }
        val screenVm = AuthViewModel(env.repo)
        rule.setContent { RushifyTheme { LoginScreen(screenVm, env.baseUrl, expired = false) } }
        rule.onNodeWithTag("username").performTextInput("someone")
        rule.onNodeWithTag("password").performTextInput("bad")
        rule.onNodeWithTag("sign_in").performClick()
        waitText("Login failure try again.")
        rule.onNodeWithTag("sign_in").assertIsEnabled()
        assert(env.count("POST", "/api/auth/login") == 1)   // no automatic retry
    }

    @Test fun httpServerShowsPersistentWarningOnLogin() {
        env.session.setServer(env.baseUrl)
        val screenVm = AuthViewModel(env.repo)
        rule.setContent { RushifyTheme { LoginScreen(screenVm, env.baseUrl, expired = true) } }
        waitText("Not encrypted (http)", substring = true)
        waitText("Your session expired", substring = true)
    }

    // ---- home / offline ----

    @Test fun homeShowsNetWorth() {
        env.signedIn(); routeAll()
        val screenVm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(screenVm) } }
        waitText("£101,450.50")
    }

    @Test fun hideBalancesMasksAmounts() {
        env.signedIn(); routeAll()
        val screenVm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { CompositionLocalProvider(LocalHideBalances provides true) { HomeScreen(screenVm) } } }
        waitText("••••")
        assert(rule.onAllNodesWithText("£101,450.50").fetchSemanticsNodes().isEmpty())
    }

    @Test fun offlineLaunchShowsSavedDataWithBanner() {
        env.signedIn(); routeAll()
        runBlocking { env.repo.dashboard() }                                 // seed the cache while online
        env.route("GET", "/api/portfolio/dashboard") { env.ok("down", 503) } // then the server goes away
        val screenVm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(screenVm) } }
        waitText("showing saved data", substring = true)
        waitText("£101,450.50")                                              // cached value still on screen
    }

    @Test fun coldStartOfflineShowsRetryNotBlankScreen() {
        env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok("down", 503) }
        val screenVm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(screenVm) } }
        waitText("Try again")
    }

    // ---- holdings ----

    @Test fun holdingsSearchAndDetailSheet() {
        env.signedIn(); routeAll()
        val screenVm = DashboardViewModel(env.repo)
        val rollupsVm = com.rushify.mobile.ui.RollupsViewModel(env.repo)
        rule.setContent { RushifyTheme { HoldingsScreen(screenVm, rollupsVm) } }
        waitText("Broker X · LISA · SnapTrade")
        rule.onNodeWithTag("holdings_search").performTextInput("alpha")
        waitText("AAA")
        rule.onNodeWithText("AAA").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Average price").fetchSemanticsNodes().isNotEmpty() }
        waitText("4.59%")            // dividend yield 0.0459 -> 4.59%
        waitText("$120.00")          // current price in the instrument currency
    }

    @Test fun emptyPortfolioShowsHelpfulState() {
        env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok("""{"base_currency":"GBP","rows":[],"net_worth_total_base":"0"}""") }
        env.route("GET", "/api/portfolio/holdings-rollups") { env.ok("""{"lines":[],"accounts":[]}""") }
        val screenVm = DashboardViewModel(env.repo)
        val rollupsVm = com.rushify.mobile.ui.RollupsViewModel(env.repo)
        rule.setContent { RushifyTheme { HoldingsScreen(screenVm, rollupsVm) } }
        waitText("No positions yet", substring = true)
    }

    // ---- sync ----

    @Test fun quotaExhaustedDisablesSyncButton() {
        env.signedIn(); routeAll()
        val screenVm = SyncViewModel(env.repo)
        rule.setContent { RushifyTheme { SyncScreen(screenVm) } }
        waitText("Manual sync limit reached", substring = true)
        rule.onNodeWithTag("sync_${Broker.SNAPTRADE.name}").assertIsNotEnabled()
        rule.onNodeWithTag("sync_${Broker.T212.name}").assertIsEnabled()
    }

    @Test fun syncAsksForConfirmationAndShowsLimitMessageOn429() {
        env.signedIn(); routeAll()
        env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"Daily manual sync limit reached"}""", 429) }
        val screenVm = SyncViewModel(env.repo)
        rule.setContent { RushifyTheme { SyncScreen(screenVm) } }
        waitText("2 of 3 manual syncs left today", substring = true)
        rule.onNodeWithTag("sync_${Broker.T212.name}").performClick()
        waitText("This uses one of your daily manual syncs", substring = true)
        assert(env.count("POST", "/api/sync/manual") == 0)   // nothing sent before confirming
        rule.onNodeWithTag("confirm_sync").performClick()
        waitText("limit reached", substring = true)
    }

    @Test fun syncConflict409ShowsAlreadyRunning() {
        env.signedIn(); routeAll()
        env.route("POST", "/api/sync/manual") { env.ok("""{"detail":"A sync is already running"}""", 409) }
        val screenVm = SyncViewModel(env.repo)
        rule.setContent { RushifyTheme { SyncScreen(screenVm) } }
        waitText("2 of 3 manual syncs left today", substring = true)
        rule.onNodeWithTag("sync_${Broker.T212.name}").performClick()
        rule.onNodeWithTag("confirm_sync").performClick()
        waitText("already running", substring = true)
    }
}
