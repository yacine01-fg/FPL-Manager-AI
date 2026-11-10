package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.domain.analysis.CaptaincyAnalyzer
import com.fplmanagerai.domain.analysis.CaptaincyRecommendation

data class CaptainCandidate(
    val player: PlayerModel,
    val expectedPoints: Double,
    val predictedCaptainPoints: Double,
    val actualPoints: Int,
    val actualCaptainPoints: Int,
    val ceilingScore: Double,
    val recommendationReasonAr: String
)

data class CaptainSelectionResult(
    val captain: PlayerModel,
    val viceCaptain: PlayerModel,
    val projectedCaptainPoints: Double,
    val topCandidates: List<CaptainCandidate>,
    val recommendation: CaptaincyRecommendation? = null,
    val summaryAr: String = "",
    val isTripleCaptainViable: Boolean = false,
    val tripleCaptainReasonAr: String = ""
)

/**
 * واجهة موحدة لاختيار الكابتن ونائب الكابتن.
 * تفوض الحساب والتقييم كلياً إلى CaptaincyAnalyzer كمصدر موحد للحقيقة (Single Source of Truth)
 * مع الحفاظ على التوافق التام مع SquadOptimizer و ViewModels وباقي شاشات التطبيق.
 */
class CaptainSelector(
    private val captaincyAnalyzer: CaptaincyAnalyzer = CaptaincyAnalyzer()
) {

    fun selectCaptain(starting11: List<PlayerModel>): CaptainSelectionResult {
        require(starting11.isNotEmpty()) { "Starting 11 must contain at least 1 player to choose Captain and Vice-Captain" }

        if (starting11.size == 1) {
            val p = starting11[0]
            val xpts = p.prediction?.expectedPoints ?: (p.totalPoints / 10.0)
            return CaptainSelectionResult(
                captain = p,
                viceCaptain = p,
                projectedCaptainPoints = Math.round(xpts * 2.0 * 10.0) / 10.0,
                topCandidates = emptyList()
            )
        }

        // التفويض للمحرك المركزي CaptaincyAnalyzer
        val recommendation = captaincyAnalyzer.recommend(starting11)

        val captain = recommendation.captain?.let { cap ->
            starting11.firstOrNull { it.id == cap.playerId }
        } ?: starting11[0]

        // التأكد التام من أن نائب الكابتن مختلف عن الكابتن وضمن الأساسيين
        val viceCaptain = recommendation.viceCaptain?.let { vc ->
            starting11.firstOrNull { it.id == vc.playerId && it.id != captain.id }
        } ?: starting11.firstOrNull { it.id != captain.id } ?: starting11.getOrElse(1) { captain }

        val projectedCapPoints = recommendation.captain?.captainPoints
            ?: Math.round((captain.prediction?.expectedPoints ?: (captain.totalPoints / 10.0)) * 2.0 * 10.0) / 10.0

        val mappedCandidates = (listOfNotNull(recommendation.captain, recommendation.viceCaptain) + recommendation.alternatives)
            .distinctBy { it.playerId }
            .mapNotNull { cand ->
                val player = starting11.firstOrNull { it.id == cand.playerId } ?: return@mapNotNull null
                CaptainCandidate(
                    player = player,
                    expectedPoints = cand.expectedPoints,
                    predictedCaptainPoints = cand.captainPoints,
                    actualPoints = player.totalPoints,
                    actualCaptainPoints = player.totalPoints * 2,
                    ceilingScore = cand.captaincyScore,
                    recommendationReasonAr = cand.reasonAr
                )
            }

        return CaptainSelectionResult(
            captain = captain,
            viceCaptain = viceCaptain,
            projectedCaptainPoints = projectedCapPoints,
            topCandidates = mappedCandidates,
            recommendation = recommendation,
            summaryAr = recommendation.summaryAr,
            isTripleCaptainViable = recommendation.tripleCaptainAssessment?.isViable ?: false,
            tripleCaptainReasonAr = recommendation.tripleCaptainAssessment?.reasonAr ?: ""
        )
    }
}
