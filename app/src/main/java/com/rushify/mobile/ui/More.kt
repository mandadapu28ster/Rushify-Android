package com.rushify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushify.mobile.data.SessionManager

@Composable
fun MoreScreen(
    session: SessionManager,
    hideBalances: Boolean, onHideBalances: (Boolean) -> Unit,
    screenPrivacy: Boolean, onScreenPrivacy: (Boolean) -> Unit,
    biometric: Boolean, biometricAvailable: Boolean, onBiometric: (Boolean) -> Unit,
    notifications: Boolean, onNotifications: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onOpen: (Dest) -> Unit = {},
    attention: Int = 0,
) {
    val st by session.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("More", color = RColors.Text, fontSize = 24.sp)
        if (st.isAdmin) RCard(Modifier.testTag("admin_reminder_more")) {
            Text("Admin account", color = RColors.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Muted("Admin tools are web-only. Open the web app → More → Admin. For your portfolio on this phone, sign in with your personal account. An admin token on a phone can do much more than view data.")
        }
        RCard {
            MenuRow("Holdings", "Positions by account, lots, sectors", Dest.Holdings, onOpen)
            MenuRow("Sync", "Trading 212, SnapTrade and Monzo", Dest.Sync, onOpen)
            MenuRow("AI assistant", "Ask about your portfolio", Dest.Ai, onOpen)
            MenuRow("Performance & income", "Growth, benchmarks, dividends", Dest.Performance, onOpen)
            MenuRow("Corporate actions", "Splits, mergers, symbols to confirm", Dest.Corporate, onOpen, badge = attention)
            MenuRow("Connections & keys", "Trading 212, SnapTrade, Monzo and AI status", Dest.Connections, onOpen)
            MenuRow("Add holding", "Stock or ETF lot, property, cash savings, other asset", Dest.AddManual, onOpen)
            MenuRow("Change password", "Sign in again with a new password", Dest.Password, onOpen)
        }
        RCard {
            Muted("Signed in as")
            Text(st.username ?: "", color = RColors.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Muted("Server")
            Num(st.serverUrl ?: "", size = 12)
            if (st.cleartext) { Spacer(Modifier.height(8.dp)); Banner("Connection is not encrypted (http).") }
        }
        RCard {
            SectionTitle("Privacy & security")
            ToggleRow("Hide balances", "Show •••• instead of amounts", hideBalances, onHideBalances, "toggle_hide")
            ToggleRow("Block screenshots", "Hides the app in the recents screen", screenPrivacy, onScreenPrivacy, "toggle_privacy")
            ToggleRow("Biometric lock", if (biometricAvailable) "Ask for fingerprint, face or screen lock on open" else "Set up a screen lock or fingerprint first", biometric, onBiometric, "toggle_bio", enabled = biometricAvailable || biometric)
        }
        RCard {
            SectionTitle("Notifications")
            ToggleRow("Sync notifications", "Tell me when a sync finishes or fails. Checked periodically in the background, so it may be delayed.", notifications, onNotifications, "toggle_notif")
        }
        RCard {
            SectionTitle("Web only")
            Muted("Entering broker or API keys, importing CSVs, editing manual holdings and clearing portfolio data are done in the web app, by design.")
        }
        Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = RColors.Variant, contentColor = RColors.Loss), modifier = Modifier.fillMaxWidth().testTag("logout")) { Text("Sign out") }
        Muted("Rushify mobile 0.1.0 · Educational / testing only", Modifier.fillMaxWidth())
    }
}

enum class Dest { Holdings, Sync, Performance, Corporate, Ai, Connections, AddManual, Password }

@Composable
private fun MenuRow(title: String, subtitle: String, dest: Dest, onOpen: (Dest) -> Unit, badge: Int = 0) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(dest) }.padding(vertical = 10.dp).testTag("menu_${dest.name}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = RColors.Text, fontSize = 15.sp); Muted(subtitle) }
        if (badge > 0) Text("$badge", color = RColors.Bg, fontSize = 12.sp, modifier = Modifier.clip(CircleShape).background(RColors.Warn).padding(horizontal = 8.dp, vertical = 2.dp))
        Text("  ›", color = RColors.Muted, fontSize = 20.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = RColors.Text, fontSize = 14.sp)
            Muted(subtitle)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}
