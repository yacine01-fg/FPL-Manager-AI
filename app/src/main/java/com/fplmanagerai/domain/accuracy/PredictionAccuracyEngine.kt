package com.fplmanagerai.domain.accuracy

import com.fplmanagerai.data.model.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * محرك قياس وتحليل دقة التوقعات في FPL Manager AI.
 * يقارن التوقعات المحفوظة قبل الموعد النهائي مع النتائج الرسمية الفعلية بعد انتهاء الجولات.
 */
class PredictionAccuracyEngine {

    /**
     * احتساب المقاييس الإحصائية الشاملة لجميع السجلات المقترنة.
     */
    fun evaluateAccuracy(
        snapshots: List<PredictionSnapshot>,
        actualResultsMap: Map<Pair<Int, Int>, ActualGameweekPlayerResult>, // (playerId, gameweek) -> Result
        modelVersion: String = "v2.0-engine"
    ): PredictionAccuracyMetrics {
        if (snapshots.isEmpty()) {
            return createEmptyMetrics(modelVersion)
        }

        val comparisonRecords = mutableListOf<AccuracyComparisonRecord>()
        var pendingCount = 0

        for (snapshot in snapshots) {
            val key = snapshot.playerId to snapshot.gameweek
            val actual = actualResultsMap[key]

            if (actual != null && actual.isGameweekFinished) {
                comparisonRecords.add(AccuracyComparisonRecord(snapshot, actual))
            } else {
                pendingCount++
            }
        }

        if (comparisonRecords.isEmpty()) {
            return createEmptyMetrics(modelVersion, totalPending = pendingCount)
        }

        val n = comparisonRecords.size.toDouble()

        // 1. المقاييس الأساسية
        val mae = comparisonRecords.sumOf { it.absolutePointsError } / n
        val rmse = sqrt(comparisonRecords.sumOf { it.squaredPointsError } / n)
        val bias = comparisonRecords.sumOf { it.pointsError } / n
        val minutesMae = comparisonRecords.sumOf { it.absoluteMinutesError } / n
        val brierScore = comparisonRecords.sumOf { it.startBrierScore } / n

        // 2. مقارنة النموذج بالـ Baseline
        val baselineMae = comparisonRecords.sumOf { it.baselinePointsError } / n
        val aiValueAdded = baselineMae - mae
        val aiWins = comparisonRecords.count { it.aiBeatsBaseline }
        val aiWinRate = (aiWins / n) * 100.0

        // 3. تحليل الكابتن
        val captaincyMetrics = calculateCaptaincyAccuracy(comparisonRecords)

        // 4. التقسيم حسب مراكز اللاعبين
        val positionBreakdown = calculatePositionBreakdown(comparisonRecords)

        // 5. التطور عبر الجولات (Gameweek Trends)
        val gameweekTrends = calculateGameweekTrends(comparisonRecords)

        // 6. التقسيم حسب درجة الثقة والمخاطر
        val confidenceBreakdown = calculateConfidenceBreakdown(comparisonRecords)
        val riskBreakdown = calculateRiskBreakdown(comparisonRecords)
        val recBreakdown = calculateRecommendationBreakdown(comparisonRecords)

        // 7. توليد الملاحظات التشخيصية باللغة العربية
        val notes = generateDiagnosticNotes(
            mae = mae,
            rmse = rmse,
            bias = bias,
            minutesMae = minutesMae,
            brierScore = brierScore,
            aiValueAdded = aiValueAdded,
            aiWinRate = aiWinRate,
            captaincy = captaincyMetrics,
            positionMap = positionBreakdown
        )

        return PredictionAccuracyMetrics(
            totalEvaluatedPredictions = comparisonRecords.size,
            totalPendingPredictions = pendingCount,
            meanAbsoluteError = Math.round(mae * 100.0) / 100.0,
            rootMeanSquareError = Math.round(rmse * 100.0) / 100.0,
            predictionBias = Math.round(bias * 100.0) / 100.0,
            minutesMeanAbsoluteError = Math.round(minutesMae * 10.0) / 10.0,
            startProbabilityBrierScore = Math.round(brierScore * 1000.0) / 1000.0,
            baselineMae = Math.round(baselineMae * 100.0) / 100.0,
            aiValueAdded = Math.round(aiValueAdded * 100.0) / 100.0,
            aiWinRatePercent = Math.round(aiWinRate * 10.0) / 10.0,
            captaincyAccuracy = captaincyMetrics,
            positionBreakdown = positionBreakdown,
            gameweekTrends = gameweekTrends,
            confidenceBreakdown = confidenceBreakdown,
            riskBreakdown = riskBreakdown,
            recommendationBreakdown = recBreakdown,
            diagnosticNotesAr = notes,
            modelVersion = modelVersion
        )
    }

    private fun calculateCaptaincyAccuracy(records: List<AccuracyComparisonRecord>): CaptaincyAccuracyMetrics {
        val captainRecords = records.filter { it.snapshot.isCaptain }
        if (captainRecords.isEmpty()) {
            return CaptaincyAccuracyMetrics(0, 0.0, 0.0, 0.0, 0, 0)
        }

        val totalPicks = captainRecords.size
        val avgExp = captainRecords.sumOf { it.snapshot.expectedPoints } / totalPicks
        val avgAct = captainRecords.sumOf { it.actual.actualPoints.toDouble() } / totalPicks

        // النجاح: تحقيق 6 نقاط فأكثر في الجولة (عائد مرتفع للقائد)
        val successCount = captainRecords.count { it.actual.actualPoints >= 6 }
        val successRate = (successCount.toDouble() / totalPicks) * 100.0

        // مقارنة الكابتن بنائب الكابتن
        val byGw = records.groupBy { it.snapshot.gameweek }
        var capOutscoredVice = 0
        var capOutscoredAvg = 0

        for ((_, gwRecords) in byGw) {
            val cap = gwRecords.firstOrNull { it.snapshot.isCaptain } ?: continue
            val vice = gwRecords.firstOrNull { it.snapshot.isViceCaptain }
            val squadAvg = gwRecords.filter { it.snapshot.isStarter }.map { it.actual.actualPoints }.average().takeIf { !it.isNaN() } ?: 4.0

            if (vice != null && cap.actual.actualPoints >= vice.actual.actualPoints) {
                capOutscoredVice++
            }
            if (cap.actual.actualPoints >= squadAvg) {
                capOutscoredAvg++
            }
        }

        return CaptaincyAccuracyMetrics(
            totalPicks = totalPicks,
            averageExpectedPoints = Math.round(avgExp * 10.0) / 10.0,
            averageActualPoints = Math.round(avgAct * 10.0) / 10.0,
            successRatePercent = Math.round(successRate * 10.0) / 10.0,
            captainOutscoredViceCount = capOutscoredVice,
            captainOutscoredAverageCount = capOutscoredAvg
        )
    }

    private fun calculatePositionBreakdown(records: List<AccuracyComparisonRecord>): Map<PlayerPosition, PositionAccuracySummary> {
        val grouped = records.groupBy { it.snapshot.position }
        val result = mutableMapOf<PlayerPosition, PositionAccuracySummary>()

        for (pos in PlayerPosition.values()) {
            val list = grouped[pos] ?: emptyList()
            if (list.isEmpty()) continue

            val count = list.size
            val dCount = count.toDouble()
            val mae = list.sumOf { it.absolutePointsError } / dCount
            val rmse = sqrt(list.sumOf { it.squaredPointsError } / dCount)
            val bias = list.sumOf { it.pointsError } / dCount
            val minsMae = list.sumOf { it.absoluteMinutesError } / dCount
            val brier = list.sumOf { it.startBrierScore } / dCount
            val baseMae = list.sumOf { it.baselinePointsError } / dCount

            result[pos] = PositionAccuracySummary(
                position = pos,
                count = count,
                mae = Math.round(mae * 100.0) / 100.0,
                rmse = Math.round(rmse * 100.0) / 100.0,
                bias = Math.round(bias * 100.0) / 100.0,
                minutesMae = Math.round(minsMae * 10.0) / 10.0,
                brierScore = Math.round(brier * 1000.0) / 1000.0,
                baselineMae = Math.round(baseMae * 100.0) / 100.0
            )
        }

        return result
    }

    private fun calculateGameweekTrends(records: List<AccuracyComparisonRecord>): List<GameweekAccuracyTrend> {
        val byGw = records.groupBy { it.snapshot.gameweek }.toSortedMap()
        val list = mutableListOf<GameweekAccuracyTrend>()

        for ((gw, gwRecords) in byGw) {
            val count = gwRecords.size
            val dCount = count.toDouble()
            val mae = gwRecords.sumOf { it.absolutePointsError } / dCount
            val rmse = sqrt(gwRecords.sumOf { it.squaredPointsError } / dCount)
            val bias = gwRecords.sumOf { it.pointsError } / dCount
            val minsMae = gwRecords.sumOf { it.absoluteMinutesError } / dCount
            val baseMae = gwRecords.sumOf { it.baselinePointsError } / dCount

            val capRecord = gwRecords.firstOrNull { it.snapshot.isCaptain }
            val viceRecord = gwRecords.firstOrNull { it.snapshot.isViceCaptain }
            val capWin = if (capRecord != null && viceRecord != null) {
                capRecord.actual.actualPoints >= viceRecord.actual.actualPoints
            } else capRecord != null && capRecord.actual.actualPoints >= 6

            list.add(
                GameweekAccuracyTrend(
                    gameweek = gw,
                    evaluatedCount = count,
                    mae = Math.round(mae * 100.0) / 100.0,
                    rmse = Math.round(rmse * 100.0) / 100.0,
                    bias = Math.round(bias * 100.0) / 100.0,
                    minutesMae = Math.round(minsMae * 10.0) / 10.0,
                    baselineMae = Math.round(baseMae * 100.0) / 100.0,
                    captainPredictedPoints = capRecord?.snapshot?.expectedPoints,
                    captainActualPoints = capRecord?.actual?.actualPoints,
                    isCaptainWin = capWin
                )
            )
        }

        return list
    }

    private fun calculateConfidenceBreakdown(records: List<AccuracyComparisonRecord>): Map<String, ConfidenceAccuracySummary> {
        val groups = records.groupBy {
            when {
                it.snapshot.confidenceScore >= 75.0 -> "ثقة مرتفعة (>= 75%)"
                it.snapshot.confidenceScore >= 55.0 -> "ثقة متوسطة (55-74%)"
                else -> "ثقة منخفضة (< 55%)"
            }
        }

        return groups.mapValues { (label, list) ->
            val count = list.size
            val dCount = count.toDouble()
            val mae = list.sumOf { it.absolutePointsError } / dCount
            val rmse = sqrt(list.sumOf { it.squaredPointsError } / dCount)
            ConfidenceAccuracySummary(
                confidenceLevel = label,
                count = count,
                mae = Math.round(mae * 100.0) / 100.0,
                rmse = Math.round(rmse * 100.0) / 100.0
            )
        }
    }

    private fun calculateRiskBreakdown(records: List<AccuracyComparisonRecord>): Map<RiskLevel, RiskAccuracySummary> {
        val groups = records.groupBy { it.snapshot.riskLevel }
        return groups.mapValues { (risk, list) ->
            val count = list.size
            val dCount = count.toDouble()
            val mae = list.sumOf { it.absolutePointsError } / dCount
            val brier = list.sumOf { it.startBrierScore } / dCount
            RiskAccuracySummary(
                riskLevel = risk,
                count = count,
                mae = Math.round(mae * 100.0) / 100.0,
                brierScore = Math.round(brier * 1000.0) / 1000.0
            )
        }
    }

    private fun calculateRecommendationBreakdown(records: List<AccuracyComparisonRecord>): Map<TransferRecommendation, RecommendationAccuracySummary> {
        val groups = records.groupBy { it.snapshot.recommendation }
        return groups.mapValues { (rec, list) ->
            val count = list.size
            val dCount = count.toDouble()
            val avgAct = list.sumOf { it.actual.actualPoints.toDouble() } / dCount
            val avgExp = list.sumOf { it.snapshot.expectedPoints } / dCount
            RecommendationAccuracySummary(
                recommendation = rec,
                count = count,
                averageActualPoints = Math.round(avgAct * 10.0) / 10.0,
                averageExpectedPoints = Math.round(avgExp * 10.0) / 10.0
            )
        }
    }

    private fun generateDiagnosticNotes(
        mae: Double,
        rmse: Double,
        bias: Double,
        minutesMae: Double,
        brierScore: Double,
        aiValueAdded: Double,
        aiWinRate: Double,
        captaincy: CaptaincyAccuracyMetrics,
        positionMap: Map<PlayerPosition, PositionAccuracySummary>
    ): List<String> {
        val notes = mutableListOf<String>()

        // 1. القيمة المضافة فوق خط الأساس
        if (aiValueAdded > 0) {
            notes.add("الذكاء الاصطناعي يتفوق على نموذج الأساس (Baseline) بمقدار +%.2f نقطة وبنسبة نجاح %.1f%%.".format(Locale.US, aiValueAdded, aiWinRate))
        } else {
            notes.add("تقارب بين النموذج الذكي والنموذج المرجعي البسيط (فارق %.2f نقطة).".format(Locale.US, aiValueAdded))
        }

        // 2. تحليل الانحياز (Prediction Bias)
        if (bias > 0.4) {
            notes.add("انحياز إيجابي (Overestimation): يميل المحرك للمبالغة الطفيفة في التوقعات بمعدل +%.2f نقطة لكل لاعب.".format(Locale.US, bias))
        } else if (bias < -0.4) {
            notes.add("انحياز سلبي (Underestimation): يميل المحرك للتحفظ وتقليل التوقعات بمعدل %.2f نقطة.".format(Locale.US, bias))
        } else {
            notes.add("انحياز متوازن ومثالي (Bias: %+.2f): التوقعات تتوزع بشكل عادل حول المتوسط الفعلي دون تضخيم.".format(Locale.US, bias))
        }

        // 3. تحليل الدقائق والبدء أساسياً
        if (brierScore <= 0.15) {
            notes.add("معايرة احتمالية البدء أساسياً ممتازة (Brier Score: %.3f)، مع متوسط خطأ دقائق %.1f دقيقة.".format(Locale.US, brierScore, minutesMae))
        } else {
            notes.add("تأثرت دقة الدقائق بمفاجآت المداورة والتبديل المبكر (Brier Score: %.3f، خطأ الدقائق: %.1f د).".format(Locale.US, brierScore, minutesMae))
        }

        // 4. تقييم دقة الكابتن
        if (captaincy.totalPicks > 0) {
            notes.add("أداء الكابتن: حقق القائد المختار متوسط %.1f نقطة فعلية بنسبة نجاح %.1f%%.".format(Locale.US, captaincy.averageActualPoints, captaincy.successRatePercent))
        }

        // 5. أدق المراكز
        val bestPos = positionMap.values.minByOrNull { it.mae }
        if (bestPos != null) {
            notes.add("أعلى المراكز استقراراً ودقة: ${bestPos.position.nameAr} بمتوسط خطأ %.2f نقطة فقط.".format(Locale.US, bestPos.mae))
        }

        return notes
    }

    private fun createEmptyMetrics(modelVersion: String, totalPending: Int = 0): PredictionAccuracyMetrics {
        return PredictionAccuracyMetrics(
            totalEvaluatedPredictions = 0,
            totalPendingPredictions = totalPending,
            meanAbsoluteError = 0.0,
            rootMeanSquareError = 0.0,
            predictionBias = 0.0,
            minutesMeanAbsoluteError = 0.0,
            startProbabilityBrierScore = 0.0,
            baselineMae = 0.0,
            aiValueAdded = 0.0,
            aiWinRatePercent = 0.0,
            captaincyAccuracy = CaptaincyAccuracyMetrics(0, 0.0, 0.0, 0.0, 0, 0),
            positionBreakdown = emptyMap(),
            gameweekTrends = emptyList(),
            confidenceBreakdown = emptyMap(),
            riskBreakdown = emptyMap(),
            recommendationBreakdown = emptyMap(),
            diagnosticNotesAr = listOf("لا توجد جولات مكتملة مقترنة بنتائج رسمية بعد. سيتم احتساب مؤشرات الدقة فور انتهاء الجولة القادمة ومقارنة التوقعات بالنتائج الفعلية."),
            modelVersion = modelVersion
        )
    }
}

