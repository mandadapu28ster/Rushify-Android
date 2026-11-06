package com.rushify.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.AppContainer
import com.rushify.mobile.MainActivity
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Home", Icons.Filled.Home), Pulse("Pulse", Icons.Filled.Star), More("More", Icons.Filled.Menu),
}

@Suppress("UNCHECKED_CAST")
private fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        AuthViewModel::class.java -> AuthViewModel(c.repo)
        DashboardViewModel::class.java -> DashboardViewModel(c.repo)
        RollupsViewModel::class.java -> RollupsViewModel(c.repo)
        WealthViewModel::class.java -> WealthViewModel(c.repo)
        EquityTrendViewModel::class.java -> EquityTrendViewModel(c.repo)
        ConnectionsViewModel::class.java -> ConnectionsViewModel(c.repo)
        ChangePasswordViewModel::class.java -> ChangePasswordViewModel(c.repo)
        RadarViewModel::class.java -> RadarViewModel(c.repo)
        NewsViewModel::class.java -> NewsViewModel(c.repo)
        WatchlistsViewModel::class.java -> WatchlistsViewModel(c.repo)
        SyncViewModel::class.java -> SyncViewModel(c.repo)
        GrowthViewModel::class.java -> GrowthViewModel(c.repo)
        BenchmarksViewModel::class.java -> BenchmarksViewModel(c.repo)
        DividendsViewModel::class.java -> DividendsViewModel(c.repo)
        MonzoViewModel::class.java -> MonzoViewModel(c.repo)
        CorporateViewModel::class.java -> CorporateViewModel(c.repo)
        TickerMapsViewModel::class.java -> TickerMapsViewModel(c.repo)
        AiViewModel::class.java -> AiViewModel(c.repo)
        ManualViewModel::class.java -> ManualViewModel(c.repo)
        else -> error("Unknown ViewModel $modelClass")
    } as T
}

@Composable
fun AppRoot(container: AppContainer, activity: FragmentActivity) {
    val session = container.session
    val st by session.state.collectAsState()
    val f = remember { factory(container) }
    val auth: AuthViewModel = viewModel(factory = f)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hide by remember { mutableStateOf(session.hideBalances) }
    var privacy by remember { mutableStateOf(session.screenPrivacy) }
    var bio by remember { mutableStateOf(session.biometricLock) }
    var notif by remember { mutableStateOf(session.notificationsEnabled) }
    var colorBlind by remember { mutableStateOf(session.colorBlindPalette) }
    var lockTimeout by remember { mutableStateOf(session.lockTimeoutSec) }
    androidx.compose.runtime.SideEffect { RColors.colorBlind = colorBlind }

    LaunchedEffect(privacy) { (activity as? MainActivity)?.applyScreenPrivacy(privacy) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(st.loggedIn) {
        if (st.loggedIn && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // App lock: starts locked, and locks again once the app has been in the background for the chosen time (0 = every time).
    var locked by remember { mutableStateOf(LockRules.startsLocked(session.biometricLock, session.current.loggedIn)) }
    var lockFailed by remember { mutableStateOf(false) }
    val backgroundedAt = remember { LongArray(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, bio, lockTimeout) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> if (backgroundedAt[0] == 0L) backgroundedAt[0] = android.os.SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (LockRules.shouldLock(bio, session.current.loggedIn, locked, backgroundedAt[0], android.os.SystemClock.elapsedRealtime(), lockTimeout)) locked = true
                    backgroundedAt[0] = 0L
                }
                else -> {}
            }
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    val authenticators = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL else BiometricManager.Authenticators.BIOMETRIC_WEAK
    val bioAvailable = BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    fun prompt() {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Rushify").setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }.build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false; lockFailed = false }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { lockFailed = true }
        }).authenticate(info)
    }
    LaunchedEffect(locked) { if (locked && bio) prompt() }

    CompositionLocalProvider(LocalHideBalances provides hide) {
        Box(Modifier.fillMaxSize().background(RColors.Bg)) {
            when {
                st.serverUrl == null -> ConnectScreen(auth, "")
                !st.loggedIn -> { LaunchedEffect(st.serverUrl) { auth.loadHealth() }; LoginScreen(auth, st.serverUrl!!, st.expired) }
                locked && bio -> LockScreen(lockFailed, ::prompt)
                else -> MainScaffold(f, container, st.cleartext, st.cacheScope, st.isAdmin,
                    hide, { hide = it; session.hideBalances = it },
                    privacy, { privacy = it; session.screenPrivacy = it },
                    bio, bioAvailable, { bio = it; session.biometricLock = it },
                    notif, { notif = it; session.notificationsEnabled = it },
                    colorBlind, { colorBlind = it; session.colorBlindPalette = it },
                    lockTimeout, { lockTimeout = it; session.lockTimeoutSec = it },
                    onLogout = { scope.launch { session.logout(); container.repo.wipeCache() } })
            }
        }
    }
}

@Composable
private fun MainScaffold(
    f: ViewModelProvider.Factory, container: AppContainer, cleartext: Boolean, scopeKey: String, isAdmin: Boolean,
    hide: Boolean, onHide: (Boolean) -> Unit, privacy: Boolean, onPrivacy: (Boolean) -> Unit,
    bio: Boolean, bioAvailable: Boolean, onBio: (Boolean) -> Unit, notif: Boolean, onNotif: (Boolean) -> Unit,
    colorBlind: Boolean, onColorBlind: (Boolean) -> Unit, lockTimeout: Int, onLockTimeout: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Keyed by server + user: signing out and in as someone else gets fresh view models, so nothing
    // from the previous account (including the in-memory AI chat) is ever shown.
    val dashboard: DashboardViewModel = viewModel(key = "$scopeKey/dashboard", factory = f)
    val rollups: RollupsViewModel = viewModel(key = "$scopeKey/rollups", factory = f)
    val wealth: WealthViewModel = viewModel(key = "$scopeKey/wealth", factory = f)
    val equity: EquityTrendViewModel = viewModel(key = "$scopeKey/equity", factory = f)
    val connections: ConnectionsViewModel = viewModel(key = "$scopeKey/connections", factory = f)
    val password: ChangePasswordViewModel = viewModel(key = "$scopeKey/password", factory = f)
    val radar: RadarViewModel = viewModel(key = "$scopeKey/radar", factory = f)
    val news: NewsViewModel = viewModel(key = "$scopeKey/news", factory = f)
    val watch: WatchlistsViewModel = viewModel(key = "$scopeKey/watch", factory = f)
    val sync: SyncViewModel = viewModel(key = "$scopeKey/sync", factory = f)
    val growth: GrowthViewModel = viewModel(key = "$scopeKey/growth", factory = f)
    val bench: BenchmarksViewModel = viewModel(key = "$scopeKey/bench", factory = f)
    val dividends: DividendsViewModel = viewModel(key = "$scopeKey/dividends", factory = f)
    val monzo: MonzoViewModel = viewModel(key = "$scopeKey/monzo", factory = f)
    val corporate: CorporateViewModel = viewModel(key = "$scopeKey/corporate", factory = f)
    val maps: TickerMapsViewModel = viewModel(key = "$scopeKey/maps", factory = f)
    val ai: AiViewModel = viewModel(key = "$scopeKey/ai", factory = f)
    val manual: ManualViewModel = viewModel(key = "$scopeKey/manual", factory = f)
    // After a write: everything computed from holdings is stale (the server has already dropped its own caches).
    val onWritten = { dashboard.refresh(); rollups.refresh(); wealth.refresh(); growth.refresh(); equity.refresh() }
    var dest by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (Dest) -> Unit = { dest = it.name }
    val corporateState by corporate.state.collectAsState()
    val dashState by dashboard.state.collectAsState()
    LaunchedEffect(Unit) { corporate.load() }
    // Like the web: when the app opens, refresh Monzo if the server says its last sync is older than 6 hours.
    LaunchedEffect(scopeKey) { if (container.repo.monzoRefreshIfStale()) { dashboard.refresh(); rollups.refresh(); monzo.refresh() } }
    val passwordUi by password.ui.collectAsState()
    LaunchedEffect(passwordUi.done) { if (passwordUi.done) { kotlinx.coroutines.delay(1_200); password.finish() } }
    val attention = pending(corporateState.data?.value ?: emptyList()).size + (dashState.data?.value?.tickerMapAttentionCount ?: 0)
    Scaffold(
        containerColor = RColors.Bg,
        bottomBar = {
            NavigationBar(containerColor = RColors.Surface) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i; dest = null },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                        modifier = Modifier.testTag("tab_${t.name}"),
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = RColors.Accent, selectedTextColor = RColors.Accent, indicatorColor = RColors.Variant, unselectedIconColor = RColors.Muted, unselectedTextColor = RColors.Muted),
                    )
                }
            }
        },
    ) { pad ->
        CompositionLocalProvider(LocalCleartext provides cleartext) {
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f)) {
                val d = dest?.let { n -> Dest.entries.firstOrNull { it.name == n } }
                if (d != null) when (d) {
                    Dest.Performance -> PerformanceScreen(growth, bench, dividends, dashboard, equity) { dest = null }
                    Dest.Holdings -> SubScreen("Holdings", { dest = null }) { HoldingsScreen(dashboard, rollups, manual, onWritten, showTitle = false) }
                    Dest.Sync -> SubScreen("Sync", { dest = null }) { SyncScreen(sync, monzo, onMonzoSynced = { dashboard.refresh(); rollups.refresh() }, showTitle = false) }
                    Dest.Corporate -> CorporateScreen(corporate, maps, onBack = { dest = null }, onMapsChanged = { dashboard.refresh(); rollups.refresh() })
                    Dest.Ai -> AiScreen(ai) { dest = null }
                    Dest.Connections -> ConnectionsScreen(connections) { dest = null }
                    Dest.AddManual -> AddManualScreen(manual, onBack = { dest = null }, onChanged = onWritten)
                    Dest.Password -> ChangePasswordScreen(password) { dest = null }
                } else when (Tab.entries[tab]) {
                    Tab.Home -> HomeScreen(dashboard, onToggleHide = { onHide(!hide) }, onOpen = open, isAdmin = isAdmin, wealth = wealth)
                    Tab.Pulse -> PulseScreen(radar, news, watch)
                    Tab.More -> MoreScreen(container.session, hide, onHide, privacy, onPrivacy, bio, bioAvailable, onBio, notif, onNotif, onLogout, open, attention, colorBlind, onColorBlind, lockTimeout, onLockTimeout)
                }
            }
        }
        }
    }
}
