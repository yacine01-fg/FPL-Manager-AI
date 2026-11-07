package com.fplmanagerai.domain.analysis

import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.UpcomingFixture

data class FixtureImpact(
    val attackingMultiplier: Double,
    val defensiveMultiplier: Double,
    val cleanSheetOdds: Double,
    val averageFdr: Double,
    val summaryAr: String
)

class FixtureDifficultyAnalyzer {

    fun analyzeUpcomingFixtures(
        fixtures: List<UpcomingFixture>,
        position: PlayerPosition,
        horizon: Int = 3
    ): FixtureImpact {
        if (fixtures.isEmpty()) {
            return FixtureImpact(
                attackingMultiplier = 1.0,
                defensiveMultiplier = 1.0,
                cleanSheetOdds = 0.25,
                averageFdr = 3.0,
                summaryAr = "لا توجد مباريات معلنة قادمة"
            )
        }

        val relevant = fixtures.take(horizon)
        val fdrSum = relevant.sumOf { it.difficulty }
        val avgFdr = fdrSum.toDouble() / relevant.size

        // Calculate weighted multipliers per fixture (earlier fixtures have higher weight)
        var totalWeight = 0.0
        var weightedAttMultiplier = 0.0
        var weightedDefMultiplier = 0.0
        var weightedCsOdds = 0.0

        for (i in relevant.indices) {
            val f = relevant[i]
            val weight = 1.0 / (i + 1)
            totalWeight += weight

            // Base Fdr factor: FDR 2 -> 1.25, FDR 3 -> 1.00, FDR 4 -> 0.82, FDR 5 -> 0.65
            val fdrAttBase = when (f.difficulty) {
                1 -> 1.35
                2 -> 1.22
                3 -> 1.00
                4 -> 0.82
                5 -> 0.65
                else -> 1.00
            }

            // Clean sheet base probability by FDR:
            val csBase = when (f.difficulty) {
                1 -> 0.50
                2 -> 0.42
                3 -> 0.28
                4 -> 0.16
                5 -> 0.08
                else -> 0.28
            }

            // Home vs Away adjustment:
            // Home matches increase goal output by ~15-20% and CS chances by ~15%
            val homeAttFactor = if (f.isHome) 1.12 else 0.90
            val homeDefFactor = if (f.isHome) 1.15 else 0.85

            val attMultiplier = fdrAttBase * homeAttFactor
            val defMultiplier = (1.0 / fdrAttBase) * homeDefFactor
            val csOdds = (csBase * (if (f.isHome) 1.18 else 0.84)).coerceIn(0.02, 0.70)

            weightedAttMultiplier += attMultiplier * weight
            weightedDefMultiplier += defMultiplier * weight
            weightedCsOdds += csOdds * weight
        }

        val finalAtt = (weightedAttMultiplier / totalWeight).coerceIn(0.5, 1.6)
        val finalDef = (weightedDefMultiplier / totalWeight).coerceIn(0.5, 1.6)
        val finalCs = (weightedCsOdds / totalWeight).coerceIn(0.05, 0.65)

        val summary = when {
            avgFdr <= 2.3 -> "جدول مباريات ممتاز وسهل جداً يعزز التوقعات"
            avgFdr <= 2.8 -> "جدول مباريات إيجابي ومناسب لتحقيق نقاط"
            avgFdr <= 3.3 -> "جدول مباريات متوسط ومتوازن الصعوبة"
            avgFdr <= 4.0 -> "جدول مباريات معقد يتطلب الحذر"
            else -> "جدول مباريات غاية في الصعوبة ضد كبار الدوري"
        }

        return FixtureImpact(
            attackingMultiplier = finalAtt,
            defensiveMultiplier = finalDef,
            cleanSheetOdds = finalCs,
            averageFdr = Math.round(avgFdr * 10.0) / 10.0,
            summaryAr = summary
        )
    }
}
