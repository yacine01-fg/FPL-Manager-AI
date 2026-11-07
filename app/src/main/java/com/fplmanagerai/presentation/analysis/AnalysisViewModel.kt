package com.fplmanagerai.presentation.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.fplmanagerai.domain.analysis.CategorizedRankings
import com.fplmanagerai.domain.analysis.PlayerRankingEngine
import com.fplmanagerai.domain.analysis.RankingCriteria
import com.fplmanagerai.domain.team.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val gameweekName: String = "",
    val allPlayers: List<PlayerModel> = emptyList(),
    val filteredPlayers: List<PlayerModel> = emptyList(),
    val searchQuery: String = "",
    val selectedPosition: PlayerPosition? = null,
    val selectedCriteria: RankingCriteria = RankingCriteria.EXPECTED_POINTS,
    val categorizedRankings: CategorizedRankings? = null,
    val selectedPlayer: PlayerModel? = null,
    // Optimizer state
    val budget: Double = 100.0,
    val preferredFormation: Formation? = null,
    val riskPreference: RiskPreference = RiskPreference.BALANCED,
    val planningHorizon: Int = 3,
    val freeTransfers: Int = 1,
    val optimizedSquad: OptimizedSquad? = null,
    val isOptimizing: Boolean = false,
    val captainResult: CaptainSelectionResult? = null,
    val transferAnalysis: TransferAnalysisResult? = null
)

class AnalysisViewModel(
    private val repository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val rankingEngine: PlayerRankingEngine = PlayerRankingEngine(),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val captainSelector: CaptainSelector = CaptainSelector(),
    private val transferAnalyzer: TransferAnalyzer = TransferAnalyzer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val players = repository.loadAndAnalyze(forceRefresh)
                val currentGw = repository.getCachedGameweek()
                val gwId = currentGw?.id ?: 1
                val gwName = currentGw?.name ?: "الجولة الحالية"

                val insights = rankingEngine.getCategorizedInsights(players, gwId)
                val ranked = rankingEngine.enrichAndRank(
                    players = players,
                    currentGameweek = gwId,
                    criteria = _uiState.value.selectedCriteria,
                    positionFilter = _uiState.value.selectedPosition
                )

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allPlayers = players,
                        filteredPlayers = ranked,
                        gameweekName = gwName,
                        categorizedRankings = insights
                    )
                }

                // Run initial squad optimization in background
                runOptimization()

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل وتحليل بيانات اللاعبين: ${e.localizedMessage ?: "خطأ في الاتصال"}"
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }

    fun onPositionSelected(position: PlayerPosition?) {
        _uiState.update { it.copy(selectedPosition = position) }
        applyFilters()
    }

    fun onCriteriaChanged(criteria: RankingCriteria) {
        _uiState.update { it.copy(selectedCriteria = criteria) }
        applyFilters()
    }

    fun onBudgetChanged(newBudget: Double) {
        _uiState.update { it.copy(budget = newBudget) }
    }

    fun onFormationChanged(formation: Formation?) {
        _uiState.update { it.copy(preferredFormation = formation) }
    }

    fun onRiskPreferenceChanged(risk: RiskPreference) {
        _uiState.update { it.copy(riskPreference = risk) }
    }

    fun onHorizonChanged(horizon: Int) {
        _uiState.update { it.copy(planningHorizon = horizon) }
    }

    fun onFreeTransfersChanged(count: Int) {
        _uiState.update { it.copy(freeTransfers = count) }
    }

    fun selectPlayer(player: PlayerModel?) {
        _uiState.update { it.copy(selectedPlayer = player) }
    }

    fun runOptimization() {
        val currentState = _uiState.value
        val players = currentState.allPlayers
        if (players.isEmpty()) return

        _uiState.update { it.copy(isOptimizing = true) }

        viewModelScope.launch {
            try {
                val optimized = squadOptimizer.optimizeSquad(
                    allPlayers = players,
                    budget = currentState.budget,
                    preferredFormation = currentState.preferredFormation,
                    riskPreference = currentState.riskPreference,
                    horizon = currentState.planningHorizon
                )

                val capResult = captainSelector.selectCaptain(optimized.starting11)

                val transferRes = transferAnalyzer.analyzeTransfers(
                    currentSquad = optimized.starting11,
                    allPlayers = players,
                    freeTransfers = currentState.freeTransfers,
                    bankBudget = optimized.remainingBudget,
                    horizon = currentState.planningHorizon
                )

                _uiState.update {
                    it.copy(
                        isOptimizing = false,
                        optimizedSquad = optimized,
                        captainResult = capResult,
                        transferAnalysis = transferRes
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isOptimizing = false) }
            }
        }
    }

    private fun applyFilters() {
        val state = _uiState.value
        val currentGw = repository.getCachedGameweek()?.id ?: 1

        val ranked = rankingEngine.enrichAndRank(
            players = state.allPlayers,
            currentGameweek = currentGw,
            criteria = state.selectedCriteria,
            positionFilter = state.selectedPosition
        )

        val filtered = if (state.searchQuery.isBlank()) {
            ranked
        } else {
            val q = state.searchQuery.trim()
            ranked.filter {
                it.webName.contains(q, ignoreCase = true) ||
                        it.fullName.contains(q, ignoreCase = true) ||
                        it.teamName.contains(q, ignoreCase = true) ||
                        it.teamShortName.contains(q, ignoreCase = true)
            }
        }

        _uiState.update { it.copy(filteredPlayers = filtered) }
    }
}
