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
        // 1. تحديد مباريات الجولة المستهدفة.
        val fixturesByGameweek = player.upcomingFixtures
            .groupBy { it.gameweek }
            .toSortedMap()

        val targetFixtures = fixturesByGameweek[currentGameweek]
            ?: if (fixturesByGameweek.isEmpty() || fixturesByGameweek.containsKey(currentGameweek) || fixturesByGameweek.firstKey() > currentGameweek) {
                emptyList()
            } else {
                fixturesByGameweek.values.firstOrNull() ?: emptyList()
            }

        val targetGameweek = currentGameweek

        // 2. تقييم جاهزية اللاعب للجولة الحالية.
        val availability = availabilityAnalyzer.assess(
            player = player,
            currentGameweek = currentGameweek
        )
        val expMinutes = availability.expectedMinutes
        val startProb = availability.startProbability

        if (expMinutes <= 0.0 || startProb <= 0.0) {
            return unavailablePrediction(
                player = player,
                gameweek = targetGameweek,
                reason = availability.summaryAr
            )
        }

        // 3. تحليل الأداء والموثوقية.
        val performance = performanceAnalyzer.analyze(
            player = player,
            completedGameweeks = currentGameweek,
            averageUpcomingFdr = fixtureAnalyzer
                .analyzeFixtureRun(
                    fixtures = targetFixtures,
                    horizon = 1
                ).averageFdr
        )

        // 4. توقع الجولة المستهدفة.
        // كل مباراة تحسب منفردة، ثم تجمع نتائجها.
        val targetCalculations = targetFixtures.map { fixture ->
            val fixtureImpact = fixtureAnalyzer.analyzeSingleFixture(fixture)
            calculateSingleMatchProbabilities(
                player = player,
                availability = availability,
                fixtureImpact = fixtureImpact
            )
        }
        val singleGwResult = combineCalculations(targetCalculations)

        // 5. توقع الجولات القادمة.
        // حالة الإصابة الحالية لا تُنقل آلياً إلى جميع الجولات المستقبلية.
        val futureAvailability = availabilityAnalyzer.assessAvailability(
            availability = PlayerAvailability.parse(
                status = "a",
                chance = 100,
                news = ""
            ),
            minutesPlayed = player.minutesPlayed,
            starts = player.starts,
            totalGameweeks = currentGameweek.coerceAtLeast(1)
        )

        val effectiveHorizon = horizon.coerceAtLeast(1)
        val horizonRange = targetGameweek until (targetGameweek + effectiveHorizon).coerceAtMost(39)

        val horizonList = horizonRange.map { gw ->
            val fixturesForGw = fixturesByGameweek[gw] ?: emptyList()
            if (fixturesForGw.isEmpty()) {
                GameweekPrediction(
                    gameweek = gw,
                    opponentShortName = "BLANK",
                    isHome = false,
                    difficulty = 3,
                    expectedPoints = 0.0,
                    floorPoints = 0.0,
                    ceilingPoints = 0.0,
                    matchCount = 0,
                    isDoubleGameweek = false,
                    isBlankGameweek = true
                )
            } else {
                val fixtureCalculations = fixturesForGw.map { fixture ->
                    val fixtureImpact = fixtureAnalyzer.analyzeSingleFixture(fixture)
                    calculateSingleMatchProbabilities(
                        player = player,
                        availability = if (gw == currentGameweek) availability else futureAvailability,
                        fixtureImpact = fixtureImpact
                    )
                }
                val gwResult = combineCalculations(fixtureCalculations)
                GameweekPrediction(
                    gameweek = gw,
                    opponentShortName = fixturesForGw.joinToString(" + ") { it.opponentShortName },
                    isHome = fixturesForGw.all { it.isHome },
                    difficulty = fixturesForGw.map { it.difficulty }.average().toInt().coerceIn(1, 5),
                    expectedPoints = gwResult.expectedPoints,
                    floorPoints = gwResult.floorPoints,
                    ceilingPoints = gwResult.ceilingPoints,
                    matchCount = fixturesForGw.size,
                    isDoubleGameweek = fixturesForGw.size >= 2,
                    isBlankGameweek = false
                )
            }
        }

        // 6. مستوى الثقة.
        val hasCompleteExpectedMetrics =
            player.statistics.expectedGoals != null &&
            player.statistics.expectedAssists != null

        val hasAnyExpectedMetrics =
            player.statistics.expectedGoals != null ||
            player.statistics.expectedAssists != null ||
            player.statistics.expectedGoalInvolvements != null ||
            player.statistics.expectedGoalsConceded != null

        var confidence = 0.50
        if (hasCompleteExpectedMetrics) {
            confidence += 0.20
        } else if (hasAnyExpectedMetrics) {
            confidence += 0.10
        }
        if (player.minutesPlayed > 450) {
            confidence += 0.15
        }
        if (availability.riskFactor < 0.10) {
            confidence += 0.15
        }
        val finalConfidence = round2(confidence.coerceIn(0.30, 0.95))

        // 7. نقاط القائد المتوقعة.
        val predictedCaptainPoints = round1(singleGwResult.expectedPoints * 2.0)

        // 8. توصية الانتقالات.
        val (recommendation, reason) = when {
            performance.riskLevel == RiskLevel.CRITICAL -> {
                TransferRecommendation.SELL to "استبعاد فوري: ${availability.summaryAr}"
            }
            performance.riskLevel == RiskLevel.HIGH && player.price >= 6.5 -> {
                TransferRecommendation.SELL to "مخاطرة مرتفعة لا تبرر سعر اللاعب (£%.1fm)".format(Locale.UK, player.price)
            }
            performance.performanceScore >= 66.0 &&
            performance.reliabilityScore >= 60.0 &&
            performance.riskLevel != RiskLevel.HIGH -> {
                TransferRecommendation.BUY to "توصية شراء: أداء مرتفع ونقاط متوقعة %.1f".format(
                    Locale.US,
                    singleGwResult.expectedPoints
                )
            }
            performance.performanceScore <= 35.0 && player.price >= 5.5 -> {
                TransferRecommendation.SELL to "مردود ضعيف وتراجع في الإنتاج الهجومي أو الدفاعي"
            }
            else -> {
                TransferRecommendation.HOLD to "خيار متوازن؛ يُنصح بمتابعة الأداء والجولات القادمة"
            }
        }

        // 9. ملخص الحساب.
        val calculationSummary = buildString {
            append("المشاركة (+%.2f) ".format(Locale.US, singleGwResult.appearancePoints))
            append("+ الهجوم (+%.2f) ".format(Locale.US, singleGwResult.attackingPoints))
            append("+ الدفاع (%.2f) ".format(Locale.US, singleGwResult.defensivePoints))
            append("+ البونص (+%.2f). ".format(Locale.US, singleGwResult.bonusPoints))
            append("المدى التقديري: [${singleGwResult.floorPoints} إلى ${singleGwResult.ceilingPoints}] نقطة.")
        }

        return PlayerPrediction(
            playerId = player.id,
            gameweek = targetGameweek,
            expectedPoints = singleGwResult.expectedPoints,
            expectedMinutes = round1(expMinutes),
            startProbability = round2(startProb),
            expectedAttackingPoints = round1(singleGwResult.attackingPoints),
            expectedDefensivePoints = round1(singleGwResult.defensivePoints),
            expectedCleanSheetPoints = round1(singleGwResult.cleanSheetPoints),
            expectedBonusPoints = round1(singleGwResult.bonusPoints),
            expectedAppearancePoints = round1(singleGwResult.appearancePoints),
            predictedCaptainPoints = predictedCaptainPoints,
            // هذه القيم تمثل نقاط الموسم الحالية، وليست نقاط GW الفعلية.
            // سنفصل بيانات الأداء الفعلي عن التوقعات في تعديل النموذج.
            actualPoints = player.totalPoints,
            actualCaptainPoints = player.totalPoints * 2,
            confidenceScore = finalConfidence,
            recommendation = recommendation,
            recommendationReason = reason,
            analysisScore = performance.performanceScore,
            performanceScore = performance.performanceScore,
            reliabilityScore = performance.reliabilityScore,
            riskLevel = performance.riskLevel,
            goalInvolvementPercent = performance.goalInvolvementPercent,
            finishingVariance = performance.finishingEfficiency,
            calculationSummaryAr = calculationSummary,
            floorPoints = singleGwResult.floorPoints,
            ceilingPoints = singleGwResult.ceilingPoints,
            uncertaintyMargin = round1((singleGwResult.ceilingPoints - singleGwResult.floorPoints) / 2.0),
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

    /**
     * جمع نتائج مباريات الجولة.
     * مهم جداً عند وجود Double Gameweek.
     */
    private fun combineCalculations(
        calculations: List<MatchCalculation>
    ): MatchCalculation {
        if (calculations.isEmpty()) {
            return MatchCalculation(
                expectedPoints = 0.0,
                floorPoints = 0.0,
                ceilingPoints = 0.0,
                appearancePoints = 0.0,
                attackingPoints = 0.0,
                defensivePoints = 0.0,
                cleanSheetPoints = 0.0,
                bonusPoints = 0.0
            )
        }

        fun total(selector: (MatchCalculation) -> Double): Double =
            calculations.sumOf(selector)

        return MatchCalculation(
            expectedPoints = round1(total { it.expectedPoints }),
            floorPoints = round1(total { it.floorPoints }),
            ceilingPoints = round1(total { it.ceilingPoints }),
            appearancePoints = round1(total { it.appearancePoints }),
            attackingPoints = round1(total { it.attackingPoints }),
            defensivePoints = round1(total { it.defensivePoints }),
            cleanSheetPoints = round1(total { it.cleanSheetPoints }),
            bonusPoints = round1(total { it.bonusPoints })
        )
    }

    private fun calculateSingleMatchProbabilities(
        player: PlayerModel,
        availability: AvailabilityAssessment,
        fixtureImpact: FixtureImpact
    ): MatchCalculation {
        val expMinutes = availability.expectedMinutes.coerceIn(0.0, 90.0)
        val startProb = availability.startProbability.coerceIn(0.0, 1.0)
        val minutesPlayed = player.minutesPlayed.toDouble()

        // استخدام الدقائق الفعلية بدلاً من افتراض عدد مباريات ثابت.
        val nineties = if (minutesPlayed >= 90.0) minutesPlayed / 90.0 else 1.0

        // المشاركة.
        val probSixtyPlus = minOf(
            startProb,
            (expMinutes / 90.0).coerceIn(0.0, 1.0)
        )
        val probSubAppearance = (
            (1.0 - startProb) *
            if (expMinutes > 15.0) 0.5 else 0.1
        ).coerceIn(0.0, 0.5)
        val appearancePoints = (probSixtyPlus * 2.0) + probSubAppearance

        // النقاط الهجومية.
        val goalPoints = when (player.position) {
            PlayerPosition.FWD -> 4.0
            PlayerPosition.MID -> 5.0
            PlayerPosition.DEF,
            PlayerPosition.GKP -> 6.0
        }
        val assistPoints = 3.0
        val stats = player.statistics

        val xgPer90 = when {
            stats.expectedGoals != null && nineties >= 0.5 ->
                (stats.expectedGoals / nineties).coerceIn(0.0, 1.5)
            nineties >= 0.5 ->
                (stats.goals.toDouble() / nineties).coerceIn(0.0, 1.5)
            else -> defaultXgForPosition(player.position)
        }

        val xaPer90 = when {
            stats.expectedAssists != null && nineties >= 0.5 ->
                (stats.expectedAssists / nineties).coerceIn(0.0, 1.2)
            nineties >= 0.5 ->
                (stats.assists.toDouble() / nineties).coerceIn(0.0, 1.2)
            else -> defaultXaForPosition(player.position)
        }

        val matchFraction = expMinutes / 90.0
        val matchXg = xgPer90 * matchFraction * fixtureImpact.attackingMultiplier
        val matchXa = xaPer90 * matchFraction * fixtureImpact.attackingMultiplier
        val attackingPoints = (matchXg * goalPoints) + (matchXa * assistPoints)

        // النقاط الدفاعية.
        val cleanSheetReward = when (player.position) {
            PlayerPosition.GKP,
            PlayerPosition.DEF -> 4.0
            PlayerPosition.MID -> 1.0
            PlayerPosition.FWD -> 0.0
        }
        val cleanSheetPoints = if (cleanSheetReward > 0.0) {
            fixtureImpact.cleanSheetOdds * probSixtyPlus * cleanSheetReward
        } else {
            0.0
        }

        val expectedGoalsConceded = if (
            player.position == PlayerPosition.DEF ||
            player.position == PlayerPosition.GKP
        ) {
            val concededPer90 = if (
                stats.expectedGoalsConceded != null && nineties >= 0.5
            ) {
                (stats.expectedGoalsConceded / nineties).coerceIn(0.2, 4.0)
            } else {
                1.5
            }
            concededPer90 * matchFraction * fixtureImpact.defensiveMultiplier
        } else {
            0.0
        }

        // تقدير تقريبي لخصم الأهداف المستقبلة.
        val goalsConcededDeduction = (expectedGoalsConceded / 2.0) * probSixtyPlus

        val savesPoints = if (player.position == PlayerPosition.GKP) {
            val savesPer90 = if (nineties >= 0.5) {
                (stats.saves.toDouble() / nineties).coerceIn(0.0, 6.0)
            } else {
                3.0
            }
            (savesPer90 * matchFraction / 3.0).coerceIn(0.0, 2.0)
        } else {
            0.0
        }

        val defensivePoints = cleanSheetPoints - goalsConcededDeduction + savesPoints

        // البونص.
        val bonusPoints = if (nineties > 1.0 && stats.bonus > 0) {
            val bonusPer90 = (stats.bonus.toDouble() / nineties).coerceIn(0.0, 1.5)
            bonusPer90 * matchFraction * (if (matchXg + matchXa > 0.4) 1.25 else 0.8)
        } else {
            ((matchXg + matchXa) * 0.65).coerceIn(0.0, 1.2)
        }

        // الانضباط: البطاقات الصفراء والحمراء.
        val yellowCardRisk = if (nineties > 1.0) {
            (stats.yellowCards.toDouble() / nineties * matchFraction).coerceIn(0.0, 0.4)
        } else {
            0.05
        }

        val redCardRisk = if (nineties > 1.0) {
            (stats.redCards.toDouble() / nineties * matchFraction * 3.0).coerceIn(0.0, 0.5)
        } else {
            0.0
        }

        val disciplineDeduction = yellowCardRisk + redCardRisk

        val expectedPoints = maxOf(
            0.0,
            appearancePoints + attackingPoints + defensivePoints + bonusPoints - disciplineDeduction
        )

        val floor = maxOf(
            0.0,
            appearancePoints - goalsConcededDeduction - disciplineDeduction
        )

        val ceiling = expectedPoints + (matchXg + 0.3) * goalPoints + 3.0

        return MatchCalculation(
            expectedPoints = round1(expectedPoints),
            floorPoints = round1(floor),
            ceilingPoints = round1(ceiling),
            appearancePoints = round1(appearancePoints),
            attackingPoints = round1(attackingPoints),
            defensivePoints = round1(defensivePoints),
            cleanSheetPoints = round1(cleanSheetPoints),
            bonusPoints = round1(bonusPoints)
        )
    }

    private fun unavailablePrediction(
        player: PlayerModel,
        gameweek: Int,
        reason: String
    ): PlayerPrediction {
        return PlayerPrediction(
            playerId = player.id,
            gameweek = gameweek,
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
            recommendationReason = reason,
            analysisScore = 5.0,
            performanceScore = 5.0,
            reliabilityScore = 0.0,
            riskLevel = RiskLevel.CRITICAL,
            calculationSummaryAr = "مستبعد بسبب الغياب الطبي أو التأديبي",
            floorPoints = 0.0,
            ceilingPoints = 0.0,
            uncertaintyMargin = 0.0,
            horizonPredictions = emptyList()
        )
    }

    private fun defaultXgForPosition(
        position: PlayerPosition
    ): Double = when (position) {
        PlayerPosition.FWD -> 0.35
        PlayerPosition.MID -> 0.18
        PlayerPosition.DEF -> 0.05
        PlayerPosition.GKP -> 0.0
    }

    private fun defaultXaForPosition(
        position: PlayerPosition
    ): Double = when (position) {
        PlayerPosition.FWD -> 0.15
        PlayerPosition.MID -> 0.15
        PlayerPosition.DEF -> 0.05
        PlayerPosition.GKP -> 0.0
    }

    private fun round1(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    private fun round2(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0
}
