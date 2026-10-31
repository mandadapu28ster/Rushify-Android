package com.rushify.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun ok(s: String) = ServerUrl.parse(s) as ServerUrl.Result.Ok
    private fun bad(s: String) = (ServerUrl.parse(s) as ServerUrl.Result.Bad).reason

    @Test fun httpsDomainIsNotCleartext() {
        val r = ok("https://rushify.example.com/")
        assertFalse(r.isCleartext)
        assertEquals("https://rushify.example.com", r.url.toString().trimEnd('/'))
    }

    @Test fun publicIpWithPortIsCleartext() {
        val r = ok("  http://90.255.245.88:8040/ ")
        assertTrue(r.isCleartext)
        assertEquals(8040, r.url.port)
    }

    @Test fun privateIpAccepted() = assertTrue(ok("http://192.168.1.20:8040").isCleartext)

    @Test fun missingSchemeRejected() = assertTrue(bad("90.255.245.88:8040").contains("http"))
    @Test fun otherSchemeRejected() = assertTrue(bad("ftp://host").contains("http"))
    @Test fun emptyRejected() = assertTrue(bad("   ").isNotEmpty())
    @Test fun pathRejected() = assertTrue(bad("http://host:8040/api").contains("path"))
    @Test fun credentialsRejected() = assertTrue(bad("http://user:pw@host").contains("credentials"))
    @Test fun portZeroRejected() = assertTrue(bad("http://host:0").contains("Port"))
    @Test fun portTooLargeRejected() = assertTrue(bad("http://host:65536").contains("Port"))
    @Test fun nonNumericPortRejected() = assertTrue(bad("http://host:abc").contains("Port"))
    @Test fun maxPortAccepted() = assertEquals(65535, ok("http://host:65535").url.port)
}
