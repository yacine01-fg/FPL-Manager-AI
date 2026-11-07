package com.fplmanagerai.domain

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.analysis.PredictionEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PredictionEngineTest {

    private lateinit var engine: PredictionEngine

    @Before
    fun setUp() {
        engine = PredictionEngine()
    }

    private fun createPlayerWithFixtures(
        pos: PlayerPosition,
        xg: Double? = 0.5,
        xa: Double? = 0.3,
        saves: Int = 0,
        fixtures: List<UpcomingFixture> = listOf(
            UpcomingFixture(11, 2, "Ipswich", "IPS", isHome = true, difficulty = 2),
            UpcomingFixture(12, 3, "Everton", "EVE", isHome = false, difficulty = 3),
            UpcomingFixture(13, 4, "Liverpool", "LIV", isHome = false, difficulty = 5)
        )
    ): PlayerModel {
        return PlayerModel(
            id = 10,
            webName = "TestStar",
            firstName = "Test",
            secondName = "Star",
            teamId = 1,
            teamName = "Arsenal",
            teamShortName = "ARS",
            position = pos,
            price = 9.5,
            totalPoints = 70,
            form = 6.5,
            selectedByPercent = 25.0,
            minutesPlayed = 900,
            starts = 10,
            statistics = PlayerStatistics(
                goals = 4,
                assists = 3,
                cleanSheets = 4,
                goalsConceded = 7,
                saves = saves,
                bonus = 6,
                bps = 200,
                expectedGoals = xg,
                expectedAssists = xa,
                expectedGoalInvolvements = (xg ?: 0.0) + (xa ?: 0.0),
                expectedGoalsConceded = 7.0,
                influence = 250.0,
                creativity = 200.0,
                threat = 350.0,
                ictIndex = 80.0,
                pointsPerGame = 7.0,
                transfersInRound = 1500,
                transfersOutRound = 300,
                yellowCards = 1,
                redCards = 0
            ),
            availability = PlayerAvailability.parse("a", 100, ""),
            upcomingFixtures = fixtures
        )
    }

    @Test
    fun testFloorCeilingAndUncertaintyBounds() {
        val forward = createPlayerWithFixtures(pos = PlayerPosition.FWD)
        val prediction = engine.generatePrediction(forward, currentGameweek = 11)

        assertTrue(
            "Floor points (${prediction.floorPoints}) must be <= Expected points (${prediction.expectedPoints})",
            prediction.floorPoints <= prediction.expectedPoints
        )
        assertTrue(
            "Expected points (${prediction.expectedPoints}) must be <= Ceiling points (${prediction.ceilingPoints})",
            prediction.expectedPoints <= prediction.ceilingPoints
        )
        assertTrue(
            "Uncertainty margin must be positive",
            prediction.uncertaintyMargin > 0.0
        )
    }

    @Test
    fun testPredictedAndActualCaptainPointsAreSeparated() {
        val mid = createPlayerWithFixtures(pos = PlayerPosition.MID)
        val prediction = engine.generatePrediction(mid, currentGameweek = 11)

        // Predicted Captain points = 2x expected points
        assertEquals(
            Math.round(prediction.expectedPoints * 2.0 * 10.0) / 10.0,
            prediction.predictedCaptainPoints,
            0.05
        )

        // Actual Captain points = 2x actual total points
        assertEquals(
            140, // 70 * 2
            prediction.actualCaptainPoints
        )

        assertNotEquals(
            "Predicted captain points must be separate from actual points",
            prediction.actualCaptainPoints.toDouble(),
            prediction.predictedCaptainPoints
        )
    }

    @Test
    fun testPositionSpecificScoringRules() {
        // Same expected goals for FWD vs MID vs DEF
        val fwd = createPlayerWithFixtures(pos = PlayerPosition.FWD, xg = 1.0, xa = 0.0)
        val mid = createPlayerWithFixtures(pos = PlayerPosition.MID, xg = 1.0, xa = 0.0)
        val def = createPlayerWithFixtures(pos = PlayerPosition.DEF, xg = 1.0, xa = 0.0)

        val fwdPred = engine.generatePrediction(fwd, currentGameweek = 11)
        val midPred = engine.generatePrediction(mid, currentGameweek = 11)
        val defPred = engine.generatePrediction(def, currentGameweek = 11)

        // Attacking points: DEF (6pts) > MID (5pts) > FWD (4pts) per goal
        assertTrue(
            "Defender should get higher attacking points per goal than Midfielder",
            defPred.expectedAttackingPoints > midPred.expectedAttackingPoints
        )
        assertTrue(
            "Midfielder should get higher attacking points per goal than Forward",
            midPred.expectedAttackingPoints > fwdPred.expectedAttackingPoints
        )
    }

    @Test
    fun testMultiGameweekHorizonProjections() {
        val player = createPlayerWithFixtures(pos = PlayerPosition.MID)
        val prediction = engine.generatePrediction(player, currentGameweek = 11, horizon = 3)

        assertEquals("Should generate 3 horizon predictions matching fixtures", 3, prediction.horizonPredictions.size)

        val gw11 = prediction.horizonPredictions[0]
        val gw13 = prediction.horizonPredictions[2]

        assertEquals(11, gw11.gameweek)
        assertEquals("IPS", gw11.opponentShortName)
        assertEquals(2, gw11.difficulty)

        // GW11 (FDR 2 at home) should have higher expected points than GW13 (FDR 5 away against Liverpool)
        assertTrue(
            "FDR 2 home fixture should project higher xPts than FDR 5 away fixture",
            gw11.expectedPoints > gw13.expectedPoints
        )
    }
}
