package com.rushify.mobile.data

import com.rushify.mobile.fixture
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test fun decimalsParseExactlyFromStrings() {
        val d = AppJson.decodeFromString(Dashboard.serializer(), fixture("dashboard.json"))
        assertEquals(BigDecimal("1000.50"), d.totalMarketValueBase)
        assertEquals(BigDecimal("2.50000000"), d.rows[0].quantity)
        assertEquals(BigDecimal("0.1079"), d.portfolioXirrAnnual)
    }

    @Test fun decimalsParseFromJsonNumbersWithoutDoubleDrift() {
        val d = AppJson.decodeFromString(Dashboard.serializer(), """{"total_cost_base": 0.1}""")
        assertEquals(BigDecimal("0.1"), d.totalCostBase)
    }

    @Test fun unknownFieldsAreIgnored() {
        val d = AppJson.decodeFromString(Dashboard.serializer(), fixture("dashboard.json"))
        assertEquals(2, d.tickerMapAttentionCount)
    }

    @Test fun nullsAndMissingFieldsAreTolerated() {
        val d = AppJson.decodeFromString(Dashboard.serializer(), "{}")
        assertNull(d.netWorthTotalBase)
        assertEquals(emptyList<HoldingRow>(), d.rows)
    }

    @Test fun radarAndQuotaAndHistory() {
        val r = AppJson.decodeFromString(Radar.serializer(), fixture("radar.json"))
        assertEquals(5, r.dimensions.size)
        assertEquals(BigDecimal("2.99"), r.healthScore)
        val q = AppJson.decodeFromString(Quota.serializer(), fixture("quota.json"))
        assertEquals(0, q.snaptrade.remaining)
        val h = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SyncLog.serializer()), fixture("sync_history.json"))
        assertEquals("T212 rate limited", h.last().errorMessage)
    }

    private fun <T> parse(ser: kotlinx.serialization.KSerializer<T>, name: String) = AppJson.decodeFromString(ser, fixture(name))

    @Test fun growthParsesDecimalsExactly() {
        val g = parse(Growth.serializer(), "growth.json")
        assertEquals(BigDecimal("-300.50"), g.periods[1].capitalGainBase)
        assertEquals("Alpha Corp", g.periods[1].purchaseEvents.single().label)
        assertEquals(BigDecimal("830.19"), g.periods.last().totalGrowthBase)
    }

    @Test fun growthComponentsAddUpToTotal() {
        parse(Growth.serializer(), "growth.json").periods.forEach { p ->
            assertEquals(p.label, p.totalGrowthBase, p.capitalGainBase!! + p.incomeBase!! + p.currencyGainBase!!)
        }
    }

    @Test fun benchmarksAndDividendsAndMonzoParse() {
        val b = parse(Benchmarks.serializer(), "benchmarks.json")
        assertEquals(BigDecimal("7.33"), b.portfolioReturnPct); assertNull(b.series.last().sp100Index)
        val d = parse(Dividends.serializer(), "dividends.json")
        assertEquals("forecast_forward", d.series.last().kind); assertEquals(listOf("AAA", "BBB"), d.symbols)
        val m = parse(MonzoStatus.serializer(), "monzo.json")
        assertEquals(1, m.skippedCount); assertEquals("connected", m.status)
    }

    @Test fun corporateActionDetailsAreFreeForm() {
        val list = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CorporateAction.serializer()), fixture("corporate.json"))
        assertEquals("stock_split", list[0].actionType); assertEquals(4, list[0].details.size); assertEquals(0, list[1].details.size)
    }

    @Test fun aiRequestEncodesSnakeCase() {
        val json = AppJson.encodeToString(AiRequest.serializer(), AiRequest("q", true, listOf(AiMessage("user", "a"))))
        assertEquals("""{"prompt":"q","use_online_augmentation":true,"conversation_history":[{"role":"user","content":"a"}]}""", json)
    }
}
