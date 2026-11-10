package com.fplmanagerai.presentation.accuracy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.*
import com.fplmanagerai.data.repository.PredictionAccuracyRepository
import com.fplmanagerai.domain.accuracy.PredictionAccuracyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccuracyScreenTab(val titleAr: String) {
    OVERVIEW("نظرة عامة"),
    CAPTAINCY("دقة الكابتن"),
    POSITIONS("المراكز والخطوط"),
    DETAILS("سجل اللاعبين"),
    CALIBRATION("التحسين الذاتي")
}

enum class AccuracySortOption(val titleAr: String) {
    HIGHEST_ERROR("الأعلى خطأ"),
    LOWEST_ERROR("الأكثر دقة"),
    POINTS_DESC("النقاط الفعلية"),
    EXPECTED_DESC("النقاط المتوقعة")
}

data class PredictionAccuracyUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val availableGameweeks: List<Int> = emptyList(),
    val selectedGameweek: Int? = null, // null = جميع الجولات (All Gameweeks)
    val metrics: PredictionAccuracyMetrics? = null,
    val comparisonRecords: List<AccuracyComparisonRecord> = emptyList(),
    val filteredRecords: List<AccuracyComparisonRecord> = emptyList(),
    val selectedPositionFilter: PlayerPosition? = null,
    val selectedSortOption: AccuracySortOption = AccuracySortOption.HIGHEST_ERROR,
    val activeTab: AccuracyScreenTab = AccuracyScreenTab.OVERVIEW,
    val isSyncingActuals: Boolean = false,
    val totalSnapshotsCount: Int = 0,
    val totalEvaluatedCount: Int = 0,
    val totalPendingCount: Int = 0
)

class PredictionAccuracyViewModel(
    private val repository: PredictionAccuracyRepository = PredictionAccuracyRepository(),
    private val engine: PredictionAccuracyEngine = PredictionAccuracyEngine()
) : ViewModel() {

    private val _uiState = MutableStateFlow(PredictionAccuracyUiState())
    val uiState: StateFlow<PredictionAccuracyUiState> = _uiState.asStateFlow()

    init {
        loadAccuracyData()
    }

    /**
     * تحميل اللقطات والنتائج الفعلية واحتساب المقاييس
     */
    fun loadAccuracyData(selectedGw: Int? = _uiState.value.selectedGameweek) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                var snapshots = repository.getAllSnapshots()

                // إذا لم توجد لقطات مسجلة بعد، نولّد لقطات واقعية مع نتائج فعلية للجولات السابقة لتمكين الفحص المباشر
                if (snapshots.isEmpty()) {
                    seedDemonstrationSnapshots()
                    snapshots = repository.getAllSnapshots()
                }

                val availableGws = snapshots.map { it.gameweek }.distinct().sorted()
                val targetGw = if (selectedGw != null && selectedGw in availableGws) selectedGw else null

                // جمع النتائج الفعلية من الكاش أو الملفات المحلية للجولات المتاحة
                val actualMap = mutableMapOf<Pair<Int, Int>, ActualGameweekPlayerResult>()
                for (gw in availableGws) {
                    val actualRes = repository.loadCachedActualResults(gw)
                    if (actualRes.isSuccess) {
                        actualRes.getOrNull()?.forEach { actual ->
                            actualMap[actual.playerId to actual.gameweek] = actual
                        }
                    }
                }

                // تصفية اللقطات بحسب الجولة المختارة إن وُجدت
                val filteredSnapshots = if (targetGw != null) {
                    snapshots.filter { it.gameweek == targetGw }
                } else {
                    snapshots
                }

                val metrics = engine.evaluateAccuracy(
                    snapshots = filteredSnapshots,
                    actualResultsMap = actualMap
                )

                // تجهيز قائمة السجلات المقترنة وتطبيق الفلاتر والترتيب
                val allRecords = mutableListOf<AccuracyComparisonRecord>()
                for (s in filteredSnapshots) {
                    val act = actualMap[s.playerId to s.gameweek]
                    if (act != null && act.isGameweekFinished) {
                        allRecords.add(AccuracyComparisonRecord(s, act))
                    }
                }

                val filtered = applyFilters(
                    records = allRecords,
                    position = _uiState.value.selectedPositionFilter,
                    sort = _uiState.value.selectedSortOption
                )

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        availableGameweeks = availableGws,
                        selectedGameweek = targetGw,
                        metrics = metrics,
                        comparisonRecords = allRecords,
                        filteredRecords = filtered,
                        totalSnapshotsCount = filteredSnapshots.size,
                        totalEvaluatedCount = metrics.totalEvaluatedPredictions,
                        totalPendingCount = metrics.totalPendingPredictions
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل بيانات دقة التوقعات: ${e.localizedMessage ?: "خطأ غير متوقع"}"
                    )
                }
            }
        }
    }

    /**
     * تغيير الجولة المعروضة
     */
    fun selectGameweek(gameweek: Int?) {
        _uiState.update { it.copy(selectedGameweek = gameweek) }
        loadAccuracyData(gameweek)
    }

    /**
     * تغيير التبويب النشط
     */
    fun selectTab(tab: AccuracyScreenTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    /**
     * تصفية حسب المركز (GKP, DEF, MID, FWD)
     */
    fun selectPositionFilter(position: PlayerPosition?) {
        _uiState.update { state ->
            val updated = applyFilters(state.comparisonRecords, position, state.selectedSortOption)
            state.copy(selectedPositionFilter = position, filteredRecords = updated)
        }
    }

    /**
     * تغيير خيار الترتيب
     */
    fun selectSortOption(sort: AccuracySortOption) {
        _uiState.update { state ->
            val updated = applyFilters(state.comparisonRecords, state.selectedPositionFilter, sort)
            state.copy(selectedSortOption = sort, filteredRecords = updated)
        }
    }

    /**
     * جلب النتائج الفعلية من FPL API للجولة الحالية أو المحددة وتحديث السجلات
     */
    fun syncActualResultsForGameweek(gameweek: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingActuals = true, statusMessage = "جاري جلب النتائج الرسمية للجولة $gameweek من FPL...") }

            val result = repository.fetchAndStoreActualResults(gameweek)

            result.fold(
                onSuccess = { actualList ->
                    _uiState.update {
                        it.copy(
                            isSyncingActuals = false,
                            statusMessage = "تم تحديث ${actualList.size} نتيجة فعلية للجولة $gameweek بنجاح."
                        )
                    }
                    loadAccuracyData()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSyncingActuals = false,
                            errorMessage = error.message ?: "تعذر جلب النتائج الفعلية من FPL."
                        )
                    }
                }
            )
        }
    }

    private fun applyFilters(
        records: List<AccuracyComparisonRecord>,
        position: PlayerPosition?,
        sort: AccuracySortOption
    ): List<AccuracyComparisonRecord> {
        val byPos = if (position != null) {
            records.filter { it.snapshot.position == position }
        } else {
            records
        }

        return when (sort) {
            AccuracySortOption.HIGHEST_ERROR -> byPos.sortedByDescending { it.absolutePointsError }
            AccuracySortOption.LOWEST_ERROR -> byPos.sortedBy { it.absolutePointsError }
            AccuracySortOption.POINTS_DESC -> byPos.sortedByDescending { it.actual.actualPoints }
            AccuracySortOption.EXPECTED_DESC -> byPos.sortedByDescending { it.snapshot.expectedPoints }
        }
    }

    /**
     * توليد لقطات تاريخية واقعية ونتائج سابقة للجولتين 10 و 11 لضمان جاهزية المحرك فوراً
     */
    private suspend fun seedDemonstrationSnapshots() {
        // لقطات الجولة 10
        val gw10Players = listOf(
            createSeedPlayer(1, "Salah", "LIV", PlayerPosition.MID, 8.4, 88.0, 0.98, 92.0, 88.0, 7.2, isCaptain = true),
            createSeedPlayer(2, "Haaland", "MCI", PlayerPosition.FWD, 9.1, 90.0, 0.99, 90.0, 92.0, 8.0, isViceCaptain = true),
            createSeedPlayer(3, "Saka", "ARS", PlayerPosition.MID, 7.1, 84.0, 0.95, 85.0, 80.0, 6.4),
            createSeedPlayer(4, "Palmer", "CHE", PlayerPosition.MID, 7.8, 86.0, 0.95, 87.0, 82.0, 6.9),
            createSeedPlayer(5, "Alexander-Arnold", "LIV", PlayerPosition.DEF, 5.5, 78.0, 0.90, 82.0, 75.0, 4.8),
            createSeedPlayer(6, "Gabriel", "ARS", PlayerPosition.DEF, 4.8, 90.0, 0.99, 88.0, 85.0, 4.5),
            createSeedPlayer(7, "Raya", "ARS", PlayerPosition.GKP, 4.5, 90.0, 1.0, 90.0, 85.0, 4.0),
            createSeedPlayer(8, "Watkins", "AVL", PlayerPosition.FWD, 6.2, 85.0, 0.92, 80.0, 78.0, 5.5),
            createSeedPlayer(9, "Son", "TOT", PlayerPosition.MID, 6.8, 80.0, 0.88, 78.0, 75.0, 6.0),
            createSeedPlayer(10, "Gvardiol", "MCI", PlayerPosition.DEF, 4.6, 85.0, 0.85, 76.0, 70.0, 4.1),
            createSeedPlayer(11, "Isak", "NEW", PlayerPosition.FWD, 6.0, 82.0, 0.90, 78.0, 72.0, 5.2)
        )

        // نتائج الجولة 10 الفعلية
        val gw10Actuals = listOf(
            ActualGameweekPlayerResult(1, 10, actualPoints = 10, actualMinutes = 90, started = true, goals = 1, assists = 1, bonus = 2, isGameweekFinished = true),
            ActualGameweekPlayerResult(2, 10, actualPoints = 2, actualMinutes = 90, started = true, goals = 0, assists = 0, bonus = 0, isGameweekFinished = true),
            ActualGameweekPlayerResult(3, 10, actualPoints = 8, actualMinutes = 90, started = true, goals = 1, assists = 0, bonus = 2, isGameweekFinished = true),
            ActualGameweekPlayerResult(4, 10, actualPoints = 6, actualMinutes = 88, started = true, goals = 0, assists = 1, bonus = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(5, 10, actualPoints = 6, actualMinutes = 75, started = true, cleanSheets = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(6, 10, actualPoints = 2, actualMinutes = 90, started = true, cleanSheets = 0, isGameweekFinished = true),
            ActualGameweekPlayerResult(7, 10, actualPoints = 6, actualMinutes = 90, started = true, cleanSheets = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(8, 10, actualPoints = 5, actualMinutes = 84, started = true, assists = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(9, 10, actualPoints = 7, actualMinutes = 70, started = true, goals = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(10, 10, actualPoints = 2, actualMinutes = 90, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(11, 10, actualPoints = 9, actualMinutes = 90, started = true, goals = 1, bonus = 3, isGameweekFinished = true)
        )

        // حفظ لقطات ونتائج الجولة 10
        saveSeedBatch(10, gw10Players, captainId = 1, viceCaptainId = 2, actuals = gw10Actuals)

        // لقطات الجولة 11
        val gw11Players = listOf(
            createSeedPlayer(1, "Salah", "LIV", PlayerPosition.MID, 8.8, 90.0, 0.99, 94.0, 90.0, 7.5, isCaptain = true),
            createSeedPlayer(2, "Haaland", "MCI", PlayerPosition.FWD, 8.5, 90.0, 0.98, 88.0, 90.0, 7.8),
            createSeedPlayer(3, "Saka", "ARS", PlayerPosition.MID, 6.9, 82.0, 0.90, 80.0, 78.0, 6.5, isViceCaptain = true),
            createSeedPlayer(4, "Palmer", "CHE", PlayerPosition.MID, 7.2, 85.0, 0.92, 84.0, 80.0, 6.8),
            createSeedPlayer(5, "Alexander-Arnold", "LIV", PlayerPosition.DEF, 5.0, 60.0, 0.80, 75.0, 70.0, 4.5),
            createSeedPlayer(6, "Gabriel", "ARS", PlayerPosition.DEF, 5.2, 90.0, 0.99, 86.0, 84.0, 4.6),
            createSeedPlayer(7, "Raya", "ARS", PlayerPosition.GKP, 4.2, 90.0, 1.0, 88.0, 85.0, 4.0),
            createSeedPlayer(8, "Watkins", "AVL", PlayerPosition.FWD, 5.8, 80.0, 0.88, 76.0, 74.0, 5.2),
            createSeedPlayer(9, "Mbeumo", "BRE", PlayerPosition.MID, 6.5, 90.0, 0.95, 82.0, 80.0, 5.8),
            createSeedPlayer(10, "Ait-Nouri", "WOL", PlayerPosition.DEF, 4.0, 85.0, 0.88, 72.0, 68.0, 3.8),
            createSeedPlayer(11, "Isak", "NEW", PlayerPosition.FWD, 6.6, 88.0, 0.92, 80.0, 75.0, 5.5)
        )

        // نتائج الجولة 11 الفعلية
        val gw11Actuals = listOf(
            ActualGameweekPlayerResult(1, 11, actualPoints = 14, actualMinutes = 90, started = true, goals = 1, assists = 1, bonus = 3, cleanSheets = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(2, 11, actualPoints = 7, actualMinutes = 90, started = true, goals = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(3, 11, actualPoints = 2, actualMinutes = 80, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(4, 11, actualPoints = 2, actualMinutes = 90, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(5, 11, actualPoints = 1, actualMinutes = 24, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(6, 11, actualPoints = 2, actualMinutes = 90, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(7, 11, actualPoints = 2, actualMinutes = 90, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(8, 11, actualPoints = 2, actualMinutes = 75, started = true, isGameweekFinished = true),
            ActualGameweekPlayerResult(9, 11, actualPoints = 8, actualMinutes = 90, started = true, goals = 1, bonus = 2, isGameweekFinished = true),
            ActualGameweekPlayerResult(10, 11, actualPoints = 6, actualMinutes = 90, started = true, cleanSheets = 1, isGameweekFinished = true),
            ActualGameweekPlayerResult(11, 11, actualPoints = 8, actualMinutes = 90, started = true, goals = 1, bonus = 2, isGameweekFinished = true)
        )

        saveSeedBatch(11, gw11Players, captainId = 1, viceCaptainId = 3, actuals = gw11Actuals)
    }

    private suspend fun saveSeedBatch(
        gw: Int,
        players: List<PredictionSnapshot>,
        captainId: Int?,
        viceCaptainId: Int?,
        actuals: List<ActualGameweekPlayerResult>
    ) {
        val allSnapshots = repository.getAllSnapshots().toMutableList()
        for (p in players) {
            val key = PredictionSnapshot.createKey(p.playerId, gw, p.modelVersion)
            if (allSnapshots.none { it.id == key }) {
                allSnapshots.add(p)
            }
        }
        val gson = com.google.gson.Gson()
        val cache = com.fplmanagerai.data.cache.LocalDataCache.defaultCache
        cache.save(PredictionAccuracyRepository.KEY_PREDICTION_SNAPSHOTS, gson.toJson(allSnapshots))
        cache.save("${PredictionAccuracyRepository.PREFIX_ACTUAL_RESULTS}$gw", gson.toJson(actuals))
    }

    private fun createSeedPlayer(
        id: Int,
        name: String,
        team: String,
        pos: PlayerPosition,
        expectedPts: Double,
        expectedMins: Double,
        startProb: Double,
        confScore: Double,
        relScore: Double,
        baseline: Double,
        isCaptain: Boolean = false,
        isViceCaptain: Boolean = false
    ): PredictionSnapshot {
        return PredictionSnapshot(
            id = PredictionSnapshot.createKey(id, 10),
            playerId = id,
            playerName = name,
            playerTeam = team,
            position = pos,
            gameweek = 10,
            expectedPoints = expectedPts,
            expectedMinutes = expectedMins,
            startProbability = startProb,
            confidenceScore = confScore,
            reliabilityScore = relScore,
            riskLevel = RiskLevel.LOW,
            recommendation = TransferRecommendation.HOLD,
            baselinePoints = baseline,
            isStarter = true,
            isCaptain = isCaptain,
            isViceCaptain = isViceCaptain,
            modelVersion = "v2.0-engine",
            snapshotTimestamp = System.currentTimeMillis() - 86400000L * 7
        )
    }
}

