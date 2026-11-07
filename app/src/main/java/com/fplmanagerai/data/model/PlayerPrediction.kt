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

data class GameweekPrediction(
    val gameweek: Int,
    val opponentShortName: String,
    val isHome: Boolean,
    val difficulty: Int,
    val expectedPoints: Double,
    val floorPoints: Double,
    val ceilingPoints: Double
)

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
    val actualPoints: Int,
    val actualCaptainPoints: Int,
    val confidenceScore: Double,
    val recommendation: TransferRecommendation,
    val recommendationReason: String,
    val analysisScore: Double,
    val performanceScore: Double = analysisScore,
    val reliabilityScore: Double = 75.0,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val goalInvolvementPercent: Double? = null,
    val finishingVariance: Double? = null,
    val calculationSummaryAr: String = "",
    val floorPoints: Double = Math.max(0.0, Math.round((expectedAppearancePoints + Math.min(0.0, expectedDefensivePoints)) * 10.0) / 10.0),
    val ceilingPoints: Double = Math.round((expectedPoints + expectedAttackingPoints * 1.5 + 3.0) * 10.0) / 10.0,
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
