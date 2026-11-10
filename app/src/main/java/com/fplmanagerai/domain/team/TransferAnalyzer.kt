package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.data.model.TransferRecommendation

enum class FplChip(
    val code: String,
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String
) {
    NONE("none", "بدون رقاقة", "None", "تنفيذ التبديلات العادية المتاحة"),
    WILDCARD("wildcard", "وايلد كارد (Wildcard)", "Wildcard", "إعادة بناء التشكيلة بالكامل مجاناً وبلا أي خصم نقاط"),
    FREE_HIT("freehit", "فري هيت (Free Hit)", "Free Hit", "تبديلات غير محدودة لجولة واحدة فقط وتعود التشكيلة بعدها كما كانت"),
    BENCH_BOOST("bboost", "بينش بوست (Bench Boost)", "Bench Boost", "احتساب نقاط دكة البدلاء الأربعة كاملة وإضافتها لنقاط الجولة"),
    TRIPLE_CAPTAIN("3xc", "تريبل كابتن (Triple Captain)", "Triple Captain", "مضاعفة نقاط الكابتن لـ 3 أضعاف (3x) بدلاً من الضعف")
}

data class ChipRecommendation(
    val chip: FplChip,
    val isRecommended: Boolean,
    val projectedGain: Double,
    val justificationAr: String
)

data class KeepOrSellAnalysis(
    val playerToSell: PlayerModel,
    val recommendedReplacement: PlayerModel,
    val keepProjectedPoints: Double,
    val buyProjectedPoints: Double,
    val hitDeduction: Int,
    val netAdvantage: Double,
    val shouldTransfer: Boolean,
    val reasoningAr: String
)

data class TransferSuggestion(
    val playerOut: PlayerModel,
    val playerIn: PlayerModel,
    val netCost: Double,
    val pointGainPerGw: Double,
    val totalProjectedGain: Double,
    val hitPenalty: Int,
    val netExpectedGain: Double,
    val explanationAr: String
)

data class TransferAnalysisResult(
    val suggestions: List<TransferSuggestion>,
    val prioritySellCandidates: List<PlayerModel>,
    val priorityBuyCandidates: List<PlayerModel>,
    val keepOrSellEvaluations: List<KeepOrSellAnalysis> = emptyList(),
    val chipRecommendations: List<ChipRecommendation> = emptyList(),
    val activeChipRecommendation: ChipRecommendation? = null,
    val freeTransfersTracked: Int = 1,
    val projectedHitPenalty: Int = 0
)

class TransferAnalyzer {

    fun analyzeTransfers(
        currentSquad: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        freeTransfers: Int = 1,
        bankBudget: Double = 0.0,
        horizon: Int = 3
    ): TransferAnalysisResult {
        val safeFreeTransfers = freeTransfers.coerceIn(1, 5)

        // 1. Identify sell candidates in current squad
        val sellCandidates = currentSquad
            .filter { player ->
                !player.availability.isAvailable ||
                        player.prediction?.riskLevel == RiskLevel.CRITICAL ||
                        player.prediction?.recommendation == TransferRecommendation.SELL ||
                        (player.prediction?.expectedPoints ?: 0.0) < 3.2
            }
            .sortedBy { it.prediction?.expectedPoints ?: 0.0 }

        // 2. Identify priority buy candidates not already in squad
        val squadIds = currentSquad.map { it.id }.toSet()
        val buyCandidates = allPlayers
            .filter { player ->
                player.id !in squadIds &&
                        player.availability.isAvailable &&
                        player.prediction?.riskLevel != RiskLevel.CRITICAL &&
                        (player.prediction?.expectedPoints ?: 0.0) >= 4.0
            }
            .sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }

        val suggestions = mutableListOf<TransferSuggestion>()
        val keepOrSellList = mutableListOf<KeepOrSellAnalysis>()

        // 3. Evaluate Combinations & Keep vs Sell Analysis
        var transferIndex = 0
        for (outPlayer in sellCandidates.take(5)) {
            // استخدام سعر البيع الفعلي (effectiveSellingPrice) وليس سعر السوق لضمان دقة الميزانية
            val maxSpend = outPlayer.effectiveSellingPrice + bankBudget
            val eligibleReplacements = buyCandidates
                .filter { candidate ->
                    // 1. توافق المركز
                    candidate.position == outPlayer.position &&
                    // 2. الميزانية الفعلية المتاحة
                    candidate.price <= maxSpend + 0.001 &&
                    // 3. قاعدة الحد الأقصى: لا يتجاوز 3 لاعبين من نفس النادي
                    (if (outPlayer.teamId == candidate.teamId) {
                        currentSquad.count { it.teamId == candidate.teamId } <= 3
                    } else {
                        currentSquad.count { it.teamId == candidate.teamId } < 3
                    })
                }
                .take(3)

            val bestReplacement = eligibleReplacements.firstOrNull()
            if (bestReplacement != null) {
                val outXptsSingle = outPlayer.prediction?.expectedPoints ?: 0.0
                val inXptsSingle = bestReplacement.prediction?.expectedPoints ?: 0.0

                // حساب النقاط على أفق 5 جولات الفعلي بالاعتماد على توقعات كل جولة (DGW, BGW, FDR)
                val outHorizon = outPlayer.prediction?.horizonPredictions
                val inHorizon = bestReplacement.prediction?.horizonPredictions
                val outXptsTotal = if (!outHorizon.isNullOrEmpty()) {
                    outHorizon.take(horizon).sumOf { it.expectedPoints }
                } else {
                    val base = if (!outPlayer.availability.isAvailable) 0.0 else outXptsSingle
                    base * horizon
                }
                val inXptsTotal = if (!inHorizon.isNullOrEmpty()) {
                    inHorizon.take(horizon).sumOf { it.expectedPoints }
                } else {
                    inXptsSingle * horizon
                }

                val hitCost = if (transferIndex >= safeFreeTransfers) 4 else 0
                val totalHorizonDelta = inXptsTotal - outXptsTotal
                val netAdvantage = totalHorizonDelta - hitCost

                val shouldMove = netAdvantage > 1.2 || !outPlayer.availability.isAvailable

                val reasoning = if (!outPlayer.availability.isAvailable) {
                    "اللاعب خارج الخدمة طبياً (${outPlayer.availability.labelAr})؛ استبداله بالبديل ${bestReplacement.webName} ضروري وحتمي."
                } else if (shouldMove) {
                    "تفوق واضح للمستهدف ${bestReplacement.webName} بفارق صافي +%.1f نقطة خلال $horizon جولات حتى بعد خصم تكلفة النقل (-$hitCost).".format(java.util.Locale.US, netAdvantage)
                } else {
                    "عائد النقل ضعيف ولا يبرر تكلفة الخصم (-$hitCost)؛ يُفضل الاحتفاظ باللاعب ${outPlayer.webName} للجولة القادمة."
                }

                keepOrSellList.add(
                    KeepOrSellAnalysis(
                        playerToSell = outPlayer,
                        recommendedReplacement = bestReplacement,
                        keepProjectedPoints = Math.round(outXptsTotal * 10.0) / 10.0,
                        buyProjectedPoints = Math.round(inXptsTotal * 10.0) / 10.0,
                        hitDeduction = hitCost,
                        netAdvantage = Math.round(netAdvantage * 10.0) / 10.0,
                        shouldTransfer = shouldMove,
                        reasoningAr = reasoning
                    )
                )

                // Add to suggestions if positive
                val deltaXpts = inXptsSingle - outXptsSingle
                if (deltaXpts > 0.6 || totalHorizonDelta > 2.0) {
                    val explanation = buildString {
                        append("استبدال ${outPlayer.webName} (${outPlayer.availability.labelAr}) ")
                        append("بالمتألق ${bestReplacement.webName} ")
                        append("(فارق نقاط: +%.1f في الجولة، +%.1f على أفق $horizon جولات)".format(java.util.Locale.US, deltaXpts, totalHorizonDelta))
                        if (hitCost > 0) append(" [خصم نقاط -$hitCost]")
                    }

                    suggestions.add(
                        TransferSuggestion(
                            playerOut = outPlayer,
                            playerIn = bestReplacement,
                            netCost = Math.round((bestReplacement.price - outPlayer.effectiveSellingPrice) * 10.0) / 10.0,
                            pointGainPerGw = Math.round(deltaXpts * 10.0) / 10.0,
                            totalProjectedGain = Math.round(totalHorizonDelta * 10.0) / 10.0,
                            hitPenalty = hitCost,
                            netExpectedGain = Math.round(netAdvantage * 10.0) / 10.0,
                            explanationAr = explanation
                        )
                    )
                    transferIndex++
                }
            }
        }

        // 4. Chip Planning Evaluation
        val chipRecommendations = evaluateChips(currentSquad, sellCandidates, horizon)
        val activeChip = chipRecommendations.firstOrNull { it.isRecommended }

        val totalHits = suggestions.sumOf { it.hitPenalty }

        return TransferAnalysisResult(
            suggestions = suggestions.sortedByDescending { it.netExpectedGain },
            prioritySellCandidates = sellCandidates,
            priorityBuyCandidates = buyCandidates.take(8),
            keepOrSellEvaluations = keepOrSellList,
            chipRecommendations = chipRecommendations,
            activeChipRecommendation = activeChip,
            freeTransfersTracked = safeFreeTransfers,
            projectedHitPenalty = totalHits
        )
    }

    private fun evaluateChips(
        currentSquad: List<PlayerModel>,
        sellCandidates: List<PlayerModel>,
        horizon: Int
    ): List<ChipRecommendation> {
        val list = mutableListOf<ChipRecommendation>()

        // 1. Wildcard Analysis: Recommend if 3+ critical injuries or 4+ urgent sell candidates
        val criticalCount = currentSquad.count { it.prediction?.riskLevel == RiskLevel.CRITICAL || !it.availability.isAvailable }
        val urgentSellCount = sellCandidates.size
        val wildcardRecommended = criticalCount >= 3 || urgentSellCount >= 4
        val wildcardGain = if (wildcardRecommended) (criticalCount * 4.0 * horizon).coerceAtLeast(16.0) else 0.0

        list.add(
            ChipRecommendation(
                chip = FplChip.WILDCARD,
                isRecommended = wildcardRecommended,
                projectedGain = Math.round(wildcardGain * 10.0) / 10.0,
                justificationAr = if (wildcardRecommended) {
                    "التشكيلة تعاني من $criticalCount إصابات مؤكدة و $urgentSellCount خيارات بيع عاجلة؛ يُنصح بتفعيل الوايلد كارد فوراً لتفادي خصم نقاط هائل."
                } else {
                    "التشكيلة مستقرة ولا تستدعي الوايلد كارد حالياً؛ احتفظ بالرقاقة لجولات ضغط المباريات القادمة."
                }
            )
        )

        // 2. Triple Captain Analysis: Recommend if top player has xPts >= 8.5
        val topCaptain = currentSquad.maxByOrNull { it.prediction?.expectedPoints ?: 0.0 }
        val topXpts = topCaptain?.prediction?.expectedPoints ?: 0.0
        val tcRecommended = topXpts >= 8.5
        val tcGain = if (tcRecommended) topXpts else 0.0 // Extra 1x points from normal captaincy

        list.add(
            ChipRecommendation(
                chip = FplChip.TRIPLE_CAPTAIN,
                isRecommended = tcRecommended,
                projectedGain = Math.round(tcGain * 10.0) / 10.0,
                justificationAr = if (tcRecommended) {
                    "فرصة ذهبية لتفعيل التريبل كابتن على النجم ${topCaptain?.webName} بمعدل متوقع (%.1f ن) ومواجهة مثالية.".format(java.util.Locale.US, topXpts)
                } else {
                    "أعلى سقف كابتن حالي هو ${topCaptain?.webName} (%.1f ن)؛ يُفضل توفير التريبل كابتن لجولات الدبل (Double Gameweek).".format(java.util.Locale.US, topXpts)
                }
            )
        )

        // 3. Bench Boost Analysis: Recommend if bench has high expected points
        val benchCandidates = currentSquad.sortedBy { it.prediction?.expectedPoints ?: 0.0 }.take(4)
        val benchXpts = benchCandidates.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        val allBenchFit = benchCandidates.all { it.availability.isAvailable }
        val bbRecommended = benchXpts >= 15.0 && allBenchFit

        list.add(
            ChipRecommendation(
                chip = FplChip.BENCH_BOOST,
                isRecommended = bbRecommended,
                projectedGain = Math.round(benchXpts * 10.0) / 10.0,
                justificationAr = if (bbRecommended) {
                    "رباعي الدكة في قمة الجاهزية بمجموع متوقع (%.1f ن)؛ تفعيل البينش بوست سيعطي قفزة نوعية في النقاط.".format(java.util.Locale.US, benchXpts)
                } else {
                    "نقاط الدكة الحالية متواضعة (%.1f ن)؛ لا يُنصح بحرق البينش بوست إلا عند اكتمال جاهزية بدلاء أساسيين في جولة مزدوجة.".format(java.util.Locale.US, benchXpts)
                }
            )
        )

        // 4. Free Hit Analysis
        val blankOrToughCount = currentSquad.count { (it.upcomingFixtures.firstOrNull()?.difficulty ?: 3) >= 4 }
        val fhRecommended = blankOrToughCount >= 7
        val fhGain = if (fhRecommended) 18.0 else 0.0

        list.add(
            ChipRecommendation(
                chip = FplChip.FREE_HIT,
                isRecommended = fhRecommended,
                projectedGain = fhGain,
                justificationAr = if (fhRecommended) {
                    "معظم لاعبي فريقك ($blankOrToughCount لاعبين) يواجهون مباريات معقدة جداً؛ الفري هيت يحميك بجولة هجومية طارئة."
                } else {
                    "مواجهات الجولة الحالية متوازنة؛ احتفظ بالفري هيت للجولات الفارغة (Blank Gameweek) أو المزدوجة."
                }
            )
        )

        return list
    }
}
