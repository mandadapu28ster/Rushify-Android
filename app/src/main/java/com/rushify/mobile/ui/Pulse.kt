package com.rushify.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.News
import com.rushify.mobile.data.NewsItem
import com.rushify.mobile.data.Radar
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SymbolHit
import com.rushify.mobile.data.WatchItem
import com.rushify.mobile.data.WatchItemCreate
import com.rushify.mobile.data.WatchItemPatch
import com.rushify.mobile.data.Watchlist
import com.rushify.mobile.data.WatchlistCreate
import com.rushify.mobile.data.Watchlists
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RadarViewModel(private val repo: RushifyRepository) : CachedViewModel<Radar>() {
    var scope = "overall"
    override suspend fun readCache() = repo.radarCached(scope)
    override suspend fun fetch() = repo.radar(scope)
    fun select(key: String) {
        if (key == scope) return
        scope = key
        reload()
    }
}

class NewsViewModel(private val repo: RushifyRepository) : CachedViewModel<News>() {
    private var force = false
    override suspend fun readCache() = repo.newsCached()
    override suspend fun fetch() = repo.news(force).also { force = false }
    fun forceRefresh() { force = true; refresh() }
}

data class WatchRefreshUi(val busy: Boolean = false, val message: String? = null, val failed: Boolean = false)
data class WatchEditUi(val busy: Boolean = false, val message: String? = null, val error: String? = null)
data class SymbolSearchUi(val query: String = "", val results: List<SymbolHit> = emptyList(), val searching: Boolean = false)

/** Same wording as the web watchlist panel. */
object WatchlistRefreshMessage {
    fun of(r: com.rushify.mobile.data.WatchlistRefresh): String {
        val base = when {
            r.allFresh && r.refreshed == 0 -> "Up to date (within ${r.cacheHours}h)."
            r.refreshed > 0 -> "Refreshed ${r.refreshed} symbol${if (r.refreshed == 1) "" else "s"}. Open Pulse → News and tap Refresh there for headlines."
            else -> "No symbols to refresh."
        }
        return if (r.errors.isNotEmpty()) "$base ${r.errors.size} could not be refreshed." else base
    }
}

class WatchlistsViewModel(private val repo: RushifyRepository) : CachedViewModel<Watchlists>() {
    val refreshUi = MutableStateFlow(WatchRefreshUi())
    val edit = MutableStateFlow(WatchEditUi())
    val search = MutableStateFlow(SymbolSearchUi())
    private var searchJob: Job? = null

    override suspend fun readCache() = repo.watchlistsCached()
    override suspend fun fetch() = repo.watchlists()

    /** Forced server refresh (force=true), then re-read the lists so the new quotes actually appear. */
    fun forceRefresh() {
        if (refreshUi.value.busy) return
        viewModelScope.launch {
            refreshUi.value = WatchRefreshUi(busy = true)
            try {
                val (summary, fresh) = repo.refreshWatchlistsNow()
                state.update { Loadable(data = fresh) }
                refreshUi.value = WatchRefreshUi(message = WatchlistRefreshMessage.of(summary))
            } catch (e: ApiError) {
                refreshUi.value = WatchRefreshUi(message = "Refresh failed: ${e.message}", failed = true)
            }
        }
    }

    // ---- editing: every change is followed by a re-read, so the screen always shows the server's truth ----

    private fun change(done: String?, call: suspend () -> Unit) {
        if (edit.value.busy) return
        viewModelScope.launch {
            edit.value = WatchEditUi(busy = true)
            try {
                call()
                val fresh = repo.watchlists()
                state.update { Loadable(data = fresh) }
                edit.value = WatchEditUi(message = done)
            } catch (e: ApiError) {
                edit.value = WatchEditUi(error = e.message)
            }
        }
    }

    fun createList(name: String) {
        val current = state.value.data?.value
        if (!WatchlistRules.canCreate(current, name)) { edit.value = WatchEditUi(error = if (WatchlistRules.atListCap(current)) "Maximum lists reached." else "Enter a name (up to ${WatchlistRules.NAME_MAX} characters)."); return }
        change("List created.") { repo.call { repo.api.createWatchlist(WatchlistCreate(name.trim())) } }
    }

    fun deleteList(id: Int) = change("List deleted.") { repo.call { repo.api.deleteWatchlist(id) } }

    fun addSymbol(list: Watchlist, hit: SymbolHit, fetchNews: Boolean) {
        if (WatchlistRules.atItemCap(state.value.data?.value, list)) { edit.value = WatchEditUi(error = "This list is full."); return }
        clearSearch()
        change("${hit.symbol} added.") { repo.call { repo.api.addWatchItem(list.id, WatchItemCreate(hit.symbol, hit.name, hit.exchange, fetchNews)) } }
    }

    fun toggleNews(list: Watchlist, item: WatchItem) = change(null) { repo.call { repo.api.patchWatchItem(list.id, item.id, WatchItemPatch(!item.fetchNews)) } }
    fun removeSymbol(list: Watchlist, item: WatchItem) = change("${item.yahooSymbol} removed.") { repo.call { repo.api.deleteWatchItem(list.id, item.id) } }
    fun clearEditMessage() { edit.value = WatchEditUi() }

    /** Debounced symbol search: starts at 2 characters, 350 ms after the last keystroke; failures just clear the results. */
    fun onQuery(q: String) {
        searchJob?.cancel()
        search.value = SymbolSearchUi(query = q)
        if (!WatchlistRules.searchable(q)) return
        searchJob = viewModelScope.launch {
            delay(350)
            search.update { it.copy(searching = true) }
            val hits = try { repo.call { repo.api.symbolSearch(q.trim()) } } catch (e: ApiError) { emptyList() }
            search.update { if (it.query == q) it.copy(results = hits, searching = false) else it }
        }
    }
    fun clearSearch() { searchJob?.cancel(); search.value = SymbolSearchUi() }
}

@Composable
fun PulseScreen(radar: RadarViewModel, news: NewsViewModel, watch: WatchlistsViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Pulse", color = RColors.Text, fontSize = 24.sp)
            Muted("Market news, watchlists and radar.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Radar", "News", "Watchlists").forEachIndexed { i, t -> Chip(t, tab == i) { tab = i } }
            }
        }
        when (tab) {
            0 -> RadarTab(radar)
            1 -> NewsTab(news)
            else -> WatchlistTab(watch)
        }
    }
}

// ============================================================ Radar

object RadarColors {
    /** Score bands, as on the web. */
    fun score(v: Int): Color = when { v >= 72 -> Color(0xFF22C55E); v >= 58 -> Color(0xFF84CC16); v >= 45 -> Color(0xFFEAB308); v >= 32 -> Color(0xFFF97316); else -> Color(0xFFEF4444) }
    fun bucket(key: String): Color = when (key) {
        "strong_buy" -> Color(0xFF15803D); "buy" -> Color(0xFF4ADE80); "neutral" -> Color(0xFFEAB308); "sell" -> Color(0xFFF87171)
        "strong_sell" -> Color(0xFFB91C1C); "index_fund" -> Color(0xFF6366F1); else -> Color(0xFF64748B)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadarTab(vm: RadarViewModel) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val r = s.data?.value
    var openBucket by rememberSaveable { mutableStateOf<String?>(null) }
    PullToRefreshBox(isRefreshing = s.loading && r != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AsOfBar(s.data?.fetchedAt, s.error, r != null, s.loading)
            if (r == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading portfolio radar…"); return@Column }
            if (r.accounts.size > 1 || r.scopeKey != "overall") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.accounts.forEach { a -> Chip("${a.label} · ${Fmt.plural(a.positionCount, "holding")}", a.key == r.scopeKey, "scope_${a.key}") { vm.select(a.key) } }
                }
            }
            r.message?.let { Banner(it) }
            if (r.positionCount <= 0) return@Column
            RCard {
                r.headline?.let { Text(it, color = RColors.Text, fontWeight = FontWeight.SemiBold) }
                r.summary?.let { Muted(it, Modifier.padding(top = 4.dp)) }
                if (r.scoredMvPct < 100 || r.indexFundMvPct > 0 || r.notCoveredMvPct > 0) {
                    Muted("MV coverage: ${r.scoredMvPct}% scored equities" + (if (r.indexFundMvPct > 0) " · ${r.indexFundMvPct}% index/funds" else "") + (if (r.notCoveredMvPct > 0) " · ${r.notCoveredMvPct}% not scored" else ""), Modifier.padding(top = 6.dp).testTag("mv_coverage"))
                }
            }
            if (r.rewards.isNotEmpty() || true) RCard {
                SectionTitle("Rewards")
                if (r.rewards.isEmpty()) Muted("No standout positives in this scope.") else r.rewards.forEach { Text("★ " + it.text, color = RColors.Gain, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            RCard {
                SectionTitle("Risk checks")
                if (r.risks.isEmpty()) Muted("No major risk flags from fundamentals rules.") else r.risks.forEach { Text("! " + it.text, color = RColors.Warn, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            if (r.dimensions.isNotEmpty()) RCard {
                SectionTitle("Five fundamentals axes")
                Muted("Portfolio-weighted. The dashed outline is the benchmark (100 on every axis).")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RadarChart(r.dimensions.map { it.label }, r.dimensions.map { it.score }) }
                r.dimensions.forEach { d ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("dimension_${d.key}")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(d.label, color = RColors.Text, fontSize = 13.sp); Num("${d.score}", color = RadarColors.score(d.score), size = 13, bold = true) }
                        HBar(listOf(d.coveragePct.toFloat() to RColors.Muted.copy(alpha = 0.6f)), 100f, height = 5.dp)
                        Muted("${d.coveragePct}% of holdings with data")
                    }
                }
                Muted("${r.coveredPositionCount} of ${r.positionCount} positions with enough fundamentals" + (r.listedMvBase?.let { " · ${Fmt.money(it, r.baseCurrency, LocalHideBalances.current, 0)} MV" } ?: ""), Modifier.padding(top = 6.dp))
                Muted("Larger shape = stronger fundamentals. Colour reflects overall quality.")
            }
            RCard {
                SectionTitle("How healthy is this portfolio?")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { r.healthScore?.let { HealthGauge(it.toFloat(), (r.healthMax ?: java.math.BigDecimal(5)).toFloat(), Modifier.testTag("health_gauge")) } }
                Muted("Portfolio health, 1 to ${(r.healthMax ?: java.math.BigDecimal(5)).toInt()}", Modifier.fillMaxWidth(), TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                r.ratingBuckets.forEach { b ->
                    val open = openBucket == b.key
                    Column(Modifier.fillMaxWidth().clickable(enabled = b.stockCount > 0) { openBucket = if (open) null else b.key }.padding(vertical = 6.dp).testTag("bucket_${b.key}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(RadarColors.bucket(b.key))); Spacer(Modifier.width(8.dp))
                            Text(b.label, color = RColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Muted("${b.mvPct}% MV · ${Fmt.plural(b.stockCount, "holding")}" + if (b.stockCount > 0) (if (open) "  ▾" else "  ▸") else "")
                        }
                        if (open) Muted(b.tickers.joinToString(", "), Modifier.padding(start = 18.dp, top = 2.dp))
                    }
                }
            }
            Muted("Scores are heuristic quality tilts (not analyst buy/sell calls). Value is sector-relative where possible; health uses leverage, liquidity and FCF yield; index funds use a neutral model profile. Refresh Home after a market-data sync for the latest fundamentals.", Modifier.testTag("radar_disclaimer"))
        }
    }
}

// ============================================================ News

fun sourceLabel(src: String?) = when (src?.lowercase()) { "yahoo" -> "Yahoo"; "google" -> "Google News"; null, "" -> null; else -> src }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsTab(vm: NewsViewModel) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val n = s.data?.value
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    PullToRefreshBox(isRefreshing = s.loading && n != null, onRefresh = vm::forceRefresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Portfolio news", color = RColors.Text, fontWeight = FontWeight.SemiBold); Muted("Holdings plus watchlist symbols with Fetch news on, grouped by company.") }
                Chip(if (s.loading) "Refreshing…" else "Refresh", false, "news_refresh") { vm.forceRefresh() }
            }
            AsOfBar(s.data?.fetchedAt, s.error, n != null, s.loading, label = n?.let { "Updated ${Fmt.stamp(it.lastSyncedAt)}" + if (it.stale) " · tap Refresh for latest" else "" })
            if (n == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading portfolio news…"); return@Column }
            n.message?.let { Banner(it) }
            if (n.cachedOnly) Muted("Showing cached headlines only (live fetch disabled).")
            val groups = n.groups.filter { it.items.isNotEmpty() }
            val total = groups.sumOf { it.items.size }
            if (total == 0) {
                Muted(if (n.symbols.isEmpty()) "No symbols to fetch yet. Add holdings, or add watchlist symbols with Fetch news on, then tap Refresh."
                      else "No headlines in the current window for ${n.symbols.take(6).joinToString(", ")}${if (n.symbols.size > 6) " (+${n.symbols.size - 6} more)" else ""}. Turn on Fetch news for watchlist symbols or tap Refresh.", Modifier.testTag("news_empty"))
                return@Column
            }
            if (groups.size > 1) {
                if (filter != null && groups.none { it.key == filter }) filter = null
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All ($total)", filter == null, "news_filter_all") { filter = null }
                    groups.forEach { g -> Chip("${g.label} (${g.items.size})", filter == g.key, "news_filter_${g.key}") { filter = g.key } }
                }
            }
            val shown = if (filter == null) groups else groups.filter { it.key == filter }
            shown.forEachIndexed { idx, g ->
                val isOpen = g.key in open || (filter != null) || (filter == null && idx == 0 && open.isEmpty())
                RCard(onClick = { open = if (g.key in open) open - g.key else open + g.key }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(g.label, color = RColors.Text, fontWeight = FontWeight.SemiBold); Muted(g.symbol ?: "Market-wide") }
                        Muted("${g.items.size} ${if (isOpen) "▲" else "▼"}")
                    }
                    if (!isOpen) Muted(g.items.first().title, Modifier.padding(top = 4.dp))
                    if (isOpen) g.items.forEach { Headline(it) { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } } }
                }
            }
        }
    }
}

@Composable
private fun Headline(it: NewsItem, openUrl: (String) -> Unit) {
    val safe = SafeUrl.httpOrNull(it.url)   // third-party feed data: only http(s) is ever opened
    Column(Modifier.fillMaxWidth().clickable(enabled = safe != null) { safe?.let(openUrl) }.padding(vertical = 6.dp)) {
        Muted(listOfNotNull(sourceLabel(it.source), it.publisher, Fmt.stamp(it.publishedAt).takeIf { s -> s != Fmt.DASH }).joinToString(" · "))
        Text(NewsText.plain(it.title), color = RColors.Text, fontSize = 13.sp)
        NewsText.summary(it.title, it.summary, it.publisher)?.let { s -> Text(s, color = RColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

// ============================================================ Watchlists

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistTab(vm: WatchlistsViewModel) {
    val s by vm.state.collectAsState()
    val r by vm.refreshUi.collectAsState()
    val e by vm.edit.collectAsState()
    val sr by vm.search.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(e.message) { if (e.message != null) { delay(6_000); vm.clearEditMessage() } }
    val w = s.data?.value
    var newName by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var adding by rememberSaveable { mutableStateOf<Int?>(null) }
    var fetchNewsOnAdd by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<Watchlist?>(null) }
    PullToRefreshBox(isRefreshing = (s.loading && w != null) || r.busy, onRefresh = vm::forceRefresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Watchlists", color = RColors.Text, fontWeight = FontWeight.SemiBold)
            Muted("Pull down to fetch the latest quotes and fundamentals. Tick News to include a symbol in Pulse → News.")
            AsOfBar(s.data?.fetchedAt, s.error, w != null, s.loading, label = s.data?.fetchedAt?.let { "Updated ${Fmt.stampMillis(it)} · prices are end-of-day/cached" })
            r.message?.let { Banner(it, if (r.failed) RColors.Loss else RColors.Accent, Modifier.testTag("watch_refresh_message")) }
            e.message?.let { Banner(it, RColors.Accent, Modifier.testTag("watch_edit_message")) }
            e.error?.let { Banner(it, RColors.Loss, Modifier.testTag("watch_edit_error")) }
            if (w == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading watchlists…"); return@Column }
            // create a list
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { if (it.length <= WatchlistRules.NAME_MAX) newName = it }, singleLine = true, enabled = !WatchlistRules.atListCap(w) && !e.busy,
                    label = { Text(if (WatchlistRules.atListCap(w)) "Maximum lists reached" else "New watchlist name") }, modifier = Modifier.weight(1f).testTag("watch_new_name"))
                Button(onClick = { vm.createList(newName); newName = "" }, enabled = WatchlistRules.canCreate(w, newName) && !e.busy, modifier = Modifier.testTag("watch_create")) { Text("Create") }
            }
            if (w.watchlists.isEmpty()) Muted("Create a watchlist, then search for a company to add your first symbol.")
            w.watchlists.forEach { list ->
                val isOpen = list.id in open
                RCard(Modifier.testTag("watchlist_${list.id}")) {
                    Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) open - list.id else open + list.id }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(list.name, color = RColors.Text, fontWeight = FontWeight.SemiBold)
                            Muted(Fmt.plural(list.items.size, "symbol") + WatchlistRules.newsCount(list).let { if (it > 0) " · $it in News" else "" })
                        }
                        Muted(if (isOpen) "▲" else "▼")
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(if (adding == list.id) "Close search" else "+ Add symbol", false, "watch_add_${list.id}") { if (adding == list.id) { adding = null; vm.clearSearch() } else if (!WatchlistRules.atItemCap(w, list)) adding = list.id }
                            TextButton(onClick = { confirmDelete = list }, enabled = !e.busy, modifier = Modifier.testTag("watch_delete_${list.id}")) { Text("Delete list", color = RColors.Loss) }
                        }
                        if (WatchlistRules.atItemCap(w, list)) Muted("This list is full (${w.maxItemsPerWatchlist} symbols).")
                        if (adding == list.id) SymbolSearch(sr, fetchNewsOnAdd, { fetchNewsOnAdd = it }, vm::onQuery) { hit -> adding = null; vm.addSymbol(list, hit, fetchNewsOnAdd) }
                        if (list.items.isEmpty()) Muted("No symbols yet.", Modifier.padding(top = 6.dp))
                        list.items.forEach { item -> WatchItemCard(item, e.busy, onToggleNews = { vm.toggleNews(list, item) }, onRemove = { vm.removeSymbol(list, item) }) }
                    }
                }
            }
            w.cache.let { Muted("Metrics are cached for ${it.cacheHours}h to respect data-provider limits.") }
        }
    }
    confirmDelete?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = RColors.Surface,
            title = { Text("Delete watchlist?") }, text = { Text("Delete \"${l.name}\" and all its symbols? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteList(l.id) }, modifier = Modifier.testTag("watch_delete_ok")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SymbolSearch(sr: SymbolSearchUi, fetchNews: Boolean, onFetchNews: (Boolean) -> Unit, onQuery: (String) -> Unit, onPick: (SymbolHit) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(sr.query, onQuery, singleLine = true, label = { Text("Search company or ticker") }, placeholder = { Text("e.g. Apple or AAPL") }, modifier = Modifier.fillMaxWidth().testTag("watch_search"))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) { Switch(fetchNews, onFetchNews, modifier = Modifier.testTag("watch_fetch_news_default")); Spacer(Modifier.width(8.dp)); Muted("Fetch news for this symbol") }
        if (sr.searching) Muted("Searching…")
        sr.results.forEach { h ->
            Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 6.dp).testTag("hit_${h.symbol}")) {
                Text(h.name ?: h.symbol, color = RColors.Text, fontSize = 13.sp)
                Muted(listOfNotNull(h.symbol, h.exchange).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun MetricRow(labelA: String, valueA: String, b: Pair<String, String>?, subA: String? = null, subB: String? = null, colorA: Color = RColors.Text, colorB: Color = RColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Muted(labelA); Num(valueA, size = 13, color = colorA); subA?.let { Text(it, color = RColors.Muted, fontSize = 10.sp) } }
        Column(Modifier.weight(1f)) { b?.let { Muted(it.first); Num(it.second, size = 13, color = colorB); subB?.let { s -> Text(s, color = RColors.Muted, fontSize = 10.sp) } } }
    }
}

@Composable
private fun WatchItemCard(it: WatchItem, busy: Boolean, onToggleNews: () -> Unit, onRemove: () -> Unit) {
    val ret = it.changePct
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("watch_item_${it.yahooSymbol}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Num(it.yahooSymbol, bold = true, size = 14)
                    if (WatchlistFormat.isIndexLike(it.quoteType)) { Spacer(Modifier.width(6.dp)); Text("INDEX / FUND", color = RColors.Accent, fontSize = 9.sp) }
                }
                it.companyName?.let { n -> Muted(n) }
                it.industry?.let { n -> Muted(n) }
            }
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.testTag("watch_remove_${it.yahooSymbol}")) { Text("×  Remove", color = RColors.Muted, fontSize = 12.sp) }
        }
        MetricRow("Now", WatchlistFormat.price(it.lastPrice, it.currency), "Return" to WatchlistFormat.percentPlain(ret).let { s -> if (ret != null && ret.signum() > 0) "+$s" else s }, colorB = pnlColor(ret))
        MetricRow("Added", it.addedAt?.let { d -> Fmt.stamp(d).substringBefore(",") } ?: Fmt.DASH, "Price at add" to WatchlistFormat.price(it.priceAtAdd, it.currency), subB = it.exchange?.let { x -> "Exchange $x" })
        MetricRow("P/E (trail)", WatchlistFormat.peText(it), "P/B" to WatchlistFormat.valuationRatio(it.pbRatio),
            subA = WatchlistFormat.industryHint(it.industryPeMedian, it.industryPeerCount), subB = WatchlistFormat.industryHint(it.industryPbMedian, it.industryPeerCount))
        MetricRow("PEG", WatchlistFormat.valuationRatio(it.pegRatio), "P/S" to WatchlistFormat.valuationRatio(it.priceToSales))
        MetricRow("Rev (YoY)", WatchlistFormat.percentPlain(it.revenueGrowthPct), "EPS" to Fmt.number(it.eps))
        MetricRow("ROE", WatchlistFormat.roe(it.roe), "Profit margin" to WatchlistFormat.percentPlain(it.profitMargin))
        MetricRow("D/E · cash", WatchlistFormat.valuationRatio(it.debtToEquity), "Market cap" to WatchlistFormat.compactMoney(it.marketCap),
            subA = WatchlistFormat.netCashLine(it.netCash), colorA = RColors.Text)
        MetricRow("52-week range", WatchlistFormat.range52(it.week52Low, it.week52High), "Dividend yield" to WatchlistFormat.dividendPct(it.dividendYield))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Switch(it.fetchNews, { onToggleNews() }, enabled = !busy, modifier = Modifier.testTag("watch_news_${it.yahooSymbol}")); Spacer(Modifier.width(8.dp)); Muted("Fetch news")
        }
    }
}
