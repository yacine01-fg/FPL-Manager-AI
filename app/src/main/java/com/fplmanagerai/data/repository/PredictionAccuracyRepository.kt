package com.fplmanagerai.data.repository

import com.fplmanagerai.data.FplApi
import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.data.model.*
import com.fplmanagerai.data.parser.FplDataValidator
import com.fplmanagerai.domain.calibration.ModelVersionRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class PredictionAccuracyRepository(
    private val api: FplApi = FplApi.create(),
    private val cache: DataCacheStore = LocalDataCache.defaultCache,
    private val gson: Gson = Gson(),
    private val modelVersionRepository: ModelVersionRepository = ModelVersionRepository()
) {

    companion object {
        const val KEY_PREDICTION_SNAPSHOTS = "fpl_accuracy_prediction_snapshots"
        const val PREFIX_ACTUAL_RESULTS = "fpl_accuracy_actual_results_gw_"
        const val DEFAULT_MODEL_VERSION = "v2.0-engine"
    }

    /**
     * حفظ لقطات التوقع قبل انطلاق الجولة.
     * تطبق مبدأ اللقطة غير القابلة للتعديل (Immutable): لا يتم استبدال أي لقطة محفوظة مسبقاً لنفس اللاعب والجولة.
     */
    suspend fun saveSnapshots(
        gameweek: Int,
        players: List<PlayerModel>,
        startingPlayerIds: Set<Int> = emptySet(),
        captainId: Int? = null,
        viceCaptainId: Int? = null,
        modelVersion: String = DEFAULT_MODEL_VERSION
    ): Int = withContext(Dispatchers.IO) {
        if (gameweek <= 0 || players.isEmpty()) return@withContext 0

        // تحديد إصدار النموذج الفعلي النشط لحظة إنشاء التوقع
        val effectiveModelVersion = if (modelVersion == DEFAULT_MODEL_VERSION) {
            modelVersionRepository.getActiveModel().id
        } else {
            modelVersion
        }

        val existingSnapshots = getAllSnapshots().associateBy { it.id }.toMutableMap()
        var addedCount = 0

        for (player in players) {
            val key = PredictionSnapshot.createKey(player.id, gameweek, effectiveModelVersion)
            // إذا كانت اللقطة موجودة مسبقاً، نمنع التعديل منعاً للتلاعب بالتوقعات السابقة
            if (existingSnapshots.containsKey(key)) {
                continue
            }

            val pred = player.prediction
            val expectedPts = pred?.expectedPoints ?: (player.totalPoints / 10.0)
            val expectedMins = pred?.expectedMinutes ?: (if (player.availability.isAvailable) 75.0 else 0.0)
            val startProb = pred?.startProbability ?: (if (player.availability.isAvailable) 0.85 else 0.0)
            val confScore = pred?.confidenceScore ?: 70.0
            val relScore = pred?.reliabilityScore ?: 75.0
            val risk = pred?.riskLevel ?: if (player.availability.isAvailable) RiskLevel.LOW else RiskLevel.CRITICAL
            val rec = pred?.recommendation ?: TransferRecommendation.HOLD
            // خط الأساس للمقارنة (Baseline): معدل النقاط لكل مباراة أو الفورمة
            val baseline = player.statistics.pointsPerGame.takeIf { it > 0.0 } ?: player.form.takeIf { it > 0.0 } ?: (player.totalPoints / 10.0)

            val snapshot = PredictionSnapshot(
                id = key,
                playerId = player.id,
                playerName = player.webName,
                playerTeam = player.teamShortName,
                position = player.position,
                gameweek = gameweek,
                expectedPoints = Math.round(expectedPts * 10.0) / 10.0,
                expectedMinutes = Math.round(expectedMins * 10.0) / 10.0,
                startProbability = Math.round(startProb * 100.0) / 100.0,
                confidenceScore = Math.round(confScore * 10.0) / 10.0,
                reliabilityScore = Math.round(relScore * 10.0) / 10.0,
                riskLevel = risk,
                recommendation = rec,
                baselinePoints = Math.round(baseline * 10.0) / 10.0,
                isStarter = player.id in startingPlayerIds,
                isCaptain = player.id == captainId,
                isViceCaptain = player.id == viceCaptainId,
                modelVersion = effectiveModelVersion,
                snapshotTimestamp = System.currentTimeMillis()
            )

            existingSnapshots[key] = snapshot
            addedCount++
        }

        if (addedCount > 0) {
            val jsonStr = gson.toJson(existingSnapshots.values.toList())
            cache.save(KEY_PREDICTION_SNAPSHOTS, jsonStr)
        }

        addedCount
    }

    /**
     * استرجاع جميع اللقطات التاريخية المحفوظة محلياً.
     */
    fun getAllSnapshots(): List<PredictionSnapshot> {
        val rawJson = cache.read(KEY_PREDICTION_SNAPSHOTS) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PredictionSnapshot>>() {}.type
            gson.fromJson<List<PredictionSnapshot>>(rawJson, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * استرجاع لقطات جولة محددة.
     */
    fun getSnapshotsForGameweek(gameweek: Int): List<PredictionSnapshot> {
        return getAllSnapshots().filter { it.gameweek == gameweek }
    }

    /**
     * جلب النتائج الفعلية من FPL API للجولة المكتملة عبر endpoint: /event/{gameweek}/live/
     */
    suspend fun fetchAndStoreActualResults(gameweek: Int): Result<List<ActualGameweekPlayerResult>> = withContext(Dispatchers.IO) {
        if (gameweek <= 0) {
            return@withContext Result.failure(IllegalArgumentException("رقم الجولة غير صالح: $gameweek"))
        }

        val cacheKey = "$PREFIX_ACTUAL_RESULTS$gameweek"

        try {
            val response: JsonObject = api.getEventLive(gameweek)
            val elementsArray = if (response.has("elements") && response.get("elements").isJsonArray) {
                response.getAsJsonArray("elements")
            } else {
                return@withContext loadCachedActualResults(gameweek)
            }

            val results = mutableListOf<ActualGameweekPlayerResult>()

            for (element in elementsArray) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                val elementId = FplDataValidator.safeInt(obj.get("id"))
                if (elementId == 0) continue

                val statsObj = if (obj.has("stats") && obj.get("stats").isJsonObject) {
                    obj.getAsJsonObject("stats")
                } else null

                val totalPoints = FplDataValidator.safeInt(statsObj?.get("total_points"), default = 0)
                val minutes = FplDataValidator.safeInt(statsObj?.get("minutes"), default = 0)
                val starts = FplDataValidator.safeInt(statsObj?.get("starts"), default = if (minutes >= 60) 1 else 0)
                val started = starts > 0 || minutes >= 60

                val goals = FplDataValidator.safeInt(statsObj?.get("goals_scored"), default = 0)
                val assists = FplDataValidator.safeInt(statsObj?.get("assists"), default = 0)
                val cleanSheets = FplDataValidator.safeInt(statsObj?.get("clean_sheets"), default = 0)
                val goalsConceded = FplDataValidator.safeInt(statsObj?.get("goals_conceded"), default = 0)
                val bonus = FplDataValidator.safeInt(statsObj?.get("bonus"), default = 0)
                val bps = FplDataValidator.safeInt(statsObj?.get("bps"), default = 0)
                val yellowCards = FplDataValidator.safeInt(statsObj?.get("yellow_cards"), default = 0)
                val redCards = FplDataValidator.safeInt(statsObj?.get("red_cards"), default = 0)

                val resultItem = ActualGameweekPlayerResult(
                    playerId = elementId,
                    gameweek = gameweek,
                    actualPoints = totalPoints,
                    actualMinutes = minutes,
                    started = started,
                    goals = goals,
                    assists = assists,
                    cleanSheets = cleanSheets,
                    goalsConceded = goalsConceded,
                    bonus = bonus,
                    bps = bps,
                    yellowCards = yellowCards,
                    redCards = redCards,
                    isGameweekFinished = true,
                    isPostponedOrBlank = (minutes == 0 && totalPoints == 0)
                )

                results.add(resultItem)
            }

            if (results.isNotEmpty()) {
                val jsonStr = gson.toJson(results)
                cache.save(cacheKey, jsonStr)
            }

            Result.success(results)
        } catch (e: HttpException) {
            val cached = loadCachedActualResults(gameweek)
            if (cached.isSuccess) return@withContext cached
            Result.failure(Exception("خطأ في خوادم الفانتازي (${e.code()}) أثناء جلب نتائج الجولة $gameweek: ${e.message()}", e))
        } catch (e: IOException) {
            val cached = loadCachedActualResults(gameweek)
            if (cached.isSuccess) return@withContext cached
            Result.failure(Exception("تعذر الاتصال بالإنترنت لجلب نتائج الجولة $gameweek الرسمية.", e))
        } catch (e: Exception) {
            val cached = loadCachedActualResults(gameweek)
            if (cached.isSuccess) return@withContext cached
            Result.failure(Exception("حدث خطأ أثناء معالجة نتائج الجولة الفعلية: ${e.localizedMessage}", e))
        }
    }

    /**
     * استرجاع النتائج الفعلية المخزنة محلياً لجولة معينة.
     */
    fun loadCachedActualResults(gameweek: Int): Result<List<ActualGameweekPlayerResult>> {
        val cacheKey = "$PREFIX_ACTUAL_RESULTS$gameweek"
        val rawJson = cache.read(cacheKey)
        if (rawJson != null) {
            try {
                val type = object : TypeToken<List<ActualGameweekPlayerResult>>() {}.type
                val list = gson.fromJson<List<ActualGameweekPlayerResult>>(rawJson, type)
                if (!list.isNullOrEmpty()) {
                    return Result.success(list)
                }
            } catch (_: Exception) {}
        }
        return Result.failure(IllegalStateException("لا توجد نتائج فعلية محفوظة محلياً للجولة $gameweek."))
    }

    /**
     * قائمة الجولات التي تحتوي على لقطات توقع أو نتائج فعلية.
     */
    fun getAvailableGameweeks(): List<Int> {
        val snapshotGws = getAllSnapshots().map { it.gameweek }
        return snapshotGws.distinct().sorted()
    }
}

