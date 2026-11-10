package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPrediction
import com.fplmanagerai.data.model.RiskLevel
import kotlin.math.round
import kotlin.math.roundToInt

data class CaptainCandidate(
    val playerId: Int,
    val playerName: String,
    val expectedPoints: Double,
    val captainPoints: Double,
    val expectedMinutes: Double,
    val startProbability: Double,
    val confidenceScore: Double,
    val captaincyScore: Double,
    val riskLevel: RiskLevel,
    val reasonAr: String,
    val player: PlayerModel? = null
) {
    /**
     * مؤشر صلاحية استخدام رقاقة Triple Captain
     */
    val isTripleCaptainViable: Boolean
        get() = captaincyScore >= 80.0 && riskLevel == RiskLevel.LOW && expectedPoints >= 7.5

    /**
     * النقاط المتوقعة في حال تفعيل Triple Captain (3 أضعاف)
     */
    val tripleCaptainPoints: Double
        get() = round(expectedPoints * 3.0 * 10.0) / 10.0
}

data class TripleCaptainAssessment(
    val isViable: Boolean,
    val projectedGain: Double,
    val captaincyScore: Double,
    val isDoubleGameweek: Boolean,
    val verdictAr: String,
    val reasonAr: String
)

data class CaptaincyRecommendation(
    val captain: CaptainCandidate?,
    val viceCaptain: CaptainCandidate?,
    val alternatives: List<CaptainCandidate>,
    val summaryAr: String,
    val tripleCaptainAssessment: TripleCaptainAssessment? = null
)

class CaptaincyAnalyzer {

    /**
     * ترشيح الكابتن ونائب الكابتن والبدائل اعتماداً على قائمة اللاعبين وخريطة التوقعات.
     */
    fun recommend(
        players: List<PlayerModel>,
        predictions: Map<Int, PlayerPrediction>
    ): CaptaincyRecommendation {
        if (players.isEmpty()) {
            return CaptaincyRecommendation(
                captain = null,
                viceCaptain = null,
                alternatives = emptyList(),
                summaryAr = "لا يوجد لاعبون متاحون لاختيار الكابتن.",
                tripleCaptainAssessment = null
            )
        }

        val candidates = players.mapNotNull { player ->
            val prediction = predictions[player.id] ?: player.prediction
            if (prediction != null) {
                evaluateCandidate(player, prediction)
            } else {
                val fallbackPred = createFallbackPrediction(player)
                evaluateCandidate(player, fallbackPred)
            }
        }

        // استبعاد غير المؤهلين (المصابون، الموقوفون، ذوو المخاطرة الحرجة، منعدمو الدقائق)
        val eligibleCandidates = candidates
            .filter { it.captaincyScore > 0.0 && it.riskLevel != RiskLevel.CRITICAL }
            .sortedWith(
                compareByDescending<CaptainCandidate> { it.captaincyScore }
                    .thenByDescending { it.expectedPoints }
                    .thenByDescending { it.startProbability }
                    .thenByDescending { it.expectedMinutes }
            )

        if (eligibleCandidates.isEmpty()) {
            return CaptaincyRecommendation(
                captain = null,
                viceCaptain = null,
                alternatives = emptyList(),
                summaryAr = "لا يوجد لاعبون مؤهلون لحمل شارة القيادة نظراً لوجود إصابات أو مخاطر حرجة.",
                tripleCaptainAssessment = null
            )
        }

        val captain = eligibleCandidates[0]

        // اختيار نائب الكابتن: ثاني أفضل مرشح مؤهل ومختلف عن القائد
        val viceCaptain = eligibleCandidates.getOrNull(1)
            ?: candidates.firstOrNull { it.playerId != captain.playerId && it.riskLevel != RiskLevel.CRITICAL }

        val alternatives = eligibleCandidates
            .filter { it.playerId != captain.playerId && (viceCaptain == null || it.playerId != viceCaptain.playerId) }
            .take(3)

        // تقييم صلاحية تفعيل التريبل كابتن
        val captainPlayer = players.firstOrNull { it.id == captain.playerId }
        val tcAssessment = evaluateTripleCaptain(captain, captainPlayer)

        val summary = generateSummary(captain, viceCaptain, alternatives, tcAssessment)

        return CaptaincyRecommendation(
            captain = captain,
            viceCaptain = viceCaptain,
            alternatives = alternatives,
            summaryAr = summary,
            tripleCaptainAssessment = tcAssessment
        )
    }

    /**
     * ترشيح الكابتن باستخدام قائمة اللاعبين وقائمة التوقعات.
     */
    fun recommend(
        players: List<PlayerModel>,
        predictions: List<PlayerPrediction>
    ): CaptaincyRecommendation = recommend(
        players = players,
        predictions = predictions.associateBy { it.playerId }
    )

    /**
     * ترشيح الكابتن عند توفر التوقعات مسبقاً داخل PlayerModel.prediction.
     */
    fun recommend(
        players: List<PlayerModel>
    ): CaptaincyRecommendation = recommend(
        players = players,
        predictions = players.mapNotNull { player ->
            player.prediction?.let { player.id to it }
        }.toMap()
    )

    /**
     * تقييم مرشح الكابتن بناءً على التوقعات.
     */
    fun evaluateCandidate(
        player: PlayerModel,
        prediction: PlayerPrediction
    ): CaptainCandidate {
        val expPoints = prediction.expectedPoints
        val expMinutes = prediction.expectedMinutes
        val startProb = prediction.startProbability.coerceIn(0.0, 1.0)
        val confidence = prediction.confidenceScore.coerceIn(0.0, 1.0)
        val risk = prediction.riskLevel

        val status = player.availability.status.lowercase()
        val chance = player.availability.chanceOfPlayingNextRound

        // التحقق من الاستبعاد الصريح
        val isExplicitlyUnavailable =
            status == "i" || status == "s" || status == "u" || chance == 0 || risk == RiskLevel.CRITICAL

        val captaincyScore = if (isExplicitlyUnavailable) {
            0.0
        } else {
            calculateCaptaincyScore(
                expectedPoints = expPoints,
                expectedMinutes = expMinutes,
                startProbability = startProb,
                confidenceScore = confidence,
                riskLevel = risk
            )
        }

        val captainPoints = if (prediction.predictedCaptainPoints > 0.0) {
            prediction.predictedCaptainPoints
        } else {
            round1(expPoints * 2.0)
        }

        val displayName = player.webName.ifBlank { player.fullName }

        val reason = generateReason(
            playerName = displayName,
            expectedPoints = expPoints,
            expectedMinutes = expMinutes,
            startProbability = startProb,
            captaincyScore = captaincyScore,
            riskLevel = risk,
            isUnavailable = isExplicitlyUnavailable,
            statusReason = player.availability.labelAr
        )

        return CaptainCandidate(
            playerId = player.id,
            playerName = displayName,
            expectedPoints = round1(expPoints),
            captainPoints = round1(captainPoints),
            expectedMinutes = round1(expMinutes),
            startProbability = round2(startProb),
            confidenceScore = round2(confidence),
            captaincyScore = captaincyScore,
            riskLevel = risk,
            reasonAr = reason,
            player = player
        )
    }

    /**
     * حساب تقييم الكابتن (0.0 إلى 100.0) اعتماداً على:
     * - النقاط المتوقعة (Expected Points) [50%]
     * - دقائق اللعب المتوقعة واحتمال البدء أساسياً (Expected Minutes & Start Probability) [30%]
     * - درجة ثقة النموذج (Confidence Score) [20%]
     * - تصنيف المخاطر (Risk Level Multiplier)
     */
    fun calculateCaptaincyScore(
        expectedPoints: Double,
        expectedMinutes: Double,
        startProbability: Double,
        confidenceScore: Double,
        riskLevel: RiskLevel
    ): Double {
        if (expectedPoints <= 0.0 || expectedMinutes <= 0.0 || startProbability <= 0.0 || riskLevel == RiskLevel.CRITICAL) {
            return 0.0
        }

        // 1. وزن النقاط المتوقعة (50 نقطة كحد أقصى عند 10 نقاط xPts)
        val pointsWeight = (expectedPoints / 10.0).coerceIn(0.0, 1.2) * 50.0

        // 2. أمان المشاركة والدقائق (30 نقطة كحد أقصى)
        val minutesRatio = (expectedMinutes / 90.0).coerceIn(0.0, 1.0)
        val startProb = startProbability.coerceIn(0.0, 1.0)
        val securityWeight = (startProb * 0.60 + minutesRatio * 0.40) * 30.0

        // 3. مستوى ثقة النموذج بالبيانات المتاحة (20 نقطة كحد أقصى)
        val confidenceRatio = confidenceScore.coerceIn(0.0, 1.0)
        val confidenceWeight = confidenceRatio * 20.0

        val rawScore = pointsWeight + securityWeight + confidenceWeight

        // 4. معامل المخاطرة: خفض التقييم وفق درجة المخاطرة
        val riskMultiplier = when (riskLevel) {
            RiskLevel.LOW -> 1.00
            RiskLevel.MEDIUM -> 0.88
            RiskLevel.HIGH -> 0.65
            RiskLevel.CRITICAL -> 0.00
        }

        return round1((rawScore * riskMultiplier).coerceIn(0.0, 100.0))
    }

    /**
     * تقييم موضوعي ومستقل لصلاحية استخدام رقاقة Triple Captain.
     * لا تفترض تفعيل الرقاقة لمجرد أن اللاعب هو أفضل كابتن، بل تفحص المعايير الاستثنائية.
     */
    fun evaluateTripleCaptain(
        captain: CaptainCandidate?,
        player: PlayerModel?,
        fixturesCountInGw: Int = player?.upcomingFixtures?.firstOrNull()?.let { f ->
            player.upcomingFixtures.count { it.gameweek == f.gameweek }
        } ?: 1
    ): TripleCaptainAssessment {
        if (captain == null) {
            return TripleCaptainAssessment(
                isViable = false,
                projectedGain = 0.0,
                captaincyScore = 0.0,
                isDoubleGameweek = false,
                verdictAr = "غير متاح",
                reasonAr = "لا يوجد كابتن مؤهل لتقييم شريحة التريبل كابتن."
            )
        }

        val isDgw = fixturesCountInGw >= 2
        val xPts = captain.expectedPoints
        val score = captain.captaincyScore
        val risk = captain.riskLevel
        val startProb = captain.startProbability
        val expMins = captain.expectedMinutes

        // صافي النقاط الإضافية من التريبل كابتن = 1x من النقاط المتوقعة (دون مضاعفة مكررة)
        val projectedGain = round1(xPts)

        // الشروط الاستثنائية لتفعيل Triple Captain:
        // 1. جولة مزدوجة مؤكدة بكابتن نخبوي قليل المخاطر وبدقائق مضمونة
        val isPrimeDgw = isDgw && score >= 75.0 && risk != RiskLevel.HIGH && startProb >= 0.80 && expMins >= 110.0

        // 2. جولة فردية فائقة الاستثنائية (نقاط متوقعة >= 9.0 وتقييم >= 85 ومخاطرة منخفضة وجاهزية تامة)
        val isExceptionalSgw = !isDgw && xPts >= 9.0 && score >= 85.0 && risk == RiskLevel.LOW && startProb >= 0.90

        val isViable = isPrimeDgw || isExceptionalSgw

        val (verdictAr, reasonAr) = when {
            isPrimeDgw -> {
                "تفعيل موصى به (Activate Now)" to
                    "فرصة ذهبية: جولة مزدوجة (DGW) لنجم نخبوي (${captain.playerName}) بعائد صافي متوقع (+%.1f نقطة إضافية) واستقرار مشاركة موثوق (%d%%).".format(
                        java.util.Locale.US,
                        projectedGain,
                        (startProb * 100).toInt()
                    )
            }
            isExceptionalSgw -> {
                "تفعيل متاح بشروط استثنائية" to
                    "مواجهة استثنائية وسقف نقطي مرتفع جداً (+%.1f نقطة إضافية)، لكن يُفضل دائماً ادخارها لجولات المزدوج.".format(
                        java.util.Locale.US,
                        projectedGain
                    )
            }
            isDgw && risk == RiskLevel.HIGH -> {
                "احتفظ بالشريحة (Save For Later)" to
                    "توجد جولة مزدوجة ولكن مخاطر المداورة أو الإصابة مرتفعة للقائد (${captain.playerName})؛ لا يُنصح بحرق الشريحة في مواجهة غير مضمونة."
            }
            else -> {
                "احتفظ بالشريحة (Save For Later)" to
                    "اللاعب هو أفضل خيار للكابتن العادي، لكن الجولة عادية (+%.1f ن) ويُفضّل ادخار Triple Captain لجولات المزدوج (DGW) لتعظيم العائد.".format(
                        java.util.Locale.US,
                        projectedGain
                    )
            }
        }

        return TripleCaptainAssessment(
            isViable = isViable,
            projectedGain = projectedGain,
            captaincyScore = score,
            isDoubleGameweek = isDgw,
            verdictAr = verdictAr,
            reasonAr = reasonAr
        )
    }

    private fun generateReason(
        playerName: String,
        expectedPoints: Double,
        expectedMinutes: Double,
        startProbability: Double,
        captaincyScore: Double,
        riskLevel: RiskLevel,
        isUnavailable: Boolean,
        statusReason: String
    ): String = buildString {
        if (isUnavailable) {
            append("مستبعد من القيادة: $statusReason.")
            return@buildString
        }

        val startPercent = (startProbability * 100).roundToInt()
        when {
            captaincyScore >= 80.0 ->
                append("خيار كابتن مثالي: مردود متوقع مرتفع (%.1f ن) وجاهزية أساسية شبه مؤكدة (%d%%) مع مخاطرة منخفضة.".format(java.util.Locale.US, expectedPoints, startPercent))
            captaincyScore >= 65.0 ->
                append("مرشح قوي أو نائب كابتن ممتاز: نقاط متوقعة (%.1f ن) ومشاركة أساسية موثوقة (%d%%).".format(java.util.Locale.US, expectedPoints, startPercent))
            riskLevel == RiskLevel.HIGH ->
                append("خيار تفاضلي عالي المخاطر: نقاط متوقعة (%.1f ن) مع قلق من المداورة أو دقائق اللعب (%.0f دقيقة).".format(java.util.Locale.US, expectedPoints, expectedMinutes))
            startProbability < 0.60 ->
                append("مخاطرة مداورة: احتمال البدء (%d%%) ودقائق متوقعة (%.0f دقيقة) قد يحد من فاعلية الشارة.".format(java.util.Locale.US, startPercent, expectedMinutes))
            else ->
                append("خيار بديل: نقاط متوقعة (%.1f ن) ودقائق مشاركة متوقعة (%.0f دقيقة).".format(java.util.Locale.US, expectedPoints, expectedMinutes))
        }
    }

    private fun generateSummary(
        captain: CaptainCandidate,
        viceCaptain: CaptainCandidate?,
        alternatives: List<CaptainCandidate>,
        tcAssessment: TripleCaptainAssessment?
    ): String = buildString {
        append("الكابتن الموصى به: ${captain.playerName} (${captain.captainPoints} ن متوقعة مضاعفة، تقييم: ${captain.captaincyScore}/100). ")

        if (viceCaptain != null) {
            append("نائب الكابتن: ${viceCaptain.playerName} (${viceCaptain.expectedPoints} ن متوقعة، تقييم: ${viceCaptain.captaincyScore}/100) كصمام أمان في حال غياب القائد. ")
        } else {
            append("لا يتوفر نائب كابتن بديل مؤهل. ")
        }

        if (alternatives.isNotEmpty()) {
            val altDetails = alternatives.joinToString("، ") { "${it.playerName} (${it.expectedPoints}ن)" }
            append("أبرز البدائل التفاضلية: $altDetails. ")
        }

        if (tcAssessment != null) {
            append("تقييم Triple Captain: ${tcAssessment.verdictAr} - ${tcAssessment.reasonAr}")
        }
    }

    private fun createFallbackPrediction(player: PlayerModel): PlayerPrediction {
        val formPts = player.form.coerceAtLeast(0.0)
        val ppg = player.statistics.pointsPerGame.coerceAtLeast(0.0)
        val basePoints = when {
            formPts > 0.0 -> formPts
            ppg > 0.0 -> ppg
            else -> (player.totalPoints / 25.0).coerceAtLeast(2.0)
        }
        val isAvail = player.availability.isAvailable
        val status = player.availability.status.lowercase()
        val isCritical = status == "i" || status == "s" || status == "u" || player.availability.chanceOfPlayingNextRound == 0

        return PlayerPrediction(
            playerId = player.id,
            gameweek = 1,
            expectedPoints = basePoints,
            expectedMinutes = if (player.starts > 0) 80.0 else 35.0,
            startProbability = if (isAvail) 0.85 else 0.40,
            expectedAttackingPoints = basePoints * 0.5,
            expectedDefensivePoints = basePoints * 0.3,
            expectedCleanSheetPoints = 0.0,
            expectedBonusPoints = basePoints * 0.1,
            expectedAppearancePoints = if (isAvail) 2.0 else 1.0,
            predictedCaptainPoints = round1(basePoints * 2.0),
            confidenceScore = 0.50,
            recommendation = com.fplmanagerai.data.model.TransferRecommendation.HOLD,
            recommendationReason = "تقدير افتراضي لعدم وجود تحليل مسبق",
            analysisScore = 50.0,
            riskLevel = if (isCritical) RiskLevel.CRITICAL else if (isAvail) RiskLevel.LOW else RiskLevel.MEDIUM
        )
    }

    private fun round1(value: Double): Double =
        round(value * 10.0) / 10.0

    private fun round2(value: Double): Double =
        round(value * 100.0) / 100.0
}
