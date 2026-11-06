package com.rushify.mobile.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.Quota
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SnaptradeStatus
import com.rushify.mobile.data.SyncLog
import com.rushify.mobile.data.SyncStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Broker(val label: String) { T212("Trading 212"), SNAPTRADE("SnapTrade") }

/** After a failed or timed-out sync the button is held back: 10 s normally, 10 min when the provider is rate limiting. */
data class Cooldown(val untilMs: Long, val provider: Boolean)

object SyncRules {
    const val SHORT_COOLDOWN_MS = 10_000L
    const val PROVIDER_COOLDOWN_MS = 10 * 60_000L
    private val RATE_LIMIT = Regex("429|rate limit|too many requests|throttl|temporarily unavailable|too many", RegexOption.IGNORE_CASE)

    fun looksRateLimited(msg: String?) = !msg.isNullOrBlank() && RATE_LIMIT.containsMatchIn(msg)
    fun cooldownFor(msg: String?, now: Long): Cooldown { val provider = looksRateLimited(msg); return Cooldown(now + if (provider) PROVIDER_COOLDOWN_MS else SHORT_COOLDOWN_MS, provider) }

    /** Server-closed stale logs get friendly text; an empty error becomes a generic one. */
    fun errorText(err: String?): String {
        val s = (err ?: "").trim()
        if (s.isEmpty()) return "Sync finished with an error."
        return if (Regex("sync did not complete \\(stale", RegexOption.IGNORE_CASE).containsMatchIn(s)) "Sync did not complete (stale; task or server ended without finishing). Try again." else s
    }

    const val T212_PROVIDER = "Trading 212 is temporarily limiting requests. Try again in about 10 minutes."
    const val SNAPTRADE_PROVIDER = "SnapTrade may be rate limiting. Try again in about 10 minutes."
    const val SHORT = "Please wait a few seconds before syncing again."

    /** Why the Sync button is off, in the web's priority order; null when it is allowed. */
    fun disabledReason(b: Broker, s: SyncUi, now: Long): String? {
        val cd = s.cooldowns[b]?.takeIf { now < it.untilMs }
        val q = if (b == Broker.T212) s.quota?.t212 else s.quota?.snaptrade
        if (cd != null) return if (cd.provider) (if (b == Broker.T212) T212_PROVIDER else SNAPTRADE_PROVIDER) else SHORT
        if (b == Broker.T212) {
            if (s.status?.t212NeedsReconnect == true) return "Trading 212 link expired. Update the keys in the web app (Settings → Keys) to enable sync."
            if (s.status?.t212Configured == false) return "Add Trading 212 keys in the web app (Settings → Keys) to enable sync."
        } else {
            val st = s.snaptrade
            if (st?.configured == false) return "SnapTrade is not fully configured. Save your Client ID and Consumer Key in the web app, complete the portal link, then sync."
            if (st?.authFailed == true) return "SnapTrade link expired. Reset the keys in the web app to enable sync."
            if (st?.needsBrokerLink == true) return "Open the connection portal first and link at least one brokerage account (read-only). Then sync."
            if (st?.needsReconnect == true) return "A SnapTrade broker link needs reconnecting. Reconnect it, then sync."
        }
        if (q != null && q.remaining <= 0) return "Manual sync limit reached (3 successful pulls per rolling 24 hours). Try again after older pulls drop out of the window."
        if (q?.rateLimitAllows == false) return "Please wait a moment before syncing again."
        return null
    }
}

data class SyncUi(
    val loading: Boolean = false,
    val error: String? = null,
    val status: SyncStatus? = null,
    val snaptrade: SnaptradeStatus? = null,
    val quota: Quota? = null,
    val history: List<SyncLog> = emptyList(),
    val historyAsOf: Long? = null,
    val running: Map<Broker, Boolean> = emptyMap(),
    val message: String? = null,
    val cooldowns: Map<Broker, Cooldown> = emptyMap(),
    val portalBusy: Boolean = false,
    val portalUrl: String? = null,     // set once, then opened by the screen and cleared
    val portalError: String? = null,
)

class SyncViewModel(
    private val repo: RushifyRepository,
    private val pollDelayMs: Long = 3000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val ui = MutableStateFlow(SyncUi())
    private val pollers = HashMap<Broker, Job>()

    fun load() {
        viewModelScope.launch {
            repo.syncHistoryCached()?.let { c -> ui.update { it.copy(history = c.value.sortedByDescending { l -> l.id }, historyAsOf = c.fetchedAt) } }
            refresh()
            resumeRunning()
        }
    }

    /** A sync started earlier (even before the app was closed) keeps running on the server: pick it up again. */
    private suspend fun resumeRunning() {
        runCatching { repo.call { repo.api.t212Running() } }.getOrNull()?.takeIf { it.inProgress }?.let { r ->
            if (ui.value.running[Broker.T212] != true) { ui.update { it.copy(running = it.running + (Broker.T212 to true)) }; r.activeSyncLogId?.let { id -> poll(Broker.T212, id) } ?: pollRunning(Broker.T212) }
        }
        runCatching { repo.call { repo.api.snaptradeRunning() } }.getOrNull()?.takeIf { it.inProgress }?.let { r ->
            if (ui.value.running[Broker.SNAPTRADE] != true) { ui.update { it.copy(running = it.running + (Broker.SNAPTRADE to true)) }; r.activeSyncLogId?.let { id -> poll(Broker.SNAPTRADE, id) } ?: pollRunning(Broker.SNAPTRADE) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            ui.update { it.copy(loading = true) }
            try {
                val status = repo.call { repo.api.syncStatus() }
                val snap = repo.call { repo.api.snaptradeStatus() }
                val quota = repo.call { repo.api.quota() }
                val hist = repo.syncHistory()
                ui.update { it.copy(loading = false, error = null, status = status, snaptrade = snap, quota = quota, history = hist.value.sortedByDescending { l -> l.id }, historyAsOf = hist.fetchedAt) }
            } catch (e: ApiError) {
                ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun sync(broker: Broker) {
        if (ui.value.running[broker] == true) return
        if (SyncRules.disabledReason(broker, ui.value, clock()) != null) return
        viewModelScope.launch {
            ui.update { it.copy(message = null, running = it.running + (broker to true)) }
            try {
                val accepted = repo.call { if (broker == Broker.T212) repo.api.syncT212() else repo.api.syncSnaptrade() }
                accepted.syncLogId?.let { poll(broker, it) } ?: run { ui.update { it.copy(running = it.running + (broker to false)) }; refresh() }
            } catch (e: ApiError.QuotaReached) {
                ui.update { it.copy(running = it.running + (broker to false), message = "Daily manual sync limit reached. ${e.message}") }
                refresh()
            } catch (e: ApiError.SyncRunning) {
                // 409: another sync is already running; show the running state, no new request.
                ui.update { it.copy(message = "A sync is already running for ${broker.label}.") }
                refresh()
                pollRunning(broker)
            } catch (e: ApiError) {
                ui.update { it.copy(running = it.running + (broker to false), message = SyncRules.errorText(e.message), cooldowns = it.cooldowns + (broker to SyncRules.cooldownFor(e.message, clock()))) }
            }
        }
    }

    private fun pollRunning(broker: Broker) {
        pollers[broker]?.cancel()
        pollers[broker] = viewModelScope.launch {
            repeat(60) {
                delay(pollDelayMs)
                val latest = runCatching { repo.syncHistory().value.maxByOrNull { l -> l.id } }.getOrNull()
                if (latest?.finishedAt != null) { finish(broker, latest); return@launch }
            }
            ui.update { it.copy(running = it.running + (broker to false)) }
        }
    }

    private fun poll(broker: Broker, id: Int) {
        pollers[broker]?.cancel()
        pollers[broker] = viewModelScope.launch {
            var wait = pollDelayMs
            repeat(80) {
                delay(wait)
                try {
                    val log = repo.call { repo.api.syncLog(id) }
                    if (log.finishedAt != null) { finish(broker, log); return@launch }
                } catch (e: ApiError.SessionExpired) { return@launch } catch (_: ApiError) { /* keep polling with backoff */ }
                wait = (wait * 3 / 2).coerceAtMost(15_000)
            }
            ui.update { it.copy(running = it.running + (broker to false), message = "Sync is taking longer than expected. Check again in a minute.", cooldowns = it.cooldowns + (broker to SyncRules.cooldownFor(null, clock()))) }
        }
    }

    private suspend fun finish(broker: Broker, log: SyncLog) {
        ui.update {
            it.copy(
                running = it.running + (broker to false),
                message = if (log.success) "${broker.label} sync finished." else "${broker.label} sync failed: ${SyncRules.errorText(log.errorMessage)}",
                cooldowns = if (log.success) it.cooldowns else it.cooldowns + (broker to SyncRules.cooldownFor(log.errorMessage, clock())),
            )
        }
        refresh()
    }

    /** Opens SnapTrade's connection portal (new brokerage, or reconnect a disabled link). Only an http(s) address is ever handed back. */
    fun openPortal(reconnectId: String?) {
        if (ui.value.portalBusy) return
        viewModelScope.launch {
            ui.update { it.copy(portalBusy = true, portalError = null) }
            try {
                val r = repo.call { repo.api.snaptradePortalUrl(com.rushify.mobile.data.PortalRequest(reconnectId)) }
                val url = SafeUrl.httpOrNull(r.redirectUri)
                ui.update { it.copy(portalBusy = false, portalUrl = url, portalError = if (url == null) "Could not open SnapTrade portal" else null) }
            } catch (e: ApiError) {
                ui.update { it.copy(portalBusy = false, portalError = e.message ?: "Could not open SnapTrade portal") }
            }
        }
    }

    fun portalOpened() = ui.update { it.copy(portalUrl = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: SyncViewModel, monzo: MonzoViewModel? = null, onMonzoSynced: () -> Unit = {}, showTitle: Boolean = true) {
    val s by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.load(); monzo?.load() }
    var confirm by remember { mutableStateOf<Broker?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(s.portalUrl) { s.portalUrl?.let { u -> vm.portalOpened(); runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } } }
    PullToRefreshBox(isRefreshing = s.loading && s.quota != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showTitle) Text("Sync", color = RColors.Text, fontSize = 24.sp)
            AsOfBar(s.historyAsOf, s.error, s.history.isNotEmpty() || s.quota != null, s.loading, label = s.historyAsOf?.let { "Updated ${Fmt.stampMillis(it)}" })
            if (s.quota == null && s.error != null) ErrorState(s.error!!, vm::refresh)
            s.message?.let { Banner(it, if (it.contains("fail", true) || it.contains("limit", true)) RColors.Loss else RColors.Accent, Modifier.testTag("sync_message")) }
            val now = rememberNow(s.cooldowns.isNotEmpty())
            Broker.entries.forEach { b ->
                val running = s.running[b] == true
                val q = if (b == Broker.T212) s.quota?.t212 else s.quota?.snaptrade
                val reason = SyncRules.disabledReason(b, s, now)
                RCard(Modifier.testTag("broker_${b.name}")) {
                    Text(b.label, color = RColors.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    BrokerStatus(b, s, vm, ctxOpen = { u -> vm.portalOpened(); runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } })
                    if (q != null) Muted("${q.remaining} of ${s.quota?.maxPer24h ?: 3} manual syncs left today (rolling 24 h)")
                    Spacer(Modifier.height(8.dp))
                    if (running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sync_running_${b.name}")) // indeterminate: the API reports no percentage
                        Muted("${b.label} sync in progress…", Modifier.padding(top = 4.dp))
                    } else {
                        Button(onClick = { confirm = b }, enabled = reason == null, modifier = Modifier.testTag("sync_${b.name}")) { Text("Sync now") }
                        reason?.let { Muted(it, Modifier.padding(top = 4.dp).testTag("sync_reason_${b.name}")) }
                    }
                }
            }
            if (monzo != null) MonzoSyncCard(monzo, onMonzoSynced)
            Muted("Manual syncs are limited to 3 per rolling 24 hours for Trading 212 and for SnapTrade. Monzo has no daily limit.")
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sync ${b.label}?") },
            text = { Text("This uses one of your daily manual syncs for ${b.label}.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.sync(b) }, modifier = Modifier.testTag("confirm_sync")) { Text("Sync") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = RColors.Surface,
        )
    }
}

object SyncKindLabel {
    /** Server kinds: manual, scheduled (Trading 212) and snaptrade_manual, snaptrade_scheduled. */
    fun of(kind: String): String {
        val broker = if (kind.startsWith("snaptrade", true)) "SnapTrade" else "Trading 212"
        val how = when {
            kind.endsWith("manual", true) -> " (manual)"
            kind.endsWith("scheduled", true) -> " (scheduled)"
            else -> ""
        }
        return broker + how
    }
}


/** Ticks once a second while a cooldown is running so the button re-enables by itself. */
@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { while (active) { now = System.currentTimeMillis(); delay(1000) } ; now = System.currentTimeMillis() }
    return if (active) now else System.currentTimeMillis()
}

/** Connection state of one broker, with the same cases as the web (expired link, reconnect, connect a brokerage). */
@Composable
private fun BrokerStatus(b: Broker, s: SyncUi, vm: SyncViewModel, ctxOpen: (String) -> Unit) {
    if (b == Broker.T212) {
        val st = s.status
        val alerts = ConnectionsText.t212Alerts(st)
        when {
            st == null -> Muted("Checking connection…")
            st.t212NeedsReconnect || alerts.isNotEmpty() -> {
                Banner("Broker link expired. Trading 212 rejected the credentials. Replace the API keys in the web app (Settings → Keys).", RColors.Warn, Modifier.testTag("t212_expired"))
                alerts.forEach { (label, err) -> Muted("$label: $err") }
            }
            !st.t212Configured -> Muted("Not connected. Add Trading 212 keys in the web app (Settings → Keys).")
            else -> Muted("Connected")
        }
        return
    }
    val st = s.snaptrade
    when {
        st == null -> Muted("Checking connection…")
        !st.configured -> Muted("Not connected. Add your SnapTrade keys in the web app (Settings → Keys).")
        st.authFailed -> Banner("SnapTrade session expired. The connection failed (401) and your SnapTrade user secret may be invalid. Reset the SnapTrade keys in the web app.", RColors.Loss, Modifier.testTag("snaptrade_auth_failed"))
        st.needsReconnect -> Column(Modifier.testTag("snaptrade_reconnect")) {
            val first = st.disabled.firstOrNull()
            Banner("Broker link disabled or expired${first?.brokerName?.let { " ($it)" } ?: ""}. Reconnect it in SnapTrade, then sync.", RColors.Warn)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.openPortal(first?.id) }, enabled = !s.portalBusy, modifier = Modifier.testTag("snaptrade_reconnect_btn")) { Text(if (s.portalBusy) "Opening…" else "Reconnect in SnapTrade") }
        }
        st.needsBrokerLink -> Column(Modifier.testTag("snaptrade_link")) {
            Banner("Connect a brokerage. Link at least one read-only brokerage account in SnapTrade's portal, then sync.", RColors.Accent)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.openPortal(null) }, enabled = !s.portalBusy, modifier = Modifier.testTag("snaptrade_link_btn")) { Text(if (s.portalBusy) "Opening portal…" else "Open connection portal") }
        }
        else -> Muted("${st.activeConnections} active broker connection${if (st.activeConnections == 1) "" else "s"} · daily sync")
    }
    s.portalError?.let { Banner(it, RColors.Loss, Modifier.testTag("portal_error")) }
}
