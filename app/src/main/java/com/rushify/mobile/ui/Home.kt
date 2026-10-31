package com.rushify.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.Cached
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.data.RushifyRepository
import java.math.BigDecimal
import java.math.RoundingMode

data class DismissUi(val busy: Boolean = false, val error: String? = null)

class DashboardViewModel(private val repo: RushifyRepository) : CachedViewModel<Dashboard>() {
    val dismiss = kotlinx.coroutines.flow.MutableStateFlow(DismissUi())
    override suspend fun readCache() = repo.dashboardCached()
    override suspend fun fetch() = repo.dashboard()

    /** "Dismiss" on the symbols reminder: hides it server-side until the pending symbols change, then reloads. */
    fun dismissTickerAttention() {
        if (dismiss.value.busy) return
        viewModelScope.launch {
            dismiss.value = DismissUi(busy = true)
            try {
                repo.call { repo.api.dismissTickerBanner() }
                dismiss.value = DismissUi()
                refresh()
            } catch (e: ApiError) {
                dismiss.value = DismissUi(error = e.message)
            }
        }
    }
}

fun bucketLabel(bucket: String) = when (bucket) {
    "equity" -> "Equities & funds"
    "real_estate" -> "Property (net)"
    "cash" -> "Cash savings"
    "other" -> "Other"
    else -> bucket.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

const val ADMIN_REMINDER_SHORT = "You're signed in as an admin. Admin tools are web-only. For your portfolio on this phone, sign in with your personal account."

/** Same alerts as the web's "Alerts & reminders": incomplete manual lots, and symbols to confirm (with Dismiss). */
@Composable
private fun AlertsCard(d: Dashboard, onOpen: (Dest) -> Unit, dismiss: DismissUi, onDismissTickers: () -> Unit, acc: HomeAccordion) {
    val incomplete = d.manualBasisIncompleteCount
    val tickers = d.tickerMapAttentionCount
    if (incomplete <= 0 && tickers <= 0) return
    val summary = listOfNotNull(if (incomplete > 0) "$incomplete incomplete lot${if (incomplete == 1) "" else "s"}" else null, if (tickers > 0) "$tickers ticker${if (tickers == 1) "" else "s"} to confirm" else null).joinToString(" · ")
    acc.Section("alerts", "Alerts & reminders", summary) { Column(Modifier.testTag("alerts_card")) {
        if (incomplete > 0) {
            Banner("$incomplete manual listed lot${if (incomplete == 1) " is" else "s are"} missing a purchase date or total GBP cost. Open each lot in Holdings and choose Edit lot so cost vs gain is correct.", RColors.Warn, Modifier.testTag("alert_incomplete"))
            Spacer(Modifier.height(8.dp))
        }
        if (tickers > 0) {
            Column(Modifier.testTag("alert_tickers")) {
                Text("Listings need your attention", color = RColors.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Muted("${Fmt.plural(tickers, "unresolved ticker")} from your broker. Open Corporate actions → Symbols to confirm and enter the listing to use.")
                Muted("Dismiss hides this temporarily; the reminder returns until the symbols are resolved.", Modifier.padding(top = 2.dp))
                dismiss.error?.let { Spacer(Modifier.height(4.dp)); Banner("Could not dismiss: $it", RColors.Loss) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Button(onClick = { onOpen(Dest.Corporate) }, modifier = Modifier.testTag("review_symbols")) { Text("Review symbols") }
                    androidx.compose.material3.TextButton(onClick = onDismissTickers, enabled = !dismiss.busy, modifier = Modifier.testTag("dismiss_symbols")) { Text(if (dismiss.busy) "…" else "Dismiss") }
                }
            }
        }
    } }
}

/** Open listed positions only: broker cash lines, Monzo, property and other manual assets are not positions. */
fun listedPositionCount(rows: List<com.rushify.mobile.data.HoldingRow>): Int =
    rows.count { it.source != "monzo" && it.assetKind !in NON_POSITION_KINDS && it.soldOutAt == null }

private val NON_POSITION_KINDS = setOf("cash", "real_estate", "other")

fun unrealisedPct(pnl: BigDecimal?, cost: BigDecimal?): BigDecimal? =
    if (pnl == null || cost == null || cost.signum() == 0) null else pnl.multiply(BigDecimal(100)).divide(cost, 2, RoundingMode.HALF_UP)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: DashboardViewModel, onOpen: (Dest) -> Unit = {}, isAdmin: Boolean = false, wealth: WealthViewModel? = null) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val hide = LocalHideBalances.current
    val d = s.data?.value
    val dismiss by vm.dismiss.collectAsState()
    PullToRefreshBox(isRefreshing = s.loading && d != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Home", color = RColors.Text, fontSize = 24.sp, modifier = Modifier.testTag("home_title"))
            if (isAdmin) Banner(ADMIN_REMINDER_SHORT, RColors.Accent, Modifier.testTag("admin_reminder_home"))
            AsOfBar(s.data?.fetchedAt, s.error, d != null, s.loading, label = d?.let { "Data as of ${Fmt.stamp(it.asOf)} · prices are end-of-day/cached" })
            if (d == null) {
                if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading your portfolio…")
            } else DashboardContent(d, hide, onOpen, dismiss, vm::dismissTickerAttention, wealth)
        }
    }
}

@Composable
private fun DashboardContent(d: Dashboard, hide: Boolean, onOpen: (Dest) -> Unit, dismiss: DismissUi, onDismissTickers: () -> Unit, wealth: WealthViewModel?) {
    val ccy = d.baseCurrency
    // Like the web: Investments opens and closes on its own; every other section is one-at-a-time.
    var investmentsOpen by rememberSaveable { mutableStateOf(true) }
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val acc = HomeAccordion(openSection) { id -> openSection = if (openSection == id) null else id }
    RCard {
        Muted("Net worth")
        Num(Fmt.money(d.netWorthTotalBase, ccy, hide), size = 30, bold = true, modifier = Modifier.testTag("net_worth"))
        Spacer(Modifier.height(4.dp))
        Muted("${Fmt.plural(listedPositionCount(d.rows), "listed position")} · ${Fmt.plural(d.rows.size, "line")} in total")
    }
    HomeCollapsible("investments", "Investments ($ccy)", InvestmentsSummary.of(d, hide), investmentsOpen, { investmentsOpen = !investmentsOpen }) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Listed value", Fmt.money(d.totalMarketValueBase, ccy, hide), Modifier.weight(1f))
                Stat("Cost", Fmt.money(d.totalCostBase, ccy, hide), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val pct = unrealisedPct(d.totalPnlBase, d.totalCostBase)
                Stat("Unrealised P/L", Fmt.signedMoney(d.totalPnlBase, ccy, hide) + (if (hide) "" else pct?.let { " (${Fmt.percent(it, signed = true)})" } ?: ""), Modifier.weight(1f), pnlColor(d.totalPnlBase))
                Stat("Realised P/L", Fmt.signedMoney(d.totalRealisedPnlBase, ccy, hide), Modifier.weight(1f), pnlColor(d.totalRealisedPnlBase))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("XIRR (annual)", Fmt.percentFromFraction(d.portfolioXirrAnnual, signed = true), Modifier.weight(1f), pnlColor(d.portfolioXirrAnnual))
                Stat("Dividend income", Fmt.money(d.dividendIncomeBrokerCashBase ?: d.dividendIncomeTotalBase, ccy, hide), Modifier.weight(1f))
            }
            val broker = d.brokerListedMarketValueBase; val manual = d.manualListedMarketValueBase
            if (broker != null && manual != null && broker.signum() > 0 && manual.signum() > 0) Muted("Listed value: broker ${Fmt.money(broker, ccy, hide)} · manual ${Fmt.money(manual, ccy, hide)}", Modifier.padding(top = 8.dp))
            else if (manual != null && manual.signum() > 0) Muted("Listed value includes manual stocks/ETFs: ${Fmt.money(manual, ccy, hide)}", Modifier.padding(top = 8.dp))
            Muted("Realised P/L is equity only, excluding dividends (FIFO on buy/sell fills).", Modifier.padding(top = 6.dp))
            Muted(if (d.dividendIncomeBrokerCashBase != null) "Dividend income: broker payouts including cash interest." else "Dividend income: estimated from fills and market data.", Modifier.padding(top = 2.dp))
            d.portfolioXirrNote?.let { Muted("XIRR is money-weighted (trades at FX, dividends, ending market value) and may differ from your broker's figure.", Modifier.padding(top = 2.dp)) }
            NonEquityGain.of(d)?.let { (gain, pct) ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.testTag("non_equity_gain")) {
                    Muted("Non-equity manual wealth gain/loss ($ccy)")
                    Num(Fmt.signedMoney(gain, ccy, hide) + (pct?.let { " · ${Fmt.percent(it, 2)} p.a. blended" } ?: ""), color = pnlColor(gain), size = 14)
                    Muted("Property, savings and other manual lines. Change vs opening amount, not listed equity.")
                }
            }
        }
    }
    AlertsCard(d, onOpen, dismiss, onDismissTickers, acc)
    PiesSection(d, ccy, hide, acc)
    PositionSizeSection(d.rows, ccy, hide, acc)
    CustodianSection(d.rows, ccy, hide, acc)
    if (wealth != null && hasManualNonListedLines(d.rows)) WealthSection(wealth, hide, acc)
    RCard(onClick = { onOpen(Dest.Performance) }) {
        SectionTitle("Performance & income")
        Muted("Growth attribution, benchmark comparison vs FTSE 100 and S&P 100, dividend income and forecast.")
        Spacer(Modifier.height(4.dp))
        Text("Open charts →", color = RColors.Accent, fontSize = 13.sp, modifier = Modifier.testTag("open_performance"))
    }
    FreshnessNote(acc)
    Spacer(Modifier.height(8.dp))
}

/** "Portfolio & net worth": the sector donut with drill-down, plus the net worth split by asset class. */
@Composable
private fun PiesSection(d: Dashboard, ccy: String, hide: Boolean, acc: HomeAccordion) {
    val sectors = remember(d.rows) { Sectors.build(d.rows) }
    if (sectors.isEmpty() && d.netWorthBreakdown.isEmpty()) return
    val summary = if (sectors.isEmpty()) "Sector donut and optional net worth split."
        else "${sectors.size} sectors · listed total ${Fmt.money(sectors.fold(BigDecimal.ZERO) { a, s -> a + s.value }, ccy, hide, 0)}."
    acc.Section("pies", "Portfolio & net worth", summary) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sectors.isNotEmpty()) { Muted("Portfolio by sector"); SectorContent(d.rows, ccy, hide) }
            if (d.netWorthBreakdown.isNotEmpty()) {
                Muted("Net worth by asset class")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Donut(d.netWorthBreakdown.mapIndexed { i, b -> Slice(bucketLabel(b.bucket), (b.valueBase ?: BigDecimal.ZERO).toFloat(), RColors.Chart[i % RColors.Chart.size]) })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.netWorthBreakdown.forEachIndexed { i, b ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(RColors.Chart[i % RColors.Chart.size]))
                                Spacer(Modifier.padding(start = 8.dp))
                                Column {
                                    Text(bucketLabel(b.bucket), color = RColors.Text, fontSize = 13.sp)
                                    Num("${Fmt.money(b.valueBase, ccy, hide, 0)} · ${Fmt.percent(b.weightPct, 1)}", color = RColors.Muted, size = 11)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The one-line summary shown while Investments is collapsed (same parts as the web). */
object InvestmentsSummary {
    fun of(d: Dashboard, hide: Boolean): String {
        val ccy = d.baseCurrency
        val parts = buildList {
            d.totalMarketValueBase?.let { add("Listed value: ${Fmt.money(it, ccy, hide)}") }
            d.totalRealisedPnlBase?.let { add("Realised P/L: ${Fmt.signedMoney(it, ccy, hide)}") }
            d.totalPnlBase?.let { add("Unrealised P/L: ${Fmt.signedMoney(it, ccy, hide)}") }
            (d.dividendIncomeBrokerCashBase ?: d.dividendIncomeTotalBase)?.let { add("Dividend income: ${Fmt.money(it, ccy, hide)}") }
            d.portfolioXirrAnnual?.let { add("XIRR: ${Fmt.percentFromFraction(it, signed = true)}") }
        }
        return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }
}
