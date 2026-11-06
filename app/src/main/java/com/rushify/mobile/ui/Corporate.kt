package com.rushify.mobile.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.CorporateAction
import com.rushify.mobile.data.CorporateActionResolve
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.TickerMapUpsert
import com.rushify.mobile.data.TickerMaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ResolveUi(val busyId: Int? = null, val message: String? = null, val failed: Boolean = false)

class CorporateViewModel(private val repo: RushifyRepository) : CachedViewModel<List<CorporateAction>>() {
    val resolve = MutableStateFlow(ResolveUi())
    override suspend fun readCache() = repo.corporateCached()
    override suspend fun fetch() = repo.corporate()

    /** status is "confirmed" or "dismissed"; changes server data, so the UI asks first. */
    fun resolve(id: Int, status: String) {
        if (resolve.value.busyId != null) return
        viewModelScope.launch {
            resolve.value = ResolveUi(busyId = id)
            try {
                repo.call { repo.api.resolveCorporateAction(id, CorporateActionResolve(status)) }
                resolve.value = ResolveUi(message = if (status == "confirmed") "Action confirmed." else "Action dismissed.")
                refresh()
            } catch (e: ApiError) {
                resolve.value = ResolveUi(message = "Could not update: ${e.message}", failed = true)
            }
        }
    }
}

/** Exactly the web wording. */
const val MAP_SAVED_INFO = "Latest portfolio tracking will include this shortly. Until then, treat your portfolio update as in progress. Open Home after a moment for refreshed numbers."
const val MAP_EMPTY_ERROR = "Add the listing symbol you want us to use for quotes and history, then save."

data class MapEditUi(val savingKey: String? = null, val message: String? = null, val error: String? = null)

class TickerMapsViewModel(private val repo: RushifyRepository) : CachedViewModel<TickerMaps>() {
    val edit = MutableStateFlow(MapEditUi())
    override suspend fun readCache() = repo.tickerMapsCached()
    override suspend fun fetch() = repo.tickerMaps()

    /** Saves this user's override for one broker ticker. `onChanged` lets the caller refresh Home and Holdings. */
    fun save(sourceKey: String, symbol: String, onChanged: () -> Unit = {}) {
        val yahoo = symbol.trim()
        if (yahoo.isEmpty()) { edit.value = MapEditUi(error = MAP_EMPTY_ERROR); return }
        change(sourceKey, onChanged) { repo.call { repo.api.putTickerMap(TickerMapUpsert(sourceKey, yahoo)) } }
    }

    /** Removes the user's override (global maps still apply). */
    fun remove(sourceKey: String, onChanged: () -> Unit = {}) = change(sourceKey, onChanged) { repo.call { repo.api.deleteTickerMap(sourceKey) } }

    private fun change(key: String, onChanged: () -> Unit, call: suspend () -> Unit) {
        if (edit.value.savingKey != null) return
        viewModelScope.launch {
            edit.value = MapEditUi(savingKey = key)
            try {
                call()
                repo.tickerMaps().let { c -> state.update { Loadable(data = c) } }
                onChanged()
                edit.value = MapEditUi(message = MAP_SAVED_INFO)
            } catch (e: ApiError) {
                edit.value = MapEditUi(error = e.message)
            }
        }
    }

    fun clearMessage() { edit.value = edit.value.copy(message = null) }
}

fun pending(list: List<CorporateAction>) = list.filter { it.status.equals("pending", true) }

/** Readable key/value lines for the free-form `details` object. */
fun detailLines(details: JsonObject): List<Pair<String, String>> = details.entries.map { (k, v) ->
    k.replace('_', ' ').replaceFirstChar { it.uppercase() } to when (v) {
        is JsonNull -> Fmt.DASH
        is JsonPrimitive -> v.content
        is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> v.toString()
    }
}

fun actionTypeLabel(t: String) = t.replace('_', ' ').replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorporateScreen(vm: CorporateViewModel, maps: TickerMapsViewModel, onBack: () -> Unit, onMapsChanged: () -> Unit = {}) {
    val s by vm.state.collectAsState()
    val r by vm.resolve.collectAsState()
    val tm by maps.state.collectAsState()
    val edit by maps.edit.collectAsState()
    val drafts = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var removeKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(edit.message) { if (edit.message != null) { kotlinx.coroutines.delay(10_000); maps.clearMessage() } }
    LaunchedEffect(Unit) { vm.load(); maps.load() }
    var confirm by remember { mutableStateOf<Pair<CorporateAction, String>?>(null) }
    val list = s.data?.value
    SubScreen("Corporate actions", onBack) {
        PullToRefreshBox(isRefreshing = s.loading && list != null, onRefresh = { vm.refresh(); maps.refresh() }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsOfBar(s.data?.fetchedAt, s.error, list != null, s.loading)
                r.message?.let { Banner(it, if (r.failed) RColors.Loss else RColors.Accent, Modifier.testTag("resolve_message")) }
                if (list == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading…"); return@Column }
                val open = pending(list); val done = list - open.toSet()
                SectionTitle("To confirm")
                if (open.isEmpty()) RCard { Text("Nothing to review", color = RColors.Text); Muted("Splits, mergers and similar events will appear here when detected.", Modifier.testTag("corporate_empty")) }
                open.forEach { a ->
                    RCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Num(a.ticker, bold = true); Muted(actionTypeLabel(a.actionType))
                        }
                        Spacer(Modifier.height(4.dp))
                        detailLines(a.details).forEach { (k, v) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted(k); Num(v, size = 12) } }
                        Muted("Detected ${Fmt.stamp(a.createdAt)}")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { confirm = a to "confirmed" }, enabled = r.busyId == null, modifier = Modifier.testTag("confirm_${a.id}")) { Text("Confirm") }
                            Button(onClick = { confirm = a to "dismissed" }, enabled = r.busyId == null, colors = ButtonDefaults.buttonColors(containerColor = RColors.Variant, contentColor = RColors.Text), modifier = Modifier.testTag("dismiss_${a.id}")) { Text("Dismiss") }
                        }
                    }
                }
                if (done.isNotEmpty()) {
                    SectionTitle("Resolved")
                    done.forEach { a -> RCard { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Num(a.ticker); Muted("${actionTypeLabel(a.actionType)} · ${a.status}") } } }
                }
                val items = tm.data?.value?.attentionItems ?: emptyList()
                SectionTitle("Symbols to confirm")
                Muted("Enter the listing your broker uses (for example VOD.L). The choice is stored on this account only.")
                edit.message?.let { Banner(it, RColors.Accent, Modifier.testTag("map_message")) }
                edit.error?.let { Banner(it, RColors.Loss, Modifier.testTag("map_error")) }
                if (items.isEmpty()) Muted("Nothing needs your review right now.", Modifier.testTag("symbols_empty"))
                items.forEach { it ->
                    val busy = edit.savingKey == it.sourceKey
                    RCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Num(it.brokerTicker ?: it.sourceKey, bold = true, size = 13)
                            if (it.hasUserMap) Muted("YOUR MAP")
                        }
                        Muted("Match in use: ${it.yahooSymbol ?: Fmt.DASH}")
                        Spacer(Modifier.height(6.dp))
                        Field("Listing symbol", drafts[it.sourceKey] ?: it.yahooSymbol.orEmpty(), { v -> drafts[it.sourceKey] = v }, "map_input_${it.sourceKey}", enabled = !busy)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Button(onClick = { maps.save(it.sourceKey, drafts[it.sourceKey] ?: it.yahooSymbol.orEmpty(), onMapsChanged) }, enabled = edit.savingKey == null, modifier = Modifier.testTag("map_save_${it.sourceKey}")) { Text(if (busy) "Saving…" else "Save") }
                            if (it.hasUserMap) TextButton(onClick = { removeKey = it.sourceKey }, enabled = edit.savingKey == null, modifier = Modifier.testTag("map_remove_${it.sourceKey}")) { Text("Remove my map") }
                        }
                    }
                }
            }
        }
    }
    removeKey?.let { k ->
        AlertDialog(
            onDismissRequest = { removeKey = null },
            title = { Text("Remove your map?") },
            text = { Text("The listing you chose for this broker ticker is removed. Global mappings (if any) still apply.") },
            confirmButton = { TextButton(onClick = { removeKey = null; maps.remove(k, onMapsChanged) }, modifier = Modifier.testTag("map_remove_ok")) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeKey = null }) { Text("Cancel") } },
            containerColor = RColors.Surface,
        )
    }
    confirm?.let { (a, status) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (status == "confirmed") "Confirm ${actionTypeLabel(a.actionType)}?" else "Dismiss ${actionTypeLabel(a.actionType)}?") },
            text = { Text("${a.ticker}: " + if (status == "confirmed") "this updates your portfolio data on the server." else "this will be ignored and removed from the to-confirm list.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.resolve(a.id, status) }, modifier = Modifier.testTag("confirm_dialog_ok")) { Text(if (status == "confirmed") "Confirm" else "Dismiss") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = RColors.Surface,
        )
    }
}
