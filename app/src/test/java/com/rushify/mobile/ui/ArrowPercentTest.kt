package com.rushify.mobile.ui

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowPercentTest {
    @Test fun gainsGetAnUpArrowAndNoPlusSign() { assertEquals("▲ 19.32%", Fmt.arrowPercent(BigDecimal("19.3182"))) }
    @Test fun lossesGetADownArrowAndNoMinusSign() { assertEquals("▼ 5.00%", Fmt.arrowPercent(BigDecimal("-5"))) }
    @Test fun zeroHasNoArrow() { assertEquals("0.00%", Fmt.arrowPercent(BigDecimal.ZERO)) }
    @Test fun missingIsADash() { assertEquals("—", Fmt.arrowPercent(null)) }
    @Test fun scaleIsRespected() { assertEquals("▲ 1.2%", Fmt.arrowPercent(BigDecimal("1.234"), 1)) }
}
