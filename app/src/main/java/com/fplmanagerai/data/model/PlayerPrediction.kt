package com.fplmanagerai.data.model

enum class TransferRecommendation(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String
) {
    BUY("شراء", "Buy", "أداء تصاعدي وجدول مباريات ملائم وعائد نقاط متوقع مرتفع"),
    HOLD("إبقاء", "Hold", "أداء مستقر أو دور أساسي مضمون يستحق البقاء في التشكيلة"),
    SELL("بيع", "Sell", "مخاطر غياب، صعوبة جدول، أو تراجع ملحوظ في المخرجات الهجومية/الدفاعية")
}

enum class RiskLevel(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String
) {
    LOW("مخاطرة منخفضة", "Low Risk", "لاعب أساسي مضمون وجاهز بدنياً بنسبة 100%"),
    MEDIUM("مخاطرة متوسطة", "Medium Risk", "جاهز مع احتمال طفيف للمداورة أو مواجهة متوسطة الصعوبة"),
    HIGH("مخاطرة عالية", "High Risk", "شكوك طبية أو تراجع في دقائق اللعب ومواجهات صعبة"),
    CRITICAL("مخاطرة حرجة", "Critical Risk", "مصاب أو موقوف رسمياً ومستبعد تماماً")
}

/**
 * Expected prediction for a specific individual Gameweek (unit of prediction in FPL)
 * Supports Single Gameweeks, Double Gameweeks (DGW), and Blank Gameweeks (BGW).
 */
data class GameweekPrediction(
    val gameweek: Int,
    val opponentShortName: String,
    val isHome: Boolean,
    val difficulty: Int,
    val expectedPoints: Double,
    val floorPoints: Double,
    val ceilingPoints: Double,
    val matchCount: Int = 1,
    val isDoubleGameweek: Boolean = false,
    val isBlankGameweek: Boolean = false
)

/**
 * Comprehensive Player Prediction model
 * Clearly distinguishes between:
 * - Confidence: Model certainty based on data volume, availability certainty, and fixture confirmation.
 * - Reliability: Player consistency and starter baseline stability.
 * - Risk: Likelihood of adverse events (rotation, early substitution, injuries).
 * - Actual Points: Actual points scored in a completed gameweek (null for upcoming/future gameweeks).
 */
data class PlayerPrediction(
    val playerId: Int,
    val gameweek: Int,
    val expectedPoints: Double,
    val expectedMinutes: Double,
    val startProbability: Double,
    val expectedAttackingPoints: Double,
    val expectedDefensivePoints: Double,
    val expectedCleanSheetPoints: Double,
    val expectedBonusPoints: Double,
    val expectedAppearancePoints: Double,
    val predictedCaptainPoints: Double,
    // Strictly separated: actual gameweek points (null for upcoming gameweeks)
    val actualPoints: Int? = null,
    val actualCaptainPoints: Int? = null,
    // Confidence: Confidence in prediction quality
    val confidenceScore: Double,
    val recommendation: TransferRecommendation,
    val recommendationReason: String,
    val analysisScore: Double,
    val performanceScore: Double = analysisScore,
    // Reliability: Historical baseline stability
    val reliabilityScore: Double = 75.0,
    // Risk: Probability of negative outcomes
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val goalInvolvementPercent: Double? = null,
    val finishingVariance: Double? = null,
    val calculationSummaryAr: String = "",
    // Floor: Bounded minimum expectation
    val floorPoints: Double = Math.max(0.0, Math.round((expectedAppearancePoints + Math.min(0.0, expectedDefensivePoints)) * 10.0) / 10.0),
    // Ceiling: Statistical 90th percentile explosive potential
    val ceilingPoints: Double = Math.min(26.0, Math.round((expectedPoints + Math.sqrt(expectedPoints.coerceAtLeast(1.0) * 2.4) + 2.0) * 10.0) / 10.0),
    val uncertaintyMargin: Double = Math.round((ceilingPoints - floorPoints) / 2.0 * 10.0) / 10.0,
    val horizonPredictions: List<GameweekPrediction> = emptyList()
) {
    val roundedExpectedPoints: Double
        get() = Math.round(expectedPoints * 10.0) / 10.0

    val roundedPredictedCaptainPoints: Double
        get() = Math.round(predictedCaptainPoints * 10.0) / 10.0

    val formattedRange: String
        get() = "%.1f - %.1f".format(java.util.Locale.US, floorPoints, ceilingPoints)
}
