package com.rushify.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.Health
import com.rushify.mobile.data.LoginRequest
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUi(
    val busy: Boolean = false,
    val error: String? = null,
    val health: Health? = null,
    val versionWarning: String? = null,
    val confirmCleartext: String? = null, // server url awaiting the one-time http:// confirmation
    val locked: Boolean = false,
)

class AuthViewModel(private val repo: RushifyRepository) : ViewModel() {
    val ui = MutableStateFlow(AuthUi())
    private val session get() = repo.session

    fun connect(input: String, cleartextConfirmed: Boolean = false) {
        when (val r = ServerUrl.parse(input)) {
            is ServerUrl.Result.Bad -> ui.update { it.copy(error = r.reason) }
            is ServerUrl.Result.Ok -> {
                val url = r.url.toString().trimEnd('/')
                if (r.isCleartext && !cleartextConfirmed && !session.cleartextAcknowledged(url)) {
                    ui.update { it.copy(confirmCleartext = url, error = null) }
                    return
                }
                if (r.isCleartext) session.acknowledgeCleartext(url)
                viewModelScope.launch {
                    ui.update { it.copy(busy = true, error = null, confirmCleartext = null) }
                    if (session.setServer(url)) repo.wipeCache()
                    try {
                        val h = repo.call { repo.api.health() }
                        ui.update { it.copy(busy = false, health = h, versionWarning = versionWarning(h)) }
                    } catch (e: ApiError) {
                        session.clearServer()
                        ui.update { it.copy(busy = false, error = "Could not reach the server: ${e.message}") }
                    }
                }
            }
        }
    }

    fun dismissCleartext() = ui.update { it.copy(confirmCleartext = null) }

    fun login(username: String, password: String) {
        if (ui.value.busy || ui.value.locked) return
        viewModelScope.launch {
            ui.update { it.copy(busy = true, error = null) }
            try {
                // Username is sent exactly as typed (trim only): the server matches case-insensitively.
                val token = repo.call { repo.api.login(LoginRequest(username.trim(), password)) }.accessToken
                val me = repo.call { repo.api.meWith("Bearer $token") }
                repo.wipeCache()
                session.saveLogin(token, me)
                ui.update { AuthUi(health = it.health) }
            } catch (e: ApiError.Locked) {
                ui.update { it.copy(busy = false, locked = true, error = e.message) }
            } catch (e: ApiError) {
                // No automatic retry: repeated bad passwords lock the account for 24h.
                ui.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun loadHealth() {
        if (ui.value.health != null || session.current.serverUrl == null) return
        viewModelScope.launch {
            runCatching { repo.call { repo.api.health() } }.onSuccess { h -> ui.update { it.copy(health = h, versionWarning = versionWarning(h)) } }
        }
    }

    fun changeServer() { session.clearServer(); ui.value = AuthUi() }

    companion object {
        fun versionWarning(h: Health): String? {
            val major = h.version.substringBefore('.').toIntOrNull()
            return if (major == null || major > 0) "This server reports version ${h.version.ifEmpty { "unknown" }}, which this app has not been tested with." else null
        }
    }
}

@Composable
fun ConnectScreen(vm: AuthViewModel, initial: String) {
    val ui by vm.ui.collectAsState()
    var url by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Rushify", color = RColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Muted("Connect to your Rushify server")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Server address") },
            placeholder = { Text("https://rushify.example.com or http://192.168.1.20:8040") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("server_url"),
        )
        Spacer(Modifier.height(8.dp))
        Muted("HTTPS is recommended. Plain http:// works for a public or private IP with a port, but traffic is not encrypted.")
        ui.error?.let { Spacer(Modifier.height(10.dp)); Banner(it, RColors.Loss) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.connect(url) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("connect")) {
            if (ui.busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp, color = RColors.Bg) else Text("Test connection")
        }
    }
    ui.confirmCleartext?.let { u ->
        AlertDialog(
            onDismissRequest = vm::dismissCleartext,
            title = { Text("Traffic is not encrypted") },
            text = { Text("$u uses plain HTTP. Your password and session token can be read by anyone on the network path. Only continue if you trust this network, or use HTTPS.") },
            confirmButton = { TextButton(onClick = { vm.connect(url, cleartextConfirmed = true) }, modifier = Modifier.testTag("accept_http")) { Text("Connect anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissCleartext) { Text("Cancel") } },
            containerColor = RColors.Surface,
        )
    }
}

@Composable
fun LoginScreen(vm: AuthViewModel, server: String, expired: Boolean) {
    val ui by vm.ui.collectAsState()
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Sign in", color = RColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Muted(server + (ui.health?.let { " · v${it.version}" } ?: ""))
        if (server.startsWith("http://")) { Spacer(Modifier.height(8.dp)); Banner("Not encrypted (http). Your password is sent in clear text.") }
        ui.versionWarning?.let { Spacer(Modifier.height(8.dp)); Banner(it) }
        if (expired) { Spacer(Modifier.height(8.dp)); Banner("Your session expired. Please sign in again.") }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username or email") }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth().testTag("username"))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            pass, { pass = it }, singleLine = true, label = { Text("Password") }, enabled = !ui.locked,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        ui.error?.let { Spacer(Modifier.height(10.dp)); Banner(it, RColors.Loss, Modifier.testTag("login_error")) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.login(user, pass) }, enabled = !ui.busy && !ui.locked && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("sign_in")) {
            if (ui.busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp, color = RColors.Bg) else Text("Sign in")
        }
        TextButton(onClick = vm::changeServer, colors = ButtonDefaults.textButtonColors(contentColor = RColors.Muted)) { Text("Change server") }
    }
}
