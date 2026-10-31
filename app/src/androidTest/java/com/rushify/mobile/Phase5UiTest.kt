package com.rushify.mobile

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.ui.AddManualScreen
import com.rushify.mobile.ui.DashboardViewModel
import com.rushify.mobile.ui.HoldingsScreen
import com.rushify.mobile.ui.ManualViewModel
import com.rushify.mobile.ui.RollupsViewModel
import com.rushify.mobile.ui.RushifyTheme
import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val NOT_FOUND = "unexpected"

/** Phase 5: the write forms behind Holdings (edit lot, record sale, remove, sector, edit values). Mock server only. */
@RunWith(AndroidJUnit4::class)
class Phase5HoldingsWriteUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv
    private lateinit var manual: ManualViewModel

    @Before fun setUp() {
        env = DeviceEnv(); env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("holdings_dashboard.json")) }
        env.route("GET", "/api/portfolio/holdings-rollups") { env.ok(fixture("rollups.json")) }
        env.route("GET", "/api/portfolio/manual") { env.ok("""[{"id":1,"asset_kind":"real_estate","label":"Living Home","broker_name":null,"home_value":"350000","mortgage_balance":"120000","currency":"GBP","opened_date":"2020-01-01","opening_value_gbp":"300000","show_on_wealth_chart":true,"chart_eligible":true,"snapshot_count":6}]""") }
        val d = DashboardViewModel(env.repo); val r = RollupsViewModel(env.repo)
        manual = ManualViewModel(env.repo)
        rule.setContent { RushifyTheme { HoldingsScreen(d, r, manual, onChanged = { d.refresh(); r.refresh() }) } }
        rule.waitUntil(10_000) { found(hasText("Data as of", substring = true)) && env.count("GET", "/api/portfolio/holdings-rollups") >= 1 }
        Thread.sleep(400); rule.waitForIdle()
    }
    @After fun tearDown() = env.close()

    private val list get() = rule.onNodeWithTag("holdings_list")
    private fun found(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun reveal(m: SemanticsMatcher): Boolean {
        if (found(m)) return true
        repeat(40) { list.performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.5f) }; rule.waitForIdle(); if (found(m)) return true }
        repeat(40) { list.performTouchInput { swipeDown(startY = height * 0.5f, endY = height * 0.75f) }; rule.waitForIdle(); if (found(m)) return true }
        return false
    }
    private fun open(key: String) { rule.waitUntil(20_000) { reveal(hasTestTag("group_$key")) }; rule.onNodeWithTag("group_$key").performScrollTo().performClick(); rule.waitForIdle() }
    private fun tapText(t: String, sub: Boolean = false) { rule.waitUntil(20_000) { reveal(hasText(t, substring = sub)) }; rule.onAllNodes(hasText(t, substring = sub))[0].performScrollTo().performClick(); rule.waitForIdle() }
    private fun tap(tag: String) { rule.waitUntil(20_000) { found(hasTestTag(tag)) || reveal(hasTestTag(tag)) }; rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle() }
    private fun type(tag: String, v: String) { rule.waitUntil(10_000) { found(hasTestTag(tag)) }; val n = rule.onNodeWithTag(tag).performScrollTo(); n.performTextClearance(); n.performTextInput(v); rule.waitForIdle() }
    private fun seeText(t: String, sub: Boolean = false) = rule.waitUntil(10_000) { found(hasText(t, substring = sub)) }
    private fun gone(tag: String) = rule.waitUntil(10_000) { !found(hasTestTag(tag)) }
    private fun lastWrite(prefix: String) = env.written.last { it.startsWith(prefix) }

    private fun openMsftLot11() { open("listed__hargreaves"); tap("multi_MSFT"); tapText("Lot · 2025-01-02 · id 11", sub = true) }

    @Test fun manualLotOffersEditSellRemoveAndSector() {
        openMsftLot11()
        rule.waitUntil(10_000) { found(hasTestTag("act_edit")) }
        listOf("act_edit", "act_sell", "act_remove", "act_sector").forEach { assertTrue(it, found(hasTestTag(it))) }
        assertTrue("non-listed edit is not offered on a listed lot", !found(hasTestTag("act_values")))
    }

    @Test fun editLotSendsTheChangedFieldsAndRefreshesHoldings() {
        env.route("PATCH", "/api/portfolio/manual/11") { env.ok("{}") }
        openMsftLot11(); tap("act_edit")
        type("f_qty", "3"); type("f_cost", "75.5"); tap("kind_isa"); type("f_date", "2025-01-03")
        val before = env.count("GET", "/api/portfolio/dashboard")
        tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("PATCH /api/portfolio/manual/11") } }
        val w = lastWrite("PATCH /api/portfolio/manual/11")
        assertTrue(w, w.contains("\"quantity\":\"3\"") && w.contains("\"cost_base_gbp\":\"75.5\"") && w.contains("\"account_kind\":\"isa\"") && w.contains("\"purchase_date\":\"2025-01-03\""))
        assertTrue("symbol is not editable", !w.contains("\"symbol\""))
        rule.waitUntil(10_000) { env.count("GET", "/api/portfolio/dashboard") > before }
        gone("manual_form")
    }

    @Test fun editLotValidationMessageShowsAndNothingIsSent() {
        openMsftLot11(); tap("act_edit")
        type("f_date", ""); tap("form_save")
        rule.waitUntil(5_000) { found(hasText("Purchase date and total GBP cost are required.")) }
        assertTrue(env.written.none { it.startsWith("PATCH") })
    }

    @Test fun serverRefusalIsShownInTheFormWhichStaysOpen() {
        env.route("PATCH", "/api/portfolio/manual/11") { env.ok("""{"detail":"quantity must be greater than zero"}""", 400) }
        openMsftLot11(); tap("act_edit"); type("f_qty", "5"); tap("form_save")
        rule.waitUntil(10_000) { found(hasText("quantity must be greater than zero")) }
        assertTrue(found(hasTestTag("manual_form")))
    }

    @Test fun recordSaleValidatesThenPostsTheSale() {
        env.route("POST", "/api/portfolio/manual/11/sell") { env.ok("""{"holding":{"id":11}}""") }
        openMsftLot11(); tap("act_sell")
        type("f_qty", "9"); type("f_price", "10"); tap("form_save")
        rule.waitUntil(5_000) { found(hasText("Sold quantity cannot exceed current holding (1).")) }
        assertTrue(env.written.none { it.contains("/sell") })
        tap("sell_all"); tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("POST /api/portfolio/manual/11/sell") } }
        val w = lastWrite("POST /api/portfolio/manual/11/sell")
        assertTrue(w, w.contains("\"quantity_sold\":\"1\"") && w.contains("\"price_per_share_gbp\":\"10\"") && w.contains("\"executed_at\":\"${LocalDate.now()}\""))
        gone("manual_form")
    }

    @Test fun removeAsksFirstWithTheWebWordingThenDeletes() {
        env.route("DELETE", "/api/portfolio/manual/11") { MockResponse().setResponseCode(204) }
        openMsftLot11(); tap("act_remove")
        seeText("consider using “Record sale” first", sub = true)
        assertTrue(env.written.none { it.startsWith("DELETE") })
        tap("remove_ok")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("DELETE /api/portfolio/manual/11") } }
        gone("manual_form")
    }

    @Test fun cancelReturnsToTheDetailsWithoutWriting() {
        openMsftLot11(); tap("act_remove"); tap("form_cancel")
        rule.waitUntil(5_000) { found(hasTestTag("holding_detail")) }
        assertTrue(env.written.isEmpty())
    }

    @Test fun brokerPositionOffersOnlyTheSectorEditorAndSavesIt() {
        env.route("PUT", "/api/portfolio/sector-override") { env.ok("""{"ticker":"INFY","sector":"Technology"}""") }
        open("t212:isa"); tapText("Infosys")
        rule.waitUntil(10_000) { found(hasTestTag("act_sector")) }
        assertTrue(!found(hasTestTag("act_edit")) && !found(hasTestTag("act_remove")) && !found(hasTestTag("act_sell")))
        tap("act_sector"); tap("sector_Technology"); tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("PUT /api/portfolio/sector-override") } }
        val w = lastWrite("PUT /api/portfolio/sector-override")
        assertTrue(w, w.contains("\"sector\":\"Technology\"") && w.contains("\"ticker\":\"INFY\""))
    }

    @Test fun propertyEditLoadsStoredValuesAndSavesANewValueAsOfToday() {
        env.route("PATCH", "/api/portfolio/manual/1") { env.ok("{}") }
        open("property__manual entry"); tapText("Living Home")
        tap("act_values")
        rule.waitUntil(10_000) { found(hasTestTag("f_home")) }
        type("f_home", "360000"); tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("PATCH /api/portfolio/manual/1 ") } }
        val w = lastWrite("PATCH /api/portfolio/manual/1 ")
        assertTrue(w, w.contains("\"home_value\":\"360000\"") && w.contains("\"mortgage_balance\":\"120000\"") && w.contains("\"save_mode\":\"update\"") && w.contains("\"opened_date\":\"2020-01-01\"") && w.contains("\"show_on_wealth_chart\":true"))
        assertTrue(w, !w.contains("exit_date"))
    }

    @Test fun closingALineNeedsBothExitFields() {
        open("property__manual entry"); tapText("Living Home"); tap("act_values")
        rule.waitUntil(10_000) { found(hasTestTag("f_home")) }
        tap("mode_exit"); type("f_exit_date", "2026-01-01"); tap("form_save")
        rule.waitUntil(5_000) { found(hasText("exit_date and exit_value_gbp are required", substring = true)) }
        assertTrue(env.written.none { it.startsWith("PATCH") })
    }
}

/** Phase 5: More → Add holding. */
@RunWith(AndroidJUnit4::class)
class Phase5AddHoldingUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var env: DeviceEnv
    private lateinit var manual: ManualViewModel
    private var changed = 0

    @Before fun setUp() {
        env = DeviceEnv(); env.signedIn()
        env.routeP("GET", "/api/portfolio/symbol-search") { env.ok("""[{"symbol":"VOD","name":"Vodafone Group","exchange":"LSE","currency":"GBX"}]""") }
        env.routeP("GET", "/api/portfolio/symbol-quote") { env.ok("""{"normalized_symbol":"VOD.L","last_price":"72.4","currency":"GBX"}""") }
        env.route("POST", "/api/portfolio/manual") { env.ok("""{"id":99}""", 201) }
        manual = ManualViewModel(env.repo)
        rule.setContent { RushifyTheme { AddManualScreen(manual, onBack = {}, onChanged = { changed++ }) } }
        rule.waitForIdle()
    }
    @After fun tearDown() = env.close()

    private fun found(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) { rule.waitUntil(10_000) { found(hasTestTag(tag)) }; rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }
    private fun type(tag: String, v: String) { rule.waitUntil(10_000) { found(hasTestTag(tag)) }; val n = rule.onNodeWithTag(tag).performScrollTo(); n.performTextClearance(); n.performTextInput(v); rule.waitForIdle() }

    @Test fun incompleteStockFormShowsTheWebMessageAndSendsNothing() {
        tap("form_save")
        rule.waitUntil(5_000) { found(hasText("Fill in ticker, qty, broker, account type, purchase date, and total cost in GBP.")) }
        assertTrue(env.written.isEmpty())
    }

    @Test fun searchPickFillsSymbolAndQuoteThenSavesAStockLot() {
        type("f_search", "vod")
        rule.waitUntil(10_000) { found(hasTestTag("hit_VOD")) }
        tap("hit_VOD")
        rule.waitUntil(10_000) { found(hasTestTag("quote_info")) }
        rule.onNodeWithTag("f_symbol").assertTextEquals("Symbol", "VOD.L")
        type("f_qty", "100"); type("f_broker", "Hargreaves"); tap("kind_isa"); type("f_date", LocalDate.now().minusDays(30).toString()); type("f_cost", "£1,234.50")
        tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("POST /api/portfolio/manual ") } }
        val w = env.written.last { it.startsWith("POST /api/portfolio/manual ") }
        assertTrue(w, w.contains("\"asset_kind\":\"listed\"") && w.contains("\"symbol\":\"VOD.L\"") && w.contains("\"label\":\"Vodafone Group\"") && w.contains("\"quantity\":\"100\"") && w.contains("\"cost_base_gbp\":\"1234.5\"") && w.contains("\"account_kind\":\"isa\""))
        rule.waitUntil(10_000) { found(hasTestTag("add_message")) }
        assertEquals(1, changed)
    }

    @Test fun propertyIsCreatedWithOpeningValues() {
        tap("add_kind_real_estate")
        type("f_label", "Flat"); type("f_home", "250000"); type("f_mortgage", "100000"); type("f_opened", "2021-03-04"); type("f_opening", "200000")
        tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("POST /api/portfolio/manual ") } }
        val w = env.written.last { it.startsWith("POST /api/portfolio/manual ") }
        assertTrue(w, w.contains("\"asset_kind\":\"real_estate\"") && w.contains("\"home_value\":\"250000\"") && w.contains("\"mortgage_balance\":\"100000\"") && w.contains("\"opening_value_gbp\":\"200000\"") && w.contains("\"currency\":\"GBP\""))
    }

    @Test fun cashAccountSendsBankAsBrokerAndTypeAsLabel() {
        tap("add_kind_cash")
        type("f_bank", "Zopa"); tap("f_type_Cash ISA"); type("f_balance", "5000"); type("f_opened", "2022-01-01"); type("f_opening", "4000")
        tap("form_save")
        rule.waitUntil(10_000) { env.written.any { it.startsWith("POST /api/portfolio/manual ") } }
        val w = env.written.last { it.startsWith("POST /api/portfolio/manual ") }
        assertTrue(w, w.contains("\"broker_name\":\"Zopa\"") && w.contains("\"label\":\"Cash ISA\"") && w.contains("\"cash_balance\":\"5000\""))
    }

    @Test fun serverRejectionIsShownAndTheFormKeepsWhatWasTyped() {
        env.route("POST", "/api/portfolio/manual") { env.ok("""{"detail":[{"msg":"home_value is required for property"}]}""", 422) }
        tap("add_kind_real_estate")
        type("f_label", "Flat"); type("f_home", "1"); type("f_mortgage", "0"); type("f_opened", "2021-03-04"); type("f_opening", "1")
        tap("form_save")
        rule.waitUntil(10_000) { found(hasText("home_value is required for property")) }
        assertEquals(0, changed)
    }

    @Test fun futureOpenedDateIsRefusedLocally() {
        tap("add_kind_other")
        type("f_label", "Car"); type("f_other", "5000"); type("f_opened", LocalDate.now().plusDays(3).toString()); type("f_opening", "5000")
        tap("form_save")
        rule.waitUntil(5_000) { found(hasText("Opened date cannot be in the future.")) }
        assertTrue(env.written.isEmpty())
    }
}
