package com.rushify.mobile.ui

import com.rushify.mobile.data.WatchItem
import com.rushify.mobile.data.Watchlist
import com.rushify.mobile.data.Watchlists
import java.math.BigDecimal
import java.math.RoundingMode

/** Display rules for the watchlist metrics, ported from the web (utils/watchlistFormat.ts) so both screens read the same. */
object WatchlistFormat {
    const val NM = "NM"
    private val HUNDRED = BigDecimal(100)

    private fun fixed(v: BigDecimal, dp: Int) = v.setScale(dp, RoundingMode.HALF_UP).toPlainString()

    /** Ratios: null "—"; zero, negative: "NM" (not meaningful); 100 or more with no decimals, else 2 decimals. */
    fun valuationRatio(v: BigDecimal?): String = when {
        v == null -> Fmt.DASH
        v.signum() <= 0 -> NM
        v >= HUNDRED -> fixed(v, 0)
        else -> fixed(v, 2)
    }

    /** ROE arrives as a percent number already (23.52). Negative is "NM". */
    fun roe(v: BigDecimal?): String = when { v == null -> Fmt.DASH; v.signum() < 0 -> NM; else -> fixed(v, 2) + "%" }

    fun compactMoney(v: BigDecimal?): String {
        if (v == null) return Fmt.DASH
        val a = v.abs(); val sign = if (v.signum() < 0) "-" else ""
        val t = BigDecimal("1000000000000"); val b = BigDecimal("1000000000"); val m = BigDecimal("1000000"); val k = BigDecimal("1000")
        return when {
            a >= t -> sign + fixed(a.divide(t, 6, RoundingMode.HALF_UP), 2) + "T"
            a >= b -> sign + fixed(a.divide(b, 6, RoundingMode.HALF_UP), 2) + "B"
            a >= m -> sign + fixed(a.divide(m, 6, RoundingMode.HALF_UP), 2) + "M"
            a >= k -> sign + fixed(a.divide(k, 6, RoundingMode.HALF_UP), 1) + "K"
            else -> sign + fixed(a, 0)
        }
    }

    fun netCashLine(v: BigDecimal?): String? = when {
        v == null -> null
        v.signum() > 0 -> "Net cash ${compactMoney(v)}"
        v.signum() < 0 -> "Net debt ${compactMoney(v.abs())}"
        else -> "Net cash neutral"
    }

    /** "ind 61.4x (2)": the median among watchlist peers in the same industry; only with 2+ peers and a positive median. */
    fun industryHint(median: BigDecimal?, peers: Int, suffix: String = "x"): String? {
        if (peers < 2 || median == null || median.signum() <= 0) return null
        val label = if (median >= HUNDRED) fixed(median, 0) else fixed(median, 1)
        return "ind $label$suffix ($peers)"
    }

    fun isIndexLike(quoteType: String?) = (quoteType ?: "").uppercase().let { it == "ETF" || it == "MUTUALFUND" }

    /** "12.60 / 9.80 fwd" style: trailing P/E, forward only when it is shown and differs. */
    fun peText(item: WatchItem): String {
        val trail = valuationRatio(item.peRatio); val fwd = valuationRatio(item.forwardPeRatio)
        return if (fwd != Fmt.DASH && fwd != trail) "$trail / $fwd fwd" else trail
    }

    /** Dividend yield: a value of 1 or less is a fraction (0.0459) and is shown x100; larger values are already percent. */
    fun dividendPct(v: BigDecimal?): String {
        if (v == null) return Fmt.DASH
        val pct = if (v.abs() <= BigDecimal.ONE) v.multiply(HUNDRED) else v
        return fixed(pct, 2) + "%"
    }

    fun percentPlain(v: BigDecimal?): String = if (v == null) Fmt.DASH else fixed(v, 2) + "%"

    fun price(v: BigDecimal?, ccy: String?): String = if (v == null) Fmt.DASH else Fmt.number(v, 2) + (ccy?.let { " $it" } ?: "")

    fun range52(low: BigDecimal?, high: BigDecimal?): String = if (low == null && high == null) Fmt.DASH else "${Fmt.number(low, 0)}–${Fmt.number(high, 0)}"
}

/** Limits and rules for creating lists and adding symbols, as the server enforces them (and the web mirrors them). */
object WatchlistRules {
    const val NAME_MAX = 64

    fun canCreate(w: Watchlists?, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > NAME_MAX) return false
        return w == null || w.limitsUnlimited || w.watchlists.size < w.maxWatchlists
    }
    fun atListCap(w: Watchlists?) = w != null && !w.limitsUnlimited && w.watchlists.size >= w.maxWatchlists
    fun atItemCap(w: Watchlists?, list: Watchlist) = w != null && !w.limitsUnlimited && list.items.size >= w.maxItemsPerWatchlist
    fun newsCount(list: Watchlist) = list.items.count { it.fetchNews }
    fun hasAnyItems(w: Watchlists?) = w?.watchlists?.any { it.items.isNotEmpty() } == true
    /** Search starts at 2 characters. */
    fun searchable(q: String) = q.trim().length >= 2
}
