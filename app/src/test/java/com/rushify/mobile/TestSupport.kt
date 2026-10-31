package com.rushify.mobile

import com.rushify.mobile.data.CacheDao
import com.rushify.mobile.data.CacheEntry
import com.rushify.mobile.data.MemoryKeyValueStore
import com.rushify.mobile.data.Network
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SessionManager
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer

fun fixture(name: String): String =
    object {}.javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

class FakeCacheDao : CacheDao {
    val map = LinkedHashMap<String, CacheEntry>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(entry: CacheEntry) { map[entry.key] = entry }
    override suspend fun clear() = map.clear()
}

class TestEnv(timeoutSec: Long = 3) {
    val server = MockWebServer().apply { start() }
    val session = SessionManager(MemoryKeyValueStore())
    val dao = FakeCacheDao()
    var now = 1_000L
    val repo = RushifyRepository(Network.api(session, Network.client(session, timeoutSec)), session, dao) { now }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun signedIn(token: String = "jwt-token", userId: Int = 7) {
        session.setServer(baseUrl)
        session.saveLogin(token, com.rushify.mobile.data.Me(id = userId, username = "TestUser"))
    }

    fun json(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    private val routes = java.util.concurrent.ConcurrentHashMap<String, () -> MockResponse>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Full request paths including the query string. */
    val fullPaths = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Path-based routing for flows that make several calls (replaces the FIFO queue). */
    fun route(method: String, path: String, handler: () -> MockResponse) {
        routes["$method $path"] = handler
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path!!.substringBefore('?')}"
                fullPaths += request.path!!
                return routes["${request.method} ${request.path!!.substringBefore('?')}"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
    }

    fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    fun count(method: String, path: String) = requests.count { it == "$method $path" }

    fun shutdown() = server.shutdown()
}
