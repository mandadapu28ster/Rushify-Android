package com.rushify.mobile.data

import com.rushify.mobile.TestEnv
import com.rushify.mobile.fixture
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); env.signedIn() }
    @After fun tearDown() = env.shutdown()

    @Test fun refreshStoresAndCacheReadsBackWithTimestamp() = runBlocking {
        env.json(fixture("dashboard.json")); env.now = 5_000
        val fresh = env.repo.dashboard()
        assertEquals(5_000L, fresh.fetchedAt)
        val cached = env.repo.dashboardCached()!!
        assertEquals(BigDecimal("1000.50"), cached.value.totalMarketValueBase)
    }

    @Test fun emptyResponseNeverOverwritesGoodCache() = runBlocking {
        env.json(fixture("dashboard.json")); env.now = 1_000; env.repo.dashboard()
        env.json("""{"rows":[],"net_worth_total_base":null}"""); env.now = 9_000
        val result = env.repo.dashboard()
        assertEquals(1_000L, result.fetchedAt)           // old copy returned
        assertEquals(5, result.value.rows.size)
        assertEquals(1_000L, env.repo.dashboardCached()!!.fetchedAt)
    }

    @Test fun failedRefreshKeepsCache() = runBlocking {
        env.json(fixture("dashboard.json")); env.repo.dashboard()
        env.json("oops", 500)
        try { env.repo.dashboard() } catch (_: ApiError.Server) {}
        assertNotNull(env.repo.dashboardCached())
    }

    @Test fun cacheIsKeyedByServerAndUser() = runBlocking {
        env.json(fixture("dashboard.json")); env.repo.dashboard()
        env.session.logout(); env.session.saveLogin("t2", Me(id = 8, username = "other"))
        assertNull(env.repo.dashboardCached())            // other user cannot see user 7's data
        env.session.logout(); env.session.saveLogin("t1", Me(id = 7, username = "TestUser"))
        assertNotNull(env.repo.dashboardCached())
    }

    @Test fun wipeClearsEverything() = runBlocking {
        env.json(fixture("dashboard.json")); env.repo.dashboard()
        env.repo.wipeCache()
        assertTrue(env.dao.map.isEmpty()); assertNull(env.repo.dashboardCached())
    }

    @Test fun radarCachedPerScope() = runBlocking {
        env.json(fixture("radar.json")); env.repo.radar("overall")
        assertNotNull(env.repo.radarCached("overall")); assertNull(env.repo.radarCached("other"))
    }

    @Test fun corruptCacheReadsAsMissNotCrash() = runBlocking {
        env.dao.put(CacheEntry("${env.session.current.cacheScope}|dashboard", "{broken", 1))
        assertNull(env.repo.dashboardCached())
    }
}
