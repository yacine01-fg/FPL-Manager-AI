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
        completedGameweeks: Int = 1,
        totalGameweeks: Int = completedGameweeks
    ): AvailabilityAssessment {
        val safeGameweeks = if (totalGameweeks != 1) totalGameweeks else completedGameweeks
        val status = availability.status
        val chance = availability.chanceOfPlayingNextRound

        // 1. Injured, suspended or 0% chance
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

        // 2. Conditional chance (25%, 50%, 75%)
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

        // 3. Available (100% or null) - assess starter role vs bench sub using completed gameweeks
        val safeCompleted = safeGameweeks.coerceAtLeast(1)
        val startRatio = starts.toDouble() / safeCompleted
        val avgMinutes = minutesPlayed.toDouble() / safeCompleted

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

    /**
     * Assesses availability for the current immediate gameweek.
     * Uses (currentGameweek - 1) as completed gameweeks count.
     */
    fun assess(player: PlayerModel, currentGameweek: Int): AvailabilityAssessment {
        val completedGws = (currentGameweek - 1).coerceAtLeast(1)
        return assessAvailability(
            availability = player.availability,
            minutesPlayed = player.minutesPlayed,
            starts = player.starts,
            completedGameweeks = completedGws
        )
    }

    /**
     * Evaluates availability dynamically across future gameweeks.
     * Models recovery progression over time rather than holding near-term doubts fixed indefinitely.
     */
    fun assessForGameweek(
        player: PlayerModel,
        targetGameweek: Int,
        currentGameweek: Int
    ): AvailabilityAssessment {
        val completedGws = (currentGameweek - 1).coerceAtLeast(1)
        val gwDelta = (targetGameweek - currentGameweek).coerceAtLeast(0)

        // For current gameweek, use direct medical status
        if (gwDelta == 0) {
            return assessAvailability(player.availability, player.minutesPlayed, player.starts, completedGws)
        }

        val baseAssessment = assessAvailability(
            availability = PlayerAvailability.parse(status = "a", chance = 100, news = ""),
            minutesPlayed = player.minutesPlayed,
            starts = player.starts,
            completedGameweeks = completedGws
        )

        val status = player.availability.status
        val chance = player.availability.chanceOfPlayingNextRound

        // Severe long-term injury: status 'i' with 0% chance or long return horizon
        if ((status == "i" || status == "u") && chance == 0) {
            val newsLower = player.availability.news.lowercase()
            val isKnownLongTerm = newsLower.contains("months") || newsLower.contains("season") ||
                    newsLower.contains("acl") || newsLower.contains("surgery")

            return if (isKnownLongTerm || gwDelta <= 2) {
                AvailabilityAssessment(
                    startProbability = 0.0,
                    expectedMinutes = 0.0,
                    riskFactor = 1.0,
                    summaryAr = "إصابة طويلة الأمد - مستبعد للجولة $targetGameweek"
                )
            } else {
                // Potential gradual return in later weeks
                AvailabilityAssessment(
                    startProbability = (baseAssessment.startProbability * 0.40),
                    expectedMinutes = (baseAssessment.expectedMinutes * 0.40),
                    riskFactor = 0.70,
                    summaryAr = "مرحلة تأهيل تدريجي للجولة $targetGameweek"
                )
            }
        }

        // Suspensions (typically 1-3 matches)
        if (status == "s") {
            return if (gwDelta <= 1) {
                AvailabilityAssessment(
                    startProbability = 0.0,
                    expectedMinutes = 0.0,
                    riskFactor = 1.0,
                    summaryAr = "موقوف للجولة $targetGameweek"
                )
            } else {
                baseAssessment
            }
        }

        // Minor doubts (25%, 50%, 75%): progressive recovery over time
        if (chance != null && chance < 100) {
            val recoveryFactor = when {
                chance >= 75 -> 1.0 // Fully recovered by next week
                chance >= 50 -> if (gwDelta >= 2) 1.0 else 0.85
                chance >= 25 -> if (gwDelta >= 3) 1.0 else (0.50 + 0.25 * gwDelta)
                else -> (gwDelta * 0.30).coerceIn(0.0, 1.0)
            }

            return AvailabilityAssessment(
                startProbability = Math.round(baseAssessment.startProbability * recoveryFactor * 100.0) / 100.0,
                expectedMinutes = Math.round(baseAssessment.expectedMinutes * recoveryFactor * 10.0) / 10.0,
                riskFactor = Math.round((1.0 - recoveryFactor * (1.0 - baseAssessment.riskFactor)) * 100.0) / 100.0,
                summaryAr = if (recoveryFactor >= 0.95) "متوقع تعافيه تماماً بحلول الجولة $targetGameweek"
                else "احتمالية مشاركة بنسبة ${(recoveryFactor * 100).toInt()}% بعد التعافي للجولة $targetGameweek"
            )
        }

        // Healthy player: standard baseline assessment
        return baseAssessment
    }
}
