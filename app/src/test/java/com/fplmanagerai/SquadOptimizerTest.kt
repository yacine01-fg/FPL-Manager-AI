package com.fplmanagerai

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.SquadOptimizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SquadOptimizerTest {

    private lateinit var optimizer: SquadOptimizer

    @Before
    fun setUp() {
        optimizer = SquadOptimizer()
    }

    private fun generatePool(): List<PlayerModel> {
        val list = mutableListOf<PlayerModel>()
        var id = 1

        // 20 teams, each team has 1 GKP, 2 DEF, 2 MID, 1 FWD
        for (team in 1..20) {
            // GKP
            list.add(createPlayer(id++, team, PlayerPosition.GKP, price = 4.0 + (team % 3) * 0.5, xpts = 3.0 + (team % 3)))
            // DEF
            list.add(createPlayer(id++, team, PlayerPosition.DEF, price = 4.5 + (team % 3) * 0.5, xpts = 3.5 + (team % 4)))
            list.add(createPlayer(id++, team, PlayerPosition.DEF, price = 4.0 + (team % 2) * 0.5, xpts = 2.5 + (team % 3)))
            // MID
            list.add(createPlayer(id++, team, PlayerPosition.MID, price = 5.0 + (team % 6) * 1.0, xpts = 4.0 + (team % 5)))
            list.add(createPlayer(id++, team, PlayerPosition.MID, price = 4.5 + (team % 3) * 0.5, xpts = 3.0 + (team % 3)))
            // FWD
            list.add(createPlayer(id++, team, PlayerPosition.FWD, price = 6.0 + (team % 5) * 1.0, xpts = 4.5 + (team % 5)))
        }

        return list
    }

    private fun createPlayer(
        id: Int,
        teamId: Int,
        pos: PlayerPosition,
        price: Double,
        xpts: Double,
        ownership: Double = 15.0,
        reliability: Double = 75.0
    ): PlayerModel {
        val pred = PlayerPrediction(
            playerId = id,
            gameweek = 10,
            expectedPoints = xpts,
            expectedMinutes = 90.0,
            startProbability = 0.95,
            expectedAttackingPoints = 2.0,
            expectedDefensivePoints = 1.0,
            expectedCleanSheetPoints = 0.8,
            expectedBonusPoints = 0.5,
            expectedAppearancePoints = 2.0,
            predictedCaptainPoints = xpts * 2.0,
            actualPoints = (xpts * 10).toInt(),
            actualCaptainPoints = (xpts * 20).toInt(),
            confidenceScore = 0.85,
            recommendation = TransferRecommendation.BUY,
            recommendationReason = "جاهز للمشاركة",
            analysisScore = 75.0,
            performanceScore = 75.0,
            reliabilityScore = reliability,
            riskLevel = RiskLevel.LOW
        )

        return PlayerModel(
            id = id,
            webName = "Player_$id",
            firstName = "First",
            secondName = "Second",
            teamId = teamId,
            teamName = "Team $teamId",
            teamShortName = "T$teamId",
            position = pos,
            price = price,
            totalPoints = (xpts * 10).toInt(),
            form = 6.0,
            selectedByPercent = ownership,
            minutesPlayed = 900,
            starts = 10,
            statistics = PlayerStatistics(2, 2, 2, 2, 0, 2, 50, 0.2, 0.2, 0.4, 1.0, null, null, null, null, 4.0, 0, 0, 0, 0),
            availability = PlayerAvailability.parse("a", null, null),
            upcomingFixtures = emptyList(),
            prediction = pred
        )
    }

    @Test
    fun testOptimizerProducesValid15ManSquad() {
        val pool = generatePool()
        val result = optimizer.optimizeSquad(
            allPlayers = pool,
            budget = 100.0,
            preferredFormation = Formation(3, 5, 2),
            strategy = OptimizationStrategy.BALANCED
        )

        assertEquals("Total squad size must be 15", 15, result.totalSquad.size)
        assertEquals("Starting 11 must be 11 players", 11, result.starting11.size)
        assertEquals("Bench must be 4 players", 4, result.bench.size)

        // Check positions in total squad
        assertEquals(2, result.totalSquad.count { it.position == PlayerPosition.GKP })
        assertEquals(5, result.totalSquad.count { it.position == PlayerPosition.DEF })
        assertEquals(5, result.totalSquad.count { it.position == PlayerPosition.MID })
        assertEquals(3, result.totalSquad.count { it.position == PlayerPosition.FWD })

        // Check club limit <= 3
        val teamCounts = result.totalSquad.groupBy { it.teamId }
        for ((team, players) in teamCounts) {
            assertTrue("Team $team cannot have more than 3 players", players.size <= 3)
        }

        // Check budget
        assertTrue("Total cost must be within budget", result.totalCost <= 100.0)

        // Captain and Vice-Captain distinct
        assertNotNull(result.captain)
        assertNotNull(result.viceCaptain)
        assertNotEquals("Captain and Vice-Captain must be different players", result.captain.id, result.viceCaptain.id)
    }

    @Test
    fun testAllFourOptimizationStrategiesProduceValidSquads() {
        val pool = generatePool()
        val strategies = listOf(
            OptimizationStrategy.MAX_EXPECTED_POINTS,
            OptimizationStrategy.LOW_RISK,
            OptimizationStrategy.BALANCED,
            OptimizationStrategy.DIFFERENTIAL
        )

        for (strategy in strategies) {
            val result = optimizer.optimizeSquad(
                allPlayers = pool,
                budget = 100.0,
                strategy = strategy
            )

            assertEquals("Strategy ${strategy.name} must return 15 players", 15, result.totalSquad.size)
            assertEquals("Strategy ${strategy.name} must have 11 starters", 11, result.starting11.size)
            assertEquals("Strategy ${strategy.name} must have 4 bench players", 4, result.bench.size)
            assertTrue("Cost must be within 100m for ${strategy.name}", result.totalCost <= 100.0)

            // Bench Slot 0 must be Goalkeeper
            assertEquals("First bench slot must be Goalkeeper", PlayerPosition.GKP, result.bench[0].position)

            // Formation must be valid
            assertTrue("Formation must be valid", result.formation.isValid)
        }
    }

    @Test
    fun testBenchOrderAdheresToOfficialRules() {
        val pool = generatePool()
        val result = optimizer.optimizeSquad(
            allPlayers = pool,
            budget = 100.0,
            strategy = OptimizationStrategy.MAX_EXPECTED_POINTS
        )

        val bench = result.bench
        assertEquals(4, bench.size)
        // 1st sub is GKP
        assertEquals(PlayerPosition.GKP, bench[0].position)
        // Subs 2, 3, 4 are outfield players
        assertTrue(bench[1].position != PlayerPosition.GKP)
        assertTrue(bench[2].position != PlayerPosition.GKP)
        assertTrue(bench[3].position != PlayerPosition.GKP)
    }
}
