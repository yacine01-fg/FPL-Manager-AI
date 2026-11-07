package com.fplmanagerai.data.chips

import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.domain.chips.ChipConfidence
import com.fplmanagerai.domain.chips.ChipHalf
import com.fplmanagerai.domain.chips.ChipType
import com.fplmanagerai.domain.chips.ChipVerdict
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Immutable snapshot of a chip recommendation saved before gameweek deadline
 */
data class ChipRecommendationSnapshot(
    val snapshotId: String = UUID.randomUUID().toString(),
    val chip: ChipType,
    val half: ChipHalf,
    val gameweek: Int,
    val predictedIncrementalGain: Double,
    val predictedFloorPoints: Double = 0.0,
    val predictedCeilingPoints: Double = 0.0,
    val confidence: ChipConfidence,
    val targetPlayerId: Int? = null,
    val targetPlayerName: String? = null,
    val recommendedAction: ChipVerdict,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Post-Gameweek actual performance record comparing prediction with actual result
 */
data class ActualChipPerformance(
    val snapshotId: String,
    val chip: ChipType,
    val half: ChipHalf,
    val gameweek: Int,
    val actualPointsScored: Double,
    val baselinePoints: Double,
    val actualIncrementalBenefit: Double,
    val predictionError: Double, // predicted - actual
    val absoluteError: Double,   // |predicted - actual|
    val isMeasurable: Boolean = true,
    val notesAr: String = "",
    val recordedTimestampMillis: Long = System.currentTimeMillis()
)

/**
 * Historical Chip Repository responsible for persisting pre-deadline snapshots,
 * post-deadline actuals, and error tracking without ever modifying original predictions.
 */
class HistoricalChipRepository(
    private val cache: DataCacheStore = LocalDataCache.defaultCache,
    private val gson: Gson = Gson()
) {

    companion object {
        const val KEY_SNAPSHOTS = "chip_historical_snapshots"
        const val KEY_PERFORMANCES = "chip_actual_performances"
    }

    private val snapshotsLock = Any()
    private val performancesLock = Any()

    /**
     * Stores a recommendation snapshot prior to gameweek deadline
     */
    fun saveRecommendationSnapshot(snapshot: ChipRecommendationSnapshot) {
        synchronized(snapshotsLock) {
            val existing = getAllSnapshots().toMutableList()
            // Do not overwrite an existing snapshot for the same chip & gameweek; preserve immutable history
            val existingIdx = existing.indexOfFirst {
                it.chip == snapshot.chip && it.half == snapshot.half && it.gameweek == snapshot.gameweek
            }
            if (existingIdx >= 0) {
                // If it already exists, we do NOT alter past prediction, but we can register if identical ID
                return
            }
            existing.add(snapshot)
            cache.save(KEY_SNAPSHOTS, gson.toJson(existing))
        }
    }

    /**
     * Retrieves all recorded snapshots
     */
    fun getAllSnapshots(): List<ChipRecommendationSnapshot> {
        synchronized(snapshotsLock) {
            val raw = cache.read(KEY_SNAPSHOTS) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<ChipRecommendationSnapshot>>() {}.type
                gson.fromJson<List<ChipRecommendationSnapshot>>(raw, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Finds a snapshot for a specific chip and gameweek
     */
    fun getSnapshot(chip: ChipType, half: ChipHalf, gameweek: Int): ChipRecommendationSnapshot? {
        return getAllSnapshots().firstOrNull {
            it.chip == chip && it.half == half && it.gameweek == gameweek
        }
    }

    /**
     * Records actual performance observed after gameweek completion
     */
    fun recordActualPerformance(performance: ActualChipPerformance) {
        synchronized(performancesLock) {
            val existing = getAllPerformances().toMutableList()
            val existingIdx = existing.indexOfFirst { it.snapshotId == performance.snapshotId }
            if (existingIdx >= 0) {
                existing[existingIdx] = performance
            } else {
                existing.add(performance)
            }
            cache.save(KEY_PERFORMANCES, gson.toJson(existing))
        }
    }

    /**
     * Evaluates and records actual performance for an existing snapshot given measured points
     */
    fun recordActualResult(
        snapshotId: String,
        actualPointsScored: Double,
        baselinePoints: Double,
        notesAr: String = ""
    ): ActualChipPerformance? {
        val snapshot = getAllSnapshots().firstOrNull { it.snapshotId == snapshotId } ?: return null
        val actualBenefit = actualPointsScored - baselinePoints
        val error = snapshot.predictedIncrementalGain - actualBenefit
        val absError = Math.abs(error)

        val perf = ActualChipPerformance(
            snapshotId = snapshot.snapshotId,
            chip = snapshot.chip,
            half = snapshot.half,
            gameweek = snapshot.gameweek,
            actualPointsScored = actualPointsScored,
            baselinePoints = baselinePoints,
            actualIncrementalBenefit = Math.round(actualBenefit * 10.0) / 10.0,
            predictionError = Math.round(error * 10.0) / 10.0,
            absoluteError = Math.round(absError * 10.0) / 10.0,
            isMeasurable = true,
            notesAr = notesAr
        )

        recordActualPerformance(perf)
        return perf
    }

    /**
     * Retrieves all recorded performance outcomes
     */
    fun getAllPerformances(): List<ActualChipPerformance> {
        synchronized(performancesLock) {
            val raw = cache.read(KEY_PERFORMANCES) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<ActualChipPerformance>>() {}.type
                gson.fromJson<List<ActualChipPerformance>>(raw, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearAllHistory() {
        synchronized(snapshotsLock) {
            cache.clear(KEY_SNAPSHOTS)
        }
        synchronized(performancesLock) {
            cache.clear(KEY_PERFORMANCES)
        }
    }
}
