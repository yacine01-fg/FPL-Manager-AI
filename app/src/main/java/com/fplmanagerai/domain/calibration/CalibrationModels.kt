package com.fplmanagerai.domain.calibration

import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import java.util.Locale

/**
 * حالة إصدار نموذج التوقع
 */
enum class ModelVersionStatus(val titleAr: String, val descriptionAr: String) {
    BASELINE("خط الأساس الافتراضي", "النموذج المرجعي الخام غير المعاير"),
    ACTIVE("نشط ومعتمد", "النموذج المعاير النشط المستخدم في توليد التوقعات الحالية"),
    ARCHIVED("مؤرشف", "إصدار سابق تم استبداله بإصدار أحدث"),
    ROLLED_BACK("مسترجع", "إصدار تم إلغاء تفعيله والرجوع عنه بسبب تراجع الدقة"),
    CANDIDATE("مرشح للاعتماد", "إصدار تحت التقييم على عينة التحقق"),
    REJECTED("مرفوض", "فشل النموذج المرشح في تحقيق تفوق إحصائي على خط الأساس"),
    INSUFFICIENT_DATA("بيانات غير كافية", "حجم العينة الحالي لا يسمح بالمعايرة الآمنة")
}

/**
 * أوزان العوامل التنبؤية المعتمدة في النموذج
 */
data class PredictionWeights(
    val formWeight: Double = 0.25,                 // الفورمة الحالية (المدى الآمن: 0.10 - 0.45)
    val productionWeight: Double = 0.35,           // الإنتاجية الهجومية/الدفاعية المتوقعة (0.20 - 0.50)
    val pointsPer90Weight: Double = 0.20,          // معدل النقاط لكل 90 دقيقة (0.10 - 0.35)
    val fixtureDifficultyWeight: Double = 0.10,    // صعوبة الجولة والملعب (0.05 - 0.30)
    val availabilityWeight: Double = 0.10,         // الجاهزية الطبية واستقرار الدقائق (0.05 - 0.25)
    val attackingWeight: Double = 0.55,            // معامل الأثر الهجومي (0.35 - 0.70)
    val defensiveWeight: Double = 0.45,            // معامل الأثر الدفاعي (0.30 - 0.65)
    val homeAdvantageMultiplier: Double = 1.08,    // مضاعف الأرض (1.00 - 1.25)
    val awayPenaltyMultiplier: Double = 0.94       // خصم اللعب خارج الأرض (0.80 - 1.00)
) {
    companion object {
        val DEFAULT_BASELINE = PredictionWeights()

        // الحدود الصارمة للأوزان لمنع القيم العشوائية أو غير المنطقية
        const val MIN_FORM_WEIGHT = 0.10
        const val MAX_FORM_WEIGHT = 0.45

        const val MIN_PRODUCTION_WEIGHT = 0.20
        const val MAX_PRODUCTION_WEIGHT = 0.50

        const val MIN_PP90_WEIGHT = 0.10
        const val MAX_PP90_WEIGHT = 0.35

        const val MIN_FDR_WEIGHT = 0.05
        const val MAX_FDR_WEIGHT = 0.30

        const val MIN_AVAILABILITY_WEIGHT = 0.05
        const val MAX_AVAILABILITY_WEIGHT = 0.25
    }

    /**
     * التحقق من سلامة الأوزان وضمان عدم وجود قيم سلبية أو غير منطقية
     */
    fun isValid(): Boolean {
        if (formWeight < MIN_FORM_WEIGHT || formWeight > MAX_FORM_WEIGHT) return false
        if (productionWeight < MIN_PRODUCTION_WEIGHT || productionWeight > MAX_PRODUCTION_WEIGHT) return false
        if (pointsPer90Weight < MIN_PP90_WEIGHT || pointsPer90Weight > MAX_PP90_WEIGHT) return false
        if (fixtureDifficultyWeight < MIN_FDR_WEIGHT || fixtureDifficultyWeight > MAX_FDR_WEIGHT) return false
        if (availabilityWeight < MIN_AVAILABILITY_WEIGHT || availabilityWeight > MAX_AVAILABILITY_WEIGHT) return false
        if (attackingWeight <= 0.0 || defensiveWeight <= 0.0) return false
        if (homeAdvantageMultiplier < 1.0 || awayPenaltyMultiplier > 1.0) return false
        return true
    }

    /**
     * تطبيع أوزان الأداء الأساسية بحيث يكون مجموعها 1.0
     */
    fun normalized(): PredictionWeights {
        val sum = formWeight + productionWeight + pointsPer90Weight + fixtureDifficultyWeight + availabilityWeight
        if (sum <= 0.0) return DEFAULT_BASELINE
        val normAttDef = attackingWeight + defensiveWeight
        return copy(
            formWeight = Math.round((formWeight / sum) * 100.0) / 100.0,
            productionWeight = Math.round((productionWeight / sum) * 100.0) / 100.0,
            pointsPer90Weight = Math.round((pointsPer90Weight / sum) * 100.0) / 100.0,
            fixtureDifficultyWeight = Math.round((fixtureDifficultyWeight / sum) * 100.0) / 100.0,
            availabilityWeight = Math.round((availabilityWeight / sum) * 100.0) / 100.0,
            attackingWeight = if (normAttDef > 0) Math.round((attackingWeight / normAttDef) * 100.0) / 100.0 else 0.55,
            defensiveWeight = if (normAttDef > 0) Math.round((defensiveWeight / normAttDef) * 100.0) / 100.0 else 0.45
        )
    }
}

/**
 * معاملات المعايرة التجريبية (Calibration Factors) المستخلصة من تحليل الانحرافات المتكررة
 */
data class SegmentCalibrationFactors(
    // مضاعفات النقاط المتوقعة حسب المركز لإزالة الانحياز (Bias Correction)
    val gkpMultiplier: Double = 1.00,              // الحراس (0.80 - 1.20)
    val defMultiplier: Double = 1.00,              // الدفاع (0.80 - 1.20)
    val midMultiplier: Double = 1.00,              // الوسط (0.80 - 1.20)
    val fwdMultiplier: Double = 1.00,              // الهجوم (0.80 - 1.20)

    // إزاحة الانحياز المباشرة (Additive Bias Offset)
    val gkpBiasOffset: Double = 0.00,
    val defBiasOffset: Double = 0.00,
    val midBiasOffset: Double = 0.00,
    val fwdBiasOffset: Double = 0.00,

    // خصم دقائق اللعب حسب مستوى المخاطرة لتقليل الخطأ في المداورة
    val lowRiskMinutesMult: Double = 1.00,
    val mediumRiskMinutesMult: Double = 0.94,      // تقليل طفيف للمخاطرة المتوسطة
    val highRiskMinutesMult: Double = 0.78,        // تخفيض احترازي للشكوك الطبية والمداورة

    // معايرة احتمالية البدء (Platt Scaling parameters)
    val startProbScale: Double = 1.00,
    val startProbOffset: Double = 0.00,

    // معامل تقليص الثقة (Confidence Damping) لتفادي الثقة المفرطة
    val confidenceDamping: Double = 1.00
) {
    companion object {
        val DEFAULT_NEUTRAL = SegmentCalibrationFactors()
    }

    fun getMultiplierForPosition(position: PlayerPosition): Double = when (position) {
        PlayerPosition.GKP -> gkpMultiplier
        PlayerPosition.DEF -> defMultiplier
        PlayerPosition.MID -> midMultiplier
        PlayerPosition.FWD -> fwdMultiplier
    }

    fun getBiasOffsetForPosition(position: PlayerPosition): Double = when (position) {
        PlayerPosition.GKP -> gkpBiasOffset
        PlayerPosition.DEF -> defBiasOffset
        PlayerPosition.MID -> midBiasOffset
        PlayerPosition.FWD -> fwdBiasOffset
    }

    fun getMinutesMultiplierForRisk(risk: RiskLevel): Double = when (risk) {
        RiskLevel.LOW -> lowRiskMinutesMult
        RiskLevel.MEDIUM -> mediumRiskMinutesMult
        RiskLevel.HIGH -> highRiskMinutesMult
        RiskLevel.CRITICAL -> 0.0
    }
}

/**
 * مقاييس التحقق الإحصائي لنموذج المعايرة
 */
data class ModelValidationMetrics(
    val mae: Double,
    val rmse: Double,
    val bias: Double,
    val minutesMae: Double,
    val brierScore: Double,
    val liftVsBaseline: Double,           // القيمة الإيجابية تعني خفض الخطأ مقارنة بخط الأساس
    val winRateVsBaseline: Double
) {
    val isBetterThanBaseline: Boolean
        get() = liftVsBaseline > 0.02
}

/**
 * نموذج إصدار الخوارزمية مع سجل التتبع والإحصائيات
 */
data class ModelVersion(
    val id: String,                               // المعرف الفريد مثل "v2.0-baseline" أو "v2.1-calibrated-gw11"
    val displayNameAr: String,                    // اسم العرض باللغة العربية
    val createdAt: Long = System.currentTimeMillis(),
    val calibrationDatasetSize: Int = 0,          // عدد التوقعات في عينة المعايرة
    val validationDatasetSize: Int = 0,           // عدد التوقعات في عينة التحقق المستقلة
    val weights: PredictionWeights = PredictionWeights.DEFAULT_BASELINE,
    val calibrationFactors: SegmentCalibrationFactors = SegmentCalibrationFactors.DEFAULT_NEUTRAL,
    val validationMetrics: ModelValidationMetrics? = null,
    val baselineMetrics: ModelValidationMetrics? = null,
    val previousVersionId: String? = null,
    val status: ModelVersionStatus = ModelVersionStatus.BASELINE,
    val changeReasonAr: String = "النموذج المرجعي الأساسي"
) {
    val isBaseline: Boolean
        get() = id == DEFAULT_BASELINE_ID

    companion object {
        const val DEFAULT_BASELINE_ID = "v2.0-baseline"

        fun createBaseline(): ModelVersion = ModelVersion(
            id = DEFAULT_BASELINE_ID,
            displayNameAr = "نموذج خط الأساس (v2.0)",
            createdAt = System.currentTimeMillis(),
            calibrationDatasetSize = 0,
            validationDatasetSize = 0,
            weights = PredictionWeights.DEFAULT_BASELINE,
            calibrationFactors = SegmentCalibrationFactors.DEFAULT_NEUTRAL,
            validationMetrics = null,
            baselineMetrics = null,
            previousVersionId = null,
            status = ModelVersionStatus.BASELINE,
            changeReasonAr = "النموذج المرجعي الافتراضي للمحرك قبل أي معايرة ذاتية"
        )
    }
}

/**
 * ملخص حالة كفاية البيانات للبدء في المعايرة الآمنة
 */
data class CalibrationDatasetSummary(
    val totalEvaluatedRecords: Int,
    val evaluatedGameweeksCount: Int,
    val evaluatedGameweeks: List<Int>,
    val minimumRequiredRecords: Int = MIN_SAMPLE_SIZE,
    val minimumRequiredGameweeks: Int = MIN_GAMEWEEKS,
    val isSufficient: Boolean = totalEvaluatedRecords >= minimumRequiredRecords && evaluatedGameweeksCount >= minimumRequiredGameweeks
) {
    val readinessPercent: Int = (
        ((totalEvaluatedRecords.toDouble() / minimumRequiredRecords).coerceAtMost(1.0) * 0.6 +
         (evaluatedGameweeksCount.toDouble() / minimumRequiredGameweeks).coerceAtMost(1.0) * 0.4) * 100
    ).toInt().coerceIn(0, 100)

    val sufficiencyStatusAr: String = when {
        isSufficient -> "العينة كافية للمعايرة والتحسين الذاتي ($totalEvaluatedRecords تقييماً عبر $evaluatedGameweeksCount جولات)"
        evaluatedGameweeksCount < minimumRequiredGameweeks -> "غير كافية: تتطلب جولات متعددة ($evaluatedGameweeksCount / $minimumRequiredGameweeks) لمنع التحيز لجولة واحدة"
        else -> "غير كافية: يلزم $minimumRequiredRecords تقييماً على الأقل (المتوفر حالياً: $totalEvaluatedRecords)"
    }

    companion object {
        const val MIN_SAMPLE_SIZE = 20        // الحد الأدنى من التقييمات المحفوظة
        const val MIN_GAMEWEEKS = 2           // الحد الأدنى من الجولات المنتهية
    }
}

/**
 * نتيجة عملية المعايرة واختبار النموذج المرشح
 */
data class CalibrationRunResult(
    val status: ModelVersionStatus,
    val candidateModel: ModelVersion?,
    val isApproved: Boolean,
    val liftAchieved: Double = 0.0,
    val messageAr: String,
    val diagnosticsAr: List<String> = emptyList()
)

