package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.*
import java.util.Locale

class PredictionEngine(
    private val availabilityAnalyzer: PlayerAvailabilityAnalyzer = PlayerAvailabilityAnalyzer(),
    private val fixtureAnalyzer: FixtureDifficultyAnalyzer = FixtureDifficultyAnalyzer(),
    private val performanceAnalyzer: PlayerPerformanceAnalyzer = PlayerPerformanceAnalyzer()
) {

    fun generatePrediction(
        player: PlayerModel,
        currentGameweek: Int,
        horizon: Int = 1
    ): PlayerPrediction {
        val completedGws = (currentGameweek - 1).coerceAtLeast(1)

        // 1. Availability Assessment for current gameweek
        val availability = availabilityAnalyzer.assess(player, currentGameweek)
        val expMinutes = availability.expectedMinutes
        val startProb = availability.startProbability

        // If player is confirmed out for this round, return deterministic 0 prediction
        if (expMinutes <= 0.0 || startProb <= 0.0) {
            return PlayerPrediction(
                playerId = player.id,
                gameweek = currentGameweek,
                expectedPoints = 0.0,
                expectedMinutes = 0.0,
                startProbability = 0.0,
                expectedAttackingPoints = 0.0,
                expectedDefensivePoints = 0.0,
                expectedCleanSheetPoints = 0.0,
                expectedBonusPoints = 0.0,
                expectedAppearancePoints = 0.0,
                predictedCaptainPoints = 0.0,
                actualPoints = null,
                actualCaptainPoints = null,
                confidenceScore = 0.95,
                recommendation = TransferRecommendation.SELL,
                recommendationReason = availability.summaryAr,
                analysisScore = 5.0,
                performanceScore = 5.0,
                reliabilityScore = 0.0,
                riskLevel = RiskLevel.CRITICAL,
                calculationSummaryAr = "مستبعد تماماً بسبب الغياب الطبي أو التأديبي للجولة الحالية",
                floorPoints = 0.0,
                ceilingPoints = 0.0,
                uncertaintyMargin = 0.0,
                horizonPredictions = emptyList()
            )
        }

        // 2. Identify fixtures for the target currentGameweek (Supporting Blank and Double Gameweeks)
        val fixturesByGw = player.upcomingFixtures.groupBy { it.gameweek }
        val currentGwFixtures = fixturesByGw[currentGameweek] ?: player.upcomingFixtures.filter { it.gameweek == currentGameweek }

        val fixtureImpact = fixtureAnalyzer.analyzeUpcomingFixtures(
            fixtures = if (currentGwFixtures.isNotEmpty()) currentGwFixtures else player.upcomingFixtures.take(1),
            position = player.position,
            horizon = 1
        )

        // 3. Performance & Reliability based on completed gameweeks
        val perfMetrics = performanceAnalyzer.analyze(
            player = player,
            completedGameweeks = completedGws,
            averageUpcomingFdr = fixtureImpact.averageFdr
        )

        // 4. Calculate Expected Points for Current Gameweek (Aggregating DGW if applicable)
        val gwCalculation = calculateGameweekExpectation(
            player = player,
            gameweek = currentGameweek,
            fixtures = currentGwFixtures,
            availability = availability,
            completedGws = completedGws
        )

        // 5. Multi-Gameweek Horizon Predictions (respecting the horizon parameter)
        val horizonList = mutableListOf<GameweekPrediction>()
        val effectiveHorizon = horizon.coerceIn(1, 8)
        val horizonGameweeks = currentGameweek until (currentGameweek + effectiveHorizon).coerceAtMost(39)

        for (targetGw in horizonGameweeks) {
            val gwFixtures = fixturesByGw[targetGw] ?: emptyList()
            // Dynamically model recovery progression over future gameweeks
            val futureAvailability = if (targetGw == currentGameweek) {
                availability
            } else {
                availabilityAnalyzer.assessForGameweek(player, targetGw, currentGameweek)
            }

            val futureGwCalc = if (targetGw == currentGameweek) {
                gwCalculation
            } else {
                calculateGameweekExpectation(
                    player = player,
                    gameweek = targetGw,
                    fixtures = gwFixtures,
                    availability = futureAvailability,
                    completedGws = completedGws
                )
            }

            val (oppName, isHome, diff) = when {
                gwFixtures.isEmpty() -> Triple("BLANK", false, 3)
                gwFixtures.size == 1 -> Triple(gwFixtures.first().opponentShortName, gwFixtures.first().isHome, gwFixtures.first().difficulty)
                else -> Triple(
                    gwFixtures.joinToString(", ") { "${it.opponentShortName}(${if (it.isHome) "H" else "A"})" },
                    gwFixtures.any { it.isHome },
                    Math.round(gwFixtures.map { it.difficulty }.average()).toInt()
                )
            }

            horizonList.add(
                GameweekPrediction(
                    gameweek = targetGw,
                    opponentShortName = oppName,
                    isHome = isHome,
                    difficulty = diff,
                    expectedPoints = futureGwCalc.expectedPoints,
                    floorPoints = futureGwCalc.floorPoints,
                    ceilingPoints = futureGwCalc.ceilingPoints,
                    matchCount = gwFixtures.size,
                    isDoubleGameweek = gwFixtures.size >= 2,
                    isBlankGameweek = gwFixtures.isEmpty()
                )
            )
        }

        // 6. Confidence Score based on data transparency and stability
        var confidence = 0.50
        if (player.statistics.hasCompleteExpectedMetrics) {
            confidence += 0.20
        } else if (player.statistics.hasAnyExpectedMetrics) {
            confidence += 0.10
        }
        if (player.minutesPlayed > 450) confidence += 0.15
        if (availability.riskFactor < 0.1) confidence += 0.15
        val finalConfidence = Math.round(confidence.coerceIn(0.30, 0.95) * 100.0) / 100.0

        // 7. Captain calculations (Strict separation: Retrospective actual points vs Predicted 2x)
        val predictedCapPoints = Math.round(gwCalculation.expectedPoints * 2.0 * 10.0) / 10.0

        // 8. Recommendation
        val (rec, reason) = when {
            perfMetrics.riskLevel == RiskLevel.CRITICAL -> {
                TransferRecommendation.SELL to "استبعاد فوري: ${availability.summaryAr}"
            }
            perfMetrics.riskLevel == RiskLevel.HIGH && player.price >= 6.5 -> {
                TransferRecommendation.SELL to "مخاطرة مرتفعة لا تبرر سعر اللاعب (£%.1fm) مع احتمالية مداورة أو صعوبة مواجهات".format(Locale.UK, player.price)
            }
            perfMetrics.performanceScore >= 66.0 && perfMetrics.reliabilityScore >= 60.0 && perfMetrics.riskLevel != RiskLevel.HIGH -> {
                TransferRecommendation.BUY to "توصية شراء قوية: مؤشر أداء عالي (${perfMetrics.performanceScore}) ونقاط متوقعة ممتازة (%.1f)".format(Locale.US, gwCalculation.expectedPoints)
            }
            perfMetrics.performanceScore <= 35.0 && player.price >= 5.5 -> {
                TransferRecommendation.SELL to "مردود هجومي/دفاعي ضعيف وتراجع حاد في معدلات الإنتاج"
            }
            else -> {
                TransferRecommendation.HOLD to "خيار مستقر ومتوازن في التشكيلة؛ يُنصح بالإبقاء عليه ومتابعة الجولات القادمة"
            }
        }

        val calculationSummary = buildString {
            if (gwCalculation.matchCount >= 2) {
                append("جولة مزدوجة (DGW - مباراتان): ")
            } else if (gwCalculation.matchCount == 0) {
                append("جولة فارغة (Blank GW - لا توجد مباريات): ")
            }
            append("مشاركة (+%.2f) ".format(Locale.US, gwCalculation.appearancePoints))
            append("+ هجوم (+%.2f) ".format(Locale.US, gwCalculation.attackingPoints))
            append("+ دفاع (%.2f) ".format(Locale.US, gwCalculation.defensivePoints))
            append("+ بونص (+%.2f). ".format(Locale.US, gwCalculation.bonusPoints))
            append("المدى التقديري المتوقع: [${gwCalculation.floorPoints} إلى ${gwCalculation.ceilingPoints}] نقطة.")
        }

        return PlayerPrediction(
            playerId = player.id,
            gameweek = currentGameweek,
            expectedPoints = gwCalculation.expectedPoints,
            expectedMinutes = Math.round(expMinutes * 10.0) / 10.0,
            startProbability = Math.round(startProb * 100.0) / 100.0,
            expectedAttackingPoints = Math.round(gwCalculation.attackingPoints * 10.0) / 10.0,
            expectedDefensivePoints = Math.round(gwCalculation.defensivePoints * 10.0) / 10.0,
            expectedCleanSheetPoints = Math.round(gwCalculation.cleanSheetPoints * 10.0) / 10.0,
            expectedBonusPoints = Math.round(gwCalculation.bonusPoints * 10.0) / 10.0,
            expectedAppearancePoints = Math.round(gwCalculation.appearancePoints * 10.0) / 10.0,
            predictedCaptainPoints = predictedCapPoints,
            actualPoints = null, // Strictly separated: null for future predictions
            actualCaptainPoints = null,
            confidenceScore = finalConfidence,
            recommendation = rec,
            recommendationReason = reason,
            analysisScore = perfMetrics.performanceScore,
            performanceScore = perfMetrics.performanceScore,
            reliabilityScore = perfMetrics.reliabilityScore,
            riskLevel = perfMetrics.riskLevel,
            goalInvolvementPercent = perfMetrics.goalInvolvementPercent,
            finishingVariance = perfMetrics.finishingEfficiency,
            calculationSummaryAr = calculationSummary,
            floorPoints = gwCalculation.floorPoints,
            ceilingPoints = gwCalculation.ceilingPoints,
            uncertaintyMargin = Math.round((gwCalculation.ceilingPoints - gwCalculation.floorPoints) / 2.0 * 10.0) / 10.0,
            horizonPredictions = horizonList
        )
    }

    private data class GameweekCalculation(
        val expectedPoints: Double,
        val floorPoints: Double,
        val ceilingPoints: Double,
        val appearancePoints: Double,
        val attackingPoints: Double,
        val defensivePoints: Double,
        val cleanSheetPoints: Double,
        val bonusPoints: Double,
        val matchCount: Int
    )

    /**
     * Calculates expectation for a complete Gameweek (0, 1, or 2 matches)
     */
    private fun calculateGameweekExpectation(
        player: PlayerModel,
        gameweek: Int,
        fixtures: List<UpcomingFixture>,
        availability: AvailabilityAssessment,
        completedGws: Int
    ): GameweekCalculation {
        // Blank Gameweek: 0 matches -> 0 points
        if (fixtures.isEmpty()) {
            return GameweekCalculation(
                expectedPoints = 0.0,
                floorPoints = 0.0,
                ceilingPoints = 0.0,
                appearancePoints = 0.0,
                attackingPoints = 0.0,
                defensivePoints = 0.0,
                cleanSheetPoints = 0.0,
                bonusPoints = 0.0,
                matchCount = 0
            )
        }

        var totalExp = 0.0
        var totalFloor = 0.0
        var totalCeiling = 0.0
        var totalApp = 0.0
        var totalAtt = 0.0
        var totalDef = 0.0
        var totalCs = 0.0
        var totalBonus = 0.0

        for (i in fixtures.indices) {
            val fix = fixtures[i]
            // In a Double Gameweek, slight rotation/fatigue discount on second match (0.92x minutes)
            val matchAvailability = if (i == 0) availability else availability.copy(
                expectedMinutes = availability.expectedMinutes * 0.92,
                startProbability = availability.startProbability * 0.95
            )

            val matchImpact = fixtureAnalyzer.analyzeUpcomingFixtures(listOf(fix), player.position, 1)
            val matchCalc = calculateSingleMatch(player, fix, matchAvailability, matchImpact, completedGws)

            totalExp += matchCalc.expectedPoints
            totalFloor += matchCalc.floorPoints
            totalCeiling += matchCalc.ceilingPoints
            totalApp += matchCalc.appearancePoints
            totalAtt += matchCalc.attackingPoints
            totalDef += matchCalc.defensivePoints
            totalCs += matchCalc.cleanSheetPoints
            totalBonus += matchCalc.bonusPoints
        }

        return GameweekCalculation(
            expectedPoints = Math.round(totalExp * 100.0) / 100.0,
            floorPoints = Math.round(totalFloor * 10.0) / 10.0,
            ceilingPoints = Math.round(totalCeiling * 10.0) / 10.0,
            appearancePoints = Math.round(totalApp * 100.0) / 100.0,
            attackingPoints = Math.round(totalAtt * 100.0) / 100.0,
            defensivePoints = Math.round(totalDef * 100.0) / 100.0,
            cleanSheetPoints = Math.round(totalCs * 100.0) / 100.0,
            bonusPoints = Math.round(totalBonus * 100.0) / 100.0,
            matchCount = fixtures.size
        )
    }

    private data class MatchCalculation(
        val expectedPoints: Double,
        val floorPoints: Double,
        val ceilingPoints: Double,
        val appearancePoints: Double,
        val attackingPoints: Double,
        val defensivePoints: Double,
        val cleanSheetPoints: Double,
        val bonusPoints: Double
    )

    private fun calculateSingleMatch(
        player: PlayerModel,
        fixture: UpcomingFixture,
        availability: AvailabilityAssessment,
        fixtureImpact: FixtureImpact,
        completedGws: Int
    ): MatchCalculation {
        val expMinutes = availability.expectedMinutes
        val startProb = availability.startProbability
        val minutesPlayed = player.minutesPlayed.toDouble()
        val safeCompletedGws = completedGws.coerceAtLeast(1)
        val nineties = if (minutesPlayed >= 90.0) minutesPlayed / 90.0 else (safeCompletedGws.toDouble() * 0.5)

        // 1. Appearance Points
        val probSixtyPlus = (startProb * (expMinutes / 90.0)).coerceIn(0.0, 1.0)
        val probSubAppearance = ((1.0 - startProb) * (if (expMinutes > 15.0) 0.5 else 0.1)).coerceIn(0.0, 0.5)
        val appearancePoints = (probSixtyPlus * 2.0) + (probSubAppearance * 1.0)

        // 2. Attacking Points
        val goalPts = when (player.position) {
            PlayerPosition.FWD -> 4.0
            PlayerPosition.MID -> 5.0
            PlayerPosition.DEF, PlayerPosition.GKP -> 6.0
        }
        val assistPts = 3.0

        val baseStats = player.statistics
        val xgPer90: Double = when {
            baseStats.expectedGoals != null && nineties > 0.5 -> (baseStats.expectedGoals / nineties).coerceIn(0.0, 1.5)
            nineties > 0.5 -> (baseStats.goals.toDouble() / nineties).coerceIn(0.0, 1.5)
            else -> defaultXgForPosition(player.position)
        }

        val xaPer90: Double = when {
            baseStats.expectedAssists != null && nineties > 0.5 -> (baseStats.expectedAssists / nineties).coerceIn(0.0, 1.2)
            nineties > 0.5 -> (baseStats.assists.toDouble() / nineties).coerceIn(0.0, 1.2)
            else -> defaultXaForPosition(player.position)
        }

        val matchFraction = (expMinutes / 90.0).coerceIn(0.0, 1.0)
        val matchXg = xgPer90 * matchFraction * fixtureImpact.attackingMultiplier
        val matchXa = xaPer90 * matchFraction * fixtureImpact.attackingMultiplier
        val attackingPoints = (matchXg * goalPts) + (matchXa * assistPts)

        // 3. Defensive Points
        val csReward = when (player.position) {
            PlayerPosition.GKP, PlayerPosition.DEF -> 4.0
            PlayerPosition.MID -> 1.0
            PlayerPosition.FWD -> 0.0
        }
        val cleanSheetPoints = if (csReward > 0.0) fixtureImpact.cleanSheetOdds * probSixtyPlus * csReward else 0.0

        // Expected goals conceded penalty: -1 per 2 goals conceded (GKP and DEF)
        val goalsConcededDeduction = if (player.position == PlayerPosition.DEF || player.position == PlayerPosition.GKP) {
            val expectedGoalsConceded = (1.5 / fixtureImpact.defensiveMultiplier).coerceIn(0.4, 3.2)
            (expectedGoalsConceded / 2.0) * probSixtyPlus * 1.0
        } else 0.0

        // Goalkeeper saves: 1 point per 3 saves, strictly without artificial minimum floor!
        val savesPoints = if (player.position == PlayerPosition.GKP) {
            val savesPer90 = if (nineties > 0.5) (baseStats.saves.toDouble() / nineties).coerceIn(0.0, 7.0) else 2.5
            val expectedSaves = savesPer90 * matchFraction
            (expectedSaves / 3.0).coerceAtLeast(0.0)
        } else 0.0

        val defensivePoints = cleanSheetPoints - goalsConcededDeduction + savesPoints

        // 4. Bonus Points Distribution
        val bonusPoints = if (nineties > 1.0 && baseStats.bonus > 0) {
            val bonusPer90 = (baseStats.bonus.toDouble() / nineties).coerceIn(0.0, 1.5)
            bonusPer90 * matchFraction * (if (matchXg + matchXa > 0.4) 1.25 else 0.8)
        } else {
            ((matchXg + matchXa) * 0.65).coerceIn(0.0, 1.2)
        }

        // 5. Discipline
        val disciplineDeduction = if (nineties > 1.0) {
            (baseStats.yellowCards.toDouble() / nineties * matchFraction).coerceIn(0.0, 0.4)
        } else 0.05

        val totalExpected = Math.max(0.0, Math.round((appearancePoints + attackingPoints + defensivePoints + bonusPoints - disciplineDeduction) * 100.0) / 100.0)

        // Floor: Baseline appearance minus conceded goals and cards (no attacking returns)
        val floor = Math.max(0.0, Math.round((appearancePoints - goalsConcededDeduction - disciplineDeduction) * 10.0) / 10.0)

        // Statistically disciplined 90th percentile ceiling (bounded based on player attacking potential + clean sheet + bonus)
        val attackingCeiling = (matchXg * 1.8 * goalPts) + (matchXa * 1.5 * assistPts)
        val cleanSheetCeiling = if (csReward > 0.0 && fixtureImpact.cleanSheetOdds >= 0.25) csReward else 0.0
        val maxBonus = if (matchXg + matchXa > 0.25 || fixtureImpact.cleanSheetOdds > 0.35) 3.0 else 1.0
        val ceiling = Math.min(24.0, Math.round((appearancePoints + attackingCeiling + cleanSheetCeiling + savesPoints + maxBonus) * 10.0) / 10.0)

        return MatchCalculation(
            expectedPoints = totalExpected,
            floorPoints = floor,
            ceilingPoints = Math.max(floor, ceiling),
            appearancePoints = appearancePoints,
            attackingPoints = attackingPoints,
            defensivePoints = defensivePoints,
            cleanSheetPoints = cleanSheetPoints,
            bonusPoints = bonusPoints
        )
    }

    private fun defaultXgForPosition(pos: PlayerPosition): Double = when (pos) {
        PlayerPosition.FWD -> 0.35
        PlayerPosition.MID -> 0.18
        PlayerPosition.DEF -> 0.05
        PlayerPosition.GKP -> 0.0
    }

    private fun defaultXaForPosition(pos: PlayerPosition): Double = when (pos) {
        PlayerPosition.FWD -> 0.15
        PlayerPosition.MID -> 0.20
        PlayerPosition.DEF -> 0.08
        PlayerPosition.GKP -> 0.0
    }
}
