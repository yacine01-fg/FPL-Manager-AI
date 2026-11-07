package com.fplmanagerai.presentation.chips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.chips.HistoricalChipRepository
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.GameweekModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.repository.FixtureRepository
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.fplmanagerai.domain.chips.*
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.SquadOptimizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChipStrategyUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val actionAlertMessageAr: String? = null,
    val currentGameweek: GameweekModel? = null,
    val selectedHalf: ChipHalf = ChipHalf.FIRST_HALF,
    val portfolioPlan: ChipPortfolioPlan? = null,
    val userHistory: UserChipHistory = UserChipHistory(),
    val calibrationMetrics: CalibrationMetrics? = null,
    val showSquadPreviewDialog: Boolean = false,
    val previewSquadTitleAr: String = "",
    val previewSquadPlayers: List<PlayerModel> = emptyList(),
    val isCacheUsed: Boolean = false
)

class ChipStrategyViewModel(
    private val playerRepository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val fixtureRepository: FixtureRepository = FixtureRepository(),
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27,
    private val rulesEngine: ChipRulesEngine = ChipRulesEngine(config),
    private val historicalRepository: HistoricalChipRepository = HistoricalChipRepository(),
    private val calibrationEngine: ChipCalibrationEngine = ChipCalibrationEngine(historicalRepository),
    private val chipStrategyEngine: ChipStrategyEngine = ChipStrategyEngine(
        config = config,
        rulesEngine = rulesEngine,
        historicalRepository = historicalRepository,
        calibrationEngine = calibrationEngine
    ),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChipStrategyUiState())
    val uiState: StateFlow<ChipStrategyUiState> = _uiState.asStateFlow()

    private var cachedAllPlayers: List<PlayerModel> = emptyList()
    private var cachedFixtures: List<FixtureModel> = emptyList()
    private var cachedSquad: List<PlayerModel> = emptyList()

    init {
        loadStrategy()
    }

    fun loadStrategy(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, actionAlertMessageAr = null) }
            try {
                // 1. Fetch Players & Gameweek
                val players = playerRepository.loadAndAnalyze(forceRefresh)
                cachedAllPlayers = players
                val gw = playerRepository.getCachedGameweek()
                val currentGwNumber = gw?.id ?: 12
                val currentHalf = ChipHalf.fromGameweek(currentGwNumber)

                // 2. Fetch Fixtures
                val fixtures = fixtureRepository.getFixtures(forceRefresh)
                cachedFixtures = fixtures

                // 3. Obtain User Squad
                val squad = squadOptimizer.optimizeSquad(
                    allPlayers = players,
                    budget = 100.0,
                    strategy = OptimizationStrategy.BALANCED
                ).totalSquad
                cachedSquad = squad

                // 4. Compute Calibration Metrics
                val metrics = calibrationEngine.computeMetrics()

                // 5. Run Chip Strategy Engine
                val plan = chipStrategyEngine.evaluateChips(
                    squad = squad,
                    allPlayers = players,
                    fixtures = fixtures,
                    currentGameweek = currentGwNumber,
                    userHistory = _uiState.value.userHistory
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentGameweek = gw,
                        selectedHalf = currentHalf,
                        portfolioPlan = plan,
                        calibrationMetrics = metrics,
                        isCacheUsed = playerRepository.isLastLoadedFromCache
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل استراتيجية الشرائح: ${e.localizedMessage ?: "خطأ في الشبكة"}"
                    )
                }
            }
        }
    }

    fun selectHalf(half: ChipHalf) {
        _uiState.update { it.copy(selectedHalf = half) }
    }

    /**
     * Mark a chip as pending for the current gameweek deadline
     */
    fun setPending(chip: ChipType, half: ChipHalf) {
        val gwNumber = _uiState.value.currentGameweek?.id ?: 12
        val (updatedHistory, validation) = rulesEngine.setPending(chip, half, gwNumber, _uiState.value.userHistory)

        if (!validation.isValid) {
            _uiState.update { it.copy(actionAlertMessageAr = validation.ruleViolationAr) }
            return
        }

        _uiState.update {
            it.copy(
                userHistory = updatedHistory,
                actionAlertMessageAr = validation.warningAr ?: "تم تحديد ${chip.titleAr} كرقاقة معلقة لهذه الجولة."
            )
        }
        recalculateWithHistory(updatedHistory)
    }

    /**
     * Cancels any pending chip
     */
    fun cancelPending() {
        val updatedHistory = rulesEngine.cancelPending(_uiState.value.userHistory)
        _uiState.update {
            it.copy(
                userHistory = updatedHistory,
                actionAlertMessageAr = "تم إلغاء الرقاقة المعلقة."
            )
        }
        recalculateWithHistory(updatedHistory)
    }

    /**
     * Confirms chip activation for the current gameweek
     */
    fun confirmActivation(chip: ChipType, half: ChipHalf) {
        val gwNumber = _uiState.value.currentGameweek?.id ?: 12
        val (updatedHistory, validation) = rulesEngine.confirmActivation(chip, half, gwNumber, _uiState.value.userHistory)

        if (!validation.isValid) {
            _uiState.update { it.copy(actionAlertMessageAr = validation.ruleViolationAr) }
            return
        }

        _uiState.update {
            it.copy(
                userHistory = updatedHistory,
                actionAlertMessageAr = "تم تفعيل ${chip.titleAr} بنجاح للجولة الحالية!"
            )
        }
        recalculateWithHistory(updatedHistory)
    }

    /**
     * Toggles whether the user has marked a chip in a specific half as used
     */
    fun toggleChipUsedInHalf(chip: ChipType, half: ChipHalf) {
        val gwNumber = _uiState.value.currentGameweek?.id ?: 12
        val currentHistory = _uiState.value.userHistory
        val isCurrentlyUsed = currentHistory.getChipState(chip, half, gwNumber) == ChipStatus.USED

        val updatedSet1 = if (half == ChipHalf.FIRST_HALF) {
            if (isCurrentlyUsed) currentHistory.set1UsedChips - chip else currentHistory.set1UsedChips + chip
        } else currentHistory.set1UsedChips

        val updatedSet2 = if (half == ChipHalf.SECOND_HALF) {
            if (isCurrentlyUsed) currentHistory.set2UsedChips - chip else currentHistory.set2UsedChips + chip
        } else currentHistory.set2UsedChips

        val updatedHistory = currentHistory.copy(
            set1UsedChips = updatedSet1,
            set2UsedChips = updatedSet2,
            firstHalfWildcardUsed = if (chip == ChipType.WILDCARD && half == ChipHalf.FIRST_HALF) !isCurrentlyUsed else currentHistory.firstHalfWildcardUsed,
            secondHalfWildcardUsed = if (chip == ChipType.WILDCARD && half == ChipHalf.SECOND_HALF) !isCurrentlyUsed else currentHistory.secondHalfWildcardUsed,
            usedChips = updatedSet1 + updatedSet2
        )

        _uiState.update { it.copy(userHistory = updatedHistory) }
        recalculateWithHistory(updatedHistory)
    }

    fun dismissAlert() {
        _uiState.update { it.copy(actionAlertMessageAr = null) }
    }

    private fun recalculateWithHistory(history: UserChipHistory) {
        val gwNumber = _uiState.value.currentGameweek?.id ?: 12
        if (cachedSquad.isNotEmpty() && cachedAllPlayers.isNotEmpty()) {
            val plan = chipStrategyEngine.evaluateChips(
                squad = cachedSquad,
                allPlayers = cachedAllPlayers,
                fixtures = cachedFixtures,
                currentGameweek = gwNumber,
                userHistory = history,
                recordSnapshots = false
            )
            _uiState.update { it.copy(portfolioPlan = plan) }
        }
    }

    fun openSquadPreview(titleAr: String, squad: List<PlayerModel>) {
        _uiState.update {
            it.copy(
                showSquadPreviewDialog = true,
                previewSquadTitleAr = titleAr,
                previewSquadPlayers = squad
            )
        }
    }

    fun closeSquadPreview() {
        _uiState.update {
            it.copy(
                showSquadPreviewDialog = false,
                previewSquadPlayers = emptyList()
            )
        }
    }
}
