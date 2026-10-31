package com.rushify.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val store = MemoryKeyValueStore()
    private val s = SessionManager(store)
    private val me = Me(id = 7, username = "TestUser")

    @Test fun savesOnlyTheTokenNeverAPassword() {
        s.setServer("http://h:1"); s.saveLogin("jwt", me)
        val keys = listOf("server", "token", "user_id", "username", "ccy").mapNotNull { store.get(it) }
        assertTrue(keys.contains("jwt"))
        assertFalse(store.get("password") != null)
    }

    @Test fun loggedInNeedsServerAndToken() {
        assertFalse(s.current.loggedIn)
        s.setServer("http://h:1"); assertFalse(s.current.loggedIn)
        s.saveLogin("jwt", me); assertTrue(s.current.loggedIn)
    }

    @Test fun changingServerDropsSession() {
        s.setServer("http://h:1"); s.saveLogin("jwt", me)
        assertTrue(s.setServer("https://other"))
        assertNull(s.current.token)
        assertFalse(s.setServer("https://other"))
    }

    @Test fun logoutKeepsServerButDropsUser() {
        s.setServer("http://h:1"); s.saveLogin("jwt", me); s.logout()
        assertEquals("http://h:1", s.current.serverUrl); assertNull(s.current.token); assertNull(s.current.userId)
    }

    @Test fun cacheScopeIsServerPlusUser() {
        s.setServer("http://h:1"); s.saveLogin("jwt", me)
        assertEquals("http://h:1|7", s.current.cacheScope)
    }

    @Test fun sessionSurvivesRestartButExpiryClearsToken() {
        s.setServer("http://h:1"); s.saveLogin("jwt", me)
        assertTrue(SessionManager(store).current.loggedIn)
        assertTrue(s.expire())
        assertFalse(SessionManager(store).current.loggedIn)
    }

    @Test fun cleartextAckIsPerServer() {
        s.acknowledgeCleartext("http://a:1")
        assertTrue(s.cleartextAcknowledged("http://a:1")); assertFalse(s.cleartextAcknowledged("http://b:1"))
    }

    @Test fun cleartextDetectedFromStoredScheme() {
        s.setServer("http://h:1"); assertTrue(s.current.cleartext)
        s.setServer("https://h"); assertFalse(s.current.cleartext)
    }
}
