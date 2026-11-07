package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChipRulesEngineTest {

    private lateinit var rulesEngine: ChipRulesEngine

    @Before
    fun setUp() {
        rulesEngine = ChipRulesEngine(ChipSeasonConfig.DEFAULT_2026_27)
    }

    @Test
    fun testGameweek1_blocksFreeHitAndWildcard() {
        val history = UserChipHistory()

        val fhResult = rulesEngine.validateChipActivation(ChipType.FREE_HIT, ChipHalf.FIRST_HALF, 1, history)
        assertFalse("Free Hit must be blocked in GW1", fhResult.isValid)
        assertTrue("Violation should mention GW1", fhResult.ruleViolationAr!!.contains("الجولة الأولى"))

        val wcResult = rulesEngine.validateChipActivation(ChipType.WILDCARD, ChipHalf.FIRST_HALF, 1, history)
        assertFalse("Wildcard must be blocked in GW1", wcResult.isValid)

        val tcResult = rulesEngine.validateChipActivation(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 1, history)
        assertTrue("Triple Captain is allowed in GW1", tcResult.isValid)
    }

    @Test
    fun testFirstHalfExpiration_blocksSet1ChipsInGameweek20() {
        val history = UserChipHistory()

        // Set 1 chips are valid up to GW19
        val gw19Result = rulesEngine.validateChipActivation(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 19, history)
        assertTrue("Set 1 chip is valid in GW19", gw19Result.isValid)
        assertNotNull("Should contain warning about impending expiration", gw19Result.warningAr)

        // Set 1 chips expire in GW20
        val gw20Result = rulesEngine.validateChipActivation(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 20, history)
        assertFalse("Set 1 chip must expire after GW19", gw20Result.isValid)
        assertTrue("Violation should mention Set 1 expiration", gw20Result.ruleViolationAr!!.contains("المجموعة الأولى"))
    }

    @Test
    fun testSecondHalfAvailability_blocksSet2ChipsBeforeGameweek20() {
        val history = UserChipHistory()

        // Set 2 chips cannot be activated before GW20
        val gw19Result = rulesEngine.validateChipActivation(ChipType.TRIPLE_CAPTAIN, ChipHalf.SECOND_HALF, 19, history)
        assertFalse("Set 2 chips must be locked before GW20", gw19Result.isValid)
        assertTrue("Violation should mention Set 2 availability", gw19Result.ruleViolationAr!!.contains("المجموعة الثانية"))

        // Set 2 chips become available in GW20
        val gw20Result = rulesEngine.validateChipActivation(ChipType.TRIPLE_CAPTAIN, ChipHalf.SECOND_HALF, 20, history)
        assertTrue("Set 2 chips are available from GW20", gw20Result.isValid)
    }

    @Test
    fun testNoCarryOverBetweenHalves_set1UnusedChipsDoNotCarryOver() {
        val history = UserChipHistory()

        // In GW25, Set 1 Bench Boost has expired and cannot be carried over
        val state = history.getChipState(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 25)
        assertEquals("Set 1 chip in GW25 must be EXPIRED", ChipStatus.EXPIRED, state)

        val result = rulesEngine.validateChipActivation(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 25, history)
        assertFalse("Cannot activate expired Set 1 chip in second half", result.isValid)
    }

    @Test
    fun testMaxOneChipPerGameweek_blocksSecondChipActivation() {
        // User already has Triple Captain active in GW12
        val history = UserChipHistory(activeChipThisGw = ChipType.TRIPLE_CAPTAIN)

        val bbResult = rulesEngine.validateChipActivation(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12, history)
        assertFalse("Cannot activate second chip in same gameweek", bbResult.isValid)
        assertTrue("Violation should cite single chip rule", bbResult.ruleViolationAr!!.contains("أكثر من رقاقة واحدة"))
    }

    @Test
    fun testFreeHitConsecutiveGameweekRestriction_blocksConsecutiveUse() {
        // Free hit was played in GW14
        val history = UserChipHistory(lastUsedFreeHitGw = 14)

        // Cannot use in GW15 (consecutive)
        val gw15Result = rulesEngine.validateChipActivation(ChipType.FREE_HIT, ChipHalf.FIRST_HALF, 15, history)
        assertFalse("Cannot play Free Hit in consecutive gameweeks", gw15Result.isValid)
        assertTrue("Violation must mention consecutive restriction", gw15Result.ruleViolationAr!!.contains("متتاليتين"))

        // Allowed in GW16 (non-consecutive)
        val gw16Result = rulesEngine.validateChipActivation(ChipType.FREE_HIT, ChipHalf.FIRST_HALF, 16, history)
        assertTrue("Free Hit is allowed in non-consecutive gameweek", gw16Result.isValid)
    }

    @Test
    fun testPreservesBankedFreeTransfers() {
        // Manager has 3 banked free transfers
        val bankedBefore = 3
        val bankedAfterWc = rulesEngine.calculateBankedTransfersAfterChip(ChipType.WILDCARD, bankedBefore)
        assertEquals("Banked transfers should be preserved after Wildcard", 3, bankedAfterWc)

        val bankedAfterFh = rulesEngine.calculateBankedTransfersAfterChip(ChipType.FREE_HIT, bankedBefore)
        assertEquals("Banked transfers should be preserved after Free Hit", 3, bankedAfterFh)
    }

    @Test
    fun testChipLifecycleTransitions_availableToPendingToActivatedToUsed() {
        var history = UserChipHistory()

        // 1. Initially Available
        assertEquals(ChipStatus.AVAILABLE, history.getChipState(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12))

        // 2. Set Pending
        val (pendingHistory, pendingResult) = rulesEngine.setPending(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12, history)
        assertTrue(pendingResult.isValid)
        assertEquals(ChipStatus.PENDING, pendingHistory.getChipState(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12))
        assertEquals(ChipInstance(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF), pendingHistory.pendingChip)

        // 3. Confirm Activation
        val (activeHistory, confirmResult) = rulesEngine.confirmActivation(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12, pendingHistory)
        assertTrue(confirmResult.isValid)
        assertEquals(ChipStatus.ACTIVE_THIS_GW, activeHistory.getChipState(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 12))
        assertNull("Pending should be cleared on activation", activeHistory.pendingChip)

        // 4. In subsequent gameweek (GW13), it is USED
        assertEquals(ChipStatus.USED, activeHistory.getChipState(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 13))
    }
}
