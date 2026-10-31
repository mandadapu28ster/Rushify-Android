package com.rushify.mobile.ui

import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.ManualHoldingOut
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Outcome of validating a form: the request body to send, or the message the web shows. */
sealed interface Built {
    data class Ok(val body: JsonObject) : Built
    data class Err(val message: String) : Built
}

enum class ManualKind(val label: String, val api: String) {
    Listed("Stock / ETF", "listed"), Property("Home / property", "real_estate"), Cash("Cash savings", "cash"), Other("Other asset", "other");
    companion object { fun of(api: String?) = entries.firstOrNull { it.api == api } }
}

/** Rules ported from the web manual-holdings forms and the backend schemas. Pure, so it is unit tested on the JVM. */
object ManualRules {
    val LISTED_CREATE_KINDS = listOf("sipp" to "SIPP", "isa" to "ISA", "invest" to "GIA / Invest", "lisa" to "LISA", "jisa" to "JISA", "jsipp" to "JSIPP")
    /** Edit also allows "unspecified"; the backend rejects "cash_isa" on update, so it is not offered. */
    val LISTED_EDIT_KINDS = LISTED_CREATE_KINDS + ("unspecified" to "Unspecified")
    val CASH_TYPES = listOf(
        "Current account", "Easy-access savings", "Savings account", "Cash ISA", "Lifetime ISA (cash)", "Junior ISA (cash)",
        "Stocks & Shares ISA", "Fixed-term savings", "Premium Bonds", "Other",
    )
    const val CASH_OTHER = "Other"
    const val CASH_DEFAULT = "Savings account"
    val SECTORS = listOf(
        "ETFs & funds", "Technology", "Healthcare", "Financial Services", "Financials", "Consumer Cyclical", "Consumer Defensive",
        "Communication Services", "Industrials", "Energy", "Utilities", "Real Estate", "Basic Materials", "Consumer Staples", "Materials",
    )

    const val MSG_LISTED_MISSING = "Fill in ticker, qty, broker, account type, purchase date, and total cost in GBP."
    const val MSG_LISTED_DATE = "Purchase date must be between 30 years ago and today (no future dates)."
    const val MSG_EDIT_BASIS = "Purchase date and total GBP cost are required."
    const val MSG_BROKER = "Broker is required."

    /** Drops currency symbols, spaces and thousands separators the way a user types money. */
    fun sanitizeDecimal(s: String) = s.trim().replace(",", "").replace("£", "").replace(" ", "")

    fun parseDec(s: String?): BigDecimal? {
        val t = sanitizeDecimal(s ?: return null)
        if (t.isEmpty()) return null
        return t.toBigDecimalOrNull()
    }

    fun parseDate(s: String?): LocalDate? = try {
        val t = s?.trim().orEmpty()
        if (t.length == 10) LocalDate.parse(t) else null
    } catch (_: Exception) { null }

    private fun str(d: BigDecimal) = d.stripTrailingZeros().toPlainString()
    private fun nn(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObjectBuilderPut(b: kotlinx.serialization.json.JsonObjectBuilder, k: String, v: String?) { b.put(k, if (v == null) JsonNull else JsonPrimitive(v)) }

    /** Quantity text accepted for listed lots (> 0). */
    private fun positive(s: String?): BigDecimal? = parseDec(s)?.takeIf { it.signum() > 0 }

    fun listedCreate(symbol: String, label: String, qty: String, broker: String, kind: String, purchaseDate: String, cost: String, today: LocalDate): Built {
        val q = positive(qty); val c = parseDec(cost); val sym = symbol.trim(); val br = broker.trim()
        if (sym.isEmpty() || q == null || br.isEmpty() || kind.isBlank() || purchaseDate.isBlank() || c == null) return Built.Err(MSG_LISTED_MISSING)
        val d = parseDate(purchaseDate)
        if (d == null || d.isAfter(today) || d.isBefore(today.minusYears(30))) return Built.Err(MSG_LISTED_DATE)
        if (c.signum() < 0) return Built.Err(MSG_LISTED_MISSING)
        return Built.Ok(buildJsonObject {
            put("asset_kind", "listed"); put("symbol", sym); JsonObjectBuilderPut(this, "label", nn(label))
            put("quantity", str(q)); put("broker_name", br); put("purchase_date", d.toString()); put("cost_base_gbp", str(c)); put("account_kind", kind)
        })
    }

    fun listedEdit(label: String, qty: String, broker: String, kind: String, purchaseDate: String, cost: String, today: LocalDate): Built {
        val q = positive(qty) ?: return Built.Err("quantity must be greater than zero")
        if (purchaseDate.isBlank() || parseDec(cost) == null) return Built.Err(MSG_EDIT_BASIS)
        if (broker.isBlank()) return Built.Err(MSG_BROKER)
        val d = parseDate(purchaseDate)
        if (d == null || d.isAfter(today) || d.isBefore(today.minusYears(30))) return Built.Err(MSG_LISTED_DATE)
        val c = parseDec(cost)!!
        return Built.Ok(buildJsonObject {
            JsonObjectBuilderPut(this, "label", nn(label)); put("quantity", str(q)); put("broker_name", broker.trim())
            put("purchase_date", d.toString()); put("cost_base_gbp", str(c))
            if (kind == "unspecified" || kind.isBlank()) put("account_kind", JsonNull) else put("account_kind", kind)
        })
    }

    /** Sale of part or all of a manual listed lot. Price is GBP per share (from the contract note). */
    fun sell(held: BigDecimal?, purchaseDate: String?, qty: String, executedAt: String, price: String, today: LocalDate): Built {
        if (held == null || held.signum() <= 0) return Built.Err("Nothing left to sell — this position is already closed")
        if ((purchaseDate?.length ?: 0) < 8) return Built.Err("Purchase date required before recording a sale. Open Edit lot and set the purchase date first.")
        if (qty.isBlank()) return Built.Err("Quantity sold is required.")
        val q = positive(qty) ?: return Built.Err("Enter a valid quantity sold (greater than zero).")
        if (q > held) return Built.Err("Sold quantity cannot exceed current holding (${str(held)}).")
        if (price.isBlank()) return Built.Err("GBP price per share is required.")
        val p = positive(price) ?: return Built.Err("Enter the GBP price per share (must be greater than zero).")
        if (executedAt.isBlank()) return Built.Err("Sell date is required.")
        val d = parseDate(executedAt) ?: return Built.Err("Sell date must be a date like 2025-01-31.")
        if (d.isAfter(today)) return Built.Err("Sell date cannot be in the future.")
        val bought = parseDate(purchaseDate)
        if (bought != null && d.isBefore(bought)) return Built.Err("Sell date cannot be before the lot purchase date")
        return Built.Ok(buildJsonObject { put("quantity_sold", str(q)); put("executed_at", d.toString()); put("price_per_share_gbp", str(p)) })
    }

    data class NonListed(
        val kind: ManualKind,
        val currency: String = "GBP",
        val notes: String = "",
        val label: String = "",
        val broker: String = "",
        val cashType: String = CASH_DEFAULT,
        val cashTypeOther: String = "",
        val homeValue: String = "",
        val mortgage: String = "",
        val cashBalance: String = "",
        val otherValue: String = "",
        val openedDate: String = "",
        val openingValue: String = "",
        val exitDate: String = "",
        val exitValue: String = "",
    )

    private fun cashLabel(f: NonListed) = if (f.cashType == CASH_OTHER) f.cashTypeOther.trim() else f.cashType

    fun nonListedCreate(f: NonListed, today: LocalDate): Built {
        val kind = f.kind
        require(kind != ManualKind.Listed)
        when (kind) {
            ManualKind.Property -> if (f.label.isBlank() || parseDec(f.homeValue) == null || parseDec(f.mortgage) == null) return Built.Err("Fill in all required fields for this entry type.")
            ManualKind.Cash -> {
                if (f.broker.isBlank()) return Built.Err("Enter the bank or building society name.")
                if (cashLabel(f).isEmpty()) return Built.Err("Choose an account type, or enter a description if you selected Other.")
                if (parseDec(f.cashBalance) == null) return Built.Err("Fill in all required fields for this entry type.")
            }
            else -> if (f.label.isBlank() || parseDec(f.otherValue) == null) return Built.Err("Fill in all required fields for this entry type.")
        }
        if (f.openedDate.isBlank() || f.openingValue.isBlank()) return Built.Err("Fill in all required fields for this entry type.")
        val opened = parseDate(f.openedDate) ?: return Built.Err("Fill in all required fields for this entry type.")
        if (opened.isAfter(today)) return Built.Err("Opened date cannot be in the future.")
        val opening = parseDec(f.openingValue)?.takeIf { it.signum() >= 0 } ?: return Built.Err("Enter a valid non-negative opening amount in GBP.")
        val hasExitDate = f.exitDate.isNotBlank(); val hasExitValue = f.exitValue.isNotBlank()
        if (hasExitDate != hasExitValue) return Built.Err("Exit date and exit amount (GBP) must both be set or both left blank.")
        var exit: LocalDate? = null; var exitValue: BigDecimal? = null
        if (hasExitDate) {
            exit = parseDate(f.exitDate) ?: return Built.Err("Exit date and exit amount (GBP) must both be set or both left blank.")
            if (exit.isAfter(today)) return Built.Err("Exit date cannot be in the future.")
            if (exit.isBefore(opened)) return Built.Err("Exit date must be on or after the opened date.")
            exitValue = parseDec(f.exitValue)?.takeIf { it.signum() >= 0 } ?: return Built.Err("Enter a valid non-negative exit amount in GBP.")
        }
        return Built.Ok(buildJsonObject {
            put("asset_kind", kind.api); put("currency", f.currency.trim().ifEmpty { "GBP" }.uppercase()); JsonObjectBuilderPut(this, "notes", nn(f.notes))
            put("opened_date", opened.toString()); put("opening_value_gbp", str(opening))
            if (exit != null && exitValue != null) { put("exit_date", exit.toString()); put("exit_value_gbp", str(exitValue)) }
            when (kind) {
                ManualKind.Property -> { put("label", f.label.trim()); put("home_value", str(parseDec(f.homeValue)!!)); put("mortgage_balance", str(parseDec(f.mortgage)!!)) }
                ManualKind.Cash -> { put("broker_name", f.broker.trim()); put("label", cashLabel(f)); put("cash_balance", str(parseDec(f.cashBalance)!!)) }
                else -> { put("label", f.label.trim()); put("other_value", str(parseDec(f.otherValue)!!)) }
            }
        })
    }

    /** "update" records a new value as of a date; "exit" closes the line. Already-exited lines only change notes/currency/chart flag. */
    data class NonListedEdit(
        val form: NonListed,
        val exited: Boolean,
        val showOnChart: Boolean,
        val mode: String = "update",
        val updateDate: String = "",
    )

    fun nonListedEdit(e: NonListedEdit, today: LocalDate): Built {
        val f = e.form
        val common = { b: kotlinx.serialization.json.JsonObjectBuilder ->
            b.put("currency", f.currency.trim().ifEmpty { "GBP" }.uppercase()); JsonObjectBuilderPut(b, "notes", nn(f.notes)); b.put("show_on_wealth_chart", e.showOnChart)
        }
        if (e.exited) return Built.Ok(buildJsonObject { common(this); put("save_mode", "update") })
        when (f.kind) {
            ManualKind.Cash -> {
                if (f.broker.isBlank()) return Built.Err("Enter the bank or building society name.")
                if (cashLabel(f).isEmpty()) return Built.Err("Choose an account type, or enter a description if you selected Other.")
                if (parseDec(f.cashBalance) == null) return Built.Err("Fill in all required fields for this entry type.")
            }
            ManualKind.Property -> if (parseDec(f.homeValue)?.takeIf { it.signum() >= 0 } == null || (f.mortgage.isNotBlank() && parseDec(f.mortgage)?.takeIf { it.signum() >= 0 } == null)) return Built.Err("Enter valid non-negative home and mortgage values.")
            else -> if (parseDec(f.otherValue) == null) return Built.Err("Fill in all required fields for this entry type.")
        }
        if (f.openedDate.isBlank() || f.openingValue.isBlank()) return Built.Err("Opened date and opening amount (GBP) are required.")
        val opened = parseDate(f.openedDate) ?: return Built.Err("Opened date and opening amount (GBP) are required.")
        if (opened.isAfter(today)) return Built.Err("Opened date cannot be in the future.")
        val opening = parseDec(f.openingValue)?.takeIf { it.signum() >= 0 } ?: return Built.Err("Enter a valid non-negative opening amount in GBP.")
        var updateDate: LocalDate? = null
        var exit: LocalDate? = null; var exitValue: BigDecimal? = null
        if (e.mode == "exit") {
            if (f.exitDate.isBlank() || f.exitValue.isBlank()) return Built.Err("exit_date and exit_value_gbp are required when updating property, cash savings, and other manual assets")
            exit = parseDate(f.exitDate) ?: return Built.Err("exit_date and exit_value_gbp must both be set or both omitted")
            if (exit.isAfter(today)) return Built.Err("Exit date cannot be in the future.")
            if (exit.isBefore(opened)) return Built.Err("Exit date must be on or after the opened date.")
            exitValue = parseDec(f.exitValue)?.takeIf { it.signum() >= 0 } ?: return Built.Err("Enter a valid non-negative exit amount in GBP.")
        } else if (e.updateDate.isNotBlank()) {
            updateDate = parseDate(e.updateDate) ?: return Built.Err("Update date must be a date like 2025-01-31.")
            if (updateDate.isAfter(today)) return Built.Err("update_date cannot be in the future")
            if (updateDate.isBefore(opened)) return Built.Err("update_date must be on or after opened_date")
        }
        return Built.Ok(buildJsonObject {
            common(this)
            when (f.kind) {
                ManualKind.Cash -> { put("broker_name", f.broker.trim()); put("label", cashLabel(f)); put("cash_balance", str(parseDec(f.cashBalance)!!)) }
                ManualKind.Property -> {
                    JsonObjectBuilderPut(this, "label", nn(f.label)); JsonObjectBuilderPut(this, "broker_name", nn(f.broker))
                    put("home_value", str(parseDec(f.homeValue)!!)); put("mortgage_balance", str(parseDec(f.mortgage) ?: BigDecimal.ZERO))
                }
                else -> { JsonObjectBuilderPut(this, "label", nn(f.label)); JsonObjectBuilderPut(this, "broker_name", nn(f.broker)); put("other_value", str(parseDec(f.otherValue)!!)) }
            }
            put("opened_date", opened.toString()); put("opening_value_gbp", str(opening)); put("save_mode", e.mode)
            if (updateDate != null) put("update_date", updateDate.toString())
            if (exit != null && exitValue != null) { put("exit_date", exit.toString()); put("exit_value_gbp", str(exitValue)) }
        })
    }

    /** Pre-fills the edit form from what the server stored. */
    fun formFrom(m: ManualHoldingOut): NonListed {
        val kind = ManualKind.of(m.assetKind) ?: ManualKind.Other
        val type = m.label.orEmpty()
        val known = CASH_TYPES.firstOrNull { it.equals(type, true) }
        fun d(v: BigDecimal?) = v?.stripTrailingZeros()?.toPlainString().orEmpty()
        return NonListed(
            kind = kind, currency = m.currency ?: "GBP", notes = m.notes.orEmpty(), label = if (kind == ManualKind.Cash) "" else type, broker = m.brokerName.orEmpty(),
            cashType = if (kind == ManualKind.Cash) (known ?: if (type.isBlank()) CASH_DEFAULT else CASH_OTHER) else CASH_DEFAULT,
            cashTypeOther = if (kind == ManualKind.Cash && known == null && type.isNotBlank()) type else "",
            homeValue = d(m.homeValue), mortgage = d(m.mortgageBalance), cashBalance = d(m.cashBalance), otherValue = d(m.otherValue),
            openedDate = m.openedDate.orEmpty(), openingValue = d(m.openingValueGbp), exitDate = m.exitDate.orEmpty(), exitValue = d(m.exitValueGbp),
        )
    }

    fun isNonListedManual(r: HoldingRow) = r.source == "manual" && r.manualHoldingId != null && !HoldingsLogic.isListedAssetKind(r.assetKind)
    fun isListedManual(r: HoldingRow) = r.source == "manual" && r.manualHoldingId != null && HoldingsLogic.isListedAssetKind(r.assetKind)

    /** Web: only the first lot of a listed ticker offers the sector editor, keyed by the display ticker without the T212 "_EQ" suffix. */
    fun sectorTicker(r: HoldingRow, rollup: com.rushify.mobile.data.RollupLine?) = HoldingsLogic.displayTicker(r, rollup).removeSuffix("_EQ")

    fun sectorBody(ticker: String, sector: String): Built {
        val t = ticker.trim(); val s = sector.trim()
        if (t.isEmpty()) return Built.Err("Invalid ticker")
        if (s.isEmpty()) return Built.Err("Sector is required")
        return Built.Ok(buildJsonObject { put("ticker", t); put("sector", s) })
    }
}
