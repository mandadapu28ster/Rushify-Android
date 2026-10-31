package com.rushify.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rushify.mobile.data.CacheEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheDbTest {
    private lateinit var env: DeviceEnv
    private val dao get() = env.db.dao()

    @Before fun setUp() { env = DeviceEnv() }
    @After fun tearDown() = env.close()

    @Test fun putGetRoundTrip() = runBlocking {
        dao.put(CacheEntry("srv|7|dashboard", "{\"a\":1}", 42))
        assertEquals("{\"a\":1}", dao.get("srv|7|dashboard")!!.json)
        assertEquals(42L, dao.get("srv|7|dashboard")!!.fetchedAt)
    }

    @Test fun upsertReplaces() = runBlocking {
        dao.put(CacheEntry("k", "old", 1)); dao.put(CacheEntry("k", "new", 2))
        assertEquals("new", dao.get("k")!!.json)
    }

    @Test fun keysForDifferentServerOrUserDoNotCollide() = runBlocking {
        dao.put(CacheEntry("http://a|7|dashboard", "A7", 1))
        dao.put(CacheEntry("http://a|8|dashboard", "A8", 1))
        dao.put(CacheEntry("http://b|7|dashboard", "B7", 1))
        assertEquals("A8", dao.get("http://a|8|dashboard")!!.json)
        assertNull(dao.get("http://c|7|dashboard"))
    }

    @Test fun clearWipesAllScopes() = runBlocking {
        dao.put(CacheEntry("a", "1", 1)); dao.put(CacheEntry("b", "2", 1))
        dao.clear()
        assertNull(dao.get("a")); assertNull(dao.get("b"))
    }

    @Test fun repositoryRoundTripThroughRealRoom() = runBlocking {
        env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("dashboard.json")) }
        env.repo.dashboard()
        assertEquals("GBP", env.repo.dashboardCached()!!.value.baseCurrency)
        env.repo.wipeCache()
        assertNull(env.repo.dashboardCached())
    }
}
