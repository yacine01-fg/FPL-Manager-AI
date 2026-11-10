package com.fplmanagerai.presentation.decision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.repository.FixtureRepository
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.fplmanagerai.data.repository.UserAccountRepository
import com.fplmanagerai.domain.calibration.ModelVersionRepository
import com.fplmanagerai.domain.chips.UserChipHistory
import com.fplmanagerai.domain.decision.DecisionIntelligenceEngine
import com.fplmanagerai.domain.decision.GameweekDecisionPlan
import com.fplmanagerai.domain.decision.ScenarioId
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.SquadOptimizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DecisionIntelligenceUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val plan: GameweekDecisionPlan? = null,
    val selectedScenarioId: ScenarioId? = null,
    val isRealUserSquad: Boolean = false,
    val squadSyncWarningAr: String? = null,
    val activeModelVersionId: String = "",
    val currentGameweek: Int = 1,
    val bank: Double = 0.0,
    val freeTransfers: Int = 1
)

class DecisionIntelligenceViewModel(
    private val playerRepository: PlayerAnalysisRepository = PlayerAnalysisRepository(),
    private val fixtureRepository: FixtureRepository = FixtureRepository(),
    private val userAccountRepository: UserAccountRepository = UserAccountRepository(),
    private val squadOptimizer: SquadOptimizer = SquadOptimizer(),
    private val decisionEngine: DecisionIntelligenceEngine = DecisionIntelligenceEngine(),
    private val modelVersionRepository: ModelVersionRepository = ModelVersionRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DecisionIntelligenceUiState())
    val uiState: StateFlow<DecisionIntelligenceUiState> = _uiState.asStateFlow()

    init {
        loadDecisions()
    }

    fun loadDecisions(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allPlayers = playerRepository.loadAndAnalyze(forceRefresh)
                if (allPlayers.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "تعذر تحميل بيانات لاعبي الدوري الإنجليزي. يرجى التحقق من الاتصال بالإنترنت."
                        )
                    }
                    return@launch
                }

                val currentGwModel = playerRepository.getCachedGameweek()
                val currentGwNumber = currentGwModel?.id ?: 1

                val fixtures = try {
                    fixtureRepository.getFixtures()
                } catch (_: Exception) {
                    emptyList<com.fplmanagerai.data.model.FixtureModel>()
                }

                // 1. مزامنة بيانات حساب المستخدم وسجل الشرائح والتبديلات المخزنة
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
                        // استخدام البيانات المحفوظة محلياً عند انقطاع الإنترنت
                    }
                }

                // 2. سحب تشكيلة المستخدم الحقيقية أو بناء تشكيلة افتراضية مثالية
                var squadWarning: String? = null
                if (playerRepository.isLastLoadedFromCache) {
                    val ageHours = (System.currentTimeMillis() - playerRepository.lastUpdatedTimestamp) / (1000 * 60 * 60)
                    if (ageHours >= 12) {
                        squadWarning = "تنبيه البيانات: البيانات الحالية مسترجعة من الذاكرة المحلية (منذ $ageHours ساعة). اضغط على زر التحديث لجلب أحدث الإصابات ومستجدات المؤتمرات الصحفية."
                    }
                }

                var isRealSquad = false
                val userSquadResult = if (savedTeamId != null) {
                    val res = userAccountRepository.fetchUserSquad(savedTeamId, currentGwNumber, allPlayers)
                    if (res.isFailure) {
                        val failMsg = res.exceptionOrNull()?.message ?: "تعذر جلب التشكيلة الرسمية من FPL."
                        squadWarning = if (squadWarning != null) "$squadWarning | $failMsg" else failMsg
                    }
                    res.getOrNull()
                } else null

                val squad15: List<PlayerModel> = if (userSquadResult != null) {
                    isRealSquad = true
                    userSquadResult.totalSquad
                } else {
                    val optimized = squadOptimizer.optimizeSquad(
                        allPlayers = allPlayers,
                        budget = profile?.teamValue ?: 100.0,
                        strategy = OptimizationStrategy.BALANCED
                    )
                    optimized.totalSquad
                }

                val effectiveBank = profile?.bank ?: 0.0
                val effectiveFreeTransfers = userHistory.bankedFreeTransfers.coerceIn(1, 5)
                val activeModel = modelVersionRepository.getActiveModel()

                // 3. توليد خطة القرارات التكتيكية الموحدة
                val plan = decisionEngine.generateGameweekPlan(
                    squad15 = squad15,
                    allPlayers = allPlayers,
                    fixtures = fixtures,
                    currentGameweek = currentGwNumber,
                    userHistory = userHistory,
                    bank = effectiveBank,
                    freeTransfers = effectiveFreeTransfers,
                    isRealUserSquad = isRealSquad,
                    activeModelId = activeModel.id
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        plan = plan,
                        selectedScenarioId = plan.scenariosComparison.bestScenario.id,
                        isRealUserSquad = isRealSquad,
                        squadSyncWarningAr = squadWarning,
                        activeModelVersionId = activeModel.id,
                        currentGameweek = currentGwNumber,
                        bank = effectiveBank,
                        freeTransfers = effectiveFreeTransfers
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "حدث خطأ أثناء معالجة قرارات الجولة: ${e.localizedMessage ?: "خطأ غير متوقع"}"
                    )
                }
            }
        }
    }

    fun selectScenario(scenarioId: ScenarioId) {
        _uiState.update { it.copy(selectedScenarioId = scenarioId) }
    }
}
