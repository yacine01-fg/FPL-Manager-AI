package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerAvailability
import com.fplmanagerai.data.model.PlayerModel

data class AvailabilityAssessment(
    val startProbability: Double,
    val expectedMinutes: Double,
    val riskFactor: Double,
    val summaryAr: String
)

class PlayerAvailabilityAnalyzer {

    fun assessAvailability(
        availability: PlayerAvailability,
        minutesPlayed: Int,
        starts: Int,
        totalGameweeks: Int
    ): AvailabilityAssessment {
        val status = availability.status
        val chance = availability.chanceOfPlayingNextRound

        // Injured, suspended or 0% chance
        if (status == "i" || status == "s" || status == "u" || chance == 0) {
            val reason = when (status) {
                "i" -> "مصاب (${availability.news.ifEmpty { "غير متاح" }})"
                "s" -> "موقوف رسمياً"
                "u" -> "غير متاح للمشاركة"
                else -> "احتمال المشاركة 0%"
            }
            return AvailabilityAssessment(
                startProbability = 0.0,
                expectedMinutes = 0.0,
                riskFactor = 1.0,
                summaryAr = reason
            )
        }

        // Conditional chance (25%, 50%, 75%)
        if (chance != null && chance < 100) {
            val (prob, expMins, risk) = when (chance) {
                25 -> Triple(0.20, 18.0, 0.80)
                50 -> Triple(0.48, 42.0, 0.52)
                75 -> Triple(0.75, 68.0, 0.25)
                else -> Triple(chance / 100.0 * 0.85, (chance / 100.0) * 75.0, 1.0 - (chance / 100.0))
            }
            return AvailabilityAssessment(
                startProbability = prob,
                expectedMinutes = expMins,
                riskFactor = risk,
                summaryAr = "شكوك حول المشاركة بنسبة $chance% - ${availability.news.ifEmpty { "متابعة التقارير الطبية" }}"
            )
        }

        // Available (100% or null) - assess starter role vs bench sub
        val completedGws = if (totalGameweeks > 0) totalGameweeks else 1
        val startRatio = starts.toDouble() / completedGws.coerceAtLeast(1)
        val avgMinutes = minutesPlayed.toDouble() / completedGws.coerceAtLeast(1)

        val (metrics, summary) = when {
            startRatio >= 0.80 || avgMinutes >= 75.0 -> {
                Triple(0.95, 84.0, 0.05) to "لاعب أساسي لا غنى عنه في تشكيلة الفريق"
            }
            startRatio >= 0.50 || avgMinutes >= 55.0 -> {
                Triple(0.75, 65.0, 0.20) to "لاعب أساسي مع احتمال التبديل المبكر أو المداورة"
            }
            startRatio >= 0.25 || avgMinutes >= 30.0 -> {
                Triple(0.40, 38.0, 0.50) to "خيار مداورة، يشارك أحياناً كبديل مؤثر"
            }
            avgMinutes >= 10.0 -> {
                Triple(0.15, 18.0, 0.75) to "بديل دقائق أخيرة، فرصة البدء ضعيفة"
            }
            else -> {
                Triple(0.05, 5.0, 0.90) to "خارج الحسابات الأساسية حالياً"
            }
        }

        return AvailabilityAssessment(
            startProbability = metrics.first,
            expectedMinutes = metrics.second,
            riskFactor = metrics.third,
            summaryAr = summary
        )
    }

    fun assess(player: PlayerModel, currentGameweek: Int): AvailabilityAssessment {
        return assessAvailability(
            availability = player.availability,
            minutesPlayed = player.minutesPlayed,
            starts = player.starts,
            totalGameweeks = currentGameweek.coerceAtLeast(1)
        )
    }
}
