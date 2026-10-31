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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.MonzoStatus
import com.rushify.mobile.data.RushifyRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MonzoSyncUi(val syncing: Boolean = false, val message: String? = null, val failed: Boolean = false)

/** Exactly the web wording for the three outcomes of a Monzo sync. */
object MonzoMessages {
    const val WAITING = "Still waiting for approval. Open the Monzo app and approve access."
    fun synced(c: com.rushify.mobile.data.MonzoSyncCounts) = "Synced ${c.accounts} account${if (c.accounts == 1) "" else "s"} and ${c.pots} pot${if (c.pots == 1) "" else "s"}."
    const val UPDATED = "Monzo balances updated."
    fun of(o: RushifyRepository.MonzoSyncOutcome): String = when {
        o.result == null -> UPDATED
        o.result.sync != null -> synced(o.result.sync)
        else -> WAITING
    }
}

class MonzoViewModel(private val repo: RushifyRepository) : CachedViewModel<MonzoStatus>() {
    val sync = MutableStateFlow(MonzoSyncUi())
    override suspend fun readCache() = repo.monzoCached()
    override suspend fun fetch() = repo.monzo()

    /** Pulls fresh balances from Monzo through the server; `onDone` lets the caller refresh the dashboard. */
    fun syncNow(onDone: () -> Unit = {}) {
        if (sync.value.syncing) return
        viewModelScope.launch {
            sync.value = MonzoSyncUi(syncing = true)
            try {
                val outcome = repo.monzoSyncNow()
                sync.value = MonzoSyncUi(message = MonzoMessages.of(outcome))
                if (outcome.result?.sync != null || outcome.result == null) onDone()
                refresh()
            } catch (e: ApiError) {
                sync.value = MonzoSyncUi(message = "Monzo sync failed: ${e.message}", failed = true)
                refresh()
            }
        }
    }
}


/** Web labels: Connected, Waiting for approval in the Monzo app, Needs reconnect, Keys saved - not connected, Not set. */
fun statusLabel(status: String?) = when (status) {
    null, "" -> "Keys saved — not connected"
    "connected" -> "Connected"
    "pending_approval" -> "Waiting for approval in the Monzo app"
    "needs_reconnect" -> "Needs reconnect"
    "not_connected" -> "Keys saved — not connected"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun monzoLabel(m: MonzoStatus?) = if (m == null || !m.configured) "Not set" else statusLabel(m.status)
fun monzoCanSync(m: MonzoStatus?) = m != null && m.configured && (m.status == "connected" || m.status == "pending_approval")

/** Monzo's manual sync, shown on Sync (More → Sync) next to Trading 212 and SnapTrade (it does not use the broker sync quota). */
@Composable
fun MonzoSyncCard(vm: MonzoViewModel, onSynced: () -> Unit) {
    val s by vm.state.collectAsState()
    val sync by vm.sync.collectAsState()
    val m = s.data?.value
    RCard(Modifier.testTag("broker_MONZO")) {
        Text("Monzo", color = RColors.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (m == null) { Muted(if (s.error != null) "Could not load Monzo status." else "Loading Monzo…"); return@RCard }
        val ok = m.configured && m.status == "connected"
        Text(monzoLabel(m), color = if (ok) RColors.Gain else RColors.Warn, fontSize = 13.sp, modifier = Modifier.testTag("monzo_status"))
        if (m.status == "connected") Muted("${Fmt.plural(m.balanceRows, "balance")} · last synced ${m.lastSyncedAt?.let { Fmt.stamp(it) } ?: "never"}. Refreshed daily at 21:00 (London).")
        m.lastError?.let { Spacer(Modifier.height(6.dp)); Banner(it, RColors.Loss) }
        if (!m.configured) Muted("Connect Monzo in the web app (Settings → Keys). OAuth sign-in stays on the web by design.")
        else if (m.status == "pending_approval") Muted("Open the Monzo app and approve the access request, then sync.")
        else if (m.status != "connected") Muted("Finish connecting Monzo in the web app (Settings → Keys), then sync here.")
        if (monzoCanSync(m)) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.syncNow(onSynced) }, enabled = !sync.syncing, modifier = Modifier.testTag("monzo_sync")) {
                Text(if (sync.syncing) "Syncing…" else if (m.status == "pending_approval") "Check approval / sync" else "Sync now")
            }
        }
        sync.message?.let { Spacer(Modifier.height(8.dp)); Banner(it, if (sync.failed) RColors.Loss else RColors.Accent, Modifier.testTag("monzo_message")) }
    }
}
