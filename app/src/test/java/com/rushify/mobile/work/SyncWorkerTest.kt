package com.rushify.mobile.work

import androidx.work.ListenableWorker
import com.rushify.mobile.TestEnv
import com.rushify.mobile.data.SyncLog
import com.rushify.mobile.fixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncWorkerTest {
    private lateinit var env: TestEnv
    private val notified = mutableListOf<SyncLog>()

    private fun history(vararg logs: String) = "[" + logs.joinToString(",") + "]"
    private fun log(id: Int, kind: String, ok: Boolean, finished: Boolean = true, err: String? = null) =
        """{"id":$id,"kind":"$kind","started_at":"2026-10-17T10:00:00Z","finished_at":${if (finished) "\"2026-10-17T10:01:00Z\"" else "null"},"success":$ok,"error_message":${err?.let { "\"$it\"" } ?: "null"}}"""

    @Before fun setUp() {
        env = TestEnv(); env.signedIn()
        env.route("GET", "/api/portfolio/dashboard") { env.ok(fixture("dashboard.json")) }
        env.route("GET", "/api/sync/history") { env.ok(fixture("sync_history.json")) }
    }
    @After fun tearDown() = env.shutdown()

    private fun run() = runBlocking { RushifySyncWorker.refresh(env.repo, env.session) { notified += it } }

    @Test fun skipsEverythingWhenSignedOut() {
        env.session.logout()
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, env.requests.size)
    }

    @Test fun firstRunOnlyBaselinesNeverReplaysOldHistory() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(notified.isEmpty())
        assertEquals(12, env.session.lastNotifiedSyncId)
    }

    @Test fun laterRunNotifiesNewManualAndFailedButNotScheduledSuccess() {
        run()
        env.route("GET", "/api/sync/history") {
            env.ok(history(log(16, "scheduled", false, err = "T212 down"), log(15, "scheduled", true), log(14, "manual", true), log(13, "snaptrade_manual", false, finished = false), log(12, "snaptrade_manual", true)))
        }
        run()
        assertEquals(listOf(14, 16), notified.map { it.id })         // 15 scheduled-ok skipped, 13 still running
        assertEquals(16, env.session.lastNotifiedSyncId)
        notified.clear(); run()
        assertTrue(notified.isEmpty())                                 // never notified twice
    }

    @Test fun failureNotificationUsesServerErrorText() {
        val l = SyncLog(id = 1, kind = "manual", finishedAt = "x", success = false, errorMessage = "T212 rate limited")
        assertEquals("Trading 212 sync failed", SyncNotifier.title(l))
        assertEquals("T212 rate limited", SyncNotifier.text(l))
        assertEquals("SnapTrade sync finished", SyncNotifier.title(SyncLog(kind = "snaptrade_manual", success = true)))
    }

    @Test fun expiredSessionStopsWithoutRetry() {
        env.route("GET", "/api/portfolio/dashboard") { env.ok("""{"detail":"Not authenticated"}""", 401) }
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(!env.session.current.loggedIn)
    }

    @Test fun networkFailureRetries() {
        env.shutdown()
        val e2 = TestEnv(1).also { it.signedIn(); it.shutdown() }
        val r = runBlocking { RushifySyncWorker.refresh(e2.repo, e2.session) { } }
        assertEquals(ListenableWorker.Result.retry(), r)
    }
}
