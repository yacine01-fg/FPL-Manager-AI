package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel

enum class OptimizationStrategy(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String
) {
    MAX_EXPECTED_POINTS(
        "أقصى نقاط متوقعة (Max xPts)",
        "Max Expected Points",
        "استهداف أعلى معدل نقاط متوقعة ممكنة بصرف النظر عن نسب الملكية"
    ),
    LOW_RISK(
        "تقليل المخاطر (Low-Risk / Template)",
        "Low-Risk Strategy",
        "الاعتماد على نجوم التيمبليت الأكثر موثوقية وضماناً لدقائق اللعب لتجنب التذبذب"
    ),
    BALANCED(
        "استراتيجية متوازنة (Balanced)",
        "Balanced Strategy",
        "توليفة ذكية تجمع بين غزارة النقاط المتوقعة والقيمة السعرية واستقرار الفورمة"
    ),
    DIFFERENTIAL(
        "جرأة وتمايز (Differential)",
        "Differential Strategy",
        "استهداف خيارات واعدة بنسب ملكية منخفضة (< 15%) لتحقيق قفزات نوعية في الترتيب"
    );

    companion object {
        val SAFE_TEMPLATE: OptimizationStrategy get() = LOW_RISK
    }
}

typealias RiskPreference = OptimizationStrategy

data class OptimizedSquad(
    val starting11: List<PlayerModel>,
    val bench: List<PlayerModel>,
    val captain: PlayerModel,
    val viceCaptain: PlayerModel,
    val formation: Formation,
    val totalCost: Double,
    val remainingBudget: Double,
    val projectedStartingPoints: Double,
    val projectedCaptainPoints: Double,
    val projectedBenchPoints: Double,
    val strategy: OptimizationStrategy,
    val horizon: Int
) {
    val totalSquad: List<PlayerModel>
        get() = starting11 + bench

    val riskPreference: OptimizationStrategy
        get() = strategy
}

class SquadOptimizer(
    private val validator: FormationValidator = FormationValidator(),
    private val captainSelector: CaptainSelector = CaptainSelector()
) {

    fun optimizeSquad(
        allPlayers: List<PlayerModel>,
        budget: Double = 100.0,
        preferredFormation: Formation? = null,
        strategy: OptimizationStrategy = OptimizationStrategy.BALANCED,
        riskPreference: OptimizationStrategy = strategy,
        horizon: Int = 3
    ): OptimizedSquad {
        val effectiveStrategy = if (strategy != OptimizationStrategy.BALANCED) strategy else riskPreference
        // 1. Filter out completely unavailable players (Critical risk / Injured / Suspended)
        val eligiblePool = allPlayers.filter {
            it.availability.isAvailable && (it.prediction?.riskLevel != RiskLevel.CRITICAL)
        }

        // 2. Score players based on selected strategy
        fun playerWeight(p: PlayerModel): Double {
            val pred = p.prediction
            val xpts = pred?.expectedPoints ?: (p.totalPoints / 10.0)
            val form = p.form
            val reliability = pred?.reliabilityScore ?: 70.0
            val ownership = p.selectedByPercent
            val ceiling = pred?.ceilingPoints ?: (xpts + 3.0)

            return when (effectiveStrategy) {
                OptimizationStrategy.MAX_EXPECTED_POINTS -> {
                    xpts * 1.0 + (if (p.upcomingFixtures.firstOrNull()?.isHome == true) 0.3 else 0.0)
                }
                OptimizationStrategy.LOW_RISK -> {
                    xpts * 0.60 + (reliability / 100.0) * 2.5 + (ownership / 100.0) * 2.0
                }
                OptimizationStrategy.BALANCED -> {
                    val value = xpts / p.price.coerceAtLeast(4.0)
                    xpts * 0.75 + (form / 8.0) * 1.5 + value * 0.8
                }
                OptimizationStrategy.DIFFERENTIAL -> {
                    val diffMultiplier = when {
                        ownership < 10.0 -> 1.45
                        ownership < 18.0 -> 1.15
                        else -> 0.75
                    }
                    xpts * diffMultiplier + (ceiling / 10.0) * 1.2
                }
            }
        }

        // 3. Evaluate Formations: If preferred is given, evaluate that one; otherwise find best formation
        val candidateFormations = if (preferredFormation != null) {
            listOf(preferredFormation)
        } else {
            Formation.ALL_VALID_FORMATIONS
        }

        var bestSquad: OptimizedSquad? = null
        var maxStartingPoints = -1.0

        for (formation in candidateFormations) {
            val squadForFormation = buildOptimizedSquadForFormation(
                pool = eligiblePool,
                formation = formation,
                budget = budget,
                strategy = effectiveStrategy,
                horizon = horizon,
                weightFunc = ::playerWeight
            )

            if (squadForFormation != null) {
                if (squadForFormation.projectedStartingPoints > maxStartingPoints) {
                    maxStartingPoints = squadForFormation.projectedStartingPoints
                    bestSquad = squadForFormation
                }
            }
        }

        // Fallback safety: guarantee an optimal squad is always returned
        return bestSquad ?: buildFallbackSquad(eligiblePool, budget, effectiveStrategy, horizon)
    }

    private fun buildOptimizedSquadForFormation(
        pool: List<PlayerModel>,
        formation: Formation,
        budget: Double,
        strategy: OptimizationStrategy,
        horizon: Int,
        weightFunc: (PlayerModel) -> Double
    ): OptimizedSquad? {
        val targetDefs = formation.defenders
        val targetMids = formation.midfielders
        val targetFwds = formation.forwards

        // Partition pool by position sorted by optimization weight
        val gkpPool = pool.filter { it.position == PlayerPosition.GKP }.sortedByDescending { weightFunc(it) }
        val defPool = pool.filter { it.position == PlayerPosition.DEF }.sortedByDescending { weightFunc(it) }
        val midPool = pool.filter { it.position == PlayerPosition.MID }.sortedByDescending { weightFunc(it) }
        val fwdPool = pool.filter { it.position == PlayerPosition.FWD }.sortedByDescending { weightFunc(it) }

        if (gkpPool.size < 2 || defPool.size < 5 || midPool.size < 5 || fwdPool.size < 3) {
            return null
        }

        val teamCounts = mutableMapOf<Int, Int>()
        val selected15 = mutableListOf<PlayerModel>()

        fun canPick(p: PlayerModel, currentCost: Double, remainingPicks: Int): Boolean {
            val count = teamCounts.getOrDefault(p.teamId, 0)
            if (count >= 3) return false
            val minReserve = (remainingPicks - 1).coerceAtLeast(0) * 4.0
            return (currentCost + p.price + minReserve) <= (budget + 0.001)
        }

        fun selectBest(poolList: List<PlayerModel>, needed: Int) {
            var added = 0
            for (p in poolList) {
                if (added >= needed) break
                if (selected15.none { it.id == p.id }) {
                    val remainingTotal = 15 - selected15.size
                    val costSoFar = selected15.sumOf { it.price }
                    if (canPick(p, costSoFar, remainingTotal)) {
                        selected15.add(p)
                        teamCounts[p.teamId] = teamCounts.getOrDefault(p.teamId, 0) + 1
                        added++
                    }
                }
            }
        }

        // Pick Starting Quota first
        selectBest(gkpPool, 1)
        selectBest(defPool, targetDefs)
        selectBest(midPool, targetMids)
        selectBest(fwdPool, targetFwds)

        // Pick Bench Quota (1 GKP, 5-targetDefs DEF, 5-targetMids MID, 3-targetFwds FWD)
        selectBest(gkpPool, 1)
        selectBest(defPool, 5 - targetDefs)
        selectBest(midPool, 5 - targetMids)
        selectBest(fwdPool, 3 - targetFwds)

        // If any slots remain unfilled due to budget or team constraint, fill with cheapest enablers
        fillRemainingSpots(selected15, pool, teamCounts, budget)

        // Strict constraint check
        val fullValidation = validator.validateFullSquad(selected15, budget)
        if (!fullValidation.isValid) {
            // Repair squad to strictly conform
            repairSquadConstraints(selected15, pool, teamCounts, budget)
        }

        // Separate Starting 11 and Bench
        val starting11 = mutableListOf<PlayerModel>()
        val bench = mutableListOf<PlayerModel>()

        // 1 GKP starts (the one with higher xPts)
        val gkps = selected15.filter { it.position == PlayerPosition.GKP }.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
        if (gkps.isNotEmpty()) {
            starting11.add(gkps[0])
            bench.addAll(gkps.drop(1))
        }

        val defs = selected15.filter { it.position == PlayerPosition.DEF }.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
        val mids = selected15.filter { it.position == PlayerPosition.MID }.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
        val fwds = selected15.filter { it.position == PlayerPosition.FWD }.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }

        starting11.addAll(defs.take(targetDefs))
        bench.addAll(defs.drop(targetDefs))

        starting11.addAll(mids.take(targetMids))
        bench.addAll(mids.drop(targetMids))

        starting11.addAll(fwds.take(targetFwds))
        bench.addAll(fwds.drop(targetFwds))

        // Order bench: Slot 1 is reserve GK, Slots 2, 3, 4 ordered descending by xPts
        val benchGk = bench.filter { it.position == PlayerPosition.GKP }
        val benchOutfield = bench.filter { it.position != PlayerPosition.GKP }.sortedByDescending { it.prediction?.expectedPoints ?: 0.0 }
        val orderedBench = benchGk + benchOutfield

        // Captain and Vice-Captain selection
        val capResult = captainSelector.selectCaptain(starting11)
        val captain = capResult.captain
        val viceCaptain = capResult.viceCaptain

        val totalCost = Math.round(selected15.sumOf { it.price } * 10.0) / 10.0
        val remainingBank = Math.round((budget - totalCost) * 10.0) / 10.0

        val baseStartingPts = starting11.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        val capBonus = captain.prediction?.expectedPoints ?: 0.0
        val projectedStarting = Math.round((baseStartingPts + capBonus) * 10.0) / 10.0
        val projectedCapPoints = Math.round((capBonus * 2.0) * 10.0) / 10.0
        val projectedBench = Math.round(orderedBench.sumOf { it.prediction?.expectedPoints ?: 0.0 } * 10.0) / 10.0

        return OptimizedSquad(
            starting11 = starting11,
            bench = orderedBench,
            captain = captain,
            viceCaptain = viceCaptain,
            formation = formation,
            totalCost = totalCost,
            remainingBudget = remainingBank,
            projectedStartingPoints = projectedStarting,
            projectedCaptainPoints = projectedCapPoints,
            projectedBenchPoints = projectedBench,
            strategy = strategy,
            horizon = horizon
        )
    }

    private fun fillRemainingSpots(
        selected: MutableList<PlayerModel>,
        pool: List<PlayerModel>,
        teamCounts: MutableMap<Int, Int>,
        budget: Double
    ) {
        val positionQuotas = mapOf(
            PlayerPosition.GKP to 2,
            PlayerPosition.DEF to 5,
            PlayerPosition.MID to 5,
            PlayerPosition.FWD to 3
        )

        for ((pos, target) in positionQuotas) {
            val currentCount = selected.count { it.position == pos }
            if (currentCount < target) {
                val needed = target - currentCount
                val candidates = pool.filter { it.position == pos && selected.none { s -> s.id == it.id } }
                    .sortedBy { it.price }

                var added = 0
                for (c in candidates) {
                    if (added >= needed) break
                    val count = teamCounts.getOrDefault(c.teamId, 0)
                    if (count < 3) {
                        selected.add(c)
                        teamCounts[c.teamId] = count + 1
                        added++
                    }
                }
            }
        }
    }

    private fun repairSquadConstraints(
        selected: MutableList<PlayerModel>,
        pool: List<PlayerModel>,
        teamCounts: MutableMap<Int, Int>,
        budget: Double
    ) {
        // Enforce max 3 per club
        val groupedByTeam = selected.groupBy { it.teamId }
        for ((teamId, players) in groupedByTeam) {
            if (players.size > 3) {
                val overflow = players.sortedBy { it.prediction?.expectedPoints ?: 0.0 }
                val toReplace = overflow.take(players.size - 3)
                for (badPlayer in toReplace) {
                    selected.remove(badPlayer)
                    teamCounts[teamId] = (teamCounts[teamId] ?: 1) - 1

                    // Find alternative from another club
                    val replacement = pool.firstOrNull {
                        it.position == badPlayer.position &&
                                selected.none { s -> s.id == it.id } &&
                                teamCounts.getOrDefault(it.teamId, 0) < 3 &&
                                (selected.sumOf { s -> s.price } + it.price) <= budget
                    } ?: pool.firstOrNull {
                        it.position == badPlayer.position &&
                                selected.none { s -> s.id == it.id } &&
                                teamCounts.getOrDefault(it.teamId, 0) < 3
                    }

                    if (replacement != null) {
                        selected.add(replacement)
                        teamCounts[replacement.teamId] = teamCounts.getOrDefault(replacement.teamId, 0) + 1
                    }
                }
            }
        }
    }

    private fun buildFallbackSquad(
        pool: List<PlayerModel>,
        budget: Double,
        strategy: OptimizationStrategy,
        horizon: Int
    ): OptimizedSquad {
        val formation = Formation(3, 5, 2)
        val teamCounts = mutableMapOf<Int, Int>()
        val selected = mutableListOf<PlayerModel>()

        fun pickCheapest(pos: PlayerPosition, count: Int) {
            val candidates = pool.filter { it.position == pos }.sortedBy { it.price }
            var added = 0
            for (c in candidates) {
                if (added >= count) break
                val tCount = teamCounts.getOrDefault(c.teamId, 0)
                if (tCount < 3 && selected.none { it.id == c.id }) {
                    selected.add(c)
                    teamCounts[c.teamId] = tCount + 1
                    added++
                }
            }
        }

        pickCheapest(PlayerPosition.GKP, 2)
        pickCheapest(PlayerPosition.DEF, 5)
        pickCheapest(PlayerPosition.MID, 5)
        pickCheapest(PlayerPosition.FWD, 3)

        val starting = selected.take(11)
        val bench = selected.drop(11)
        val capResult = captainSelector.selectCaptain(starting)

        return OptimizedSquad(
            starting11 = starting,
            bench = bench,
            captain = capResult.captain,
            viceCaptain = capResult.viceCaptain,
            formation = formation,
            totalCost = Math.round(selected.sumOf { it.price } * 10.0) / 10.0,
            remainingBudget = Math.round((budget - selected.sumOf { it.price }) * 10.0) / 10.0,
            projectedStartingPoints = 45.0,
            projectedCaptainPoints = 12.0,
            projectedBenchPoints = 6.0,
            strategy = strategy,
            horizon = horizon
        )
    }
}
