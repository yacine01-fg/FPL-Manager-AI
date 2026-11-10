package com.fplmanagerai.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.*
import com.fplmanagerai.data.repository.FixtureRepository
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.fplmanagerai.data.repository.PredictionAccuracyRepository
import com.fplmanagerai.data.repository.UserAccountRepository
import com.fplmanagerai.domain.calibration.ModelVersionRepository
import com.fplmanagerai.domain.chips.ChipPortfolioPlan
import com.fplmanagerai.domain.chips.ChipStrategyEngine
import com.fplmanagerai.domain.chips.UnifiedChipDecision
import com.fplmanagerai.domain.chips.UserChipHistory
import com.fplmanagerai.domain.team.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class SmartDashboardUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val currentGameweek: GameweekModel? = null,
    val nextGameweek: GameweekModel? = null,
    val deadlineCountdown: String = "",
    val deadlinePassed: Boolean = false,
    val mySquad: OptimizedSquad? = null,
    val squadValue: Double = 100.0,
    val moneyInBank: Double = 0.0,
    val freeTransfersAvailable: Int = 1,
    val injuryAlerts: List<PlayerModel> = emptyList(),
    val captainResult: CaptainSelectionResult? = null,
    val recommendedTransfers: List<TransferSuggestion> = emptyList(),
    val chipRecommendation: ChipRecommendation? = null,
    val chipPortfolioPlan: ChipPortfolioPlan? = null,
    val bestChipDecision: UnifiedChipDecision? = null,
    val selectedPlayerForDetails: PlayerModel? = null,
    val accountProfile: FplAccountProfile? = null,
    val isAccountLinkingLoading: Boolean = false,
    val accountLinkingError: String? = null,
    val showAccountDialog: Boolean = false,
    val dataSyncStatus: DataSyncStatus = DataSyncStatus.LIVE,
    val lastUpdatedTimeFormatted: String = "",
    val lastUpdatedTimestamp: Long = 0L,
    val isRealUserSquad: Boolean = false,
    val squadSyncWarningAr: String? = null
)

class DashboardViewModel(
    private val repository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val fixtureRepository: FixtureRepository = FixtureRepository(),
    private val userAccountRepository: UserAccountRepository = UserAccountRepository(),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val transferAnalyzer: TransferAnalyzer = TransferAnalyzer(),
    private val chipStrategyEngine: ChipStrategyEngine = ChipStrategyEngine(),
    private val accuracyRepository: PredictionAccuracyRepository = PredictionAccuracyRepository(),
    private val modelVersionRepository: ModelVersionRepository = ModelVersionRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartDashboardUiState())
    val uiState: StateFlow<SmartDashboardUiState> = _uiState.asStateFlow()

    init {
        val initialProfile = userAccountRepository.getSavedProfile()
        if (initialProfile != null) {
            _uiState.update { it.copy(accountProfile = initialProfile) }
        }
        loadDashboardData()
    }

    fun loadDashboardData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allPlayers = repository.loadAndAnalyze(forceRefresh)
                if (allPlayers.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "لم يتم العثور على بيانات اللاعبين من المصدر."
                        )
                    }
                    return@launch
                }
                val currentGw = repository.getCachedGameweek()
                val currentGwNumber = currentGw?.id ?: 12

                // Calculate next gameweek and deadline countdown
                val (nextGw, countdownStr, passed) = calculateDeadlineInfo(currentGw)

                // 1. Sync User Account if linked
                val savedTeamId = userAccountRepository.getSavedTeamId()
                var profile = userAccountRepository.getSavedProfile()
                var userHistory = userAccountRepository.getSavedChipHistory()
                    ?: UserChipHistory(isLinkedToAccount = savedTeamId != null, linkedTeamId = savedTeamId, isChipHistoryConfirmed = false)

                if (savedTeamId != null) {
                    try {
                        val profRes = userAccountRepository.fetchAccountProfile(savedTeamId)
                        if (profRes.isSuccess) {
                            profile = profRes.getOrNull()
                        }
                        val histRes = userAccountRepository.fetchChipHistory(savedTeamId, currentGwNumber)
                        if (histRes.isSuccess) {
                            userHistory = histRes.getOrNull() ?: userHistory
                        }
                    } catch (_: Exception) {
                        // Keep cached profile if offline
                    }
                }

                // 2. Fetch User Squad if linked, or optimize starting squad
                var squadWarning: String? = null
                val actualUserSquad = if (savedTeamId != null) {
                    val res = userAccountRepository.fetchUserSquad(savedTeamId, currentGwNumber, allPlayers)
                    if (res.isFailure) {
                        squadWarning = res.exceptionOrNull()?.message ?: "تعذر جلب التشكيلة الرسمية من FPL."
                    }
                    res.getOrNull()
                } else null

                val squad = actualUserSquad ?: squadOptimizer.optimizeSquad(
                    allPlayers = allPlayers,
                    budget = profile?.teamValue ?: 100.0,
                    strategy = OptimizationStrategy.BALANCED
                )

                // Injury alerts within current squad
                val alerts = squad.totalSquad.filter {
                    !it.availability.isAvailable ||
                            it.prediction?.riskLevel == RiskLevel.CRITICAL ||
                            it.prediction?.riskLevel == RiskLevel.HIGH ||
                            (it.availability.chanceOfPlayingNextRound != null && it.availability.chanceOfPlayingNextRound < 100)
                }

                // Captain pick
                val capResult = captainSelector.selectCaptain(squad.starting11)

                // أرشفة لقطة غير قابلة للتعديل للتوقعات قبل انطلاق الجولة لمحرك قياس الدقة
                try {
                    accuracyRepository.saveSnapshots(
                        gameweek = currentGwNumber,
                        players = allPlayers,
                        startingPlayerIds = squad.starting11.map { it.id }.toSet(),
                        captainId = capResult?.captain?.id,
                        viceCaptainId = capResult?.viceCaptain?.id,
                        modelVersion = modelVersionRepository.getActiveModel().id
                    )
                } catch (_: Exception) {
                    // فشل أرشفة اللقطة لا يوقف عمل لوحة التحكم
                }

                // Transfer suggestions
                val effectiveBank = if (actualUserSquad != null) actualUserSquad.remainingBudget else (profile?.bank ?: squad.remainingBudget)
                val freeTransfers = userHistory.bankedFreeTransfers

                val transferRes = transferAnalyzer.analyzeTransfers(
                    currentSquad = squad.starting11,
                    allPlayers = allPlayers,
                    freeTransfers = freeTransfers,
                    bankBudget = effectiveBank,
                    horizon = 3
                )

                // Chip Strategy Analysis with actual account user history
                val fixtures = try {
                    fixtureRepository.getFixtures(forceRefresh)
                } catch (_: Exception) {
                    emptyList()
                }

                val chipPlan = try {
                    chipStrategyEngine.evaluateChips(
                        squad = squad.totalSquad,
                        allPlayers = allPlayers,
                        fixtures = fixtures,
                        currentGameweek = currentGwNumber,
                        userHistory = userHistory,
                        freeTransfers = freeTransfers,
                        budget = squad.totalCost + effectiveBank,
                        recordSnapshots = false
                    )
                } catch (_: Exception) {
                    null
                }

                val bestDecision = chipPlan?.unifiedDecisions?.firstOrNull { it.shouldActivateNow }
                    ?: chipPlan?.unifiedDecisions?.maxByOrNull { it.expectedGain }

                val syncStatus = DataSyncStatus.resolve(
                    isFromCache = repository.isLastLoadedFromCache,
                    isError = false,
                    timestampMillis = repository.lastUpdatedTimestamp
                )
                val timeStr = DataSyncStatus.formatRelativeTimeAr(repository.lastUpdatedTimestamp)

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentGameweek = currentGw,
                        nextGameweek = nextGw,
                        deadlineCountdown = countdownStr,
                        deadlinePassed = passed,
                        mySquad = squad,
                        squadValue = if (actualUserSquad != null) (profile?.teamValue ?: squad.totalCost) else squad.totalCost,
                        moneyInBank = effectiveBank,
                        freeTransfersAvailable = freeTransfers,
                        injuryAlerts = alerts,
                        captainResult = capResult,
                        recommendedTransfers = transferRes.suggestions.take(3),
                        chipRecommendation = transferRes.activeChipRecommendation,
                        chipPortfolioPlan = chipPlan,
                        bestChipDecision = bestDecision,
                        accountProfile = profile,
                        dataSyncStatus = syncStatus,
                        lastUpdatedTimeFormatted = timeStr,
                        lastUpdatedTimestamp = repository.lastUpdatedTimestamp,
                        isRealUserSquad = actualUserSquad != null,
                        squadSyncWarningAr = squadWarning
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dataSyncStatus = DataSyncStatus.ERROR,
                        errorMessage = "تعذر تحميل لوحة التحكم الذكية: ${e.localizedMessage ?: "خطأ في الشبكة"}"
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
                    loadDashboardData(forceRefresh = true)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isAccountLinkingLoading = false,
                            accountLinkingError = error.localizedMessage ?: "فشل ربط الحساب. يرجى التأكد من الـ Team ID."
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
                mySquad = null,
                accountLinkingError = null,
                showAccountDialog = false
            )
        }
        loadDashboardData(forceRefresh = true)
    }

    fun selectPlayerForDetails(player: PlayerModel?) {
        _uiState.update { it.copy(selectedPlayerForDetails = player) }
    }

    private fun calculateDeadlineInfo(currentGw: GameweekModel?): Triple<GameweekModel?, String, Boolean> {
        val deadlineStr = currentGw?.deadlineTime
        if (deadlineStr.isNullOrBlank()) {
            return Triple(currentGw, "غير محدد", false)
        }

        return try {
            val deadlineInstant = Instant.parse(deadlineStr)
            val now = Instant.now()
            val duration = Duration.between(now, deadlineInstant)

            if (duration.isNegative || duration.isZero) {
                Triple(currentGw, "الجولة مغلقة (بدأت المباريات)", true)
            } else {
                val days = duration.toDays()
                val hours = duration.toHours() % 24
                val minutes = duration.toMinutes() % 60
                val countdown = buildString {
                    if (days > 0) append("$days يوم و ")
                    append("$hours ساعة و $minutes دقيقة")
                }
                Triple(currentGw, countdown, false)
            }
        } catch (_: Exception) {
            Triple(currentGw, currentGw.deadlineTime, false)
        }
    }
}
