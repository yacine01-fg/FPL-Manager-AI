package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import kotlin.math.roundToInt

data class PerformanceMetrics(
    val pointsPer90: Double,
    val xGPer90: Double?,
    val xAPer90: Double?,
    val xGIPer90: Double?,
    val valueForm: Double,
    val valueSeason: Double,
    val finishingEfficiency: Double?,
    val minutesReliability: Double,
    val consistencyScore: Double,
    val performanceScore: Double,
    val reliabilityScore: Double,
    val riskLevel: RiskLevel,
    val goalInvolvementPercent: Double?,
    val summaryAr: String,
    val calculationDocumentationAr: String
)

class PlayerPerformanceAnalyzer {

    fun analyze(
        player: PlayerModel,
        completedGameweeks: Int,
        teamTotalGoals: Int = 0,
        averageUpcomingFdr: Double = 3.0
    ): PerformanceMetrics {

        val gws = completedGameweeks.coerceAtLeast(1)
        val stats = player.statistics

        val minutes = player.minutesPlayed.coerceAtLeast(0)
        val starts = player.starts.coerceAtLeast(0)

        val nineties = (minutes / 90.0).coerceAtLeast(0.01)

        val pointsPer90 = player.totalPoints.coerceAtLeast(0) / nineties

        val xG90 = stats.expectedGoals?.takeIf { it >= 0.0 }?.div(nineties)
        val xA90 = stats.expectedAssists?.takeIf { it >= 0.0 }?.div(nineties)
        val xGI90 = stats.expectedGoalInvolvements
            ?.takeIf { it >= 0.0 }
            ?.div(nineties)
            ?: if (xG90 != null || xA90 != null) {
                (xG90 ?: 0.0) + (xA90 ?: 0.0)
            } else {
                null
            }

        // الفارق بين الأهداف الفعلية والمتوقعة، وليس نسبة تحويل الفرص.
        val finishingEfficiency = stats.expectedGoals
            ?.takeIf { it > 0.0 }
            ?.let { stats.goals - it }

        // القيمة مقابل السعر.
        val price = player.price.coerceAtLeast(3.8)
        val valueForm = player.form.coerceAtLeast(0.0) / price
        val valueSeason = player.totalPoints.coerceAtLeast(0) / price

        val goalInvolvementPercent = if (teamTotalGoals > 0) {
            (
                (stats.goals + stats.assists).toDouble() /
                    teamTotalGoals * 100.0
                ).coerceIn(0.0, 100.0)
        } else {
            null
        }

        // 1. مؤشرات المشاركة والموثوقية.
        val startsRatio = (starts.toDouble() / gws).coerceIn(0.0, 1.0)

        val minutesRatio = (
            minutes.toDouble() / (gws * 90.0)
            ).coerceIn(0.0, 1.0)

        val chance = player.availability.chanceOfPlayingNextRound
        val status = player.availability.status.lowercase()

        val medicalProbability = when {
            status == "i" || status == "s" || status == "u" -> 0.0
            chance != null -> (chance / 100.0).coerceIn(0.0, 1.0)
            status == "d" -> 0.75
            else -> 1.0
        }

        val healthScore = medicalProbability * 45.0

        val minutesSecurityScore = (
            startsRatio * 0.60 + minutesRatio * 0.40
            ) * 35.0

        val yellowPenalty = (stats.yellowCards.coerceAtLeast(0) * 1.0)
            .coerceAtMost(8.0)

        val redPenalty = (stats.redCards.coerceAtLeast(0) * 6.0)
            .coerceAtMost(12.0)

        val disciplineScore = (
            20.0 - yellowPenalty - redPenalty
            ).coerceAtLeast(0.0)

        val reliabilityScore = round1(
            (healthScore + minutesSecurityScore + disciplineScore)
                .coerceIn(0.0, 100.0)
        )

        // 2. تقييم الإنتاج الهجومي والدفاعي.
        val formScore = (
            player.form.coerceAtLeast(0.0) / 8.0
            ).coerceIn(0.0, 1.0) * 25.0

        val productionScore = when (player.position) {
            PlayerPosition.FWD, PlayerPosition.MID -> {
                val production = xGI90 ?: (
                    (stats.goals + stats.assists).toDouble() / nineties
                    )

                (production / 0.70).coerceIn(0.0, 1.0) * 35.0
            }

            PlayerPosition.DEF -> {
                val cleanSheetRate = (
                    stats.cleanSheets.toDouble() / gws
                    ).coerceIn(0.0, 1.0)

                val attackingContribution = (
                    stats.goals * 0.45 + stats.assists * 0.35
                    ).coerceAtMost(1.0)

                (
                    cleanSheetRate * 0.65 +
                        attackingContribution * 0.35
                    ).coerceIn(0.0, 1.0) * 35.0
            }

            PlayerPosition.GKP -> {
                val cleanSheetRate = (
                    stats.cleanSheets.toDouble() / gws
                    ).coerceIn(0.0, 1.0)

                val saveRate = (
                    stats.saves.toDouble() / nineties
                    ).coerceIn(0.0, 8.0) / 8.0

                (
                    cleanSheetRate * 0.65 +
                        saveRate * 0.35
                    ).coerceIn(0.0, 1.0) * 35.0
            }
        }

        val pointsScore = (
            pointsPer90 / 7.0
            ).coerceIn(0.0, 1.0) * 20.0

        val fixtureScore = (
            (5.0 - averageUpcomingFdr.coerceIn(1.0, 5.0)) / 4.0
            ).coerceIn(0.0, 1.0) * 10.0

        // تخفيض الثقة في التقييم عندما تكون العينة صغيرة.
        val sampleConfidence = (
            minutes / 900.0
            ).coerceIn(0.35, 1.0)

        val rawPerformance = (
            formScore +
                productionScore +
                pointsScore +
                fixtureScore
            )

        val performanceScore = round1(
            (
                rawPerformance * (0.75 + sampleConfidence * 0.25)
                ).coerceIn(0.0, 100.0)
        )

        // 3. الاتساق: استقرار المشاركة والدقائق.
        val consistencyScore = round1(
            (
                startsRatio * 60.0 +
                    minutesRatio * 40.0
                ).coerceIn(0.0, 100.0)
        )

        // 4. تصنيف المخاطر.
        val riskLevel = when {
            medicalProbability <= 0.0 -> RiskLevel.CRITICAL

            medicalProbability < 0.50 -> RiskLevel.CRITICAL

            minutes < 90 && gws >= 3 -> RiskLevel.HIGH

            medicalProbability < 0.75 -> RiskLevel.HIGH

            startsRatio < 0.35 -> RiskLevel.HIGH

            averageUpcomingFdr >= 4.3 -> RiskLevel.HIGH

            medicalProbability < 0.90 -> RiskLevel.MEDIUM

            startsRatio < 0.65 -> RiskLevel.MEDIUM

            averageUpcomingFdr >= 3.6 -> RiskLevel.MEDIUM

            reliabilityScore < 65.0 -> RiskLevel.MEDIUM

            else -> RiskLevel.LOW
        }

        // 5. التفسير.
        val summary = buildString {
            when {
                player.form >= 6.5 ->
                    append("فورمة مرتفعة وأداء هجومي أو نقطي قوي. ")

                player.form >= 4.5 ->
                    append("مردود جيد ومتوازن. ")

                player.form >= 2.5 ->
                    append("مردود متوسط يحتاج إلى متابعة. ")

                else ->
                    append("فورمة ضعيفة وتراجع في المردود الأخير. ")
            }

            when {
                consistencyScore >= 80.0 ->
                    append("استقرار جيد في المشاركة والدقائق. ")

                consistencyScore >= 50.0 ->
                    append("استقرار متوسط في دقائق اللعب. ")

                else ->
                    append("مشاركة غير مستقرة وتحتاج إلى الحذر. ")
            }

            if (finishingEfficiency != null) {
                when {
                    finishingEfficiency > 2.0 ->
                        append("سجل أهدافاً أكثر من xG، وقد يتراجع هذا المعدل. ")

                    finishingEfficiency < -2.0 ->
                        append("أهدافه أقل من xG، مع فرص لتحسن التسجيل. ")

                    else ->
                        append("مردوده التهديفي قريب من المتوقع. ")
                }
            }

            append("الموثوقية: $reliabilityScore/100. ")
            append("المخاطرة: ${riskLevel.titleAr}.")
        }

        val documentation = buildString {
            append(
                "الأداء: الفورمة 25 نقطة، الإنتاج الأساسي 35، " +
                    "النقاط لكل 90 دقيقة 20، وصعوبة الجدول 10. "
            )

            append(
                "الموثوقية: الجاهزية الطبية 45 نقطة، " +
                    "دقائق اللعب والبدء أساسياً 35، والانضباط 20. "
            )

            append(
                "الاتساق يقيس انتظام المشاركة والدقائق، " +
                    "ولا يساوي مؤشر الموثوقية. "
            )

            append(
                "تم تخفيض أثر الأداء عند قلة دقائق اللعب، " +
                    "لأن العينات الصغيرة أقل موثوقية."
            )
        }

        return PerformanceMetrics(
            pointsPer90 = round1(pointsPer90),
            xGPer90 = xG90?.let { round2(it) },
            xAPer90 = xA90?.let { round2(it) },
            xGIPer90 = xGI90?.let { round2(it) },
            valueForm = round2(valueForm),
            valueSeason = round1(valueSeason),
            finishingEfficiency = finishingEfficiency?.let { round1(it) },
            minutesReliability = round2(minutesRatio),
            consistencyScore = consistencyScore,
            performanceScore = performanceScore,
            reliabilityScore = reliabilityScore,
            riskLevel = riskLevel,
            goalInvolvementPercent = goalInvolvementPercent?.let { round1(it) },
            summaryAr = summary.trim(),
            calculationDocumentationAr = documentation
        )
    }

    private fun round1(value: Double): Double =
        (value * 10.0).roundToInt() / 10.0

    private fun round2(value: Double): Double =
        (value * 100.0).roundToInt() / 100.0
}
