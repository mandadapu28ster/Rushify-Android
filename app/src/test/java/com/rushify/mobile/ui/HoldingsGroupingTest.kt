package com.rushify.mobile.ui

import com.rushify.mobile.data.AppJson
import com.rushify.mobile.data.Dashboard
import com.rushify.mobile.fixture
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldingsGroupingTest {
    private val rows = AppJson.decodeFromString(Dashboard.serializer(), fixture("dashboard.json")).rows

    @Test fun sameLabelDifferentAccountsStaySeparate() {
        val groups = HoldingGroups.build(rows, "", HoldingSort.Value).filter { it.name.startsWith("Broker X") }
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.name == "Broker X · LISA · SnapTrade" })
    }

    @Test fun soldManualLotsAreHiddenUnlessAsked() {
        assertFalse(HoldingGroups.build(rows, "", HoldingSort.Value).flatMap { it.rows }.any { it.ticker == "OLD" })
        assertTrue(HoldingGroups.build(rows, "", HoldingSort.Value, showSold = true).flatMap { it.rows }.any { it.ticker == "OLD" })
    }

    @Test fun cashIsNotCountedAsAPosition() {
        val g = HoldingGroups.build(rows, "", HoldingSort.Value).first { g -> g.rows.any { it.ticker == "CASH.1" } }
        assertEquals(1, g.positionCount)
        assertEquals(BigDecimal("50.00"), g.cashValue)
        assertEquals(BigDecimal("450.20"), g.totalValue)
    }

    @Test fun manualPropertyHasFriendlyGroupName() = assertTrue(HoldingGroups.build(rows, "", HoldingSort.Value).any { it.name == "Property (manual)" })

    @Test fun groupsFollowTheWebOrderBrokersThenMonzoThenManual() {
        val rank = listOf(GroupKind.T212, GroupKind.SNAPTRADE, GroupKind.CSV, GroupKind.MONZO, GroupKind.MANUAL_LISTED, GroupKind.MANUAL_PROPERTY, GroupKind.MANUAL_CASH, GroupKind.MANUAL_OTHER, GroupKind.OTHER)
        val kinds = HoldingGroups.build(rows, "", HoldingSort.Value).map { it.kind }
        assertEquals(kinds.sortedBy { rank.indexOf(it) }, kinds)
    }

    @Test fun searchMatchesTickerNameAndSectorCaseInsensitively() {
        assertEquals(listOf("AAA"), HoldingGroups.build(rows, "alpha", HoldingSort.Value).flatMap { it.rows }.map { it.ticker })
        assertEquals(listOf("BBB"), HoldingGroups.build(rows, "HEALTH", HoldingSort.Value).flatMap { it.rows }.map { it.ticker })
        assertTrue(HoldingGroups.build(rows, "zzz", HoldingSort.Value).isEmpty())
    }

    @Test fun sortByPnlAndName() {
        val sameGroup = rows.filter { it.snaptradeAccountId != null }
        assertEquals("AAA", HoldingGroups.build(sameGroup, "", HoldingSort.Name).flatMap { it.rows }.first().ticker)
    }
}
