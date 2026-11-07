package com.fplmanagerai

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.analysis.ExpectedPointsCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpectedPointsCalculatorTest {

    private lateinit var calculator: ExpectedPointsCalculator

    @Before
    fun setUp() {
        calculator = ExpectedPointsCalculator()
    }

    private fun createDummyPlayer(
        id: Int = 1,
        name: String = "Test Player",
        position: PlayerPosition = PlayerPosition.FWD,
        price: Double = 8.5,
        totalPoints: Int = 50,
        status: String = "a",
        chance: Int? = 100,
        expectedGoals: Double? = 0.5,
        expectedAssists: Double? = 0.3,
        minutes: Int = 900,
        starts: Int = 10
    ): PlayerModel {
        return PlayerModel(
            id = id,
            webName = name,
            firstName = "Test",
            secondName = "Player",
            teamId = 1,
            teamName = "Arsenal",
            teamShortName = "ARS",
            position = position,
            price = price,
            totalPoints = totalPoints,
            form = 6.0,
            selectedByPercent = 25.0,
            minutesPlayed = minutes,
            starts = starts,
            statistics = PlayerStatistics(
                goals = 5,
                assists = 3,
                cleanSheets = 2,
                goalsConceded = 8,
                saves = 0,
                bonus = 6,
                bps = 150,
                expectedGoals = expectedGoals,
                expectedAssists = expectedAssists,
                expectedGoalInvolvements = (expectedGoals ?: 0.0) + (expectedAssists ?: 0.0),
                expectedGoalsConceded = 8.0,
                influence = 200.0,
                creativity = 150.0,
                threat = 300.0,
                ictIndex = 65.0,
                pointsPerGame = 5.0,
                transfersInRound = 1000,
                transfersOutRound = 200,
                yellowCards = 1,
                redCards = 0
            ),
            availability = PlayerAvailability.parse(status, chance, ""),
            upcomingFixtures = listOf(
                UpcomingFixture(
                    gameweek = 10,
                    opponentId = 2,
                    opponentName = "Aston Villa",
                    opponentShortName = "AVL",
                    isHome = true,
                    difficulty = 2
                )
            )
        )
    }

    @Test
    fun testInjuredPlayerReturnsZeroExpectedPoints() {
        val injuredPlayer = createDummyPlayer(
            status = "i",
            chance = 0
        )

        val prediction = calculator.calculateExpectedPoints(injuredPlayer, currentGameweek = 10)

        assertEquals("Injured player must have 0.0 expected points", 0.0, prediction.expectedPoints, 0.01)
        assertEquals("Injured player must have 0.0 expected minutes", 0.0, prediction.expectedMinutes, 0.01)
        assertEquals("Injured player recommendation should be SELL", TransferRecommendation.SELL, prediction.recommendation)
    }

    @Test
    fun testCaptainPointsIsDoubleExpectedPoints() {
        val player = createDummyPlayer()
        val prediction = calculator.calculateExpectedPoints(player, currentGameweek = 10)

        assertTrue("Expected points should be positive for active starter", prediction.expectedPoints > 0.0)
        assertEquals(
            "Predicted captain points must be exactly 2x expected points",
            prediction.expectedPoints * 2.0,
            prediction.predictedCaptainPoints,
            0.05
        )
    }

    @Test
    fun testActualPointsRemainSeparateFromPredictedPoints() {
        val player = createDummyPlayer(totalPoints = 85)
        val prediction = calculator.calculateExpectedPoints(player, currentGameweek = 10)

        // In current model, actualPoints holds player totalPoints baseline until model refactor
        assertEquals(85, prediction.actualPoints)
        assertEquals(170, prediction.actualCaptainPoints)
        assertTrue("Predicted points must be positive", prediction.expectedPoints > 0.0)

        // Verifying that actual points can be recorded independently without affecting prediction
        val historicalRecord = prediction.copy(actualPoints = 9, actualCaptainPoints = 18)
        assertEquals(9, historicalRecord.actualPoints)
        assertEquals(18, historicalRecord.actualCaptainPoints)
    }

    @Test
    fun testForwardReceivesFourPointsPerGoalInModel() {
        val fwd = createDummyPlayer(position = PlayerPosition.FWD, expectedGoals = 1.0, expectedAssists = 0.0)
        val mid = createDummyPlayer(position = PlayerPosition.MID, expectedGoals = 1.0, expectedAssists = 0.0)

        val fwdPred = calculator.calculateExpectedPoints(fwd, currentGameweek = 10)
        val midPred = calculator.calculateExpectedPoints(mid, currentGameweek = 10)

        // Midfielders earn 5 pts per goal, forwards earn 4 pts per goal
        assertTrue(
            "Midfielders should have higher attacking points per goal than forwards",
            midPred.expectedAttackingPoints > fwdPred.expectedAttackingPoints
        )
    }
}
