package com.rushify.mobile.ui

import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.Health
import com.rushify.mobile.fixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private lateinit var env: TestEnv
    private lateinit var vm: AuthViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestEnv(); env.session.setServer(env.baseUrl)
        vm = AuthViewModel(env.repo)
    }
    @After fun tearDown() { env.shutdown(); Dispatchers.resetMain() }

    private fun await(cond: (AuthUi) -> Boolean) = runBlocking { withTimeout(5_000) { vm.ui.first(cond) } }

    @Test fun usernameIsTrimmedButCasePreserved() {
        env.route("POST", "/api/auth/login") { env.ok("""{"access_token":"jwt","token_type":"bearer"}""") }
        env.route("GET", "/api/auth/me") { env.ok(fixture("me.json")) }
        vm.login("  MixedCase.User@Example.com  ", "pw")
        await { env.session.current.loggedIn }
        val login = env.server.takeRequest()
        val body = login.body.readUtf8()
        assertTrue(body, body.contains("\"username\":\"MixedCase.User@Example.com\""))
    }

    @Test fun successStoresJwtAndUserButNotPassword() {
        env.route("POST", "/api/auth/login") { env.ok("""{"access_token":"jwt-1"}""") }
        env.route("GET", "/api/auth/me") { env.ok(fixture("me.json")) }
        vm.login("u", "secret-password")
        await { env.session.current.loggedIn }
        assertEquals("jwt-1", env.session.current.token); assertEquals(7, env.session.current.userId)
    }

    @Test fun badPasswordShowsMessageAndIsNeverRetried() {
        env.route("POST", "/api/auth/login") { env.ok("""{"detail":"Login failure try again."}""", 401) }
        vm.login("u", "wrong")
        val s = await { it.error != null }
        assertEquals("Login failure try again.", s.error)
        assertFalse(s.locked)
        Thread.sleep(300)
        assertEquals(1, env.count("POST", "/api/auth/login"))
        assertFalse(env.session.current.loggedIn)
    }

    @Test fun lockedAccountDisablesFurtherAttempts() {
        env.route("POST", "/api/auth/login") { env.ok("""{"detail":"You are locked, wait for one day and try again"}""", 403) }
        vm.login("u", "pw")
        assertTrue(await { it.locked }.error!!.contains("locked"))
        vm.login("u", "pw"); vm.login("u", "pw")
        Thread.sleep(200)
        assertEquals(1, env.count("POST", "/api/auth/login"))
    }

    @Test fun inactiveUserShowsMessageWithoutLocking() {
        env.route("POST", "/api/auth/login") { env.ok("""{"detail":"Inactive user"}""", 403) }
        vm.login("u", "pw")
        val s = await { it.error != null }
        assertEquals("Inactive user", s.error); assertFalse(s.locked)
    }

    @Test fun http_requires_one_time_confirmation_before_any_request() {
        env.route("GET", "/api/health") { env.ok("""{"status":"ok","app":"rushify","version":"0.2.2"}""") }
        env.session.clearServer()
        vm.connect(env.baseUrl)
        assertNotNull(vm.ui.value.confirmCleartext)
        assertEquals(0, env.count("GET", "/api/health"))
        vm.connect(env.baseUrl, cleartextConfirmed = true)
        val s = await { it.health != null }
        assertEquals("0.2.2", s.health!!.version)
        assertTrue(env.session.cleartextAcknowledged(env.baseUrl))
        // Second connect to the same server no longer asks.
        vm.connect(env.baseUrl); await { it.health != null }; assertNull(vm.ui.value.confirmCleartext)
    }

    @Test fun invalidAddressShowsReason() {
        vm.connect("http://host:99999")
        assertNotNull(vm.ui.value.error)
    }

    @Test fun unreachableServerClearsServerAndShowsError() {
        env.shutdown()
        env.session.clearServer()
        val vm2 = AuthViewModel(TestEnv().also { it.shutdown() }.repo)
        vm2.connect("http://127.0.0.1:1", cleartextConfirmed = true)
        val s = runBlocking { withTimeout(8_000) { vm2.ui.first { it.error != null } } }
        assertTrue(s.error!!.contains("Could not reach"))
    }

    @Test fun versionWarningOnlyForUntestedMajor() {
        assertNull(AuthViewModel.versionWarning(Health(version = "0.2.2")))
        assertNotNull(AuthViewModel.versionWarning(Health(version = "2.0.0")))
        assertNotNull(AuthViewModel.versionWarning(Health(version = "")))
    }
}
