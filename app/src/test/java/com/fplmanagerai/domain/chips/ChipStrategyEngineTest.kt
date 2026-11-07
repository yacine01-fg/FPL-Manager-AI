package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChipStrategyEngineTest {

    private lateinit var engine: ChipStrategyEngine
    private lateinit var tcAnalyzer: TripleCaptainAnalyzer
    private lateinit var bbAnalyzer: BenchBoostAnalyzer
    private lateinit var fhAnalyzer: FreeHitAnalyzer
    private lateinit var wcAnalyzer: WildcardAnalyzer

    @Before
    fun setUp() {
        engine = ChipStrategyEngine()
        tcAnalyzer = TripleCaptainAnalyzer()
        bbAnalyzer = BenchBoostAnalyzer()
        fhAnalyzer = FreeHitAnalyzer()
        wcAnalyzer = WildcardAnalyzer()
    }

    private fun createPlayer(
        id: Int,
        name: String,
        pos: PlayerPosition,
        price: Double,
        xpts: Double,
        teamId: Int = (id % 15) + 1,
        status: String = "a",
        chance: Int? = 100,
        minutes: Int = 900,
        starts: Int = 10,
        expectedMinutes: Double = 85.0
    ): PlayerModel {
        val pred = PlayerPrediction(
            playerId = id,
            gameweek = 12,
            expectedPoints = xpts,
            expectedMinutes = expectedMinutes,
            startProbability = if (status == "a" && (chance == null || chance >= 75)) 0.95 else 0.20,
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
            recommendationReason = "Good player",
            analysisScore = 80.0,
            performanceScore = 80.0,
            reliabilityScore = if (status == "a") 85.0 else 20.0,
            riskLevel = if (status == "a" && (chance == null || chance >= 75)) RiskLevel.LOW else RiskLevel.CRITICAL
        )

        return PlayerModel(
            id = id,
            webName = name,
            firstName = "First",
            secondName = name,
            teamId = teamId,
            teamName = "Team $teamId",
            teamShortName = "T$teamId",
            position = pos,
            price = price,
            totalPoints = (xpts * 18).toInt(),
            form = xpts,
            selectedByPercent = 25.0,
            minutesPlayed = minutes,
            starts = starts,
            statistics = PlayerStatistics(2, 2, 2, 2, 0, 2, 50, 0.2, 0.2, 0.4, 1.0, null, null, null, null, 4.0, 0, 0, 0, 0),
            availability = PlayerAvailability.parse(status, chance, ""),
            upcomingFixtures = listOf(
                UpcomingFixture(12, 2, "Opponent", "OPP", true, 2),
                UpcomingFixture(13, 3, "Opponent", "OPP", false, 3)
            ),
            prediction = pred
        )
    }

    private fun createStandardSquad(): List<PlayerModel> {
        val squad = mutableListOf<PlayerModel>()
        // 2 GK
        squad.add(createPlayer(1, "Raya", PlayerPosition.GKP, 5.5, 4.5, teamId = 1))
        squad.add(createPlayer(2, "Flekken", PlayerPosition.GKP, 4.5, 3.8, teamId = 2))
        // 5 DEF
        squad.add(createPlayer(3, "Gabriel", PlayerPosition.DEF, 6.2, 5.0, teamId = 1))
        squad.add(createPlayer(4, "Alexander-Arnold", PlayerPosition.DEF, 7.0, 5.8, teamId = 3))
        squad.add(createPlayer(5, "Gvardiol", PlayerPosition.DEF, 6.0, 4.8, teamId = 4))
        squad.add(createPlayer(6, "Robinson", PlayerPosition.DEF, 4.7, 4.0, teamId = 5))
        squad.add(createPlayer(7, "Van de Ven", PlayerPosition.DEF, 4.6, 3.9, teamId = 6))
        // 5 MID
        squad.add(createPlayer(8, "Salah", PlayerPosition.MID, 12.8, 8.5, teamId = 3))
        squad.add(createPlayer(9, "Palmer", PlayerPosition.MID, 11.0, 7.8, teamId = 7))
        squad.add(createPlayer(10, "Saka", PlayerPosition.MID, 10.1, 7.2, teamId = 1))
        squad.add(createPlayer(11, "Mbeumo", PlayerPosition.MID, 7.8, 6.0, teamId = 2))
        squad.add(createPlayer(12, "Rogers", PlayerPosition.MID, 5.4, 4.2, teamId = 8))
        // 3 FWD
        squad.add(createPlayer(13, "Haaland", PlayerPosition.FWD, 15.2, 9.2, teamId = 4))
        squad.add(createPlayer(14, "Isak", PlayerPosition.FWD, 8.5, 6.4, teamId = 9))
        squad.add(createPlayer(15, "Wood", PlayerPosition.FWD, 6.5, 5.1, teamId = 10))
        return squad
    }

    private fun createStandardFixtures(gw: Int): List<FixtureModel> {
        val list = mutableListOf<FixtureModel>()
        // 10 matches for 20 teams in gw
        for (i in 1..10) {
            val home = i
            val away = i + 10
            list.add(
                FixtureModel(
                    id = 100 + i,
                    event = gw,
                    kickoffTime = "2026-11-20T15:00:00Z",
                    homeTeamId = home,
                    awayTeamId = away,
                    homeDifficulty = 2,
                    awayDifficulty = 3,
                    isFinished = false,
                    isStarted = false
                )
            )
        }
        return list
    }

    // --- A. TRIPLE CAPTAIN TESTS ---

    @Test
    fun testTripleCaptain_recommendsSaveInSingleGwWhenFutureDgwExists() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = tcAnalyzer.analyze(squad, squad, fixtures, 12, history)

        assertEquals(ChipType.TRIPLE_CAPTAIN, analysis.chip)
        assertEquals(ChipVerdict.SAVE_FOR_LATER, analysis.verdict)
        assertTrue("Recommended GW should target future DGW", analysis.recommendedGameweek > 12)
        assertTrue(analysis.futureOpportunityGain > analysis.currentGwGain)
        assertNotNull(analysis.targetPlayer)
    }

    @Test
    fun testTripleCaptain_recommendsActivateInDoubleGameweekWithEliteCaptain() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12).toMutableList()
        // Make team 4 (Haaland's team) play twice in GW 12
        fixtures.add(
            FixtureModel(
                id = 999,
                event = 12,
                kickoffTime = "2026-11-22T20:00:00Z",
                homeTeamId = 4,
                awayTeamId = 15,
                homeDifficulty = 2,
                awayDifficulty = 4,
                isFinished = false,
                isStarted = false
            )
        )

        val history = UserChipHistory()
        val analysis = tcAnalyzer.analyze(squad, squad, fixtures, 12, history)

        assertEquals(ChipType.TRIPLE_CAPTAIN, analysis.chip)
        assertEquals(ChipVerdict.ACTIVATE_NOW, analysis.verdict)
        assertEquals(12, analysis.recommendedGameweek)
        assertTrue("Gain should be substantial in DGW", analysis.expectedPointsGain >= 10.0)
        assertTrue("Arabic reason mentions DGW", analysis.primaryReasonAr.contains("مزدوجة") || analysis.primaryReasonAr.contains("DGW"))
    }

    @Test
    fun testTripleCaptain_returnsUsedWhenAlreadyConsumed() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory(usedChips = setOf(ChipType.TRIPLE_CAPTAIN))

        val analysis = tcAnalyzer.analyze(squad, squad, fixtures, 12, history)

        assertEquals(ChipStatus.USED, analysis.status)
        assertEquals(ChipVerdict.USED_OR_EXPIRED, analysis.verdict)
        assertEquals(0.0, analysis.expectedPointsGain, 0.01)
    }

    // --- B. BENCH BOOST TESTS ---

    @Test
    fun testBenchBoost_neverRecommendsWhenBenchHasInjuredOrDoubtfulPlayer() {
        val squad = createStandardSquad().toMutableList()
        // Inject an injured bench player (Flekken GK, id 2)
        squad[1] = createPlayer(2, "Flekken", PlayerPosition.GKP, 4.5, 0.0, teamId = 2, status = "i", chance = 0)
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = bbAnalyzer.analyze(squad, fixtures, 12, history)

        assertEquals(ChipVerdict.SAVE_FOR_LATER, analysis.verdict)
        assertTrue("Reason must warn about weak/injured bench", analysis.primaryReasonAr.contains("دكة البدلاء غير جاهزة") || analysis.primaryReasonAr.contains("مصاب"))
    }

    @Test
    fun testBenchBoost_neverRecommendsWhenBenchXptsIsLow() {
        val squad = createStandardSquad().toMutableList()
        // Make 4 bench players have very low minutes/points (< 2.0 each, total < 8.0)
        squad[1] = createPlayer(2, "ReserveGk", PlayerPosition.GKP, 4.0, 1.5, minutes = 50, starts = 0, expectedMinutes = 15.0)
        squad[6] = createPlayer(7, "ReserveDef", PlayerPosition.DEF, 4.0, 1.2, minutes = 60, starts = 0, expectedMinutes = 10.0)
        squad[11] = createPlayer(12, "ReserveMid", PlayerPosition.MID, 4.5, 1.8, minutes = 100, starts = 1, expectedMinutes = 20.0)
        squad[14] = createPlayer(15, "ReserveFwd", PlayerPosition.FWD, 4.5, 1.6, minutes = 80, starts = 0, expectedMinutes = 15.0)

        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = bbAnalyzer.analyze(squad, fixtures, 12, history)

        assertEquals(ChipVerdict.SAVE_FOR_LATER, analysis.verdict)
        assertTrue("Gain should be small", analysis.currentGwGain < 14.0)
    }

    @Test
    fun testBenchBoost_recommendsActivateWhenAllBenchStartersInDoubleGameweek() {
        val squad = createStandardSquad().toMutableList()
        // Elevate bench players: GK2=5.0, Outfield bench=5.2, 5.0, 4.8 -> sum > 20
        squad[1] = createPlayer(2, "Flekken", PlayerPosition.GKP, 4.5, 5.0, teamId = 2)
        squad[6] = createPlayer(7, "Van de Ven", PlayerPosition.DEF, 4.6, 5.2, teamId = 6)
        squad[11] = createPlayer(12, "Rogers", PlayerPosition.MID, 5.4, 5.0, teamId = 8)
        squad[14] = createPlayer(15, "Wood", PlayerPosition.FWD, 6.5, 4.8, teamId = 10)

        val fixtures = createStandardFixtures(12).toMutableList()
        // Add double fixtures for bench teams 2 and 6
        fixtures.add(FixtureModel(901, 12, "2026-11-22T20:00:00Z", 2, 11, 2, 3, false, false))
        fixtures.add(FixtureModel(902, 12, "2026-11-22T20:00:00Z", 6, 12, 2, 3, false, false))

        val history = UserChipHistory()
        val bench = listOf(squad[1], squad[6], squad[11], squad[14])
        val analysis = bbAnalyzer.analyze(squad, fixtures, 12, history, explicitBench = bench)

        assertEquals(ChipVerdict.ACTIVATE_NOW, analysis.verdict)
        assertEquals(12, analysis.recommendedGameweek)
        assertTrue("Bench gain should be high", analysis.expectedPointsGain >= 18.0)
    }

    // --- C. FREE HIT TESTS ---

    @Test
    fun testFreeHit_recommendsSaveInNormalGameweek() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = fhAnalyzer.analyze(squad, squad, fixtures, 12, history)

        assertEquals(ChipVerdict.SAVE_FOR_LATER, analysis.verdict)
        assertTrue("Should target future blank gameweek", analysis.recommendedGameweek > 12)
    }

    @Test
    fun testFreeHit_recommendsActivateInMajorBlankGameweek() {
        val squad = createStandardSquad()
        // Simulate a major blank gameweek where only teams 1, 2, 3, 4 play (6 fixtures instead of 10)
        // Squad has players from teams 5, 6, 7, 8, 9, 10 which have NO fixtures in this GW! (6 players blanking)
        val blankFixtures = listOf(
            FixtureModel(1, 12, "2026-11-20T15:00:00Z", 1, 2, 2, 2, false, false),
            FixtureModel(2, 12, "2026-11-20T15:00:00Z", 3, 4, 2, 2, false, false),
            FixtureModel(3, 12, "2026-11-20T15:00:00Z", 11, 12, 2, 2, false, false)
        )
        val history = UserChipHistory()

        val analysis = fhAnalyzer.analyze(squad, squad, blankFixtures, 12, history)

        assertEquals(ChipVerdict.ACTIVATE_NOW, analysis.verdict)
        assertEquals(12, analysis.recommendedGameweek)
        assertTrue("Affected players should detect blanking players", analysis.affectedPlayers.size >= 4)
    }

    @Test
    fun testFreeHit_enforcesNoConsecutiveGameweekRule() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        // User played Free Hit in GW 11!
        val history = UserChipHistory(lastUsedFreeHitGw = 11)

        val analysis = fhAnalyzer.analyze(squad, squad, fixtures, 12, history)

        assertEquals(ChipVerdict.SAVE_FOR_LATER, analysis.verdict)
        assertTrue("Reason must mention consecutive restriction", analysis.primaryReasonAr.contains("متتاليتين"))
    }

    // --- D. WILDCARD TESTS ---

    @Test
    fun testWildcard_recommendsActivateWhenSquadHasMultipleInjuriesAndHitPenalties() {
        val squad = createStandardSquad().toMutableList()
        // Inject 4 injured players
        squad[0] = createPlayer(1, "Raya", PlayerPosition.GKP, 5.5, 0.0, status = "i", chance = 0)
        squad[3] = createPlayer(4, "Alexander-Arnold", PlayerPosition.DEF, 7.0, 0.0, status = "i", chance = 0)
        squad[7] = createPlayer(8, "Salah", PlayerPosition.MID, 12.8, 0.0, status = "i", chance = 0)
        squad[12] = createPlayer(13, "Haaland", PlayerPosition.FWD, 15.2, 0.0, status = "s", chance = 0)

        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = wcAnalyzer.analyze(squad, squad, fixtures, 12, history, freeTransfersAvailable = 1)

        assertEquals(ChipVerdict.ACTIVATE_NOW, analysis.verdict)
        assertEquals(12, analysis.recommendedGameweek)
        assertTrue("Reason must cite squad crisis or hit savings", analysis.primaryReasonAr.contains("أزمة") || analysis.primaryReasonAr.contains("إصابات"))
    }

    @Test
    fun testWildcard_warnsAndActivatesBeforeFirstHalfDeadline() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(19)
        val history = UserChipHistory(firstHalfWildcardUsed = false)

        val analysis = wcAnalyzer.analyze(squad, squad, fixtures, 19, history)

        assertEquals(ChipVerdict.ACTIVATE_NOW, analysis.verdict)
        assertEquals(19, analysis.recommendedGameweek)
        assertTrue("Must alert about first half expiration", analysis.primaryReasonAr.contains("تنتهي صلاحية") || analysis.primaryReasonAr.contains("النصف الأول"))
    }

    // --- E. MASTER ARBITRATION TESTS (STRICT 1 CHIP PER GAMEWEEK) ---

    @Test
    fun testMasterEngine_strictlyAllowsAtMostOneChipPerGameweek() {
        val squad = createStandardSquad().toMutableList()
        // Set up conditions where BOTH Wildcard (deadline GW19) and Triple Captain (DGW) want to activate
        val fixtures = createStandardFixtures(19).toMutableList()
        fixtures.add(FixtureModel(999, 19, "2026-11-22T20:00:00Z", 4, 15, 2, 4, false, false))

        val history = UserChipHistory(firstHalfWildcardUsed = false)
        val plan = engine.evaluateChips(squad, squad, fixtures, 19, history)

        // Count how many chips have ACTIVATE_NOW
        val activateNowCount = plan.chipAnalyses.values.count { it.verdict == ChipVerdict.ACTIVATE_NOW }
        assertEquals("Strict rule: exactly at most 1 chip can be ACTIVATE_NOW", 1, activateNowCount)
        assertNotNull(plan.activeChipThisGameweek)

        // Check that demoted chips have an arbitration note
        val otherChips = plan.chipAnalyses.values.filter { it.chip != plan.activeChipThisGameweek }
        assertTrue("Other chips must be SAVE_FOR_LATER", otherChips.all { it.verdict != ChipVerdict.ACTIVATE_NOW })
        assertTrue("Demoted chip should have arbitration explanation", otherChips.any { it.arbitrationNoteAr != null })
    }

    @Test
    fun testMasterEngine_respectsUserConfirmedActiveChipThisGameweek() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory(activeChipThisGw = ChipType.TRIPLE_CAPTAIN)

        val plan = engine.evaluateChips(squad, squad, fixtures, 12, history)

        assertEquals(ChipType.TRIPLE_CAPTAIN, plan.activeChipThisGameweek)
        assertEquals(ChipStatus.ACTIVE_THIS_GW, plan.chipAnalyses[ChipType.TRIPLE_CAPTAIN]?.status)
        assertEquals(ChipVerdict.ACTIVATE_NOW, plan.chipAnalyses[ChipType.TRIPLE_CAPTAIN]?.verdict)

        // All other chips must not be ACTIVATE_NOW
        assertEquals(ChipVerdict.SAVE_FOR_LATER, plan.chipAnalyses[ChipType.BENCH_BOOST]?.verdict)
        assertEquals(ChipVerdict.SAVE_FOR_LATER, plan.chipAnalyses[ChipType.FREE_HIT]?.verdict)
        assertEquals(ChipVerdict.SAVE_FOR_LATER, plan.chipAnalyses[ChipType.WILDCARD]?.verdict)
    }

    @Test
    fun testGameweek1_prohibitsFreeHitAndWildcard() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(1)
        val history = UserChipHistory()

        val fhAnalysis = fhAnalyzer.analyze(squad, squad, fixtures, 1, history)
        assertEquals("Free Hit must be SAVE_FOR_LATER in GW1", ChipVerdict.SAVE_FOR_LATER, fhAnalysis.verdict)
        assertTrue(fhAnalysis.primaryReasonAr.contains("الجولة الأولى"))

        val wcAnalysis = wcAnalyzer.analyze(squad, squad, fixtures, 1, history)
        assertEquals("Wildcard must be SAVE_FOR_LATER in GW1", ChipVerdict.SAVE_FOR_LATER, wcAnalysis.verdict)
        assertTrue(wcAnalysis.primaryReasonAr.contains("الجولة الأولى"))
    }

    @Test
    fun testTripleCaptain_calculatesIncrementalGainCorrectly() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val analysis = tcAnalyzer.analyze(squad, squad, fixtures, 12, history)
        assertNotNull(analysis.targetPlayer)
        val captain = analysis.targetPlayer!!
        val captainXpts = captain.prediction?.expectedPoints ?: 0.0

        // The incremental gain should equal 1x captain xPts (adjusted by minutes and start probability)
        assertTrue("Gain must be positive and non-zero", analysis.currentGwGain > 0.0)
        assertTrue("Incremental gain must not count normal 2x captain points twice", analysis.currentGwGain <= (captainXpts * 1.5))
    }

    @Test
    fun testBenchBoost_calculatesExpectedPointsOfAllFourBenchPlayers() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(12)
        val history = UserChipHistory()

        val explicitBench = listOf(squad[1], squad[6], squad[11], squad[14])
        val expectedSum = explicitBench.sumOf { it.prediction?.expectedPoints ?: 0.0 }

        val analysis = bbAnalyzer.analyze(squad, fixtures, 12, history, explicitBench = explicitBench)
        assertEquals(expectedSum, analysis.currentGwGain, 0.5)
        assertEquals(4, analysis.affectedPlayers.size)
    }

    @Test
    fun testFirstHalfExpirationWarning_emittedForSet1InGameweek18And19() {
        val squad = createStandardSquad()
        val fixtures = createStandardFixtures(19)
        val history = UserChipHistory()

        val tcAnalysis = tcAnalyzer.analyze(squad, squad, fixtures, 19, history, half = ChipHalf.FIRST_HALF)
        assertNotNull("Should emit expiration warning for Set 1 in GW19", tcAnalysis.expirationWarningAr)
        assertTrue(tcAnalysis.expirationWarningAr!!.contains("تنتهي صلاحية"))

        val wcAnalysis = wcAnalyzer.analyze(squad, squad, fixtures, 19, history, half = ChipHalf.FIRST_HALF)
        assertNotNull("Should emit expiration warning for Set 1 in GW19", wcAnalysis.expirationWarningAr)
    }
}
