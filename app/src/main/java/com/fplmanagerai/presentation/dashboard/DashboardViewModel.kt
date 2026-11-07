package com.fplmanagerai.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.*
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
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
    val selectedPlayerForDetails: PlayerModel? = null
)

class DashboardViewModel(
    private val repository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val transferAnalyzer: TransferAnalyzer = TransferAnalyzer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartDashboardUiState())
    val uiState: StateFlow<SmartDashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    fun loadDashboardData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allPlayers = repository.loadAndAnalyze(forceRefresh)
                val currentGw = repository.getCachedGameweek()

                // Calculate next gameweek and deadline countdown
                val (nextGw, countdownStr, passed) = calculateDeadlineInfo(currentGw)

                // Generate squad
                val squad = squadOptimizer.optimizeSquad(
                    allPlayers = allPlayers,
                    budget = 100.0,
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

                // Transfer suggestions & chips
                val transferRes = transferAnalyzer.analyzeTransfers(
                    currentSquad = squad.starting11,
                    allPlayers = allPlayers,
                    freeTransfers = 1,
                    bankBudget = squad.remainingBudget,
                    horizon = 3
                )

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentGameweek = currentGw,
                        nextGameweek = nextGw,
                        deadlineCountdown = countdownStr,
                        deadlinePassed = passed,
                        mySquad = squad,
                        squadValue = squad.totalCost,
                        moneyInBank = squad.remainingBudget,
                        freeTransfersAvailable = 1,
                        injuryAlerts = alerts,
                        captainResult = capResult,
                        recommendedTransfers = transferRes.suggestions.take(3),
                        chipRecommendation = transferRes.activeChipRecommendation
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل لوحة التحكم الذكية: ${e.localizedMessage ?: "خطأ في الشبكة"}"
                    )
                }
            }
        }
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
