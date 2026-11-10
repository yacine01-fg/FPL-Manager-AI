package com.fplmanagerai.data.model

import java.util.Locale

/**
 * لقطة تاريخية غير قابلة للتعديل (Immutable Snapshot) لتوقع اللاعب في جولة محددة قبل انطلاقها.
 */
data class PredictionSnapshot(
    val id: String, // مفتاح فريد: "${playerId}_gw${gameweek}_${modelVersion}"
    val playerId: Int,
    val playerName: String,
    val playerTeam: String,
    val position: PlayerPosition,
    val gameweek: Int,
    val expectedPoints: Double,
    val expectedMinutes: Double,
    val startProbability: Double,
    val confidenceScore: Double,
    val reliabilityScore: Double,
    val riskLevel: RiskLevel,
    val recommendation: TransferRecommendation,
    val baselinePoints: Double, // نموذج مرجعي بسيط للمقارنة (مثل معدل النقاط لكل مباراة)
    val isStarter: Boolean = false,
    val isCaptain: Boolean = false,
    val isViceCaptain: Boolean = false,
    val modelVersion: String = "v2.0-engine",
    val snapshotTimestamp: Long = System.currentTimeMillis(),
    val isImmutable: Boolean = true
) {
    companion object {
        fun createKey(playerId: Int, gameweek: Int, modelVersion: String = "v2.0-engine"): String {
            return "${playerId}_gw${gameweek}_${modelVersion}"
        }
    }
}

/**
 * النتيجة والإحصائيات الرسمية الفعلية للاعب في جولة مكتملة مستخرجة من FPL API.
 */
data class ActualGameweekPlayerResult(
    val playerId: Int,
    val gameweek: Int,
    val actualPoints: Int,
    val actualMinutes: Int,
    val started: Boolean,
    val goals: Int = 0,
    val assists: Int = 0,
    val cleanSheets: Int = 0,
    val goalsConceded: Int = 0,
    val bonus: Int = 0,
    val bps: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    val isGameweekFinished: Boolean = true,
    val isPostponedOrBlank: Boolean = false,
    val recordedTimestamp: Long = System.currentTimeMillis()
)

/**
 * مقارنة تفصيلية بين لقطة التوقع والنتيجة الفعلية للاعب واحد.
 */
data class AccuracyComparisonRecord(
    val snapshot: PredictionSnapshot,
    val actual: ActualGameweekPlayerResult
) {
    val pointsError: Double = snapshot.expectedPoints - actual.actualPoints
    val absolutePointsError: Double = Math.abs(pointsError)
    val squaredPointsError: Double = pointsError * pointsError
    val minutesError: Double = snapshot.expectedMinutes - actual.actualMinutes
    val absoluteMinutesError: Double = Math.abs(minutesError)
    val startBrierScore: Double = Math.pow(snapshot.startProbability - (if (actual.started) 1.0 else 0.0), 2.0)
    val baselinePointsError: Double = Math.abs(snapshot.baselinePoints - actual.actualPoints)
    val aiBeatsBaseline: Boolean = absolutePointsError <= baselinePointsError
}

/**
 * ملخص دقة التوقعات بحسب مركز اللاعب (GKP / DEF / MID / FWD).
 */
data class PositionAccuracySummary(
    val position: PlayerPosition,
    val count: Int,
    val mae: Double,
    val rmse: Double,
    val bias: Double,
    val minutesMae: Double,
    val brierScore: Double,
    val baselineMae: Double
) {
    val aiAdvantage: Double
        get() = Math.round((baselineMae - mae) * 100.0) / 100.0
}

/**
 * مسار تطور الدقة عبر الجولات المختلفة.
 */
data class GameweekAccuracyTrend(
    val gameweek: Int,
    val evaluatedCount: Int,
    val mae: Double,
    val rmse: Double,
    val bias: Double,
    val minutesMae: Double,
    val baselineMae: Double,
    val captainPredictedPoints: Double? = null,
    val captainActualPoints: Int? = null,
    val isCaptainWin: Boolean = false
)

/**
 * دقة التوقعات حسب مستوى الثقة (HIGH / MEDIUM / LOW).
 */
data class ConfidenceAccuracySummary(
    val confidenceLevel: String,
    val count: Int,
    val mae: Double,
    val rmse: Double
)

/**
 * دقة التوقعات حسب تصنيف المخاطر (LOW / MEDIUM / HIGH / CRITICAL).
 */
data class RiskAccuracySummary(
    val riskLevel: RiskLevel,
    val count: Int,
    val mae: Double,
    val brierScore: Double
)

/**
 * تقييم توصيات التبديل (BUY / HOLD / SELL).
 */
data class RecommendationAccuracySummary(
    val recommendation: TransferRecommendation,
    val count: Int,
    val averageActualPoints: Double,
    val averageExpectedPoints: Double
)

/**
 * مقاييس دقة قرارات الكابتن ونائب الكابتن.
 */
data class CaptaincyAccuracyMetrics(
    val totalPicks: Int,
    val averageExpectedPoints: Double,
    val averageActualPoints: Double,
    val successRatePercent: Double, // النسبة التي حقق فيها الكابتن 6 نقاط أو أكثر
    val captainOutscoredViceCount: Int,
    val captainOutscoredAverageCount: Int
)

/**
 * الحزمة الشاملة لمقاييس دقة محرك التوقعات في FPL Manager AI.
 */
data class PredictionAccuracyMetrics(
    val totalEvaluatedPredictions: Int,
    val totalPendingPredictions: Int,
    val meanAbsoluteError: Double,
    val rootMeanSquareError: Double,
    val predictionBias: Double, // موجب = مبالغة في التوقع، سالب = تقليل من التوقع
    val minutesMeanAbsoluteError: Double,
    val startProbabilityBrierScore: Double, // مقياس موثوقية احتمالية المشاركة (0 إلى 1، الأقل أفضل)
    val baselineMae: Double, // خط الأساس للمقارنة (Baseline)
    val aiValueAdded: Double, // القيمة الإضافية المحققة فوق خط الأساس (Baseline MAE - AI MAE)
    val aiWinRatePercent: Double, // نسبة تفوق الذكاء الاصطناعي على خط الأساس البسيط
    val captaincyAccuracy: CaptaincyAccuracyMetrics,
    val positionBreakdown: Map<PlayerPosition, PositionAccuracySummary>,
    val gameweekTrends: List<GameweekAccuracyTrend>,
    val confidenceBreakdown: Map<String, ConfidenceAccuracySummary>,
    val riskBreakdown: Map<RiskLevel, RiskAccuracySummary>,
    val recommendationBreakdown: Map<TransferRecommendation, RecommendationAccuracySummary>,
    val diagnosticNotesAr: List<String>,
    val modelVersion: String = "v2.0-engine",
    val calculatedTimestamp: Long = System.currentTimeMillis()
) {
    val roundedMae: String
        get() = "%.2f".format(Locale.US, meanAbsoluteError)

    val roundedRmse: String
        get() = "%.2f".format(Locale.US, rootMeanSquareError)

    val roundedBias: String
        get() = "%+.2f".format(Locale.US, predictionBias)

    val roundedMinutesError: String
        get() = "%.1f د".format(Locale.US, minutesMeanAbsoluteError)

    val roundedBrierScore: String
        get() = "%.3f".format(Locale.US, startProbabilityBrierScore)

    val roundedValueAdded: String
        get() = "%+.2f".format(Locale.US, aiValueAdded)
}

