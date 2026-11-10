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

        val status = availability.status.lowercase()
        val chance = availability.chanceOfPlayingNextRound

        // 1. الغياب المؤكد
        if (
            status == "i" ||
            status == "s" ||
            status == "u" ||
            chance == 0
        ) {
            val reason = when (status) {
                "i" -> "مصاب: ${availability.news.ifEmpty { "غير متاح" }}"
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

        // 2. تقدير الدور الأساسي اعتماداً على البيانات التاريخية
        val completedGws = totalGameweeks.coerceAtLeast(1)

        val safeMinutes = minutesPlayed.coerceAtLeast(0)
        val safeStarts = starts.coerceAtLeast(0)

        val startRatio = (
            safeStarts.toDouble() / completedGws
        ).coerceIn(0.0, 1.0)

        val averageMinutes = (
            safeMinutes.toDouble() / completedGws
        ).coerceIn(0.0, 90.0)

        val hasHistory = safeMinutes > 0 || safeStarts > 0

        val baseStartProbability = if (hasHistory) {
            (
                startRatio * 0.70 +
                    (averageMinutes / 90.0) * 0.30
            ).coerceIn(0.05, 0.95)
        } else {
            0.35
        }

        // تقدير دقائق اللعب عند الجاهزية الكاملة.
        val estimatedMinutesWhenAvailable = (
            baseStartProbability * 82.0 +
                (1.0 - baseStartProbability) * 20.0
            ).coerceIn(5.0, 90.0)

        // 3. احتمال الجاهزية الطبية
        val medicalProbability = when {
            chance != null ->
                (chance / 100.0).coerceIn(0.0, 1.0)

            status == "d" -> 0.75

            else -> 1.0
        }

        // 4. فصل احتمال البدء عن الجاهزية الطبية.
        val finalStartProbability = (
            baseStartProbability * medicalProbability
        ).coerceIn(0.0, 1.0)

        val expectedMinutes = (
            estimatedMinutesWhenAvailable * medicalProbability
        ).coerceIn(0.0, 90.0)

        // 5. مخاطر الغياب والمداورة.
        val medicalRisk = 1.0 - medicalProbability

        val rotationRisk = (
            1.0 - baseStartProbability
        ) * 0.70

        val riskFactor = maxOf(
            medicalRisk,
            rotationRisk
        ).coerceIn(0.0, 1.0)

        // 6. التفسير باللغة العربية.
        val summary = when {
            medicalProbability < 0.50 ->
                "مخاطر طبية مرتفعة واحتمال المشاركة ضعيف"

            medicalProbability < 0.90 ->
                "توجد شكوك حول الجاهزية الطبية: احتمال المشاركة ${chance ?: 75}%"

            baseStartProbability >= 0.80 ->
                "لاعب أساسي غالباً مع جاهزية جيدة"

            baseStartProbability >= 0.55 ->
                "مرشح للبدء أساسياً مع احتمال المداورة"

            baseStartProbability >= 0.30 ->
                "لاعب مداورة وقد يشارك بديلاً"

            else ->
                "احتمال البدء أساسياً ضعيف"
        }

        return AvailabilityAssessment(
            startProbability = round2(finalStartProbability),
            expectedMinutes = round1(expectedMinutes),
            riskFactor = round2(riskFactor),
            summaryAr = summary
        )
    }

    /**
     * الواجهة المستخدمة حالياً داخل PredictionEngine.
     */
    fun assess(
        player: PlayerModel,
        currentGameweek: Int
    ): AvailabilityAssessment {

        // نفترض أن الجولة الحالية لم تكتمل بعد.
        val completedGameweeks =
            (currentGameweek - 1).coerceAtLeast(1)

        return assessAvailability(
            availability = player.availability,
            minutesPlayed = player.minutesPlayed,
            starts = player.starts,
            totalGameweeks = completedGameweeks
        )
    }

    private fun round1(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    private fun round2(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0
}
