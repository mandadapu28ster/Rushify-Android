package com.rushify.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.RollupAccount
import com.rushify.mobile.data.RollupLine
import com.rushify.mobile.data.Rollups
import com.rushify.mobile.data.RushifyRepository
import java.math.BigDecimal

class RollupsViewModel(private val repo: RushifyRepository) : CachedViewModel<Rollups>() {
    override suspend fun readCache() = repo.rollupsCached()
    override suspend fun fetch() = repo.rollups()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingsScreen(vm: DashboardViewModel, rollupsVm: RollupsViewModel, manual: ManualViewModel? = null, onChanged: () -> Unit = {}, showTitle: Boolean = true) {
    val s by vm.state.collectAsState()
    val rs by rollupsVm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load(); rollupsVm.load() }
    val hide = LocalHideBalances.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(HoldingSort.Value) }
    var exposure by rememberSaveable { mutableStateOf(ExposureFilter.All) }
    var showSold by rememberSaveable { mutableStateOf(false) }
    // One account group open at a time keeps the page short (searching opens every match).
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var openMulti by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<DetailTarget?>(null) }
    var form by remember { mutableStateOf<ManualForm?>(null) }
    val d = s.data?.value
    val ccy = d?.baseCurrency ?: "GBP"
    val rollups = rs.data?.value ?: Rollups()
    val soldCount = remember(d) { d?.rows?.count { HoldingsLogic.isManualListedSold(it) } ?: 0 }
    val groups = remember(d, rollups, query, sort, exposure, showSold) {
        d?.let { HoldingGroups.build(it.rows, query, sort, rollups, exposure, showSold) } ?: emptyList()
    }
    val summaries = remember(d, rollups) { d?.let { AccountSummaries.build(rollups, it.rows) } ?: emptyList() }

    PullToRefreshBox(isRefreshing = (s.loading && d != null) || rs.loading, onRefresh = { vm.refresh(); rollupsVm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("holdings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (showTitle) { Text("Holdings", color = RColors.Text, fontSize = 24.sp); Spacer(Modifier.height(6.dp)) }
                AsOfBar(s.data?.fetchedAt, s.error, d != null, s.loading, label = d?.let { "Data as of ${Fmt.stamp(it.asOf)}" })
                if (rs.loading && d != null && rs.data == null) Muted("Updating broker rollups and account totals…", Modifier.padding(top = 4.dp))
                val incomplete = d?.manualBasisIncompleteCount ?: 0
                if (incomplete > 0) {
                    Spacer(Modifier.height(8.dp))
                    Banner("$incomplete manual listed lot${if (incomplete == 1) " is" else "s are"} missing a purchase date or total GBP cost. Open each lot in Holdings and choose Edit lot so cost vs gain is correct.", RColors.Warn, Modifier.testTag("incomplete_lots"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search ticker, name or sector") }, modifier = Modifier.fillMaxWidth().testTag("holdings_search"))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HoldingSort.entries.forEach { Chip(it.label, sort == it) { sort = it } } }
                Spacer(Modifier.height(8.dp))
                Muted("Listed broker positions")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposureFilter.entries.forEach { f -> Chip(f.label, exposure == f) { exposure = f } }
                }
                Muted(exposure.hint, Modifier.padding(top = 4.dp).testTag("exposure_hint"))
                if (soldCount > 0) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(showSold, { showSold = it }, modifier = Modifier.testTag("show_sold"))
                        Spacer(Modifier.padding(start = 8.dp))
                        Muted("Show sold positions ($soldCount closed manual lot${if (soldCount == 1) "" else "s"})")
                    }
                }
            }
            if (summaries.isNotEmpty()) item { AccountsSummaryCard(summaries, ccy, hide) }
            if (d == null) item { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading holdings…") }
            else if (groups.isEmpty()) item { Muted(if (query.isBlank()) "No positions yet. Sync a broker from More → Sync, or add one from More → Add holding." else "No matches for \"$query\".") }
            groups.forEach { g ->
                val open = query.isNotBlank() || g.key == expanded
                item(key = "g:" + g.key) {
                    RCard(onClick = { expanded = if (g.key == expanded) null else g.key }, modifier = Modifier.testTag("group_${g.key}")) {
                        GroupHeader(g, exposure, ccy, hide)
                    }
                }
                if (open) {
                    item(key = "gb:" + g.key) { GroupBody(g, rollups, exposure, ccy, hide, openMulti, { k -> openMulti = if (k in openMulti) openMulti - k else openMulti + k }) { row, rollup, primary -> selected = DetailTarget(row, rollup, primary) } }
                }
            }
        }
    }
    selected?.let { t ->
        ModalBottomSheet(onDismissRequest = { selected = null; form = null; manual?.clear() }, containerColor = RColors.Surface) {
            val fm = form
            if (fm != null && manual != null) ManualFormHost(fm, manual, onDone = { form = null; selected = null }, onBack = { form = null }, onChanged = onChanged)
            else HoldingDetail(t.row, ccy, hide, t.rollup, t.primary, actions = manual?.let { { ManualActions(t) { f -> form = f } } })
        }
    }
}

data class DetailTarget(val row: HoldingRow, val rollup: RollupLine?, val primary: Boolean)

// ---------------- Accounts summary (the web's "Broker accounts (local history)" table) ----------------

@Composable
private fun AccountsSummaryCard(summaries: List<AccountSummary>, ccy: String, hide: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val totals = AccountSummaries.totals(summaries)
    RCard(onClick = { open = !open }, modifier = Modifier.testTag("accounts_summary")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Broker accounts (local history)", color = RColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Muted(if (open) "▲" else "▼")
        }
        Muted(
            "${Fmt.plural(summaries.size, "account")}" + (totals?.let { " · realised ${Fmt.signedMoney(it.realised, ccy, hide)} · unrealised ${Fmt.signedMoney(it.unrealised, ccy, hide)}" } ?: ""),
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            Muted("Realised, unrealised, tax / FX / broker fees come from stored order fills. Dividend payouts are equity dividends only; interest on cash is reported separately; Payout is dividends plus interest.")
            summaries.forEach { a -> Spacer(Modifier.height(10.dp)); AccountSummaryBlock(a, ccy, hide) }
            totals?.let { t ->
                Spacer(Modifier.height(12.dp))
                Text("Total (all accounts)", color = RColors.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("accounts_total"))
                KV("Investments", Fmt.money(t.investments, ccy, hide)); KV("Realised", Fmt.signedMoney(t.realised, ccy, hide), pnlColor(t.realised))
                KV("Unrealised", Fmt.signedMoney(t.unrealised, ccy, hide), pnlColor(t.unrealised)); KV("Tax", Fmt.money(t.tax, ccy, hide))
                KV("FX fees", Fmt.money(t.fxFees, ccy, hide)); KV("Broker cost", Fmt.money(t.brokerCost, ccy, hide))
                KV("Interest on cash", Fmt.money(t.interest, ccy, hide)); KV("Dividend payouts", Fmt.money(t.dividends, ccy, hide)); KV("Payout", Fmt.money(t.payout, ccy, hide))
            }
        }
    }
}

@Composable
private fun AccountSummaryBlock(a: AccountSummary, ccy: String, hide: Boolean) {
    Text(a.label, color = RColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Muted(a.source)
    if (a.source == "monzo") {
        KV("Balance", Fmt.money(a.monzoTotal, ccy, hide)); Muted("Bank balance: no trades or payouts")
        return
    }
    val x = a.account ?: return
    val cashIsa = x.accountRef.trim().lowercase().endsWith(":cash_isa")
    KV(if (cashIsa) "Total deposits (principal)" else "Investments", Fmt.money(x.listedInvestmentsMvBaseSum, ccy, hide))
    KV("Realised", Fmt.signedMoney(x.realisedGainBaseSum, ccy, hide), pnlColor(x.realisedGainBaseSum))
    KV("Unrealised", Fmt.signedMoney(x.unrealisedPnlBaseSum, ccy, hide), pnlColor(x.unrealisedPnlBaseSum))
    KV("Tax", Fmt.money(x.fillTaxBaseSum, ccy, hide)); KV("FX fees", Fmt.money(x.fillFxFeesBaseSum, ccy, hide)); KV("Broker cost", Fmt.money(x.fillBrokerFeesBaseSum, ccy, hide))
    KV("Interest on cash", Fmt.money(x.interestOnCashBaseSum, ccy, hide)); KV("Dividend payouts", Fmt.money(x.dividendPayoutBaseSum, ccy, hide)); KV("Payout", Fmt.money(x.combinedBrokerPayoutBaseSum, ccy, hide))
}

@Composable
private fun KV(label: String, value: String, color: Color = RColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted(label); Num(value, color = color, size = 12) }
}

// ---------------- Group header + body ----------------

@Composable
private fun GroupHeader(g: HoldingGroup, exposure: ExposureFilter, ccy: String, hide: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(g.name, color = RColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        g.badge?.let { Muted(it.uppercase()) }
    }
    val nClosed = g.closed.size
    val line1 = when {
        g.kind == GroupKind.MONZO -> "${Fmt.plural(g.rows.size, "balance")} · total value ${Fmt.money(g.totalValue, ccy, hide)}"
        g.brokerLike || g.kind == GroupKind.MANUAL_LISTED -> if (exposure == ExposureFilter.Closed) {
            (if (g.listedRows.isEmpty()) "No open broker lines" else "${Fmt.plural(g.listedRows.size, "open line")}") + if (nClosed > 0) " · ${Fmt.plural(nClosed, "closed instrument")} (order history)" else ""
        } else buildString {
            append(Fmt.plural(g.positionCount, "position"))
            if (exposure == ExposureFilter.All && g.hasCash) append(" · cash ${Fmt.money(g.cashValue, ccy, hide)}")
            append(" · total qty ${Fmt.quantity(g.totalQty)} · total value ${Fmt.money(g.totalValue, ccy, hide)}")
        }
        else -> "${Fmt.plural(g.rows.size, "line")} · total value ${Fmt.money(g.totalValue, ccy, hide)}"
    }
    Muted(line1)
    val acc = g.account
    if (g.kind == GroupKind.T212 || g.kind == GroupKind.SNAPTRADE) {
        val payouts = acc?.combinedBrokerPayoutBaseSum ?: acc?.dividendPayoutBaseSum
        HeaderMetric("Realised", acc?.realisedGainBaseSum, ccy, hide, signed = true)
        HeaderMetric("Unrealised", g.totalPnl, ccy, hide, signed = true)
        HeaderMetric("Cash payouts", payouts, ccy, hide)
        Muted("XIRR: ${Fmt.percentFromFraction(acc?.xirrAnnual)}", Modifier.testTag("xirr_${g.key}"))
    } else if (g.kind == GroupKind.CSV) {
        HeaderMetric("Realised", acc?.realisedGainBaseSum, ccy, hide, signed = true); HeaderMetric("Unrealised", g.totalPnl, ccy, hide, signed = true)
    } else if (g.kind == GroupKind.MANUAL_LISTED) {
        HeaderMetric("Unrealised", g.totalPnl, ccy, hide, signed = true)
    }
}

@Composable
private fun HeaderMetric(label: String, v: BigDecimal?, ccy: String, hide: Boolean, signed: Boolean = false) {
    Row {
        Muted("$label: ")
        Num(if (signed) Fmt.signedMoney(v, ccy, hide) else Fmt.money(v, ccy, hide), size = 12, color = if (signed) pnlColor(v) else RColors.Text)
    }
}

@Composable
private fun GroupBody(
    g: HoldingGroup, rollups: Rollups, exposure: ExposureFilter, ccy: String, hide: Boolean,
    openMulti: Set<String>, toggleMulti: (String) -> Unit, onPick: (HoldingRow, RollupLine?, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (g.kind == GroupKind.MONZO) {
            HoldingsLogic.monzoSections(g.rows).forEach { (title, rows) ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = RColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Num(Fmt.money(rows.fold(BigDecimal.ZERO) { a, r -> a + (r.marketValueBase ?: BigDecimal.ZERO) }, ccy, hide), size = 12)
                }
                rows.forEach { r -> MonzoRowItem(r, ccy, hide) { onPick(r, null, true) } }
            }
            return
        }
        val items = HoldingGroups.items(g, rollups, exposure)
        val primary = HoldingGroups.primaryMetricRows(HoldingGroups.displayOrder(items), rollups.lines, exposure)
        items.forEach { item ->
            when (item) {
                is HoldingItem.Single -> RowLine(item.row, rollups, exposure, ccy, hide, primary, onPick)
                is HoldingItem.Multi -> {
                    val key = g.key + "|" + item.ticker
                    MultiHeader(item, g.kind, ccy, hide, key in openMulti) { toggleMulti(key) }
                    if (key in openMulti) item.rows.forEachIndexed { i, r -> Column(Modifier.padding(start = 12.dp)) { RowLine(r, rollups, exposure, ccy, hide, primary, onPick, lotLabel = lotLabel(r, g.kind, i)) } }
                }
            }
        }
        if (g.kind == GroupKind.T212 || g.kind == GroupKind.SNAPTRADE || g.kind == GroupKind.CSV) {
            if (g.closed.isNotEmpty()) {
                Text("Closed (order history, no open quantity)", color = RColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                g.closed.forEach { ClosedCard(it, ccy, hide) }
            } else if (exposure == ExposureFilter.Closed) Muted("No closed instruments in order history for this account.", Modifier.padding(top = 8.dp))
        }
    }
}

private fun lotLabel(r: HoldingRow, kind: GroupKind, i: Int) =
    if (kind == GroupKind.MANUAL_LISTED) "Lot · ${r.purchaseDate ?: "no date"}${r.manualHoldingId?.let { " · id $it" } ?: ""}" else "Line ${i + 1} · ${r.currency ?: ""}"

@Composable
private fun MultiHeader(m: HoldingItem.Multi, kind: GroupKind, ccy: String, hide: Boolean, open: Boolean, onClick: () -> Unit) {
    val noun = when (kind) { GroupKind.MANUAL_LISTED -> "lots"; GroupKind.SNAPTRADE -> "SnapTrade lines"; GroupKind.CSV -> "CSV import lines"; GroupKind.T212 -> "Trading 212 lines"; else -> "lines" }
    val value = m.rows.fold(BigDecimal.ZERO) { a, r -> a + (r.marketValueBase ?: BigDecimal.ZERO) }
    val pnl = m.rows.mapNotNull { it.pnlStockBase }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp).testTag("multi_${m.ticker}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Num(m.ticker, bold = true); Num(Fmt.money(value, ccy, hide))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("${m.rows.size} $noun · total qty ${Fmt.quantity(m.rows.fold(BigDecimal.ZERO) { a, r -> a + (r.quantity ?: BigDecimal.ZERO) })} ${if (open) "▲" else "▼"}")
            Num(Fmt.signedMoney(pnl, ccy, hide), color = pnlColor(pnl), size = 12)
        }
    }
}

@Composable
private fun RowLine(
    r: HoldingRow, rollups: Rollups, exposure: ExposureFilter, ccy: String, hide: Boolean, primary: Map<String, String>,
    onPick: (HoldingRow, RollupLine?, Boolean) -> Unit, lotLabel: String? = null,
) {
    val rollup = HoldingsLogic.findRollupForRow(r, rollups.lines, exposure)
    val isPrimary = rollup == null || primary[HoldingsLogic.rollupIdentityKey(rollup)] == HoldingsLogic.rowSlotId(r)
    val sold = HoldingsLogic.isManualListedSold(r)
    Row(Modifier.fillMaxWidth().clickable { onPick(r, rollup, isPrimary) }.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Num(HoldingsLogic.displayTicker(r, rollup), bold = true)
            Muted(lotLabel ?: r.instrumentName ?: r.sector ?: "")
            val tags = listOfNotNull(if (sold) "Sold" else null, rollup?.let { HoldingsLogic.rollupExposureLabel(it.exposure) })
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = if (sold) RColors.Warn else RColors.Muted, fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Num(Fmt.money(r.marketValueBase, ccy, hide))
            Num(Fmt.signedMoney(r.pnlStockBase, ccy, hide), color = pnlColor(r.pnlStockBase), size = 12)
        }
    }
}

@Composable
private fun MonzoRowItem(r: HoldingRow, ccy: String, hide: Boolean, onClick: () -> Unit) {
    val badge = HoldingsLogic.monzoKindBadge(r.accountKind)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(HoldingsLogic.monzoRowName(r), color = RColors.Text, fontSize = 14.sp)
            badge?.let { Muted(it) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Num(Fmt.money(r.marketValueBase, ccy, hide))
            if (r.currency != null && r.currency != ccy && r.quantity != null) Muted("(${r.currency} ${Fmt.number(r.quantity)})")
        }
    }
}

@Composable
private fun ClosedCard(l: RollupLine, ccy: String, hide: Boolean) {
    val sym = HoldingsLogic.rollupDisplaySymbol(l)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp).testTag("closed_$sym")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Num(sym, bold = true); Muted("Closed (history)")
        }
        l.instrumentName?.let { Muted(it) }
        if (!l.brokerTicker.isNullOrBlank() && !l.brokerTicker.equals(sym, true) && HoldingsLogic.stripT212VenueSuffix(l.brokerTicker).uppercase() != sym.uppercase()) Muted(l.brokerTicker)
        KV("P/L (FIFO realised)", Fmt.signedMoney(l.fifoRealisedBase, ccy, hide), pnlColor(l.fifoRealisedBase))
        KV("Bought (qty)", Fmt.quantity(l.buyFillQty)); KV("Sold (qty)", Fmt.quantity(l.sellFillQty)); KV("Fill rows", l.fillRowCount.toString())
        KV("Tax", Fmt.money(l.fillTaxBaseSum ?: BigDecimal.ZERO, ccy, hide)); KV("FX fees", Fmt.money(l.fillFxFeesBaseSum ?: BigDecimal.ZERO, ccy, hide)); KV("Broker fees", Fmt.money(l.fillBrokerFeesBaseSum ?: BigDecimal.ZERO, ccy, hide))
        DividendsBlock(l, ccy, hide)
        Muted("Realised uses FIFO on stored fills; fees are the sums on those fills.")
    }
}

/** Broker-reported cash dividends (or a market-data estimate for manual lots), like the web's dividends block. */
@Composable
fun DividendsBlock(l: RollupLine, base: String, hide: Boolean) {
    val n = l.dividendPayoutCount
    if (n <= 0) return
    val rows = l.dividendsByCurrency
    val baseSum = l.dividendPayoutBaseSum
    val redundant = rows.size == 1 && baseSum != null && rows[0].currency.trim().equals(base.trim(), true) && rows[0].amount != null && rows[0].amount!!.compareTo(baseSum) == 0
    Column(Modifier.padding(top = 4.dp).testTag("dividends_block")) {
        Muted(if (l.source == "manual") "Estimated dividends (market data)" else "Broker cash dividends")
        Num("$n payouts", size = 12)
        if (!redundant) Muted(if (rows.isEmpty()) "—" else rows.joinToString(" · ") { "${it.currency} ${Fmt.number(it.amount)}" })
        Muted("Total ($base): ${if (baseSum != null) Fmt.money(baseSum, base, hide) else "— (could not convert all payouts: missing FX)"}")
    }
}

// ---------------- Detail sheet ----------------

@Composable
fun HoldingDetail(r: HoldingRow, ccy: String, hide: Boolean, rollup: RollupLine? = null, primary: Boolean = true, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("holding_detail"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Num(HoldingsLogic.displayTicker(r, rollup), size = 20, bold = true)
        Muted(listOfNotNull(r.instrumentName, r.brokerLabel).joinToString(" · "))
        val sold = HoldingsLogic.isManualListedSold(r)
        val tags = listOfNotNull(if (sold) "Sold" else null, rollup?.let { HoldingsLogic.rollupExposureLabel(it.exposure) })
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = if (sold) RColors.Warn else RColors.Accent, fontSize = 12.sp)
        if (sold) Banner("Fully exited${r.soldOutAt?.let { " on $it" } ?: ""}. Kept for your records.", RColors.Warn)
        Spacer(Modifier.height(4.dp))
        DetailRow("Sector", r.sector ?: Fmt.DASH)
        DetailRow("Quantity", Fmt.quantity(r.quantity))
        DetailRow("Currency", r.currency ?: Fmt.DASH)
        DetailRow("Average price", Fmt.money(r.avgPrice, r.currency, hide))
        DetailRow("Current price", Fmt.money(r.currentPrice, r.currency, hide))
        DetailRow("Market value", Fmt.money(r.marketValueBase, ccy, hide))
        DetailRow("Cost", Fmt.money(r.costBase, ccy, hide))
        DetailRow("P/L (price)", Fmt.signedMoney(r.pnlStockBase, ccy, hide), pnlColor(r.pnlStockBase))
        r.purchaseDate?.let { DetailRow("Purchase date", it) }
        if (r.source == "manual" && HoldingsLogic.isListedAssetKind(r.assetKind)) {
            val set = r.purchaseDate != null && r.costBaseGbp != null
            if (set) DetailRow("Purchase & cost (GBP)", "${r.purchaseDate} · ${Fmt.money(r.costBaseGbp, "GBP", hide)}")
            else Text("Purchase & cost (GBP): not set. Use Edit lot below.", color = RColors.Warn, fontSize = 12.sp, modifier = Modifier.testTag("basis_not_set"))
            if (set) DetailRow("FX effect (approx.)", if (r.pnlCurrencyEffect != null) Fmt.signedMoney(r.pnlCurrencyEffect, ccy, hide) else Fmt.DASH, pnlColor(r.pnlCurrencyEffect))
        }
        if (rollup != null) {
            Spacer(Modifier.height(4.dp))
            if (primary) {
                DetailRow("Tax", Fmt.money(rollup.fillTaxBaseSum ?: BigDecimal.ZERO, ccy, hide))
                DetailRow("FX fees", Fmt.money(rollup.fillFxFeesBaseSum ?: BigDecimal.ZERO, ccy, hide))
                DetailRow("Broker fees", Fmt.money(rollup.fillBrokerFeesBaseSum ?: BigDecimal.ZERO, ccy, hide))
                DividendsBlock(rollup, ccy, hide)
            } else Muted("Fees and dividends for this symbol are shown on its first line.")
        }
        Spacer(Modifier.height(4.dp))
        DetailRow("P/E", Fmt.number(r.peRatio))
        DetailRow("P/B", Fmt.number(r.pbRatio))
        DetailRow("PEG", Fmt.number(r.pegRatio))
        DetailRow("P/S", Fmt.number(r.priceToSales))
        DetailRow("Dividend yield", Fmt.percentFromFraction(r.dividendYield))
        DetailRow("ROE", Fmt.percentFromFraction(r.roe))
        DetailRow("Debt / equity", Fmt.number(r.debtToEquity))
        DetailRow("Profit margin", Fmt.percent(r.profitMargin))
        DetailRow("Revenue", Fmt.abbreviate(r.revenue))
        Muted("Prices are end-of-day / cached, not live.")
        actions?.invoke()
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color = RColors.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted(label); Num(value, color = color, size = 13) }
}
