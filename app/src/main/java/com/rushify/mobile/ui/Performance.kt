package com.rushify.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.rushify.mobile.data.Growth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushify.mobile.data.Benchmarks
import com.rushify.mobile.data.Dividends
import com.rushify.mobile.data.RushifyRepository
import java.math.BigDecimal

/** Period choices, identical to the web toolbars (Growth/Benchmarks: 6/12/18/24 months or 3/5/8/10 years; Dividends history is wider). */
object PeriodOptions {
    fun growth(granularity: String) = if (granularity == "yearly") listOf(3, 5, 8, 10) else listOf(6, 12, 18, 24)
    fun dividends(granularity: String) = if (granularity == "yearly") listOf(3, 4, 5, 8, 10, 15) else listOf(6, 12, 18, 24, 36)
    /** Switching Monthly/Yearly resets to the web default: 12 months or 5 years. */
    fun default(granularity: String) = if (granularity == "yearly") 5 else 12
    fun label(granularity: String, n: Int) = if (granularity == "yearly") "$n yr" else "$n mo"
}

class GrowthViewModel(private val repo: RushifyRepository) : CachedViewModel<Growth>() {
    var granularity = "monthly"; private set
    var count = PeriodOptions.default("monthly"); private set
    override suspend fun readCache() = repo.growthCached(granularity, count)
    override suspend fun fetch() = repo.growth(granularity, count)
    fun select(g: String = granularity, n: Int = if (g == granularity) count else PeriodOptions.default(g)) {
        if (g != granularity || n != count) { granularity = g; count = n; reload() }
    }
}

/** mode: "day", "week" (server ranges) or "monthly", "yearly" (+ count), matching the web's Monthly/Yearly + Periods. */
class BenchmarksViewModel(private val repo: RushifyRepository) : CachedViewModel<Benchmarks>() {
    var mode = "monthly"; private set
    var count = PeriodOptions.default("monthly"); private set
    override suspend fun readCache() = repo.benchmarksCached(mode, count)
    override suspend fun fetch() = repo.benchmarks(mode, count)
    fun select(m: String = mode, n: Int = if (m == mode) count else PeriodOptions.default(m)) {
        if (m != mode || n != count) { mode = m; count = n; reload() }
    }
}

/** Web defaults: Year, 5 years of history. */
class DividendsViewModel(private val repo: RushifyRepository) : CachedViewModel<Dividends>() {
    var granularity = "yearly"; private set
    var count = PeriodOptions.default("yearly"); private set
    var symbol: String? = null; private set
    override suspend fun readCache() = repo.dividendsCached(granularity, count, symbol)
    override suspend fun fetch() = repo.dividends(granularity, count, symbol)
    fun select(g: String = granularity, n: Int = if (g == granularity) count else PeriodOptions.default(g), sym: String? = symbol) {
        if (g != granularity || n != count || sym != symbol) { granularity = g; count = n; symbol = sym; reload() }
    }
}

/** Row of "6 mo / 12 mo …" chips for the selected granularity. */
@Composable
private fun PeriodChips(granularity: String, options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Muted("Periods", Modifier.padding(top = 8.dp))
        options.forEach { n -> Chip(PeriodOptions.label(granularity, n), n == selected) { onPick(n) } }
    }
}

/** Header with a back chevron, used by every screen opened from More / Home. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = RColors.Accent, fontSize = 30.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp).testTag("back"))
            Text(title, color = RColors.Text, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) { content() }
    }
}

private val GrowthColors = listOf(Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF34D399), Color(0xFFA855F7))   // price, interaction, income, currency

/** Listed-equity chart preferences and its daily data (daily points are only fetched when "Daily" is on). */
data class EquityPrefs(val daily: Boolean = false, val totals: Boolean = true, val selected: Set<String>? = null)

class EquityTrendViewModel(private val repo: RushifyRepository) : CachedViewModel<Growth>() {
    val prefs = kotlinx.coroutines.flow.MutableStateFlow(EquityPrefs())
    private var granularity = "monthly"; private var count = 12; private var brokers: List<String> = emptyList()
    override suspend fun readCache() = repo.growthDailyCached(granularity, count, brokers)
    override suspend fun fetch() = repo.growthDaily(granularity, count, brokers)
    fun update(p: EquityPrefs.() -> EquityPrefs) { prefs.value = prefs.value.p() }
    /** Called when the window or the broker selection changes while Daily is on. */
    fun sync(g: String, n: Int, selected: List<String>) {
        if (g == granularity && n == count && selected == brokers && state.value.data != null) return
        granularity = g; count = n; brokers = selected; reload()
    }
}

@Composable
fun PerformanceScreen(growth: GrowthViewModel, bench: BenchmarksViewModel, dividends: DividendsViewModel, dashboard: DashboardViewModel, equity: EquityTrendViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    SubScreen("Performance & income", onBack) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Growth", "Benchmarks", "Dividends").forEachIndexed { i, t -> Chip(t, tab == i) { tab = i } }
        }
        when (tab) {
            0 -> GrowthTab(growth, dashboard, equity)
            1 -> BenchmarksTab(bench)
            else -> DividendsTab(dividends)
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, sub: String?, color: Color, modifier: Modifier = Modifier, dot: Color? = null) {
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { dot?.let { Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(it)); Spacer(Modifier.width(5.dp)) }; Muted(label) }
        Num(value, color = color, size = 14, bold = true)
        sub?.let { Muted(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrowthTab(vm: GrowthViewModel, dashboard: DashboardViewModel, equity: EquityTrendViewModel) {
    val s by vm.state.collectAsState()
    val dash by dashboard.state.collectAsState()
    val prefs by equity.prefs.collectAsState()
    val daily by equity.state.collectAsState()
    LaunchedEffect(Unit) { vm.load(); dashboard.load() }
    val hide = LocalHideBalances.current
    var sel by rememberSaveable { mutableStateOf<Int?>(null) }
    val g = s.data?.value
    val ccy = g?.baseCurrency ?: "GBP"
    val labels = remember(dash.data) { Custodian.listedEquityBrokerLabels(dash.data?.value?.rows ?: emptyList()) }
    val selected = prefs.selected?.filter { it in labels }?.toSet()?.ifEmpty { null } ?: labels.take(1).toSet()   // web default: first broker alphabetically
    LaunchedEffect(prefs.daily, vm.granularity, vm.count, selected) { if (prefs.daily && labels.isNotEmpty()) equity.sync(vm.granularity, vm.count, labels.filter { it in selected }) }
    PullToRefreshBox(isRefreshing = s.loading && g != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Monthly", vm.granularity == "monthly") { sel = null; vm.select("monthly") }
                Chip("Yearly", vm.granularity == "yearly") { sel = null; vm.select("yearly") }
            }
            PeriodChips(vm.granularity, PeriodOptions.growth(vm.granularity), vm.count) { sel = null; vm.select(n = it) }
            AsOfBar(s.data?.fetchedAt, s.error, g != null, s.loading)
            if (g == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading growth… the server calculates this on first use and it can take several seconds."); return@Column }
            if (g.periods.isEmpty()) { Muted("No growth data yet. Sync positions and make sure prices load."); return@Column }
            val pick = (sel ?: g.periods.lastIndex).coerceIn(0, g.periods.lastIndex)
            val p = g.periods[pick]
            val sum = GrowthMath.summary(g.periods)
            val unit = if (vm.granularity == "yearly") "years" else "months"
            RCard {
                SectionTitle("Growth attribution")
                Muted("How change splits across price, currency, dividends and interaction, in $ccy. Totals for the range (${g.periods.size} $unit):")
                Spacer(Modifier.height(6.dp))
                SummaryTile("Total change", Fmt.signedMoney(sum.total, ccy, hide), null, pnlColor(sum.total), Modifier.testTag("growth_total"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryTile("Price (× FX₀)", Fmt.signedMoney(sum.price, ccy, hide), sum.share(sum.price), pnlColor(sum.price), Modifier.weight(1f), GrowthColors[0])
                    SummaryTile("Interaction", Fmt.signedMoney(sum.interaction, ccy, hide), sum.share(sum.interaction), pnlColor(sum.interaction), Modifier.weight(1f), GrowthColors[1])
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryTile("Income (dividends)", Fmt.signedMoney(sum.income, ccy, hide), sum.share(sum.income), pnlColor(sum.income), Modifier.weight(1f), GrowthColors[2])
                    SummaryTile("Currency (FX)", Fmt.signedMoney(sum.currency, ccy, hide), sum.share(sum.currency), pnlColor(sum.currency), Modifier.weight(1f), GrowthColors[3])
                }
                Spacer(Modifier.height(8.dp))
                if (hide) Muted("Charts are hidden while balances are hidden.") else {
                    StackedBarChart(
                        g.periods.map { StackedBar(it.label, listOf(it.priceGainBase.f(), it.interactionGainBase.f(), it.incomeBase.f(), it.currencyGainBase.f())) },
                        GrowthColors, pick, { sel = it }, description = "Growth attribution chart",
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(GrowthColors[0], "Price (× FX₀)"); LegendDot(GrowthColors[1], "Interaction"); LegendDot(GrowthColors[2], "Income"); LegendDot(GrowthColors[3], "Currency")
                }
                Muted("Positive effects stack above zero and negative effects below, not as one running total. The four parts still add up to the period total.", Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Text(p.label, color = RColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("growth_selected"))
                ReadoutRow("Total", Fmt.signedMoney(p.totalGrowthBase, ccy, hide), pnlColor(p.totalGrowthBase), bold = true)
                ReadoutRow("Price (× FX₀)", Fmt.signedMoney(p.priceGainBase, ccy, hide), pnlColor(p.priceGainBase))
                ReadoutRow("Interaction", Fmt.signedMoney(p.interactionGainBase, ccy, hide), pnlColor(p.interactionGainBase))
                ReadoutRow("Income (dividends)", Fmt.signedMoney(p.incomeBase, ccy, hide), pnlColor(p.incomeBase))
                ReadoutRow("Currency (FX)", Fmt.signedMoney(p.currencyGainBase, ccy, hide), pnlColor(p.currencyGainBase))
                if (GrowthMath.stackDiffers(p)) Muted("Stack sum ${Fmt.money(GrowthMath.stackSum(p), ccy, hide)} (rounding)")
                if (p.purchaseEvents.isNotEmpty()) Muted("Purchases: " + p.purchaseEvents.joinToString(", ") { it.label ?: it.ticker }.take(160))
                Muted("Note on Interaction: " + GrowthMath.INTERACTION_NOTE, Modifier.padding(top = 8.dp).testTag("interaction_note"))
            }
            EquityTrendCard(g, daily.data?.value, daily.loading, labels, selected, prefs, equity, ccy, hide, pick) { sel = it }
            Muted("Values are end-of-day reconstructions, not live prices.")
        }
    }
}

@Composable
private fun EquityTrendCard(
    g: Growth, dailyData: Growth?, dailyLoading: Boolean, labels: List<String>, selected: Set<String>, prefs: EquityPrefs, vm: EquityTrendViewModel,
    ccy: String, hide: Boolean, pick: Int, onPick: (Int) -> Unit,
) {
    val usingDaily = prefs.daily && dailyData?.equityDaily != null
    val points: List<EquityPoint> = remember(g, dailyData, prefs.daily) {
        if (usingDaily) dailyData!!.equityDaily!!.map { EquityPoint(it.asOf, it.listedEquityMvEndBase, it.investedCapitalEndBase, it.brokers) }
        else g.periods.map { EquityPoint(it.label, it.listedEquityMvEndBase, it.investedCapitalEndBase, it.brokers) }
    }
    val series = remember(points, selected, labels, prefs.totals) { EquityTrend.series(points, selected, labels, prefs.totals) }
    val partial = EquityTrend.isPartial(selected, labels)
    val idx = if (usingDaily) (points.lastIndex).coerceAtLeast(0) else pick.coerceIn(0, (points.size - 1).coerceAtLeast(0))
    var cursor by rememberSaveable { mutableStateOf<Int?>(null) }
    val at = (cursor ?: idx).coerceIn(0, (points.size - 1).coerceAtLeast(0))
    RCard(Modifier.testTag("equity_card")) {
        SectionTitle("Listed equity over time")
        Muted("Market value (solid) and book cost (dashed). Listed equities only.")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Period", !prefs.daily) { cursor = null; vm.update { copy(daily = false) } }
            Chip("Daily", prefs.daily) { cursor = null; vm.update { copy(daily = true) } }
            Chip(if (prefs.totals) "Totals ✓" else "Totals", prefs.totals) { vm.update { copy(totals = !totals) } }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Muted("Accounts")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    val on = l in selected
                    Chip(l, on) {
                        val next = if (on) selected - l else selected + l
                        if (next.isNotEmpty()) vm.update { copy(selected = next) }       // the last remaining account cannot be unticked
                    }
                }
                Chip("Select all", false) { vm.update { copy(selected = labels.toSet()) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            hide -> Muted("Hidden while balances are hidden.")
            prefs.daily && dailyLoading && dailyData == null -> Muted("Loading daily points… this can take several seconds.")
            points.size < 2 -> Muted("No data for this range.")
            else -> {
                val lines = series.lines.map { ln ->
                    val c = when (ln.colorIndex) { -1 -> Color(0xFFF8FAFC); -2 -> Color(0xFFFACC15); else -> RColors.Chart[ln.colorIndex % RColors.Chart.size] }
                    ChartLine(ln.name, c, ln.values.map { it?.toFloat() }, ln.dashed)
                }
                LineChart(points.map { if (usingDaily) it.label.takeLast(5) else it.label.replace(" 20", " '") }, lines, at, { cursor = it }, description = "Listed equity over time")
                series.deltaMv?.let { Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted("Δ MV (first → last)"); Num(Fmt.signedMoney(it, ccy, hide), color = pnlColor(it), size = 13, modifier = Modifier.testTag("equity_delta")) } }
                val p = points[at]
                Spacer(Modifier.height(6.dp))
                Text(p.label + if (partial) " · selected accounts only" else "", color = RColors.Text, fontWeight = FontWeight.SemiBold)
                series.lines.filter { it.isTotal }.forEach { ln ->
                    val v = ln.values.getOrNull(at); ReadoutRow(ln.name, Fmt.money(v, ccy, hide))
                }
                series.lines.firstOrNull { it.isTotal && !it.dashed }?.values?.getOrNull(at)?.let { mv ->
                    series.lines.firstOrNull { it.isTotal && it.dashed }?.values?.getOrNull(at)?.let { bk -> ReadoutRow("MV − book", Fmt.signedMoney(mv - bk, ccy, hide), pnlColor(mv - bk)) }
                }
                series.lines.filter { !it.isTotal && !it.dashed }.forEachIndexed { i, ln -> ReadoutRow(ln.name.removeSuffix(" · mv"), Fmt.money(ln.values.getOrNull(at), ccy, hide)) }
                Muted("Reconstructed from stored end-of-day prices and synced cost basis, not live broker tiles, so real-time totals can differ.", Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun BigDecimal?.f(): Float = this?.toFloat() ?: 0f

@Composable
private fun ReadoutRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = RColors.Text, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted(label); Num(value, color = color, size = 13, bold = bold)
    }
}

@Composable
private fun MetricCard(title: String, value: String, note: String, color: Color = RColors.Text) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Muted(title); Num(value, color = color, size = 15, bold = true); Muted(note) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BenchmarksTab(vm: BenchmarksViewModel) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val hide = LocalHideBalances.current
    var sel by rememberSaveable { mutableStateOf<Int?>(null) }
    val b = s.data?.value
    PullToRefreshBox(isRefreshing = s.loading && b != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("monthly" to "Monthly", "yearly" to "Yearly", "week" to "1 week", "day" to "1 day").forEach { (k, l) -> Chip(l, vm.mode == k) { sel = null; vm.select(m = k) } }
            }
            if (vm.mode == "monthly" || vm.mode == "yearly") PeriodChips(vm.mode, PeriodOptions.growth(vm.mode), vm.count) { sel = null; vm.select(n = it) }
            AsOfBar(s.data?.fetchedAt, s.error, b != null, s.loading)
            if (b == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading benchmarks…"); return@Column }
            if (b.emptyPortfolio) { Muted("Benchmarks need at least one listed investment (stock or ETF) so we can build a price series. If your portfolio is only cash, property or other assets, this section stays empty."); return@Column }
            if (b.series.isEmpty()) { Muted("No portfolio history to compare yet."); return@Column }
            RCard {
                SectionTitle("Return vs FTSE 100 and S&P 100")
                Muted("Each series is set to 100 on the first day of the range: your portfolio in ${b.baseCurrency}, FTSE 100 (^FTSE) and S&P 100 (^OEX).")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Portfolio", Fmt.percent(b.portfolioReturnPct, signed = true), Modifier.weight(1f), pnlColor(b.portfolioReturnPct))
                    Stat("FTSE 100", Fmt.percent(b.ftse100ReturnPct, signed = true), Modifier.weight(1f), pnlColor(b.ftse100ReturnPct))
                    Stat("S&P 100", Fmt.percent(b.sp100ReturnPct, signed = true), Modifier.weight(1f), pnlColor(b.sp100ReturnPct))
                }
                Spacer(Modifier.height(10.dp))
                val pick = (sel ?: b.series.lastIndex).coerceIn(0, b.series.lastIndex)
                LineChart(
                    b.series.map { it.date.takeLast(5) },
                    listOf(
                        ChartLine("Portfolio", RColors.Chart[0], b.series.map { it.portfolioIndex?.toFloat() }),
                        ChartLine("FTSE 100", RColors.Chart[2], b.series.map { it.ftse100Index?.toFloat() }),
                        ChartLine("S&P 100", RColors.Chart[3], b.series.map { it.sp100Index?.toFloat() }),
                    ),
                    pick, { sel = it }, description = "Benchmark comparison chart",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { LegendDot(RColors.Chart[0], "Portfolio"); LegendDot(RColors.Chart[2], "FTSE 100"); LegendDot(RColors.Chart[3], "S&P 100") }
                Spacer(Modifier.height(8.dp))
                val pt = b.series[pick]
                Text(pt.date, color = RColors.Text, fontWeight = FontWeight.SemiBold)
                ReadoutRow("Portfolio (index)", pt.portfolioIndex?.let { String.format(java.util.Locale.UK, "%.2f", it) } ?: Fmt.DASH)
                ReadoutRow("FTSE 100", pt.ftse100Index?.let { String.format(java.util.Locale.UK, "%.2f", it) } ?: Fmt.DASH)
                ReadoutRow("S&P 100", pt.sp100Index?.let { String.format(java.util.Locale.UK, "%.2f", it) } ?: Fmt.DASH)
            }
            val rangeLabel = if (vm.mode == "day" || vm.mode == "week") "selected ${if (vm.mode == "day") "day" else "week"}" else "selected ${vm.count} ${if (vm.mode == "monthly") "month" else "year"}${if (vm.count == 1) "" else "s"}"
            RCard(Modifier.testTag("vs_cards")) {
                SectionTitle("Portfolio vs the indices")
                listOf(Triple("FTSE 100", b.ftse100ReturnPct, "FTSE 100"), Triple("S&P 100", b.sp100ReturnPct, "S&P 100 (^OEX)")).forEach { (name, ret, verdictName) ->
                    val gap = BenchmarkMath.points(b.portfolioReturnPct, ret)
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Muted("Portfolio vs $name ($rangeLabel)")
                        Num(BenchmarkMath.pointsText(gap), color = pnlColor(gap), size = 16, bold = true)
                        BenchmarkMath.verdict(gap, verdictName)?.let { Muted(it) }
                    }
                }
            }
            RCard {
                SectionTitle("Where the period P/L came from")
                MetricCard("Period total Δ (${b.baseCurrency})", Fmt.signedMoney(b.periodTotalPnlBase, b.baseCurrency, hide), "First → last day in range (portfolio value)", pnlColor(b.periodTotalPnlBase))
                MetricCard("Price effect (${b.baseCurrency}, est.)", Fmt.signedMoney(b.periodPricePnlBase, b.baseCurrency, hide), "Local move × FX at period start")
                MetricCard("Currency / FX effect (${b.baseCurrency}, est.)", Fmt.signedMoney(b.periodFxPnlBase, b.baseCurrency, hide), "FX move × local value at start")
                MetricCard("Interaction (est.)", Fmt.signedMoney(b.periodCrossPnlBase, b.baseCurrency, hide), "Same idea as Interaction in Growth attribution: the remainder when price and FX both move")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DividendsTab(vm: DividendsViewModel) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val hide = LocalHideBalances.current
    var sel by rememberSaveable { mutableStateOf<Int?>(null) }
    var howTo by rememberSaveable { mutableStateOf(false) }
    val d = s.data?.value
    PullToRefreshBox(isRefreshing = s.loading && d != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Monthly", vm.granularity == "monthly") { sel = null; vm.select(g = "monthly") }
                Chip("Yearly", vm.granularity == "yearly") { sel = null; vm.select(g = "yearly") }
            }
            PeriodChips(vm.granularity, PeriodOptions.dividends(vm.granularity), vm.count) { sel = null; vm.select(n = it) }
            if (d != null && d.symbols.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All holdings", vm.symbol == null) { sel = null; vm.select(sym = null) }
                    d.symbols.forEach { sym -> Chip(sym, vm.symbol == sym) { sel = null; vm.select(sym = sym) } }
                }
            }
            AsOfBar(s.data?.fetchedAt, s.error, d != null, s.loading)
            if (d == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading dividends…"); return@Column }
            if (d.series.isEmpty()) { Muted(d.note ?: "No dividend data for this view. Widen the history, clear the stock filter, or sync your portfolio."); return@Column }
            val summary = DividendMath.summary(d)
            val stacks = remember(d) { DividendStacks.build(d.series) }
            RCard {
                SectionTitle("Cash dividends")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Received (shown)", Fmt.money(summary.received, d.baseCurrency, hide), Modifier.weight(1f))
                    Stat("Forward estimate", Fmt.money(summary.forecast, d.baseCurrency, hide), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                val pick = (sel ?: d.series.indexOfLast { it.kind == "current_partial" }.takeIf { it >= 0 } ?: d.series.lastIndex).coerceIn(0, d.series.lastIndex)
                val tickerColors = stacks.tickers.indices.map { Palette.SectorPie[it % Palette.SectorPie.size] }
                if (hide) Muted("Hidden while balances are hidden.") else {
                    StackedBarChart(
                        stacks.bars.map { b -> StackedBar(DividendMath.axisLabel(d.series[stacks.bars.indexOf(b)]), (b.actual + b.forecast).map { it.toFloat() }, fadedFrom = stacks.tickers.size) },
                        tickerColors, pick, { sel = it }, description = "Dividend income chart",
                        averageLine = stacks.average?.toFloat(), zoneFromIndex = stacks.bars.indexOfFirst { it.forecastZone }.takeIf { it >= 0 },
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    stacks.tickers.forEachIndexed { i, t -> LegendDot(tickerColors[i], t) }
                    LegendDot(RColors.Warn, "Average${stacks.average?.let { " ${Fmt.money(it, d.baseCurrency, hide, 0)}" } ?: ""}")
                }
                Muted("Solid = paid, translucent = estimate, shaded = forward estimate.", Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                val p = d.series[pick]
                Text(p.label + when (p.kind) { "current_partial" -> " (so far)"; "forecast_forward" -> " (estimate)"; else -> "" }, color = RColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("dividend_selected"))
                ReadoutRow("Total", Fmt.money(p.totalBase, d.baseCurrency, hide), bold = true)
                p.growthPctPrior?.let { ReadoutRow("vs prior period", Fmt.percent(it, 1, signed = true), pnlColor(it)) }
                DividendMath.topContributors(p, 5).forEach { (t, amt) -> ReadoutRow(t, Fmt.money(amt, d.baseCurrency, hide)) }
            }
            if (d.note != null || d.forecastMethod != null) RCard {
                Row(Modifier.fillMaxWidth().clickable { howTo = !howTo }, horizontalArrangement = Arrangement.SpaceBetween) { Text("How to read this chart", color = RColors.Text, fontWeight = FontWeight.SemiBold); Muted(if (howTo) "▲" else "▼") }
                if (howTo) { d.note?.let { Muted(it, Modifier.padding(top = 4.dp)) }; d.forecastMethod?.let { Muted("Estimates: $it", Modifier.padding(top = 4.dp)) } }
            }
        }
    }
}

object DividendMath {
    /** Short x-axis label; the server's "Next 6 mo est." is too wide for a bar slot. */
    fun axisLabel(p: com.rushify.mobile.data.DividendPoint) = if (p.kind == "forecast_forward") "Next 6m" else p.label

    data class Summary(val received: BigDecimal, val forecast: BigDecimal)

    fun summary(d: Dividends): Summary {
        var received = BigDecimal.ZERO; var forecast = BigDecimal.ZERO
        d.series.forEach { p ->
            val v = p.totalBase ?: BigDecimal.ZERO
            if (p.kind == "forecast_forward") forecast += v else received += v
        }
        return Summary(received, forecast)
    }

    /** Largest per-ticker amounts in one period, summing actual + forecast layers. */
    fun topContributors(p: com.rushify.mobile.data.DividendPoint, n: Int): List<Pair<String, BigDecimal>> =
        p.layers.groupBy { it.ticker }.map { (t, l) -> t to l.fold(BigDecimal.ZERO) { a, x -> a + (x.amountBase ?: BigDecimal.ZERO) } }
            .filter { it.second.signum() > 0 }.sortedByDescending { it.second }.take(n)
}
