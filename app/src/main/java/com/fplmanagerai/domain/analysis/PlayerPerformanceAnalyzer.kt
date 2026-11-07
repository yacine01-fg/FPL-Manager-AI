package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel

data class PerformanceMetrics(
    val pointsPer90: Double,
    val xGPer90: Double?,
    val xAPer90: Double?,
    val xGIPer90: Double?,
    val valueForm: Double,
    val valueSeason: Double,
    val finishingEfficiency: Double?, // positive = overperforming xG, negative = underperforming
    val minutesReliability: Double, // 0.0 to 1.0
    val consistencyScore: Double, // 0.0 to 100.0
    val performanceScore: Double, // 0.0 to 100.0
    val reliabilityScore: Double, // 0.0 to 100.0
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
        val mins = player.minutesPlayed.toDouble()
        val nineties = if (mins >= 45.0) mins / 90.0 else (gws * 0.5)

        val p90 = if (nineties > 0.0) player.totalPoints / nineties else 0.0

        val stats = player.statistics
        val xG90 = stats.expectedGoals?.let { it / nineties }
        val xA90 = stats.expectedAssists?.let { it / nineties }
        val xGI90 = stats.expectedGoalInvolvements?.let { it / nineties }

        val finishingEfficiency = if (stats.expectedGoals != null && stats.expectedGoals > 0.0) {
            stats.goals - stats.expectedGoals
        } else null

        // Value metrics
        val price = player.price.coerceAtLeast(3.8)
        val valueForm = player.form / price
        val valueSeason = player.totalPoints / price

        // Goal Involvement Percentage
        val goalInvolvementPercent = if (teamTotalGoals > 0) {
            ((stats.goals + stats.assists).toDouble() / teamTotalGoals * 100.0).coerceIn(0.0, 100.0)
        } else null

        // 1. Reliability Score Calculation (0.0 to 100.0)
        // A. Health & Availability (45 points)
        val chance = player.availability.chanceOfPlayingNextRound
        val status = player.availability.status
        val healthScore = when {
            status == "i" || status == "s" || status == "u" || chance == 0 -> 0.0
            chance == 25 -> 10.0
            chance == 50 -> 22.0
            chance == 75 -> 34.0
            else -> 45.0
        }

        // B. Minutes Security (35 points)
        val startsRatio = (player.starts.toDouble() / gws).coerceIn(0.0, 1.0)
        val maxExpectedMins = gws * 90.0
        val minutesRatio = (mins / maxExpectedMins).coerceIn(0.0, 1.0)
        val minutesSecurityScore = (startsRatio * 0.60 + minutesRatio * 0.40) * 35.0

        // C. Discipline & Consistency (20 points)
        val yellowPenalty = (stats.yellowCards * 1.5).coerceAtMost(10.0)
        val redPenalty = (stats.redCards * 6.0).coerceAtMost(12.0)
        val disciplineScore = (20.0 - yellowPenalty - redPenalty).coerceIn(3.0, 20.0)

        val reliabilityScore = Math.round((healthScore + minutesSecurityScore + disciplineScore) * 10.0) / 10.0

        // 2. Performance Score Calculation (0.0 to 100.0)
        // A. Form Weight (30 points)
        val formFactor = (player.form / 8.0).coerceIn(0.0, 1.0) * 30.0

        // B. Underlying Production Weight (35 points)
        val productionFactor = when (player.position) {
            PlayerPosition.FWD, PlayerPosition.MID -> {
                val effectiveXgi90 = xGI90 ?: ((stats.goals + stats.assists).toDouble() / nineties)
                (effectiveXgi90 / 0.70).coerceIn(0.0, 1.0) * 35.0
            }
            PlayerPosition.DEF, PlayerPosition.GKP -> {
                val csRatio = stats.cleanSheets.toDouble() / gws
                val attBonus = (stats.goals * 0.4 + stats.assists * 0.3)
                ((csRatio * 1.5 + attBonus) / 0.8).coerceIn(0.0, 1.0) * 35.0
            }
        }

        // C. Points Per 90 (20 points)
        val p90Factor = (p90 / 6.5).coerceIn(0.0, 1.0) * 20.0

        // D. Fixture Ease Factor (15 points)
        val fixtureFactor = ((5.2 - averageUpcomingFdr) / 3.2).coerceIn(0.0, 1.0) * 15.0

        val performanceScore = Math.round((formFactor + productionFactor + p90Factor + fixtureFactor) * 10.0) / 10.0

        // 3. Risk Level Classification
        val riskLevel = when {
            status == "i" || status == "s" || status == "u" || chance == 0 || minutesRatio < 0.15 -> {
                RiskLevel.CRITICAL
            }
            chance in 25..50 || startsRatio < 0.40 || averageUpcomingFdr >= 4.2 -> {
                RiskLevel.HIGH
            }
            chance == 75 || startsRatio in 0.40..0.72 || averageUpcomingFdr in 3.4..4.1 -> {
                RiskLevel.MEDIUM
            }
            else -> {
                RiskLevel.LOW
            }
        }

        val summary = buildString {
            when {
                player.form >= 6.5 -> append("فترة تألق عالية وأداء استثنائي حالياً. ")
                player.form >= 4.5 -> append("مردود نقطي متوازن ومستقر. ")
                player.form >= 2.5 -> append("أداء متذبذب أو يمر بفترة ركود نسبي. ")
                else -> append("مردود ضعيف في الجولات الأخيرة. ")
            }

            if (finishingEfficiency != null) {
                when {
                    finishingEfficiency > 2.0 -> append("إنهاء هجومي يفوق المتوقع (قد يكون غير مستدام). ")
                    finishingEfficiency < -2.0 -> append("إهدار لفرص محققة مع أرقام xG واعدة (مرشح للانفجار التهديفي). ")
                    else -> append("الإنهاء متطابق تماماً مع الفرص المتاحة. ")
                }
            }
            append("مستوى الأمان: ${reliabilityScore}/100 [${riskLevel.titleAr}].")
        }

        val calculationDoc = buildString {
            append("طريقة احتساب مؤشر الأداء (Performance): الفورمة (30%) + المخرجات المتوقعة (35%) + معدل Pts/90 (20%) + سهولة الجدول (15%). ")
            append("طريقة احتساب مؤشر الموثوقية (Reliability): الجاهزية الطبية (45%) + استقرار دقائق اللعب والبدء أساسياً (35%) + الانضباط وتجنب البطاقات (20%).")
        }

        return PerformanceMetrics(
            pointsPer90 = Math.round(p90 * 10.0) / 10.0,
            xGPer90 = xG90?.let { Math.round(it * 100.0) / 100.0 },
            xAPer90 = xA90?.let { Math.round(it * 100.0) / 100.0 },
            xGIPer90 = xGI90?.let { Math.round(it * 100.0) / 100.0 },
            valueForm = Math.round(valueForm * 100.0) / 100.0,
            valueSeason = Math.round(valueSeason * 10.0) / 10.0,
            finishingEfficiency = finishingEfficiency?.let { Math.round(it * 10.0) / 10.0 },
            minutesReliability = Math.round(minutesRatio * 100.0) / 100.0,
            consistencyScore = reliabilityScore,
            performanceScore = performanceScore,
            reliabilityScore = reliabilityScore,
            riskLevel = riskLevel,
            goalInvolvementPercent = goalInvolvementPercent?.let { Math.round(it * 10.0) / 10.0 },
            summaryAr = summary.trim(),
            calculationDocumentationAr = calculationDoc
        )
    }
}
