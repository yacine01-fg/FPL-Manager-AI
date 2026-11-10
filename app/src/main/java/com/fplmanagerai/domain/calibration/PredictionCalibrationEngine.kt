package com.fplmanagerai.domain.calibration

import com.fplmanagerai.data.model.AccuracyComparisonRecord
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * محرك معايرة وتحسين التوقعات ذاتياً (Prediction Calibration & Self-Improvement Engine)
 * يعتمد على البيانات الفعلية المحفوظة في PredictionAccuracyRepository ويطبق معايير Walk-Forward Validation
 * الصارمة لمنع ظاهرة الانحدار الزائد (Overfitting).
 */
class PredictionCalibrationEngine {

    /**
     * فحص كفاية حجم العينة والبيانات التاريخية لإجراء المعايرة
     */
    fun checkDatasetSufficiency(records: List<AccuracyComparisonRecord>): CalibrationDatasetSummary {
        val evaluatedGws = records.map { it.snapshot.gameweek }.distinct().sorted()
        return CalibrationDatasetSummary(
            totalEvaluatedRecords = records.size,
            evaluatedGameweeksCount = evaluatedGws.size,
            evaluatedGameweeks = evaluatedGws
        )
    }

    /**
     * تشغيل خوارزمية المعايرة والتحسين التكيفي مع دعم التحقق الزمني عبر جولات متعددة
     */
    fun calibrateAndOptimize(
        records: List<AccuracyComparisonRecord>,
        currentModel: ModelVersion
    ): CalibrationRunResult {
        // 1. فحص كفاية العينة
        val sufficiency = checkDatasetSufficiency(records)
        if (!sufficiency.isSufficient) {
            return CalibrationRunResult(
                status = ModelVersionStatus.INSUFFICIENT_DATA,
                candidateModel = null,
                isApproved = false,
                messageAr = sufficiency.sufficiencyStatusAr,
                diagnosticsAr = listOf(
                    "يتطلب المحرك الذكي بيانات مكتملة عبر جولات متعددة لضمان عدم تأثره بتقلبات جولة استثنائية واحدة.",
                    "المتوفر حالياً: ${records.size} تقييماً عبر ${sufficiency.evaluatedGameweeksCount} جولات.",
                    "الحد الأدنى الآمن: ${CalibrationDatasetSummary.MIN_SAMPLE_SIZE} تقييماً عبر ${CalibrationDatasetSummary.MIN_GAMEWEEKS} جولات على الأقل."
                )
            )
        }

        // 2. تقسيم البيانات زمنياً (Walk-Forward Validation Split)
        // عند توفر 4 جولات أو أكثر، يتم حجز آخر جولتين كعينة تحقق متعددة النوافذ لمنع الاعتماد على جولة وحيدة
        val sortedGws = records.map { it.snapshot.gameweek }.distinct().sorted()
        val validationGws = if (sortedGws.size >= 4) {
            sortedGws.takeLast(2)
        } else {
            listOf(sortedGws.last())
        }
        val calibrationGws = sortedGws.filter { it !in validationGws }

        val calibrationSet = records.filter { it.snapshot.gameweek in calibrationGws }
        val validationSet = records.filter { it.snapshot.gameweek in validationGws }

        if (calibrationSet.isEmpty() || validationSet.isEmpty()) {
            return CalibrationRunResult(
                status = ModelVersionStatus.INSUFFICIENT_DATA,
                candidateModel = null,
                isApproved = false,
                messageAr = "تعذر إتمام تقسيم التحقق الزمني (Walk-forward split) لعدم اكتمال عينة التحقق المستقلة.",
                diagnosticsAr = listOf("يجب توفر جولة واحدة مستقلة على الأقل كعينة تحقق بعد جولات المعايرة.")
            )
        }

        // 3. احتساب معاملات المعايرة حسب المراكز ومستويات المخاطرة ومعايرة Brier Score لاحتمالية البدء
        val candidateFactors = computeSegmentCalibrationFactors(calibrationSet)

        // 4. تحسين تكيفي لأوزان العوامل التنبؤية بالارتباط بالأخطاء الفعلية
        val candidateWeights = optimizePredictionWeights(calibrationSet, currentModel.weights)

        // 5. تقييم النموذج المرشح ونموذج خط الأساس على عينة التحقق المستقلة (Holdout Validation Set)
        val baselineMetrics = evaluateOnValidationSet(validationSet, currentModel.weights, SegmentCalibrationFactors.DEFAULT_NEUTRAL)
        val candidateMetrics = evaluateOnValidationSet(validationSet, candidateWeights, candidateFactors, baselineMae = baselineMetrics.mae)

        val lift = Math.round((baselineMetrics.mae - candidateMetrics.mae) * 100.0) / 100.0
        val isBetter = lift > 0.01 && abs(candidateMetrics.bias) <= abs(baselineMetrics.bias) + 0.25

        // إنشاء معرف ورقم إصدار جديد
        val validationGwsLabel = validationGws.joinToString("-") { "gw$it" }
        val newVersionId = "v2.1-calibrated-$validationGwsLabel"
        val candidateModel = ModelVersion(
            id = newVersionId,
            displayNameAr = "النموذج المعاير (${validationGws.joinToString(", ") { "GW$it" }})",
            createdAt = System.currentTimeMillis(),
            calibrationDatasetSize = calibrationSet.size,
            validationDatasetSize = validationSet.size,
            weights = candidateWeights,
            calibrationFactors = candidateFactors,
            validationMetrics = candidateMetrics.copy(liftVsBaseline = lift),
            baselineMetrics = baselineMetrics,
            previousVersionId = currentModel.id,
            status = if (isBetter) ModelVersionStatus.CANDIDATE else ModelVersionStatus.REJECTED,
            changeReasonAr = if (isBetter) {
                "معايرة ناجحة أثبتت خفض متوسط الخطأ بنسبة +$lift نقطة على عينة التحقق (${validationGws.joinToString(", ") { "الجولة $it" }})"
            } else {
                "فشل النموذج المرشح في تحسين دقة التحقق (الفارق: $lift نقطة)، تم الاحتفاظ بخط الأساس"
            }
        )

        val diagnostics = generateCalibrationDiagnostics(
            calibrationSize = calibrationSet.size,
            validationSize = validationSet.size,
            validationGws = validationGws,
            baselineMetrics = baselineMetrics,
            candidateMetrics = candidateMetrics,
            lift = lift,
            factors = candidateFactors,
            weights = candidateWeights,
            isApproved = isBetter
        )

        return CalibrationRunResult(
            status = if (isBetter) ModelVersionStatus.CANDIDATE else ModelVersionStatus.REJECTED,
            candidateModel = candidateModel,
            isApproved = isBetter,
            liftAchieved = lift,
            messageAr = if (isBetter) {
                "تمت المعايرة بنجاح وأثبت النموذج تفوقاً بمقدار +$lift نقطة فوق خط الأساس عبر عينة تحقق مستقلة."
            } else {
                "لم يحقق النموذج المرشح تفوقاً كافياً (+0.01) على عينة التحقق المستقلة؛ تم رفض التعديل حمايةً من Overfitting."
            },
            diagnosticsAr = diagnostics
        )
    }

    /**
     * احتساب معاملات المعايرة لكل مركز، ومستوى مخاطرة، واستخدام Brier Score في احتمالية البدء
     */
    private fun computeSegmentCalibrationFactors(records: List<AccuracyComparisonRecord>): SegmentCalibrationFactors {
        fun calcPositionMultiplierAndOffset(pos: PlayerPosition): Pair<Double, Double> {
            val subset = records.filter { it.snapshot.position == pos }
            if (subset.size < 2) return 1.0 to 0.0

            val meanExpected = subset.map { it.snapshot.expectedPoints }.average()
            val meanActual = subset.map { it.actual.actualPoints.toDouble() }.average()
            val bias = meanExpected - meanActual // موجب = مبالغة في التوقع

            val mult = if (meanExpected > 0.5) {
                (1.0 - (bias / meanExpected) * 0.40).coerceIn(0.85, 1.15)
            } else 1.0

            val offset = (-0.25 * bias).coerceIn(-0.6, 0.6)
            return Math.round(mult * 100.0) / 100.0 to Math.round(offset * 100.0) / 100.0
        }

        val (gkpMult, gkpOff) = calcPositionMultiplierAndOffset(PlayerPosition.GKP)
        val (defMult, defOff) = calcPositionMultiplierAndOffset(PlayerPosition.DEF)
        val (midMult, midOff) = calcPositionMultiplierAndOffset(PlayerPosition.MID)
        val (fwdMult, fwdOff) = calcPositionMultiplierAndOffset(PlayerPosition.FWD)

        // معايرة دقائق اللعب حسب مستوى المخاطرة الفعلي
        val highRiskRecords = records.filter { it.snapshot.riskLevel == RiskLevel.HIGH }
        val highRiskMinsMult = if (highRiskRecords.size >= 2) {
            val meanExp = highRiskRecords.map { it.snapshot.expectedMinutes }.average()
            val meanAct = highRiskRecords.map { it.actual.actualMinutes.toDouble() }.average()
            if (meanExp > 10.0) (meanAct / meanExp).coerceIn(0.65, 0.90) else 0.78
        } else 0.78

        val medRiskRecords = records.filter { it.snapshot.riskLevel == RiskLevel.MEDIUM }
        val medRiskMinsMult = if (medRiskRecords.size >= 2) {
            val meanExp = medRiskRecords.map { it.snapshot.expectedMinutes }.average()
            val meanAct = medRiskRecords.map { it.actual.actualMinutes.toDouble() }.average()
            if (meanExp > 10.0) (meanAct / meanExp).coerceIn(0.85, 0.98) else 0.94
        } else 0.94

        // معايرة احتمالية البدء أساسياً بناءً على مقارنة التردد الفعلي واحتساب Brier Score
        val (startProbScale, startProbOffset) = if (records.size >= 5) {
            val meanPredProb = records.map { it.snapshot.startProbability }.average()
            val actualStartRatio = records.count { it.actual.started }.toDouble() / records.size
            val probBias = meanPredProb - actualStartRatio // موجب = تفاؤل زائد في نسبة البدء أساسياً

            val scale = if (meanPredProb > 0.1) {
                (actualStartRatio / meanPredProb).coerceIn(0.80, 1.20)
            } else 1.0
            val offset = (-0.25 * probBias).coerceIn(-0.10, 0.10)
            Math.round(scale * 100.0) / 100.0 to Math.round(offset * 100.0) / 100.0
        } else {
            1.0 to 0.0
        }

        // معايرة الثقة بناءً على جودة التوقعات الفعلية (MAE) بدلاً من نسبة ثابتة
        val highConfRecords = records.filter { it.snapshot.confidenceScore >= 0.70 }
        val dynamicConfidenceDamping = if (highConfRecords.size >= 5) {
            val highConfMae = highConfRecords.map { it.absolutePointsError }.average()
            if (highConfMae > 2.2) {
                (2.2 / highConfMae).coerceIn(0.75, 1.0)
            } else 1.0
        } else 0.95

        return SegmentCalibrationFactors(
            gkpMultiplier = gkpMult,
            defMultiplier = defMult,
            midMultiplier = midMult,
            fwdMultiplier = fwdMult,
            gkpBiasOffset = gkpOff,
            defBiasOffset = defOff,
            midBiasOffset = midOff,
            fwdBiasOffset = fwdOff,
            lowRiskMinutesMult = 1.0,
            mediumRiskMinutesMult = Math.round(medRiskMinsMult * 100.0) / 100.0,
            highRiskMinutesMult = Math.round(highRiskMinsMult * 100.0) / 100.0,
            startProbScale = startProbScale,
            startProbOffset = startProbOffset,
            confidenceDamping = Math.round(dynamicConfidenceDamping * 100.0) / 100.0
        )
    }

    /**
     * تحسين أوزان العوامل التنبؤية ضمن القيود المنطقية بالارتباط بمصادر الأخطاء الفعلية
     */
    private fun optimizePredictionWeights(
        records: List<AccuracyComparisonRecord>,
        baseWeights: PredictionWeights
    ): PredictionWeights {
        if (records.size < 5) return baseWeights

        var newForm = baseWeights.formWeight
        var newProd = baseWeights.productionWeight
        var newPp90 = baseWeights.pointsPer90Weight
        var newFdr = baseWeights.fixtureDifficultyWeight
        var newAvail = baseWeights.availabilityWeight

        // 1. فحص انحياز الفورمة الحالية
        val highFormRecords = records.filter { it.snapshot.expectedPoints >= 5.5 }
        if (highFormRecords.isNotEmpty()) {
            val formBias = highFormRecords.map { it.pointsError }.average()
            if (formBias < -0.4) {
                // خفض في التوقع: نزيد وزن الفورمة قليلاً
                newForm = (newForm + 0.02).coerceIn(PredictionWeights.MIN_FORM_WEIGHT, PredictionWeights.MAX_FORM_WEIGHT)
                newPp90 = (newPp90 - 0.01).coerceIn(PredictionWeights.MIN_PP90_WEIGHT, PredictionWeights.MAX_PP90_WEIGHT)
            } else if (formBias > 0.4) {
                // مبالغة في التوقع: نخفض وزن الفورمة ونرفع وزن الإنتاجية الثابتة
                newForm = (newForm - 0.02).coerceIn(PredictionWeights.MIN_FORM_WEIGHT, PredictionWeights.MAX_FORM_WEIGHT)
                newProd = (newProd + 0.02).coerceIn(PredictionWeights.MIN_PRODUCTION_WEIGHT, PredictionWeights.MAX_PRODUCTION_WEIGHT)
            }
        }

        // 2. فحص أثر صعوبة المواجهة (Fixture Difficulty) للدفاع
        val defRecords = records.filter { it.snapshot.position == PlayerPosition.DEF || it.snapshot.position == PlayerPosition.GKP }
        if (defRecords.size >= 4) {
            val defBias = defRecords.map { it.pointsError }.average()
            if (defBias > 0.5) {
                // مبالغة في توقع الكلين شيت: نرفع وزن صعوبة المواجهة للحد من التفاؤل
                newFdr = (newFdr + 0.02).coerceIn(PredictionWeights.MIN_FDR_WEIGHT, PredictionWeights.MAX_FDR_WEIGHT)
            }
        }

        // 3. فحص خطأ دقائق اللعب لضبط وزن الجاهزية
        val avgMinsError = records.map { it.absoluteMinutesError }.average()
        if (avgMinsError > 15.0) {
            newAvail = (newAvail + 0.02).coerceIn(PredictionWeights.MIN_AVAILABILITY_WEIGHT, PredictionWeights.MAX_AVAILABILITY_WEIGHT)
        }

        return baseWeights.copy(
            formWeight = newForm,
            productionWeight = newProd,
            pointsPer90Weight = newPp90,
            fixtureDifficultyWeight = newFdr,
            availabilityWeight = newAvail
        ).normalized()
    }

    /**
     * تقييم أداء نموذج على عينة التحقق المستقلة
     */
    private fun evaluateOnValidationSet(
        validationSet: List<AccuracyComparisonRecord>,
        weights: PredictionWeights,
        factors: SegmentCalibrationFactors,
        baselineMae: Double = 0.0
    ): ModelValidationMetrics {
        val n = validationSet.size.toDouble()

        var sumAbsError = 0.0
        var sumSqError = 0.0
        var sumBias = 0.0
        var sumMinsError = 0.0
        var sumBrier = 0.0
        var winsVsBase = 0

        for (record in validationSet) {
            val pos = record.snapshot.position
            val mult = factors.getMultiplierForPosition(pos)
            val offset = factors.getBiasOffsetForPosition(pos)
            val minsMult = factors.getMinutesMultiplierForRisk(record.snapshot.riskLevel)
            val startProb = ((record.snapshot.startProbability * factors.startProbScale) + factors.startProbOffset).coerceIn(0.0, 1.0)

            // تطبيق المعايرة على النقاط المتوقعة
            val calibratedXpts = ((record.snapshot.expectedPoints * mult) + offset).coerceAtLeast(0.0)
            val calibratedMins = (record.snapshot.expectedMinutes * minsMult).coerceAtLeast(0.0)

            val error = calibratedXpts - record.actual.actualPoints.toDouble()
            val absError = abs(error)

            sumAbsError += absError
            sumSqError += (error * error)
            sumBias += error
            sumMinsError += abs(calibratedMins - record.actual.actualMinutes.toDouble())
            sumBrier += (startProb - (if (record.actual.started) 1.0 else 0.0)).pow(2.0)

            if (absError <= record.baselinePointsError) {
                winsVsBase++
            }
        }

        val mae = sumAbsError / n
        val rmse = sqrt(sumSqError / n)
        val bias = sumBias / n
        val minsMae = sumMinsError / n
        val brier = sumBrier / n
        val winRate = (winsVsBase / n) * 100.0
        val lift = if (baselineMae > 0.0) baselineMae - mae else 0.0

        return ModelValidationMetrics(
            mae = Math.round(mae * 100.0) / 100.0,
            rmse = Math.round(rmse * 100.0) / 100.0,
            bias = Math.round(bias * 100.0) / 100.0,
            minutesMae = Math.round(minsMae * 10.0) / 10.0,
            brierScore = Math.round(brier * 1000.0) / 1000.0,
            liftVsBaseline = Math.round(lift * 100.0) / 100.0,
            winRateVsBaseline = Math.round(winRate * 10.0) / 10.0
        )
    }

    /**
     * توليد التقرير التشخيصي التفصيلي باللغة العربية
     */
    private fun generateCalibrationDiagnostics(
        calibrationSize: Int,
        validationSize: Int,
        validationGws: List<Int>,
        baselineMetrics: ModelValidationMetrics,
        candidateMetrics: ModelValidationMetrics,
        lift: Double,
        factors: SegmentCalibrationFactors,
        weights: PredictionWeights,
        isApproved: Boolean
    ): List<String> {
        val notes = mutableListOf<String>()

        val gwsStr = validationGws.joinToString(", ") { "الجولة $it" }
        notes.add("تقسيم العينة: $calibrationSize سجلاً للمعايرة، و$validationSize سجلاً مستقلاً للتحقق عبر ($gwsStr) بأسلوب Walk-Forward.")
        notes.add("أداء خط الأساس على عينة التحقق: MAE = ±${baselineMetrics.mae}، الانحياز = %+.2f، Brier Score = %.3f".format(Locale.US, baselineMetrics.bias, baselineMetrics.brierScore))
        notes.add("أداء النموذج المعاير على عينة التحقق: MAE = ±${candidateMetrics.mae}، الانحياز = %+.2f، Brier Score = %.3f".format(Locale.US, candidateMetrics.bias, candidateMetrics.brierScore))

        if (lift > 0.0) {
            notes.add("تحقق تفوق إحصائي موجب (Lift): خفض الخطأ بمقدار +$lift نقطة لكل توقع.")
        } else {
            notes.add("تراجع أو تطابق في الأداء (Lift: $lift): لم يتمكن النموذج المرشح من خفض الخطأ على العينة المستقلة.")
        }

        notes.add("معاملات معايرة المراكز المطبقة: دفاع (x${factors.defMultiplier})، وسط (x${factors.midMultiplier})، هجوم (x${factors.fwdMultiplier})، حراس (x${factors.gkpMultiplier}).")
        notes.add("معايرة احتمالية البدء (Brier Calibrated): Scale = x${factors.startProbScale}، Offset = %+.2f".format(Locale.US, factors.startProbOffset))
        notes.add("خصم دقائق اللعب للمخاطرة العالية: x${factors.highRiskMinutesMult} لتقليص أخطاء المداورة والتبديل المبكر.")
        notes.add("الأوزان التكيفية: الفورمة (${(weights.formWeight * 100).toInt()}%)، الإنتاجية (${(weights.productionWeight * 100).toInt()}%)، صعوبة المواجهة (${(weights.fixtureDifficultyWeight * 100).toInt()}%)، الجاهزية (${(weights.availabilityWeight * 100).toInt()}%).")

        if (isApproved) {
            notes.add("القرار العلمي: النموذج المرشح مؤهل وموصى باعتماده لتقليص الانحرافات المتكررة دون إخلال باستقرار التوقعات.")
        } else {
            notes.add("القرار العلمي: تم رفض الاعتماد التلقائي حفاظاً على موثوقية التوقعات ومنعاً لأي تحسين وهمي غير مثبت إحصائياً.")
        }

        return notes
    }
}
