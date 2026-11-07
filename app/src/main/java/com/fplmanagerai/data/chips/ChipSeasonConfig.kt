package com.fplmanagerai.data.chips

import com.fplmanagerai.domain.chips.ChipType

/**
 * Configurable official season rules (Configured for 2026/27 FPL Season specifications)
 * Keeps season-specific constraints modular and configurable rather than hardcoded.
 */
data class ChipSeasonConfig(
    val seasonName: String = "2026/27",
    val chipsPerSet: Int = 4,
    val totalSets: Int = 2,
    val firstHalfDeadlineGw: Int = 19,
    val secondHalfStartGw: Int = 20,
    val totalGameweeks: Int = 38,
    val maxChipsPerGameweek: Int = 1,
    val allowCarryOverBetweenHalves: Boolean = false,
    val allowConsecutiveFreeHit: Boolean = false,
    val disallowedChipsInGw1: Set<ChipType> = setOf(ChipType.FREE_HIT, ChipType.WILDCARD),
    val preserveBankedTransfersOnChip: Boolean = true,
    val maxBankedFreeTransfers: Int = 5,
    val minBankedFreeTransfers: Int = 1,
    val projectedFixtureConfidenceDiscount: Double = 0.85
) {
    companion object {
        val DEFAULT_2026_27 = ChipSeasonConfig()
    }
}
