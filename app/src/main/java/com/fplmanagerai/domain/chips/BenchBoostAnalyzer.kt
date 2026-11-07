package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import java.util.Locale

class BenchBoostAnalyzer(
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27
) {

    /**
     * Evaluates Bench Boost chip potential based on squad's 4 bench players
     */
    fun analyze(
        squad: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory,
        explicitBench: List<PlayerModel>? = null,
        half: ChipHalf = ChipHalf.fromGameweek(currentGameweek)
    ): SingleChipAnalysis {
        val currentState = userHistory.getChipState(ChipType.BENCH_BOOST, half, currentGameweek)

        // 1. Check if user already used this chip or if it expired
        if (currentState == ChipStatus.USED || currentState == ChipStatus.EXPIRED) {
            val isExpired = currentState == ChipStatus.EXPIRED
            return SingleChipAnalysis(
                chip = ChipType.BENCH_BOOST,
                half = half,
                status = currentState,
                verdict = ChipVerdict.USED_OR_EXPIRED,
                recommendedGameweek = currentGameweek,
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 0.0,
                confidence = ChipConfidence.HIGH,
                primaryReasonAr = if (isExpired) {
                    "انتهت صلاحية بينش بوست المجموعة الأولى (${half.titleAr}) بنهاية الجولة ${config.firstHalfDeadlineGw}."
                } else {
                    "تم استهلاك بينش بوست (${half.titleAr}) مسبقاً خلال هذا الموسم."
                },
                detailedPointsAr = listOf("الرقاقة غير متاحة للاستخدام مجدداً وفقاً للوائح الفانتازي الرسمية.")
            )
        }

        // 2. Identify the 4 bench players
        val benchPlayers = explicitBench ?: identifyBenchPlayers(squad)

        // 3. Inspect availability, minutes, starting probability, and fixtures for all 4 bench players
        val weakOrDoubtfulPlayers = mutableListOf<PlayerModel>()
        val zeroFixturePlayers = mutableListOf<PlayerModel>()
        var totalBenchXpts = 0.0
        var benchDgwPlayerCount = 0

        for (benchPlayer in benchPlayers) {
            val pred = benchPlayer.prediction
            val basePoints = pred?.expectedPoints ?: (benchPlayer.totalPoints / 30.0).coerceAtLeast(1.5)
            val chance = benchPlayer.availability.chanceOfPlayingNextRound ?: 100
            val isAvailable = benchPlayer.availability.isAvailable
            val starts = benchPlayer.starts
            val expectedMins = pred?.expectedMinutes ?: (if (starts > 5) 70.0 else 25.0)

            val teamFixturesNow = fixtures.filter {
                it.event == currentGameweek && it.isTeamInvolved(benchPlayer.teamId)
            }

            if (teamFixturesNow.isEmpty()) {
                zeroFixturePlayers.add(benchPlayer)
            } else if (teamFixturesNow.size >= 2) {
                benchDgwPlayerCount++
            }

            val isDoubling = teamFixturesNow.size >= 2
            val effectivePlayerPoints = if (teamFixturesNow.isEmpty()) {
                0.0
            } else {
                val multiplier = if (isDoubling) 1.8 else 1.0
                basePoints * multiplier
            }
            totalBenchXpts += effectivePlayerPoints

            // Criterion for weak/doubtful bench player:
            // - Not fully available
            // - Chance < 75%
            // - Injured / Suspended / Unavailable
            // - Critical risk
            // - Low expected minutes (< 45 min)
            val isUnreliable = !isAvailable ||
                    chance < 75 ||
                    benchPlayer.availability.status in listOf("i", "s", "u") ||
                    pred?.riskLevel == RiskLevel.CRITICAL ||
                    expectedMins < 45.0 ||
                    effectivePlayerPoints < 2.0

            if (isUnreliable) {
                weakOrDoubtfulPlayers.add(benchPlayer)
            }
        }

        val currentGwGain = Math.round(totalBenchXpts * 10.0) / 10.0

        // 4. Scan future opportunities in the active half
        val halfEndGw = if (half == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks
        val futureGameweeks = ((currentGameweek + 1)..halfEndGw)

        val confirmedFutureDgws = mutableListOf<Int>()
        val projectedFutureDgws = mutableListOf<Int>()

        for (gw in futureGameweeks) {
            val fixturesInGw = fixtures.filter { it.event == gw }
            val dgwTeams = mutableSetOf<Int>()
            fixturesInGw.forEach { f ->
                if (fixturesInGw.count { it.isTeamInvolved(f.homeTeamId) } >= 2) dgwTeams.add(f.homeTeamId)
                if (fixturesInGw.count { it.isTeamInvolved(f.awayTeamId) } >= 2) dgwTeams.add(f.awayTeamId)
            }
            if (dgwTeams.size >= 4) {
                confirmedFutureDgws.add(gw)
            }
        }

        if (confirmedFutureDgws.isEmpty()) {
            if (half == ChipHalf.FIRST_HALF) {
                projectedFutureDgws.addAll(listOf(8, 12).filter { it in futureGameweeks })
            } else {
                projectedFutureDgws.addAll(listOf(34, 37).filter { it in futureGameweeks })
            }
        }

        val benchmarkDgwGain = if (half == ChipHalf.FIRST_HALF) 18.0 else 22.0
        val futureOpportunityGain = if (confirmedFutureDgws.isNotEmpty()) {
            benchmarkDgwGain
        } else if (projectedFutureDgws.isNotEmpty()) {
            Math.round(benchmarkDgwGain * config.projectedFixtureConfidenceDiscount * 10.0) / 10.0
        } else {
            16.0
        }

        // 5. Expiration Warning for Set 1
        val isSet1ExpiryApproaching = half == ChipHalf.FIRST_HALF && currentGameweek in (config.firstHalfDeadlineGw - 1)..config.firstHalfDeadlineGw
        val expirationWarning = if (isSet1ExpiryApproaching) {
            "تحذير انتهاء الصلاحية: تنتهي صلاحية بينش بوست النصف الأول مع بداية الجولة ${config.secondHalfStartGw} ولا تُرحّل!"
        } else null

        // 6. Decision Logic:
        // STRICT MANDATE: Never recommend Bench Boost when bench is weak or several players are unlikely to play.
        val hasWeakBench = weakOrDoubtfulPlayers.isNotEmpty() || zeroFixturePlayers.isNotEmpty() || currentGwGain < 14.0
        val isDoubleGameweekBench = benchDgwPlayerCount >= 2 && currentGwGain >= 18.0
        val isSolidSingleGwBench = weakOrDoubtfulPlayers.isEmpty() && zeroFixturePlayers.isEmpty() && currentGwGain >= 16.0

        val shouldActivateNow = !hasWeakBench && (isDoubleGameweekBench || (isSolidSingleGwBench && confirmedFutureDgws.isEmpty() && projectedFutureDgws.isEmpty()) || (isSet1ExpiryApproaching && currentGwGain >= 15.0))

        val verdict: ChipVerdict
        val recommendedGw: Int
        val confidence: ChipConfidence
        val primaryReason: String
        val detailedReasons = mutableListOf<String>()

        if (shouldActivateNow) {
            verdict = ChipVerdict.ACTIVATE_NOW
            recommendedGw = currentGameweek
            confidence = if (benchDgwPlayerCount >= 2) ChipConfidence.VERY_HIGH else ChipConfidence.HIGH
            primaryReason = "اكتمال جاهزية دكة البدلاء بالكامل (4 لاعبين أساسيين بجداول مواتية) بمردود نقطي متوقع استثنائي."

            detailedReasons.add("إجمالي النقاط المتوقعة من البدلاء الأربعة: +%.1f نقطة.".format(Locale.US, currentGwGain))
            detailedReasons.add("جميع البدلاء الأربعة جاهزون بدنياً ويشاركون بانتظام كعناصر أساسية.")
            if (benchDgwPlayerCount > 0) {
                detailedReasons.add("يوجد $benchDgwPlayerCount لاعبين على الدكة يخوضون جولة مزدوجة (DGW).")
            }
            detailedReasons.add("البدلاء: " + benchPlayers.joinToString { "${it.webName} (%.1f)".format(Locale.US, it.prediction?.expectedPoints ?: 0.0) })
        } else {
            verdict = ChipVerdict.SAVE_FOR_LATER
            recommendedGw = confirmedFutureDgws.firstOrNull() ?: projectedFutureDgws.firstOrNull() ?: (currentGameweek + 4).coerceAtMost(halfEndGw)
            confidence = ChipConfidence.HIGH

            primaryReason = when {
                zeroFixturePlayers.isNotEmpty() -> {
                    "يوجد لاعبون على الدكة بدون مباريات في هذه الجولة (${zeroFixturePlayers.joinToString { it.webName }}). تفعيل الخاصية الآن هدر تام!"
                }
                weakOrDoubtfulPlayers.isNotEmpty() -> {
                    "دكة البدلاء غير جاهزة: لديك بدلاء مصابون أو لا يشاركون أساسياً (${weakOrDoubtfulPlayers.joinToString { it.webName }}). احتفظ بها."
                }
                currentGwGain < 14.0 -> {
                    "عائد الدكة الحالي (+%.1f نقطة) ضعيف جداً. الحد الأدنى لتفعيل بينش بوست هو 15+ نقطة.".format(Locale.US, currentGwGain)
                }
                else -> {
                    "احتفظ بالبينش بوست للجولة المزدوجة (DGW $recommendedGw) لتحقيق أقصى استفادة (+%.1f نقطة).".format(Locale.US, futureOpportunityGain)
                }
            }

            if (weakOrDoubtfulPlayers.isNotEmpty()) {
                detailedReasons.add("عناصر مهددة بعدم اللعب: ${weakOrDoubtfulPlayers.joinToString { "${it.webName} (${it.availability.labelAr})" }}.")
            }
            if (zeroFixturePlayers.isNotEmpty()) {
                detailedReasons.add("لاعبون بلا مباريات (Blank): ${zeroFixturePlayers.joinToString { it.webName }}.")
            }
            detailedReasons.add("العائد المتوقع الحالي (+%.1f نقطة) مقابل العائد المستهدف في الجولات المزدوجة (+%.1f نقطة).".format(Locale.US, currentGwGain, futureOpportunityGain))
            detailedReasons.add("الاستراتيجية الفضلى: تفعيل الوايلد كارد قبل الجولة المزدوجة لتجهيز 15 لاعباً أساسياً ثم تفعيل بينش بوست فوراً.")
        }

        return SingleChipAnalysis(
            chip = ChipType.BENCH_BOOST,
            half = half,
            status = currentState,
            verdict = verdict,
            recommendedGameweek = recommendedGw,
            alternativeGameweeks = (confirmedFutureDgws + projectedFutureDgws).filter { it != recommendedGw },
            expectedPointsGain = if (verdict == ChipVerdict.ACTIVATE_NOW) currentGwGain else futureOpportunityGain,
            currentGwGain = currentGwGain,
            futureOpportunityGain = futureOpportunityGain,
            confidence = confidence,
            primaryReasonAr = primaryReason,
            detailedPointsAr = detailedReasons,
            affectedPlayers = benchPlayers,
            expirationWarningAr = expirationWarning
        )
    }

    private fun identifyBenchPlayers(squad: List<PlayerModel>): List<PlayerModel> {
        if (squad.size <= 4) return squad

        val gks = squad.filter { it.position == PlayerPosition.GKP }
        val defs = squad.filter { it.position == PlayerPosition.DEF }
        val mids = squad.filter { it.position == PlayerPosition.MID }
        val fwds = squad.filter { it.position == PlayerPosition.FWD }

        val benchGk = gks.sortedBy { it.prediction?.expectedPoints ?: 0.0 }.firstOrNull() ?: gks.first()
        val outfield = (defs + mids + fwds).sortedBy { it.prediction?.expectedPoints ?: 0.0 }
        val benchOutfield = outfield.take(3)
        return listOf(benchGk) + benchOutfield
    }
}
