package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ActualChipPerformance
import com.fplmanagerai.data.chips.HistoricalChipRepository
import java.util.Locale

/**
 * Summary metrics of prediction accuracy and historical learning
 */
data class CalibrationMetrics(
    val totalEvaluatedChips: Int,
    val meanAbsoluteError: Double,
    val predictionBias: Double, // positive = overpredicting, negative = underpredicting
    val calibrationMultiplier: Double,
    val accuracyPercent: Int,
    val summaryAr: String
)

/**
 * Historical Learning & Calibration Engine
 * Dynamically improves future chip expected value calibration based on post-gameweek actuals
 * without modifying historical predictions.
 */
class ChipCalibrationEngine(
    private val historicalRepository: HistoricalChipRepository = HistoricalChipRepository()
) {

    /**
     * Calculates current calibration metrics across all evaluated chips
     */
    fun computeMetrics(): CalibrationMetrics {
        val performances = historicalRepository.getAllPerformances().filter { it.isMeasurable }
        if (performances.isEmpty()) {
            return CalibrationMetrics(
                totalEvaluatedChips = 0,
                meanAbsoluteError = 0.0,
                predictionBias = 0.0,
                calibrationMultiplier = 1.0,
                accuracyPercent = 85,
                summaryAr = "لا توجد نتائج سابقة مسجلة بعد؛ يتم استخدام معاملات المعايرة القياسية (1.0x)."
            )
        }

        val totalSamples = performances.size
        val mae = performances.map { it.absoluteError }.average()
        val bias = performances.map { it.predictionError }.average()

        val snapshots = historicalRepository.getAllSnapshots()
        val snapshotMap = snapshots.associateBy { it.snapshotId }

        var sumPredicted = 0.0
        var sumActual = 0.0
        for (perf in performances) {
            val snap = snapshotMap[perf.snapshotId]
            if (snap != null && snap.predictedIncrementalGain > 0.0) {
                sumPredicted += snap.predictedIncrementalGain
                sumActual += perf.actualIncrementalBenefit.coerceAtLeast(0.0)
            }
        }

        // Calibration multiplier: actual / predicted, bounded between 0.75 and 1.25 to prevent wild swings
        val rawMultiplier = if (sumPredicted > 0.0) sumActual / sumPredicted else 1.0
        val boundedMultiplier = rawMultiplier.coerceIn(0.75, 1.25)
        val roundedMultiplier = Math.round(boundedMultiplier * 100.0) / 100.0

        val accuracy = ((1.0 - (mae / 15.0).coerceIn(0.0, 1.0)) * 100).toInt()

        val summary = "تمت معايرة التوقعات استناداً إلى $totalSamples قرارات سابقة: متوسط الخطأ ±%.1f نقطة، معامل المعايرة الحالي %.2fx.".format(
            Locale.US,
            mae,
            roundedMultiplier
        )

        return CalibrationMetrics(
            totalEvaluatedChips = totalSamples,
            meanAbsoluteError = Math.round(mae * 10.0) / 10.0,
            predictionBias = Math.round(bias * 10.0) / 10.0,
            calibrationMultiplier = roundedMultiplier,
            accuracyPercent = accuracy,
            summaryAr = summary
        )
    }

    /**
     * Calibrates raw expected point gains using learned historical bias/multiplier
     */
    fun calibrateExpectedGain(rawGain: Double, chip: ChipType): Double {
        if (rawGain <= 0.0) return 0.0
        val multiplier = computeMetrics().calibrationMultiplier
        val calibrated = rawGain * multiplier
        return Math.round(calibrated * 10.0) / 10.0
    }
}
