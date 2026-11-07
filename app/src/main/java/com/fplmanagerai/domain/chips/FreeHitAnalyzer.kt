package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.SquadOptimizer
import java.util.Locale

class FreeHitAnalyzer(
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27
) {

    /**
     * Evaluates Free Hit chip potential for blank gameweek mitigation and point maximization
     */
    fun analyze(
        squad: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory,
        budget: Double = 100.0,
        half: ChipHalf = ChipHalf.fromGameweek(currentGameweek)
    ): SingleChipAnalysis {
        val currentState = userHistory.getChipState(ChipType.FREE_HIT, half, currentGameweek)

        // 1. Check if user already used this chip or if it expired
        if (currentState == ChipStatus.USED || currentState == ChipStatus.EXPIRED) {
            val isExpired = currentState == ChipStatus.EXPIRED
            return SingleChipAnalysis(
                chip = ChipType.FREE_HIT,
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
                    "انتهت صلاحية فري هيت المجموعة الأولى (${half.titleAr}) بنهاية الجولة ${config.firstHalfDeadlineGw}."
                } else {
                    "تم استهلاك فري هيت (${half.titleAr}) مسبقاً خلال هذا الموسم."
                },
                detailedPointsAr = listOf("الرقاقة غير متاحة للاستخدام مجدداً وفقاً للوائح الفانتازي الرسمية.")
            )
        }

        // 2. Check rule: Free Hit cannot be activated in Gameweek 1
        if (currentGameweek == 1 && ChipType.FREE_HIT in config.disallowedChipsInGw1) {
            return SingleChipAnalysis(
                chip = ChipType.FREE_HIT,
                half = half,
                status = ChipStatus.AVAILABLE,
                verdict = ChipVerdict.SAVE_FOR_LATER,
                recommendedGameweek = 12,
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 20.0,
                confidence = ChipConfidence.VERY_HIGH,
                primaryReasonAr = "ممنوع رسمياً: لا يمكن تفعيل فري هيت في الجولة الأولى (GW 1) لأن التبديلات مفتوحة مجاناً.",
                detailedPointsAr = listOf("تمنح قواعد الفانتازي تبديلات لا نهائية مجانية قبل بداية الموسم، فلا حاجة لاستخدام الفري هيت.")
            )
        }

        // 3. Check official consecutive-gameweek restriction:
        val wasUsedLastGw = userHistory.lastUsedFreeHitGw == (currentGameweek - 1)
        if (wasUsedLastGw && !config.allowConsecutiveFreeHit) {
            return SingleChipAnalysis(
                chip = ChipType.FREE_HIT,
                half = half,
                status = ChipStatus.AVAILABLE,
                verdict = ChipVerdict.SAVE_FOR_LATER,
                recommendedGameweek = (currentGameweek + 2).coerceAtMost(if (half == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks),
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 22.0,
                confidence = ChipConfidence.VERY_HIGH,
                primaryReasonAr = "ممنوع رسمياً: لا يمكن تفعيل فري هيت في جولتين متتاليتين بحسب لوائح الفانتازي الرسمية.",
                detailedPointsAr = listOf("تم تفعيل فري هيت في الجولة السابقة (GW ${currentGameweek - 1}). يجب الانتظار لجولة قادمة.")
            )
        }

        // 4. Inspect Current Gameweek Fixtures & detect Blank Gameweek (BGW)
        val fixturesNow = fixtures.filter { it.event == currentGameweek }
        val teamsWithMatchesNow = fixturesNow.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.toSet()

        val squadPlayersBlanking = squad.filter { it.teamId !in teamsWithMatchesNow }
        val squadPlayersSidelined = squad.filter {
            it.teamId in teamsWithMatchesNow &&
                    (!it.availability.isAvailable || it.availability.status in listOf("i", "s", "u"))
        }
        val totalUnavailableCount = squadPlayersBlanking.size + squadPlayersSidelined.size

        // Calculate expected points of Current Squad for this GW (players with no fixture = 0 pts)
        val currentSquadStarting11 = squad.take(11)
        val currentSquadExpectedPts = currentSquadStarting11.sumOf { p ->
            if (p.teamId !in teamsWithMatchesNow || !p.availability.isAvailable) 0.0
            else p.prediction?.expectedPoints ?: 3.5
        }

        // 5. Build temporary 15-player Free Hit Squad targeting active players with high xPts
        val eligibleActivePlayers = allPlayers.filter { p ->
            p.teamId in teamsWithMatchesNow && p.availability.isAvailable && p.prediction?.riskLevel != RiskLevel.CRITICAL
        }

        val freeHitOptimized = if (eligibleActivePlayers.size >= 15) {
            squadOptimizer.optimizeSquad(
                allPlayers = eligibleActivePlayers,
                budget = budget,
                strategy = OptimizationStrategy.MAX_EXPECTED_POINTS,
                horizon = 1
            )
        } else {
            null
        }

        val freeHitExpectedPts = freeHitOptimized?.let {
            it.projectedStartingPoints + it.projectedCaptainPoints
        } ?: (currentSquadExpectedPts + 25.0)

        // Mathematical calculation: Difference between optimized temporary squad and existing squad for same GW
        val currentGwGain = Math.round((freeHitExpectedPts - currentSquadExpectedPts).coerceAtLeast(0.0) * 10.0) / 10.0

        // 6. Detect future major Blank Gameweeks within this half
        val halfEndGw = if (half == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks
        val futureGameweeks = ((currentGameweek + 1)..halfEndGw)

        val confirmedFutureBgws = mutableListOf<Int>()
        val projectedFutureBgws = mutableListOf<Int>()

        for (gw in futureGameweeks) {
            val fList = fixtures.filter { it.event == gw }
            if (fList.isNotEmpty() && fList.size <= 6) {
                confirmedFutureBgws.add(gw)
            }
        }

        if (confirmedFutureBgws.isEmpty()) {
            if (half == ChipHalf.FIRST_HALF) {
                // Autumn postponements / cup clashes occasionally in GW7-12
                projectedFutureBgws.addAll(listOf(7, 12, 18).filter { it in futureGameweeks })
            } else {
                // Major spring BGWs clashing with FA Cup (GW29, 34)
                projectedFutureBgws.addAll(listOf(29, 34).filter { it in futureGameweeks })
            }
        }

        val benchmarkBgwGain = if (half == ChipHalf.FIRST_HALF) 18.0 else 24.0
        val futureOpportunityGain = if (confirmedFutureBgws.isNotEmpty()) {
            benchmarkBgwGain
        } else if (projectedFutureBgws.isNotEmpty()) {
            Math.round(benchmarkBgwGain * config.projectedFixtureConfidenceDiscount * 10.0) / 10.0
        } else {
            15.0
        }

        // 7. Expiration Warning for Set 1
        val isSet1ExpiryApproaching = half == ChipHalf.FIRST_HALF && currentGameweek in (config.firstHalfDeadlineGw - 1)..config.firstHalfDeadlineGw
        val expirationWarning = if (isSet1ExpiryApproaching) {
            "تحذير انتهاء الصلاحية: تنتهي صلاحية فري هيت النصف الأول مع بداية الجولة ${config.secondHalfStartGw} ولا تُرحّل!"
        } else null

        // 8. Decision Logic:
        val isCriticalBlankForUser = totalUnavailableCount >= 4 || squadPlayersBlanking.size >= 3
        val shouldActivateNow = (isCriticalBlankForUser && currentGwGain >= 18.0) ||
                (squadPlayersBlanking.size >= 5) ||
                (isSet1ExpiryApproaching && currentGwGain >= 14.0)

        val verdict: ChipVerdict
        val recommendedGw: Int
        val confidence: ChipConfidence
        val primaryReason: String
        val detailedReasons = mutableListOf<String>()

        if (shouldActivateNow) {
            verdict = ChipVerdict.ACTIVATE_NOW
            recommendedGw = currentGameweek
            confidence = if (totalUnavailableCount >= 5) ChipConfidence.VERY_HIGH else ChipConfidence.HIGH
            primaryReason = when {
                isSet1ExpiryApproaching -> {
                    "فرصة أخيرة لتفعيل فري هيت المجموعة الأولى قبل انتهاء صلاحيتها رسمياً بنهاية الجولة ${config.firstHalfDeadlineGw}."
                }
                else -> {
                    "جولة فارغة حرجة (Blank GW): تشكيلتك تعاني من غياب $totalUnavailableCount لاعبين. الفري هيت ينقذك ويمنحك فارقاً ضخماً قدره +%.1f نقطة.".format(Locale.US, currentGwGain)
                }
            }

            detailedReasons.add("فارق النقاط المتوقع بين تشكيلة الفري هيت المؤقتة والتشكيلة الحالية: +%.1f نقطة.".format(Locale.US, currentGwGain))
            detailedReasons.add("لاعبون بلا مباريات في تشكيلتك: ${squadPlayersBlanking.joinToString { it.webName }}.")
            if (squadPlayersSidelined.isNotEmpty()) {
                detailedReasons.add("لاعبون مصابون/موقوفون: ${squadPlayersSidelined.joinToString { it.webName }}.")
            }
            detailedReasons.add("حماية التشكيلة: وفقاً للقواعد الرسمية، ستعود تشكيلتك الأصلية بالكامل تلقائياً في الجولة القادمة.")
        } else {
            verdict = ChipVerdict.SAVE_FOR_LATER
            recommendedGw = confirmedFutureBgws.firstOrNull() ?: projectedFutureBgws.firstOrNull() ?: (currentGameweek + 6).coerceAtMost(halfEndGw)
            confidence = ChipConfidence.HIGH
            primaryReason = if (totalUnavailableCount <= 2) {
                "تشكيلتك مكتملة وقادرة على بدء الجولة بـ 11 لاعباً أساسياً. احتفظ بالفري هيت للجولة الفارغة الكبرى (BGW $recommendedGw)."
            } else {
                "احتفظ بالفري هيت للجولة الفارغة الأكبر (BGW $recommendedGw) حيث يتجاوز العائد المتوقع +%.1f نقطة.".format(Locale.US, futureOpportunityGain)
            }

            detailedReasons.add("العائد المتوقع الآن (+%.1f نقطة) مقابل عائد الجولة الفارغة المستهدفة (+%.1f نقطة).".format(Locale.US, currentGwGain, futureOpportunityGain))
            if (confirmedFutureBgws.isNotEmpty()) {
                detailedReasons.add("جولات فارغة مؤكدة رسمياً: ${confirmedFutureBgws.joinToString { "GW$it" }}.")
            } else if (projectedFutureBgws.isNotEmpty()) {
                detailedReasons.add("جولات فارغة متوقعة في الروزنامة: ${projectedFutureBgws.joinToString { "GW$it" }}.")
            }
            detailedReasons.add("عدد اللاعبين المتاحين في تشكيلتك حالياً كافٍ لتفادي حرق الشريحة مبكراً.")
        }

        return SingleChipAnalysis(
            chip = ChipType.FREE_HIT,
            half = half,
            status = currentState,
            verdict = verdict,
            recommendedGameweek = recommendedGw,
            alternativeGameweeks = (confirmedFutureBgws + projectedFutureBgws).filter { it != recommendedGw },
            expectedPointsGain = if (verdict == ChipVerdict.ACTIVATE_NOW) currentGwGain else futureOpportunityGain,
            currentGwGain = currentGwGain,
            futureOpportunityGain = futureOpportunityGain,
            confidence = confidence,
            primaryReasonAr = primaryReason,
            detailedPointsAr = detailedReasons,
            affectedPlayers = squadPlayersBlanking + squadPlayersSidelined,
            temporarySquad = freeHitOptimized?.totalSquad,
            expirationWarningAr = expirationWarning
        )
    }
}
