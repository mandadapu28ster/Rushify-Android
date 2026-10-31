package com.rushify.mobile.data

import com.rushify.mobile.TestEnv
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkErrorsTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv(timeoutSec = 1); env.session.setServer(env.baseUrl) }
    @After fun tearDown() = env.shutdown()

    private inline fun <reified E : ApiError> expect(crossinline block: suspend () -> Unit): E = runBlocking {
        try { block(); fail("expected ${E::class.simpleName}"); throw AssertionError() }
        catch (e: ApiError) { assertTrue("was ${e::class.simpleName}: ${e.message}", e is E); e as E }
    }

    private fun login() = expect<ApiError.BadCredentials> { env.repo.call { env.repo.api.login(LoginRequest("u", "p")) } }

    @Test fun login401IsBadCredentialsWithServerMessage() {
        env.json("""{"detail":"Login failure try again."}""", 401)
        assertEquals("Login failure try again.", login().message)
    }

    @Test fun login403LockedIsLocked() {
        env.json("""{"detail":"You are locked, wait for one day and try again"}""", 403)
        val e = expect<ApiError.Locked> { env.repo.call { env.repo.api.login(LoginRequest("u", "p")) } }
        assertTrue(e.message!!.contains("locked"))
    }

    @Test fun login403InactiveIsInactive() {
        env.json("""{"detail":"Inactive user"}""", 403)
        expect<ApiError.Inactive> { env.repo.call { env.repo.api.login(LoginRequest("u", "p")) } }
    }

    @Test fun authenticated401ExpiresSessionOnce() {
        env.signedIn()
        env.json("""{"detail":"Not authenticated"}""", 401)
        env.json("""{"detail":"Not authenticated"}""", 401)
        expect<ApiError.SessionExpired> { env.repo.call { env.repo.api.dashboard() } }
        assertNull(env.session.current.token)
        assertTrue(env.session.current.expired)
        assertFalse(env.session.expire()) // second concurrent 401 does not "clear" again
    }

    @Test fun status429IsQuotaReached() {
        env.signedIn(); env.json("""{"detail":"Manual sync limit reached (3 per 24h)"}""", 429)
        val e = expect<ApiError.QuotaReached> { env.repo.call { env.repo.api.syncT212() } }
        assertTrue(e.message!!.contains("limit"))
    }

    @Test fun status409IsSyncRunning() {
        env.signedIn(); env.json("""{"detail":"A sync is already running"}""", 409)
        expect<ApiError.SyncRunning> { env.repo.call { env.repo.api.syncSnaptrade() } }
    }

    @Test fun status400NotConfiguredIsNotConfigured() {
        env.signedIn(); env.json("""{"detail":"Gemini API key not configured"}""", 400)
        expect<ApiError.NotConfigured> { env.repo.call { env.repo.api.aiInsight(AiRequest("hi")) } }
    }

    @Test fun otherStatus400IsPlainServerError() {
        env.signedIn(); env.json("""{"detail":"Bad prompt"}""", 400)
        assertEquals("Bad prompt", expect<ApiError.Server> { env.repo.call { env.repo.api.aiInsight(AiRequest("hi")) } }.message)
    }

    @Test fun status500IsServerError() {
        env.signedIn(); env.json("boom", 500)
        assertEquals(500, expect<ApiError.Server> { env.repo.call { env.repo.api.dashboard() } }.code)
    }

    @Test fun timeoutIsNetworkError() {
        env.signedIn(); env.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expect<ApiError.Network> { env.repo.call { env.repo.api.dashboard() } }
    }

    @Test fun malformedJsonIsServerErrorNotCrash() {
        env.signedIn(); env.json("{not json")
        expect<ApiError.Server> { env.repo.call { env.repo.api.dashboard() } }
    }

    @Test fun validationErrorArrayDetailIsReadable() {
        env.signedIn(); env.json("""{"detail":[{"msg":"field required","loc":["body","x"]}]}""", 422)
        assertEquals("field required", expect<ApiError.Server> { env.repo.call { env.repo.api.dashboard() } }.message)
    }

    @Test fun bearerTokenAndStoredHostAreUsed() = runBlocking {
        env.signedIn("abc.def.ghi")
        env.json(fixtureMe())
        env.repo.call { env.repo.api.me() }
        val req = env.server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer abc.def.ghi", req.getHeader("Authorization"))
        assertEquals("/api/auth/me", req.path)
    }

    @Test fun noAuthHeaderBeforeLogin() = runBlocking {
        env.json("""{"status":"ok","app":"rushify","version":"0.2.2"}""")
        env.repo.call { env.repo.api.health() }
        assertNull(env.server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun httpsToHttpRedirectIsNotFollowed() {
        env.signedIn()
        env.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/steal"))
        // A redirect is surfaced as an error rather than followed to another host.
        val e = runBlocking { runCatching { env.repo.call { env.repo.api.dashboard() } }.exceptionOrNull() }
        assertNotNull(e)
        assertEquals(1, env.server.requestCount)
    }

    private fun fixtureMe() = com.rushify.mobile.fixture("me.json")
}
