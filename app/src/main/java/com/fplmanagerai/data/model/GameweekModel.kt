package com.fplmanagerai.data.model

data class GameweekModel(
    val id: Int,
    val name: String,
    val deadlineTime: String,
    val isPrevious: Boolean,
    val isCurrent: Boolean,
    val isNext: Boolean,
    val isFinished: Boolean,
    val averageScore: Int? = null,
    val highestScore: Int? = null
)
