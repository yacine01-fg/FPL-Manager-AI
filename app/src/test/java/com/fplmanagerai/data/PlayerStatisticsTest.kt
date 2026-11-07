package com.fplmanagerai.data

import com.fplmanagerai.data.model.PlayerStatistics
import org.junit.Assert.*
import org.junit.Test

class PlayerStatisticsTest {

    private fun createStats(xg: Double?, xa: Double?): PlayerStatistics {
        return PlayerStatistics(
            goals = 2,
            assists = 1,
            cleanSheets = 3,
            goalsConceded = 4,
            saves = 0,
            bonus = 2,
            bps = 45,
            expectedGoals = xg,
            expectedAssists = xa,
            expectedGoalInvolvements = if (xg != null && xa != null) xg + xa else null,
            expectedGoalsConceded = null,
            influence = null,
            creativity = null,
            threat = null,
            ictIndex = null,
            pointsPerGame = 4.5,
            transfersInRound = 100,
            transfersOutRound = 50,
            yellowCards = 1,
            redCards = 0
        )
    }

    @Test
    fun testCompleteMetrics_bothPresent() {
        val stats = createStats(xg = 0.45, xa = 0.30)
        assertTrue(stats.hasAnyExpectedMetrics)
        assertTrue(stats.hasCompleteExpectedMetrics)
        @Suppress("DEPRECATION")
        assertTrue(stats.hasExpectedMetrics)
    }

    @Test
    fun testPartialMetrics_onlyGoalsPresent() {
        val stats = createStats(xg = 0.45, xa = null)
        assertTrue(stats.hasAnyExpectedMetrics)
        assertFalse(stats.hasCompleteExpectedMetrics)
        @Suppress("DEPRECATION")
        assertFalse(stats.hasExpectedMetrics)
    }

    @Test
    fun testPartialMetrics_onlyAssistsPresent() {
        val stats = createStats(xg = null, xa = 0.25)
        assertTrue(stats.hasAnyExpectedMetrics)
        assertFalse(stats.hasCompleteExpectedMetrics)
        @Suppress("DEPRECATION")
        assertFalse(stats.hasExpectedMetrics)
    }

    @Test
    fun testNoMetrics_bothNull() {
        val stats = createStats(xg = null, xa = null)
        assertFalse(stats.hasAnyExpectedMetrics)
        assertFalse(stats.hasCompleteExpectedMetrics)
        @Suppress("DEPRECATION")
        assertFalse(stats.hasExpectedMetrics)
    }
}
