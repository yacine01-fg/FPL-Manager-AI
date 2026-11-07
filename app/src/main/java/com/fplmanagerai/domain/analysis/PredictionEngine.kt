package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.*

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
        // 1. Availability Assessment
        val availability = availabilityAnalyzer.assess(player, currentGameweek)
        val expMinutes = availability.expectedMinutes
        val startProb = availability.startProbability

        // If player is out, return deterministic 0 prediction
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
                actualPoints = player.totalPoints,
                actualCaptainPoints = player.totalPoints * 2,
                confidenceScore = 0.95,
                recommendation = TransferRecommendation.SELL,
                recommendationReason = availability.summaryAr,
                analysisScore = 5.0,
                performanceScore = 5.0,
                reliabilityScore = 0.0,
                riskLevel = RiskLevel.CRITICAL,
                calculationSummaryAr = "مستبعد تماماً بسبب الغياب الطبي أو التأديبي",
                floorPoints = 0.0,
                ceilingPoints = 0.0,
                uncertaintyMargin = 0.0,
                horizonPredictions = emptyList()
            )
        }

        // 2. Fixture Impact for next gameweek
        val nextFixture = player.upcomingFixtures.firstOrNull()
        val fixtureImpact = fixtureAnalyzer.analyzeUpcomingFixtures(
            fixtures = player.upcomingFixtures,
            position = player.position,
            horizon = horizon
        )

        // 3. Performance & Reliability
        val perfMetrics = performanceAnalyzer.analyze(
            player = player,
            completedGameweeks = currentGameweek,
            averageUpcomingFdr = fixtureImpact.averageFdr
        )

        // 4. Single-Match Probabilistic Components
        val singleGwResult = calculateSingleMatchProbabilities(
            player = player,
            currentGameweek = currentGameweek,
            fixture = nextFixture,
            availability = availability,
            fixtureImpact = fixtureImpact
        )

        // 5. Multi-Gameweek Horizon Predictions (Next 3 to 5 GWs)
        val horizonList = player.upcomingFixtures.take(5).map { fix ->
            val fixImpact = fixtureAnalyzer.analyzeUpcomingFixtures(
                fixtures = listOf(fix),
                position = player.position,
                horizon = 1
            )
            val gwRes = calculateSingleMatchProbabilities(
                player = player,
                currentGameweek = fix.gameweek,
                fixture = fix,
                availability = availability,
                fixtureImpact = fixImpact
            )
            GameweekPrediction(
                gameweek = fix.gameweek,
                opponentShortName = fix.opponentShortName,
                isHome = fix.isHome,
                difficulty = fix.difficulty,
                expectedPoints = gwRes.expectedPoints,
                floorPoints = gwRes.floorPoints,
                ceilingPoints = gwRes.ceilingPoints
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

        // 7. Captain calculations (Strict separation: Retrospective 2x actual vs Predicted 2x)
        val predictedCapPoints = Math.round(singleGwResult.expectedPoints * 2.0 * 10.0) / 10.0
        val actualCapPoints = player.totalPoints * 2

        // 8. Recommendation
        val (rec, reason) = when {
            perfMetrics.riskLevel == RiskLevel.CRITICAL -> {
                TransferRecommendation.SELL to "استبعاد فوري: ${availability.summaryAr}"
            }
            perfMetrics.riskLevel == RiskLevel.HIGH && player.price >= 6.5 -> {
                TransferRecommendation.SELL to "مخاطرة مرتفعة لا تبرر سعر اللاعب (£%.1fm) مع احتمالية مداورة أو صعوبة مواجهات".format(java.util.Locale.UK, player.price)
            }
            perfMetrics.performanceScore >= 66.0 && perfMetrics.reliabilityScore >= 60.0 && perfMetrics.riskLevel != RiskLevel.HIGH -> {
                TransferRecommendation.BUY to "توصية شراء قوية: مؤشر أداء عالي (${perfMetrics.performanceScore}) ونقاط متوقعة ممتازة (%.1f)".format(java.util.Locale.US, singleGwResult.expectedPoints)
            }
            perfMetrics.performanceScore <= 35.0 && player.price >= 5.5 -> {
                TransferRecommendation.SELL to "مردود هجومي/دفاعي ضعيف وتراجع حاد في معدلات الإنتاج"
            }
            else -> {
                TransferRecommendation.HOLD to "خيار مستقر ومتوازن في التشكيلة؛ يُنصح بالإبقاء عليه ومتابعة الجولات القادمة"
            }
        }

        val calculationSummary = buildString {
            append("نموذج التنبؤ: مشاركة (+%.2f) ".format(java.util.Locale.US, singleGwResult.appearancePoints))
            append("+ هجوم (+%.2f) ".format(java.util.Locale.US, singleGwResult.attackingPoints))
            append("+ دفاع (%.2f) ".format(java.util.Locale.US, singleGwResult.defensivePoints))
            append("+ بونص (+%.2f). ".format(java.util.Locale.US, singleGwResult.bonusPoints))
            append("المدى التقديري المتوقع: [${singleGwResult.floorPoints} إلى ${singleGwResult.ceilingPoints}] نقطة.")
        }

        return PlayerPrediction(
            playerId = player.id,
            gameweek = currentGameweek,
            expectedPoints = singleGwResult.expectedPoints,
            expectedMinutes = Math.round(expMinutes * 10.0) / 10.0,
            startProbability = Math.round(startProb * 100.0) / 100.0,
            expectedAttackingPoints = Math.round(singleGwResult.attackingPoints * 10.0) / 10.0,
            expectedDefensivePoints = Math.round(singleGwResult.defensivePoints * 10.0) / 10.0,
            expectedCleanSheetPoints = Math.round(singleGwResult.cleanSheetPoints * 10.0) / 10.0,
            expectedBonusPoints = Math.round(singleGwResult.bonusPoints * 10.0) / 10.0,
            expectedAppearancePoints = Math.round(singleGwResult.appearancePoints * 10.0) / 10.0,
            predictedCaptainPoints = predictedCapPoints,
            actualPoints = player.totalPoints,
            actualCaptainPoints = actualCapPoints,
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
            floorPoints = singleGwResult.floorPoints,
            ceilingPoints = singleGwResult.ceilingPoints,
            uncertaintyMargin = Math.round((singleGwResult.ceilingPoints - singleGwResult.floorPoints) / 2.0 * 10.0) / 10.0,
            horizonPredictions = horizonList
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

    private fun calculateSingleMatchProbabilities(
        player: PlayerModel,
        currentGameweek: Int,
        fixture: UpcomingFixture?,
        availability: AvailabilityAssessment,
        fixtureImpact: FixtureImpact
    ): MatchCalculation {
        val expMinutes = availability.expectedMinutes
        val startProb = availability.startProbability
        val minutesPlayed = player.minutesPlayed.toDouble()
        val completedGws = currentGameweek.coerceAtLeast(1)
        val nineties = if (minutesPlayed >= 90.0) minutesPlayed / 90.0 else (completedGws.toDouble() * 0.5)

        // Appearance
        val probSixtyPlus = (startProb * (expMinutes / 90.0)).coerceIn(0.0, 1.0)
        val probSubAppearance = ((1.0 - startProb) * (if (expMinutes > 15.0) 0.5 else 0.1)).coerceIn(0.0, 0.5)
        val appearancePoints = (probSixtyPlus * 2.0) + (probSubAppearance * 1.0)

        // Attacking
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

        // Defensive
        val csReward = when (player.position) {
            PlayerPosition.GKP, PlayerPosition.DEF -> 4.0
            PlayerPosition.MID -> 1.0
            PlayerPosition.FWD -> 0.0
        }
        val cleanSheetPoints = if (csReward > 0.0) fixtureImpact.cleanSheetOdds * probSixtyPlus * csReward else 0.0

        val goalsConcededDeduction = if (player.position == PlayerPosition.DEF || player.position == PlayerPosition.GKP) {
            val expectedGoalsConceded = (1.5 / fixtureImpact.defensiveMultiplier).coerceIn(0.4, 3.2)
            (expectedGoalsConceded / 2.0) * probSixtyPlus * 1.0
        } else 0.0

        val savesPoints = if (player.position == PlayerPosition.GKP) {
            val savesPer90 = if (nineties > 0.5) (baseStats.saves.toDouble() / nineties).coerceIn(1.0, 6.0) else 3.0
            ((savesPer90 * matchFraction) / 3.0).coerceIn(0.3, 2.0)
        } else 0.0

        val defensivePoints = cleanSheetPoints - goalsConcededDeduction + savesPoints

        // Bonus Points Distribution
        val bonusPoints = if (nineties > 1.0 && baseStats.bonus > 0) {
            val bonusPer90 = (baseStats.bonus.toDouble() / nineties).coerceIn(0.0, 1.5)
            bonusPer90 * matchFraction * (if (matchXg + matchXa > 0.4) 1.25 else 0.8)
        } else {
            ((matchXg + matchXa) * 0.65).coerceIn(0.0, 1.2)
        }

        // Discipline
        val disciplineDeduction = if (nineties > 1.0) {
            (baseStats.yellowCards.toDouble() / nineties * matchFraction).coerceIn(0.0, 0.4)
        } else 0.05

        val totalExpected = Math.max(0.0, Math.round((appearancePoints + attackingPoints + defensivePoints + bonusPoints - disciplineDeduction) * 100.0) / 100.0)

        // Floor: Baseline appearance minus conceded goals and cards (0 attacking return)
        val floor = Math.max(0.0, Math.round((appearancePoints - goalsConcededDeduction - disciplineDeduction) * 10.0) / 10.0)

        // Ceiling: Explosive scenario (Brace or Goal+Assist + Clean sheet + 3 Bonus)
        val ceiling = Math.round((totalExpected + (matchXg + 0.3) * goalPts + 3.0) * 10.0) / 10.0

        return MatchCalculation(
            expectedPoints = totalExpected,
            floorPoints = floor,
            ceilingPoints = ceiling,
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
