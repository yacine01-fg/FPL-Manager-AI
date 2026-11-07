package com.fplmanagerai.domain

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.analysis.ExpectedPointsCalculator
import com.fplmanagerai.domain.analysis.PlayerPerformanceAnalyzer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlayerPerformanceAnalyzerTest {

    private lateinit var analyzer: PlayerPerformanceAnalyzer
    private lateinit var calculator: ExpectedPointsCalculator

    @Before
    fun setUp() {
        analyzer = PlayerPerformanceAnalyzer()
        calculator = ExpectedPointsCalculator()
    }

    private fun createPlayer(
        status: String = "a",
        chance: Int? = 100,
        starts: Int = 10,
        minutes: Int = 900,
        form: Double = 6.0,
        goals: Int = 5,
        expectedGoals: Double? = 3.5,
        yellowCards: Int = 1,
        redCards: Int = 0
    ): PlayerModel {
        return PlayerModel(
            id = 1,
            webName = "Haaland",
            firstName = "Erling",
            secondName = "Haaland",
            teamId = 1,
            teamName = "Man City",
            teamShortName = "MCI",
            position = PlayerPosition.FWD,
            price = 14.0,
            totalPoints = 85,
            form = form,
            selectedByPercent = 60.0,
            minutesPlayed = minutes,
            starts = starts,
            statistics = PlayerStatistics(
                goals = goals,
                assists = 2,
                cleanSheets = 0,
                goalsConceded = 8,
                saves = 0,
                bonus = 10,
                bps = 250,
                expectedGoals = expectedGoals,
                expectedAssists = 1.2,
                expectedGoalInvolvements = (expectedGoals ?: 0.0) + 1.2,
                expectedGoalsConceded = 8.0,
                influence = 300.0,
                creativity = 120.0,
                threat = 450.0,
                ictIndex = 87.0,
                pointsPerGame = 8.5,
                transfersInRound = 5000,
                transfersOutRound = 1000,
                yellowCards = yellowCards,
                redCards = redCards
            ),
            availability = PlayerAvailability.parse(status, chance, ""),
            upcomingFixtures = listOf(
                UpcomingFixture(
                    gameweek = 11,
                    opponentId = 2,
                    opponentName = "Ipswich",
                    opponentShortName = "IPS",
                    isHome = true,
                    difficulty = 2
                )
            )
        )
    }

    @Test
    fun testFitRegularStarterHasHighReliabilityAndLowRisk() {
        val player = createPlayer(status = "a", chance = 100, starts = 10, minutes = 900)
        val metrics = analyzer.analyze(player, completedGameweeks = 10)

        assertTrue("Regular starter must have high reliability score (>= 80)", metrics.reliabilityScore >= 80.0)
        assertEquals("Regular starter with easy fixture should have LOW risk", RiskLevel.LOW, metrics.riskLevel)
        assertNotNull(metrics.calculationDocumentationAr)
    }

    @Test
    fun testInjuredPlayerHasCriticalRiskAndZeroHealthScore() {
        val injured = createPlayer(status = "i", chance = 0, starts = 0, minutes = 0)
        val metrics = analyzer.analyze(injured, completedGameweeks = 10)

        assertEquals("Injured player must have CRITICAL risk level", RiskLevel.CRITICAL, metrics.riskLevel)
        assertTrue("Injured player reliability score must be low (< 35)", metrics.reliabilityScore < 35.0)

        val prediction = calculator.calculateExpectedPoints(injured, currentGameweek = 11)
        assertEquals("Injured player recommendation must be SELL", TransferRecommendation.SELL, prediction.recommendation)
        assertEquals(RiskLevel.CRITICAL, prediction.riskLevel)
    }

    @Test
    fun testFinishingEfficiencyVarianceCalculation() {
        // Player with 5 goals and 3.5 xG -> +1.5 overperformance
        val clinicalPlayer = createPlayer(goals = 5, expectedGoals = 3.5)
        val metrics = analyzer.analyze(clinicalPlayer, completedGameweeks = 10)

        assertEquals(1.5, metrics.finishingEfficiency!!, 0.01)

        // Player with missing xG data (null) should not have 0.0, should remain null
        val noXgPlayer = createPlayer(goals = 3, expectedGoals = null)
        val metricsNoXg = analyzer.analyze(noXgPlayer, completedGameweeks = 10)

        assertNull("Missing xG must produce null finishing variance, not 0.0", metricsNoXg.finishingEfficiency)
    }

    @Test
    fun testDisciplinaryDeductionLowersReliability() {
        val cleanPlayer = createPlayer(yellowCards = 0, redCards = 0)
        val aggressivePlayer = createPlayer(yellowCards = 5, redCards = 1)

        val cleanMetrics = analyzer.analyze(cleanPlayer, completedGameweeks = 10)
        val aggMetrics = analyzer.analyze(aggressivePlayer, completedGameweeks = 10)

        assertTrue(
            "Aggressive player with red/yellow cards must have lower reliability than clean player",
            aggMetrics.reliabilityScore < cleanMetrics.reliabilityScore
        )
    }
}
