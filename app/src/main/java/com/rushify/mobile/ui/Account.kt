package com.rushify.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.ReplacePasswordRequest
import com.rushify.mobile.data.RushifyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasswordUi(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

object PasswordRules {
    const val MIN = 8
    /** Same checks and wording as the web's "Set your own password" page. */
    fun validate(current: String, new: String, confirm: String): String? = when {
        new != confirm -> "New password and confirmation do not match."
        new.length < MIN -> "New password must be at least $MIN characters."
        new == current -> "New password must be different from your current password."
        current.isEmpty() -> "Enter your current password."
        else -> null
    }
}

/**
 * Changes the password through the same public endpoint as the web. The server then ends every session of this
 * user, so on success the app signs out and asks for the new password. Passwords are never stored.
 */
class ChangePasswordViewModel(private val repo: RushifyRepository) : ViewModel() {
    val ui = MutableStateFlow(PasswordUi())

    fun submit(current: String, new: String, confirm: String) {
        if (ui.value.busy) return
        PasswordRules.validate(current, new, confirm)?.let { msg -> ui.value = PasswordUi(error = msg); return }
        val username = repo.session.current.username ?: run { ui.value = PasswordUi(error = "You are not signed in."); return }
        viewModelScope.launch {
            ui.value = PasswordUi(busy = true)
            try {
                repo.call { repo.api.replacePassword(ReplacePasswordRequest(username, current, new, confirm)) }
                ui.update { PasswordUi(done = true) }
            } catch (e: ApiError.BadCredentials) {
                ui.value = PasswordUi(error = "Current password is incorrect.")
            } catch (e: ApiError.SessionExpired) {
                // The endpoint is public, so a 401 means the current password was wrong. The session itself is untouched.
                ui.value = PasswordUi(error = "Current password is incorrect.")
            } catch (e: ApiError) {
                ui.value = PasswordUi(error = e.message ?: "Could not update password")
            }
        }
    }

    /** After success: all sessions are revoked server-side, so leave cleanly and show the sign-in screen. */
    suspend fun finish() { repo.session.expire(); repo.wipeCache() }
}

@Composable
fun ChangePasswordScreen(vm: ChangePasswordViewModel, onBack: () -> Unit) {
    val s by vm.ui.collectAsState()
    var cur by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var conf by rememberSaveable { mutableStateOf("") }
    SubScreen("Change password", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Muted("Choose a new password (at least ${PasswordRules.MIN} characters). You will be signed out everywhere and asked to sign in with the new password.")
            val pw = KeyboardOptions(keyboardType = KeyboardType.Password)
            RField(cur, { cur = it }, "Current password", Modifier.testTag("pw_current"), visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw)
            RField(new, { new = it }, "New password", Modifier.testTag("pw_new"), visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw)
            RField(conf, { conf = it }, "Confirm new password", Modifier.testTag("pw_confirm"), visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw)
            s.error?.let { Banner(it, RColors.Loss, Modifier.testTag("pw_error")) }
            if (s.done) Banner("Password updated. Please sign in with your new password.", RColors.Gain, Modifier.testTag("pw_done"))
            Spacer(Modifier.height(4.dp))
            Button(onClick = { vm.submit(cur, new, conf) }, enabled = !s.busy && !s.done && cur.isNotEmpty() && new.isNotEmpty() && conf.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("pw_submit")) { Text(if (s.busy) "Saving…" else "Save new password") }
        }
    }
}
