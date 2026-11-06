package com.rushify.mobile.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Stock logos from `GET /api/icons/{symbol}.webp` (96x96 WebP, ~1 KB, cacheable for 30 days).
 *
 * Three caches: OkHttp's disk cache honours the server's Cache-Control (so a logo is downloaded once per month),
 * [memory] keeps decoded bitmaps for scrolling lists, and [missing] remembers symbols the server has no logo for
 * (404) for this process so lists do not re-ask. Callers draw a letter badge when [image] returns null.
 */
class IconLoader(private val client: OkHttpClient) {
    private val memory = LruCache<String, ImageBitmap>(300)
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun cached(symbol: String?): ImageBitmap? = key(symbol)?.let { memory.get(it) }

    /** True once the server said there is no logo for [symbol] (so the UI can skip loading straight to the badge). */
    fun isKnownMissing(symbol: String?): Boolean = key(symbol)?.let { it in missing } == true

    suspend fun image(symbol: String?): ImageBitmap? {
        val k = key(symbol) ?: return null
        memory.get(k)?.let { return it }
        val bytes = bytes(k) ?: return null
        val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
        return bmp.asImageBitmap().also { memory.put(k, it) }
    }

    /** Raw WebP bytes, or null when there is no logo (404, remembered) or the request failed (not remembered, retried later). */
    suspend fun bytes(symbol: String?): ByteArray? {
        val k = key(symbol) ?: return null
        if (k in missing) return null
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(urlFor(k)).build()).execute().use { resp ->
                    when {
                        resp.code == 404 -> { missing.add(k); null }
                        !resp.isSuccessful -> null
                        else -> resp.body?.source()?.let { src ->
                            src.request(MAX_BYTES + 1)
                            if (src.buffer.size > MAX_BYTES) null else src.readByteArray()
                        }
                    }
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 256_000L

        /** Upper-case, trimmed symbol, or null when blank or implausible (so we never build odd URLs). */
        fun key(symbol: String?): String? {
            val s = symbol?.trim()?.uppercase().orEmpty()
            return if (s.isEmpty() || s.length > 40 || !s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) null else s
        }

        /** Placeholder host: the client's BaseUrlInterceptor swaps in the stored server. */
        fun urlFor(key: String): HttpUrl = HttpUrl.Builder().scheme("http").host("localhost")
            .addPathSegments("api/icons").addPathSegment("$key.webp").build()

        /** Same server rewrite as the API client, but no auth header (the endpoint is public) and a 20 MB disk cache. */
        fun client(session: SessionManager, cacheDir: File): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor(session))
            .cache(Cache(File(cacheDir, "icons"), 20L * 1024 * 1024))
            .followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** Letter badge shown when a symbol has no logo: first two letters of the base symbol on a colour from the symbol. */
object IconBadge {
    fun text(symbol: String?): String {
        val base = (symbol ?: "").trim().uppercase().replace(Regex("\\.[A-Z]{1,4}$"), "").filter { it.isLetterOrDigit() }
        return base.take(2).ifEmpty { "?" }
    }

    /** Hue 0..359, stable per symbol. */
    fun hue(symbol: String?): Float {
        var h = 0
        for (c in (symbol ?: "").uppercase()) h = (h * 31 + c.code) % 360
        return h.toFloat()
    }
}
