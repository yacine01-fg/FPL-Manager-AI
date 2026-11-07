package com.fplmanagerai.data.model

data class TeamModel(
    val id: Int,
    val name: String,
    val shortName: String,
    val strength: Int,
    val strengthOverallHome: Int,
    val strengthOverallAway: Int,
    val strengthAttackHome: Int,
    val strengthAttackAway: Int,
    val strengthDefenceHome: Int,
    val strengthDefenceAway: Int
)
