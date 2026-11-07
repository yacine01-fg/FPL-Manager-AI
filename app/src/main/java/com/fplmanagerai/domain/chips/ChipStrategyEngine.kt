package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipRecommendationSnapshot
import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.chips.HistoricalChipRepository
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.PlayerModel
import java.util.Locale

class ChipStrategyEngine(
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27,
    private val rulesEngine: ChipRulesEngine = ChipRulesEngine(config),
    private val historicalRepository: HistoricalChipRepository = HistoricalChipRepository(),
    private val calibrationEngine: ChipCalibrationEngine = ChipCalibrationEngine(historicalRepository),
    private val tripleCaptainAnalyzer: TripleCaptainAnalyzer = TripleCaptainAnalyzer(config = config),
    private val benchBoostAnalyzer: BenchBoostAnalyzer = BenchBoostAnalyzer(config = config),
    private val freeHitAnalyzer: FreeHitAnalyzer = FreeHitAnalyzer(config = config),
    private val wildcardAnalyzer: WildcardAnalyzer = WildcardAnalyzer(config = config)
) {

    /**
     * Executes complete chip portfolio analysis, arbitrating conflicts, applying historical calibration,
     * enforcing official FPL constraints, and persisting pre-deadline recommendation snapshots.
     */
    fun evaluateChips(
        squad: List<PlayerModel>,
        allPlayers: List<PlayerModel>,
        fixtures: List<FixtureModel>,
        currentGameweek: Int,
        userHistory: UserChipHistory = UserChipHistory(),
        freeTransfers: Int = 1,
        budget: Double = 100.0,
        recordSnapshots: Boolean = true
    ): ChipPortfolioPlan {
        val currentHalf = ChipHalf.fromGameweek(currentGameweek)

        // 1. Run individual analyzers for the active half
        val tcAnalysisRaw = tripleCaptainAnalyzer.analyze(
            squad = squad,
            allPlayers = allPlayers,
            fixtures = fixtures,
            currentGameweek = currentGameweek,
            userHistory = userHistory,
            half = currentHalf
        )

        val bbAnalysisRaw = benchBoostAnalyzer.analyze(
            squad = squad,
            fixtures = fixtures,
            currentGameweek = currentGameweek,
            userHistory = userHistory,
            half = currentHalf
        )

        val fhAnalysisRaw = freeHitAnalyzer.analyze(
            squad = squad,
            allPlayers = allPlayers,
            fixtures = fixtures,
            currentGameweek = currentGameweek,
            userHistory = userHistory,
            budget = budget,
            half = currentHalf
        )

        val wcAnalysisRaw = wildcardAnalyzer.analyze(
            squad = squad,
            allPlayers = allPlayers,
            fixtures = fixtures,
            currentGameweek = currentGameweek,
            userHistory = userHistory,
            freeTransfersAvailable = freeTransfers,
            budget = budget,
            half = currentHalf
        )

        // 2. Apply Calibration from Historical Learning Engine
        fun calibrate(analysis: SingleChipAnalysis): SingleChipAnalysis {
            val calibratedGain = calibrationEngine.calibrateExpectedGain(analysis.expectedPointsGain, analysis.chip)
            return analysis.copy(expectedPointsGain = calibratedGain)
        }

        val rawAnalyses = mutableMapOf(
            ChipType.TRIPLE_CAPTAIN to calibrate(tcAnalysisRaw),
            ChipType.BENCH_BOOST to calibrate(bbAnalysisRaw),
            ChipType.FREE_HIT to calibrate(fhAnalysisRaw),
            ChipType.WILDCARD to calibrate(wcAnalysisRaw)
        )

        // 3. Official Rule Arbitration: Strictly AT MOST ONE chip can be activated in a single Gameweek!
        var winningChip: ChipType? = null
        val candidatesForNow = rawAnalyses.filter { it.value.verdict == ChipVerdict.ACTIVATE_NOW }.keys.toList()

        if (userHistory.activeChipThisGw != null) {
            // User already has an active chip confirmed for this GW
            winningChip = userHistory.activeChipThisGw
            for ((type, analysis) in rawAnalyses) {
                if (type == winningChip) {
                    rawAnalyses[type] = analysis.copy(
                        status = ChipStatus.ACTIVE_THIS_GW,
                        verdict = ChipVerdict.ACTIVATE_NOW
                    )
                } else if (analysis.verdict == ChipVerdict.ACTIVATE_NOW) {
                    val fallbackGw = (analysis.alternativeGameweeks.firstOrNull() ?: (currentGameweek + 3)).coerceAtMost(if (currentHalf == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks)
                    rawAnalyses[type] = analysis.copy(
                        verdict = ChipVerdict.SAVE_FOR_LATER,
                        recommendedGameweek = fallbackGw,
                        arbitrationNoteAr = "تم تأجيل هذه الرقاقة نظراً لأنك قمت بتفعيل ${winningChip.titleAr} بالفعل في هذه الجولة."
                    )
                }
            }
        } else if (userHistory.pendingChip != null && userHistory.pendingChipGameweek == currentGameweek) {
            // User has a pending chip selected before deadline
            winningChip = userHistory.pendingChip.chipType
            for ((type, analysis) in rawAnalyses) {
                if (type == winningChip) {
                    rawAnalyses[type] = analysis.copy(
                        status = ChipStatus.PENDING,
                        verdict = ChipVerdict.ACTIVATE_NOW
                    )
                } else if (analysis.verdict == ChipVerdict.ACTIVATE_NOW) {
                    val fallbackGw = (analysis.alternativeGameweeks.firstOrNull() ?: (currentGameweek + 3)).coerceAtMost(if (currentHalf == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks)
                    rawAnalyses[type] = analysis.copy(
                        verdict = ChipVerdict.SAVE_FOR_LATER,
                        recommendedGameweek = fallbackGw,
                        arbitrationNoteAr = "تم تأجيل هذه الرقاقة لوجود رقاقة معلّقة بالفعل (${winningChip.titleAr}) لهذه الجولة."
                    )
                }
            }
        } else if (candidatesForNow.size == 1) {
            winningChip = candidatesForNow.first()
        } else if (candidatesForNow.size > 1) {
            // Conflict resolution: Multiple chips want to activate in the same gameweek!
            // Priority:
            // 1. Expiring Set 1 chips in GW18-19 (Deadline urgency)
            // 2. Critical Blank GW Free Hit (Massive squad crisis)
            // 3. Highest expected points gain
            val sortedCandidates = candidatesForNow.sortedWith(
                compareByDescending<ChipType> { chip ->
                    when (chip) {
                        ChipType.WILDCARD -> if (currentHalf == ChipHalf.FIRST_HALF && currentGameweek in 18..19) 1000.0 else rawAnalyses[chip]!!.expectedPointsGain
                        ChipType.FREE_HIT -> if (rawAnalyses[chip]!!.affectedPlayers.size >= 5) 500.0 else rawAnalyses[chip]!!.expectedPointsGain
                        else -> rawAnalyses[chip]!!.expectedPointsGain
                    }
                }
            )

            winningChip = sortedCandidates.first()

            // Keep winner as ACTIVATE_NOW, demote all others to SAVE_FOR_LATER with clear arbitration notes
            val halfEndGw = if (currentHalf == ChipHalf.FIRST_HALF) config.firstHalfDeadlineGw else config.totalGameweeks
            for (demotedChip in sortedCandidates.drop(1)) {
                val demotedAnalysis = rawAnalyses[demotedChip]!!
                val fallbackGw = (demotedAnalysis.alternativeGameweeks.firstOrNull() ?: (currentGameweek + 4)).coerceAtMost(halfEndGw)
                val note = "قاعدة رسمية: يُمنع تفعيل أكثر من رقاقة في نفس الجولة. تم تفضيل ${winningChip.titleAr} كأولوية قصوى وتأجيل هذه الرقاقة للجولة $fallbackGw."

                rawAnalyses[demotedChip] = demotedAnalysis.copy(
                    verdict = ChipVerdict.SAVE_FOR_LATER,
                    recommendedGameweek = fallbackGw,
                    arbitrationNoteAr = note,
                    detailedPointsAr = listOf(note) + demotedAnalysis.detailedPointsAr
                )
            }
        }

        // 4. Also analyze Second Half (Set 2) chips if currently in Set 1 (for seasonal roadmap awareness)
        val allAnalysesList = mutableListOf<SingleChipAnalysis>()
        allAnalysesList.addAll(rawAnalyses.values)

        if (currentHalf == ChipHalf.FIRST_HALF) {
            // Add Set 2 evaluations for complete season view
            val set2Tc = tripleCaptainAnalyzer.analyze(squad, allPlayers, fixtures, config.secondHalfStartGw, userHistory, ChipHalf.SECOND_HALF)
            val set2Bb = benchBoostAnalyzer.analyze(squad, fixtures, config.secondHalfStartGw, userHistory, half = ChipHalf.SECOND_HALF)
            val set2Fh = freeHitAnalyzer.analyze(squad, allPlayers, fixtures, config.secondHalfStartGw, userHistory, budget, half = ChipHalf.SECOND_HALF)
            val set2Wc = wildcardAnalyzer.analyze(squad, allPlayers, fixtures, config.secondHalfStartGw, userHistory, freeTransfers, budget, half = ChipHalf.SECOND_HALF)
            allAnalysesList.addAll(listOf(set2Tc, set2Bb, set2Fh, set2Wc))
        }

        // 5. Store Recommendation Snapshots for Historical Learning
        if (recordSnapshots) {
            for ((type, analysis) in rawAnalyses) {
                val snapshot = ChipRecommendationSnapshot(
                    snapshotId = "rec_gw${currentGameweek}_${type.code}_set${currentHalf.setNumber}",
                    chip = type,
                    half = currentHalf,
                    gameweek = currentGameweek,
                    predictedIncrementalGain = analysis.expectedPointsGain,
                    confidence = analysis.confidence,
                    targetPlayerId = analysis.targetPlayer?.id,
                    targetPlayerName = analysis.targetPlayer?.webName,
                    recommendedAction = analysis.verdict
                )
                historicalRepository.saveRecommendationSnapshot(snapshot)
            }
        }

        // 6. Formulate Season Schedule Roadmap
        val schedule = mutableListOf<Pair<Int, ChipType>>()
        for ((type, analysis) in rawAnalyses) {
            if (analysis.status == ChipStatus.AVAILABLE) {
                schedule.add(analysis.recommendedGameweek to type)
            }
        }
        val sortedSchedule = schedule.sortedBy { it.first }

        // 7. Calculate preserved banked free transfers
        val preservedTransfers = if (winningChip != null) {
            rulesEngine.calculateBankedTransfersAfterChip(winningChip, userHistory.bankedFreeTransfers)
        } else {
            userHistory.bankedFreeTransfers
        }

        // 8. Formulate Comprehensive Arabic Strategic Summary
        val summaryAr = when {
            winningChip != null -> {
                val winningAnalysis = rawAnalyses[winningChip]!!
                "توصية الجولة $currentGameweek: يُنصح بتفعيل ${winningChip.titleAr} (${currentHalf.titleAr}) بفارق نقطي متوقع (+%.1f نقطة). ${winningAnalysis.primaryReasonAr}".format(Locale.US, winningAnalysis.expectedPointsGain)
            }
            rawAnalyses.values.all { it.status == ChipStatus.USED || it.status == ChipStatus.EXPIRED } -> {
                "تم استهلاك كافة رقاقات المجموعة الحالية (${currentHalf.titleAr}). ركّز على التبديلات المجانية والتخطيط الأسبوعي."
            }
            else -> {
                val nextScheduled = sortedSchedule.firstOrNull()
                if (nextScheduled != null) {
                    val chipName = nextScheduled.second.titleAr
                    val targetGw = nextScheduled.first
                    "خطة الرقاقات (${currentHalf.titleAr}): التشكيلة مستقرة ولا تتطلب تفعيل أي رقاقة الآن. أقرب فرصة موصى بها هي تفعيل $chipName في الجولة GW $targetGw."
                } else {
                    "التشكيلة في وضع مثالي. احتفظ بالرقاقات للجولات المزدوجة والفارغة في روزنامة الموسم."
                }
            }
        }

        return ChipPortfolioPlan(
            currentGameweek = currentGameweek,
            currentHalf = currentHalf,
            activeChipThisGameweek = winningChip,
            pendingChipThisGameweek = if (userHistory.pendingChipGameweek == currentGameweek) userHistory.pendingChip?.chipType else null,
            chipAnalyses = rawAnalyses,
            allChipAnalyses = allAnalysesList,
            overallStrategySummaryAr = summaryAr,
            recommendedSeasonSchedule = sortedSchedule,
            userChipHistory = userHistory,
            preservedBankedTransfers = preservedTransfers
        )
    }
}
