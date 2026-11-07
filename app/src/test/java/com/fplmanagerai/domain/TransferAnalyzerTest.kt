package com.fplmanagerai.domain

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.team.FplChip
import com.fplmanagerai.domain.team.TransferAnalyzer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransferAnalyzerTest {

    private lateinit var analyzer: TransferAnalyzer

    @Before
    fun setUp() {
        analyzer = TransferAnalyzer()
    }

    private fun createPlayer(
        id: Int,
        name: String,
        pos: PlayerPosition,
        price: Double,
        xpts: Double,
        status: String = "a",
        chance: Int? = 100
    ): PlayerModel {
        val pred = PlayerPrediction(
            playerId = id,
            gameweek = 11,
            expectedPoints = xpts,
            expectedMinutes = if (status == "a") 90.0 else 0.0,
            startProbability = if (status == "a") 0.95 else 0.0,
            expectedAttackingPoints = 2.0,
            expectedDefensivePoints = 1.0,
            expectedCleanSheetPoints = 0.8,
            expectedBonusPoints = 0.5,
            expectedAppearancePoints = 2.0,
            predictedCaptainPoints = xpts * 2.0,
            actualPoints = (xpts * 10).toInt(),
            actualCaptainPoints = (xpts * 20).toInt(),
            confidenceScore = 0.85,
            recommendation = if (status == "a" && xpts >= 4.5) TransferRecommendation.BUY else if (status != "a") TransferRecommendation.SELL else TransferRecommendation.HOLD,
            recommendationReason = if (status == "a") "جاهز" else "مصاب",
            analysisScore = 75.0,
            performanceScore = 75.0,
            reliabilityScore = if (status == "a") 85.0 else 0.0,
            riskLevel = if (status == "a") RiskLevel.LOW else RiskLevel.CRITICAL
        )

        return PlayerModel(
            id = id,
            webName = name,
            firstName = "First",
            secondName = name,
            teamId = (id % 15) + 1,
            teamName = "Team $id",
            teamShortName = "T$id",
            position = pos,
            price = price,
            totalPoints = (xpts * 10).toInt(),
            form = 6.0,
            selectedByPercent = 20.0,
            minutesPlayed = 900,
            starts = 10,
            statistics = PlayerStatistics(2, 2, 2, 2, 0, 2, 50, 0.2, 0.2, 0.4, 1.0, null, null, null, null, 4.0, 0, 0, 0, 0),
            availability = PlayerAvailability.parse(status, chance, ""),
            upcomingFixtures = listOf(UpcomingFixture(11, 2, "Opp", "OPP", true, 2)),
            prediction = pred
        )
    }

    @Test
    fun testHitPenaltyCalculatedBeyondFreeTransfers() {
        val squad = listOf(
            createPlayer(1, "OutInjured", PlayerPosition.MID, price = 7.0, xpts = 0.0, status = "i", chance = 0),
            createPlayer(2, "OutPoor", PlayerPosition.FWD, price = 8.0, xpts = 1.5),
            createPlayer(3, "OutSub", PlayerPosition.DEF, price = 5.0, xpts = 1.0)
        )

        val pool = listOf(
            createPlayer(10, "InMid", PlayerPosition.MID, price = 7.0, xpts = 6.5),
            createPlayer(11, "InFwd", PlayerPosition.FWD, price = 8.0, xpts = 7.0),
            createPlayer(12, "InDef", PlayerPosition.DEF, price = 5.0, xpts = 5.5)
        )

        // 1 Free Transfer: 1st transfer hit = 0, 2nd transfer hit = 4
        val resultWithOneFt = analyzer.analyzeTransfers(
            currentSquad = squad,
            allPlayers = pool,
            freeTransfers = 1,
            horizon = 3
        )

        assertTrue(resultWithOneFt.suggestions.isNotEmpty())
        assertEquals("First transfer uses free transfer (hit = 0)", 0, resultWithOneFt.suggestions[0].hitPenalty)
        if (resultWithOneFt.suggestions.size > 1) {
            assertEquals("Second transfer exceeds free transfer (hit = 4)", 4, resultWithOneFt.suggestions[1].hitPenalty)
        }
    }

    @Test
    fun testKeepVsSellEvaluatesInjuredPlayersToSell() {
        val injuredMid = createPlayer(1, "InjuredStar", PlayerPosition.MID, price = 8.0, xpts = 0.0, status = "i", chance = 0)
        val fitMid = createPlayer(10, "FitReplacement", PlayerPosition.MID, price = 8.0, xpts = 6.0)

        val result = analyzer.analyzeTransfers(
            currentSquad = listOf(injuredMid),
            allPlayers = listOf(fitMid),
            freeTransfers = 1,
            horizon = 3
        )

        val evaluation = result.keepOrSellEvaluations.firstOrNull { it.playerToSell.id == 1 }
        assertNotNull(evaluation)
        assertTrue("Injured player must be marked shouldTransfer == true", evaluation!!.shouldTransfer)
        assertTrue(evaluation.reasoningAr.contains("خارج الخدمة"))
    }

    @Test
    fun testWildcardRecommendedWhenMultipleInjuriesExist() {
        // Squad with 3 critical injuries
        val squad = listOf(
            createPlayer(1, "Inj1", PlayerPosition.GKP, price = 5.0, xpts = 0.0, status = "i", chance = 0),
            createPlayer(2, "Inj2", PlayerPosition.DEF, price = 5.0, xpts = 0.0, status = "i", chance = 0),
            createPlayer(3, "Inj3", PlayerPosition.MID, price = 7.0, xpts = 0.0, status = "i", chance = 0),
            createPlayer(4, "Fit1", PlayerPosition.FWD, price = 9.0, xpts = 5.0)
        )

        val result = analyzer.analyzeTransfers(
            currentSquad = squad,
            allPlayers = emptyList(),
            freeTransfers = 1
        )

        val wcRec = result.chipRecommendations.firstOrNull { it.chip == FplChip.WILDCARD }
        assertNotNull(wcRec)
        assertTrue("Wildcard must be recommended when 3+ injuries exist", wcRec!!.isRecommended)
    }

    @Test
    fun testTripleCaptainRecommendedForHighExpectedPointsCandidate() {
        val superCaptain = createPlayer(1, "Haaland", PlayerPosition.FWD, price = 14.0, xpts = 9.2)
        val squad = listOf(superCaptain)

        val result = analyzer.analyzeTransfers(
            currentSquad = squad,
            allPlayers = emptyList()
        )

        val tcRec = result.chipRecommendations.firstOrNull { it.chip == FplChip.TRIPLE_CAPTAIN }
        assertNotNull(tcRec)
        assertTrue("Triple Captain should be recommended when candidate xPts >= 8.5", tcRec!!.isRecommended)
    }
}
