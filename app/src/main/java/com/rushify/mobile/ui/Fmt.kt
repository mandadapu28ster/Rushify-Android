package com.rushify.mobile.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure formatting helpers. All money stays BigDecimal; nothing here goes through Double. */
object Fmt {
    const val HIDDEN = "••••"
    const val DASH = "—"
    private val symbols = DecimalFormatSymbols(Locale.UK)

    private fun group(v: BigDecimal, scale: Int): String {
        val pattern = if (scale == 0) "#,##0" else "#,##0." + "0".repeat(scale)
        return DecimalFormat(pattern, symbols).format(v.setScale(scale, RoundingMode.HALF_UP))
    }

    fun number(v: BigDecimal?, scale: Int = 2): String = v?.let { group(it, scale) } ?: DASH

    /** Trims trailing zeros and rounds to at most 4 decimals like the web (0.43181800 -> 0.4318, 114.519041 -> 114.519). */
    fun quantity(v: BigDecimal?): String {
        if (v == null) return DASH
        val s = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val scale = s.scale().coerceIn(0, 4)
        return group(s, scale)
    }

    fun money(v: BigDecimal?, ccy: String?, hide: Boolean = false, scale: Int = 2): String {
        if (v == null) return DASH
        if (hide) return HIDDEN
        val body = group(v.abs(), scale)
        val neg = if (v.signum() < 0) "-" else ""
        return when (ccy) {
            "GBP", null -> "$neg£$body"
            "USD" -> "$neg$$body"
            "EUR" -> "$neg€$body"
            else -> "$neg$body $ccy"
        }
    }

    fun signedMoney(v: BigDecimal?, ccy: String?, hide: Boolean = false): String {
        if (v == null) return DASH
        if (hide) return HIDDEN
        val plus = if (v.signum() > 0) "+" else ""
        return plus + money(v, ccy)
    }

    /** A percentage that is a gain or a loss, with an arrow so the direction never relies on colour alone: "▲ 19.32%", "▼ 5.00%". */
    fun arrowPercent(v: BigDecimal?, scale: Int = 2): String {
        if (v == null) return DASH
        val abs = percent(v.abs(), scale)
        return when (v.signum()) { 1 -> "▲ $abs"; -1 -> "▼ $abs"; else -> abs }
    }

    /** For values already expressed as percent (e.g. weight_pct = 44.41). */
    fun percent(v: BigDecimal?, scale: Int = 2, signed: Boolean = false): String {
        if (v == null) return DASH
        val plus = if (signed && v.signum() > 0) "+" else ""
        return plus + group(v, scale) + "%"
    }

    /** For fractions (e.g. XIRR 0.1079 -> 10.79%). */
    fun percentFromFraction(v: BigDecimal?, scale: Int = 2, signed: Boolean = false): String =
        percent(v?.multiply(BigDecimal(100)), scale, signed)

    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
    fun stamp(i: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        i?.let { stamp.format(it.atZone(zone)) } ?: DASH

    fun stamp(iso: String?, zone: ZoneId = ZoneId.systemDefault()) = stamp(parseInstant(iso), zone)
    fun stampMillis(ms: Long, zone: ZoneId = ZoneId.systemDefault()) = stamp(Instant.ofEpochMilli(ms), zone)

    /** Revenue / market cap style: 1.23T, 4.5B, 12.3M. */
    fun abbreviate(v: BigDecimal?): String {
        if (v == null) return DASH
        val a = v.abs()
        val (div, suffix) = when {
            a >= BigDecimal("1000000000000") -> BigDecimal("1000000000000") to "T"
            a >= BigDecimal("1000000000") -> BigDecimal("1000000000") to "B"
            a >= BigDecimal("1000000") -> BigDecimal("1000000") to "M"
            else -> return group(v, 0)
        }
        return group(v.divide(div, 4, RoundingMode.HALF_UP), 2) + suffix
    }

    /** Axis labels: 1.2k, 3.4M, 950. */
    fun compact(v: Double): String {
        val a = kotlin.math.abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            a >= 1_000_000 -> sign + trim(a / 1_000_000) + "M"
            a >= 1_000 -> sign + trim(a / 1_000) + "k"
            else -> sign + trim(a)
        }
    }
    private fun trim(x: Double) = if (x >= 100 || x == Math.floor(x)) x.toLong().toString() else String.format(Locale.UK, "%.1f", x).removeSuffix(".0")

    /** "Group name, 3 positions" style helper. */
    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}

fun BigDecimal?.signColorIsGain(): Boolean? = this?.let { if (it.signum() > 0) true else if (it.signum() < 0) false else null }
