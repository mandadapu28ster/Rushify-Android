package com.rushify.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IconLoaderTest {
    private val server = MockWebServer()
    private lateinit var loader: IconLoader
    private val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)

    @Before fun setUp() {
        server.start()
        val session = SessionManager(MemoryKeyValueStore()).apply { setServer(server.url("/").toString().trimEnd('/')) }
        loader = IconLoader(OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(session)).build())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun keyNormalisesAndRejectsOddInput() {
        assertEquals("VOD.L", IconLoader.key(" vod.l "))
        assertEquals("BRK-B", IconLoader.key("brk-b"))
        assertNull(IconLoader.key(null)); assertNull(IconLoader.key("  "))
        assertNull(IconLoader.key("../etc/passwd")); assertNull(IconLoader.key("A".repeat(41)))
    }

    @Test fun urlEncodesSymbolAsOnePathSegment() {
        assertEquals("/api/icons/VOD.L.webp", IconLoader.urlFor("VOD.L").encodedPath)
        assertEquals("/api/icons/BRK-B.webp", IconLoader.urlFor("BRK-B").encodedPath)
    }

    @Test fun downloadsBytesFromThePublicEndpointWithoutAuth() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(webp)))
        assertArrayEquals(webp, loader.bytes("vod.l"))
        val req = server.takeRequest()
        assertEquals("/api/icons/VOD.L.webp", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun remembersNoLogoSoListsDoNotReAsk() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(loader.bytes("ZZZZ"))
        assertTrue(loader.isKnownMissing("zzzz"))
        assertNull(loader.bytes("ZZZZ"))
        assertEquals(1, server.requestCount)
    }

    @Test fun serverErrorsAreNotRememberedAsMissing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(Buffer().write(webp)))
        assertNull(loader.bytes("AAPL"))
        assertFalse(loader.isKnownMissing("AAPL"))
        assertArrayEquals(webp, loader.bytes("AAPL"))
    }

    @Test fun blankSymbolNeverHitsTheNetwork() = runBlocking {
        assertNull(loader.bytes(" "))
        assertEquals(0, server.requestCount)
    }

    @Test fun badgeTextAndHueAreStable() {
        assertEquals("VO", IconBadge.text("VOD.L")); assertEquals("BR", IconBadge.text("BRK-B"))
        assertEquals("?", IconBadge.text(null)); assertEquals("A", IconBadge.text("a"))
        assertEquals(IconBadge.hue("SAP.DE"), IconBadge.hue("sap.de"))
        assertTrue(IconBadge.hue("AAPL") in 0f..359f)
    }
}
