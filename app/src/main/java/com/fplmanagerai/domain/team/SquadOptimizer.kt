package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.domain.analysis.CaptaincyAnalyzer
import com.fplmanagerai.domain.analysis.CaptaincyRecommendation
import java.util.Locale
import kotlin.math.round

enum class OptimizationStrategy(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String
) {
    MAX_EXPECTED_POINTS(
        "أقصى نقاط متوقعة (Max xPts)",
        "Max Expected Points",
        "استهداف أعلى معدل نقاط متوقعة ممكنة بصرف النظر عن نسب الملكية"
    ),
    LOW_RISK(
        "تقليل المخاطر (Low-Risk / Template)",
        "Low-Risk Strategy",
        "الاعتماد على نجوم التيمبليت الأكثر موثوقية وضماناً لدقائق اللعب لتجنب التذبذب"
    ),
    BALANCED(
        "استراتيجية متوازنة (Balanced)",
        "Balanced Strategy",
        "توليفة ذكية تجمع بين غزارة النقاط المتوقعة والقيمة السعرية واستقرار الفورمة"
    ),
    DIFFERENTIAL(
        "جرأة وتمايز (Differential)",
        "Differential Strategy",
        "استهداف خيارات واعدة بنسب ملكية منخفضة (< 15%) لتحقيق قفزات نوعية في الترتيب"
    );

    companion object {
        val SAFE_TEMPLATE: OptimizationStrategy get() = LOW_RISK
    }
}

typealias RiskPreference = OptimizationStrategy

data class ChipReadinessSummary(
    val benchBoostViable: Boolean,
    val benchBoostScore: Double,
    val benchBoostReasonAr: String,

    val tripleCaptainViable: Boolean,
    val tripleCaptainScore: Double,
    val tripleCaptainReasonAr: String,

    val freeHitViable: Boolean,
    val freeHitReasonAr: String,

    val wildcardViable: Boolean,
    val wildcardReasonAr: String
)

data class OptimizedSquad(
    val starting11: List<PlayerModel>,
    val bench: List<PlayerModel>,
    val captain: PlayerModel,
    val viceCaptain: PlayerModel,
    val formation: Formation,
    val totalCost: Double,
    val remainingBudget: Double,
    val projectedStartingPoints: Double,
    val projectedCaptainPoints: Double,
    val projectedBenchPoints: Double,
    val strategy: OptimizationStrategy,
    val horizon: Int,
    val summaryAr: String = "",
    val tacticalNotesAr: List<String> = emptyList(),
    val captaincyRecommendation: CaptaincyRecommendation? = null,
    val chipReadiness: ChipReadinessSummary? = null,
    val sourceGameweek: Int? = null,
    val isFromPreviousGameweek: Boolean = false
) {
    val totalSquad: List<PlayerModel>
        get() = starting11 + bench

    val riskPreference: OptimizationStrategy
        get() = strategy
}

class SquadOptimizer(
    private val validator: FormationValidator = FormationValidator(),
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val captaincyAnalyzer: CaptaincyAnalyzer = CaptaincyAnalyzer()
) {

    /**
     * بناء وتحسين تشكيلة 15 لاعباً كاملة مع فرز أفضل تشكيل أساسي وترتيب مقاعد البدلاء.
     */
    fun optimizeSquad(
        allPlayers: List<PlayerModel>,
        budget: Double = 100.0,
        preferredFormation: Formation? = null,
        strategy: OptimizationStrategy = OptimizationStrategy.BALANCED,
        riskPreference: OptimizationStrategy = strategy,
        horizon: Int = 3
    ): OptimizedSquad {
        val effectiveStrategy = if (strategy != OptimizationStrategy.BALANCED) strategy else riskPreference

        // 1. استبعاد اللاعبين غير المتاحين تماماً (إصابات مؤكدة، إيقاف، مخاطرة حرجة)
        val eligiblePool = allPlayers.filter {
            it.availability.isAvailable && (it.prediction?.riskLevel != RiskLevel.CRITICAL)
        }

        // 2. تقييم التشكيلات الرسمية المتاحة واختيار الخطة الأكثر تحقيقاً للنقاط
        val candidateFormations = if (preferredFormation != null) {
            listOf(preferredFormation)
        } else {
            Formation.ALL_VALID_FORMATIONS
        }

        var bestSquad: OptimizedSquad? = null
        var maxStartingPoints = -1.0

        for (formation in candidateFormations) {
            val squadForFormation = buildOptimizedSquadForFormation(
                pool = eligiblePool,
                formation = formation,
                budget = budget,
                strategy = effectiveStrategy,
                horizon = horizon
            )

            if (squadForFormation != null && squadForFormation.projectedStartingPoints > maxStartingPoints) {
                maxStartingPoints = squadForFormation.projectedStartingPoints
                bestSquad = squadForFormation
            }
        }

        return bestSquad ?: buildFallbackSquad(eligiblePool, budget, effectiveStrategy, horizon)
    }

    /**
     * تحديد أفضل 11 لاعباً أساسياً وترتيب مقاعد الاحتياط لتشكيلة مكونة بالفعل من 15 لاعباً.
     */
    fun optimizeStartingLineup(
        squad15: List<PlayerModel>,
        preferredFormation: Formation? = null,
        strategy: OptimizationStrategy = OptimizationStrategy.BALANCED
    ): OptimizedSquad {
        require(squad15.size == 15) { "التشكيلة يجب أن تضم 15 لاعباً بالضبط (المتوفر: ${squad15.size})" }

        val candidateFormations = if (preferredFormation != null) {
            listOf(preferredFormation)
        } else {
            Formation.ALL_VALID_FORMATIONS
        }

        var bestSquad: OptimizedSquad? = null
        var maxScore = -1.0

        for (formation in candidateFormations) {
            val evaluated = evaluateLineupForFormation(squad15, formation, strategy, horizon = 1)
            if (evaluated.projectedStartingPoints > maxScore) {
                maxScore = evaluated.projectedStartingPoints
                bestSquad = evaluated
            }
        }

        return bestSquad ?: evaluateLineupForFormation(squad15, Formation(3, 5, 2), strategy, horizon = 1)
    }

    /**
     * حساب مؤشر بدء المباراة أساسياً (Starter Score) للاعب.
     * يجمع بين: النقاط المتوقعة، دقائق اللعب، احتمال البدء، صعوبة اللقاء، الموثوقية والمخاطر.
     */
    fun calculateStarterScore(
        player: PlayerModel,
        strategy: OptimizationStrategy = OptimizationStrategy.BALANCED
    ): Double {
        val pred = player.prediction
        val xpts = pred?.expectedPoints ?: (player.totalPoints / 10.0).coerceAtLeast(1.0)
        val expMins = pred?.expectedMinutes ?: (if (player.starts > 0) 75.0 else 30.0)
        val startProb = pred?.startProbability ?: (if (player.availability.isAvailable) 0.85 else 0.40)
        val reliability = pred?.reliabilityScore ?: 65.0
        val perfScore = pred?.performanceScore ?: 60.0
        val risk = pred?.riskLevel ?: if (!player.availability.isAvailable) RiskLevel.CRITICAL else RiskLevel.LOW

        val status = player.availability.status.lowercase()
        val chance = player.availability.chanceOfPlayingNextRound

        // استبعاد فعلي إذا كان مصاباً أو موقوفاً أو مؤكد الغياب
        if (status in listOf("i", "s", "u") || chance == 0 || risk == RiskLevel.CRITICAL) {
            return 0.01
        }

        // 1. أثر صعوبة اللقاء والأرض (FDR)
        val nextFixture = player.upcomingFixtures.firstOrNull()
        val fdr = nextFixture?.difficulty ?: 3
        val isHome = nextFixture?.isHome ?: true
        val fdrModifier = (3.2 - fdr) * 0.35 + (if (isHome) 0.30 else -0.15)
        val effectivePoints = (xpts + fdrModifier).coerceAtLeast(0.5)

        // 2. أمان المشاركة واحتمال اللعب أكثر من 60 دقيقة
        val minutesRatio = (expMins / 90.0).coerceIn(0.1, 1.0)
        val under60Penalty = if (expMins < 60.0) 0.70 else 1.0
        val playSafety = (startProb * 0.65 + minutesRatio * 0.35) * under60Penalty

        // 3. مؤشر الجودة والموثوقية
        val qualityFactor = ((reliability * 0.50 + perfScore * 0.50) / 100.0).coerceIn(0.3, 1.0)

        // 4. معامل المخاطرة
        val riskMultiplier = when (risk) {
            RiskLevel.LOW -> 1.00
            RiskLevel.MEDIUM -> 0.88
            RiskLevel.HIGH -> 0.55
            RiskLevel.CRITICAL -> 0.05
        }

        val baseScore = effectivePoints * playSafety * (0.80 + qualityFactor * 0.20) * riskMultiplier

        return when (strategy) {
            OptimizationStrategy.MAX_EXPECTED_POINTS -> baseScore * 1.15
            OptimizationStrategy.LOW_RISK -> baseScore * (reliability / 80.0).coerceIn(0.6, 1.3)
            OptimizationStrategy.BALANCED -> baseScore
            OptimizationStrategy.DIFFERENTIAL -> {
                val ownership = player.selectedByPercent
                val diffMultiplier = if (ownership < 12.0) 1.25 else if (ownership < 25.0) 1.0 else 0.85
                baseScore * diffMultiplier
            }
        }
    }

    /**
     * ترتيب أولويات دكة البدلاء وفق قواعد التبديل التلقائي (Auto-Sub) في الفانتازي.
     * لا يعتمد على النقاط وحدها، بل يوازن بين أمان المشاركة والحفاظ على قانونية التشكيل.
     */
    fun calculateBenchPriorityScore(
        player: PlayerModel,
        startingFormation: Formation
    ): Double {
        val pred = player.prediction
        val xpts = pred?.expectedPoints ?: (player.totalPoints / 10.0).coerceAtLeast(1.0)
        val startProb = pred?.startProbability ?: (if (player.availability.isAvailable) 0.85 else 0.40)
        val expMins = pred?.expectedMinutes ?: 60.0
        val reliability = pred?.reliabilityScore ?: 60.0
        val risk = pred?.riskLevel ?: RiskLevel.LOW
        val status = player.availability.status.lowercase()
        val chance = player.availability.chanceOfPlayingNextRound

        // اللاعب المصاب أو الموقوف ينزل تلقائياً لآخر الدكة (B3)
        if (status in listOf("i", "s", "u") || chance == 0 || risk == RiskLevel.CRITICAL) {
            return 0.01
        }

        // أمان المشاركة هو العامل الحاسم في الدكة لضمان دخول البديل
        val minutesSafety = (startProb * 0.65 + (expMins / 90.0) * 0.35)
        val riskFactor = when (risk) {
            RiskLevel.LOW -> 1.00
            RiskLevel.MEDIUM -> 0.75
            RiskLevel.HIGH -> 0.35
            RiskLevel.CRITICAL -> 0.05
        }

        // ميزة حماية التشكيلة: إذا كانت التشكيلة الأساسية تضم 3 مدافعين فقط (الحد الأدنى القانوني)،
        // يُرفع ترتيب المدافع على الدكة لضمان تعويض أي مدافع غائب وتفادي قفل التبديل التلقائي.
        val formationProtection = if (player.position == PlayerPosition.DEF && startingFormation.defenders == 3) {
            1.25
        } else if (player.position == PlayerPosition.FWD && startingFormation.forwards == 1) {
            1.15
        } else {
            1.00
        }

        return (xpts * 0.60 + (reliability / 100.0) * 2.0) * minutesSafety * riskFactor * formationProtection
    }

    private fun buildOptimizedSquadForFormation(
        pool: List<PlayerModel>,
        formation: Formation,
        budget: Double,
        strategy: OptimizationStrategy,
        horizon: Int
    ): OptimizedSquad? {
        val targetDefs = formation.defenders
        val targetMids = formation.midfielders
        val targetFwds = formation.forwards

        // فرز المرشحين لكل مركز حسب مؤشر البداية الذكي
        val gkpPool = pool.filter { it.position == PlayerPosition.GKP }.sortedByDescending { calculateStarterScore(it, strategy) }
        val defPool = pool.filter { it.position == PlayerPosition.DEF }.sortedByDescending { calculateStarterScore(it, strategy) }
        val midPool = pool.filter { it.position == PlayerPosition.MID }.sortedByDescending { calculateStarterScore(it, strategy) }
        val fwdPool = pool.filter { it.position == PlayerPosition.FWD }.sortedByDescending { calculateStarterScore(it, strategy) }

        if (gkpPool.size < 2 || defPool.size < 5 || midPool.size < 5 || fwdPool.size < 3) {
            return null
        }

        val teamCounts = mutableMapOf<Int, Int>()
        val selected15 = mutableListOf<PlayerModel>()

        fun canPick(p: PlayerModel, currentCost: Double, remainingPicks: Int): Boolean {
            val count = teamCounts.getOrDefault(p.teamId, 0)
            if (count >= 3) return false
            val minReserve = (remainingPicks - 1).coerceAtLeast(0) * 4.0
            return (currentCost + p.price + minReserve) <= (budget + 0.001)
        }

        fun selectBest(poolList: List<PlayerModel>, needed: Int) {
            var added = 0
            for (p in poolList) {
                if (added >= needed) break
                if (selected15.none { it.id == p.id }) {
                    val remainingTotal = 15 - selected15.size
                    val costSoFar = selected15.sumOf { it.price }
                    if (canPick(p, costSoFar, remainingTotal)) {
                        selected15.add(p)
                        teamCounts[p.teamId] = teamCounts.getOrDefault(p.teamId, 0) + 1
                        added++
                    }
                }
            }
        }

        // 1. اختيار الحصة الأساسية أولاً
        selectBest(gkpPool, 1)
        selectBest(defPool, targetDefs)
        selectBest(midPool, targetMids)
        selectBest(fwdPool, targetFwds)

        // 2. اختيار حصة دكة البدلاء
        selectBest(gkpPool, 1)
        selectBest(defPool, 5 - targetDefs)
        selectBest(midPool, 5 - targetMids)
        selectBest(fwdPool, 3 - targetFwds)

        // 3. ملء أي مراكز ناقصة بأرخص الخيارات المتاحة
        fillRemainingSpots(selected15, pool, teamCounts, budget)

        // 4. فحص القيود الصارمة (الحد الأقصى 3 لكل نادٍ، والميزانية)
        val fullValidation = validator.validateFullSquad(selected15, budget)
        if (!fullValidation.isValid) {
            repairSquadConstraints(selected15, pool, teamCounts, budget)
        }

        return evaluateLineupForFormation(selected15, formation, strategy, horizon, budget)
    }

    /**
     * تقسيم وترتيب التشكيلة الأساسية والدكة وتقييم الكابتن والـ Chips.
     */
    private fun evaluateLineupForFormation(
        squad15: List<PlayerModel>,
        formation: Formation,
        strategy: OptimizationStrategy,
        horizon: Int,
        budget: Double = 100.0
    ): OptimizedSquad {
        val targetDefs = formation.defenders
        val targetMids = formation.midfielders
        val targetFwds = formation.forwards

        val starting11 = mutableListOf<PlayerModel>()
        val bench = mutableListOf<PlayerModel>()

        // 1. حارس المرمى الأساسي (الأعلى في starterScore)
        val gkps = squad15.filter { it.position == PlayerPosition.GKP }
            .sortedByDescending { calculateStarterScore(it, strategy) }
        if (gkps.isNotEmpty()) {
            starting11.add(gkps[0])
            bench.addAll(gkps.drop(1))
        }

        // 2. المدافعون، لاعبو الوسط، والمهاجمون
        val defs = squad15.filter { it.position == PlayerPosition.DEF }
            .sortedByDescending { calculateStarterScore(it, strategy) }
        val mids = squad15.filter { it.position == PlayerPosition.MID }
            .sortedByDescending { calculateStarterScore(it, strategy) }
        val fwds = squad15.filter { it.position == PlayerPosition.FWD }
            .sortedByDescending { calculateStarterScore(it, strategy) }

        starting11.addAll(defs.take(targetDefs))
        bench.addAll(defs.drop(targetDefs))

        starting11.addAll(mids.take(targetMids))
        bench.addAll(mids.drop(targetMids))

        starting11.addAll(fwds.take(targetFwds))
        bench.addAll(fwds.drop(targetFwds))

        // 3. ترتيب الدكة الذكي:
        // Slot 12: الحارس الاحتياط
        // Slots 13, 14, 15: لاعبو الميدان مرتبين حسب calculateBenchPriorityScore
        val benchGk = bench.filter { it.position == PlayerPosition.GKP }
        val benchOutfield = bench.filter { it.position != PlayerPosition.GKP }
            .sortedByDescending { calculateBenchPriorityScore(it, formation) }
        val orderedBench = benchGk + benchOutfield

        // 4. اختيار الكابتن ونائب الكابتن عبر المحرك الموحد
        val capResult = captainSelector.selectCaptain(starting11)
        val captain = capResult.captain
        val viceCaptain = capResult.viceCaptain

        val totalCost = round(squad15.sumOf { it.price } * 10.0) / 10.0
        val remainingBank = round((budget - totalCost) * 10.0) / 10.0

        val baseStartingPts = starting11.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        val capBonus = captain.prediction?.expectedPoints ?: 0.0
        val projectedStarting = round((baseStartingPts + capBonus) * 10.0) / 10.0
        val projectedCapPoints = round((capBonus * 2.0) * 10.0) / 10.0
        val projectedBench = round(orderedBench.sumOf { it.prediction?.expectedPoints ?: 0.0 } * 10.0) / 10.0

        // 5. تقييم جاهزية الـ Chips
        val chipReadiness = evaluateChipReadiness(
            starting11 = starting11,
            bench = orderedBench,
            captain = captain,
            capResult = capResult
        )

        // 6. الملاحظات التكتيكية باللغة العربية
        val tacticalNotes = generateTacticalNotes(formation, starting11, orderedBench, captain, capResult)
        val summaryAr = generateSummary(formation, projectedStarting, captain, viceCaptain, chipReadiness)

        return OptimizedSquad(
            starting11 = starting11,
            bench = orderedBench,
            captain = captain,
            viceCaptain = viceCaptain,
            formation = formation,
            totalCost = totalCost,
            remainingBudget = remainingBank,
            projectedStartingPoints = projectedStarting,
            projectedCaptainPoints = projectedCapPoints,
            projectedBenchPoints = projectedBench,
            strategy = strategy,
            horizon = horizon,
            summaryAr = summaryAr,
            tacticalNotesAr = tacticalNotes,
            captaincyRecommendation = capResult.recommendation,
            chipReadiness = chipReadiness
        )
    }

    /**
     * تقييم موضوعي ومستقل لجاهزية وتكامل الـ Chips دون تفعيلها تلقائياً.
     */
    private fun evaluateChipReadiness(
        starting11: List<PlayerModel>,
        bench: List<PlayerModel>,
        captain: PlayerModel,
        capResult: CaptainSelectionResult
    ): ChipReadinessSummary {
        // A. Bench Boost: فحص نقاط وجاهزية لاعبي الدكة الأربعة
        val benchPts = bench.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        val benchFitCount = bench.count {
            val pred = it.prediction
            (pred?.startProbability ?: 0.8) >= 0.70 &&
                    (pred?.expectedMinutes ?: 60.0) >= 55.0 &&
                    pred?.riskLevel != RiskLevel.CRITICAL &&
                    pred?.riskLevel != RiskLevel.HIGH
        }
        val bbViable = benchFitCount == 4 && benchPts >= 13.0
        val bbReason = if (bbViable) {
            "جاهزية مثالية لتفعيل Bench Boost: جميع لاعبي الدكة الأربعة أساسيون بنقاط متوقعة (%.1f ن).".format(Locale.US, benchPts)
        } else {
            "ادخر Bench Boost: عائد الدكة الحالي (%.1f ن) أو وجود شكوك في مشاركة بعض البدلاء (%d/4 جاهزون).".format(Locale.US, benchPts, benchFitCount)
        }

        // B. Triple Captain: مأخوذ مباشرة من محرك الكابتن الموحد
        val tcViable = capResult.isTripleCaptainViable
        val tcReason = capResult.tripleCaptainReasonAr.ifBlank {
            if (tcViable) "فرصة ممتازة لتفعيل Triple Captain مع ${captain.webName}."
            else "ادخر Triple Captain لجولة مزدوجة مواتية."
        }

        // C. Free Hit: فحص الغيابات وصعوبة المواجهات في التشكيلة الحالية
        val sidelinedCount = (starting11 + bench).count {
            !it.availability.isAvailable ||
                    it.prediction?.riskLevel == RiskLevel.CRITICAL ||
                    (it.availability.chanceOfPlayingNextRound != null && it.availability.chanceOfPlayingNextRound < 75)
        }
        val fhViable = sidelinedCount >= 4
        val fhReason = if (fhViable) {
            "توصية بدراسة Free Hit: يوجد $sidelinedCount لاعبين متأثرين بالإصابات أو الغيابات في التشكيلة."
        } else {
            "التشكيلة في حالة استقرار بدني مناسبة؛ لا داعي لحرق Free Hit في هذه الجولة."
        }

        // D. Wildcard: فحص الهيكل العام ومدى الحاجة لإعادة البناء
        val lowOutputCount = (starting11 + bench).count {
            (it.prediction?.expectedPoints ?: 0.0) < 2.5 || it.prediction?.riskLevel == RiskLevel.HIGH
        }
        val wcViable = lowOutputCount >= 5
        val wcReason = if (wcViable) {
            "إعادة البناء عبر Wildcard خيار جذاب: يوجد $lowOutputCount لاعباً بمردود منخفض أو مخاطر مرتفعة."
        } else {
            "هيكل الفريق متوازن ومستقر؛ الانتقالات العادية كافية لإدارة التشكيلة."
        }

        return ChipReadinessSummary(
            benchBoostViable = bbViable,
            benchBoostScore = round(benchPts * 10.0) / 10.0,
            benchBoostReasonAr = bbReason,
            tripleCaptainViable = tcViable,
            tripleCaptainScore = capResult.recommendation?.captain?.captaincyScore ?: 70.0,
            tripleCaptainReasonAr = tcReason,
            freeHitViable = fhViable,
            freeHitReasonAr = fhReason,
            wildcardViable = wcViable,
            wildcardReasonAr = wcReason
        )
    }

    private fun generateTacticalNotes(
        formation: Formation,
        starting11: List<PlayerModel>,
        bench: List<PlayerModel>,
        captain: PlayerModel,
        capResult: CaptainSelectionResult
    ): List<String> {
        val notes = mutableListOf<String>()

        notes.add("الخطة المعتمدة: ${formation.name} هي الخيار الأنسب لموازنة القوة الهجومية والدفاعية.")

        if (bench.size >= 2) {
            val b1 = bench[1]
            notes.add("البديل الأول (B1): ${b1.webName} (${b1.position.shortName}) لضمان أعلى أمان مشاركة في حال غياب أي أساسي.")
        }

        if (formation.defenders == 3 && bench.size >= 2) {
            notes.add("تأمين الدفاع: التشكيلة تعتمد على الحد الأدنى من المدافعين (3)، لذا تم إعطاء أولوية للمدافع البديل لحماية قانونية التشكيلة.")
        }

        val highRiskBenched = bench.filter { it.prediction?.riskLevel == RiskLevel.HIGH || !it.availability.isAvailable }
        if (highRiskBenched.isNotEmpty()) {
            val names = highRiskBenched.joinToString("، ") { it.webName }
            notes.add("إجراء وقائي: تم تحويل اللاعبين ذوي الشكوك ($names) لمقاعد البدلاء لحماية التشكيل الأساسي.")
        }

        notes.add("الكابتن: ${captain.webName} (C) - ${capResult.summaryAr.ifBlank { "الخيار الأبرز للشارة" }}")

        return notes
    }

    private fun generateSummary(
        formation: Formation,
        projectedPoints: Double,
        captain: PlayerModel,
        viceCaptain: PlayerModel,
        chipReadiness: ChipReadinessSummary
    ): String = buildString {
        append("التشكيلة المثالية بخطة (${formation.name}) بنقاط متوقعة إجمالية (%.1f ن). ".format(Locale.US, projectedPoints))
        append("الشارة موجهة إلى ${captain.webName} (C) مع ${viceCaptain.webName} كنائب قائد (V). ")
        if (chipReadiness.tripleCaptainViable) {
            append("حالة 3xC: موصى بالنظر في التفعيل. ")
        }
        if (chipReadiness.benchBoostViable) {
            append("حالة Bench Boost: دكة قوية تدعم التفعيل. ")
        }
    }

    private fun fillRemainingSpots(
        selected: MutableList<PlayerModel>,
        pool: List<PlayerModel>,
        teamCounts: MutableMap<Int, Int>,
        budget: Double
    ) {
        val positionQuotas = mapOf(
            PlayerPosition.GKP to 2,
            PlayerPosition.DEF to 5,
            PlayerPosition.MID to 5,
            PlayerPosition.FWD to 3
        )

        for ((pos, target) in positionQuotas) {
            val currentCount = selected.count { it.position == pos }
            if (currentCount < target) {
                val needed = target - currentCount
                val candidates = pool.filter { it.position == pos && selected.none { s -> s.id == it.id } }
                    .sortedBy { it.price }

                var added = 0
                for (c in candidates) {
                    if (added >= needed) break
                    val count = teamCounts.getOrDefault(c.teamId, 0)
                    if (count < 3) {
                        selected.add(c)
                        teamCounts[c.teamId] = count + 1
                        added++
                    }
                }
            }
        }
    }

    private fun repairSquadConstraints(
        selected: MutableList<PlayerModel>,
        pool: List<PlayerModel>,
        teamCounts: MutableMap<Int, Int>,
        budget: Double
    ) {
        val groupedByTeam = selected.groupBy { it.teamId }
        for ((teamId, players) in groupedByTeam) {
            if (players.size > 3) {
                val overflow = players.sortedBy { it.prediction?.expectedPoints ?: 0.0 }
                val toReplace = overflow.take(players.size - 3)
                for (badPlayer in toReplace) {
                    selected.remove(badPlayer)
                    teamCounts[teamId] = (teamCounts[teamId] ?: 1) - 1

                    val replacement = pool.firstOrNull {
                        it.position == badPlayer.position &&
                                selected.none { s -> s.id == it.id } &&
                                teamCounts.getOrDefault(it.teamId, 0) < 3 &&
                                (selected.sumOf { s -> s.price } + it.price) <= budget
                    } ?: pool.firstOrNull {
                        it.position == badPlayer.position &&
                                selected.none { s -> s.id == it.id } &&
                                teamCounts.getOrDefault(it.teamId, 0) < 3
                    }

                    if (replacement != null) {
                        selected.add(replacement)
                        teamCounts[replacement.teamId] = teamCounts.getOrDefault(replacement.teamId, 0) + 1
                    }
                }
            }
        }
    }

    private fun buildFallbackSquad(
        pool: List<PlayerModel>,
        budget: Double,
        strategy: OptimizationStrategy,
        horizon: Int
    ): OptimizedSquad {
        val formation = Formation(3, 5, 2)
        val teamCounts = mutableMapOf<Int, Int>()
        val selected = mutableListOf<PlayerModel>()

        fun pickCheapest(pos: PlayerPosition, count: Int) {
            val candidates = pool.filter { it.position == pos }.sortedBy { it.price }
            var added = 0
            for (c in candidates) {
                if (added >= count) break
                val tCount = teamCounts.getOrDefault(c.teamId, 0)
                if (tCount < 3 && selected.none { it.id == c.id }) {
                    selected.add(c)
                    teamCounts[c.teamId] = tCount + 1
                    added++
                }
            }
        }

        pickCheapest(PlayerPosition.GKP, 2)
        pickCheapest(PlayerPosition.DEF, 5)
        pickCheapest(PlayerPosition.MID, 5)
        pickCheapest(PlayerPosition.FWD, 3)

        val starting = selected.take(11)
        val bench = selected.drop(11)
        val capResult = captainSelector.selectCaptain(starting)

        return OptimizedSquad(
            starting11 = starting,
            bench = bench,
            captain = capResult.captain,
            viceCaptain = capResult.viceCaptain,
            formation = formation,
            totalCost = round(selected.sumOf { it.price } * 10.0) / 10.0,
            remainingBudget = round((budget - selected.sumOf { it.price }) * 10.0) / 10.0,
            projectedStartingPoints = 45.0,
            projectedCaptainPoints = 12.0,
            projectedBenchPoints = 6.0,
            strategy = strategy,
            horizon = horizon,
            summaryAr = "تشكيلة احتياطية موحدة",
            tacticalNotesAr = listOf("تم الاعتماد على خيارات الطوارئ لضمان توفير 15 لاعباً قانونياً.")
        )
    }
}
