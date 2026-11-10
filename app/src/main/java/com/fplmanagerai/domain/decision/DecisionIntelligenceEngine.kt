package com.fplmanagerai.domain.decision

import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.domain.calibration.ModelVersionRepository
import com.fplmanagerai.domain.chips.ChipHalf
import com.fplmanagerai.domain.chips.ChipPortfolioPlan
import com.fplmanagerai.domain.chips.ChipStrategyEngine
import com.fplmanagerai.domain.chips.ChipType
import com.fplmanagerai.domain.chips.ChipVerdict
import com.fplmanagerai.domain.chips.UnifiedChipDecision
import com.fplmanagerai.domain.chips.UserChipHistory
import com.fplmanagerai.domain.team.CaptainSelector
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.OptimizedSquad
import com.fplmanagerai.domain.team.SquadOptimizer
import com.fplmanagerai.domain.team.TransferAnalyzer
import com.fplmanagerai.domain.team.TransferSuggestion
import java.util.Locale
import kotlin.math.round

/**
 * المحرك المركزي الموحد لذكاء القرارات (FPL Decision Intelligence Engine).
 * يدمج كافة المحركات التحليلية في نظام تكتيكي موحد يجيب المدرب عن:
 * «بناءً على تشكيلتي وميزانيتي وجدول المباريات، ما أفضل القرارات التي يمكنني اتخاذها لهذه الجولة؟ ولماذا؟»
 */
class DecisionIntelligenceEngine(
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val transferAnalyzer: TransferAnalyzer = TransferAnalyzer(),
    private val chipStrategyEngine: ChipStrategyEngine = ChipStrategyEngine(),
    private val modelVersionRepository: ModelVersionRepository = ModelVersionRepository()
) {

    /**
     * توليد خطة القرارات التكتيكية الشاملة للجولة الحالية.
     */
    fun generateGameweekPlan(
        squad15: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory = UserChipHistory(),
        bank: Double = 0.0,
        freeTransfers: Int = 1,
        isRealUserSquad: Boolean = false,
        activeModelId: String? = null
    ): GameweekDecisionPlan {
        require(squad15.size == 15) { "التشكيلة يجب أن تضم 15 لاعباً بالضبط (المتوفر: ${squad15.size})" }

        val activeModelVersion = activeModelId ?: modelVersionRepository.getActiveModel().id
        val safeGw = currentGameweek.coerceIn(1, 38)
        val safeBank = round(bank * 10.0) / 10.0
        val safeFreeTransfers = freeTransfers.coerceIn(1, 5)

        // =========================================================================
        // 1. تحسين التشكيلة الأساسية الذكية (Smart Starting XI)
        // =========================================================================
        val optimizedLineup = squadOptimizer.optimizeStartingLineup(
            squad15 = squad15,
            preferredFormation = null,
            strategy = OptimizationStrategy.BALANCED
        )

        val starting11 = optimizedLineup.starting11
        val formation = optimizedLineup.formation

        // =========================================================================
        // 2. ذكاء دكة البدلاء وقواعد التبديل التلقائي (Bench Intelligence & Auto-Sub)
        // لا يتم تعديل دكة حساب المستخدم إطلاقاً، بل تُعرض الدكة الأصلية والدكة المقترحة منفصلتين
        // =========================================================================
        val benchIntelligence = evaluateBenchIntelligence(
            benchPlayers = optimizedLineup.bench,
            startingFormation = formation,
            userSquad15 = squad15
        )

        // =========================================================================
        // 3. ذكاء شارة القيادة (Captain & Vice-Captain Intelligence)
        // التحقق الصارم: الكابتن ونائبه من الأساسيين، C != VC، وعدم مضاعفة النقاط مرتين
        // =========================================================================
        val capResult = captainSelector.selectCaptain(starting11)
        val captain = capResult.captain
        val viceCaptain = capResult.viceCaptain

        val verifiedViceCaptain = if (viceCaptain.id == captain.id) {
            starting11.firstOrNull { it.id != captain.id } ?: starting11.getOrElse(1) { captain }
        } else {
            viceCaptain
        }

        // التحقق المنطقي القاطع
        val startingIds = starting11.map { it.id }.toSet()
        require(captain.id in startingIds) { "الكابتن (${captain.webName}) يجب أن يكون أساسياً ضمن الـ 11 لاعباً" }
        require(verifiedViceCaptain.id in startingIds) { "نائب الكابتن (${verifiedViceCaptain.webName}) يجب أن يكون أساسياً ضمن الـ 11 لاعباً" }
        require(captain.id != verifiedViceCaptain.id) { "لا يمكن أن يكون الكابتن ونائب الكابتن نفس اللاعب" }

        val isTripleCaptainActive = userHistory.activeChipThisGw == ChipType.TRIPLE_CAPTAIN
        val baseCapXpts = captain.prediction?.expectedPoints ?: (captain.totalPoints / 10.0).coerceAtLeast(1.0)
        val projectedCapPoints = round((if (isTripleCaptainActive) baseCapXpts * 3.0 else baseCapXpts * 2.0) * 10.0) / 10.0

        val startingBaseXpts = starting11.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        // المضاعفة الدقيقة: startingBaseXpts تضم الكابتن مرة واحدة (1x)، فإضافة baseCapXpts تجعله (2x)، وإضافة 2x في حالة التريبل تجعله (3x)
        val capMultiplierBonus = if (isTripleCaptainActive) baseCapXpts * 2.0 else baseCapXpts
        val projectedStartingPoints = round((startingBaseXpts + capMultiplierBonus) * 10.0) / 10.0

        // =========================================================================
        // 4. ذكاء سوق الانتقالات والمفاضلة بين التبديل والتوفير (Transfer Intelligence)
        // =========================================================================
        val transferAnalysis = transferAnalyzer.analyzeTransfers(
            currentSquad = squad15,
            allPlayers = allPlayers,
            freeTransfers = safeFreeTransfers,
            bankBudget = safeBank,
            horizon = 5
        )

        val transferSuggestions = transferAnalysis.suggestions
        val transferDecisions = evaluateTransferDecisions(
            squad = squad15,
            suggestions = transferSuggestions,
            freeTransfers = safeFreeTransfers,
            bank = safeBank
        )

        // =========================================================================
        // 5. ذكاء الشرائح التكتيكية (Chip Strategy Intelligence)
        // =========================================================================
        val chipPortfolio = chipStrategyEngine.evaluateChips(
            squad = squad15,
            allPlayers = allPlayers,
            fixtures = fixtures,
            currentGameweek = safeGw,
            userHistory = userHistory,
            freeTransfers = safeFreeTransfers,
            budget = safeBank + squad15.sumOf { it.price },
            recordSnapshots = false
        )

        val unifiedChipDecision = chipPortfolio.unifiedDecisions.firstOrNull { it.shouldActivateNow }
            ?: chipPortfolio.unifiedDecisions.maxByOrNull { it.expectedGain }
        val chipRecommendation = evaluateChipDecisionRecommendation(unifiedChipDecision, userHistory, safeGw)

        // =========================================================================
        // 6. محرك مقارنة السيناريوهات التكتيكية الخمسة (Scenario Comparison Engine)
        // =========================================================================
        val scenarioComparison = buildScenarioComparison(
            baseSquadPoints = projectedStartingPoints,
            optimizedSquad = optimizedLineup,
            transferSuggestions = transferSuggestions,
            freeTransfers = safeFreeTransfers,
            unifiedChip = unifiedChipDecision,
            captain = captain,
            baseCapXpts = baseCapXpts,
            bank = safeBank,
            squad15 = squad15
        )

        // =========================================================================
        // 7. تخليق القرار البطل للجولة (Hero Decision Synthesis)
        // =========================================================================
        val heroDecision = synthesizeHeroDecision(
            chipRec = chipRecommendation,
            transferDecisions = transferDecisions,
            scenarioComparison = scenarioComparison,
            captain = captain,
            viceCaptain = verifiedViceCaptain,
            formation = formation,
            projectedPoints = projectedStartingPoints
        )

        // =========================================================================
        // 8. حصر التنبيهات والتحذيرات التكتيكية والطبية
        // =========================================================================
        val tacticalWarnings = collectTacticalWarnings(squad15, formation, captain, safeFreeTransfers)

        val squadValue = round(squad15.sumOf { it.price } * 10.0) / 10.0

        return GameweekDecisionPlan(
            gameweek = safeGw,
            heroDecision = heroDecision,
            optimalSquad = optimizedLineup,
            startingLineup = starting11,
            formation = formation,
            benchIntelligence = benchIntelligence,
            captain = captain,
            viceCaptain = verifiedViceCaptain,
            projectedStartingPoints = projectedStartingPoints,
            projectedCaptainPoints = projectedCapPoints,
            projectedTotalPoints = projectedStartingPoints,
            transferRecommendations = transferDecisions,
            transferSuggestions = transferSuggestions,
            chipRecommendation = chipRecommendation,
            unifiedChipDecision = unifiedChipDecision,
            scenariosComparison = scenarioComparison,
            tacticalWarnings = tacticalWarnings,
            activeModelVersionId = activeModelVersion,
            isRealUserSquad = isRealUserSquad,
            bank = safeBank,
            freeTransfers = safeFreeTransfers,
            squadValue = squadValue
        )
    }

    /**
     * حساب النقاط التراكمية للاعب على أفق 5 جولات من واقع جدول المباريات الفعلي (DGW/BGW/FDR).
     */
    private fun calculatePlayer5GwPoints(player: PlayerModel): Double {
        val horizonList = player.prediction?.horizonPredictions
        return if (!horizonList.isNullOrEmpty()) {
            horizonList.take(5).sumOf { it.expectedPoints }
        } else {
            val base = if (!player.availability.isAvailable || (player.availability.chanceOfPlayingNextRound ?: 100) == 0) 0.0
            else (player.prediction?.expectedPoints ?: 3.0)
            base * 5.0
        }
    }

    /**
     * حساب النقاط التراكمية للتشكيلة الأساسية مع الكابتن على أفق 5 جولات.
     */
    private fun calculateStarting11_5GwPoints(starting11: List<PlayerModel>, captain: PlayerModel): Double {
        val baseSum = starting11.sumOf { calculatePlayer5GwPoints(it) }
        val capBonus = calculatePlayer5GwPoints(captain) // مضاعفة الكابتن مرة واحدة إضافية
        return round((baseSum + capBonus) * 10.0) / 10.0
    }

    /**
     * تقييم دكة البدلاء وصياغة ترتيب الأولوية وفق قواعد Auto-Sub الرسمية،
     * مع الاحتفاظ بترتيب حساب المستخدم الأصلي دون المساس به، وعرض الترتيبين منفصلين.
     */
    private fun evaluateBenchIntelligence(
        benchPlayers: List<PlayerModel>,
        startingFormation: Formation,
        userSquad15: List<PlayerModel>
    ): BenchIntelligenceResult {
        // 1. ترتيب الدكة الفعلي في حساب المستخدم على FPL
        val userOriginalBench = userSquad15.filter { it.fplPosition in 12..15 }
            .sortedBy { it.fplPosition }
            .map { p ->
                val slot = p.fplPosition ?: 12
                val label = when (slot) {
                    12 -> "حارس بديل (FPL)"
                    13 -> "بديل 1 (FPL)"
                    14 -> "بديل 2 (FPL)"
                    15 -> "بديل 3 (FPL)"
                    else -> "بديل (FPL)"
                }
                BenchSubstitute(
                    player = p,
                    slotIndex = slot,
                    slotLabelAr = label,
                    priorityScore = p.prediction?.expectedPoints ?: 0.0,
                    isGoalkeeper = p.position == PlayerPosition.GKP,
                    roleExplanationAr = "الترتيب الفعلي المحفوظ في تشكيلة حسابك الرسمية.",
                    originalSlotIndex = slot
                )
            }

        // 2. الترتيب المقترح تكتيكياً من الذكاء الاصطناعي لحماية التبديل التلقائي
        val gkSub = benchPlayers.firstOrNull { it.position == PlayerPosition.GKP }
        val outfieldSubs = benchPlayers.filter { it.position != PlayerPosition.GKP }
            .sortedByDescending { squadOptimizer.calculateBenchPriorityScore(it, startingFormation) }

        val orderedSubs = mutableListOf<BenchSubstitute>()

        // البديل 12: حارس المرمى البديل (لا يدخل إلا مكان الحارس الأساسي)
        if (gkSub != null) {
            val pred = gkSub.prediction
            val xpts = pred?.expectedPoints ?: 0.0
            orderedSubs.add(
                BenchSubstitute(
                    player = gkSub,
                    slotIndex = 12,
                    slotLabelAr = "حارس بديل (مقترح)",
                    priorityScore = xpts,
                    isGoalkeeper = true,
                    roleExplanationAr = "يدخل تلقائياً فقط إذا غاب حارس المرمى الأساسي تماماً عن خوض أي دقيقة.",
                    originalSlotIndex = gkSub.fplPosition
                )
            )
        }

        // البدلاء 13 و14 و15: لاعبو الميدان
        outfieldSubs.forEachIndexed { index, player ->
            val slot = 13 + index
            val label = when (index) {
                0 -> "بديل 1 (الأولوية القصوى)"
                1 -> "بديل 2 (أولوية ثانوية)"
                else -> "بديل 3 (أولوية متأخرة)"
            }
            val score = squadOptimizer.calculateBenchPriorityScore(player, startingFormation)
            val roleReason = when (player.position) {
                PlayerPosition.DEF -> if (startingFormation.defenders == 3) {
                    "أولوية دفاعية حاسمة: يحمي التشكيل من كسر قاعدة الحد الأدنى لخط الدفاع (3 مدافعين)."
                } else {
                    "مدافع بديل: يدخل وفق ترتيب الأولوية في حال غياب أي لاعب أساسي لا يكسر دخوله قانونية التشكيل."
                }
                PlayerPosition.FWD -> if (startingFormation.forwards == 1) {
                    "أولوية هجومية: يضمن وجود مهاجم واحد على الأقل في التشكيل الأساسي القانوني."
                } else {
                    "مهاجم بديل: خيار هجومي يدخل تلقائياً عند غياب أي لاعب ميدان."
                }
                PlayerPosition.MID -> "لاعب وسط بديل: خيار متوازن للمشاركة عند غياب أي لاعب أساسي."
                else -> "لاعب بديل وفق ترتيب الأولوية."
            }

            orderedSubs.add(
                BenchSubstitute(
                    player = player,
                    slotIndex = slot,
                    slotLabelAr = label,
                    priorityScore = round(score * 10.0) / 10.0,
                    isGoalkeeper = false,
                    roleExplanationAr = roleReason,
                    originalSlotIndex = player.fplPosition
                )
            )
        }

        val hasBenchOrderChanged = userOriginalBench.isNotEmpty() &&
                userOriginalBench.filter { !it.isGoalkeeper }.map { it.player.id } !=
                orderedSubs.filter { !it.isGoalkeeper }.map { it.player.id }

        val autoSubRulesExplanation = "وفق لوائح FPL الرسمية: إذا لم يشارك لاعب أساسي (0 دقيقة)، يحل محله أول لاعب ميدان بديل (بديل 1 ثم 2 ثم 3) بشرط ألا يؤدي دخوله إلى مخالفة التشكيل القانوني (الحد الأدنى: 3 مدافعين، لاعبي وسط، ومهاجم واحد). حارس المرمى البديل يدخل فقط إذا لم يلعب الحارس الأساسي."

        val formationProtectionNote = if (startingFormation.defenders == 3) {
            "تنبيه تشكيل (3 مدافعين): نظراً لأن تشكيلتك الأساسية تضم 3 مدافعين فقط (الحد الأدنى المسموح)، فإن غياب أي مدافع أساسي يُلزم النظام بإدخال أول مدافع على الدكة حصراً، متجاوزاً أي لاعب وسط أو مهاجم يسبقه في الترتيب."
        } else if (startingFormation.forwards == 1) {
            "تنبيه تشكيل (مهاجم وحيد): نظراً للعب بمهاجم أساسي واحد، فإن غيابه يُلزم النظام بإشراك المهاجم البديل لضمان بقاء مهاجم واحد على الأقل في التشكيل."
        } else {
            "التشكيل الأساسي متوازن ومرن ويسمح بتبديلات تلقائية مباشرة دون عوائق قانونية."
        }

        return BenchIntelligenceResult(
            substitutes = orderedSubs,
            userOriginalBench = userOriginalBench,
            hasBenchOrderChanged = hasBenchOrderChanged,
            autoSubRulesExplanationAr = autoSubRulesExplanation,
            formationProtectionNoteAr = formationProtectionNote,
            disclaimerAr = "تنبيه: ترتيب الدكة المقترح لا يُعدّل حسابك على FPL تلقائياً. هذه توصية استرشادية، ويجب عليك تطبيق الترتيب يدوياً قبل الديدلاين."
        )
    }

    /**
     * تقييم قرارات الانتقالات والمفاضلة بين إجراء التبديل وتوفيره (Roll Transfer).
     */
    private fun evaluateTransferDecisions(
        squad: List<PlayerModel>,
        suggestions: List<TransferSuggestion>,
        freeTransfers: Int,
        bank: Double
    ): List<DecisionRecommendation> {
        val decisions = mutableListOf<DecisionRecommendation>()

        val injuredCount = squad.count { !it.availability.isAvailable || it.prediction?.riskLevel == RiskLevel.CRITICAL }
        val bestSuggestion = suggestions.firstOrNull()

        // هل نوفر التبديل (Roll Transfer)؟
        if (injuredCount == 0 && (bestSuggestion == null || bestSuggestion.netExpectedGain < 1.8)) {
            val rollAdvantage = if (freeTransfers < 5) {
                "توفير التبديل سيرفع رصيدك إلى ${freeTransfers + 1} تبديلات مجانية للجولة القادمة، مما يمنحك مرونة تكتيكية أكبر لمواكبة تقلبات السوق وضغط المباريات."
            } else {
                "رصيد التبديلات في حده الأقصى (5 تبديلات)؛ توفير التبديل لن يضيف رصيداً إضافياً وسيؤدي لحرق التبديل المجاني الإضافي."
            }

            decisions.add(
                DecisionRecommendation(
                    type = DecisionType.ROLL_TRANSFER,
                    titleAr = if (freeTransfers < 5) "توفير التبديل المجاني (Roll Transfer)" else "الرصيد في الحد الأقصى (5 تبديلات)",
                    summaryAr = if (freeTransfers < 5) {
                        "التشكيلة الحالية مستقرة ولا توجد إصابات ملحة؛ أفضل خيار هو ترحيل التبديل المجاني للجولة القادمة لمضاعفة المرونة."
                    } else {
                        "لديك 5 تبديلات مجانية؛ تجنب ترحيل التبديل لأن اللائحة لا تسمح برصيد أعلى من 5 وستفقد التبديل الإضافي."
                    },
                    expectedGain = 0.0,
                    confidence = DecisionConfidence(
                        level = ConfidenceLevel.HIGH,
                        scorePercent = 85,
                        factorsAr = listOf(
                            "جاهزية كاملة لعناصر التشكيلة الأساسية",
                            "عدم توفر بديل في السوق يحقق فارقاً يبرر استهلاك التبديل",
                            if (freeTransfers < 5) "تراكم التبديلات حتى ${freeTransfers + 1} تبديلات مجانية حسب لوائح 2026/27" else "الرصيد في حده الأقصى (5 تبديلات)"
                        )
                    ),
                    risk = DecisionRisk(
                        category = if (freeTransfers < 5) RiskCategory.SAFE else RiskCategory.BALANCED,
                        titleAr = if (freeTransfers < 5) "استراتيجية آمنة ومتحفظة" else "تنبيه الحد الأقصى",
                        descriptionAr = if (freeTransfers < 5) "تحافظ على استقرار الفريق وتمنحك قوة تفاوضية أكبر في الجولة التالية." else "توفير التبديل عند امتلاك 5 تبديلات يهدر التبديل الجديد."
                    ),
                    explanation = DecisionExplanation(
                        mainReasonAr = "عائد الاستبدال الحالي هامشي مقارنة بقيمة المرونة التكتيكية للتبديل الإضافي.",
                        keyDriversAr = listOf("غياب الإصابات المؤكدة", "مواجهات متوازنة للأساسيين", "زيادة الرصيد التراكمي للتبديلات")
                    ),
                    tradeOffs = listOf(
                        TacticalTradeOff(
                            advantageAr = rollAdvantage,
                            downsideOrRiskAr = "تفويت فرصة الاستفادة من لاعب صاعد في جولة واحدة إذا تألق البديل المقترح."
                        )
                    ),
                    isPrimaryAction = freeTransfers < 5
                )
            )
        }

        // إذا وجد تبديل مجاني جذاب ومبرر
        if (bestSuggestion != null && (injuredCount > 0 || bestSuggestion.netExpectedGain >= 1.8)) {
            val isUrgent = !bestSuggestion.playerOut.availability.isAvailable
            val isHit = bestSuggestion.hitPenalty > 0

            val type = if (isHit) DecisionType.TRANSFER_HIT else DecisionType.FREE_TRANSFER
            val title = if (isHit) {
                "إجراء تبديل مع خصم نقاط (-${bestSuggestion.hitPenalty}): ${bestSuggestion.playerOut.webName} ⬅ ${bestSuggestion.playerIn.webName}"
            } else {
                "تنفيذ تبديل مجاني: ${bestSuggestion.playerOut.webName} ⬅ ${bestSuggestion.playerIn.webName}"
            }

            val confidenceLevel = if (isUrgent) ConfidenceLevel.VERY_HIGH else if (bestSuggestion.netExpectedGain > 3.0) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            val riskCat = if (isHit) RiskCategory.AGGRESSIVE else if (isUrgent) RiskCategory.SAFE else RiskCategory.BALANCED

            decisions.add(
                DecisionRecommendation(
                    type = type,
                    titleAr = title,
                    summaryAr = "استبدال ${bestSuggestion.playerOut.webName} باللاعب ${bestSuggestion.playerIn.webName} يحقق فارق نقاط صافي متوقع +%.1f خلال 5 جولات.".format(Locale.US, bestSuggestion.netExpectedGain),
                    expectedGain = bestSuggestion.netExpectedGain,
                    confidence = DecisionConfidence(
                        level = confidenceLevel,
                        scorePercent = if (isUrgent) 92 else 78,
                        factorsAr = listOf(
                            "سعر البيع الفعلي للاعب المغادر: £%.1fm".format(Locale.US, bestSuggestion.playerOut.effectiveSellingPrice),
                            "سعر شراء اللاعب القادم: £%.1fm".format(Locale.US, bestSuggestion.playerIn.price),
                            "فارق النقاط المتوقع في الجولة: +%.1f".format(Locale.US, bestSuggestion.pointGainPerGw),
                            if (isHit) "خصم نقاط (-${bestSuggestion.hitPenalty}) مبرر بفارق 5 جولات" else "تبديل مجاني بدون أي خصم نقاط"
                        )
                    ),
                    risk = DecisionRisk(
                        category = riskCat,
                        titleAr = if (isHit) "مخاطرة هجومية مبررة بالأرقام" else "قرار تكتيكي متوازن",
                        descriptionAr = if (isHit) "يتطلب تعويض خصم الـ 4 نقاط عبر تفوق البديل في الجولات القادمة." else "يعزز التشكيلة دون أي خسارة في الرصيد النقطي."
                    ),
                    explanation = DecisionExplanation(
                        mainReasonAr = bestSuggestion.explanationAr,
                        keyDriversAr = listOf(
                            "الفورمة والإنتاجية المتوقعة للبديل (${bestSuggestion.playerIn.webName})",
                            "سهولة جدول مباريات القادم في الجولات الخمس المقبلة",
                            if (isUrgent) "اللاعب المغادر يعاني من إصابة مؤكدة" else "تراجع نسبي في عوائد اللاعب المغادر"
                        ),
                        warningsAr = if (isHit) listOf("تنفيذ هذا التبديل سيكلفك -${bestSuggestion.hitPenalty} نقاط فورية من رصيد الجولة!") else emptyList()
                    ),
                    tradeOffs = listOf(
                        TacticalTradeOff(
                            advantageAr = "مكسب نقطي متوقع +%.1f نقطة خلال أفق 5 جولات.".format(Locale.US, bestSuggestion.totalProjectedGain),
                            downsideOrRiskAr = if (isHit) "خصم ${bestSuggestion.hitPenalty} نقاط فوري من نقاط الجولة الحالية." else "استهلاك تبديل مجاني وتصفير تراكم التبديلات."
                        )
                    ),
                    isPrimaryAction = injuredCount > 0 || bestSuggestion.netExpectedGain >= 3.0
                )
            )
        }

        return decisions
    }

    /**
     * تقييم توصية الشريحة التكتيكية (Chip Recommendation).
     */
    private fun evaluateChipDecisionRecommendation(
        chipDecision: UnifiedChipDecision?,
        userHistory: UserChipHistory,
        currentGw: Int
    ): DecisionRecommendation? {
        if (chipDecision == null) return null

        val shouldActivate = chipDecision.shouldActivateNow

        return DecisionRecommendation(
            type = DecisionType.CHIP_ACTIVATION,
            titleAr = if (shouldActivate) {
                "تفعيل شريحة ${chipDecision.chip.titleAr} في الجولة $currentGw"
            } else {
                "الاحتفاظ بشريحة ${chipDecision.chip.titleAr} لجولة قادمة (GW ${chipDecision.recommendedGameweek})"
            },
            summaryAr = if (shouldActivate) {
                "فرصة ذهبية لتفعيل الشريحة؛ المكسب الصافي المتوقع +%.1f نقطة مقارنة باللعب العادي.".format(Locale.US, chipDecision.expectedGain)
            } else {
                "لا يُنصح بتفعيل أي شريحة في الجولة الحالية؛ الاحتفاظ بها للجولات المزدوجة (DGW) يعطي عائداً أعلى بكثير."
            },
            expectedGain = if (shouldActivate) chipDecision.expectedGain else 0.0,
            confidence = DecisionConfidence(
                level = when (chipDecision.confidence.name) {
                    "VERY_HIGH" -> ConfidenceLevel.VERY_HIGH
                    "HIGH" -> ConfidenceLevel.HIGH
                    "MODERATE" -> ConfidenceLevel.MEDIUM
                    else -> ConfidenceLevel.LOW
                },
                scorePercent = chipDecision.confidence.scorePercent,
                factorsAr = listOf(
                    "تقييم المخاطر: ${chipDecision.riskAssessmentAr}",
                    "الجولة الموصى بها: GW ${chipDecision.recommendedGameweek}",
                    "قواعد 2026/27: مسموح تفعيل شريحة واحدة فقط في الجولة"
                )
            ),
            risk = DecisionRisk(
                category = when (chipDecision.riskLevel) {
                    RiskLevel.LOW -> RiskCategory.SAFE
                    RiskLevel.MEDIUM -> RiskCategory.BALANCED
                    RiskLevel.HIGH -> RiskCategory.AGGRESSIVE
                    RiskLevel.CRITICAL -> RiskCategory.SPECULATIVE
                },
                titleAr = chipDecision.riskAssessmentAr,
                descriptionAr = if (shouldActivate) "تفعيل الشريحة لا يمكن الرجوع عنه بعد انطلاق الجولة." else "الاحتفاظ بالرقاقة خيار آمن يحمي مرونتك المستقبلية."
            ),
            explanation = DecisionExplanation(
                mainReasonAr = chipDecision.recommendationReasonAr,
                keyDriversAr = listOf(
                    "العائد الإضافي المتوقع للشريحة: +%.1f نقطة".format(Locale.US, chipDecision.expectedGain),
                    "توافق الشريحة مع المجموعة الحالية (Set ${ChipHalf.fromGameweek(currentGw).setNumber})"
                )
            ),
            tradeOffs = listOf(
                TacticalTradeOff(
                    advantageAr = if (shouldActivate) "قفزة نوعية في نقاط الجولة (+%.1f نقطة متوقعة).".format(Locale.US, chipDecision.expectedGain) else "الحفاظ على الشريحة لأقوى جولة دبل في الموسم.",
                    downsideOrRiskAr = if (shouldActivate) "استهلاك الشريحة نهائياً للمجموعة الحالية." else "تفويت مكاسب محتملة إذا حقق نجوم الفريق أرقاماً استثنائية هذا الأسبوع."
                )
            ),
            isPrimaryAction = shouldActivate
        )
    }

    private data class ValidTransferCombo(
        val suggestions: List<TransferSuggestion>,
        val gwGain: Double,
        val horizonGain: Double,
        val totalNetCost: Double
    )

    /**
     * فحص توافيقي للبحث عن أفضل تركيبة من m تبديلات مع احترام الميزانية وقيود الأندية ومراكز اللاعبين
     */
    private fun findBestTransferCombination(
        suggestions: List<TransferSuggestion>,
        squad15: List<PlayerModel>,
        bank: Double,
        targetCount: Int
    ): ValidTransferCombo? {
        if (targetCount <= 0 || suggestions.size < targetCount) return null

        val validCombos = mutableListOf<ValidTransferCombo>()

        fun generateCombos(startIdx: Int, current: MutableList<TransferSuggestion>) {
            if (current.size == targetCount) {
                // 1. التأكد من اختلاف اللاعبين المغادرين والقادمين
                val outIds = current.map { it.playerOut.id }
                if (outIds.distinct().size != targetCount) return

                val inIds = current.map { it.playerIn.id }
                if (inIds.distinct().size != targetCount) return

                // 2. التحقق من عدم وجود أي لاعب قادم في التشكيلة الحالية
                val currentSquadIds = squad15.map { it.id }.toSet()
                if (inIds.any { it in currentSquadIds }) return

                // 3. التحقق من سقف الميزانية اعتماداً على سعر البيع الفعلي
                val totalCost = current.sumOf { it.playerIn.price - it.playerOut.effectiveSellingPrice }
                if (totalCost > bank + 0.001) return

                // 4. التحقق من قيد النادي (3 لاعبين كحد أقصى من كل نادٍ)
                val clubCounts = squad15.groupingBy { it.teamId }.eachCount().toMutableMap()
                for (s in current) {
                    val outClub = s.playerOut.teamId
                    val inClub = s.playerIn.teamId
                    clubCounts[outClub] = (clubCounts[outClub] ?: 1) - 1
                    clubCounts[inClub] = (clubCounts[inClub] ?: 0) + 1
                }
                if (clubCounts.values.any { it > 3 }) return

                val gwGain = round(current.sumOf { it.pointGainPerGw } * 10.0) / 10.0
                val horizonGain = round(current.sumOf { it.totalProjectedGain } * 10.0) / 10.0
                validCombos.add(ValidTransferCombo(current.toList(), gwGain, horizonGain, totalCost))
                return
            }

            for (i in startIdx until suggestions.size) {
                current.add(suggestions[i])
                generateCombos(i + 1, current)
                current.removeAt(current.size - 1)
            }
        }

        generateCombos(0, mutableListOf())

        return validCombos.maxByOrNull { it.horizonGain }
    }

    /**
     * بناء مقارنة تفصيلية رياضية ومنطقية بين السيناريوهات التكتيكية الخمسة على خط أساس مرجعي موحد.
     */
    private fun buildScenarioComparison(
        baseSquadPoints: Double,
        optimizedSquad: OptimizedSquad,
        transferSuggestions: List<TransferSuggestion>,
        freeTransfers: Int,
        unifiedChip: UnifiedChipDecision?,
        captain: PlayerModel,
        baseCapXpts: Double,
        bank: Double,
        squad15: List<PlayerModel>
    ): ScenarioComparisonResult {
        val scenarios = mutableListOf<DecisionScenario>()

        // 1. حساب الأساس النقطي لأفق 5 جولات من واقع توقعات الجولات الفعلية (DGW/BGW/FDR)
        val base5GwPoints = calculateStarting11_5GwPoints(optimizedSquad.starting11, captain)
        val baseGwPoints = baseSquadPoints

        // -------------------------------------------------------------------------
        // السيناريو 2: تعديل التشكيل والشارة فقط (Lineup & Captaincy Only)
        // الأساس التكتيكي الداخلي الموحد: استخراج أقصى طاقة من التشكيلة بـ 0 تبديلات و 0 خصم
        // -------------------------------------------------------------------------
        val s2GwPts = baseGwPoints
        val s2Horizon = base5GwPoints
        val s2Advantage = 0.0 // نقطة الأساس المرجعية الموحدة (Unified Baseline)
        scenarios.add(
            DecisionScenario(
                id = ScenarioId.LINEUP_ONLY,
                titleAr = "السيناريو 2: تعديل التشكيل والشارة فقط",
                descriptionAr = "الاعتماد على التشكيل الأمثل (${optimizedSquad.formation.name}) واختيار ${captain.webName} كابتن دون أي تبديل خارجي، يمثل خط الأساس المرجعي الموحد للأداء الفعلي لتشكيلتك.",
                projectedGameweekPoints = s2GwPts,
                hitCost = 0,
                netGameweekPoints = s2GwPts,
                projectedHorizon5GwPoints = s2Horizon,
                netHorizonAdvantage = s2Advantage,
                risk = DecisionRisk(RiskCategory.SAFE, "آمن ومجاني", "لا توجد أي خصومات نقاط مع الاعتماد على أفضل 11 لاعباً متاحين."),
                verdictAr = "خط الأساس المرجعي الموحد للأداء الميداني دون أي تبديلات خارجية.",
                isRecommended = false,
                tagAr = "تحسين داخلي",
                transfersCount = 0,
                extraTransfersCount = 0
            )
        )

        // -------------------------------------------------------------------------
        // السيناريو 1: الثبات وتوفير التبديل (Roll Transfer)
        // ترحيل التبديل لمضاعفة المرونة للجولة القادمة (تراكم حتى 5 تبديلات وفق لوائح 2026/27)
        // النقاط الميدانية مطابقة تماماً لخط الأساس (بدون أي نقاط افتراضية وهمية +1.5)
        // -------------------------------------------------------------------------
        val canBankMore = freeTransfers < 5
        val s1GwPts = baseGwPoints
        val s1Horizon = base5GwPoints
        val s1Advantage = 0.0
        val s1Verdict = if (canBankMore) {
            "توفير التبديل يرفع رصيدك إلى ${freeTransfers + 1} تبديلات مجانية للجولة القادمة، مما يمنحك مرونة تكتيكية أكبر دون إضافة نقاط افتراضية للتوقع الميداني."
        } else {
            "تحذير: رصيدك في حده الأقصى (5 تبديلات)؛ ترحيل التبديل سيؤدي لحرق التبديل المجاني الإضافي دون أي زيادة في الرصيد!"
        }

        scenarios.add(
            DecisionScenario(
                id = ScenarioId.ROLL_TRANSFER,
                titleAr = "السيناريو 1: الثبات وتوفير التبديل (Roll FT)",
                descriptionAr = if (canBankMore) {
                    "الاحتفاظ بالتبديل وترحيله للجولة القادمة لرفع الرصيد إلى ${freeTransfers + 1} تبديلات (قواعد 2026/27)، مما يعزز مرونتك التكتيكية مستقبلاً دون تغيير في نقاط التشكيلة الحالية."
                } else {
                    "رصيدك يبلغ الحد الأقصى (5 تبديلات)؛ توفير التبديل الآن سيهدر التبديل المجاني المكتسب حديثاً دون أي مكسب إضافي."
                },
                projectedGameweekPoints = s1GwPts,
                hitCost = 0,
                netGameweekPoints = s1GwPts,
                projectedHorizon5GwPoints = s1Horizon,
                netHorizonAdvantage = s1Advantage,
                risk = if (canBankMore) DecisionRisk(RiskCategory.SAFE, "مرونة تكتيكية واستقرار", "يمنحك أفضلية تراكم التبديلات للجولات الحرجة دون مخاطرة نقطية.") else DecisionRisk(RiskCategory.BALANCED, "حرق تبديل مجاني", "التوفير عند 5 تبديلات يهدر التبديل الجديد."),
                verdictAr = s1Verdict,
                isRecommended = false,
                tagAr = if (canBankMore) "توفير التبديل (+1 FT)" else "الحد الأقصى (5 FT)",
                transfersCount = 0,
                extraTransfersCount = 0
            )
        )

        // -------------------------------------------------------------------------
        // السيناريو 3: تحسين التبديلات المجانية المتعددة (Multi-Free-Transfer Optimization)
        // فحص توافيقي للتبديلات: اختبار استخدام 1، 2، حتى K تبديل، واختيار التركيبة ذات أعلى عائد صافٍ
        // -------------------------------------------------------------------------
        var bestFreeCombo: ValidTransferCombo? = null
        if (freeTransfers > 0) {
            for (m in 1..freeTransfers.coerceAtMost(5)) {
                val combo = findBestTransferCombination(transferSuggestions, squad15, bank, m)
                if (combo != null) {
                    // اختيار التركيبة ذات العائد الأعلى؛ وفي حال التساوي نفضل العدد الأقل لتوفير تبديلات
                    if (bestFreeCombo == null || combo.horizonGain > bestFreeCombo.horizonGain) {
                        bestFreeCombo = combo
                    }
                }
            }
        }

        val freeCountUsed = bestFreeCombo?.suggestions?.size ?: 0
        val freeCountBanked = freeTransfers - freeCountUsed
        val s3GwGain = bestFreeCombo?.gwGain ?: 0.0
        val s3HorizonGain = bestFreeCombo?.horizonGain ?: 0.0
        val s3GwPts = round((baseGwPoints + s3GwGain) * 10.0) / 10.0
        val s3Horizon = round((base5GwPoints + s3HorizonGain) * 10.0) / 10.0
        val s3Advantage = round(s3HorizonGain * 10.0) / 10.0

        val s3Description = if (bestFreeCombo != null && s3Advantage > 0.0) {
            if (freeCountBanked > 0) {
                "تنفيذ $freeCountUsed تبديل مجاني (${bestFreeCombo.suggestions.joinToString { "${it.playerOut.webName} ⬅ ${it.playerIn.webName}" }}) بمكسب صافي متوقع +$s3Advantage ن، مع ترحيل $freeCountBanked تبديل للجولة القادمة لتحقيق أقصى توازن بين العائد والمرونة."
            } else {
                "تنفيذ كافة التبديلات المجانية المتاحة ($freeCountUsed تبديلات): (${bestFreeCombo.suggestions.joinToString { "${it.playerOut.webName} ⬅ ${it.playerIn.webName}" }}) بصافي مكسب متوقع +$s3Advantage ن دون أي خصم نقاط."
            }
        } else {
            "لا توجد تركيبة تبديل مجاني في السوق تحقق تفوقاً إيجابياً ملموساً على عناصر التشكيلة الحالية؛ الأفضل هو توفير التبديل."
        }

        scenarios.add(
            DecisionScenario(
                id = ScenarioId.ONE_FREE_TRANSFER,
                titleAr = "السيناريو 3: التبديل المجاني الأمثل ($freeCountUsed من أصل $freeTransfers FT)",
                descriptionAr = s3Description,
                projectedGameweekPoints = s3GwPts,
                hitCost = 0,
                netGameweekPoints = s3GwPts,
                projectedHorizon5GwPoints = s3Horizon,
                netHorizonAdvantage = s3Advantage,
                risk = DecisionRisk(RiskCategory.BALANCED, "تطوير متوازن ومجاني", "استغلال الرصيد المجاني لرفع كفاءة التشكيلة دون أي خصومات."),
                verdictAr = if (s3Advantage >= 2.0) "موصى به؛ يحقق مكسباً صافياً (+%.1f ن) دون أي ضريبة خصم.".format(Locale.US, s3Advantage) else "مكسب هامشي؛ توفير التبديل قد يكون أكثر جدوى تكتيكية.",
                isRecommended = false,
                tagAr = "تبديل مجاني ($freeCountUsed)",
                transfersCount = freeCountUsed,
                extraTransfersCount = 0
            )
        )

        // -------------------------------------------------------------------------
        // السيناريو 4: تبديل إضافي مع خصم نقاط (-4 لكل تبديل مدفوع)
        // فحص توافيقي للتبديلات الإضافية: [freeTransfers + 1] و [freeTransfers + 2] مع خصم نقاط
        // -------------------------------------------------------------------------
        var bestHitCombo: ValidTransferCombo? = null
        var bestHitCost = 4
        var bestHitNetAdvantage = -999.0

        for (extra in 1..2) {
            val targetM = freeTransfers + extra
            val combo = findBestTransferCombination(transferSuggestions, squad15, bank, targetM)
            if (combo != null) {
                val hitCost = extra * 4
                val netAdv = combo.horizonGain - hitCost
                if (netAdv > bestHitNetAdvantage) {
                    bestHitNetAdvantage = netAdv
                    bestHitCombo = combo
                    bestHitCost = hitCost
                }
            }
        }

        val s4GwGain = bestHitCombo?.gwGain ?: 0.0
        val s4HorizonGain = bestHitCombo?.horizonGain ?: 0.0
        val totalHitCost = if (bestHitCombo != null) bestHitCost else 4
        val s4GwPts = round((baseGwPoints + s4GwGain) * 10.0) / 10.0
        val s4NetGw = round((s4GwPts - totalHitCost) * 10.0) / 10.0
        val s4Horizon = round((base5GwPoints + s4HorizonGain - totalHitCost) * 10.0) / 10.0
        val s4Advantage = round((s4HorizonGain - totalHitCost) * 10.0) / 10.0
        val s4TotalCount = bestHitCombo?.suggestions?.size ?: (freeTransfers + 1)
        val s4ExtraCount = totalHitCost / 4

        val s4Description = if (bestHitCombo != null) {
            "إجراء $s4TotalCount تبديلات ($freeTransfers مجاني + $s4ExtraCount إضافي بخصم -$totalHitCost نقاط): (${bestHitCombo.suggestions.joinToString { "${it.playerOut.webName} ⬅ ${it.playerIn.webName}" }})."
        } else {
            "لا توجد تركيبة تبديل إضافية بالخصم تعوض ضريبة الخصم (-$totalHitCost ن) في السوق الحالي."
        }

        scenarios.add(
            DecisionScenario(
                id = ScenarioId.TRANSFER_WITH_HIT,
                titleAr = "السيناريو 4: تبديل مع خصم نقاط (-$totalHitCost Hit)",
                descriptionAr = s4Description,
                projectedGameweekPoints = s4GwPts,
                hitCost = totalHitCost,
                netGameweekPoints = s4NetGw,
                projectedHorizon5GwPoints = s4Horizon,
                netHorizonAdvantage = s4Advantage,
                risk = DecisionRisk(RiskCategory.AGGRESSIVE, "هجومي ومخاطرة نقطية", "خصم -$totalHitCost نقطة يتطلب تفوقاً صريحاً للبدلاء لتعويض الضريبة النقطية."),
                verdictAr = if (s4Advantage >= 3.0) "مبرر إحصائياً؛ العائد الممتد يعوض تكلفة السالب ويفيض بمكسب صافي +%.1f ن.".format(Locale.US, s4Advantage) else "غير مبرر رياضياً؛ العائد المتوقع لا يعوض ضريبة الخصم (-$totalHitCost ن).",
                isRecommended = false,
                tagAr = "خصم نقاط (-$totalHitCost)",
                transfersCount = s4TotalCount,
                extraTransfersCount = s4ExtraCount
            )
        )

        // -------------------------------------------------------------------------
        // السيناريو 5: تفعيل الشريحة المقترحة (Chip Activation)
        // توحيد القياس على أفق 5 جولات:
        // - تريبل كابتن وبينش بوست وفري هيت: أثرها النقطي الإضافي يتحقق في الجولة الحالية فقط ويدخل ضمن تراكم الـ 5 جولات (Horizon5Gw = Base5Gw + Gain).
        // - وايلد كارد: إعادة هيكلة دائمة يمتد أثرها وتفوقها عبر كامل أفق الـ 5 جولات (Horizon5Gw = Base5Gw + 5GwDelta).
        // -------------------------------------------------------------------------
        val chipActive = unifiedChip != null && unifiedChip.shouldActivateNow
        val chipType = unifiedChip?.chip

        var chipGwGain = 0.0
        var chipHorizonGain = 0.0

        if (chipActive && chipType != null) {
            when (chipType) {
                ChipType.TRIPLE_CAPTAIN -> {
                    // تريبل كابتن: مكسب الكابتن الإضافي (1x) في الجولة الحالية فقط (مدة الأثر: جولة واحدة)
                    chipGwGain = baseCapXpts
                    chipHorizonGain = baseCapXpts
                }
                ChipType.BENCH_BOOST -> {
                    // بينش بوست: نقاط الدكة الأربعة كاملة في الجولة الحالية فقط (مدة الأثر: جولة واحدة)
                    chipGwGain = unifiedChip.currentGwGain
                    chipHorizonGain = unifiedChip.currentGwGain
                }
                ChipType.FREE_HIT -> {
                    // فري هيت: تشكيلة طوارئ مؤقتة لجولة واحدة وتعود التشكيلة الأصلية بعدها (مدة الأثر: جولة واحدة)
                    chipGwGain = unifiedChip.currentGwGain
                    chipHorizonGain = unifiedChip.currentGwGain
                }
                ChipType.WILDCARD -> {
                    // وايلد كارد: بناء دائم يستمر أثره وتفوقه التراكمي وتوفير النقاط السالبة عبر كامل الـ 5 جولات (مدة الأثر: 5 جولات)
                    chipGwGain = unifiedChip.currentGwGain
                    chipHorizonGain = unifiedChip.expectedGain
                }
            }
        }

        val s5GwPts = round((baseGwPoints + chipGwGain) * 10.0) / 10.0
        val s5Horizon = round((base5GwPoints + chipHorizonGain) * 10.0) / 10.0
        val s5Advantage = round(chipHorizonGain * 10.0) / 10.0

        scenarios.add(
            DecisionScenario(
                id = ScenarioId.CHIP_ACTIVATION,
                titleAr = if (chipActive && chipType != null) "السيناريو 5: تفعيل شريحة ${chipType.titleAr}" else "السيناريو 5: الاحتفاظ بالشرائح للمستقبل",
                descriptionAr = if (chipActive && chipType != null) {
                    when (chipType) {
                        ChipType.FREE_HIT -> "تفعيل فري هيت لجولة واحدة فقط (+%.1f ن)، وتعود التشكيلة الأصلية تلقائياً في الجولة التالية (مدة الأثر: جولة واحدة).".format(Locale.US, chipGwGain)
                        ChipType.WILDCARD -> "تفعيل وايلد كارد لتجديد التشكيلة بالكامل بأثر دائم عبر الـ 5 جولات (+%.1f ن، و +%.1f ن في الجولة الحالية).".format(Locale.US, chipHorizonGain, chipGwGain)
                        ChipType.TRIPLE_CAPTAIN -> "مضاعفة نقاط الكابتن ${captain.webName} إلى 3 أضعاف (3x) بمكسب إضافي +%.1f نقطة للجولة الحالية.".format(Locale.US, chipGwGain)
                        ChipType.BENCH_BOOST -> "احتساب نقاط بدلاء الدكة الأربعة كاملة بمكسب إضافي +%.1f نقطة للجولة الحالية.".format(Locale.US, chipGwGain)
                    }
                } else {
                    "الاحتفاظ بكافة الشرائح لجولات الدبل (DGW) والبلانك (BGW) القادمة ذات العوائد الأكبر."
                },
                projectedGameweekPoints = s5GwPts,
                hitCost = 0,
                netGameweekPoints = s5GwPts,
                projectedHorizon5GwPoints = s5Horizon,
                netHorizonAdvantage = s5Advantage,
                risk = if (chipActive) DecisionRisk(RiskCategory.BALANCED, "استغلال التوقيت المثالي", "تفعيل الشريحة محكوم بضوابط 2026/27 الرسمية.") else DecisionRisk(RiskCategory.SAFE, "حماية الرصيد الاستراتيجي", "عدم حرق الشريحة في جولة عادية."),
                verdictAr = if (chipActive) "فرصة ذهبية لتفعيل الشريحة الآن." else "لا تفعل أي شريحة في هذه الجولة.",
                isRecommended = chipActive,
                tagAr = if (chipActive) "تفعيل الشريحة" else "احتفاظ بالشريحة",
                transfersCount = 0,
                extraTransfersCount = 0
            )
        )

        // تحديد السيناريو الفائز استناداً إلى صافي الميزة على أفق 5 جولات ومستوى الأمان وقواعد ترحيل التبديلات
        val winningScenario = when {
            chipActive -> scenarios.first { it.id == ScenarioId.CHIP_ACTIVATION }
            !canBankMore && s3Advantage > 0.0 -> scenarios.first { it.id == ScenarioId.ONE_FREE_TRANSFER }
            s3Advantage >= 2.5 -> scenarios.first { it.id == ScenarioId.ONE_FREE_TRANSFER }
            s4Advantage >= 3.0 -> scenarios.first { it.id == ScenarioId.TRANSFER_WITH_HIT }
            canBankMore -> scenarios.first { it.id == ScenarioId.ROLL_TRANSFER }
            s3Advantage > 0.0 -> scenarios.first { it.id == ScenarioId.ONE_FREE_TRANSFER }
            else -> scenarios.first { it.id == ScenarioId.LINEUP_ONLY }
        }

        // تحديث حالة التوصية
        val updatedScenarios = scenarios.map {
            it.copy(isRecommended = (it.id == winningScenario.id))
        }

        val summary = "مقارنة السيناريوهات تُظهر أن ${winningScenario.titleAr} هو الخيار الأكثر جدوى إحصائياً بصافي عائد ممتد متوقع +%.1f نقطة.".format(Locale.US, winningScenario.netHorizonAdvantage)

        return ScenarioComparisonResult(
            scenarios = updatedScenarios,
            bestScenario = winningScenario.copy(isRecommended = true),
            comparisonSummaryAr = summary
        )
    }

    /**
     * تركيب القرار البطل الذي يتصدر واجهة المستخدم ويجيب عن السؤال الجوهري مباشرة.
     */
    private fun synthesizeHeroDecision(
        chipRec: DecisionRecommendation?,
        transferDecisions: List<DecisionRecommendation>,
        scenarioComparison: ScenarioComparisonResult,
        captain: PlayerModel,
        viceCaptain: PlayerModel,
        formation: Formation,
        projectedPoints: Double
    ): DecisionRecommendation {
        // إذا كان هناك شريحة موصى بتفعيلها الآن
        if (chipRec != null && chipRec.isPrimaryAction) {
            return chipRec
        }

        // إذا كان هناك قرار تبديل ملح (إصابة أو عائد هائل)
        val urgentTransfer = transferDecisions.firstOrNull { it.isPrimaryAction }
        if (urgentTransfer != null) {
            return urgentTransfer
        }

        // إذا كان السيناريو الأفضل هو توفير التبديل
        val rollDecision = transferDecisions.firstOrNull { it.type == DecisionType.ROLL_TRANSFER }
        if (rollDecision != null) {
            return rollDecision
        }

        // الخيار الافتراضي: الاعتماد على التشكيل الأمثل والشارة
        val capXpts = captain.prediction?.expectedPoints ?: 6.0
        return DecisionRecommendation(
            type = DecisionType.CAPTAINCY,
            titleAr = "تثبيت شارة الكابتن على ${captain.webName} والاعتماد على تشكيل ${formation.name}",
            summaryAr = "تشكيلتك الأساسية متوازنة وتعد بجولة قوية؛ التركيز الأساسي يجب أن يكون على حسم الكابتن مع تثبيت ${viceCaptain.webName} نائباً.",
            expectedGain = capXpts,
            confidence = DecisionConfidence(
                level = ConfidenceLevel.HIGH,
                scorePercent = 82,
                factorsAr = listOf(
                    "معدل نقاط متوقعة للكابتن: %.1f ن (تتضاعف إلى %.1f ن)".format(Locale.US, capXpts, capXpts * 2.0),
                    "أمان دقائق اللعب بنسبة %d%%".format(Locale.US, (captain.prediction?.startProbability?.times(100))?.toInt() ?: 90),
                    "جاهزية كاملة لنائب الكابتن (${viceCaptain.webName})"
                )
            ),
            risk = DecisionRisk(
                category = RiskCategory.SAFE,
                titleAr = "قرار تكتيكي آمن",
                descriptionAr = "يعتمد على الخيارات الأكثر موثوقية واستقراراً في الجولة."
            ),
            explanation = DecisionExplanation(
                mainReasonAr = "الرهان على النجم الأكثر إنتاجية في مواجهة مثالية مع تأمين خطوط الفريق.",
                keyDriversAr = listOf(
                    "إجمالي نقاط متوقعة للتشكيلة: %.1f نقطة".format(Locale.US, projectedPoints),
                    "تشكيل ${formation.name} يحقق أعلى كفاءة للنقاط المتوقعة",
                    "نائب الكابتن ${viceCaptain.webName} جاهز تماماً للطوارئ"
                )
            ),
            tradeOffs = listOf(
                TacticalTradeOff(
                    advantageAr = "مضاعفة نقاط النجم الأعلى سقفاً في تشكيلتك وحماية الترتيب.",
                    downsideOrRiskAr = "المخاطرة المعتادة لتقلبات نتائج المباريات الفردية."
                )
            ),
            isPrimaryAction = true
        )
    }

    /**
     * حصر التنبيهات التكتيكية والطبية الصريحة.
     */
    private fun collectTacticalWarnings(
        squad: List<PlayerModel>,
        formation: Formation,
        captain: PlayerModel,
        freeTransfers: Int
    ): List<String> {
        val warnings = mutableListOf<String>()

        // 1. تنبيهات الإصابات والشكوك
        val injured = squad.filter { !it.availability.isAvailable }
        if (injured.isNotEmpty()) {
            warnings.add("تنبيه طبي: لديك ${injured.size} لاعبين خارج الخدمة أو مصابين مؤكداً (${injured.joinToString { it.webName }}).")
        }

        val doubts = squad.filter { it.availability.isAvailable && (it.availability.chanceOfPlayingNextRound ?: 100) < 100 }
        if (doubts.isNotEmpty()) {
            warnings.add("شكوك طبية: ${doubts.joinToString { "${it.webName} (${it.availability.chanceOfPlayingNextRound}%)" }} تحت الملاحظة؛ تحقق من المؤتمرات الصحفية قبل الديدلاين.")
        }

        // 2. تنبيه الكابتن
        val capChance = captain.availability.chanceOfPlayingNextRound
        if (capChance != null && capChance < 100) {
            warnings.add("تحذير كابتن: الكابتن المختار (${captain.webName}) تحوم حوله شكوك طبية بنسبة $capChance%؛ تأكد من وضع شارة النائب على لاعب أساسي مضمون المشاركة.")
        }

        // 3. تنبيه قوانين التشكيل
        if (formation.defenders == 3) {
            warnings.add("قانون التشكيل (3 مدافعين): أي غياب لمدافع أساسي يستدعي مدافعاً من الدكة حصراً ولا يُعوّض بلاعب وسط أو مهاجم.")
        }

        // 4. تنبيه الحد الأقصى للتبديلات المجانية
        if (freeTransfers >= 5) {
            warnings.add("تنبيه رصيد التبديلات: رصيدك في حده الأقصى (5 تبديلات). ترحيل التبديل للجولة القادمة سيهدر التبديل المجاني الجديد؛ يفضل استغلال تبديل واحد على الأقل.")
        }

        return warnings
    }
}
