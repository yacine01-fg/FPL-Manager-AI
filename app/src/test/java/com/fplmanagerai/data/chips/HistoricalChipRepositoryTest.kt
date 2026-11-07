package com.fplmanagerai.data.chips

import com.fplmanagerai.data.cache.InMemoryDataCache
import com.fplmanagerai.domain.chips.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HistoricalChipRepositoryTest {

    private lateinit var repository: HistoricalChipRepository
    private lateinit var calibrationEngine: ChipCalibrationEngine

    @Before
    fun setUp() {
        val memoryCache = InMemoryDataCache()
        repository = HistoricalChipRepository(cache = memoryCache)
        calibrationEngine = ChipCalibrationEngine(historicalRepository = repository)
    }

    @Test
    fun testSaveSnapshot_preservesImmutablePredictionRecord() {
        val snapshot1 = ChipRecommendationSnapshot(
            snapshotId = "snap_gw12_tc",
            chip = ChipType.TRIPLE_CAPTAIN,
            half = ChipHalf.FIRST_HALF,
            gameweek = 12,
            predictedIncrementalGain = 14.5,
            confidence = ChipConfidence.HIGH,
            targetPlayerId = 13,
            targetPlayerName = "Haaland",
            recommendedAction = ChipVerdict.ACTIVATE_NOW
        )

        repository.saveRecommendationSnapshot(snapshot1)

        val retrieved = repository.getSnapshot(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 12)
        assertNotNull(retrieved)
        assertEquals(14.5, retrieved!!.predictedIncrementalGain, 0.01)

        // Attempting to save duplicate for same chip & GW must NOT mutate original historical prediction
        val modifiedSnapshot = snapshot1.copy(predictedIncrementalGain = 20.0)
        repository.saveRecommendationSnapshot(modifiedSnapshot)

        val retrievedAfter = repository.getSnapshot(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 12)
        assertEquals("Historical record must remain immutable", 14.5, retrievedAfter!!.predictedIncrementalGain, 0.01)
    }

    @Test
    fun testRecordActualResult_computesPredictionErrorAccurately() {
        val snapshot = ChipRecommendationSnapshot(
            snapshotId = "snap_gw15_bb",
            chip = ChipType.BENCH_BOOST,
            half = ChipHalf.FIRST_HALF,
            gameweek = 15,
            predictedIncrementalGain = 20.0,
            confidence = ChipConfidence.VERY_HIGH,
            recommendedAction = ChipVerdict.ACTIVATE_NOW
        )
        repository.saveRecommendationSnapshot(snapshot)

        // Actual bench scored 17 points, baseline 0
        val perf = repository.recordActualResult(
            snapshotId = "snap_gw15_bb",
            actualPointsScored = 17.0,
            baselinePoints = 0.0,
            notesAr = "4 bench players played"
        )

        assertNotNull(perf)
        assertEquals(17.0, perf!!.actualIncrementalBenefit, 0.01)
        assertEquals(3.0, perf.predictionError, 0.01) // 20 - 17 = 3.0
        assertEquals(3.0, perf.absoluteError, 0.01)

        val allPerfs = repository.getAllPerformances()
        assertEquals(1, allPerfs.size)
    }

    @Test
    fun testCalibrationEngine_adjustsFutureProjectionsWithoutModifyingHistory() {
        // Record 2 historical performances where model slightly overpredicted:
        // Snapshot 1: Predicted 20.0, Actual 16.0
        val snap1 = ChipRecommendationSnapshot(
            snapshotId = "s1",
            chip = ChipType.TRIPLE_CAPTAIN,
            half = ChipHalf.FIRST_HALF,
            gameweek = 8,
            predictedIncrementalGain = 20.0,
            confidence = ChipConfidence.HIGH,
            recommendedAction = ChipVerdict.ACTIVATE_NOW
        )
        repository.saveRecommendationSnapshot(snap1)
        repository.recordActualResult("s1", actualPointsScored = 16.0, baselinePoints = 0.0)

        // Snapshot 2: Predicted 10.0, Actual 8.0
        val snap2 = ChipRecommendationSnapshot(
            snapshotId = "s2",
            chip = ChipType.BENCH_BOOST,
            half = ChipHalf.FIRST_HALF,
            gameweek = 10,
            predictedIncrementalGain = 10.0,
            confidence = ChipConfidence.HIGH,
            recommendedAction = ChipVerdict.ACTIVATE_NOW
        )
        repository.saveRecommendationSnapshot(snap2)
        repository.recordActualResult("s2", actualPointsScored = 8.0, baselinePoints = 0.0)

        val metrics = calibrationEngine.computeMetrics()
        assertEquals(2, metrics.totalEvaluatedChips)
        // sum actual = 24.0, sum predicted = 30.0 -> multiplier = 24 / 30 = 0.80
        assertEquals(0.80, metrics.calibrationMultiplier, 0.05)
        assertTrue(metrics.predictionBias > 0) // Overpredicting

        // Verify that a future raw prediction of 15.0 is calibrated down to ~12.0
        val calibrated = calibrationEngine.calibrateExpectedGain(15.0, ChipType.TRIPLE_CAPTAIN)
        assertEquals(12.0, calibrated, 0.5)

        // Historical snapshots must remain completely untouched
        assertEquals(20.0, repository.getSnapshot(ChipType.TRIPLE_CAPTAIN, ChipHalf.FIRST_HALF, 8)!!.predictedIncrementalGain, 0.01)
        assertEquals(10.0, repository.getSnapshot(ChipType.BENCH_BOOST, ChipHalf.FIRST_HALF, 10)!!.predictedIncrementalGain, 0.01)
    }
}
