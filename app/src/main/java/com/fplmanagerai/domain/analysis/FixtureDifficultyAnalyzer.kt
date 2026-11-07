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

    private data class SingleFixtureImpact(
        val attackingMultiplier: Double,
        val defensiveMultiplier: Double,
        val cleanSheetOdds: Double,
        val difficulty: Double
    )

    /**
     * تحليل مباراة واحدة فقط.
     * لا يتأثر بنتائج المباريات في الجولات الأخرى.
     */
    fun analyzeSingleFixture(
        fixture: UpcomingFixture
    ): FixtureImpact {

        val result = calculateFixtureImpact(fixture)

        return FixtureImpact(
            attackingMultiplier = result.attackingMultiplier,
            defensiveMultiplier = result.defensiveMultiplier,
            cleanSheetOdds = result.cleanSheetOdds,
            averageFdr = result.difficulty,
            summaryAr = buildSummary(result.difficulty)
        )
    }

    /**
     * تحليل مجموعة مباريات مستقبلية.
     *
     * مباريات الجولة نفسها تحصل على الوزن الزمني نفسه.
     * يتم تطبيق الأوزان التنازلية على مستوى Gameweek وليس المباراة.
     *
     * ملاحظة: هذه الدالة تقيس صعوبة الجدول،
     * وليست بديلاً عن جمع نقاط مباريات DGW داخل PredictionEngine.
     */
    fun analyzeFixtureRun(
        fixtures: List<UpcomingFixture>,
        horizon: Int = 3
    ): FixtureImpact {

        if (fixtures.isEmpty() || horizon <= 0) {
            return emptyImpact()
        }

        val groupedFixtures = fixtures
            .groupBy { it.gameweek }
            .toSortedMap()
            .entries
            .take(horizon)

        if (groupedFixtures.isEmpty()) {
            return emptyImpact()
        }

        var totalWeight = 0.0
        var weightedAttack = 0.0
        var weightedDefense = 0.0
        var weightedCleanSheet = 0.0
        var weightedFdr = 0.0

        groupedFixtures.forEachIndexed { index, entry ->

            val weight = 1.0 / (index + 1.0)

            // كل مباريات الجولة المزدوجة لها الوزن نفسه.
            val matchResults = entry.value.map {
                calculateFixtureImpact(it)
            }

            val matchCount = matchResults.size.toDouble()

            val gwAttack = matchResults.sumOf {
                it.attackingMultiplier
            } / matchCount

            val gwDefense = matchResults.sumOf {
                it.defensiveMultiplier
            } / matchCount

            val gwCleanSheet = matchResults.sumOf {
                it.cleanSheetOdds
            } / matchCount

            val gwFdr = matchResults.sumOf {
                it.difficulty
            } / matchCount

            weightedAttack += gwAttack * weight
            weightedDefense += gwDefense * weight
            weightedCleanSheet += gwCleanSheet * weight
            weightedFdr += gwFdr * weight

            totalWeight += weight
        }

        val finalAttack =
            (weightedAttack / totalWeight).coerceIn(0.5, 1.6)

        val finalDefense =
            (weightedDefense / totalWeight).coerceIn(0.5, 1.6)

        val finalCleanSheet =
            (weightedCleanSheet / totalWeight).coerceIn(0.02, 0.70)

        val finalFdr =
            weightedFdr / totalWeight

        return FixtureImpact(
            attackingMultiplier = round2(finalAttack),
            defensiveMultiplier = round2(finalDefense),
            cleanSheetOdds = round2(finalCleanSheet),
            averageFdr = round1(finalFdr),
            summaryAr = buildSummary(finalFdr)
        )
    }

    /**
     * الحفاظ على الواجهة القديمة لتجنب كسر الاستدعاءات الحالية.
     */
    @Suppress("UNUSED_PARAMETER")
    fun analyzeUpcomingFixtures(
        fixtures: List<UpcomingFixture>,
        position: PlayerPosition,
        horizon: Int = 3
    ): FixtureImpact {
        return analyzeFixtureRun(
            fixtures = fixtures,
            horizon = horizon
        )
    }

    private fun calculateFixtureImpact(
        fixture: UpcomingFixture
    ): SingleFixtureImpact {

        val difficulty = fixture.difficulty.coerceIn(1, 5)

        val attackBase = when (difficulty) {
            1 -> 1.35
            2 -> 1.22
            3 -> 1.00
            4 -> 0.82
            5 -> 0.65
            else -> 1.00
        }

        val cleanSheetBase = when (difficulty) {
            1 -> 0.50
            2 -> 0.42
            3 -> 0.28
            4 -> 0.16
            5 -> 0.08
            else -> 0.28
        }

        val homeAttackFactor =
            if (fixture.isHome) 1.12 else 0.90

        val homeDefenseFactor =
            if (fixture.isHome) 1.15 else 0.85

        val attackMultiplier =
            (attackBase * homeAttackFactor).coerceIn(0.5, 1.6)

        val defenseMultiplier =
            ((1.0 / attackBase) * homeDefenseFactor)
                .coerceIn(0.5, 1.6)

        val cleanSheetOdds =
            (cleanSheetBase *
                if (fixture.isHome) 1.18 else 0.84)
                .coerceIn(0.02, 0.70)

        return SingleFixtureImpact(
            attackingMultiplier = attackMultiplier,
            defensiveMultiplier = defenseMultiplier,
            cleanSheetOdds = cleanSheetOdds,
            difficulty = difficulty.toDouble()
        )
    }

    private fun buildSummary(fdr: Double): String {
        return when {
            fdr <= 2.3 ->
                "جدول ممتاز ومناسب لتحقيق نقاط مرتفعة"

            fdr <= 2.8 ->
                "جدول إيجابي ومناسب لتحقيق نقاط جيدة"

            fdr <= 3.3 ->
                "جدول متوسط ومتوازن الصعوبة"

            fdr <= 4.0 ->
                "جدول صعب يتطلب الحذر"

            else ->
                "جدول شديد الصعوبة"
        }
    }

    private fun emptyImpact() = FixtureImpact(
        attackingMultiplier = 1.0,
        defensiveMultiplier = 1.0,
        cleanSheetOdds = 0.25,
        averageFdr = 3.0,
        summaryAr = "لا توجد مباريات معلنة قادمة"
    )

    private fun round1(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    private fun round2(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0
}
