package com.rushify.mobile.ui

import com.rushify.mobile.data.Dashboard
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryTest {
    private fun d(s: String) = BigDecimal(s)
    @Test fun investmentsSummaryListsWhatIsKnownInWebOrder() {
        val dash = Dashboard(totalMarketValueBase = d("10"), totalRealisedPnlBase = d("-1"), totalPnlBase = d("2"), dividendIncomeBrokerCashBase = d("3"), portfolioXirrAnnual = d("0.1"))
        assertEquals("Listed value: £10.00 · Realised P/L: -£1.00 · Unrealised P/L: +£2.00 · Dividend income: £3.00 · XIRR: +10.00%", InvestmentsSummary.of(dash, hide = false))
    }
    @Test fun missingFiguresAreSkippedAndEmptyIsADash() {
        assertEquals("Listed value: £5.00", InvestmentsSummary.of(Dashboard(totalMarketValueBase = d("5")), false))
        assertEquals("—", InvestmentsSummary.of(Dashboard(), false))
    }
    @Test fun dividendFallsBackToTheEstimate() { assertEquals("Dividend income: £4.00", InvestmentsSummary.of(Dashboard(dividendIncomeTotalBase = d("4")), false)) }
    @Test fun hiddenBalancesAreNotLeakedIntoTheSummary() {
        val s = InvestmentsSummary.of(Dashboard(totalMarketValueBase = d("12345.67"), totalPnlBase = d("99.5")), hide = true)
        assert(!s.contains("12,345") && !s.contains("99.5")) { s }
    }
}
