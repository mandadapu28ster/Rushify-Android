package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.ManualHoldingOut
import com.rushify.mobile.data.RollupLine
import com.rushify.mobile.data.SymbolHit
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val TODAY = LocalDate.of(2026, 10, 31)
private fun ok(b: Built) = (b as? Built.Ok)?.body ?: error("expected Ok but was $b")
private fun err(b: Built) = (b as? Built.Err)?.message ?: error("expected Err but was $b")
private fun JsonObject.s(k: String) = (get(k) as? JsonPrimitive)?.content

/** Phase 5: the web's manual-holding forms and sector override, as pure rules. */
class ManualRulesTest {
    @Test fun listedCreateBodyMatchesTheWeb() {
        val b = ok(ManualRules.listedCreate(" VOD.L ", "Vodafone", "1,000", " Hargreaves ", "isa", "2024-05-01", "£1,234.50", TODAY))
        assertEquals(setOf("asset_kind", "symbol", "label", "quantity", "broker_name", "purchase_date", "cost_base_gbp", "account_kind"), b.keys)
        assertEquals("listed", b.s("asset_kind")); assertEquals("VOD.L", b.s("symbol")); assertEquals("Vodafone", b.s("label")); assertEquals("1000", b.s("quantity"))
        assertEquals("Hargreaves", b.s("broker_name")); assertEquals("2024-05-01", b.s("purchase_date")); assertEquals("1234.5", b.s("cost_base_gbp")); assertEquals("isa", b.s("account_kind"))
        assertFalse("currency is never sent for listed lots", b.containsKey("currency"))
    }
    @Test fun blankLabelIsNull() { assertEquals(JsonNull, ok(ManualRules.listedCreate("A", "  ", "1", "B", "sipp", "2024-01-01", "10", TODAY))["label"]) }
    @Test fun listedCreateMissingFields() {
        listOf(
            arrayOf("", "", "1", "B", "sipp", "2024-01-01", "10"), arrayOf("A", "", "", "B", "sipp", "2024-01-01", "10"), arrayOf("A", "", "0", "B", "sipp", "2024-01-01", "10"),
            arrayOf("A", "", "1", " ", "sipp", "2024-01-01", "10"), arrayOf("A", "", "1", "B", "", "2024-01-01", "10"), arrayOf("A", "", "1", "B", "sipp", "", "10"), arrayOf("A", "", "1", "B", "sipp", "2024-01-01", "x"),
        ).forEach { a -> assertEquals(ManualRules.MSG_LISTED_MISSING, err(ManualRules.listedCreate(a[0], a[1], a[2], a[3], a[4], a[5], a[6], TODAY))) }
    }
    @Test fun listedCreatePurchaseDateWindow() {
        assertEquals(ManualRules.MSG_LISTED_DATE, err(ManualRules.listedCreate("A", "", "1", "B", "sipp", "2026-11-01", "10", TODAY)))
        assertEquals(ManualRules.MSG_LISTED_DATE, err(ManualRules.listedCreate("A", "", "1", "B", "sipp", "1996-10-30", "10", TODAY)))
        assertEquals(ManualRules.MSG_LISTED_DATE, err(ManualRules.listedCreate("A", "", "1", "B", "sipp", "31/10/2025", "10", TODAY)))
        ok(ManualRules.listedCreate("A", "", "1", "B", "sipp", "2026-10-31", "10", TODAY)); ok(ManualRules.listedCreate("A", "", "1", "B", "sipp", "1996-10-31", "10", TODAY))
    }
    @Test fun accountKindOptions() {
        assertEquals(listOf("sipp", "isa", "invest", "lisa", "jisa", "jsipp"), ManualRules.LISTED_CREATE_KINDS.map { it.first })
        assertTrue(ManualRules.LISTED_EDIT_KINDS.any { it.first == "unspecified" })
        assertFalse("the backend rejects cash_isa on edit", ManualRules.LISTED_EDIT_KINDS.any { it.first == "cash_isa" })
    }
    @Test fun listedEditUnspecifiedSendsNullKind() {
        val b = ok(ManualRules.listedEdit("L", "5", "Broker", "unspecified", "2024-01-01", "100", TODAY))
        assertEquals(JsonNull, b["account_kind"]); assertEquals("5", b.s("quantity")); assertEquals("100", b.s("cost_base_gbp")); assertFalse(b.containsKey("symbol"))
        assertEquals("sipp", ok(ManualRules.listedEdit("", "5", "Broker", "sipp", "2024-01-01", "100", TODAY)).s("account_kind"))
    }
    @Test fun listedEditMessages() {
        assertEquals(ManualRules.MSG_EDIT_BASIS, err(ManualRules.listedEdit("", "5", "B", "sipp", "", "100", TODAY)))
        assertEquals(ManualRules.MSG_EDIT_BASIS, err(ManualRules.listedEdit("", "5", "B", "sipp", "2024-01-01", "", TODAY)))
        assertEquals(ManualRules.MSG_BROKER, err(ManualRules.listedEdit("", "5", " ", "sipp", "2024-01-01", "100", TODAY)))
        assertEquals("quantity must be greater than zero", err(ManualRules.listedEdit("", "0", "B", "sipp", "2024-01-01", "100", TODAY)))
    }

    private fun held(s: String) = BigDecimal(s)
    @Test fun sellBody() {
        val b = ok(ManualRules.sell(held("10"), "2024-01-01", "4", "2025-06-01", "12.5", TODAY))
        assertEquals(setOf("quantity_sold", "executed_at", "price_per_share_gbp"), b.keys); assertEquals("4", b.s("quantity_sold")); assertEquals("2025-06-01", b.s("executed_at")); assertEquals("12.5", b.s("price_per_share_gbp"))
        assertEquals("10", ok(ManualRules.sell(held("10"), "2024-01-01", "10", "2025-06-01", "1", TODAY)).s("quantity_sold"))
    }
    @Test fun sellMessages() {
        fun s(h: BigDecimal?, pd: String?, q: String, d: String, p: String) = err(ManualRules.sell(h, pd, q, d, p, TODAY))
        assertEquals("Nothing left to sell — this position is already closed", s(null, "2024-01-01", "1", "2025-01-01", "1"))
        assertEquals("Nothing left to sell — this position is already closed", s(BigDecimal.ZERO, "2024-01-01", "1", "2025-01-01", "1"))
        assertTrue(s(held("5"), null, "1", "2025-01-01", "1").startsWith("Purchase date required before recording a sale"))
        assertEquals("Quantity sold is required.", s(held("5"), "2024-01-01", "", "2025-01-01", "1"))
        assertEquals("Enter a valid quantity sold (greater than zero).", s(held("5"), "2024-01-01", "0", "2025-01-01", "1"))
        assertEquals("Enter a valid quantity sold (greater than zero).", s(held("5"), "2024-01-01", "abc", "2025-01-01", "1"))
        assertEquals("Sold quantity cannot exceed current holding (5).", s(held("5"), "2024-01-01", "6", "2025-01-01", "1"))
        assertEquals("GBP price per share is required.", s(held("5"), "2024-01-01", "1", "2025-01-01", ""))
        assertEquals("Enter the GBP price per share (must be greater than zero).", s(held("5"), "2024-01-01", "1", "2025-01-01", "0"))
        assertEquals("Sell date is required.", s(held("5"), "2024-01-01", "1", "", "1"))
        assertEquals("Sell date cannot be before the lot purchase date", s(held("5"), "2024-06-01", "1", "2024-05-31", "1"))
        assertEquals("Sell date cannot be in the future.", s(held("5"), "2024-01-01", "1", "2026-11-01", "1"))
    }

    private fun base(k: ManualKind) = ManualRules.NonListed(kind = k, openedDate = "2020-01-01", openingValue = "100000")
    @Test fun propertyCreate() {
        val b = ok(ManualRules.nonListedCreate(base(ManualKind.Property).copy(label = "Home", homeValue = "350,000", mortgage = "120000", notes = " n "), TODAY))
        assertEquals("real_estate", b.s("asset_kind")); assertEquals("Home", b.s("label")); assertEquals("350000", b.s("home_value")); assertEquals("120000", b.s("mortgage_balance"))
        assertEquals("GBP", b.s("currency")); assertEquals("n", b.s("notes")); assertEquals("2020-01-01", b.s("opened_date")); assertEquals("100000", b.s("opening_value_gbp"))
        assertFalse(b.containsKey("exit_date")); assertFalse(b.containsKey("other_value"))
    }
    @Test fun cashCreateUsesBankAndAccountType() {
        val b = ok(ManualRules.nonListedCreate(base(ManualKind.Cash).copy(broker = "Nationwide", cashBalance = "5000"), TODAY))
        assertEquals("cash", b.s("asset_kind")); assertEquals("Nationwide", b.s("broker_name")); assertEquals("Savings account", b.s("label")); assertEquals("5000", b.s("cash_balance"))
        val other = ok(ManualRules.nonListedCreate(base(ManualKind.Cash).copy(broker = "N", cashType = "Other", cashTypeOther = " Cash box ", cashBalance = "1"), TODAY))
        assertEquals("Cash box", other.s("label"))
        assertEquals("Choose an account type, or enter a description if you selected Other.", err(ManualRules.nonListedCreate(base(ManualKind.Cash).copy(broker = "N", cashType = "Other", cashBalance = "1"), TODAY)))
        assertEquals("Enter the bank or building society name.", err(ManualRules.nonListedCreate(base(ManualKind.Cash).copy(cashBalance = "1"), TODAY)))
        assertEquals(10, ManualRules.CASH_TYPES.size)
    }
    @Test fun otherCreateAndRequired() {
        val b = ok(ManualRules.nonListedCreate(base(ManualKind.Other).copy(label = "Car", otherValue = "8000"), TODAY)); assertEquals("other", b.s("asset_kind")); assertEquals("8000", b.s("other_value"))
        val req = "Fill in all required fields for this entry type."
        assertEquals(req, err(ManualRules.nonListedCreate(base(ManualKind.Other).copy(otherValue = "8000"), TODAY)))
        assertEquals(req, err(ManualRules.nonListedCreate(base(ManualKind.Property).copy(label = "H", homeValue = "1"), TODAY)))
        assertEquals(req, err(ManualRules.nonListedCreate(base(ManualKind.Other).copy(label = "Car", otherValue = "1", openedDate = ""), TODAY)))
    }
    @Test fun createDatesAndOpening() {
        val f = base(ManualKind.Other).copy(label = "Car", otherValue = "1")
        assertEquals("Opened date cannot be in the future.", err(ManualRules.nonListedCreate(f.copy(openedDate = "2026-11-01"), TODAY)))
        assertEquals("Enter a valid non-negative opening amount in GBP.", err(ManualRules.nonListedCreate(f.copy(openingValue = "-1"), TODAY)))
        assertEquals("Enter a valid non-negative opening amount in GBP.", err(ManualRules.nonListedCreate(f.copy(openingValue = "abc"), TODAY)))
    }
    @Test fun createExitRules() {
        val f = base(ManualKind.Other).copy(label = "Car", otherValue = "1")
        val b = ok(ManualRules.nonListedCreate(f.copy(exitDate = "2024-01-01", exitValue = "5000"), TODAY)); assertEquals("2024-01-01", b.s("exit_date")); assertEquals("5000", b.s("exit_value_gbp"))
        val both = "Exit date and exit amount (GBP) must both be set or both left blank."
        assertEquals(both, err(ManualRules.nonListedCreate(f.copy(exitDate = "2024-01-01"), TODAY))); assertEquals(both, err(ManualRules.nonListedCreate(f.copy(exitValue = "5"), TODAY)))
        assertEquals("Exit date cannot be in the future.", err(ManualRules.nonListedCreate(f.copy(exitDate = "2026-12-01", exitValue = "5"), TODAY)))
        assertEquals("Exit date must be on or after the opened date.", err(ManualRules.nonListedCreate(f.copy(exitDate = "2019-12-31", exitValue = "5"), TODAY)))
        assertEquals("Enter a valid non-negative exit amount in GBP.", err(ManualRules.nonListedCreate(f.copy(exitDate = "2024-01-01", exitValue = "-5"), TODAY)))
    }

    private fun edit(f: ManualRules.NonListed, exited: Boolean = false, chart: Boolean = true, mode: String = "update", update: String = "") = ManualRules.nonListedEdit(ManualRules.NonListedEdit(f, exited, chart, mode, update), TODAY)
    @Test fun editUpdateModeSendsNewValueAsOfDate() {
        val b = ok(edit(base(ManualKind.Property).copy(label = "Home", homeValue = "360000", mortgage = ""), update = "2026-10-01"))
        assertEquals("update", b.s("save_mode")); assertEquals("2026-10-01", b.s("update_date")); assertEquals("360000", b.s("home_value")); assertEquals("0", b.s("mortgage_balance"))
        assertTrue((b["show_on_wealth_chart"] as JsonPrimitive).boolean); assertFalse(b.containsKey("exit_date"))
        assertFalse("blank update date is omitted so the server uses today", ok(edit(base(ManualKind.Other).copy(otherValue = "5")))["update_date"] != null)
    }
    @Test fun editExitModeClosesTheLine() {
        val b = ok(edit(base(ManualKind.Other).copy(label = "Car", otherValue = "5", exitDate = "2026-01-01", exitValue = "4000"), mode = "exit"))
        assertEquals("exit", b.s("save_mode")); assertEquals("2026-01-01", b.s("exit_date")); assertEquals("4000", b.s("exit_value_gbp")); assertFalse(b.containsKey("update_date"))
        assertEquals("exit_date and exit_value_gbp are required when updating property, cash savings, and other manual assets", err(edit(base(ManualKind.Other).copy(otherValue = "5"), mode = "exit")))
    }
    @Test fun exitedLineOnlyChangesNotesCurrencyAndChartFlag() {
        val b = ok(edit(base(ManualKind.Cash).copy(currency = "gbp", notes = "closed"), exited = true, chart = false))
        assertEquals(setOf("currency", "notes", "show_on_wealth_chart", "save_mode"), b.keys); assertEquals("GBP", b.s("currency")); assertEquals("update", b.s("save_mode")); assertFalse((b["show_on_wealth_chart"] as JsonPrimitive).boolean)
    }
    @Test fun editDateRules() {
        val f = base(ManualKind.Other).copy(otherValue = "5")
        assertEquals("update_date cannot be in the future", err(edit(f, update = "2026-11-02")))
        assertEquals("update_date must be on or after opened_date", err(edit(f, update = "2019-01-01")))
        assertEquals("Opened date and opening amount (GBP) are required.", err(edit(f.copy(openingValue = ""))))
        assertEquals("Opened date cannot be in the future.", err(edit(f.copy(openedDate = "2027-01-01"))))
        assertEquals("Enter a valid non-negative opening amount in GBP.", err(edit(f.copy(openingValue = "-3"))))
    }
    @Test fun cashEditAllowsNegativeBalance() { assertEquals("-50", ok(edit(base(ManualKind.Cash).copy(broker = "Bank", cashBalance = "-50"))).s("cash_balance")) }

    @Test fun formPrefillFromServerRow() {
        val m = ManualHoldingOut(id = 3, assetKind = "cash", label = "Cash ISA", brokerName = "Nationwide", cashBalance = BigDecimal("5000.00"), openedDate = "2021-02-03", openingValueGbp = BigDecimal("4000.0"), currency = "GBP")
        val f = ManualRules.formFrom(m)
        assertEquals(ManualKind.Cash, f.kind); assertEquals("Cash ISA", f.cashType); assertEquals("", f.cashTypeOther); assertEquals("5000", f.cashBalance); assertEquals("4000", f.openingValue); assertEquals("Nationwide", f.broker)
        val custom = ManualRules.formFrom(m.copy(label = "Offset pot")); assertEquals("Other", custom.cashType); assertEquals("Offset pot", custom.cashTypeOther)
        val prop = ManualRules.formFrom(ManualHoldingOut(assetKind = "real_estate", label = "Flat", homeValue = BigDecimal("1E+5"))); assertEquals("Flat", prop.label); assertEquals("100000", prop.homeValue)
    }
    @Test fun sectorBodyAndTicker() {
        val b = ok(ManualRules.sectorBody(" AAPL ", " Technology ")); assertEquals("AAPL", b.s("ticker")); assertEquals("Technology", b.s("sector"))
        assertEquals("Invalid ticker", err(ManualRules.sectorBody(" ", "Energy"))); assertEquals("Sector is required", err(ManualRules.sectorBody("X", " ")))
        assertEquals(15, ManualRules.SECTORS.size); assertEquals("ETFs & funds", ManualRules.SECTORS.first())
        assertEquals("AAPL", ManualRules.sectorTicker(com.rushify.mobile.data.HoldingRow(ticker = "AAPL_EQ"), null))
    }
    @Test fun whichRowsAreEditable() {
        val listed = com.rushify.mobile.data.HoldingRow(ticker = "VOD.L", source = "manual", manualHoldingId = 5, assetKind = "listed")
        assertTrue(ManualRules.isListedManual(listed)); assertFalse(ManualRules.isNonListedManual(listed))
        val prop = listed.copy(assetKind = "real_estate"); assertTrue(ManualRules.isNonListedManual(prop)); assertFalse(ManualRules.isListedManual(prop))
        assertFalse(ManualRules.isListedManual(listed.copy(source = "trading212"))); assertFalse(ManualRules.isListedManual(listed.copy(manualHoldingId = null)))
    }
    @Test fun sanitizeDecimal() { assertEquals("1234.5", ManualRules.sanitizeDecimal(" £1,234.5 ")); assertNull(ManualRules.parseDec("")); assertNull(ManualRules.parseDec("abc")); assertEquals(BigDecimal("12.5"), ManualRules.parseDec("12.5")) }
}

/** The write calls: exact method, path and body on the wire, success handling, and failures that must change nothing. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: ManualViewModel
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn(); vm = ManualViewModel(env.repo).also { it.today = { TODAY } } }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }
    private fun <T> await(flow: StateFlow<T>, cond: (T) -> Boolean): T = runBlocking { withTimeout(6_000) { flow.first(cond) } }
    private fun next() = env.server.takeRequest(2, TimeUnit.SECONDS)!!
    private fun noRequest() = assertNull(env.server.takeRequest(300, TimeUnit.MILLISECONDS))
    private fun body(r: okhttp3.mockwebserver.RecordedRequest) = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    private fun valid() = ManualRules.listedCreate("VOD.L", "Vodafone", "10", "HL", "isa", "2024-01-01", "100", TODAY)

    @Test fun createPostsToManualAndRefreshes() {
        env.json("""{"id":1}""", 201); var done = 0
        vm.create(valid()) { done++ }
        val ui = await(vm.ui) { it.message != null }
        val r = next()
        assertEquals("POST", r.method); assertEquals("/api/portfolio/manual", r.path); assertEquals("Bearer jwt-token", r.getHeader("Authorization"))
        assertEquals("VOD.L", body(r).s("symbol")); assertEquals(1, done); assertFalse(ui.busy); assertNull(ui.error)
    }
    @Test fun createAlsoAcceptsEmpty200() { env.server.enqueue(MockResponse().setResponseCode(200)); var done = 0; vm.create(valid()) { done++ }; await(vm.ui) { it.message != null }; assertEquals(1, done) }
    @Test fun invalidFormNeverHitsTheServer() {
        var done = 0; vm.create(ManualRules.listedCreate("", "", "", "", "", "", "", TODAY)) { done++ }
        assertEquals(ManualRules.MSG_LISTED_MISSING, vm.ui.value.error); assertEquals(0, done); noRequest()
    }
    @Test fun serverRejectionShowsTheServersMessageAndChangesNothing() {
        env.json("""{"detail":[{"msg":"quantity must be greater than zero for listed investments"}]}""", 422); var done = 0
        vm.create(valid()) { done++ }
        val ui = await(vm.ui) { it.error != null }
        assertEquals("quantity must be greater than zero for listed investments", ui.error); assertEquals(0, done); assertNull(ui.message); assertFalse(ui.busy)
    }
    @Test fun stringDetailIsShown() {
        env.json("""{"detail":"Sell date cannot be before the lot purchase date"}""", 400)
        vm.sell(4, ManualRules.sell(BigDecimal("10"), "2024-01-01", "1", "2025-01-01", "5", TODAY)) {}
        assertEquals("Sell date cannot be before the lot purchase date", await(vm.ui) { it.error != null }.error)
    }
    @Test fun editLotPatchesTheLot() {
        env.server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setHeader("Content-Type", "application/json")); var done = 0
        vm.editListed(9, ManualRules.listedEdit("L", "5", "HL", "unspecified", "2024-01-01", "50", TODAY)) { done++ }
        await(vm.ui) { it.message != null }
        val r = next(); assertEquals("PATCH", r.method); assertEquals("/api/portfolio/manual/9", r.path); assertEquals(JsonNull, body(r)["account_kind"]); assertEquals(1, done)
    }
    @Test fun sellPostsToSellEndpoint() {
        env.json("""{"holding":{"id":9}}""")
        vm.sell(9, ManualRules.sell(BigDecimal("10"), "2024-01-01", "4", "2025-06-01", "12.5", TODAY)) {}
        await(vm.ui) { it.message == "Sale recorded." }
        val r = next(); assertEquals("POST", r.method); assertEquals("/api/portfolio/manual/9/sell", r.path); assertEquals("4", body(r).s("quantity_sold"))
    }
    @Test fun removeDeletesAndToleratesNoContent() {
        env.server.enqueue(MockResponse().setResponseCode(204)); var done = 0
        vm.remove(12) { done++ }
        await(vm.ui) { it.message == "Position removed." }
        val r = next(); assertEquals("DELETE", r.method); assertEquals("/api/portfolio/manual/12", r.path); assertEquals(1, done)
    }
    @Test fun sectorOverridePutsTickerAndSector() {
        env.json("""{"ticker":"AAPL","sector":"Technology"}""")
        vm.saveSector(ManualRules.sectorBody("AAPL", "Technology")) {}
        await(vm.ui) { it.message == "Sector saved." }
        val r = next(); assertEquals("PUT", r.method); assertEquals("/api/portfolio/sector-override", r.path); val b = body(r); assertEquals("AAPL", b.s("ticker")); assertEquals("Technology", b.s("sector"))
    }
    @Test fun editValuesReloadsDetailsAfterSaving() {
        env.json("{}"); env.json("""[{"id":3,"asset_kind":"cash","label":"Savings account","broker_name":"Bank","cash_balance":"6000"}]""")
        vm.editValues(3, ManualRules.nonListedEdit(ManualRules.NonListedEdit(ManualRules.NonListed(ManualKind.Cash, broker = "Bank", cashBalance = "6000", openedDate = "2020-01-01", openingValue = "100"), false, true), TODAY)) {}
        val d = await(vm.details) { it.containsKey(3) }
        assertEquals(BigDecimal("6000"), d[3]!!.cashBalance); assertEquals("PATCH", next().method); assertEquals("GET", next().method)
    }
    @Test fun sessionExpiryOnAWriteIsReportedNotCrashed() {
        env.json("""{"detail":"Not authenticated"}""", 401)
        vm.create(valid()) {}
        assertEquals("Session expired. Please sign in again.", await(vm.ui) { it.error != null }.error)
    }
    @Test fun networkFailureLeavesNoSuccessMessage() {
        env.server.shutdown(); var done = 0
        vm.create(valid()) { done++ }
        val ui = await(vm.ui) { it.error != null }; assertEquals(0, done); assertNull(ui.message)
        env = TestEnv()
    }
    @Test fun secondTapWhileBusyIsIgnored() {
        env.server.enqueue(MockResponse().setResponseCode(201).setBody("{}").setHeader("Content-Type", "application/json").setBodyDelay(400, TimeUnit.MILLISECONDS))
        vm.create(valid()) {}; vm.create(valid()) {}
        await(vm.ui) { it.message != null }
        assertEquals(1, env.server.requestCount)
    }

    @Test fun symbolSearchWaitsForTwoCharactersAndDebounces() {
        env.json("""[{"symbol":"VOD.L","name":"Vodafone Group","exchange":"LSE","currency":"GBX"}]""")
        vm.search("v"); noRequest(); assertTrue(vm.hits.value.isEmpty())
        vm.search("vo"); vm.search("vod")
        val hits = await(vm.hits) { it.isNotEmpty() }
        assertEquals("VOD.L", hits[0].symbol); assertEquals("/api/portfolio/symbol-search?q=vod", next().path); assertEquals(1, env.server.requestCount)
    }
    @Test fun pickingAHitFillsSymbolThenUsesTheNormalisedOne() {
        env.json("""{"normalized_symbol":"VOD.L","last_price":"72.4","currency":"GBX"}""")
        val seen = mutableListOf<String>()
        vm.pick(SymbolHit(symbol = "VOD", name = "Vodafone")) { seen += it }
        await(vm.quote) { it != null }
        assertEquals(listOf("VOD", "VOD.L"), seen); assertEquals(BigDecimal("72.4"), vm.quote.value!!.lastPrice); assertEquals("/api/portfolio/symbol-quote?symbol=VOD", next().path)
    }
    @Test fun aFailedQuoteIsIgnored() {
        env.json("""{"detail":"nope"}""", 502); val seen = mutableListOf<String>()
        vm.pick(SymbolHit(symbol = "ZZZ")) { seen += it }
        runBlocking { kotlinx.coroutines.delay(400) }
        assertEquals(listOf("ZZZ"), seen); assertNull(vm.quote.value); assertNull(vm.ui.value.error)
    }
}
