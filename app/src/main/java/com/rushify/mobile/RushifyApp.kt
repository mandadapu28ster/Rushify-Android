package com.rushify.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.rushify.mobile.data.CacheDb
import com.rushify.mobile.data.EncryptedKeyValueStore
import com.rushify.mobile.data.IconLoader
import com.rushify.mobile.data.Network
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SessionManager
import com.rushify.mobile.work.SyncScheduler

class AppContainer(context: Context) {
    val session: SessionManager = SessionManager(openStore(context))
    private val db = Room.databaseBuilder(context, CacheDb::class.java, "rushify-cache.db").fallbackToDestructiveMigration(true).build()
    val repo = RushifyRepository(Network.api(session), session, db.dao())
    val icons = IconLoader(IconLoader.client(session, context.cacheDir))

    private fun openStore(context: Context): EncryptedKeyValueStore = try {
        EncryptedKeyValueStore(context)
    } catch (e: Exception) {
        // Keystore key lost (e.g. restored device): start clean rather than crash. User signs in again.
        context.deleteSharedPreferences("rushify_session")
        EncryptedKeyValueStore(context)
    }
}

class RushifyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Sync results", NotificationManager.IMPORTANCE_DEFAULT))
        SyncScheduler.schedule(this)
    }

    companion object { const val CHANNEL_SYNC = "sync_results" }
}
