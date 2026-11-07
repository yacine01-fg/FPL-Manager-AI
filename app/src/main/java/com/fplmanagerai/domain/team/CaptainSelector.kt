package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel

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
    val topCandidates: List<CaptainCandidate>
)

class CaptainSelector {

    fun selectCaptain(starting11: List<PlayerModel>): CaptainSelectionResult {
        require(starting11.size >= 2) { "Starting 11 must contain at least 2 players to choose Captain and Vice-Captain" }

        val candidates = starting11.map { player ->
            val xpts = player.prediction?.expectedPoints ?: (player.totalPoints / 10.0)
            val form = player.form
            val isHome = player.upcomingFixtures.firstOrNull()?.isHome ?: true
            val fixtureDifficulty = player.upcomingFixtures.firstOrNull()?.difficulty ?: 3

            // Ceiling score based on attacking xG/xA and explosive potential
            val stats = player.statistics
            val explosiveFactor = (stats.goals * 1.2 + stats.assists * 0.8) / (player.minutesPlayed.coerceAtLeast(90) / 90.0)
            val ceiling = xpts * 1.3 + explosiveFactor * 0.5 + (if (isHome) 0.5 else -0.3)

            val reason = buildString {
                append("نقاط متوقعة: %.1f".format(java.util.Locale.US, xpts))
                if (isHome) append(" • مباراة على أرضه") else append(" • مباراة خارج الأرض")
                append(" (صعوبة FDR: $fixtureDifficulty)")
            }

            CaptainCandidate(
                player = player,
                expectedPoints = Math.round(xpts * 10.0) / 10.0,
                predictedCaptainPoints = Math.round(xpts * 2.0 * 10.0) / 10.0,
                actualPoints = player.totalPoints,
                actualCaptainPoints = player.totalPoints * 2,
                ceilingScore = Math.round(ceiling * 10.0) / 10.0,
                recommendationReasonAr = reason
            )
        }.sortedWith(
            compareByDescending<CaptainCandidate> { it.expectedPoints }
                .thenByDescending { it.ceilingScore }
        )

        val captain = candidates[0].player
        val viceCaptain = candidates.getOrNull(1)?.player ?: candidates[0].player

        return CaptainSelectionResult(
            captain = captain,
            viceCaptain = viceCaptain,
            projectedCaptainPoints = candidates[0].predictedCaptainPoints,
            topCandidates = candidates.take(5)
        )
    }
}
