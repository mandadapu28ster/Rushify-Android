package com.rushify.mobile.ui

import androidx.compose.ui.text.font.FontWeight
import com.rushify.mobile.TestEnv
import com.rushify.mobile.fixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: AiViewModel

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); env = TestEnv(); env.signedIn()
        env.route("GET", "/api/portfolio/credentials") { env.ok(creds("gemini", gemini = true, openai = false)) }
        vm = AiViewModel(env.repo); await { it.keyReady != null } }

    private fun creds(provider: String, gemini: Boolean, openai: Boolean) =
        """{"gemini_configured":$gemini,"openai_configured":$openai,"ai_provider":"$provider","gemini_api_key":"AIza****","t212_api_key":"****"}"""
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun await(cond: (AiUi) -> Boolean) = runBlocking { withTimeout(6_000) { vm.ui.first(cond) } }
    private fun lastBody() = generateSequence { env.server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.last().body.readUtf8()

    @Test fun replyIsAppendedAndFlagsAreRead() {
        env.route("POST", "/api/ai/insight") { env.ok(fixture("ai.json")) }
        vm.send("  Which sectors?  ")
        val s = await { it.messages.size == 2 && !it.sending }
        assertEquals(listOf("user" to "Which sectors?", "assistant" to s.messages[1].text), s.messages.map { it.role to it.text })
        assertTrue(s.suggestOnline); assertTrue(s.disclaimer.contains("not financial advice"))
        assertTrue(lastBody().contains("\"prompt\":\"Which sectors?\""))
    }

    @Test fun historyContainsCompletedTurnsAndOnlineFlag() {
        env.route("POST", "/api/ai/insight") { env.ok(fixture("ai.json")) }
        vm.send("one"); await { it.messages.size == 2 && !it.sending }
        vm.setOnline(true); vm.send("two"); await { it.messages.size == 4 && !it.sending }
        val body = lastBody()
        assertTrue(body, body.contains("\"use_online_augmentation\":true"))
        assertTrue(body, body.contains("\"conversation_history\":[{\"role\":\"user\",\"content\":\"one\"},{\"role\":\"assistant\""))
        assertFalse("current prompt must not be duplicated in history", body.substringAfter("conversation_history").contains("\"content\":\"two\""))
    }

    @Test fun notConfiguredShowsSetupGuidanceAndBlocksFurtherSends() {
        env.route("POST", "/api/ai/insight") { env.ok("""{"detail":"Gemini API key not configured"}""", 400) }
        vm.send("hello")
        val s = await { it.notConfigured }
        assertTrue(s.messages.isEmpty()); assertEquals("hello", s.restore)
        vm.send("again"); Thread.sleep(200)
        assertEquals(1, env.count("POST", "/api/ai/insight"))
    }

    @Test fun otherServerErrorRestoresPromptAndShowsError() {
        env.route("POST", "/api/ai/insight") { env.ok("""{"detail":"Upstream timeout"}""", 502) }
        vm.send("hello")
        val s = await { it.error != null }
        assertEquals("Upstream timeout", s.error); assertEquals("hello", s.restore); assertFalse(s.notConfigured); assertTrue(s.messages.isEmpty())
        vm.consumeRestore(); assertNull(vm.ui.value.restore)
    }

    @Test fun blankAndOverlongPromptsAreRejectedLocally() {
        vm.send("   "); assertTrue(vm.ui.value.messages.isEmpty())
        vm.send("x".repeat(AiViewModel.MAX_PROMPT + 1))
        assertTrue(vm.ui.value.error!!.contains("too long"))
        assertEquals(0, env.count("POST", "/api/ai/insight"))
    }

    @Test fun historyIsCappedAtFortyAndEachMessageAtSixteenThousand() {
        val many = (1..50).map { ChatMsg(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val h = AiViewModel.historyFor(many)
        assertEquals(40, h.size); assertEquals("m11", h.first().content); assertEquals("m50", h.last().content)
        assertEquals(16000, AiViewModel.historyFor(listOf(ChatMsg("user", "y".repeat(20000)))).single().content.length)
    }

    @Test fun clearKeepsOnlineChoice() {
        vm.setOnline(true); vm.clear()
        assertTrue(vm.ui.value.online); assertTrue(vm.ui.value.messages.isEmpty())
    }

    @Test fun augmentationWarningIsExposedThenClearable() {
        env.route("POST", "/api/ai/insight") { env.ok("""{"text":"ok","augmentation_warning":"Online grounding unavailable, used local data."}""") }
        vm.send("q")
        assertTrue(await { it.warning != null }.warning!!.contains("local data"))
        vm.clearWarning(); assertNull(vm.ui.value.warning)
    }

    // ---- markdown ----

    @Test fun markdownBoldBulletsAndHeadings() {
        val a = SimpleMarkdown.toAnnotated(runBlocking { fixture("ai.json").let { com.rushify.mobile.data.AppJson.decodeFromString(com.rushify.mobile.data.AiResponse.serializer(), it).text } })
        assertEquals("You are heavily exposed to Technology.\n• Tech is 44%\n• Health is 20%\nSummary\nDiversify.", a.text)
        val bold = a.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size); assertEquals("heavily", a.text.substring(bold[0].start, bold[0].end))
        assertTrue(a.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
    }

    @Test fun markdownUnmatchedMarkersStayLiteral() {
        assertEquals("a **b", SimpleMarkdown.toAnnotated("a **b").text)
        assertEquals("plain", SimpleMarkdown.toAnnotated("plain").text)
        assertEquals("x y z", SimpleMarkdown.toAnnotated("x **y** z").text)
    }
}
