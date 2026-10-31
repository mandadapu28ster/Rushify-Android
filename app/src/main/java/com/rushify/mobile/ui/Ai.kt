package com.rushify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.AiMessage
import com.rushify.mobile.data.AiRequest
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.RushifyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMsg(val role: String, val text: String)

data class AiUi(
    val messages: List<ChatMsg> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val notConfigured: Boolean = false,
    val warning: String? = null,
    val suggestOnline: Boolean = false,
    val online: Boolean = false,
    val restore: String? = null, // prompt handed back to the text box after a failure
    val providerLabel: String? = null, // "Gemini" or "OpenAI", from the user's saved provider
    val keyReady: Boolean? = null,     // null = not checked yet / check failed (then the server decides)
    val disclaimer: String = "Educational / testing only; not financial advice.",
) {
    /** The composer is off when the key is known to be missing (server said so, or the credentials check did). */
    val blocked: Boolean get() = notConfigured || keyReady == false
}

/** The web's wording for AI failures, keyed off the provider and the server's own error text. */
object AiErrors {
    private val OPENAI_PREFIXES = listOf("openai assistant error", "openai assistant run", "openai assistant returned", "openai assistant stalled", "openai assistant hit", "openai assistant run timed out", "openai tool loop", "openai request failed")

    /** True when the backend put an OpenAI error string into a normal (HTTP 200) reply. */
    fun isOpenAiFailureText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return OPENAI_PREFIXES.any { t.startsWith(it) } || t.contains("rate_limit") || t.contains("rate limit exceeded") || t.contains("exceeded your current quota") || t.contains("invalid_api_key")
    }

    fun openAiBanner(full: String): String {
        val low = full.lowercase()
        if (low.contains("rate_limit") || low.contains("rate limit") || (low.contains("quota") && low.contains("exceeded")) || low.contains("check your plan and billing"))
            return "OpenAI blocked this request (quota or rate limit). Check usage and billing at platform.openai.com, then retry. Tip: in the web app under Settings → Keys use \"Chat agent (tools)\" instead of Assistants; it usually uses fewer Assistants API calls."
        val t = full.trim()
        return if (t.length > 900) t.take(900) + "…" else t
    }

    const val OPENAI_FAILED_BUBBLE = "Couldn't generate a reply. See the red error notice. It is often quota, billing or rate limits on your OpenAI account."

    /** Thrown errors: known categories get the web's text; anything else keeps the server's own message. */
    fun forThrown(message: String?, online: Boolean, providerLabel: String?): String {
        val low = (message ?: "").lowercase()
        return when {
            online && (low.contains("google_search_retrieval is not supported") || low.contains("please use google_search tool instead")) ->
                "Online augmentation (beta) isn't available right now. Turn it off and try again, or retry later."
            low.contains("503") || low.contains("unavailable") || low.contains("high demand") ->
                "Google's AI is temporarily busy (high demand). This is not a Rushify outage. Try again in a few minutes."
            low.contains("not configured") || low.contains("api key") -> "Add your ${providerLabel ?: "Gemini or OpenAI"} API key in the web app under Settings → Keys (and select the matching provider)."
            message.isNullOrBlank() -> "Couldn't reach the AI service. Check your connection and try again."
            else -> message
        }
    }
}

/** Chat lives in memory only: it is never cached to disk and is discarded with the session. */
class AiViewModel(private val repo: RushifyRepository) : ViewModel() {
    val ui = MutableStateFlow(AiUi())

    init {
        // Like the web: learn up front whether the selected provider has a key, instead of after a failed send.
        viewModelScope.launch {
            repo.aiCredentials()?.let { c -> ui.update { it.copy(providerLabel = c.providerLabel, keyReady = c.ready) } }
        }
    }

    fun setOnline(on: Boolean) = ui.update { it.copy(online = on, suggestOnline = false) }
    fun clear() = ui.update { AiUi(online = it.online, providerLabel = it.providerLabel, keyReady = it.keyReady) }
    fun clearWarning() = ui.update { it.copy(warning = null) }
    fun consumeRestore() = ui.update { it.copy(restore = null) }

    fun send(raw: String) {
        val prompt = raw.trim()
        val cur = ui.value
        if (prompt.isEmpty() || cur.sending || cur.blocked) return
        if (prompt.length > MAX_PROMPT) { ui.update { it.copy(error = "Message is too long (max $MAX_PROMPT characters).") }; return }
        val history = historyFor(cur.messages)
        ui.update { it.copy(messages = it.messages + ChatMsg("user", prompt), sending = true, error = null, warning = null, suggestOnline = false) }
        viewModelScope.launch {
            try {
                val r = repo.call { repo.api.aiInsight(AiRequest(prompt, cur.online, history)) }
                if (AiErrors.isOpenAiFailureText(r.text)) {
                    // The server answered 200 but the text is an OpenAI error: show it as an error, keep the question in the chat.
                    ui.update { it.copy(messages = it.messages + ChatMsg("assistant", AiErrors.OPENAI_FAILED_BUBBLE), sending = false, error = AiErrors.openAiBanner(r.text), warning = null, suggestOnline = false) }
                    return@launch
                }
                ui.update { it.copy(messages = it.messages + ChatMsg("assistant", r.text), sending = false, warning = r.augmentationWarning, suggestOnline = r.suggestOnlineAugmentation && !it.online, disclaimer = r.disclaimer ?: it.disclaimer) }
            } catch (e: ApiError.NotConfigured) {
                ui.update { it.copy(messages = it.messages.dropLast(1), sending = false, notConfigured = true, keyReady = false, restore = prompt) }
            } catch (e: ApiError) {
                ui.update { it.copy(messages = it.messages.dropLast(1), sending = false, error = AiErrors.forThrown(e.message, cur.online, cur.providerLabel), restore = prompt) }
            }
        }
    }

    companion object {
        const val MAX_PROMPT = 4000
        const val MAX_HISTORY = 40
        const val MAX_CONTENT = 16000

        /** Completed turns only, last 40, each capped to the server's per-message limit. */
        fun historyFor(messages: List<ChatMsg>): List<AiMessage> =
            messages.takeLast(MAX_HISTORY).map { AiMessage(it.role, it.text.take(MAX_CONTENT)) }
    }
}

/** Minimal markdown: **bold**, "* " / "- " bullets and "#" headings. Everything else is shown as plain text. */
object SimpleMarkdown {
    fun toAnnotated(src: String): AnnotatedString = buildAnnotatedString {
        src.lines().forEachIndexed { i, rawLine ->
            if (i > 0) append('\n')
            var line = rawLine
            val heading = line.trimStart().startsWith("#")
            if (heading) line = line.trimStart().trimStart('#').trim()
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) line = "• " + trimmed.drop(2)
            if (heading) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendBold(line) } else appendBold(line)
        }
    }

    private fun AnnotatedString.Builder.appendBold(line: String) {
        val parts = line.split("**")
        parts.forEachIndexed { idx, part ->
            when {
                idx % 2 == 0 -> append(part)
                idx < parts.size - 1 -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
                else -> append("**$part") // opening marker with no closing one: show literally
            }
        }
    }
}

@Composable
fun AiScreen(vm: AiViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.restore) { ui.restore?.let { draft = it; vm.consumeRestore() } }
    LaunchedEffect(ui.messages.size, ui.sending) { if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.size) }
    LaunchedEffect(ui.warning) { if (ui.warning != null) { delay(6_000); vm.clearWarning() } }
    SubScreen("AI assistant", onBack) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Muted(ui.disclaimer, Modifier.weight(1f).testTag("ai_disclaimer"))
                    if (ui.messages.isNotEmpty()) Chip("Clear chat", false, "ai_clear") { vm.clear(); draft = "" }
                }
                if (!ui.blocked && !ui.online && ui.keyReady == true) Muted("Standard mode: replies use your Rushify portfolio snapshot only (no live web). Turn on online augmentation (beta) to allow web search when data is missing.", Modifier.testTag("ai_standard_note"))
                ui.warning?.let { Banner(it, RColors.Warn) }
                ui.error?.let { Banner(it, RColors.Loss, Modifier.testTag("ai_error")) }
            }
            if (ui.blocked) {
                RCard(Modifier.padding(16.dp).testTag("ai_not_configured")) {
                    Text(ui.providerLabel?.let { "No $it API key saved yet." } ?: "AI isn't set up yet", color = RColors.Text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Muted("The assistant uses your own ${ui.providerLabel ?: "Gemini or OpenAI"} API key. Add it, and choose the matching provider, in the web app under Settings → Keys, then come back here. Keys are never entered on the phone.")
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai_messages"), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.messages.isEmpty() && !ui.blocked) item { Muted("Ask about your portfolio, e.g. \"Which sectors am I most exposed to?\"") }
                items(ui.messages) { m ->
                    val mine = m.role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(14.dp)).background(if (mine) RColors.Accent.copy(alpha = 0.2f) else RColors.Surface).padding(12.dp)) {
                            Text(if (mine) AnnotatedString(m.text) else SimpleMarkdown.toAnnotated(m.text), color = RColors.Text, fontSize = 14.sp)
                        }
                    }
                }
                if (ui.sending) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp); Muted("  Thinking…") } }
                if (ui.suggestOnline) item { Chip("Answer used local data only. Try online augmentation (beta)?", false) { vm.setOnline(true) } }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(ui.online, vm::setOnline, modifier = Modifier.testTag("ai_online"))
                    Spacer(Modifier.padding(start = 8.dp)); Muted("Use online augmentation (beta)")
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft, { if (it.length <= AiViewModel.MAX_PROMPT) draft = it }, modifier = Modifier.weight(1f).testTag("ai_input"),
                        label = { Text("Ask something") }, maxLines = 4, enabled = !ui.blocked,
                        supportingText = if (draft.length > 3500) ({ Text("${draft.length}/${AiViewModel.MAX_PROMPT}") }) else null,
                    )
                    Button(onClick = { vm.send(draft); draft = "" }, enabled = draft.isNotBlank() && !ui.sending && !ui.blocked, modifier = Modifier.testTag("ai_send")) { Text("Send") }
                }
            }
        }
    }
}
