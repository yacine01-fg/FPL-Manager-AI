package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.model.PlayerModel

/**
 * Official FPL Chip Types
 */
enum class ChipType(
    val code: String,
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String,
    val iconName: String
) {
    TRIPLE_CAPTAIN(
        code = "3xc",
        titleAr = "تريبل كابتن (Triple Captain)",
        titleEn = "Triple Captain",
        descriptionAr = "مضاعفة نقاط الكابتن إلى 3 أضعاف (3x) بدلاً من ضعفين",
        iconName = "Star"
    ),
    BENCH_BOOST(
        code = "bboost",
        titleAr = "بينش بوست (Bench Boost)",
        titleEn = "Bench Boost",
        descriptionAr = "احتساب نقاط بدلاء الدكة الأربعة كاملة وإضافتها لإجمالي نقاط الجولة",
        iconName = "Groups"
    ),
    FREE_HIT(
        code = "freehit",
        titleAr = "فري هيت (Free Hit)",
        titleEn = "Free Hit",
        descriptionAr = "إجراء تبديلات غير محدودة لجولة واحدة، وتعود التشكيلة الأصلية بالكامل في الجولة التالية",
        iconName = "Autorenew"
    ),
    WILDCARD(
        code = "wildcard",
        titleAr = "وايلد كارد (Wildcard)",
        titleEn = "Wildcard",
        descriptionAr = "إعادة بناء وتجديد التشكيلة بالكامل مجاناً وبلا أي خصم نقاط (-4)",
        iconName = "Build"
    )
}

/**
 * Half of the season (Two sets of four chips in official 2026/27 rules)
 */
enum class ChipHalf(
    val setNumber: Int,
    val titleAr: String,
    val titleEn: String,
    val startGameweek: Int,
    val endGameweek: Int
) {
    FIRST_HALF(
        setNumber = 1,
        titleAr = "المجموعة الأولى (الجولات 1-19)",
        titleEn = "Set 1 (GW 1-19)",
        startGameweek = 1,
        endGameweek = 19
    ),
    SECOND_HALF(
        setNumber = 2,
        titleAr = "المجموعة الثانية (الجولات 20-38)",
        titleEn = "Set 2 (GW 20-38)",
        startGameweek = 20,
        endGameweek = 38
    );

    companion object {
        fun fromGameweek(gw: Int): ChipHalf = if (gw <= 19) FIRST_HALF else SECOND_HALF
    }
}

/**
 * Unique identifier for a chip instance in a specific half
 */
data class ChipInstance(
    val chipType: ChipType,
    val half: ChipHalf
) {
    val key: String get() = "${chipType.code}_set${half.setNumber}"
}

/**
 * Comprehensive lifecycle states of an FPL chip
 */
enum class ChipStatus(val titleAr: String) {
    AVAILABLE("متاح للاستخدام"),
    PENDING("معلّق / محدد للجولة القادمة"),
    ACTIVE_THIS_GW("نشط في الجولة الحالية"),
    USED("تم استخدامه مسبقاً"),
    EXPIRED("منتهي الصلاحية")
}

/**
 * Decision verdict produced by the AI Engine
 */
enum class ChipVerdict(val titleAr: String, val shortAr: String) {
    ACTIVATE_NOW("تفعيل الآن في هذه الجولة", "تفعيل الآن"),
    SAVE_FOR_LATER("احتفظ بها لجولة قادمة مواتية", "احتفظ بها"),
    USED_OR_EXPIRED("غير متاحة (مستخدمة أو منتهية)", "غير متاحة")
}

/**
 * AI Confidence assessment
 */
enum class ChipConfidence(val titleAr: String, val scorePercent: Int) {
    VERY_HIGH("ثقة عالية جداً", 92),
    HIGH("ثقة مرتفعة", 78),
    MODERATE("ثقة متوسطة", 55),
    LOW("ثقة منخفضة / مخاطرة", 35)
}

/**
 * Distinguishes confirmed official fixtures from projected double/blank gameweeks
 */
enum class FixtureSchedulingType(val titleAr: String) {
    CONFIRMED("مؤكدة رسمياً"),
    PROJECTED("مباريات متوقعة / مؤجلة")
}

/**
 * Analysis of an individual Gameweek regarding fixtures, blanks, and doubles
 */
data class GameweekFixtureSummary(
    val gameweek: Int,
    val totalFixtures: Int,
    val isDoubleGameweek: Boolean,
    val isBlankGameweek: Boolean,
    val schedulingType: FixtureSchedulingType = FixtureSchedulingType.CONFIRMED,
    val teamsWithDouble: List<Int> = emptyList(),
    val teamsWithBlank: List<Int> = emptyList()
)

/**
 * Result of validating a chip action against official rules
 */
data class ChipValidationResult(
    val isValid: Boolean,
    val ruleViolationAr: String? = null,
    val warningAr: String? = null
)

/**
 * Comprehensive analysis result for a single FPL chip
 */
data class SingleChipAnalysis(
    val chip: ChipType,
    val half: ChipHalf = ChipHalf.FIRST_HALF,
    val status: ChipStatus,
    val verdict: ChipVerdict,
    val recommendedGameweek: Int,
    val alternativeGameweeks: List<Int> = emptyList(),
    val expectedPointsGain: Double,
    val currentGwGain: Double,
    val futureOpportunityGain: Double,
    val confidence: ChipConfidence,
    val primaryReasonAr: String,
    val detailedPointsAr: List<String> = emptyList(),
    val targetPlayer: PlayerModel? = null,
    val affectedPlayers: List<PlayerModel> = emptyList(),
    val temporarySquad: List<PlayerModel>? = null,
    val arbitrationNoteAr: String? = null,
    val expirationWarningAr: String? = null,
    val isConfirmedFixture: Boolean = true
) {
    val roundedExpectedGain: Double
        get() = Math.round(expectedPointsGain * 10.0) / 10.0

    val isRecommendedNow: Boolean
        get() = verdict == ChipVerdict.ACTIVATE_NOW
}

/**
 * State of user's chip consumption across the season
 * Tracks all two sets of four chips (8 total) according to 2026/27 rules
 */
data class UserChipHistory(
    val usedChips: Set<ChipType> = emptySet(),
    val firstHalfWildcardUsed: Boolean = false,
    val secondHalfWildcardUsed: Boolean = false,
    // Set 1 explicit usage
    val set1UsedChips: Set<ChipType> = emptySet(),
    // Set 2 explicit usage
    val set2UsedChips: Set<ChipType> = emptySet(),
    // Gameweek in which each chip was played: Map of ChipInstance to Gameweek
    val chipPlayedGameweekMap: Map<ChipInstance, Int> = emptyMap(),
    // Currently pending chip (selected before deadline, can be confirmed or cancelled)
    val pendingChip: ChipInstance? = null,
    val pendingChipGameweek: Int? = null,
    // Active chip currently in play for this GW
    val activeChipThisGw: ChipType? = null,
    val activeChipGameweek: Int? = null,
    val lastUsedFreeHitGw: Int? = null,
    // Track banked free transfers (1 to 5)
    val bankedFreeTransfers: Int = 1
) {
    /**
     * Determines the state of a specific chip instance given the current gameweek
     */
    fun getChipState(chip: ChipType, half: ChipHalf, currentGw: Int): ChipStatus {
        val instance = ChipInstance(chip, half)

        // Check if pending
        if (pendingChip == instance && pendingChipGameweek == currentGw) {
            return ChipStatus.PENDING
        }

        // Check if explicitly recorded in played map
        val playedGw = chipPlayedGameweekMap[instance]
        if (playedGw != null) {
            if (currentGw == playedGw) return ChipStatus.ACTIVE_THIS_GW
            if (currentGw > playedGw) return ChipStatus.USED
        }

        // Check if currently active this gameweek
        if (activeChipThisGw == chip && ChipHalf.fromGameweek(currentGw) == half) {
            val actGw = activeChipGameweek ?: currentGw
            if (currentGw == actGw) return ChipStatus.ACTIVE_THIS_GW
            if (currentGw > actGw) return ChipStatus.USED
        }

        // Check if used
        val isUsedInHalf = if (half == ChipHalf.FIRST_HALF) {
            chip in set1UsedChips || (chip == ChipType.WILDCARD && firstHalfWildcardUsed) || (chip in usedChips && currentGw <= 19)
        } else {
            chip in set2UsedChips || (chip == ChipType.WILDCARD && secondHalfWildcardUsed) || (chip in usedChips && currentGw > 19)
        }
        if (isUsedInHalf) {
            return ChipStatus.USED
        }

        // Check expiration for Set 1 (First Half chips expire after GW19 deadline)
        if (half == ChipHalf.FIRST_HALF && currentGw > 19) {
            return ChipStatus.EXPIRED
        }

        return ChipStatus.AVAILABLE
    }

    fun isChipUsed(chip: ChipType, currentGw: Int): Boolean {
        val currentHalf = ChipHalf.fromGameweek(currentGw)
        val state = getChipState(chip, currentHalf, currentGw)
        return state == ChipStatus.USED || state == ChipStatus.EXPIRED
    }
}

/**
 * Full seasonal portfolio plan and current gameweek chip strategy
 */
data class ChipPortfolioPlan(
    val currentGameweek: Int,
    val currentHalf: ChipHalf = ChipHalf.fromGameweek(currentGameweek),
    val activeChipThisGameweek: ChipType?,
    val pendingChipThisGameweek: ChipType? = null,
    val chipAnalyses: Map<ChipType, SingleChipAnalysis>,
    val allChipAnalyses: List<SingleChipAnalysis> = emptyList(),
    val overallStrategySummaryAr: String,
    val recommendedSeasonSchedule: List<Pair<Int, ChipType>>,
    val userChipHistory: UserChipHistory,
    val preservedBankedTransfers: Int = 1
)
