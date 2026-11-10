package com.fplmanagerai.presentation.chips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.chips.ChipSeasonConfig
import com.fplmanagerai.data.chips.HistoricalChipRepository
import com.fplmanagerai.data.model.DataSyncStatus
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.FplAccountProfile
import com.fplmanagerai.data.model.GameweekModel
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.repository.FixtureRepository
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.fplmanagerai.data.repository.UserAccountRepository
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
    val isCacheUsed: Boolean = false,
    val accountProfile: FplAccountProfile? = null,
    val isAccountLinkingLoading: Boolean = false,
    val accountLinkingError: String? = null,
    val showAccountDialog: Boolean = false,
    val dataSyncStatus: DataSyncStatus = DataSyncStatus.LIVE,
    val lastUpdatedTimeFormatted: String = "",
    val lastUpdatedTimestamp: Long = 0L
)

class ChipStrategyViewModel(
    private val playerRepository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val fixtureRepository: FixtureRepository = FixtureRepository(),
    private val userAccountRepository: UserAccountRepository = UserAccountRepository(),
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
        val initialProfile = userAccountRepository.getSavedProfile()
        if (initialProfile != null) {
            _uiState.update { it.copy(accountProfile = initialProfile) }
        }
        loadStrategy()
    }

    fun loadStrategy(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, actionAlertMessageAr = null) }
            try {
                // 1. Fetch Players & Gameweek
                val players = playerRepository.loadAndAnalyze(forceRefresh)
                if (players.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "تعذر تحميل بيانات اللاعبين من المصدر."
                        )
                    }
                    return@launch
                }
                cachedAllPlayers = players
                val gw = playerRepository.getCachedGameweek()
                val currentGwNumber = gw?.id ?: 12
                val currentHalf = ChipHalf.fromGameweek(currentGwNumber)

                // 2. Fetch Fixtures
                val fixtures = fixtureRepository.getFixtures(forceRefresh)
                cachedFixtures = fixtures

                // 3. Sync User Account if linked
                val savedTeamId = userAccountRepository.getSavedTeamId()
                var profile = userAccountRepository.getSavedProfile()
                var currentHistory = userAccountRepository.getSavedChipHistory()
                    ?: UserChipHistory(isLinkedToAccount = savedTeamId != null, linkedTeamId = savedTeamId, isChipHistoryConfirmed = false)

                if (savedTeamId != null) {
                    try {
                        val profRes = userAccountRepository.fetchAccountProfile(savedTeamId)
                        if (profRes.isSuccess) {
                            profile = profRes.getOrNull()
                        }
                        val histRes = userAccountRepository.fetchChipHistory(savedTeamId, currentGwNumber)
                        if (histRes.isSuccess) {
                            currentHistory = histRes.getOrNull() ?: currentHistory
                        }
                    } catch (_: Exception) {}
                }

                // 4. Obtain User Squad (From FPL picks if available, or optimizer)
                val actualUserSquad = if (savedTeamId != null) {
                    userAccountRepository.fetchUserSquad(savedTeamId, currentGwNumber, players).getOrNull()
                } else null

                val squad = actualUserSquad?.totalSquad ?: squadOptimizer.optimizeSquad(
                    allPlayers = players,
                    budget = profile?.teamValue ?: 100.0,
                    strategy = OptimizationStrategy.BALANCED
                ).totalSquad
                cachedSquad = squad

                // 5. Compute Calibration Metrics
                val metrics = calibrationEngine.computeMetrics()

                // 6. Run Chip Strategy Engine with verified user history
                val plan = chipStrategyEngine.evaluateChips(
                    squad = squad,
                    allPlayers = players,
                    fixtures = fixtures,
                    currentGameweek = currentGwNumber,
                    userHistory = currentHistory,
                    freeTransfers = currentHistory.bankedFreeTransfers,
                    budget = (profile?.bank ?: 0.0) + (actualUserSquad?.totalCost ?: 100.0)
                )

                val syncStatus = DataSyncStatus.resolve(
                    isFromCache = playerRepository.isLastLoadedFromCache,
                    isError = false,
                    timestampMillis = playerRepository.lastUpdatedTimestamp
                )
                val timeStr = DataSyncStatus.formatRelativeTimeAr(playerRepository.lastUpdatedTimestamp)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentGameweek = gw,
                        selectedHalf = currentHalf,
                        portfolioPlan = plan,
                        userHistory = currentHistory,
                        calibrationMetrics = metrics,
                        isCacheUsed = playerRepository.isLastLoadedFromCache,
                        accountProfile = profile,
                        dataSyncStatus = syncStatus,
                        lastUpdatedTimeFormatted = timeStr,
                        lastUpdatedTimestamp = playerRepository.lastUpdatedTimestamp
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dataSyncStatus = DataSyncStatus.ERROR,
                        errorMessage = "تعذر تحميل استراتيجية الشرائح: ${e.localizedMessage ?: "خطأ في الشبكة"}"
                    )
                }
            }
        }
    }

    fun setAccountDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showAccountDialog = visible, accountLinkingError = null) }
    }

    fun linkFplAccount(teamId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAccountLinkingLoading = true, accountLinkingError = null) }
            val result = userAccountRepository.fetchAccountProfile(teamId)
            result.fold(
                onSuccess = { profile ->
                    _uiState.update {
                        it.copy(
                            isAccountLinkingLoading = false,
                            accountProfile = profile,
                            accountLinkingError = null,
                            showAccountDialog = false
                        )
                    }
                    loadStrategy(forceRefresh = true)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isAccountLinkingLoading = false,
                            accountLinkingError = error.localizedMessage ?: "فشل ربط الحساب. يرجى التحقق من صحة Team ID."
                        )
                    }
                }
            )
        }
    }

    fun unlinkFplAccount() {
        userAccountRepository.unlinkAccount()
        _uiState.update {
            it.copy(
                accountProfile = null,
                userHistory = UserChipHistory(),
                portfolioPlan = null,
                accountLinkingError = null,
                showAccountDialog = false
            )
        }
        loadStrategy(forceRefresh = true)
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
