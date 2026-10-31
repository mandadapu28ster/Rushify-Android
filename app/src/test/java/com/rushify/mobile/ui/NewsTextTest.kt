package com.rushify.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsTextTest {
    @Test fun decodesEntitiesAndTags() {
        assertEquals("A & B <C> it's", NewsText.plain("A &amp; B &lt;C&gt; it&#39;s"))
        assertEquals("x y", NewsText.plain("<b>x</b>&nbsp;&nbsp;y"))
        assertEquals("é", NewsText.plain("&#233;"))
    }
    @Test fun hidesSummaryThatRepeatsTitle() {
        val t = "100 years of stock market history shows why picking winners is very difficult - AJ Bell"
        assertNull(NewsText.summary(t, "100 years of stock market history shows why picking winners is very difficult&nbsp;&nbsp;AJ Bell", "AJ Bell"))
        assertNull(NewsText.summary("Same headline", "Same headline&nbsp;&nbsp;", null))
        assertNull(NewsText.summary("Same headline", "Same headline - Reuters", "Reuters"))
    }
    @Test fun keepsRealSummary() {
        assertEquals("More detail here.", NewsText.summary("Headline", "More detail here.", "Reuters"))
        assertEquals("Headline and more context", NewsText.summary("Headline", "Headline and more context", "Reuters"))
        assertNull(NewsText.summary("Headline", "   ", null))
        assertNull(NewsText.summary("Headline", null, null))
    }
}
