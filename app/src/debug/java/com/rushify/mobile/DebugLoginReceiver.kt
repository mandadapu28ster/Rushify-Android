package com.rushify.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** adb shell am broadcast -n com.rushify.mobile/.DebugLoginReceiver --es server http://host:port --es token <jwt> */
class DebugLoginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val server = intent.getStringExtra("server") ?: return
        val token = intent.getStringExtra("token") ?: return
        val c = (context.applicationContext as RushifyApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (c.session.setServer(server)) c.repo.wipeCache()
                c.session.acknowledgeCleartext(server)
                val me = c.repo.api.meWith("Bearer $token")
                c.session.saveLogin(token, me)
            } catch (e: Exception) {
                android.util.Log.w("DebugLogin", "sign-in failed: ${e.message}")
            } finally { pending.finish() }
        }
    }
}
