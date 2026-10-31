package com.rushify.mobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun clear()
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    @Synchronized override fun clear() = map.clear()
}

/** Keystore-backed storage. Only the JWT is ever stored for credentials; the password never is. */
class EncryptedKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "rushify_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    override fun clear() = prefs.edit().clear().apply()
}

data class SessionState(
    val serverUrl: String? = null,
    val token: String? = null,
    val userId: Int? = null,
    val username: String? = null,
    val role: String? = null,
    val baseCurrency: String = "GBP",
    val expired: Boolean = false,
) {
    val isAdmin get() = role.equals("admin", ignoreCase = true)
    val loggedIn get() = !token.isNullOrEmpty() && serverUrl != null
    val cleartext get() = serverUrl?.startsWith("http://") == true
    /** Cache scope: server + user, so data never leaks across accounts or servers. */
    val cacheScope get() = "$serverUrl|$userId"
}

class SessionManager(private val store: KeyValueStore) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> get() = _state
    val current get() = _state.value

    private fun load() = SessionState(
        serverUrl = store.get(K_SERVER),
        token = store.get(K_TOKEN),
        userId = store.get(K_USER_ID)?.toIntOrNull(),
        username = store.get(K_USERNAME),
        role = store.get(K_ROLE),
        baseCurrency = store.get(K_CCY) ?: "GBP",
    )

    /** Changing the server drops the session. Returns true when the server actually changed. */
    @Synchronized fun setServer(url: String): Boolean {
        if (url == current.serverUrl) return false
        store.put(K_TOKEN, null); store.put(K_USER_ID, null); store.put(K_USERNAME, null); store.put(K_ROLE, null)
        store.put(K_SERVER, url)
        _state.value = SessionState(serverUrl = url)
        return true
    }

    @Synchronized fun saveLogin(token: String, me: Me) {
        store.put(K_TOKEN, token); store.put(K_USER_ID, me.id.toString())
        store.put(K_USERNAME, me.username); store.put(K_CCY, me.baseCurrency); store.put(K_ROLE, me.role)
        _state.value = _state.value.copy(token = token, userId = me.id, username = me.username, role = me.role, baseCurrency = me.baseCurrency, expired = false)
    }

    /** Single-flight: concurrent 401s clear the session once. Returns true for the caller that cleared it. */
    @Synchronized fun expire(): Boolean {
        if (current.token == null) return false
        store.put(K_TOKEN, null)
        _state.value = _state.value.copy(token = null, expired = true)
        return true
    }

    @Synchronized fun logout() {
        store.put(K_TOKEN, null); store.put(K_USER_ID, null); store.put(K_USERNAME, null); store.put(K_ROLE, null)
        _state.value = _state.value.copy(token = null, userId = null, username = null, role = null, expired = false)
    }

    @Synchronized fun clearServer() {
        store.put(K_SERVER, null); store.put(K_TOKEN, null); store.put(K_USER_ID, null); store.put(K_USERNAME, null); store.put(K_ROLE, null)
        _state.value = SessionState()
    }

    fun clearExpiredFlag() { _state.value = _state.value.copy(expired = false) }

    fun cleartextAcknowledged(serverUrl: String) = store.get(K_ACK + serverUrl) == "1"
    fun acknowledgeCleartext(serverUrl: String) = store.put(K_ACK + serverUrl, "1")

    var hideBalances: Boolean
        get() = store.get(K_HIDE) == "1"
        set(v) = store.put(K_HIDE, if (v) "1" else "0")
    var screenPrivacy: Boolean
        get() = store.get(K_PRIVACY) == "1"
        set(v) = store.put(K_PRIVACY, if (v) "1" else "0")
    var biometricLock: Boolean
        get() = store.get(K_BIO) == "1"
        set(v) = store.put(K_BIO, if (v) "1" else "0")
    var notificationsEnabled: Boolean
        get() = store.get(K_NOTIF) != "0"
        set(v) = store.put(K_NOTIF, if (v) "1" else "0")
    var lastNotifiedSyncId: Int
        get() = store.get(K_LAST_SYNC)?.toIntOrNull() ?: 0
        set(v) = store.put(K_LAST_SYNC, v.toString())

    private companion object {
        const val K_SERVER = "server"; const val K_TOKEN = "token"; const val K_USER_ID = "user_id"
        const val K_USERNAME = "username"; const val K_CCY = "ccy"; const val K_ACK = "ack:"
        const val K_HIDE = "hide"; const val K_PRIVACY = "privacy"; const val K_BIO = "bio"
        const val K_NOTIF = "notif"; const val K_LAST_SYNC = "last_sync"; const val K_ROLE = "role"
    }
}
