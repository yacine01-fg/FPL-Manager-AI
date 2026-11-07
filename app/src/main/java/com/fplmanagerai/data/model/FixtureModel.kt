package com.fplmanagerai.data.model

data class FixtureModel(
    val id: Int,
    val event: Int?,
    val kickoffTime: String?,
    val homeTeamId: Int,
    val awayTeamId: Int,
    val homeDifficulty: Int,
    val awayDifficulty: Int,
    val isFinished: Boolean,
    val isStarted: Boolean,
    val homeScore: Int? = null,
    val awayScore: Int? = null
) {
    fun isTeamInvolved(teamId: Int): Boolean = homeTeamId == teamId || awayTeamId == teamId

    fun isHome(teamId: Int): Boolean = homeTeamId == teamId

    fun getOpponentId(teamId: Int): Int = if (homeTeamId == teamId) awayTeamId else homeTeamId

    fun getDifficulty(teamId: Int): Int = if (homeTeamId == teamId) homeDifficulty else awayDifficulty
}
