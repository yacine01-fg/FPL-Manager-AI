package com.fplmanagerai.domain.calibration

import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.LocalDataCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelVersionRepository(
    private val cache: DataCacheStore = LocalDataCache.defaultCache,
    private val gson: Gson = Gson()
) {

    companion object {
        const val KEY_MODEL_VERSIONS = "fpl_model_versions_history"
        const val KEY_ACTIVE_MODEL_ID = "fpl_active_model_version_id"
    }

    /**
     * استرجاع النموذج النشط حالياً، أو نموذج خط الأساس الافتراضي إذا لم يُحدد نموذج معاير
     */
    fun getActiveModel(): ModelVersion {
        val activeId = cache.read(KEY_ACTIVE_MODEL_ID) ?: ModelVersion.DEFAULT_BASELINE_ID
        val allVersions = getAllVersions()
        return allVersions.firstOrNull { it.id == activeId } ?: ModelVersion.createBaseline()
    }

    /**
     * استرجاع كافة إصدارات النماذج المحفوظة محلياً مرتبة من الأحدث إلى الأقدم
     */
    fun getAllVersions(): List<ModelVersion> {
        val jsonStr = cache.read(KEY_MODEL_VERSIONS)
        if (jsonStr.isNullOrBlank()) {
            val baseline = ModelVersion.createBaseline()
            saveAllVersions(listOf(baseline))
            return listOf(baseline)
        }
        return try {
            val type = object : TypeToken<List<ModelVersion>>() {}.type
            val list = gson.fromJson<List<ModelVersion>>(jsonStr, type)
            if (list.isNullOrEmpty()) {
                val baseline = ModelVersion.createBaseline()
                saveAllVersions(listOf(baseline))
                listOf(baseline)
            } else {
                list.sortedByDescending { it.createdAt }
            }
        } catch (_: Exception) {
            listOf(ModelVersion.createBaseline())
        }
    }

    /**
     * حفظ أو تحديث إصدار نموذج جديد في السجل الدائم
     */
    suspend fun saveModelVersion(version: ModelVersion) = withContext(Dispatchers.IO) {
        val versions = getAllVersions().toMutableList()
        val index = versions.indexOfFirst { it.id == version.id }
        if (index >= 0) {
            versions[index] = version
        } else {
            versions.add(0, version)
        }
        saveAllVersions(versions)
    }

    /**
     * تفعيل إصدار نموذج محدد
     */
    suspend fun activateVersion(versionId: String): Result<ModelVersion> = withContext(Dispatchers.IO) {
        val all = getAllVersions().toMutableList()
        val target = all.firstOrNull { it.id == versionId }
            ?: return@withContext Result.failure(IllegalArgumentException("لم يتم العثور على الإصدار: $versionId"))

        // تحديث حالات النماذج: النموذج المستهدف يصبح ACTIVE، والنموذج السابق يصبح ARCHIVED (إلا إذا كان Baseline)
        val updatedList = all.map { v ->
            when {
                v.id == versionId -> v.copy(status = if (v.isBaseline) ModelVersionStatus.BASELINE else ModelVersionStatus.ACTIVE)
                v.status == ModelVersionStatus.ACTIVE -> v.copy(status = ModelVersionStatus.ARCHIVED)
                else -> v
            }
        }

        saveAllVersions(updatedList)
        cache.save(KEY_ACTIVE_MODEL_ID, versionId)
        Result.success(target.copy(status = if (target.isBaseline) ModelVersionStatus.BASELINE else ModelVersionStatus.ACTIVE))
    }

    /**
     * استرجاع إصدار سابق (Rollback)
     */
    suspend fun rollbackTo(versionId: String): Result<ModelVersion> = withContext(Dispatchers.IO) {
        val all = getAllVersions().toMutableList()
        val target = all.firstOrNull { it.id == versionId }
            ?: return@withContext Result.failure(IllegalArgumentException("الإصدار المطلوب استرجاعه غير موجود: $versionId"))

        val currentActive = getActiveModel()

        val updatedList = all.map { v ->
            when {
                v.id == currentActive.id && !v.isBaseline -> v.copy(status = ModelVersionStatus.ROLLED_BACK)
                v.id == versionId -> v.copy(status = if (v.isBaseline) ModelVersionStatus.BASELINE else ModelVersionStatus.ACTIVE)
                else -> v
            }
        }

        saveAllVersions(updatedList)
        cache.save(KEY_ACTIVE_MODEL_ID, versionId)
        Result.success(target)
    }

    /**
     * إعادة ضبط فورية إلى نموذج خط الأساس (Baseline)
     */
    suspend fun resetToBaseline(): ModelVersion = withContext(Dispatchers.IO) {
        val all = getAllVersions().map { v ->
            if (v.status == ModelVersionStatus.ACTIVE && !v.isBaseline) {
                v.copy(status = ModelVersionStatus.ARCHIVED)
            } else v
        }
        saveAllVersions(all)
        cache.save(KEY_ACTIVE_MODEL_ID, ModelVersion.DEFAULT_BASELINE_ID)
        ModelVersion.createBaseline()
    }

    private fun saveAllVersions(versions: List<ModelVersion>) {
        val jsonStr = gson.toJson(versions)
        cache.save(KEY_MODEL_VERSIONS, jsonStr)
    }
}

