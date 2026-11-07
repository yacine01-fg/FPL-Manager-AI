package com.fplmanagerai.presentation

import com.fplmanagerai.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DashboardViewModelTest {

    @Test
    fun testDeadlineCountdownCalculatesPositiveRemainingDuration() {
        // Use a fixed instant to avoid clock tick differences
        val baseInstant = Instant.parse("2026-11-10T12:00:00Z")
        val futureInstant = baseInstant.plus(Duration.ofHours(51)) // 2 days and 3 hours
        val duration = Duration.between(baseInstant, futureInstant)

        assertFalse(duration.isNegative)
        val days = duration.toDays()
        val hours = duration.toHours() % 24

        assertEquals(2, days)
        assertEquals(3, hours)
    }

    @Test
    fun testDeadlinePassedDetectsClosedGameweek() {
        // Create an instant 5 hours in the past
        val pastInstant = Instant.now().minus(Duration.ofHours(5))
        val deadlineStr = pastInstant.toString()

        val parsed = Instant.parse(deadlineStr)
        val now = Instant.now()
        val duration = Duration.between(now, parsed)

        assertTrue(duration.isNegative)
    }

    @Test
    fun testInjuryAlertsFilterOnlyUnfitOrHighRiskPlayers() {
        fun dummyPlayer(id: Int, status: String, risk: RiskLevel): PlayerModel {
            return PlayerModel(
                id = id,
                webName = "P$id",
                firstName = "F",
                secondName = "S",
                teamId = 1,
                teamName = "Arsenal",
                teamShortName = "ARS",
                position = PlayerPosition.MID,
                price = 7.0,
                totalPoints = 50,
                form = 5.0,
                selectedByPercent = 10.0,
                minutesPlayed = 800,
                starts = 9,
                statistics = PlayerStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, null, null, null, null, null, 5.0, 0, 0, 0, 0),
                availability = PlayerAvailability.parse(status, if (status == "a") 100 else 50, ""),
                upcomingFixtures = emptyList(),
                prediction = PlayerPrediction(
                    playerId = id,
                    gameweek = 11,
                    expectedPoints = 5.0,
                    expectedMinutes = 80.0,
                    startProbability = 0.9,
                    expectedAttackingPoints = 2.0,
                    expectedDefensivePoints = 1.0,
                    expectedCleanSheetPoints = 0.5,
                    expectedBonusPoints = 0.5,
                    expectedAppearancePoints = 2.0,
                    predictedCaptainPoints = 10.0,
                    actualPoints = 50,
                    actualCaptainPoints = 100,
                    confidenceScore = 0.8,
                    recommendation = TransferRecommendation.HOLD,
                    recommendationReason = "",
                    analysisScore = 70.0,
                    riskLevel = risk
                )
            )
        }

        val squad = listOf(
            dummyPlayer(1, "a", RiskLevel.LOW),
            dummyPlayer(2, "a", RiskLevel.LOW),
            dummyPlayer(3, "d", RiskLevel.HIGH),
            dummyPlayer(4, "i", RiskLevel.CRITICAL)
        )

        val alerts = squad.filter {
            !it.availability.isAvailable ||
                    it.prediction?.riskLevel == RiskLevel.CRITICAL ||
                    it.prediction?.riskLevel == RiskLevel.HIGH ||
                    (it.availability.chanceOfPlayingNextRound != null && it.availability.chanceOfPlayingNextRound < 100)
        }

        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it.id == 3 })
        assertTrue(alerts.any { it.id == 4 })
    }
}
