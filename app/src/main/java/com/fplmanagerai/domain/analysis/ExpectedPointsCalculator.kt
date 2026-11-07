package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPrediction

class ExpectedPointsCalculator(
    private val engine: PredictionEngine = PredictionEngine()
) {
    fun calculateExpectedPoints(
        player: PlayerModel,
        currentGameweek: Int,
        horizon: Int = 1
    ): PlayerPrediction {
        return engine.generatePrediction(player, currentGameweek, horizon)
    }
}
