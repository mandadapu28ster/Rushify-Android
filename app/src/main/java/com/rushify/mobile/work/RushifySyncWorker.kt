package com.rushify.mobile.work

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.rushify.mobile.RushifyApp
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SessionManager
import com.rushify.mobile.data.SyncLog

/** Decides which finished syncs deserve a notification. Pure so it is unit-testable. */
object SyncNotifier {
    /** Finished syncs newer than the last one seen. Scheduled runs only matter when they fail. */
    fun fresh(history: List<SyncLog>, lastNotifiedId: Int): List<SyncLog> =
        history.filter { it.id > lastNotifiedId && it.finishedAt != null }.sortedBy { it.id }

    fun worthNotifying(log: SyncLog) = !log.success || log.kind.endsWith("manual", ignoreCase = true)

    fun title(log: SyncLog) = if (log.success) "${label(log.kind)} sync finished" else "${label(log.kind)} sync failed"
    fun text(log: SyncLog) = if (log.success) "Your portfolio data has been refreshed." else (log.errorMessage ?: "The sync did not complete.")
    fun label(kind: String) = when {
        kind.contains("snaptrade", true) -> "SnapTrade"
        else -> "Trading 212"
    }
}

class RushifySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val container = (applicationContext as RushifyApp).container
        return refresh(container.repo, container.session) { log -> notify(log, container.session) }
    }

    @SuppressLint("MissingPermission")
    private fun notify(log: SyncLog, session: SessionManager) {
        if (!session.notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(applicationContext, RushifyApp.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(SyncNotifier.title(log))
            .setContentText(SyncNotifier.text(log))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(log.id, n)
    }

    companion object {
        /** Extracted so it can be tested without Android services. */
        suspend fun refresh(repo: RushifyRepository, session: SessionManager, notify: (SyncLog) -> Unit): ListenableWorker.Result {
            if (!session.current.loggedIn) return ListenableWorker.Result.success()
            return try {
                repo.dashboard()
                val history = repo.syncHistory().value
                val fresh = SyncNotifier.fresh(history, session.lastNotifiedSyncId)
                if (session.lastNotifiedSyncId == 0 && fresh.isNotEmpty()) {
                    // First run: baseline only, never replay old history as notifications.
                    session.lastNotifiedSyncId = fresh.last().id
                } else if (fresh.isNotEmpty()) {
                    fresh.filter(SyncNotifier::worthNotifying).forEach(notify)
                    session.lastNotifiedSyncId = fresh.last().id
                }
                ListenableWorker.Result.success()
            } catch (e: ApiError.SessionExpired) {
                ListenableWorker.Result.success() // user must sign in again; nothing to retry
            } catch (e: ApiError.Network) {
                ListenableWorker.Result.retry()
            } catch (e: ApiError) {
                ListenableWorker.Result.success()
            }
        }
    }
}
