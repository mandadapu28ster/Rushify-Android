package com.rushify.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    colorBlind: Boolean = false, onColorBlind: (Boolean) -> Unit = {},
    lockTimeoutSec: Int = 60, onLockTimeout: (Int) -> Unit = {},
) {
    val st by session.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabHeader("More")
        if (st.isAdmin) RCard(Modifier.testTag("admin_reminder_more")) {
            Text("Admin account", color = RColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Muted("Admin tools are web-only. Open the web app → More → Admin. For your portfolio on this phone, sign in with your personal account. An admin token on a phone can do much more than view data.")
        }
        RCard {
            MenuRow("Holdings", "Positions by account, lots, sectors", Dest.Holdings, onOpen, RTint.Holdings, Icons.Filled.List)
            MenuRow("Sync", "Trading 212, SnapTrade and Monzo", Dest.Sync, onOpen, RTint.Sync, Icons.Filled.Refresh)
            MenuRow("AI assistant", "Ask about your portfolio", Dest.Ai, onOpen, RTint.Ai, RIcons.Sparkles)
            MenuRow("Performance & income", "Growth, benchmarks, dividends", Dest.Performance, onOpen, RTint.Performance, RIcons.TrendingUp)
            MenuRow("Corporate actions", "Splits, mergers, symbols to confirm", Dest.Corporate, onOpen, RTint.Corporate, RIcons.Bank, badge = attention)
            MenuRow("Connections & keys", "Trading 212, SnapTrade, Monzo and AI status", Dest.Connections, onOpen, RTint.Connections, RIcons.Link)
            MenuRow("Add holding", "Stock or ETF lot, property, cash savings, other asset", Dest.AddManual, onOpen, RTint.AddHolding, Icons.Filled.AddCircle)
            MenuRow("Change password", "Sign in again with a new password", Dest.Password, onOpen, RTint.Password, RIcons.Key)
        }
        RCard {
            Muted("Signed in as")
            Text(st.username ?: "", color = RColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Muted("Server")
            Num(st.serverUrl ?: "", size = 13)
            if (st.cleartext) { Spacer(Modifier.height(8.dp)); Banner("Connection is not encrypted (http).") }
        }
        RCard {
            SectionTitle("Privacy & security")
            ToggleRow("Hide balances", "Show •••• instead of amounts", hideBalances, onHideBalances, "toggle_hide")
            HairlineDivider()
            ToggleRow("Colour-blind friendly", "Blue and orange instead of green and red", colorBlind, onColorBlind, "toggle_colorblind")
            HairlineDivider()
            ToggleRow("Biometric lock", if (biometricAvailable) "Ask for fingerprint, face or screen lock each time you open Rushify" else "Set up a screen lock or fingerprint first", biometric, onBiometric, "toggle_bio", enabled = biometricAvailable || biometric)
            if (biometric) { HairlineDivider(); LockTimeoutRow(lockTimeoutSec, onLockTimeout) }
            HairlineDivider()
            ToggleRow("Hide in app switcher", "Cover the screen when you leave the app", screenPrivacy, onScreenPrivacy, "toggle_privacy")
        }
        RCard {
            SectionTitle("Notifications")
            ToggleRow("Sync notifications", "Tell me when a sync finishes or fails. Checked periodically in the background, so it may be delayed.", notifications, onNotifications, "toggle_notif")
        }
        RCard {
            SectionTitle("Web only")
            Muted("Entering broker or API keys, importing CSVs and clearing portfolio data are done in the web app, by design.")
        }
        Button(
            onClick = onLogout, modifier = Modifier.fillMaxWidth().testTag("logout"),
            colors = ButtonDefaults.buttonColors(containerColor = RColors.Surface, contentColor = RColors.Loss),
        ) { Text("Sign out") }
        val ctx = LocalContext.current
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("") }
        Muted("Rushify for Android $version", Modifier.fillMaxWidth().testTag("version_footer"), align = TextAlign.Center)
    }
}

enum class Dest { Holdings, Sync, Performance, Corporate, Ai, Connections, AddManual, Password }

@Composable
private fun HairlineDivider() { Box(Modifier.fillMaxWidth().height(1.dp).background(RColors.Hairline)) }

@Composable
private fun MenuRow(title: String, subtitle: String, dest: Dest, onOpen: (Dest) -> Unit, tint: Color, icon: ImageVector, badge: Int = 0) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(dest) }.padding(vertical = 8.dp).testTag("menu_${dest.name}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, color = RColors.Text, fontSize = 16.sp); Muted(subtitle) }
        if (badge > 0) Text("$badge", color = RColors.Bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(RColors.Warn).padding(horizontal = 8.dp, vertical = 2.dp))
        Text("  ›", color = RColors.Muted, fontSize = 20.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    val tick = rememberTick()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = RColors.Text, fontSize = 16.sp)
            Muted(subtitle)
        }
        Switch(
            checked, { tick(); onChange(it) }, enabled = enabled, modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(checkedTrackColor = RColors.Accent, checkedThumbColor = RColors.Bg, uncheckedTrackColor = RColors.Variant, uncheckedThumbColor = RColors.Muted, uncheckedBorderColor = RColors.Hairline),
        )
    }
}


/** How long the app may sit in the background before it asks again. */
@Composable
private fun LockTimeoutRow(seconds: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp).testTag("lock_timeout"), verticalAlignment = Alignment.CenterVertically) {
        Text("Lock", color = RColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(LockRules.label(seconds), color = RColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        DropdownMenu(open, { open = false }, containerColor = RColors.Elevated) {
            LockRules.timeouts.forEach { (sec, label) -> DropdownMenuItem(text = { Text(label, color = RColors.Text) }, onClick = { open = false; onChange(sec) }, modifier = Modifier.testTag("lock_$sec")) }
        }
    }
}
