package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.SquadOptimizer
import java.util.Locale

class WildcardAnalyzer(
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27
) {

    /**
     * Evaluates Wildcard chip activation based on squad health, multi-GW horizon planning, hit savings, and deadlines
     */
    fun analyze(
        squad: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory,
        freeTransfersAvailable: Int = 1,
        budget: Double = 100.0,
        horizonGameweeks: Int = 5,
        half: ChipHalf = ChipHalf.fromGameweek(currentGameweek)
    ): SingleChipAnalysis {
        val currentState = userHistory.getChipState(ChipType.WILDCARD, half, currentGameweek)

        // 1. Check if user already used this chip or if it expired
        if (currentState == ChipStatus.USED || currentState == ChipStatus.EXPIRED) {
            val isExpired = currentState == ChipStatus.EXPIRED
            return SingleChipAnalysis(
                chip = ChipType.WILDCARD,
                half = half,
                status = currentState,
                verdict = ChipVerdict.USED_OR_EXPIRED,
                recommendedGameweek = if (half == ChipHalf.FIRST_HALF) config.secondHalfStartGw else currentGameweek,
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 0.0,
                confidence = ChipConfidence.HIGH,
                primaryReasonAr = if (isExpired) {
                    "انتهت صلاحية وايلد كارد المجموعة الأولى (${half.titleAr}) بنهاية الجولة ${config.firstHalfDeadlineGw}."
                } else {
                    "تم استهلاك وايلد كارد (${half.titleAr}) مسبقاً خلال هذا الموسم."
                },
                detailedPointsAr = listOf("الرقاقة غير متاحة للاستخدام مجدداً وفقاً للوائح الفانتازي الرسمية.")
            )
        }

        // 2. Check rule: Wildcard cannot be activated in Gameweek 1
        if (currentGameweek == 1 && ChipType.WILDCARD in config.disallowedChipsInGw1) {
            return SingleChipAnalysis(
                chip = ChipType.WILDCARD,
                half = half,
                status = ChipStatus.AVAILABLE,
                verdict = ChipVerdict.SAVE_FOR_LATER,
                recommendedGameweek = 8,
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 28.0,
                confidence = ChipConfidence.VERY_HIGH,
                primaryReasonAr = "ممنوع رسمياً: لا يمكن تفعيل وايلد كارد في الجولة الأولى (GW 1) لأن التبديلات مفتوحة مجاناً افتراضياً.",
                detailedPointsAr = listOf("تتيح الفانتازي إجراء تبديلات مجانية بلا حدود لتشكيل الفريق المبدئي قبل بداية الموسم.")
            )
        }

        // 3. Assess Squad Health & Crisis Indicators
        val injuredOrSuspended = squad.filter {
            !it.availability.isAvailable || it.availability.status in listOf("i", "s", "u") ||
                    (it.availability.chanceOfPlayingNextRound != null && it.availability.chanceOfPlayingNextRound < 75)
        }

        val lowMinutesOrInactive = squad.filter {
            it !in injuredOrSuspended && (it.minutesPlayed < 200 || it.prediction?.riskLevel == RiskLevel.CRITICAL)
        }

        val badFixtureSwingPlayers = squad.filter { p ->
            val upcoming = p.upcomingFixtures.take(horizonGameweeks)
            upcoming.isNotEmpty() && upcoming.map { it.difficulty }.average() >= 3.6
        }

        // Estimate number of transfers urgently needed to fix squad
        val urgentIssuesCount = injuredOrSuspended.size + lowMinutesOrInactive.size + (badFixtureSwingPlayers.size / 2)
        val neededTransfers = urgentIssuesCount.coerceAtLeast(0)
        val transfersRequiringHits = (neededTransfers - freeTransfersAvailable).coerceAtLeast(0)
        val hitPointsSaved = transfersRequiringHits * 4

        // 4. Mathematical calculation: Multi-Gameweek horizon rebuilding benefit compared with normal FT planning
        val rebuiltSquad = squadOptimizer.optimizeSquad(
            allPlayers = allPlayers,
            budget = budget,
            strategy = OptimizationStrategy.BALANCED,
            horizon = horizonGameweeks
        )

        val currentSquadHorizonPts = squad.take(11).sumOf { p ->
            (p.prediction?.expectedPoints ?: 3.5) * horizonGameweeks
        }
        val rebuiltSquadHorizonPts = rebuiltSquad.projectedStartingPoints * horizonGameweeks

        val performanceDeltaOverHorizon = (rebuiltSquadHorizonPts - currentSquadHorizonPts).coerceAtLeast(0.0)
        val totalWildcardValue = Math.round((performanceDeltaOverHorizon + hitPointsSaved) * 10.0) / 10.0
        val currentGwGain = totalWildcardValue

        // 5. Expiration Warning: Set 1 Wildcard expires after GW19 deadline
        val isSet1ExpiryApproaching = half == ChipHalf.FIRST_HALF && currentGameweek in (config.firstHalfDeadlineGw - 1)..config.firstHalfDeadlineGw
        val expirationWarning = if (isSet1ExpiryApproaching) {
            "تحذير انتهاء الصلاحية: تنتهي صلاحية وايلد كارد المجموعة الأولى مع بداية الجولة ${config.secondHalfStartGw} ولن تُرحّل للنصف الثاني!"
        } else null

        // 6. Look ahead for strategic Wildcard windows within this half
        val halfEndGw = if (half == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks
        val strategicTargetGws = if (half == ChipHalf.FIRST_HALF) {
            listOf(8, 12, 18).filter { it in currentGameweek..halfEndGw }
        } else {
            listOf(30, 31, 33).filter { it in currentGameweek..halfEndGw }
        }

        val futureOpportunityGain = if (half == ChipHalf.FIRST_HALF) 26.0 else 32.0

        // 7. Decision Logic:
        val isCrisis = urgentIssuesCount >= 3 && hitPointsSaved >= 8
        val isSubstantialGain = totalWildcardValue >= 28.0
        val shouldActivateNow = isSet1ExpiryApproaching || isCrisis || isSubstantialGain

        val verdict: ChipVerdict
        val recommendedGw: Int
        val confidence: ChipConfidence
        val primaryReason: String
        val detailedReasons = mutableListOf<String>()

        if (shouldActivateNow) {
            verdict = ChipVerdict.ACTIVATE_NOW
            recommendedGw = currentGameweek
            confidence = if (isSet1ExpiryApproaching || urgentIssuesCount >= 4) ChipConfidence.VERY_HIGH else ChipConfidence.HIGH

            primaryReason = when {
                isSet1ExpiryApproaching -> {
                    "تنبيه عاجل: تنتهي صلاحية وايلد كارد المجموعة الأولى مع بداية الجولة ${config.secondHalfStartGw} ولا تُرحّل! يجب تفعيله الآن لتفادي ضياعه."
                }
                isCrisis -> {
                    "أزمة تشكيلة: لديك $urgentIssuesCount لاعبين مصابين أو غير أساسيين. تفعيل الوايلد كارد يوفر عليك $hitPointsSaved نقطة سالبة (-4) ويعيد بناء الفريق فوراً."
                }
                else -> {
                    "فرصة تحول تكتيكي مثالية: إعادة بناء التشكيلة تمنحك تفوقاً متوقعاً قدره +%.1f نقطة خلال الجولات الـ $horizonGameweeks القادمة.".format(Locale.US, totalWildcardValue)
                }
            }

            detailedReasons.add("توفير نقاط الخصم (Hits Avoided): يوفر $hitPointsSaved نقطة كانت ستُخصم لإجراء $neededTransfers تبديلات مطلوبة.")
            detailedReasons.add("فارق النقاط التراكمي المتوقع عبر $horizonGameweeks جولات: +%.1f نقطة.".format(Locale.US, performanceDeltaOverHorizon))
            if (injuredOrSuspended.isNotEmpty()) {
                detailedReasons.add("عناصر مصابة/موقوفة يجب التخلص منها: ${injuredOrSuspended.joinToString { it.webName }}.")
            }
            if (isSet1ExpiryApproaching) {
                detailedReasons.add("قاعدة رسمية: عدم تفعيل وايلد كارد المجموعة الأولى قبل الجولة ${config.secondHalfStartGw} يؤدي لفقدانه نهائياً.")
            }
        } else {
            verdict = ChipVerdict.SAVE_FOR_LATER
            recommendedGw = strategicTargetGws.firstOrNull() ?: (if (half == ChipHalf.FIRST_HALF) 18 else 31)
            confidence = ChipConfidence.HIGH

            primaryReason = if (half == ChipHalf.FIRST_HALF) {
                "تشكيلتك الحالية متماسكة وصحية ($urgentIssuesCount مشاكل فقط). احتفظ بالوايلد كارد لتقلبات الجولات القادمة (GW $recommendedGw)."
            } else {
                "احتفظ بوايلد كارد النصف الثاني للجولة $recommendedGw لإعادة هيكلة الفريق قبل الجولات المزدوجة والتجهيز للبينش بوست."
            }

            detailedReasons.add("القيمة المتوقعة مستقبلاً (+%.1f نقطة) تتفوق على استخدامها الاضطراري الآن (+%.1f نقطة).".format(Locale.US, futureOpportunityGain, totalWildcardValue))
            detailedReasons.add("عدد التبديلات المطلوبة حالياً ($neededTransfers) يمكن إدارتها عبر التبديلات المجانية المتاحة ($freeTransfersAvailable).")
            if (badFixtureSwingPlayers.isNotEmpty()) {
                detailedReasons.add("راقب تقلب جدول مباريات هؤلاء اللاعبين في الأسابيع القادمة: ${badFixtureSwingPlayers.take(3).joinToString { it.webName }}.")
            }
        }

        return SingleChipAnalysis(
            chip = ChipType.WILDCARD,
            half = half,
            status = currentState,
            verdict = verdict,
            recommendedGameweek = recommendedGw,
            alternativeGameweeks = strategicTargetGws.filter { it != recommendedGw },
            expectedPointsGain = if (verdict == ChipVerdict.ACTIVATE_NOW) totalWildcardValue else futureOpportunityGain,
            currentGwGain = currentGwGain,
            futureOpportunityGain = futureOpportunityGain,
            confidence = confidence,
            primaryReasonAr = primaryReason,
            detailedPointsAr = detailedReasons,
            affectedPlayers = injuredOrSuspended + lowMinutesOrInactive,
            temporarySquad = rebuiltSquad.totalSquad,
            expirationWarningAr = expirationWarning
        )
    }
}
