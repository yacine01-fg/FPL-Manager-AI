package com.fplmanagerai.domain.decision

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.domain.chips.UnifiedChipDecision
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.OptimizedSquad
import com.fplmanagerai.domain.team.TransferSuggestion

/**
 * أنواع القرارات التكتيكية المتاحة لمدرب الفانتازي في كل جولة
 */
enum class DecisionType(val titleAr: String, val badgeAr: String) {
    STARTING_XI("التشكيلة الأساسية", "تشكيلة أساسية"),
    BENCH_ORDER("ترتيب دكة البدلاء", "دكة البدلاء"),
    CAPTAINCY("شارة القيادة (C & VC)", "الكابتن"),
    ROLL_TRANSFER("توفير التبديل (Roll Transfer)", "توفير تبديل"),
    FREE_TRANSFER("تبديل مجاني (Free Transfer)", "تبديل مجاني"),
    TRANSFER_HIT("تبديل مع خصم نقاط (Transfer Hit)", "خصم نقاط"),
    CHIP_ACTIVATION("تفعيل شريحة تكتيكية (Chip)", "شريحة تكتيكية")
}

/**
 * درجات ثقة النموذج الذكي في صحة التوصية
 */
enum class ConfidenceLevel(val titleAr: String, val baseScore: Int) {
    VERY_HIGH("ثقة عالية جداً", 90),
    HIGH("ثقة مرتفعة", 78),
    MEDIUM("ثقة متوسطة", 60),
    LOW("ثقة منخفضة", 40)
}

data class DecisionConfidence(
    val level: ConfidenceLevel,
    val scorePercent: Int,
    val factorsAr: List<String> = emptyList()
)

/**
 * تصنيفات مستوى المخاطرة للقرار
 */
enum class RiskCategory(val titleAr: String, val badgeColorHex: Long) {
    SAFE("آمن (Safe)", 0xFF2E7D32),
    BALANCED("متوازن (Balanced)", 0xFF0288D1),
    AGGRESSIVE("هجومي / جريء (Aggressive)", 0xFFED6C02),
    SPECULATIVE("مغامرة (Speculative)", 0xFFD32F2F)
}

data class DecisionRisk(
    val category: RiskCategory,
    val titleAr: String,
    val descriptionAr: String
)

/**
 * موازنة تكتيكية (المكسب المتوقع مقابل التضحية أو المخاطرة المحتملة)
 */
data class TacticalTradeOff(
    val advantageAr: String,
    val downsideOrRiskAr: String
)

/**
 * تفسير تحليلي شفاف لأسباب اتخاذ القرار
 */
data class DecisionExplanation(
    val mainReasonAr: String,
    val keyDriversAr: List<String> = emptyList(),
    val warningsAr: List<String> = emptyList()
)

/**
 * معرفات السيناريوهات التكتيكية الخمسة المقارنة
 */
enum class ScenarioId(val titleAr: String, val shortNameAr: String) {
    ROLL_TRANSFER("السيناريو 1: الثبات وتوفير التبديل", "توفير التبديل"),
    LINEUP_ONLY("السيناريو 2: تعديل التشكيل والشارة فقط", "تعديل التشكيل"),
    ONE_FREE_TRANSFER("السيناريو 3: تنفيذ التبديلات المجانية المتاحة", "تبديل مجاني"),
    TRANSFER_WITH_HIT("السيناريو 4: تبديل مع خصم نقاط (-4 لكل تبديل إضافي)", "تبديل مع خصم"),
    CHIP_ACTIVATION("السيناريو 5: تفعيل الشريحة المقترحة", "تفعيل الشريحة")
}

/**
 * سيناريو تكتيكي متكامل مع أرقام النقاط والمخاطر والمكسب الممتد لـ 5 جولات
 */
data class DecisionScenario(
    val id: ScenarioId,
    val titleAr: String,
    val descriptionAr: String,
    val projectedGameweekPoints: Double,
    val hitCost: Int,
    val netGameweekPoints: Double,
    val projectedHorizon5GwPoints: Double,
    val netHorizonAdvantage: Double,
    val risk: DecisionRisk,
    val verdictAr: String,
    val isRecommended: Boolean,
    val tagAr: String,
    val transfersCount: Int = 0,
    val extraTransfersCount: Int = 0
)

/**
 * مقارنة جدولية تفصيلية بين السيناريوهات التكتيكية الخمسة
 */
data class ScenarioComparisonResult(
    val scenarios: List<DecisionScenario>,
    val bestScenario: DecisionScenario,
    val comparisonSummaryAr: String
)

/**
 * عنصر لاعب بديل مع موقعه على الدكة ودوره في التبديل التلقائي
 */
data class BenchSubstitute(
    val player: PlayerModel,
    val slotIndex: Int, // 12..15
    val slotLabelAr: String,
    val priorityScore: Double,
    val isGoalkeeper: Boolean,
    val roleExplanationAr: String,
    val originalSlotIndex: Int? = null
)

/**
 * ذكاء دكة البدلاء وقواعد التبديل التلقائي (Auto-Sub)
 */
data class BenchIntelligenceResult(
    val substitutes: List<BenchSubstitute>,
    val userOriginalBench: List<BenchSubstitute> = emptyList(),
    val hasBenchOrderChanged: Boolean = false,
    val autoSubRulesExplanationAr: String,
    val formationProtectionNoteAr: String,
    val disclaimerAr: String = "ترتيب مقترح استرشادياً من الذكاء الاصطناعي - يتطلب مراجعة واعتماد المدرب."
)

/**
 * توصية تكتيكية موحدة وقابلة للتفسير
 */
data class DecisionRecommendation(
    val type: DecisionType,
    val titleAr: String,
    val summaryAr: String,
    val expectedGain: Double,
    val confidence: DecisionConfidence,
    val risk: DecisionRisk,
    val explanation: DecisionExplanation,
    val tradeOffs: List<TacticalTradeOff> = emptyList(),
    val isPrimaryAction: Boolean = false
)

/**
 * الخطة الشاملة لقرارات الجولة (Unified Gameweek Decision Plan)
 * تجيب المدرب عن: «بناءً على تشكيلتي وميزانيتي وجدول المباريات، ما أفضل القرارات التي يمكنني اتخاذها لهذه الجولة؟ ولماذا؟»
 */
data class GameweekDecisionPlan(
    val gameweek: Int,
    val heroDecision: DecisionRecommendation,
    val optimalSquad: OptimizedSquad,
    val startingLineup: List<PlayerModel>,
    val formation: Formation,
    val benchIntelligence: BenchIntelligenceResult,
    val captain: PlayerModel,
    val viceCaptain: PlayerModel,
    val projectedStartingPoints: Double,
    val projectedCaptainPoints: Double,
    val projectedTotalPoints: Double,
    val transferRecommendations: List<DecisionRecommendation>,
    val transferSuggestions: List<TransferSuggestion>,
    val chipRecommendation: DecisionRecommendation?,
    val unifiedChipDecision: UnifiedChipDecision?,
    val scenariosComparison: ScenarioComparisonResult,
    val tacticalWarnings: List<String>,
    val activeModelVersionId: String,
    val isRealUserSquad: Boolean,
    val bank: Double,
    val freeTransfers: Int,
    val squadValue: Double
)

