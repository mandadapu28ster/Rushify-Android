package com.rushify.mobile.ui

import com.rushify.mobile.data.HoldingRow
import com.rushify.mobile.data.RollupAccount
import com.rushify.mobile.data.RollupLine
import com.rushify.mobile.data.Rollups
import java.math.BigDecimal

/*
 * Pure holdings logic, ported from the web app's HoldingsPage so both show the same groups, matches and totals.
 * Nothing here touches Android, so it is fully unit-tested on the JVM.
 */

enum class ExposureFilter(val label: String, val hint: String) {
    All("All", "Default list (still respects \"Show sold positions\" for manual lots)."),
    OpenFull("Open (no sells)", "Broker line with quantity and no sell fills in stored history."),
    OpenPartial("Open (reduced)", "Still holding shares but at least one sell fill is stored."),
    Closed("Closed (history)", "Exited in the broker app: no open line. Closed instruments appear inside each broker account below."),
}

enum class GroupKind { T212, SNAPTRADE, CSV, MONZO, MANUAL_LISTED, MANUAL_CASH, MANUAL_PROPERTY, MANUAL_OTHER, OTHER }

object HoldingsLogic {
    /** Same order as the backend's venue map; longer `_WBAG_EQ` before `_WB_EQ`. */
    private val T212_SUFFIXES = listOf(
        "_PA_EQ", "_FR_EQ", "_US_EQ", "_GB_EQ", "_L_EQ", "_DE_EQ", "_AS_EQ", "_ST_EQ", "_MI_EQ", "_MC_EQ",
        "_SW_EQ", "_BR_EQ", "_TO_EQ", "_AU_EQ", "_HK_EQ", "_JP_EQ", "_VI_EQ", "_WBAG_EQ", "_WB_EQ",
    )

    /** `INFY_US_EQ` -> `INFY` (best effort, no network). */
    fun stripT212VenueSuffix(raw: String?): String {
        val t = (raw ?: "").trim()
        if (t.isEmpty()) return t
        val u = t.uppercase()
        for (suf in T212_SUFFIXES) if (u.endsWith(suf)) return t.dropLast(suf.length)
        if (u.contains("_US_EQ")) { val cut = u.indexOf('_'); if (cut > 0) return t.substring(0, cut) }
        if (u.contains("_EQ") && u.contains("_")) { val cut = t.indexOf('_'); if (cut > 0) return t.substring(0, cut) }
        return t
    }

    fun rollupDisplaySymbol(r: RollupLine): String {
        var s = (r.displayTicker ?: r.yahooTicker ?: "").trim()
        if (s.isEmpty()) return ""
        if (s.uppercase().contains("_EQ")) stripT212VenueSuffix(s).trim().takeIf { it.isNotEmpty() }?.let { s = it }
        return s
    }

    /** Ticker shown on a row: the rollup's display symbol when linked, else the raw ticker without the `_EQ` venue suffix. */
    fun displayTicker(r: HoldingRow, rollup: RollupLine?): String {
        if (rollup != null) rollupDisplaySymbol(rollup).takeIf { it.isNotEmpty() }?.let { return it }
        val raw = r.ticker.trim()
        if (raw.isEmpty()) return raw
        if ((r.source == "t212" || r.source == "snaptrade") && raw.uppercase().contains("_EQ")) {
            stripT212VenueSuffix(raw).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return raw
    }

    fun isListedAssetKind(kind: String?): Boolean {
        val k = (kind ?: "").trim().lowercase()
        return k.isEmpty() || k == "listed" || k == "equity"
    }

    private val BROKER_SOURCES = setOf("t212", "snaptrade", "csv")

    fun isBrokerCash(r: HoldingRow) = (r.source in BROKER_SOURCES || r.source == "monzo") && r.assetKind == "cash"

    /** Listed equity from a broker (excludes synthetic CASH.* rows). */
    fun isBrokerListedEquityRow(r: HoldingRow) =
        r.source in BROKER_SOURCES && isListedAssetKind(r.assetKind) && !r.ticker.startsWith("CASH.")

    /** A manual listed lot that is fully sold: a sold date, or no quantity left. */
    fun isManualListedSold(r: HoldingRow) =
        r.source == "manual" && isListedAssetKind(r.assetKind) && (r.soldOutAt != null || (r.quantity != null && r.quantity.signum() <= 0))

    fun normalizeCsvRef(ref: String) = ref.trim().lowercase().replace("trading-212", "trading212")

    fun csvAccountRefFromRow(r: HoldingRow): String {
        val ref = (r.brokerAccountRef ?: "").trim().lowercase()
        if (ref.isNotEmpty()) return normalizeCsvRef(ref)
        val ak = (r.accountKind ?: "invest").trim().lowercase().ifEmpty { "invest" }
        val slug = (r.brokerLabel ?: "broker").substringBefore("·").trim().lowercase().replace(Regex("\\s+"), "-")
        return normalizeCsvRef("csv:$slug:$ak")
    }

    fun rowTickerMatchesRollup(rowTicker: String, rollup: RollupLine): Boolean {
        fun normClass(s: String) = stripT212VenueSuffix(s).trim().uppercase().replace('-', '.')
        val rowU = rowTicker.trim().uppercase()
        val disp = rollupDisplaySymbol(rollup)
        val dispU = disp.uppercase()
        val broU = (rollup.brokerTicker ?: "").trim().uppercase()
        val yhU = (rollup.yahooTicker ?: "").trim().uppercase()
        if (rowU == dispU || rowU == broU) return true
        if (rowU.isNotEmpty() && yhU.isNotEmpty() && rowU == yhU) return true
        val rowS = stripT212VenueSuffix(rowTicker).trim().uppercase()
        val dispS = stripT212VenueSuffix(disp).trim().uppercase()
        val broS = stripT212VenueSuffix(rollup.brokerTicker).trim().uppercase()
        val yhS = stripT212VenueSuffix(rollup.yahooTicker).trim().uppercase()
        if (rowS.isNotEmpty() && (rowS == dispS || rowS == broS || (yhS.isNotEmpty() && rowS == yhS))) return true
        val rn = normClass(rowTicker)
        return rn.isNotEmpty() && (rn == normClass(disp) || rn == normClass(rollup.brokerTicker ?: "") || rn == normClass(rollup.yahooTicker ?: ""))
    }

    fun rollupMatchesListedRow(rollup: RollupLine, r: HoldingRow): Boolean {
        if (r.source == "manual" && isListedAssetKind(r.assetKind)) {
            if (rollup.source != "manual") return false
            val id = r.manualHoldingId ?: return false
            return rollup.accountRef.trim().lowercase() == "manual:$id"
        }
        if (!isBrokerListedEquityRow(r)) return false
        if (!rowTickerMatchesRollup(r.ticker, rollup)) return false
        if (rollup.source == "csv") return normalizeCsvRef(csvAccountRefFromRow(r)) == normalizeCsvRef(rollup.accountRef)
        if (rollup.source == "t212") {
            val ak = (r.accountKind ?: "invest").lowercase().ifEmpty { "invest" }
            return rollup.accountRef.trim().lowercase() == "t212:$ak"
        }
        val aid = (r.snaptradeAccountId ?: "").trim()
        return aid.isNotEmpty() && rollup.accountRef.trim().lowercase() == "snaptrade:${aid.lowercase()}"
    }

    /** Lower is better when several rollups match one dashboard ticker. */
    fun rollupRowMatchRank(r: HoldingRow, rollup: RollupLine): Int {
        val rowT = r.ticker.trim()
        val rowU = rowT.uppercase()
        val rowS = stripT212VenueSuffix(rowT).trim().uppercase()
        val broU = (rollup.brokerTicker ?: "").trim().uppercase()
        val disp = rollupDisplaySymbol(rollup).trim()
        val dispU = disp.uppercase()
        val broS = stripT212VenueSuffix(rollup.brokerTicker).trim().uppercase()
        val dispS = stripT212VenueSuffix(disp).trim().uppercase()
        return when {
            rowU.isNotEmpty() && rowU == broU -> 0
            rowU.isNotEmpty() && rowU == dispU -> 1
            rowS.isNotEmpty() && broS.isNotEmpty() && rowS == broS -> 2
            rowS.isNotEmpty() && dispS.isNotEmpty() && rowS == dispS -> 3
            else -> 50
        }
    }

    fun rollupMatchesExposure(rollup: RollupLine, exposure: ExposureFilter) = when (exposure) {
        ExposureFilter.All -> true
        ExposureFilter.Closed -> rollup.exposure == "closed_history"
        ExposureFilter.OpenFull -> rollup.exposure == "open_full"
        ExposureFilter.OpenPartial -> rollup.exposure == "open_partial"
    }

    fun findRollupForRow(r: HoldingRow, lines: List<RollupLine>, exposure: ExposureFilter = ExposureFilter.All): RollupLine? {
        val candidates = lines.filter { rollupMatchesListedRow(it, r) && rollupMatchesExposure(it, exposure) }
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.sortedWith(
            compareBy<RollupLine> { rollupRowMatchRank(r, it) }
                .thenByDescending { it.fillRowCount }
                .thenBy { it.brokerTicker ?: "" },
        ).first()
    }

    fun rollupIdentityKey(ru: RollupLine?): String =
        if (ru == null) "" else "${ru.source}|${ru.accountRef.trim().lowercase()}|${(ru.brokerTicker ?: "").trim().uppercase()}"

    fun rowSlotId(r: HoldingRow): String =
        if (r.manualHoldingId != null) "mh:${r.manualHoldingId}"
        else "${r.source}:${r.ticker.trim()}:${(r.snaptradeAccountId ?: "").trim()}:${(r.accountKind ?: "").trim()}:${(r.currency ?: "").trim()}"

    /** Which rows survive the exposure filter. Broker cash rows are hidden in every mode except All. */
    fun applyExposureFilter(rows: List<HoldingRow>, lines: List<RollupLine>, exposure: ExposureFilter, showSold: Boolean): List<HoldingRow> {
        if (exposure == ExposureFilter.All) return rows
        val base = rows.filterNot { isBrokerCash(it) }
        if (exposure == ExposureFilter.Closed) {
            return base.filter { r ->
                when {
                    isBrokerListedEquityRow(r) -> false
                    r.source == "manual" && isListedAssetKind(r.assetKind) && !isManualListedSold(r) -> false
                    isManualListedSold(r) -> showSold
                    else -> true
                }
            }
        }
        return base.filter { r -> !isBrokerListedEquityRow(r) || findRollupForRow(r, lines, exposure) != null }
    }

    /** One card per account x display symbol; the one with more fills wins. */
    fun dedupeClosedHistory(closed: List<RollupLine>): List<RollupLine> {
        val byKey = LinkedHashMap<String, RollupLine>()
        for (r in closed) {
            val k = "${r.source}|${r.accountRef.trim().lowercase()}|${rollupDisplaySymbol(r).trim().uppercase()}"
            val prev = byKey[k]
            if (prev == null || r.fillRowCount > prev.fillRowCount) byKey[k] = r
        }
        return byKey.values.sortedBy { rollupDisplaySymbol(it) }
    }

    fun rollupExposureLabel(exposure: String) = when (exposure) {
        "open_full" -> "Open (synced)"
        "open_partial" -> "Open (reduced)"
        "closed_history" -> "Closed (history)"
        else -> exposure
    }

    // ---------- labels ----------

    fun snaptradeLabelsFromRows(rows: List<HoldingRow>): Map<String, String> {
        val m = HashMap<String, String>()
        for (r in rows) {
            if (r.source != "snaptrade") continue
            val id = (r.snaptradeAccountId ?: "").trim()
            val label = (r.brokerLabel ?: "").trim()
            if (id.isEmpty() || label.isEmpty()) continue
            val prev = m[id]
            if (prev == null || label.length > prev.length) m[id] = label
        }
        return m
    }

    private val KIND_SHORT = mapOf("invest" to "Invest", "isa" to "ISA", "cash_isa" to "Cash ISA", "sipp" to "SIPP", "lisa" to "LISA", "jisa" to "JISA", "jsipp" to "JSIPP")

    fun formatRollupAccountLabel(ref: String, snaptradeLabels: Map<String, String> = emptyMap()): String {
        if (ref.startsWith("t212:")) return "Trading 212 · ${ref.removePrefix("t212:").uppercase()}"
        if (ref.startsWith("csv:")) {
            val tail = ref.removePrefix("csv:")
            val colon = tail.indexOf(':')
            val slug = if (colon >= 0) tail.substring(0, colon) else tail
            val kind = if (colon >= 0) tail.substring(colon + 1) else "invest"
            val name = if (slug == "trading212") "Trading 212" else slug.split("-").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            return "$name · ${KIND_SHORT[kind] ?: kind.uppercase()} (CSV)"
        }
        if (ref.startsWith("snaptrade:")) {
            val id = ref.removePrefix("snaptrade:")
            snaptradeLabels[id]?.let { return "$it · SnapTrade" }
            val short = if (id.length > 14) "${id.take(8)}…${id.takeLast(4)}" else id
            return "SnapTrade · $short"
        }
        return ref
    }

    // ---------- manual grouping (same keys/titles/badges as the web) ----------

    private fun normPart(s: String?) = (s ?: "").trim().lowercase().ifEmpty { "unspecified" }

    fun manualGroupKey(r: HoldingRow): String {
        val kind = (r.assetKind ?: "listed").trim().lowercase()
        if (kind == "listed" || r.assetKind == null) return "listed__" + (r.brokerLabel ?: r.manualBrokerName ?: "Manual entry").trim().lowercase()
        if (kind == "cash") {
            val bank = normPart(r.manualBrokerName?.takeIf { it.isNotBlank() } ?: (r.brokerLabel ?: "").substringBefore("·"))
            val inst = (r.instrumentName ?: "").trim()
            val parts = inst.split(Regex("\\s*·\\s*"))
            val acct = if (parts.size >= 2) parts.drop(1).joinToString(" · ").trim() else inst.ifEmpty { r.instrumentName ?: "cash" }
            return "cash__${bank}__${acct.lowercase()}"
        }
        if (kind == "real_estate") return "property__" + (r.manualBrokerName ?: r.brokerLabel ?: "Manual entry").trim().lowercase()
        return "other__" + (r.manualBrokerName ?: r.brokerLabel ?: "Manual entry").trim().lowercase()
    }

    fun manualGroupTitle(key: String, head: HoldingRow): String = when {
        key.startsWith("listed__") -> (head.brokerLabel ?: head.manualBrokerName ?: "Manual entry").trim()
        key.startsWith("cash__") -> (head.instrumentName ?: "Cash savings").trim()
        key.startsWith("property__") -> (head.manualBrokerName ?: "Manual entry").trim().let { if (it == "Manual entry") "Property (manual)" else "$it · Property" }
        else -> (head.manualBrokerName ?: "Manual entry").trim().let { if (it == "Manual entry") "Other assets (manual)" else "$it · Other" }
    }

    fun manualKind(key: String) = when {
        key.startsWith("listed__") -> GroupKind.MANUAL_LISTED
        key.startsWith("cash__") -> GroupKind.MANUAL_CASH
        key.startsWith("property__") -> GroupKind.MANUAL_PROPERTY
        else -> GroupKind.MANUAL_OTHER
    }

    fun manualBadge(kind: GroupKind) = when (kind) {
        GroupKind.MANUAL_LISTED -> "Manual listed"
        GroupKind.MANUAL_CASH -> "Cash savings"
        GroupKind.MANUAL_PROPERTY -> "Property"
        else -> "Other assets"
    }

    private fun manualOrder(kind: GroupKind) = when (kind) { GroupKind.MANUAL_LISTED -> 0; GroupKind.MANUAL_PROPERTY -> 1; GroupKind.MANUAL_CASH -> 2; else -> 3 }

    // ---------- Monzo ----------

    /** 0 = Accounts, 1 = Savings & ISAs, 2 = Pots. */
    fun monzoSection(r: HoldingRow): Int = when ((r.accountKind ?: "").trim().lowercase()) { "isa", "savings" -> 1; "pot" -> 2; else -> 0 }
    val MONZO_SECTION_TITLES = listOf("Accounts", "Savings & ISAs", "Pots")

    fun monzoKindBadge(kind: String?): String? = when ((kind ?: "").trim().lowercase()) {
        "isa" -> "ISA"; "savings" -> "Savings"; "joint" -> "Joint"; "young" -> "Young"; "rewards" -> "Rewards"; "pot" -> "Pot"; else -> null
    }

    fun monzoSections(rows: List<HoldingRow>): List<Pair<String, List<HoldingRow>>> =
        MONZO_SECTION_TITLES.mapIndexedNotNull { i, title ->
            val inSection = rows.filter { monzoSection(it) == i }.sortedByDescending { it.marketValueBase ?: BigDecimal.ZERO }
            if (inSection.isEmpty()) null else title to inSection
        }

    /** Monzo row name without the "Monzo ·" prefix. */
    fun monzoRowName(r: HoldingRow): String = (r.instrumentName ?: r.ticker).replace(Regex("^Monzo\\s*·\\s*"), "")
}

data class HoldingGroup(
    val key: String,
    val name: String,
    val rows: List<HoldingRow>,
    val kind: GroupKind = GroupKind.OTHER,
    val badge: String? = null,
    val accountRef: String? = null,
    val account: RollupAccount? = null,
    val closed: List<RollupLine> = emptyList(),
) {
    private val isCash = { r: HoldingRow -> r.assetKind == "cash" }
    val brokerLike get() = kind == GroupKind.T212 || kind == GroupKind.SNAPTRADE || kind == GroupKind.CSV
    val listedRows get() = rows.filter { HoldingsLogic.isListedAssetKind(it.assetKind) }
    val positionCount get() = rows.count { !isCash(it) }
    val cashValue: BigDecimal get() = rows.filter(isCash).fold(BigDecimal.ZERO) { a, r -> a + (r.marketValueBase ?: BigDecimal.ZERO) }
    val totalValue: BigDecimal get() = rows.fold(BigDecimal.ZERO) { a, r -> a + (r.marketValueBase ?: BigDecimal.ZERO) }
    val totalQty: BigDecimal get() = listedRows.fold(BigDecimal.ZERO) { a, r -> a + (r.quantity ?: BigDecimal.ZERO) }
    /** Sum of listed-row P/L; zero (not a dash) when there is none, like the web. */
    val totalPnl: BigDecimal? get() = listedRows.mapNotNull { it.pnlStockBase }.fold(BigDecimal.ZERO, BigDecimal::add)
    val hasCash get() = rows.any(isCash)
}

/** What the list renders inside a group: a single line, or several lines of the same ticker under one summary. */
sealed interface HoldingItem {
    data class Single(val row: HoldingRow) : HoldingItem
    data class Multi(val ticker: String, val rows: List<HoldingRow>) : HoldingItem
}

enum class HoldingSort(val label: String) { Value("Value"), Pnl("P/L"), Name("Name") }

object HoldingGroups {
    private fun matchesQuery(q: String, vararg fields: String?) = q.isEmpty() || fields.any { it != null && it.contains(q, ignoreCase = true) }

    private fun rowKey(r: HoldingRow): Pair<String, GroupKind> = when (r.source) {
        "t212" -> "t212:" + (r.accountKind ?: "invest").lowercase().ifEmpty { "invest" } to GroupKind.T212
        "snaptrade" -> "st:" + ((r.snaptradeAccountId ?: "").ifEmpty { r.brokerLabel ?: "" }) to GroupKind.SNAPTRADE
        "csv" -> "csv:" + HoldingsLogic.csvAccountRefFromRow(r) to GroupKind.CSV
        "monzo" -> "monzo" to GroupKind.MONZO
        "manual" -> { val k = HoldingsLogic.manualGroupKey(r); k to HoldingsLogic.manualKind(k) }
        else -> "other:${r.source}:${r.brokerLabel}" to GroupKind.OTHER
    }

    private fun accountRefFor(kind: GroupKind, head: HoldingRow): String? = when (kind) {
        GroupKind.T212 -> "t212:" + (head.accountKind ?: "invest").lowercase().ifEmpty { "invest" }
        GroupKind.SNAPTRADE -> head.snaptradeAccountId?.takeIf { it.isNotBlank() }?.let { "snaptrade:${it.trim().lowercase()}" }
        GroupKind.CSV -> HoldingsLogic.csvAccountRefFromRow(head)
        else -> null
    }

    private fun title(kind: GroupKind, key: String, head: HoldingRow): String = when (kind) {
        GroupKind.SNAPTRADE -> (head.brokerLabel ?: "SnapTrade") + " · SnapTrade"
        GroupKind.MONZO -> "Monzo"
        GroupKind.MANUAL_LISTED, GroupKind.MANUAL_CASH, GroupKind.MANUAL_PROPERTY, GroupKind.MANUAL_OTHER -> HoldingsLogic.manualGroupTitle(key, head)
        else -> head.brokerLabel?.takeIf { it.isNotBlank() } ?: head.source.replaceFirstChar { it.uppercase() }
    }

    private fun orderOf(g: HoldingGroup): Int = when (g.kind) {
        GroupKind.T212 -> 0; GroupKind.SNAPTRADE -> 1; GroupKind.CSV -> 2; GroupKind.MONZO -> 3
        GroupKind.MANUAL_LISTED -> 4; GroupKind.MANUAL_PROPERTY -> 5; GroupKind.MANUAL_CASH -> 6; GroupKind.MANUAL_OTHER -> 7; GroupKind.OTHER -> 8
    }

    private fun t212Rank(g: HoldingGroup) = when (g.accountRef?.removePrefix("t212:")) { "invest" -> 0; "isa" -> 1; "sipp" -> 2; else -> 3 }

    fun sortRows(rows: List<HoldingRow>, sort: HoldingSort): List<HoldingRow> = when (sort) {
        HoldingSort.Value -> rows.sortedByDescending { it.marketValueBase ?: BigDecimal.ZERO }
        HoldingSort.Pnl -> rows.sortedByDescending { it.pnlStockBase ?: BigDecimal.ZERO }
        HoldingSort.Name -> rows.sortedBy { (it.instrumentName ?: it.ticker).lowercase() }
    }

    /**
     * Groups dashboard rows like the web Holdings page: one block per broker account, then Monzo, then manual
     * groups. Search and sort are mobile extras applied inside each block.
     */
    fun build(
        rows: List<HoldingRow>,
        query: String = "",
        sort: HoldingSort = HoldingSort.Value,
        rollups: Rollups = Rollups(),
        exposure: ExposureFilter = ExposureFilter.All,
        showSold: Boolean = false,
    ): List<HoldingGroup> {
        val q = query.trim()
        val lines = rollups.lines
        val accounts = rollups.accounts.associateBy { it.accountRef.trim().lowercase() }
        val stLabels = HoldingsLogic.snaptradeLabelsFromRows(rows)

        val visible = HoldingsLogic.applyExposureFilter(
            rows.filter { !HoldingsLogic.isManualListedSold(it) || showSold }, lines, exposure, showSold,
        ).filter { matchesQuery(q, it.ticker, it.instrumentName, it.sector, it.brokerLabel) }

        val groups = LinkedHashMap<String, MutableList<HoldingRow>>()
        val kinds = HashMap<String, GroupKind>()
        for (r in visible) { val (k, kind) = rowKey(r); groups.getOrPut(k) { mutableListOf() }.add(r); kinds[k] = kind }

        // In Closed mode the open rows are gone, so the "already shown on an open line" suppression must see none of them.
        val bucketRowsForClosedFilter = rows.filter { exposure != ExposureFilter.Closed }
        val closedAll = lines.filter { it.exposure == "closed_history" && it.source in setOf("t212", "snaptrade", "csv") }
        val showClosed = exposure == ExposureFilter.All || exposure == ExposureFilter.Closed

        val result = mutableListOf<HoldingGroup>()
        val usedRefs = mutableSetOf<String>()
        for ((k, list) in groups) {
            val kind = kinds.getValue(k)
            val head = list.first()
            val ref = accountRefFor(kind, head)
            ref?.let { usedRefs += it.lowercase() }
            val closed = if (showClosed && ref != null) closedFor(ref, closedAll, bucketRowsForClosedFilter, q, stLabels) else emptyList()
            val titleText = title(kind, k, head)
            result += HoldingGroup(
                key = k, name = titleText, rows = sortRows(list, sort), kind = kind,
                badge = when (kind) { GroupKind.MONZO -> "Bank (synced)"; GroupKind.MANUAL_LISTED, GroupKind.MANUAL_CASH, GroupKind.MANUAL_PROPERTY, GroupKind.MANUAL_OTHER -> HoldingsLogic.manualBadge(kind); else -> null },
                accountRef = ref, account = ref?.let { accounts[it.lowercase()] }, closed = closed,
            )
        }
        // Accounts that only have closed history still get a block.
        if (showClosed) {
            val closedRefs = closedAll.map { it.accountRef.trim().lowercase() }.distinct().filter { it !in usedRefs }
            for (ref in closedRefs) {
                val closed = closedFor(ref, closedAll, bucketRowsForClosedFilter, q, stLabels)
                if (closed.isEmpty()) continue
                val kind = when { ref.startsWith("t212:") -> GroupKind.T212; ref.startsWith("snaptrade:") -> GroupKind.SNAPTRADE; else -> GroupKind.CSV }
                if (q.isNotEmpty() && result.isNotEmpty() && closed.isEmpty()) continue
                result += HoldingGroup(
                    key = "closed:$ref", name = HoldingsLogic.formatRollupAccountLabel(ref, stLabels), rows = emptyList(), kind = kind,
                    accountRef = ref, account = accounts[ref], closed = closed,
                )
            }
        }
        return result.sortedWith(
            compareBy<HoldingGroup> { orderOf(it) }
                .thenBy { if (it.kind == GroupKind.T212) t212Rank(it) else 0 }
                .thenBy { if (it.kind == GroupKind.T212 || it.kind == GroupKind.MONZO) "" else it.name.lowercase() },
        )
    }

    private fun closedFor(ref: String, closedAll: List<RollupLine>, bucketRows: List<HoldingRow>, q: String, stLabels: Map<String, String>): List<RollupLine> {
        val mine = closedAll.filter { it.accountRef.trim().lowercase() == ref.trim().lowercase() }
        val notOnListedRows = mine.filter { ru -> bucketRows.none { r -> HoldingsLogic.isListedAssetKind(r.assetKind) && HoldingsLogic.rollupMatchesListedRow(ru, r) } }
        return HoldingsLogic.dedupeClosedHistory(notOnListedRows)
            .filter { matchesQuery(q, HoldingsLogic.rollupDisplaySymbol(it), it.instrumentName, it.brokerTicker) }
    }

    /** Several lines with the same ticker collapse under one summary; cash, Monzo and non-listed manual rows stay single. */
    fun items(group: HoldingGroup, rollups: Rollups, exposure: ExposureFilter): List<HoldingItem> {
        if (group.kind == GroupKind.MONZO || group.kind == GroupKind.MANUAL_CASH || group.kind == GroupKind.MANUAL_PROPERTY || group.kind == GroupKind.MANUAL_OTHER) {
            return group.rows.map { HoldingItem.Single(it) }
        }
        val byTicker = LinkedHashMap<String, MutableList<HoldingRow>>()
        val order = mutableListOf<Any>()
        group.rows.forEach { r ->
            if (r.assetKind == "cash" || !HoldingsLogic.isListedAssetKind(r.assetKind)) { order += HoldingItem.Single(r); return@forEach }
            val t = HoldingsLogic.displayTicker(r, HoldingsLogic.findRollupForRow(r, rollups.lines, exposure)).uppercase()
            if (!byTicker.containsKey(t)) order += t
            byTicker.getOrPut(t) { mutableListOf() }.add(r)
        }
        return order.map { o ->
            when (o) {
                is HoldingItem -> o
                else -> { val rs = byTicker.getValue(o as String); if (rs.size == 1) HoldingItem.Single(rs[0]) else HoldingItem.Multi(o, rs) }
            }
        }
    }

    /** First row (in display order) per rollup identity shows fees and dividends once, so a split position is not double counted. */
    fun primaryMetricRows(displayOrder: List<HoldingRow>, lines: List<RollupLine>, exposure: ExposureFilter): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (r in displayOrder) {
            val k = HoldingsLogic.rollupIdentityKey(HoldingsLogic.findRollupForRow(r, lines, exposure))
            if (k.isNotEmpty() && !m.containsKey(k)) m[k] = HoldingsLogic.rowSlotId(r)
        }
        return m
    }

    fun displayOrder(items: List<HoldingItem>): List<HoldingRow> = items.flatMap { when (it) { is HoldingItem.Single -> listOf(it.row); is HoldingItem.Multi -> it.rows } }
}

/** One line of the "Broker accounts" summary. */
data class AccountSummary(val ref: String, val label: String, val source: String, val account: RollupAccount?, val monzoBalances: Int = 0, val monzoTotal: BigDecimal? = null)

data class AccountTotals(
    val investments: BigDecimal, val realised: BigDecimal, val unrealised: BigDecimal, val tax: BigDecimal,
    val fxFees: BigDecimal, val brokerCost: BigDecimal, val interest: BigDecimal, val dividends: BigDecimal, val payout: BigDecimal,
)

object AccountSummaries {
    private val SOURCE_ORDER = listOf("csv", "monzo", "snaptrade", "t212")

    /**
     * Rollup accounts ordered csv, monzo, snaptrade, t212 like the web table. The server already sends one Monzo
     * account (`monzo:all`); only when it does not do we build a line from the dashboard's Monzo rows.
     */
    fun build(rollups: Rollups, rows: List<HoldingRow>): List<AccountSummary> {
        val st = HoldingsLogic.snaptradeLabelsFromRows(rows)
        val monzoRows = rows.filter { it.source == "monzo" }
        val list = rollups.accounts.map { a ->
            if (a.source == "monzo") {
                AccountSummary(a.accountRef, a.displayLabel?.takeIf { it.isNotBlank() } ?: "Monzo · ${monzoRows.size} balances", "monzo", a, monzoRows.size,
                    a.listedInvestmentsMvBaseSum ?: monzoRows.fold(BigDecimal.ZERO) { acc, r -> acc + (r.marketValueBase ?: BigDecimal.ZERO) })
            } else AccountSummary(a.accountRef, a.displayLabel?.takeIf { it.isNotBlank() } ?: HoldingsLogic.formatRollupAccountLabel(a.accountRef, st), a.source, a)
        }.toMutableList()
        if (monzoRows.isNotEmpty() && list.none { it.source == "monzo" }) {
            list += AccountSummary("monzo", "Monzo · ${monzoRows.size} balance${if (monzoRows.size == 1) "" else "s"}", "monzo", null, monzoRows.size,
                monzoRows.fold(BigDecimal.ZERO) { a, r -> a + (r.marketValueBase ?: BigDecimal.ZERO) })
        }
        return list.sortedBy { SOURCE_ORDER.indexOf(it.source).let { i -> if (i < 0) 99 else i } }
    }

    /** Totals over every non-Monzo account; null when there is none (the web hides the total row then). */
    fun totals(summaries: List<AccountSummary>): AccountTotals? {
        val accs = summaries.filter { it.source != "monzo" }.mapNotNull { it.account }
        if (accs.isEmpty()) return null
        fun sum(f: (RollupAccount) -> BigDecimal?) = accs.fold(BigDecimal.ZERO) { a, x -> a + (f(x) ?: BigDecimal.ZERO) }
        return AccountTotals(
            sum { it.listedInvestmentsMvBaseSum }, sum { it.realisedGainBaseSum }, sum { it.unrealisedPnlBaseSum }, sum { it.fillTaxBaseSum },
            sum { it.fillFxFeesBaseSum }, sum { it.fillBrokerFeesBaseSum }, sum { it.interestOnCashBaseSum }, sum { it.dividendPayoutBaseSum },
            sum { it.combinedBrokerPayoutBaseSum },
        )
    }
}
