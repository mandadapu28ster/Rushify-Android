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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.CredentialFlags
import com.rushify.mobile.data.MonzoStatus
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SnaptradeConnection
import com.rushify.mobile.data.SnaptradeStatus
import com.rushify.mobile.data.SyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionsData(
    val flags: CredentialFlags, val sync: SyncStatus?, val snaptrade: SnaptradeStatus?, val monzo: MonzoStatus?, val connections: List<SnaptradeConnection>,
)
data class ConnectionsUi(val loading: Boolean = false, val error: String? = null, val data: ConnectionsData? = null)

/** Read-only view of what is connected. Keys themselves are entered only in the web app and never shown here. */
class ConnectionsViewModel(private val repo: RushifyRepository) : ViewModel() {
    val ui = MutableStateFlow(ConnectionsUi())

    fun load() { if (ui.value.data == null && !ui.value.loading) refresh() }

    fun refresh() {
        viewModelScope.launch {
            ui.update { it.copy(loading = true, error = null) }
            try {
                val d = coroutineScope {
                    val flags = async { repo.credentialFlags() }
                    // Everything else is optional: one failing call must not hide the rest.
                    val sync = async { runCatching { repo.call { repo.api.syncStatus() } }.getOrNull() }
                    val snap = async { runCatching { repo.call { repo.api.snaptradeStatus() } }.getOrNull() }
                    val monzo = async { runCatching { repo.call { repo.api.monzoStatus() } }.getOrNull() }
                    val conns = async { runCatching { repo.snaptradeConnections() }.getOrDefault(emptyList()) }
                    ConnectionsData(flags.await(), sync.await(), snap.await(), monzo.await(), conns.await())
                }
                ui.value = ConnectionsUi(data = d)
            } catch (e: ApiError) {
                ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

object ConnectionsText {
    fun yesNo(b: Boolean) = if (b) "Configured" else "Not set"
    fun aiProviderLabel(f: CredentialFlags) = if (f.aiProvider.equals("openai", true)) "OpenAI" else "Gemini"
    fun aiAgentLabel(mode: String?) = when (mode) { "assistants" -> "Assistants API"; "chat_tools" -> "Chat agent (tools)"; null, "" -> null; else -> mode }
    /** One line per T212 account that needs new keys. */
    fun t212Alerts(sync: SyncStatus?): List<Pair<String, String>> = listOf("invest" to "Invest", "isa" to "Stocks & Shares ISA", "sipp" to "SIPP").mapNotNull { (k, label) ->
        sync?.accounts?.get(k)?.takeIf { it.needsReconnect }?.let { label to (it.lastError ?: "Trading 212 rejected or expired these API keys.") }
    }
    fun snaptradeState(s: SnaptradeStatus?): String? = when {
        s == null || !s.configured -> null
        s.authFailed -> "Authentication failed (401). Your SnapTrade user secret may be invalid."
        s.needsReconnect -> "A broker link was disabled or expired and needs reconnecting."
        s.needsBrokerLink -> "No broker linked yet."
        else -> "${s.activeConnections} active connection${if (s.activeConnections == 1) "" else "s"}."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(vm: ConnectionsViewModel, onBack: () -> Unit) {
    val s by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    SubScreen("Connections & keys", onBack) {
        PullToRefreshBox(isRefreshing = s.loading && s.data != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Muted("What is connected to your account. Keys are entered and changed only in the web app (Settings → Keys) and are never shown here.")
                val d = s.data
                if (d == null) { if (s.error != null) ErrorState(s.error!!, vm::refresh) else Muted("Loading…"); return@Column }
                val f = d.flags
                RCard(Modifier.testTag("conn_t212")) {
                    SectionTitle("Trading 212")
                    Row3("Invest", ConnectionsText.yesNo(f.t212Invest), f.t212Invest); Row3("Stocks & Shares ISA", ConnectionsText.yesNo(f.t212Isa), f.t212Isa); Row3("SIPP", ConnectionsText.yesNo(f.t212Sipp), f.t212Sipp)
                    ConnectionsText.t212Alerts(d.sync).forEach { (label, err) -> Spacer(Modifier.height(6.dp)); Banner("$label: API keys rejected or expired. Replace the key and secret in the web app. $err", RColors.Loss, Modifier.testTag("t212_alert_$label")) }
                }
                RCard(Modifier.testTag("conn_snaptrade")) {
                    SectionTitle("SnapTrade")
                    Row3("Status", if (f.snaptrade) "Ready" else "Not set", f.snaptrade)
                    ConnectionsText.snaptradeState(d.snaptrade)?.let { Muted(it, Modifier.padding(top = 4.dp)) }
                    if (d.connections.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp)); Muted("Linked brokerages")
                        d.connections.forEach { c -> Row3(c.brokerName ?: "Broker", if (c.disabled) "Reconnect" else "Active", !c.disabled) }
                        Muted("Add or reconnect brokers from More → Sync (opens SnapTrade's portal) or in the web app.", Modifier.padding(top = 4.dp))
                    }
                }
                RCard(Modifier.testTag("conn_monzo")) {
                    SectionTitle("Monzo")
                    Row3("Status", monzoLabel(d.monzo), d.monzo?.status == "connected")
                    d.monzo?.takeIf { it.status == "connected" }?.let { Muted("${Fmt.plural(it.balanceRows, "balance")} · last synced ${it.lastSyncedAt?.let { t -> Fmt.stamp(t) } ?: "never"}") }
                }
                RCard(Modifier.testTag("conn_ai")) {
                    SectionTitle("AI assistant")
                    Row3("Provider", ConnectionsText.aiProviderLabel(f), true)
                    Row3("Gemini key", ConnectionsText.yesNo(f.gemini), f.gemini); Row3("OpenAI key", ConnectionsText.yesNo(f.openai), f.openai)
                    if (f.aiProvider.equals("openai", true)) ConnectionsText.aiAgentLabel(f.openaiAgentMode)?.let { Row3("OpenAI agent mode", it, true) }
                }
            }
        }
    }
}

@Composable
private fun Row3(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = RColors.Text, fontSize = 14.sp)
        Text(value, color = if (good) RColors.Gain else RColors.Warn, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
