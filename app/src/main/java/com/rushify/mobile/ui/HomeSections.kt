package com.rushify.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.ManualWealthSeries
import com.rushify.mobile.data.RushifyRepository
import java.math.BigDecimal

class WealthViewModel(private val repo: RushifyRepository) : CachedViewModel<ManualWealthSeries>() {
    override suspend fun readCache() = repo.wealthCached()
    override suspend fun fetch() = repo.wealth()
}

/** The web only asks for the wealth series when manual non-listed lines (property, cash, other) exist. */
fun hasManualNonListedLines(rows: List<HoldingRow>) = rows.any { it.source == "manual" && it.manualHoldingId != null && !HoldingsLogic.isListedAssetKind(it.assetKind) }

object Palette {
    val SectorPie = listOf(Color(0xFF0072B2), Color(0xFFE69F00), Color(0xFF009E73), Color(0xFFD55E00), Color(0xFFCC79A7), Color(0xFF785EF0), Color(0xFF56B4E9), Color(0xFF0E7490))
    val Wrapper = mapOf("Invest" to Color(0xFF005A9C), "ISA" to Color(0xFF007A53), "SIPP" to Color(0xFF5E3F99), "LISA" to Color(0xFFC2410C), "JISA" to Color(0xFFBE185D), "JSIPP" to Color(0xFF0C6E87), "Other" to Color(0xFF475569))
    val Property = Color(0xFF785EF0)
    val OtherAssets = Color(0xFF64748B)
    private val cash = mapOf("Invest" to Color(0xFFD97706), "ISA" to Color(0xFF2563EB), "Cash ISA" to Color(0xFF7C3AED), "SIPP" to Color(0xFF0D9488), "LISA" to Color(0xFF0284C7), "JISA" to Color(0xFF059669), "JSIPP" to Color(0xFFEA580C), "Other" to Color(0xFF64748B))
    private val cashFallback = listOf(Color(0xFFDB2777), Color(0xFF65A30D), Color(0xFF0891B2), Color(0xFF9333EA), Color(0xFFCA8A04), Color(0xFF4F46E5))
    fun cashColor(label: String): Color = cash[label] ?: cashFallback[(label.lowercase().hashCode() and 0x7fffffff) % cashFallback.size]
    val WealthLines = listOf(Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF472B6), Color(0xFFFBBF24), Color(0xFFA78BFA), Color(0xFFFB7185), Color(0xFF2DD4BF), Color(0xFF818CF8))
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(c))

@Composable
private fun GainText(gain: BigDecimal, pct: BigDecimal?, ccy: String, hide: Boolean, size: Int = 12) {
    val arrow = if (gain.signum() >= 0) "▲" else "▼"
    Num(if (hide) Fmt.HIDDEN else Fmt.signedMoney(gain, ccy) + (pct?.let { " $arrow ${Fmt.percent(it.abs(), 2)}" } ?: ""), color = pnlColor(gain), size = size)
}

// ---------------------------------------------------------------- collapsible sections (like the web's HomeCollapsible)

/** One-at-a-time state for Home's secondary sections: opening one closes the open one; tapping it again closes it. */
class HomeAccordion(val open: String?, val toggle: (String) -> Unit) {
    @Composable
    fun Section(id: String, title: String, summary: String, content: @Composable () -> Unit) =
        HomeCollapsible(id, title, summary, expanded = open == id, onToggle = { toggle(id) }, content = content)
}

/**
 * Title and a short summary while collapsed; the full content when expanded. The arrow turns 180 degrees, the summary fades out
 * as the body fades in, and the height eases with a soft no-bounce spring (a plain fade under Reduce motion).
 */
@Composable
fun HomeCollapsible(id: String, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val tick = rememberTick()
    val reduce = rememberReduceMotion()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, if (reduce) tween(RMotion.FADE_MS) else tween(RMotion.SMOOTH_MS, easing = RMotion.EaseOut), label = "chevron")
    RCard(Modifier.testTag("section_$id")) {
        Row(Modifier.fillMaxWidth().clickable { tick(); onToggle() }.testTag("section_toggle_$id"), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SectionTitle(title)
                AnimatedVisibility(!expanded, enter = fadeIn(tween(200, delayMillis = 150)), exit = fadeOut(tween(200, easing = RMotion.EaseOut))) {
                    Muted(summary, Modifier.padding(top = 2.dp).testTag("section_summary_$id"))
                }
            }
            Text("▼", color = RColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 5.dp).graphicsLayer { rotationZ = rotation })
        }
        AnimatedVisibility(
            expanded,
            enter = fadeIn(tween(300, delayMillis = 120)) + (if (reduce) expandVertically(tween(RMotion.FADE_MS)) else expandVertically(RMotion.expand())),
            exit = fadeOut(tween(150)) + (if (reduce) shrinkVertically(tween(RMotion.FADE_MS)) else shrinkVertically(RMotion.expand())),
        ) {
            Column { Spacer(Modifier.height(8.dp)); Column(Modifier.testTag("section_body_$id")) { content() } }
        }
    }
}

// ---------------------------------------------------------------- sectors

@Composable
fun SectorContent(rows: List<HoldingRow>, ccy: String, hide: Boolean) {
    val sectors = remember(rows) { Sectors.build(rows) }
    if (sectors.isEmpty()) return
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val current = sectors.firstOrNull { it.sector == picked }
    val total = sectors.fold(BigDecimal.ZERO) { a, s -> a + s.value }
    Column(Modifier.testTag("sector_section")) {
        if (current == null) {
            Muted("${sectors.size} sectors · listed total ${Fmt.money(total, ccy, hide, 0)}. Tap a sector to see its holdings.")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Donut(sectors.mapIndexed { i, s -> Slice(s.sector, s.value.toFloat(), Palette.SectorPie[i % Palette.SectorPie.size]) }) }
            sectors.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clickable { picked = s.sector }.padding(vertical = 7.dp).testTag("sector_${s.sector}"), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Palette.SectorPie[i % Palette.SectorPie.size]); Spacer(Modifier.padding(start = 8.dp))
                    Column(Modifier.weight(1f)) { Text(s.sector, color = RColors.Text, fontSize = 13.sp); Muted(Fmt.plural(s.holdings.size, "holding")) }
                    Column(horizontalAlignment = Alignment.End) {
                        Num(Fmt.money(s.value, ccy, hide), size = 12); GainText(s.gain, s.gainPct, ccy, hide, 11)
                        Num(Fmt.percent(s.allocPct, 1), color = RColors.Muted, size = 11)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().clickable { picked = null }.padding(bottom = 6.dp).testTag("sector_back"), verticalAlignment = Alignment.CenterVertically) {
                Text("← Back", color = RColors.Accent, fontSize = 13.sp); Muted("   Portfolio / ${current.sector}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Donut(current.holdings.mapIndexed { i, h -> Slice(h.ticker, h.value.toFloat(), Palette.SectorPie[i % Palette.SectorPie.size]) }) }
            if (current.holdings.isEmpty()) Muted("No listed holdings in this sector.")
            current.holdings.forEachIndexed { i, h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Palette.SectorPie[i % Palette.SectorPie.size]); Spacer(Modifier.padding(start = 8.dp))
                    StockIcon(h.ticker); Spacer(Modifier.padding(start = 8.dp))
                    Column(Modifier.weight(1f)) { Num(h.ticker, bold = true, size = 13); h.name?.let { Muted(it) } }
                    Column(horizontalAlignment = Alignment.End) {
                        Num(Fmt.money(h.value, ccy, hide), size = 12)
                        GainText(h.gain, if (h.cost.signum() > 0) h.gain.multiply(BigDecimal(100)).divide(h.cost, 2, java.math.RoundingMode.HALF_UP) else null, ccy, hide, 11)
                        Num(Fmt.percent(h.shareOfSectorPct, 1) + " of sector", color = RColors.Muted, size = 11)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- position size

@Composable
fun PositionSizeSection(rows: List<HoldingRow>, ccy: String, hide: Boolean, acc: HomeAccordion) {
    val bars = remember(rows) { PositionBars.build(rows) }
    if (bars.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) bars else bars.take(12)
    val scale = bars.maxOf { maxOf(it.value, it.cost) }.toFloat()
    acc.Section("positions", "Position size: cost vs gain/loss", "${Fmt.plural(bars.size, "position")} — bars ordered by market value.") { Column(Modifier.testTag("position_section")) {
        Muted("${Fmt.plural(bars.size, "position")}, largest value first. Blue = value that is cost, green = gain, red = loss (the part of cost that is gone). Scale in $ccy.")
        Spacer(Modifier.height(6.dp))
        shown.forEach { b ->
            val cost = b.cost.toFloat(); val value = b.value.toFloat()
            val segs = when {
                b.cost.signum() <= 0 -> listOf(value to RColors.Chart[0])
                b.pnl.signum() >= 0 -> listOf(minOf(cost, value) to RColors.Chart[0], (value - cost).coerceAtLeast(0f) to RColors.Gain)
                else -> listOf(value to RColors.Chart[0], (cost - value).coerceAtLeast(0f) to RColors.Loss)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) { StockIcon(b.ticker, size = 20.dp); Spacer(Modifier.padding(start = 6.dp)); Num(b.ticker, bold = true, size = 12) }
                    Num(Fmt.money(b.value, ccy, hide), size = 12)
                }
                HBar(segs, scale)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Muted("Cost ${Fmt.money(b.cost, ccy, hide, 0)}"); Num(Fmt.signedMoney(b.pnl, ccy, hide), color = pnlColor(b.pnl), size = 11)
                }
            }
        }
        if (bars.size > 12) Chip(if (all) "Show top 12" else "Show all ${bars.size}", false) { all = !all }
    } }
}

// ---------------------------------------------------------------- net worth by broker

@Composable
fun CustodianSection(rows: List<HoldingRow>, ccy: String, hide: Boolean, acc: HomeAccordion) {
    val list = remember(rows) { Custodian.build(rows) }
    if (list.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val grand = list.fold(BigDecimal.ZERO) { a, r -> a + r.total }
    val maxTotal = list.maxOf { it.total }.toFloat()
    acc.Section("custodian", "Net worth by broker", "${Fmt.plural(list.size, "institution")} · total ${Fmt.money(grand, ccy, hide)}.") { Column(Modifier.testTag("custodian_section")) {
        Muted("${Fmt.plural(list.size, "institution")} · total ${Fmt.money(grand, ccy, hide)}. One bar per broker; tap for the listed and cash split by account type.")
        Spacer(Modifier.height(6.dp))
        list.forEach { r ->
            val isOpen = r.name in open
            Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) open - r.name else open + r.name }.padding(vertical = 6.dp).testTag("custodian_${r.name}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, color = RColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Num(Fmt.money(r.total, ccy, hide), size = 12)
                }
                HBar(segmentsOf(r), maxTotal)
                Muted("${Fmt.percent(r.total.multiply(BigDecimal(100)).divide(grand, 1, java.math.RoundingMode.HALF_UP), 1)} of net worth ${if (isOpen) "▲" else "▼"}")
                if (isOpen) {
                    if (r.equity.signum() > 0) BreakdownLine("Listed", r.equity, ccy, hide, r.listedByAccount.map { "Listed · ${it.account}" to it.value to Palette.Wrapper[it.account] })
                    if (r.property.signum() > 0) BreakdownLine("Property", r.property, ccy, hide, emptyList())
                    if (r.cash.signum() > 0) BreakdownLine("Cash", r.cash, ccy, hide, r.cashByAccount.map { "Cash · ${it.account}" to it.value to Palette.cashColor(it.account) })
                    if (r.other.signum() > 0) BreakdownLine("Other assets", r.other, ccy, hide, emptyList())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted("Grand total"); Num(Fmt.money(grand, ccy, hide), bold = true, size = 13) }
        Spacer(Modifier.height(8.dp))
        CustodianLegend(list)
    } }
}

private fun segmentsOf(r: CustodianRow): List<Pair<Float, Color>> = buildList {
    Custodian.WRAPPER_KEYS.forEach { k -> r.wrappers[k]?.takeIf { it.signum() > 0 }?.let { add(it.toFloat() to (Palette.Wrapper[k] ?: Palette.OtherAssets)) } }
    if (r.property.signum() > 0) add(r.property.toFloat() to Palette.Property)
    r.cashByAccount.ifEmpty { if (r.cash.signum() > 0) listOf(NamedAmount("Other", r.cash)) else emptyList() }.sortedBy { it.account }.forEach { add(it.value.toFloat() to Palette.cashColor(it.account)) }
    if (r.other.signum() > 0) add(r.other.toFloat() to Palette.OtherAssets)
}

@Composable
private fun BreakdownLine(title: String, total: BigDecimal, ccy: String, hide: Boolean, parts: List<Pair<Pair<String, BigDecimal>, Color?>>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted(title); Num(Fmt.money(total, ccy, hide), size = 12) }
    parts.forEach { (nv, c) ->
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Dot(c ?: Palette.OtherAssets); Spacer(Modifier.padding(start = 6.dp)); Muted(nv.first) }
            Num(Fmt.money(nv.second, ccy, hide), size = 11, color = RColors.Muted)
        }
    }
}

@Composable
private fun CustodianLegend(list: List<CustodianRow>) {
    val wrappers = Custodian.WRAPPER_KEYS.filter { k -> list.any { (it.wrappers[k] ?: BigDecimal.ZERO).signum() > 0 } }
    val cash = list.flatMap { it.cashByAccount.map { c -> c.account } + if (it.cashByAccount.isEmpty() && it.cash.signum() > 0) listOf("Other") else emptyList() }.distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            wrappers.forEach { LegendDot(Palette.Wrapper[it] ?: Palette.OtherAssets, "Listed · $it") }
            if (list.any { it.property.signum() > 0 }) LegendDot(Palette.Property, "Property")
            if (list.any { it.other.signum() > 0 }) LegendDot(Palette.OtherAssets, "Other assets")
        }
        if (cash.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { cash.forEach { LegendDot(Palette.cashColor(it), "Cash · $it") } }
    }
}

// ---------------------------------------------------------------- wealth over time

@Composable
fun WealthSection(vm: WealthViewModel, hide: Boolean, acc: HomeAccordion) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val d = s.data?.value
    if (!WealthMath.visible(d)) return
    var filter by rememberSaveable { mutableStateOf(WealthFilter.All) }
    var sel by rememberSaveable { mutableStateOf<Int?>(null) }
    val data = d!!
    val chart = remember(data, filter) { WealthMath.chart(data, filter) }
    val ineligible = WealthMath.ineligibleCount(data)
    val lines = WealthMath.eligible(data).size
    val totalNow = WealthMath.chart(data, WealthFilter.All).lines.fold(BigDecimal.ZERO) { a, (_, v) -> a + (v.lastOrNull() ?: BigDecimal.ZERO) }
    acc.Section("wealth", "Wealth over time", "${Fmt.plural(lines, "line")} · ${Fmt.money(totalNow, data.baseCurrency, hide, 0)} total.") { Column(Modifier.testTag("wealth_section")) {
        Muted("Property, cash savings and other manual assets (${data.minSnapshotsForChart}+ updates over ${data.minHoldDaysForChart / 365}+ year). Separate from listed equity.")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WealthFilter.entries.forEach { f -> Chip(f.label, filter == f, "wealth_filter_${f.name}") { sel = null; filter = f } } }
        Spacer(Modifier.height(6.dp))
        when {
            hide -> Muted("Hidden while balances are hidden.")
            chart.lines.isEmpty() -> Muted("No lines match this filter.")
            else -> {
                val pick = (sel ?: chart.dates.lastIndex).coerceIn(0, chart.dates.lastIndex)
                LineChart(chart.dates.map { it.takeLast(5) }, chart.lines.mapIndexed { i, (name, vals) -> ChartLine(name, Palette.WealthLines[i % Palette.WealthLines.size], vals.map { it.toFloat() }) }, pick, { sel = it }, description = "Wealth over time")
                Spacer(Modifier.height(4.dp))
                Text(chart.dates[pick], color = RColors.Text, fontWeight = FontWeight.SemiBold)
                chart.lines.forEachIndexed { i, (name, vals) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically) { Dot(Palette.WealthLines[i % Palette.WealthLines.size]); Spacer(Modifier.padding(start = 6.dp)); Muted(name) }; Num(Fmt.money(vals[pick], data.baseCurrency, hide, 0), size = 12) } }
            }
        }
        if (ineligible > 0) Muted("$ineligible line${if (ineligible == 1) "" else "s"} still building history.", Modifier.padding(top = 6.dp))
        Muted("Manual property (net equity), cash savings and other assets. Broker cash is excluded. Opt a line out under Holdings → Edit values.", Modifier.padding(top = 6.dp))
    } }
}

// ---------------------------------------------------------------- freshness

const val FRESHNESS_NOTE = "After your equity data is synced from a broker or added manually, the backend usually needs 12-24 hours to fetch the latest market updates. Charts and dashboard figures roll forward on that timeline."

@Composable
fun FreshnessNote(acc: HomeAccordion) {
    acc.Section("freshness", "Data freshness", "How recent market data and FX are for your synced holdings.") { Column(Modifier.testTag("freshness_note")) {
        Muted(FRESHNESS_NOTE)
        Muted("Sync or manual add → 12–24 hr pipeline → dashboards and benchmarks refresh", Modifier.padding(top = 4.dp))
    } }
}
