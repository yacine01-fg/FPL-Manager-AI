package com.fplmanagerai.data.model

data class PlayerStatistics(
    val goals: Int,
    val assists: Int,
    val cleanSheets: Int,
    val goalsConceded: Int,
    val saves: Int,
    val bonus: Int,
    val bps: Int,
    val expectedGoals: Double?,
    val expectedAssists: Double?,
    val expectedGoalInvolvements: Double?,
    val expectedGoalsConceded: Double?,
    val influence: Double?,
    val creativity: Double?,
    val threat: Double?,
    val ictIndex: Double?,
    val pointsPerGame: Double,
    val transfersInRound: Int,
    val transfersOutRound: Int,
    val yellowCards: Int,
    val redCards: Int
) {
    val hasAnyExpectedMetrics: Boolean
        get() = expectedGoals != null || expectedAssists != null

    val hasCompleteExpectedMetrics: Boolean
        get() = expectedGoals != null && expectedAssists != null

    @Deprecated(
        message = "Use hasCompleteExpectedMetrics or hasAnyExpectedMetrics instead for clarity",
        replaceWith = ReplaceWith("hasCompleteExpectedMetrics")
    )
    val hasExpectedMetrics: Boolean
        get() = hasCompleteExpectedMetrics
}
