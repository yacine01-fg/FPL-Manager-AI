package com.fplmanagerai.presentation.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fplmanagerai.data.model.AccuracyComparisonRecord
import com.fplmanagerai.data.model.ActualGameweekPlayerResult
import com.fplmanagerai.data.repository.PredictionAccuracyRepository
import com.fplmanagerai.domain.calibration.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PredictionCalibrationUiState(
    val isLoading: Boolean = false,
    val isCalibrating: Boolean = false,
    val activeModel: ModelVersion = ModelVersion.createBaseline(),
    val allVersions: List<ModelVersion> = emptyList(),
    val datasetSummary: CalibrationDatasetSummary = CalibrationDatasetSummary(0, 0, emptyList()),
    val latestRunResult: CalibrationRunResult? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class PredictionCalibrationViewModel(
    private val calibrationEngine: PredictionCalibrationEngine = PredictionCalibrationEngine(),
    private val modelVersionRepository: ModelVersionRepository = ModelVersionRepository(),
    private val accuracyRepository: PredictionAccuracyRepository = PredictionAccuracyRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(PredictionCalibrationUiState())
    val uiState: StateFlow<PredictionCalibrationUiState> = _uiState.asStateFlow()

    init {
        loadCalibrationState()
    }

    /**
     * تحميل حالة النماذج والتحقق من كفاية البيانات المسجلة
     */
    fun loadCalibrationState() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val active = modelVersionRepository.getActiveModel()
                val versions = modelVersionRepository.getAllVersions()

                val comparisonRecords = loadEvaluatedComparisonRecords()
                val summary = calibrationEngine.checkDatasetSufficiency(comparisonRecords)

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        activeModel = active,
                        allVersions = versions,
                        datasetSummary = summary
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل بيانات المعايرة: ${e.localizedMessage ?: "خطأ غير متوقع"}"
                    )
                }
            }
        }
    }

    /**
     * بدء عملية المعايرة والتحسين الذاتي التكيفي
     */
    fun runCalibration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCalibrating = true, errorMessage = null, statusMessage = "جاري فحص العينة وإجراء Walk-Forward Validation...") }

            try {
                val records = loadEvaluatedComparisonRecords()
                val currentActive = modelVersionRepository.getActiveModel()

                val result = calibrationEngine.calibrateAndOptimize(
                    records = records,
                    currentModel = currentActive
                )

                if (result.isApproved && result.candidateModel != null) {
                    // حفظ النموذج المرشح في السجل
                    modelVersionRepository.saveModelVersion(result.candidateModel)
                }

                val allUpdated = modelVersionRepository.getAllVersions()
                val summary = calibrationEngine.checkDatasetSufficiency(records)

                _uiState.update { state ->
                    state.copy(
                        isCalibrating = false,
                        latestRunResult = result,
                        allVersions = allUpdated,
                        datasetSummary = summary,
                        statusMessage = result.messageAr
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCalibrating = false,
                        errorMessage = "حدث خطأ أثناء تنفيذ المعايرة: ${e.localizedMessage ?: "خطأ في المعالجة"}"
                    )
                }
            }
        }
    }

    /**
     * تفعيل النموذج المرشح أو أي إصدار معتمد
     */
    fun activateModelVersion(versionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = modelVersionRepository.activateVersion(versionId)
            res.fold(
                onSuccess = { activated ->
                    val versions = modelVersionRepository.getAllVersions()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeModel = activated,
                            allVersions = versions,
                            statusMessage = "تم تفعيل النموذج «${activated.displayNameAr}» بنجاح لجميع التوقعات القادمة."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "تعذر تفعيل النموذج المطلوب."
                        )
                    }
                }
            )
        }
    }

    /**
     * التراجع إلى إصدار سابق (Rollback)
     */
    fun rollbackToVersion(versionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = modelVersionRepository.rollbackTo(versionId)
            res.fold(
                onSuccess = { target ->
                    val versions = modelVersionRepository.getAllVersions()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeModel = target,
                            allVersions = versions,
                            statusMessage = "تم الرجوع بنجاح إلى الإصدار «${target.displayNameAr}»."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "فشل التراجع إلى الإصدار السابق."
                        )
                    }
                }
            )
        }
    }

    /**
     * إعادة ضبط فورية لجميع التوقعات إلى نموذج خط الأساس الافتراضي
     */
    fun resetToBaseline() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val baseline = modelVersionRepository.resetToBaseline()
            val versions = modelVersionRepository.getAllVersions()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    activeModel = baseline,
                    allVersions = versions,
                    statusMessage = "تمت إعادة ضبط النموذج إلى خط الأساس الافتراضي (v2.0-baseline)."
                )
            }
        }
    }

    private fun loadEvaluatedComparisonRecords(): List<AccuracyComparisonRecord> {
        val snapshots = accuracyRepository.getAllSnapshots()
        if (snapshots.isEmpty()) return emptyList()

        val gws = snapshots.map { it.gameweek }.distinct()
        val actualMap = mutableMapOf<Pair<Int, Int>, ActualGameweekPlayerResult>()

        for (gw in gws) {
            val res = accuracyRepository.loadCachedActualResults(gw)
            if (res.isSuccess) {
                res.getOrNull()?.forEach { actual ->
                    actualMap[actual.playerId to actual.gameweek] = actual
                }
            }
        }

        val records = mutableListOf<AccuracyComparisonRecord>()
        for (s in snapshots) {
            val act = actualMap[s.playerId to s.gameweek]
            if (act != null && act.isGameweekFinished) {
                records.add(AccuracyComparisonRecord(s, act))
            }
        }
        return records
    }
}

