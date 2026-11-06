package com.rushify.mobile

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.rushify.mobile.data.CacheDb
import com.rushify.mobile.data.Me
import com.rushify.mobile.data.MemoryKeyValueStore
import com.rushify.mobile.data.Network
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SessionManager
import java.util.concurrent.ConcurrentHashMap
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

fun fixture(name: String): String =
    object {}.javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

/** Real Room (in memory) + real Retrofit/OkHttp against an in-process MockWebServer. */
class DeviceEnv {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val server = MockWebServer().apply { start() }
    val session = SessionManager(MemoryKeyValueStore())
    val db: CacheDb = Room.inMemoryDatabaseBuilder(context, CacheDb::class.java).allowMainThreadQueries().build()
    val repo = RushifyRepository(Network.api(session, Network.client(session, 3)), session, db.dao())
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    private val routes = ConcurrentHashMap<String, (String) -> MockResponse>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    val fullPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Every non-GET request as "METHOD /path <body>", for asserting exactly what was written. */
    val written = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.path!!.substringBefore('?')}"
                requests += key
                fullPaths += request.path!!
                if (request.method != "GET") written += "$key ${request.body.readUtf8()}"
                return routes[key]?.invoke(request.path!!) ?: MockResponse().setResponseCode(404)
            }
        }
    }

    fun route(method: String, path: String, handler: () -> MockResponse) { routes["$method $path"] = { _ -> handler() } }
    /** Like [route], but the handler sees the full request path (with query) to branch on parameters. */
    fun routeP(method: String, path: String, handler: (String) -> MockResponse) { routes["$method $path"] = handler }
    fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    fun count(method: String, path: String) = requests.count { it == "$method $path" }

    fun signedIn() {
        session.setServer(baseUrl)
        session.saveLogin("jwt", Me(id = 7, username = "TestUser"))
    }

    /**
     * View models started by a test keep refreshing and caching after the assertions are done. Closing the in-memory database
     * under them throws on a background coroutine and takes down the whole instrumentation process, so let requests go quiet first.
     */
    private fun settle() {
        var last = -1; var stable = 0
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end && stable < 3) {
            val n = requests.size
            if (n == last) stable++ else { stable = 0; last = n }
            Thread.sleep(100)
        }
        Thread.sleep(150)   // the cache write that follows the last response
    }

    fun close() { settle(); server.shutdown(); db.close() }
}
