package com.rushify.mobile

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.data.Me
import com.rushify.mobile.ui.Dest
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.HomeScreen
import com.rushify.mobile.ui.MoreScreen
import com.rushify.mobile.ui.RushifyTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation tidy-up: Holdings and Sync live under More, and Monzo has no tile on Home and no entry in More. */
@RunWith(AndroidJUnit4::class)
class Phase7NavigationUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv
    @Before fun setUp() { env = DeviceEnv(); env.signedIn() }
    @After fun tearDown() = env.close()

    private fun has(t: String, sub: Boolean = false) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { has(t, sub) }
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun more(onOpen: (Dest) -> Unit = {}) {
        env.session.logout(); env.session.saveLogin("t", Me(id = 2, username = "someone", role = "user"))
        rule.setContent { RushifyTheme { MoreScreen(env.session, false, {}, false, {}, false, false, {}, true, {}, {}, onOpen, 0) } }
    }

    @Test fun moreListsHoldingsAndSyncButNotMonzo() {
        more()
        waitText("Holdings"); waitText("Sync"); waitText("Trading 212, SnapTrade and Monzo")
        assert(tagged("menu_Holdings") && tagged("menu_Sync"))
        assert(!tagged("menu_Monzo") && !has("Balances and sync"))
        assert(Dest.entries.none { it.name == "Monzo" })
    }

    @Test fun holdingsAndSyncRowsOpenTheirScreens() {
        var opened: Dest? = null
        more { opened = it }
        rule.waitUntil(10_000) { tagged("menu_Holdings") }
        rule.onNodeWithTag("menu_Holdings").performScrollTo().performClick(); rule.waitUntil(3_000) { opened == Dest.Holdings }
        rule.onNodeWithTag("menu_Sync").performScrollTo().performClick(); rule.waitUntil(3_000) { opened == Dest.Sync }
    }

    @Test fun homeHasNoMonzoOrBrokerSyncTile() {
        val dash = """{"base_currency":"GBP","net_worth_total_base":"500","rows":[
            {"ticker":"A","source":"t212","market_value_base":"1","instrument_name":"A"},
            {"ticker":"MZ1","instrument_name":"Monzo · Current account","source":"monzo","broker_label":"Monzo","asset_kind":"cash","market_value_base":"181.04","broker_account_ref":"monzo:acc_1"}]}"""
        env.route("GET", "/api/portfolio/dashboard") { env.ok(dash) }
        val vm = DashboardViewModel(env.repo)
        rule.setContent { RushifyTheme { HomeScreen(vm) } }
        waitText("Net worth"); waitText("Open charts →")
        Thread.sleep(400)
        assert(!has("Open Monzo →") && !has("Broker sync") && !has("Open Sync →"))
        assert(!tagged("section_monzo"))
    }
}
