package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.TransferRecommendation
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.OptimizedSquad
import com.fplmanagerai.domain.team.SquadOptimizer

enum class RankingCriteria(val titleAr: String) {
    EXPECTED_POINTS("النقاط المتوقعة (xPts)"),
    ANALYSIS_SCORE("تقييم المحرك الذكي"),
    FORM("الفورمة الحالية"),
    VALUE_FOR_MONEY("القيمة مقابل السعر"),
    TOTAL_POINTS("إجمالي النقاط")
}

data class CategorizedRankings(
    val topPicks: List<PlayerModel>,
    val differentials: List<PlayerModel>,
    val budgetGems: List<PlayerModel>,
    val sellWarnings: List<PlayerModel>
)

class PlayerRankingEngine(
    private val expectedPointsCalculator: ExpectedPointsCalculator = ExpectedPointsCalculator(),
    private val captaincyAnalyzer: CaptaincyAnalyzer = CaptaincyAnalyzer(),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer()
) {

    /**
     * ترشيح الكابتن ونائب الكابتن والبدائل اعتماداً على المحرك المركزي الموحد CaptaincyAnalyzer.
     */
    fun recommendCaptaincy(
        players: List<PlayerModel>,
        currentGameweek: Int
    ): CaptaincyRecommendation {
        val enriched = enrichAndRank(players, currentGameweek)
        return captaincyAnalyzer.recommend(enriched)
    }

    /**
     * بناء التشكيلة المثالية المكونة من 15 لاعباً وتحديد الأساسيين والبدلاء.
     */
    fun optimizeSquad(
        players: List<PlayerModel>,
        currentGameweek: Int,
        budget: Double = 100.0,
        strategy: OptimizationStrategy = OptimizationStrategy.BALANCED
    ): OptimizedSquad {
        val enriched = enrichAndRank(players, currentGameweek)
        return squadOptimizer.optimizeSquad(
            allPlayers = enriched,
            budget = budget,
            strategy = strategy
        )
    }

    fun enrichAndRank(
        players: List<PlayerModel>,
        currentGameweek: Int,
        criteria: RankingCriteria = RankingCriteria.EXPECTED_POINTS,
        positionFilter: PlayerPosition? = null
    ): List<PlayerModel> {
        val enriched = players.map { player ->
            if (player.prediction != null) player
            else {
                val pred = expectedPointsCalculator.calculateExpectedPoints(player, currentGameweek)
                player.copy(prediction = pred)
            }
        }

        val filtered = if (positionFilter != null) {
            enriched.filter { it.position == positionFilter }
        } else {
            enriched
        }

        return when (criteria) {
            RankingCriteria.EXPECTED_POINTS -> filtered.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
            RankingCriteria.ANALYSIS_SCORE -> filtered.sortedByDescending { it.prediction?.analysisScore ?: 0.0 }
            RankingCriteria.FORM -> filtered.sortedByDescending { it.form }
            RankingCriteria.VALUE_FOR_MONEY -> filtered.sortedByDescending {
                val xpts = it.prediction?.expectedPoints ?: 0.0
                xpts / it.price.coerceAtLeast(4.0)
            }
            RankingCriteria.TOTAL_POINTS -> filtered.sortedByDescending { it.totalPoints }
        }
    }

    fun getCategorizedInsights(players: List<PlayerModel>, currentGameweek: Int): CategorizedRankings {
        val ranked = enrichAndRank(players, currentGameweek, RankingCriteria.ANALYSIS_SCORE)

        val topPicks = ranked
            .filter { it.prediction?.recommendation == TransferRecommendation.BUY }
            .take(10)

        // Differentials: Selected by < 10%, high xPts (> 4.5), available
        val differentials = ranked
            .filter { it.selectedByPercent < 10.0 && (it.prediction?.expectedPoints ?: 0.0) >= 4.2 && it.availability.isAvailable }
            .sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
            .take(6)

        // Budget Gems: Price <= 6.0m, high value
        val budgetGems = ranked
            .filter { it.price <= 6.0 && (it.prediction?.expectedPoints ?: 0.0) >= 3.8 && it.availability.isAvailable }
            .sortedByDescending { (it.prediction?.expectedPoints ?: 0.0) / it.price }
            .take(6)

        // Sell warnings
        val sellWarnings = ranked
            .filter { it.prediction?.recommendation == TransferRecommendation.SELL || !it.availability.isAvailable }
            .take(6)

        return CategorizedRankings(
            topPicks = topPicks,
            differentials = differentials,
            budgetGems = budgetGems,
            sellWarnings = sellWarnings
        )
    }
}
