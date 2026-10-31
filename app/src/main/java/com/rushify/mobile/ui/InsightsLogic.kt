package com.rushify.mobile.ui

import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.DividendPoint
import com.rushify.mobile.data.GrowthBroker
import com.rushify.mobile.data.GrowthPeriod
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.ManualWealthSeries
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Pure logic behind the Home and Performance charts, ported from the web's DashboardPage, custodianChart.ts,
 * GrowthSection and WealthTrendSection so the same inputs give the same numbers. No Android types here.
 */

private val ZERO = BigDecimal.ZERO
private val HUNDRED = BigDecimal(100)

// ======================================================================================
// Net worth by broker
// ======================================================================================

data class NamedAmount(val account: String, val value: BigDecimal)

data class CustodianRow(
    val name: String,
    val equity: BigDecimal, val cash: BigDecimal, val property: BigDecimal, val other: BigDecimal,
    val listedByAccount: List<NamedAmount>, val cashByAccount: List<NamedAmount>,
    /** Listed equity split by account wrapper, keys: Invest, ISA, SIPP, LISA, JISA, JSIPP, Other. */
    val wrappers: Map<String, BigDecimal>,
) {
    val total: BigDecimal get() = equity + cash + property + other
}

object Custodian {
    val WRAPPER_KEYS = listOf("Invest", "ISA", "SIPP", "LISA", "JISA", "JSIPP", "Other")

    fun institutionLabelForReport(raw: String): String { val t = raw.trim(); return if (t == "Manual entry") "No institution name" else t.ifEmpty { "Other" } }

    private fun peelBrokerLabelSuffix(raw: String) = raw.replace(Regex("\\s*\\((?:csv|import)\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()

    private val WRAPPER_A = Regex("^(ISA|SIPP|Invest|GIA|Unspecified|LISA|JISA|JSIPP|TFSA|RRSP|RESP|RRIF|LIF|LIRA|401\\(k\\)|403\\(b\\)|457\\(b\\)|Roth IRA|Traditional IRA|SEP IRA|HSA|Margin|Joint|Trust|Corporate|UGMA|UTMA)$", RegexOption.IGNORE_CASE)
    private val WRAPPER_B = Regex("^(lifetime\\s*isa|cash\\s*isa|stocks\\s*(and|&)\\s*shares\\s*isa|flexible\\s*isa|help\\s*to\\s*buy\\s*isa|innovation\\s*isa|junior\\s*isa|junior\\s*sipp)$", RegexOption.IGNORE_CASE)

    private fun isWrapperSuffix(suffix: String): Boolean {
        val last = peelBrokerLabelSuffix(suffix)
        return last.isNotEmpty() && (WRAPPER_A.matches(last) || WRAPPER_B.matches(last))
    }

    private val TRAILING = listOf(
        "\\s+stocks\\s+and\\s+shares\\s+isa$", "\\s+cash\\s+isa$", "\\s+lifetime\\s+isa$", "\\s+flexible\\s+isa$", "\\s+junior\\s+isa$", "\\s+junior\\s+sipp$",
        "\\s+(invest|isa|sipp|lisa|jisa|jsipp|gia)$",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun peelTrailingWrapper(name: String): String {
        val t = name.trim()
        for (p in TRAILING) { val m = p.find(t); if (m != null && m.range.first > 0) return t.substring(0, m.range.first).trim() }
        return t
    }

    private fun institutionFromBrokerLabel(bl: String, source: String?): String {
        val parts = bl.split(Regex("\\s*·\\s*"))
        if (parts.size >= 2) {
            val last = parts.last().trim()
            if (isWrapperSuffix(last)) return institutionLabelForReport(parts.dropLast(1).joinToString(" · "))
            if (source == "snaptrade" || source == "csv") return institutionLabelForReport(parts.dropLast(1).joinToString(" · "))
        }
        return institutionLabelForReport(peelTrailingWrapper(bl))
    }

    /** One bar per broker/bank: "Trading 212 · ISA" -> "Trading 212". */
    fun institutionKey(r: HoldingRow): String {
        when (r.assetKind) { "real_estate" -> return "Property"; "other" -> return "Other assets" }
        val manual = (r.manualBrokerName ?: "").trim()
        if (manual.isNotEmpty()) {
            if (manual.contains("·")) return institutionFromBrokerLabel(manual, r.source)
            if (Regex("^Trading 212\\s+", RegexOption.IGNORE_CASE).containsMatchIn(manual)) return institutionLabelForReport(peelTrailingWrapper(manual))
            return institutionLabelForReport(manual)
        }
        val bl = (r.brokerLabel ?: "").trim()
        if (bl.isEmpty()) return institutionLabelForReport(when (r.source) { "t212" -> "Trading 212"; "snaptrade" -> "SnapTrade"; else -> r.source.trim().ifEmpty { "Other" } })
        return institutionFromBrokerLabel(bl, r.source)
    }

    /** Wrapper bucket for listed equity (tooltip detail and stack colours). */
    fun listedAccountLabel(r: HoldingRow): String {
        if (r.assetKind != null && r.assetKind != "listed") return ""
        return when (val k = (r.accountKind ?: "").trim().lowercase()) {
            "isa" -> "ISA"; "sipp" -> "SIPP"; "invest" -> "Invest"; "lisa" -> "LISA"; "jisa" -> "JISA"; "jsipp" -> "JSIPP"
            "lifetime_isa", "lifetimeisa" -> "LISA"
            "cash_isa", "cashisa", "flexible_isa", "flexibleisa", "help_to_buy_isa", "innovation_isa", "stocks_and_shares_isa", "stocksandsharesisa" -> "ISA"
            "junior_isa", "juniorisa" -> "JISA"; "junior_sipp", "juniorsipp" -> "JSIPP"
            else -> if (k.isEmpty()) "Other" else "Other"
        }
    }

    /** Cash bucket: broker cash by account kind; manual cash by the account part of "Bank · account type". */
    fun cashBucketLabel(r: HoldingRow): String {
        if (r.assetKind != "cash") return ""
        if (r.source != "manual") {
            val k = (r.accountKind ?: "").trim().lowercase()
            when (k) {
                "current" -> return "Current account"; "joint" -> return "Joint account"; "young" -> return "Young account"; "rewards" -> return "Rewards"
                "savings" -> return "Savings"; "pot" -> return "Pot"; "isa" -> return "ISA"; "cash_isa" -> return "Cash ISA"; "sipp" -> return "SIPP"
                "invest" -> return "Invest"; "lisa" -> return "LISA"; "jisa" -> return "JISA"; "jsipp" -> return "JSIPP"
            }
            if (k.isNotEmpty()) return k.replace('_', ' ').trim().ifEmpty { "Other" }
        }
        val raw = (r.instrumentName ?: "").trim()
        if (raw.isEmpty()) return "Other"
        val parts = raw.split(Regex("\\s*·\\s*"))
        return if (parts.size >= 2) parts.last().trim().ifEmpty { "Other" } else raw
    }

    fun isOpenManualListed(r: HoldingRow) =
        (r.assetKind == null || r.assetKind == "listed") && r.source == "manual" && r.manualHoldingId != null &&
            (r.quantity?.signum() ?: 0) > 0 && r.soldOutAt.isNullOrBlank()

    /** Quoted MV when positive; an open manual lot without a quote falls back to cost, then average price x quantity x FX. */
    fun marketValue(r: HoldingRow): BigDecimal {
        r.marketValueBase?.takeIf { it.signum() > 0 }?.let { return it }
        if (!isOpenManualListed(r)) return ZERO
        for (c in listOf(r.costBase, r.costBaseGbp)) if (c != null && c.signum() > 0) return c
        val qty = r.quantity ?: ZERO; val avg = r.avgPrice
        if (avg != null && avg.signum() > 0 && qty.signum() > 0) {
            val fx = r.fxToBase?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
            return avg.multiply(qty).multiply(fx)
        }
        return ZERO
    }

    fun build(rows: List<HoldingRow>): List<CustodianRow> {
        class Acc { var equity = ZERO; var cash = ZERO; var property = ZERO; var other = ZERO
            val listed = LinkedHashMap<String, BigDecimal>(); val cashBy = LinkedHashMap<String, BigDecimal>() }
        val map = LinkedHashMap<String, Acc>()
        for (r in rows) {
            val mv = marketValue(r)
            if (mv.signum() <= 0) continue
            val acc = map.getOrPut(institutionKey(r)) { Acc() }
            when (val ak = r.assetKind) {
                "cash" -> { acc.cash += mv; cashBucketLabel(r).takeIf { it.isNotEmpty() }?.let { acc.cashBy.merge(it, mv, BigDecimal::add) } }
                "real_estate" -> acc.property += mv
                "other" -> acc.other += mv
                else -> if (ak == null || ak == "listed") { acc.equity += mv; acc.listed.merge(listedAccountLabel(r), mv, BigDecimal::add) }
            }
        }
        fun detail(m: Map<String, BigDecimal>, total: BigDecimal): List<NamedAmount> {
            if (total.signum() <= 0) return emptyList()
            val list = m.filter { it.value.signum() > 0 }.map { NamedAmount(it.key, it.value) }.sortedByDescending { it.value }
            return if (list.size == 1 && list[0].account == "Other") emptyList() else list
        }
        return map.map { (name, a) ->
            val wrappers = WRAPPER_KEYS.associateWith { a.listed[it] ?: ZERO }.let { w ->
                if (a.listed.isEmpty() && a.equity.signum() > 0) w + ("Other" to a.equity) else w
            }
            CustodianRow(name, a.equity, a.cash, a.property, a.other, detail(a.listed, a.equity), detail(a.cashBy, a.cash), wrappers)
        }.sortedByDescending { it.total }
    }

    /** Chart label for the listed-equity custodian picker: brokers that hold (or once held) listed equity. */
    fun listedEquityBrokerLabels(rows: List<HoldingRow>): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in rows) {
            if (r.assetKind != null && r.assetKind != "listed") continue
            val qty = r.quantity ?: ZERO
            val soldOutManual = r.source == "manual" && (!r.soldOutAt.isNullOrBlank() || (qty.signum() <= 0 && r.manualHoldingId != null))
            val openManual = r.source == "manual" && r.manualHoldingId != null && qty.signum() > 0 && r.soldOutAt.isNullOrBlank()
            if (!soldOutManual && !openManual) { val mv = r.marketValueBase ?: continue; if (mv.signum() <= 0) continue }
            val lbl = (r.brokerLabel ?: "").trim()
            if (lbl.isNotEmpty()) seen += lbl
        }
        return seen.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}

// ======================================================================================
// Sector breakdown with drill-down, and position-size bars
// ======================================================================================

data class SectorHolding(val ticker: String, val name: String?, val value: BigDecimal, val cost: BigDecimal, val gain: BigDecimal, val shareOfSectorPct: BigDecimal)

data class SectorRow(
    val sector: String, val value: BigDecimal, val cost: BigDecimal, val gain: BigDecimal, val gainPct: BigDecimal?,
    val allocPct: BigDecimal, val holdings: List<SectorHolding>,
)

private fun BigDecimal.pctOf(total: BigDecimal): BigDecimal = if (total.signum() == 0) ZERO else multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)

object Sectors {
    /** Listed rows only, sector defaults to "Unknown", holdings merge per upper-cased ticker. */
    fun isListedPosition(r: HoldingRow) = HoldingsLogic.isListedAssetKind(r.assetKind) && r.source != "monzo" && !r.ticker.startsWith("CASH.")

    fun build(rows: List<HoldingRow>): List<SectorRow> {
        data class H(var name: String?, var value: BigDecimal, var cost: BigDecimal, var pnl: BigDecimal?)
        val bySector = LinkedHashMap<String, LinkedHashMap<String, H>>()
        for (r in rows) {
            if (!isListedPosition(r)) continue
            val v = r.marketValueBase ?: continue
            if (v.signum() <= 0) continue
            val sector = (r.sector ?: "").trim().ifEmpty { "Unknown" }
            val h = bySector.getOrPut(sector) { LinkedHashMap() }.getOrPut(HoldingsLogic.displayTicker(r, null).uppercase()) { H(r.instrumentName, ZERO, ZERO, null) }
            h.value += v; h.cost += r.costBase ?: ZERO
            r.pnlStockBase?.let { h.pnl = (h.pnl ?: ZERO) + it }
        }
        val total = bySector.values.sumOf { m -> m.values.sumOf { it.value } }
        return bySector.map { (sector, hs) ->
            val value = hs.values.sumOf { it.value }; val cost = hs.values.sumOf { it.cost }
            val gain = hs.values.sumOf { it.pnl ?: (it.value - it.cost) }
            SectorRow(sector, value, cost, gain, if (cost.signum() > 0) gain.pctOf(cost) else null, value.pctOf(total),
                hs.map { (t, h) -> SectorHolding(t, h.name, h.value, h.cost, h.pnl ?: (h.value - h.cost), h.value.pctOf(value)) }.sortedByDescending { it.value })
        }.sortedByDescending { it.value }
    }
}

data class PositionBar(val ticker: String, val name: String?, val value: BigDecimal, val cost: BigDecimal, val pnl: BigDecimal)

object PositionBars {
    /** One bar per upper-cased ticker (lots and brokers merged), largest market value first. P/L = value - cost, else the row's own P/L. */
    fun build(rows: List<HoldingRow>): List<PositionBar> {
        class A(var name: String?, var value: BigDecimal = ZERO, var cost: BigDecimal = ZERO, var pnl: BigDecimal = ZERO, var hasCost: Boolean = false)
        val by = LinkedHashMap<String, A>()
        for (r in rows) {
            if (!Sectors.isListedPosition(r)) continue
            val v = r.marketValueBase ?: continue
            if (v.signum() <= 0) continue
            val a = by.getOrPut(HoldingsLogic.displayTicker(r, null).uppercase()) { A(r.instrumentName) }
            a.value += v
            val c = r.costBase
            if (c != null) { a.cost += c; a.hasCost = true; a.pnl += v - c } else a.pnl += r.pnlStockBase ?: ZERO
        }
        return by.map { (t, a) -> PositionBar(t, a.name, a.value, a.cost, a.pnl) }.sortedWith(compareByDescending<PositionBar> { it.value }.thenBy { it.ticker })
    }
}

// ======================================================================================
// Growth attribution, benchmarks
// ======================================================================================

data class GrowthSummary(val total: BigDecimal, val price: BigDecimal, val interaction: BigDecimal, val income: BigDecimal, val currency: BigDecimal) {
    /** "12.3% of total", or a dash when the total is about zero. */
    fun share(part: BigDecimal): String =
        if (total.abs() < BigDecimal("0.000000001")) "—" else part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP).toPlainString() + "% of total"
}

object GrowthMath {
    fun summary(periods: List<GrowthPeriod>) = GrowthSummary(
        periods.sumOf { it.totalGrowthBase ?: ZERO }, periods.sumOf { it.priceGainBase ?: ZERO }, periods.sumOf { it.interactionGainBase ?: ZERO },
        periods.sumOf { it.incomeBase ?: ZERO }, periods.sumOf { it.currencyGainBase ?: ZERO },
    )

    fun stackSum(p: GrowthPeriod): BigDecimal = (p.priceGainBase ?: ZERO) + (p.interactionGainBase ?: ZERO) + (p.incomeBase ?: ZERO) + (p.currencyGainBase ?: ZERO)

    /** The tooltip only mentions the stack sum when rounding makes it differ from the period total by more than 0.02. */
    fun stackDiffers(p: GrowthPeriod): Boolean = (stackSum(p) - (p.totalGrowthBase ?: ZERO)).abs() > BigDecimal("0.02")

    const val INTERACTION_NOTE = "Each bar splits the period into pieces. Price is the effect of share-price moves while exchange rates are fixed at the start of that month or year. " +
        "Currency is the effect of exchange-rate moves while prices are fixed at the start. In real life both move together, so those two pieces do not add up to the exact change " +
        "in your portfolio value; the difference is Interaction. It is not extra money on top of the other parts; it is the remainder so the coloured segments add up to the total. " +
        "It often looks larger when you hold overseas stocks or assets in another currency."
}

object BenchmarkMath {
    /** Portfolio return minus index return, in percentage points; null when either is missing. */
    fun points(portfolio: BigDecimal?, index: BigDecimal?): BigDecimal? = if (portfolio == null || index == null) null else portfolio - index
    fun pointsText(v: BigDecimal?): String = if (v == null) "—" else (if (v.signum() >= 0) "+" else "") + v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " pp"
    fun verdict(v: BigDecimal?, indexName: String): String? = when {
        v == null -> null
        v.signum() >= 0 -> "Portfolio total return exceeded $indexName over the same dates."
        else -> "Portfolio total return trailed $indexName over the same dates."
    }
}

// ======================================================================================
// Non-equity manual gain
// ======================================================================================

object NonEquityGain {
    private val EPS = BigDecimal("0.000000001")
    /** Shown only when there are non-equity manual lines and the gain is material. Returns (gain, % p.a. blended or null). */
    fun of(d: Dashboard): Pair<BigDecimal, BigDecimal?>? {
        val lines = d.nonEquityManualBasisLines
        val gain = d.nonEquityManualGainTotalBase ?: return null
        if (lines <= 0 || gain.abs() < EPS) return null
        return gain to d.nonEquityManualAnnualizedPct?.multiply(HUNDRED)
    }
}

// ======================================================================================
// Manual wealth over time
// ======================================================================================

enum class WealthFilter(val label: String) { All("All"), Cash("Cash"), Property("Property"), Other("Other") }

data class WealthChart(val dates: List<String>, val lines: List<Pair<String, List<BigDecimal>>>)

object WealthMath {
    fun eligible(d: ManualWealthSeries) = d.assets.filter { it.chartEligible && it.showOnWealthChart }
    /** The whole section is hidden unless at least one line qualifies (5+ updates over 1+ year by default). */
    fun visible(d: ManualWealthSeries?) = d != null && eligible(d).isNotEmpty()
    fun ineligibleCount(d: ManualWealthSeries) = d.assets.count { !it.chartEligible && it.showOnWealthChart }

    fun chart(d: ManualWealthSeries, filter: WealthFilter): WealthChart {
        val assets = eligible(d).filter {
            when (filter) { WealthFilter.All -> true; WealthFilter.Cash -> it.assetKind == "cash"; WealthFilter.Property -> it.assetKind == "real_estate"; WealthFilter.Other -> it.assetKind == "other" }
        }
        val dates = assets.flatMap { a -> a.snapshots.map { it.asOf } }.toSortedSet().toList()
        // Each line carries its last snapshot forward to every date.
        val lines = assets.map { a ->
            a.label to dates.map { day -> a.snapshots.filter { it.asOf <= day }.maxByOrNull { it.asOf }?.valueGbp ?: ZERO }
        }
        return WealthChart(dates, lines)
    }
}

// ======================================================================================
// Listed equity over time (per broker lines)
// ======================================================================================

data class EquityPoint(val label: String, val mv: BigDecimal?, val invested: BigDecimal?, val brokers: List<GrowthBroker>)
data class EquityLine(val name: String, val values: List<BigDecimal?>, val dashed: Boolean, val colorIndex: Int, val isTotal: Boolean)
data class EquitySeries(val lines: List<EquityLine>, val deltaMv: BigDecimal?)

object EquityTrend {
    fun isPartial(selected: Set<String>, all: List<String>) = selected.size < all.toSet().size && selected.isNotEmpty()

    /**
     * Per selected broker a market-value line and a dashed book-cost line, plus (optional) totals.
     * Totals are the aggregate fields when every broker is selected, else the sum over the selected brokers.
     */
    fun series(points: List<EquityPoint>, selected: Set<String>, allLabels: List<String>, totals: Boolean): EquitySeries {
        val partial = isPartial(selected, allLabels)
        val chosen = allLabels.filter { it in selected }.ifEmpty { allLabels }
        val lines = mutableListOf<EquityLine>()
        if (totals) {
            val mv = points.map { p -> if (partial) sel(p, chosen) { it.listedEquityMvEndBase } else p.mv }
            val bk = points.map { p -> if (partial) sel(p, chosen) { it.investedCapitalEndBase } else p.invested }
            lines += EquityLine(if (partial) "MV (selected)" else "MV", mv, false, -1, true)
            lines += EquityLine(if (partial) "Book (selected)" else "Book", bk, true, -2, true)
        }
        chosen.forEachIndexed { i, label ->
            lines += EquityLine("$label · mv", points.map { p -> p.brokers.firstOrNull { it.brokerLabel == label }?.listedEquityMvEndBase }, false, i, false)
            lines += EquityLine("$label · book", points.map { p -> p.brokers.firstOrNull { it.brokerLabel == label }?.investedCapitalEndBase }, true, i, false)
        }
        val totalMv = (lines.firstOrNull { it.isTotal && !it.dashed }?.values ?: points.map { p -> if (partial) sel(p, chosen) { it.listedEquityMvEndBase } else p.mv })
        val first = totalMv.firstOrNull { it != null }; val last = totalMv.lastOrNull { it != null }
        return EquitySeries(lines, if (first != null && last != null) last - first else null)
    }

    private fun sel(p: EquityPoint, chosen: List<String>, f: (GrowthBroker) -> BigDecimal?): BigDecimal? {
        val parts = p.brokers.filter { it.brokerLabel in chosen }.mapNotNull(f)
        return if (parts.isEmpty()) null else parts.fold(ZERO, BigDecimal::add)
    }
}

// ======================================================================================
// Dividend stacks (per-ticker bars, average line, forecast zone)
// ======================================================================================

data class DividendStackData(val tickers: List<String>, val bars: List<DividendBar>, val average: BigDecimal?)
data class DividendBar(val label: String, val kind: String, val total: BigDecimal, val actual: List<BigDecimal>, val forecast: List<BigDecimal>, val forecastZone: Boolean)

object DividendStacks {
    private const val TOP = 5

    /**
     * Segments are the biggest payers over the whole range (top 5) plus "Other", in a fixed order so a ticker keeps its colour.
     * Each bar keeps actual and estimated amounts apart; the average is the mean total of bars outside the forecast zone.
     */
    fun build(series: List<DividendPoint>): DividendStackData {
        val totals = LinkedHashMap<String, BigDecimal>()
        series.forEach { p -> p.layers.forEach { l -> totals.merge(l.ticker, l.amountBase ?: ZERO, BigDecimal::add) } }
        val top = totals.entries.sortedByDescending { it.value }.take(TOP).map { it.key }
        val tickers = if (totals.size > TOP) top + "Other" else top
        fun slot(t: String) = if (t in top) tickers.indexOf(t) else tickers.lastIndex
        val bars = series.map { p ->
            val actual = MutableList(tickers.size) { ZERO }; val forecast = MutableList(tickers.size) { ZERO }
            p.layers.forEach { l -> val i = slot(l.ticker); val a = l.amountBase ?: ZERO; if (l.layer == "forecast") forecast[i] += a else actual[i] += a }
            DividendBar(p.label, p.kind, p.totalBase ?: ZERO, actual, forecast, p.forecastZone || p.kind == "forecast_forward")
        }
        val hist = bars.filter { !it.forecastZone }
        val avg = if (hist.isEmpty()) null else hist.fold(ZERO) { a, b -> a + b.total }.divide(BigDecimal(hist.size), 2, RoundingMode.HALF_UP)
        return DividendStackData(tickers, bars, avg)
    }
}
