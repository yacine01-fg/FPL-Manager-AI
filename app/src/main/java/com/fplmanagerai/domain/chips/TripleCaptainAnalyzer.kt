package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.domain.team.CaptainSelector
import java.util.Locale

class TripleCaptainAnalyzer(
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27
) {

    /**
     * Evaluates Triple Captain chip potential for the given squad and gameweek
     */
    fun analyze(
        squad: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory,
        half: ChipHalf = ChipHalf.fromGameweek(currentGameweek)
    ): SingleChipAnalysis {
        val currentState = userHistory.getChipState(ChipType.TRIPLE_CAPTAIN, half, currentGameweek)

        // 1. Check if user already used this chip or if it expired
        if (currentState == ChipStatus.USED || currentState == ChipStatus.EXPIRED) {
            val isExpired = currentState == ChipStatus.EXPIRED
            return SingleChipAnalysis(
                chip = ChipType.TRIPLE_CAPTAIN,
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
                    "انتهت صلاحية تريبل كابتن المجموعة الأولى (${half.titleAr}) بنهاية الجولة ${config.firstHalfDeadlineGw}."
                } else {
                    "تم استهلاك تريبل كابتن (${half.titleAr}) مسبقاً خلال هذا الموسم."
                },
                detailedPointsAr = listOf("الرقاقة غير متاحة للاستخدام مجدداً وفقاً للوائح الفانتازي الرسمية.")
            )
        }

        // 2. Data Validation: Guard against missing squad or player data
        if (squad.isEmpty() && allPlayers.isEmpty()) {
            return SingleChipAnalysis(
                chip = ChipType.TRIPLE_CAPTAIN,
                half = half,
                status = currentState,
                verdict = ChipVerdict.SAVE_FOR_LATER,
                recommendedGameweek = currentGameweek,
                alternativeGameweeks = emptyList(),
                expectedPointsGain = 0.0,
                currentGwGain = 0.0,
                futureOpportunityGain = 0.0,
                confidence = ChipConfidence.LOW,
                primaryReasonAr = "بيانات اللاعبين والتشكيلة غير كافية لتحليل الكابتن.",
                detailedPointsAr = listOf("تأكد من تحميل بيانات التشكيلة واللاعبين قبل تحليل الشريحة.")
            )
        }

        // 3. Select top captain candidate in the squad
        val poolForCaptain = if (squad.isNotEmpty()) squad else allPlayers
        val squadCaptainSelection = captainSelector.selectCaptain(poolForCaptain)
        val bestSquadCandidate = squadCaptainSelection.captain

        val benchmarkCandidate = if (allPlayers.isNotEmpty()) {
            captainSelector.selectCaptain(allPlayers).captain
        } else bestSquadCandidate

        val chosenCaptain = if ((bestSquadCandidate.prediction?.expectedPoints ?: 0.0) >= 5.0 || squad.isNotEmpty()) {
            bestSquadCandidate
        } else {
            benchmarkCandidate
        }

        // 4. Inspect current Gameweek for Double Gameweek (DGW) or Blank status
        val teamFixturesNow = fixtures.filter {
            it.event == currentGameweek && it.isTeamInvolved(chosenCaptain.teamId)
        }
        val isCurrentDgw = teamFixturesNow.size >= 2
        val isCurrentBlank = teamFixturesNow.isEmpty()
        val isConfirmedNow = teamFixturesNow.isNotEmpty()

        val nextFixture = chosenCaptain.upcomingFixtures.firstOrNull { it.gameweek == currentGameweek }
        val fdr = nextFixture?.difficulty ?: 3
        val isHome = nextFixture?.isHome ?: true

        val basePrediction = chosenCaptain.prediction
        val expectedMinutes = basePrediction?.expectedMinutes ?: (if (chosenCaptain.starts > 5) 80.0 else 30.0)
        val startProb = basePrediction?.startProbability ?: ((chosenCaptain.availability.chanceOfPlayingNextRound ?: 90) / 100.0)

        // Rotation & difficulty risk check
        val averageDgwFdr = if (teamFixturesNow.size >= 2) {
            teamFixturesNow.map { it.getDifficulty(chosenCaptain.teamId) }.average()
        } else {
            fdr.toDouble()
        }
        val isHarshDgw = isCurrentDgw && (averageDgwFdr >= 4.5 || expectedMinutes < 60.0 ||
                chosenCaptain.prediction?.riskLevel == RiskLevel.CRITICAL || chosenCaptain.prediction?.riskLevel == RiskLevel.HIGH)

        // التحقق من الجاهزية الطبية: استبعاد أي لاعب مصاب أو موقوف رسمياً
        val isMedicallyAvailable = chosenCaptain.availability.isAvailable &&
                chosenCaptain.availability.status !in listOf("i", "s", "u") &&
                (chosenCaptain.availability.chanceOfPlayingNextRound ?: 100) > 0

        // العائد الإضافي الصافي من التريبل كابتن = 1x نقاط الكابتن المتوقعة الأساسية للجولة كاملة
        // محرك التوقعات (PredictionEngine) يجمع بالفعل نقاط مباريات الجولة المزدوجة ومعدل الدقائق واحتمال المشاركة
        val effectiveCurrentXpts = if (isCurrentBlank || !isMedicallyAvailable) {
            0.0
        } else {
            basePrediction?.expectedPoints ?: run {
                val base = (chosenCaptain.totalPoints / 25.0).coerceAtLeast(2.0)
                base * startProb * (expectedMinutes / 90.0).coerceIn(0.2, 1.0)
            }
        }

        val currentGwGain = Math.round(effectiveCurrentXpts * 10.0) / 10.0

        // 4. Scan future opportunities within the half boundary
        val halfEndGw = if (half == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks
        val futureGameweeks = ((currentGameweek + 1)..halfEndGw)

        val confirmedFutureDgws = mutableListOf<Int>()
        val projectedFutureDgws = mutableListOf<Int>()

        for (gw in futureGameweeks) {
            val fixturesInGw = fixtures.filter { it.event == gw }
            val teamCountMap = mutableMapOf<Int, Int>()
            fixturesInGw.forEach { f ->
                teamCountMap[f.homeTeamId] = (teamCountMap[f.homeTeamId] ?: 0) + 1
                teamCountMap[f.awayTeamId] = (teamCountMap[f.awayTeamId] ?: 0) + 1
            }
            if (teamCountMap.values.any { it >= 2 }) {
                confirmedFutureDgws.add(gw)
            }
        }

        // Standard calendar projected DGWs if none confirmed yet
        if (confirmedFutureDgws.isEmpty()) {
            if (half == ChipHalf.FIRST_HALF) {
                // First half occasionally has minor DGW around GW7-12
                projectedFutureDgws.addAll(listOf(7, 12).filter { it in futureGameweeks })
            } else {
                // Second half major DGWs around GW34 and GW37
                projectedFutureDgws.addAll(listOf(34, 37).filter { it in futureGameweeks })
            }
        }

        // Future expected gain with discount for projected fixtures
        val hasConfirmedFutureDgw = confirmedFutureDgws.isNotEmpty()
        val projectedDgwBenchmark = if (half == ChipHalf.FIRST_HALF) 11.5 else 13.5
        val futureOpportunityGain = if (hasConfirmedFutureDgw) {
            projectedDgwBenchmark
        } else if (projectedFutureDgws.isNotEmpty()) {
            Math.round(projectedDgwBenchmark * config.projectedFixtureConfidenceDiscount * 10.0) / 10.0
        } else {
            8.5
        }

        // 5. Expiration Warning: Set 1 chips expire at GW19 deadline
        val isSet1ExpiryApproaching = half == ChipHalf.FIRST_HALF && currentGameweek in (config.firstHalfDeadlineGw - 1)..config.firstHalfDeadlineGw
        val expirationWarning = if (isSet1ExpiryApproaching) {
            "تحذير انتهاء الصلاحية: تنتهي صلاحية تريبل كابتن النصف الأول مع بداية الجولة ${config.secondHalfStartGw} ولا تُرحّل!"
        } else null

        // 6. Decision Logic:
        // Activate now if:
        // a) Imminent expiration: GW19 in first half -> MUST activate to avoid total loss
        // b) Prime DGW for elite captain WITHOUT harsh rotation/difficulty risks
        // c) Outstanding SGW late in half where no better DGW is expected
        val isCaptainRiskAcceptable = chosenCaptain.prediction?.riskLevel != RiskLevel.HIGH && chosenCaptain.prediction?.riskLevel != RiskLevel.CRITICAL
        val isCaptainScoreViable = squadCaptainSelection.isTripleCaptainViable || (isCaptainRiskAcceptable && currentGwGain >= 9.5)

        val hasPrimeConditionsNow = ((isCurrentDgw && !isHarshDgw && currentGwGain >= 10.0) ||
                (isSet1ExpiryApproaching && currentGwGain >= 6.5) ||
                (fdr <= 2 && isHome && currentGwGain >= 9.5 && confirmedFutureDgws.isEmpty() && projectedFutureDgws.isEmpty())) &&
                (isCaptainScoreViable || isSet1ExpiryApproaching)

        val verdict: ChipVerdict
        val recommendedGw: Int
        val confidence: ChipConfidence
        val primaryReason: String
        val detailedReasons = mutableListOf<String>()

        if (squadCaptainSelection.tripleCaptainReasonAr.isNotBlank()) {
            detailedReasons.add(squadCaptainSelection.tripleCaptainReasonAr)
        }

        if (hasPrimeConditionsNow) {
            verdict = ChipVerdict.ACTIVATE_NOW
            recommendedGw = currentGameweek
            confidence = if (isCurrentDgw && !isHarshDgw && currentGwGain >= 11.0) ChipConfidence.VERY_HIGH else ChipConfidence.HIGH
            primaryReason = when {
                isSet1ExpiryApproaching -> {
                    "فرصة أخيرة لتفعيل تريبل كابتن المجموعة الأولى قبل انتهاء صلاحيتها رسمياً بنهاية الجولة ${config.firstHalfDeadlineGw}."
                }
                isCurrentDgw -> {
                    "فرصة ذهبية: جولة مزدوجة مؤكدة (DGW) لنجم نخبوي (${chosenCaptain.webName}) بمباراتين وسقف نقطي مرتفع."
                }
                else -> {
                    "مواجهة استثنائية على أرضه وسهولة جدول (FDR $fdr) مع انعدام خيارات جولات مزدوجة أفضل في هذا النصف."
                }
            }

            detailedReasons.add("العائد الإضافي الصافي من الشريحة: +%.1f نقطة مقارنة بالكابتن العادي.".format(Locale.US, currentGwGain))
            detailedReasons.add("احتمالية البدء: %d%%، مع معدل دقائق متوقع %.0f دقيقة.".format(Locale.US, (startProb * 100).toInt(), expectedMinutes))
            if (isCurrentDgw) {
                detailedReasons.add("اللاعب يخوض مباراتين خلال نفس الجولة، مما يرفع سقف النقاط والبونص.")
            }
        } else {
            verdict = ChipVerdict.SAVE_FOR_LATER
            recommendedGw = confirmedFutureDgws.firstOrNull() ?: projectedFutureDgws.firstOrNull() ?: (currentGameweek + 4).coerceAtMost(halfEndGw)
            confidence = ChipConfidence.HIGH

            primaryReason = when {
                isCurrentBlank -> {
                    "اللاعب لا يملك أي مباراة في هذه الجولة (Blank GW). تفعيل الشريحة الآن هدر تام!"
                }
                isHarshDgw -> {
                    "رغم وجود جولة مزدوجة، إلا أن صعوبة المواجهات ومخاطر المداورة تقلل العائد. لا تفعّل لمجرد وجود DGW!"
                }
                else -> {
                    "احتفظ بالتريبل كابتن لجولة قادمة مواتية (GW $recommendedGw). القيمة المتوقعة مستقبلاً (%.1f نقطة) تتفوق على الجولة الحالية (%.1f نقطة).".format(Locale.US, futureOpportunityGain, currentGwGain)
                }
            }

            detailedReasons.add("العائد المتوقع الآن (+%.1f نقطة) مقابل العائد المستهدف مستقبلاً (+%.1f نقطة).".format(Locale.US, currentGwGain, futureOpportunityGain))
            if (confirmedFutureDgws.isNotEmpty()) {
                detailedReasons.add("جولات مزدوجة مؤكدة رسمياً: ${confirmedFutureDgws.joinToString { "GW$it" }}.")
            } else if (projectedFutureDgws.isNotEmpty()) {
                detailedReasons.add("جولات مزدوجة متوقعة (تخضع لخصم عدم اليقين %.0f%%): ${projectedFutureDgws.joinToString { "GW$it" }}.".format(Locale.US, (1 - config.projectedFixtureConfidenceDiscount) * 100))
            }
            if (fdr >= 4) {
                detailedReasons.add("صعوبة مواجهة الجولة الحالية (FDR $fdr) تزيد من مخاطر تضييع الرقاقة.")
            }
        }

        val captainRisk = chosenCaptain.prediction?.riskLevel ?: RiskLevel.LOW
        val riskLevel = when {
            isHarshDgw || captainRisk == RiskLevel.HIGH -> RiskLevel.HIGH
            captainRisk == RiskLevel.CRITICAL || isCurrentBlank -> RiskLevel.CRITICAL
            startProb < 0.85 || expectedMinutes < 70.0 || averageDgwFdr >= 3.6 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        val riskAssessmentAr = when (riskLevel) {
            RiskLevel.LOW -> "مخاطرة منخفضة: اللاعب أساسي مضمون في لقاءات مواتية جداً."
            RiskLevel.MEDIUM -> "مخاطرة متوسطة: مواجهة متوسطة الصعوبة أو تدوير طفيف محتمل."
            RiskLevel.HIGH -> "مخاطرة مرتفعة: صعوبة جدول أو احتمالية تبديل مبكر ومداورة."
            RiskLevel.CRITICAL -> "مخاطرة حرجة: غياب أو إيقاف أو انعدام تام للمباريات."
        }

        return SingleChipAnalysis(
            chip = ChipType.TRIPLE_CAPTAIN,
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
            targetPlayer = chosenCaptain,
            expirationWarningAr = expirationWarning,
            isConfirmedFixture = isConfirmedNow,
            riskLevel = riskLevel,
            riskAssessmentAr = riskAssessmentAr
        )
    }
}
