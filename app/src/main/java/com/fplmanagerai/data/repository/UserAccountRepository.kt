package com.fplmanagerai.data.repository

import com.fplmanagerai.data.FplApi
import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.data.model.CachedUserSquadPicks
import com.fplmanagerai.data.model.FplAccountProfile
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.parser.FplDataValidator
import com.fplmanagerai.domain.chips.ChipHalf
import com.fplmanagerai.domain.chips.ChipInstance
import com.fplmanagerai.domain.chips.ChipType
import com.fplmanagerai.domain.chips.UserChipHistory
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.OptimizationStrategy
import com.fplmanagerai.domain.team.OptimizedSquad
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class UserAccountRepository(
    private val api: FplApi = FplApi.create(),
    private val cache: DataCacheStore = LocalDataCache.defaultCache,
    private val gson: Gson = Gson()
) {

    companion object {
        const val KEY_USER_TEAM_ID = "fpl_user_team_id"
        const val KEY_ACCOUNT_PROFILE = "fpl_user_profile"
        const val KEY_CHIP_HISTORY = "fpl_user_chip_history"
        const val KEY_USER_SQUAD = "fpl_user_squad"

        const val ACCOUNT_PROFILE_TTL_MILLIS = 30 * 60 * 1000L // 30 دقيقة
        const val CHIP_HISTORY_TTL_MILLIS = 30 * 60 * 1000L // 30 دقيقة
        const val SQUAD_PICKS_TTL_MILLIS = 60 * 60 * 1000L // ساعة
    }

    fun getSavedTeamId(): Int? {
        val raw = cache.read(KEY_USER_TEAM_ID) ?: return null
        return raw.toIntOrNull()
    }

    fun getSavedProfile(): FplAccountProfile? {
        val jsonStr = cache.read(KEY_ACCOUNT_PROFILE) ?: return null
        return try {
            gson.fromJson(jsonStr, FplAccountProfile::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedChipHistory(): UserChipHistory? {
        val jsonStr = cache.read(KEY_CHIP_HISTORY) ?: return null
        return try {
            gson.fromJson(jsonStr, UserChipHistory::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedSquadPicks(): CachedUserSquadPicks? {
        val jsonStr = cache.read(KEY_USER_SQUAD) ?: return null
        return try {
            gson.fromJson(jsonStr, CachedUserSquadPicks::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * مسح شامل لكافة بيانات الحساب والتشكيلة وسجل الشرائح عند فك الربط
     * لضمان عدم اختلاط أو تسرب بيانات مستخدمين مختلفين
     */
    fun unlinkAccount() {
        cache.clear(KEY_USER_TEAM_ID)
        cache.clear(KEY_ACCOUNT_PROFILE)
        cache.clear(KEY_CHIP_HISTORY)
        cache.clear(KEY_USER_SQUAD)
    }

    /**
     * جلب والتحقق من حساب المدرب عبر FPL Team ID من endpoint: /entry/{team_id}/
     */
    suspend fun fetchAccountProfile(teamId: Int): Result<FplAccountProfile> = withContext(Dispatchers.IO) {
        if (teamId <= 0) {
            return@withContext Result.failure(IllegalArgumentException("رقم الفريق (Team ID) يجب أن يكون رقماً موجباً صحيحاً."))
        }

        try {
            val response: JsonObject = api.getEntry(teamId)
            val id = FplDataValidator.safeInt(response.get("id"))
            if (id == 0) {
                return@withContext Result.failure(IllegalArgumentException("لم يتم العثور على فريق برقم $teamId في خوادم الفانتازي."))
            }

            val teamName = FplDataValidator.safeString(response.get("name"), default = "فريق FPL")
            val firstName = FplDataValidator.safeString(response.get("player_first_name"))
            val lastName = FplDataValidator.safeString(response.get("player_last_name"))
            val overallPoints = FplDataValidator.safeInt(response.get("summary_overall_points"))
            val overallRank = FplDataValidator.safeNullableInt(response.get("summary_overall_rank"))
            val currentGw = FplDataValidator.safeInt(response.get("current_event"), default = 1)

            val rawBank = FplDataValidator.safeInt(response.get("last_deadline_bank"), default = 0)
            val rawValue = FplDataValidator.safeInt(response.get("last_deadline_value"), default = 1000)
            val totalTransfers = FplDataValidator.safeInt(response.get("last_deadline_total_transfers"), default = 0)

            val profile = FplAccountProfile(
                teamId = id,
                teamName = teamName,
                playerFirstName = firstName,
                playerLastName = lastName,
                overallPoints = overallPoints,
                overallRank = overallRank,
                currentGameweek = currentGw,
                bank = rawBank / 10.0,
                teamValue = rawValue / 10.0,
                totalTransfers = totalTransfers,
                isLinked = true
            )

            // حفظ محلياً
            cache.save(KEY_USER_TEAM_ID, id.toString())
            cache.save(KEY_ACCOUNT_PROFILE, gson.toJson(profile))

            Result.success(profile)
        } catch (e: HttpException) {
            val msg = if (e.code() == 404) {
                "لم يتم العثور على فريق برقم $teamId في موقع الفانتازي الرسمي. يرجى التأكد من الرقم والمحاولة مجدداً."
            } else {
                "خطأ في خوادم FPL (${e.code()}): ${e.message()}"
            }
            Result.failure(Exception(msg, e))
        } catch (e: IOException) {
            android.util.Log.e("UserAccountRepo", "fetchAccountProfile IOException: ${e.javaClass.name} - ${e.message}", e)
            // محاولة استرجاع الملف التعريفي المحفوظ عند انقطاع الاتصال
            val cachedProfile = getSavedProfile()
            if (cachedProfile != null && cachedProfile.teamId == teamId) {
                Result.success(cachedProfile)
            } else {
                Result.failure(Exception("تعذر الاتصال بالإنترنت أو بخوادم Fantasy Premier League الرسمية.", e))
            }
        } catch (e: Exception) {
            Result.failure(Exception("حدث خطأ أثناء جلب بيانات الحساب: ${e.localizedMessage ?: "خطأ غير معروف"}", e))
        }
    }

    /**
     * استخراج وتدقيق سجل الشرائح المستخدمة من endpoint: /entry/{team_id}/history/
     */
    suspend fun fetchChipHistory(teamId: Int, currentGameweek: Int): Result<UserChipHistory> = withContext(Dispatchers.IO) {
        try {
            val historyJson = api.getEntryHistory(teamId)
            val chipsArray = if (historyJson.has("chips") && historyJson.get("chips").isJsonArray) {
                historyJson.getAsJsonArray("chips")
            } else null

            val set1Used = mutableSetOf<ChipType>()
            val set2Used = mutableSetOf<ChipType>()
            val playedMap = mutableMapOf<ChipInstance, Int>()
            var activeThisGw: ChipType? = null
            var activeGw: Int? = null
            var lastFreeHitGw: Int? = null

            if (chipsArray != null) {
                for (element in chipsArray) {
                    if (!element.isJsonObject) continue
                    val obj = element.asJsonObject
                    val chipName = FplDataValidator.safeString(obj.get("name")).lowercase()
                    val event = FplDataValidator.safeInt(obj.get("event"))

                    val chipType = when (chipName) {
                        "3xc" -> ChipType.TRIPLE_CAPTAIN
                        "bboost" -> ChipType.BENCH_BOOST
                        "freehit" -> ChipType.FREE_HIT
                        "wildcard" -> ChipType.WILDCARD
                        else -> null
                    }

                    if (chipType != null && event > 0) {
                        val half = ChipHalf.fromGameweek(event)
                        val instance = ChipInstance(chipType, half)
                        playedMap[instance] = event

                        if (half == ChipHalf.FIRST_HALF) {
                            set1Used.add(chipType)
                        } else {
                            set2Used.add(chipType)
                        }

                        if (chipType == ChipType.FREE_HIT) {
                            lastFreeHitGw = event
                        }

                        if (event == currentGameweek) {
                            activeThisGw = chipType
                            activeGw = event
                        }
                    }
                }
            }

            // حساب التبديلات المجانية المخزنة (Banked Transfers)
            val currentTransfers = if (historyJson.has("current") && historyJson.get("current").isJsonArray) {
                val currentArray = historyJson.getAsJsonArray("current")
                val lastEntry = currentArray.lastOrNull()?.asJsonObject
                val eventTransfers = FplDataValidator.safeInt(lastEntry?.get("event_transfers"), default = 0)
                if (eventTransfers == 0) 2 else 1
            } else 1

            val userHistory = UserChipHistory(
                usedChips = set1Used + set2Used,
                firstHalfWildcardUsed = ChipType.WILDCARD in set1Used,
                secondHalfWildcardUsed = ChipType.WILDCARD in set2Used,
                set1UsedChips = set1Used,
                set2UsedChips = set2Used,
                chipPlayedGameweekMap = playedMap,
                activeChipThisGw = activeThisGw,
                activeChipGameweek = activeGw,
                lastUsedFreeHitGw = lastFreeHitGw,
                bankedFreeTransfers = currentTransfers.coerceIn(1, 5),
                isLinkedToAccount = true,
                linkedTeamId = teamId,
                isChipHistoryConfirmed = true
            )

            // حفظ سجل الشرائح محلياً لضمان عدم فقدانه عند انقطاع الشبكة
            cache.save(KEY_CHIP_HISTORY, gson.toJson(userHistory))

            Result.success(userHistory)
        } catch (e: Exception) {
            // محاولة استخدام السجل المخزن محلياً إذا كان يخص نفس الفريق
            val cachedHistory = getSavedChipHistory()
            if (cachedHistory != null && cachedHistory.linkedTeamId == teamId) {
                Result.success(cachedHistory)
            } else {
                // فشل الجلب ولا يوجد كاش موثق: نرجع فشلاً حتى لا تتحول الرقاقات إلى AVAILABLE بالخطأ
                Result.failure(Exception("تعذر استخراج سجل الشرائح من خوادم FPL: ${e.localizedMessage ?: "خطأ في الشبكة"}", e))
            }
        }
    }

    /**
     * استيراد التشكيلة الحقيقية للمدرب من /entry/{team_id}/event/{gameweek}/picks/
     */
    suspend fun fetchUserSquad(
        teamId: Int,
        currentGameweek: Int,
        allPlayers: List<PlayerModel>
    ): Result<OptimizedSquad?> = withContext(Dispatchers.IO) {
        if (allPlayers.isEmpty()) {
            return@withContext Result.success(null)
        }

        // محاولة جلب تشكيلة الجولة الحالية، وإن لم تُنشر بعد (قبل الديدلاين الرسمي يرجع 404)، نسحب تشكيلة آخر جولة مؤكدة
        val targetGw = currentGameweek.coerceAtLeast(1)
        var sourceGw = targetGw
        var isPreviousGw = false

        val picksJson = try {
            api.getEntryPicks(teamId, targetGw)
        } catch (e: HttpException) {
            if (e.code() == 404 && targetGw > 1) {
                try {
                    sourceGw = targetGw - 1
                    isPreviousGw = true
                    api.getEntryPicks(teamId, targetGw - 1)
                } catch (_: Exception) {
                    return@withContext reconstructSquadFromCache(teamId, allPlayers)
                }
            } else {
                return@withContext reconstructSquadFromCache(teamId, allPlayers)
            }
        } catch (_: Exception) {
            return@withContext reconstructSquadFromCache(teamId, allPlayers)
        }

        try {
            val picksArray = if (picksJson.has("picks") && picksJson.get("picks").isJsonArray) {
                picksJson.getAsJsonArray("picks")
            } else {
                return@withContext reconstructSquadFromCache(teamId, allPlayers)
            }

            val playersMap = allPlayers.associateBy { it.id }

            val startingPicks = mutableListOf<Pair<Int, PlayerModel>>()
            val benchPicks = mutableListOf<Pair<Int, PlayerModel>>()
            val purchasePrices = mutableMapOf<Int, Double>()
            val sellingPrices = mutableMapOf<Int, Double>()
            val multipliers = mutableMapOf<Int, Int>()
            val missingPlayerIds = mutableListOf<Int>()
            var captainPlayer: PlayerModel? = null
            var viceCaptainPlayer: PlayerModel? = null

            for (element in picksArray) {
                if (!element.isJsonObject) continue
                val pickObj = element.asJsonObject
                val elementId = FplDataValidator.safeInt(pickObj.get("element"))
                val position = FplDataValidator.safeInt(pickObj.get("position")) // 1..15
                val isCaptain = FplDataValidator.safeBoolean(pickObj.get("is_captain"))
                val isViceCaptain = FplDataValidator.safeBoolean(pickObj.get("is_vice_captain"))
                val multiplier = FplDataValidator.safeInt(pickObj.get("multiplier"), default = 1)

                val rawPurchasePrice = FplDataValidator.safeNullableInt(pickObj.get("purchase_price"))
                val purchasePrice = rawPurchasePrice?.let { it / 10.0 }
                val rawSellingPrice = FplDataValidator.safeNullableInt(pickObj.get("selling_price"))
                val sellingPrice = rawSellingPrice?.let { it / 10.0 }

                if (purchasePrice != null) purchasePrices[elementId] = purchasePrice
                if (sellingPrice != null) sellingPrices[elementId] = sellingPrice
                multipliers[elementId] = multiplier

                val basePlayer = playersMap[elementId]
                if (basePlayer == null) {
                    missingPlayerIds.add(elementId)
                    continue
                }

                // دمج بيانات الاختيار الحية من Picks داخل كائن اللاعب
                val enrichedPlayer = basePlayer.copy(
                    purchasePrice = purchasePrice,
                    sellingPrice = sellingPrice,
                    fplPosition = position,
                    fplMultiplier = multiplier,
                    isFplCaptain = isCaptain,
                    isFplViceCaptain = isViceCaptain
                )

                if (position in 1..11) {
                    startingPicks.add(position to enrichedPlayer)
                } else if (position in 12..15) {
                    benchPicks.add(position to enrichedPlayer)
                }

                if (isCaptain) {
                    captainPlayer = enrichedPlayer
                } else if (multiplier >= 2 && captainPlayer == null) {
                    captainPlayer = enrichedPlayer
                }

                if (isViceCaptain) {
                    viceCaptainPlayer = enrichedPlayer
                }
            }

            // إذا تعذر العثور على أي لاعب، لا نستبدله بلاعب آخر عشوائي
            if (missingPlayerIds.isNotEmpty()) {
                val cached = reconstructSquadFromCache(teamId, allPlayers)
                if (cached.getOrNull() != null) return@withContext cached
                return@withContext Result.failure(
                    IllegalStateException("تعذر مطابقة بعض لاعبي التشكيلة (${missingPlayerIds.joinToString()}) مع قاعدة بيانات الموسم.")
                )
            }

            // التحقق من اكتمال التشكيلة (11 أساسياً و4 بدلاء)
            if (startingPicks.size != 11 || benchPicks.size != 4) {
                return@withContext reconstructSquadFromCache(teamId, allPlayers)
            }

            // ترتيب دكة البدلاء الصارم وفق الترتيب الأصلي في FPL دون إعادة ترتيب بالتوقعات
            // position 12 (حارس بديل)، 13 (بديل 1)، 14 (بديل 2)، 15 (بديل 3)
            benchPicks.sortBy { it.first }
            val benchList = benchPicks.map { it.second }

            // فرز التشكيلة الأساسية للعرض التكتيكي حسب الخطوط (حارس -> دفاع -> وسط -> هجوم)
            val startingList = startingPicks.map { it.second }.sortedWith(
                compareBy<PlayerModel> {
                    when (it.position) {
                        PlayerPosition.GKP -> 1
                        PlayerPosition.DEF -> 2
                        PlayerPosition.MID -> 3
                        PlayerPosition.FWD -> 4
                    }
                }
            )

            // التأكد من أن الكابتن ضمن الأساسيين
            val cap = if (captainPlayer != null && startingList.any { it.id == captainPlayer.id }) {
                captainPlayer
            } else {
                startingList.maxByOrNull { it.prediction?.expectedPoints ?: 0.0 } ?: startingList.first()
            }

            // التأكد من أن نائب الكابتن مختلف تماماً عن الكابتن وضمن الأساسيين
            val vCap = if (viceCaptainPlayer != null && viceCaptainPlayer.id != cap.id && startingList.any { it.id == viceCaptainPlayer.id }) {
                viceCaptainPlayer
            } else {
                startingList.filter { it.id != cap.id }.maxByOrNull { it.prediction?.expectedPoints ?: 0.0 }
                    ?: startingList.firstOrNull { it.id != cap.id }
                    ?: startingList.getOrElse(1) { cap }
            }

            // استخراج الميزانية من entry_history
            val entryHistory = if (picksJson.has("entry_history") && picksJson.get("entry_history").isJsonObject) {
                picksJson.getAsJsonObject("entry_history")
            } else null
            val rawBank = FplDataValidator.safeInt(entryHistory?.get("bank"), default = 0)
            val bankM = rawBank / 10.0

            val defCount = startingList.count { it.position == PlayerPosition.DEF }
            val midCount = startingList.count { it.position == PlayerPosition.MID }
            val fwdCount = startingList.count { it.position == PlayerPosition.FWD }
            val formation = Formation(defCount, midCount, fwdCount)

            val totalCost = (startingList + benchList).sumOf { it.price }
            val startingXpts = startingList.sumOf { it.prediction?.expectedPoints ?: 0.0 }
            val capXpts = cap.prediction?.expectedPoints ?: 0.0
            val benchXpts = benchList.sumOf { it.prediction?.expectedPoints ?: 0.0 }

            val summary = if (isPreviousGw) {
                "تشكيلة الجولة $sourceGw (آخر جولة مؤكدة في FPL - لم يبدأ موعد الجولة $targetGw بعد)."
            } else {
                "تشكيلة الجولة $sourceGw الرسمية المعتمدة من حسابك في FPL."
            }

            val squad = OptimizedSquad(
                starting11 = startingList,
                bench = benchList,
                captain = cap,
                viceCaptain = vCap,
                formation = formation,
                totalCost = Math.round(totalCost * 10.0) / 10.0,
                remainingBudget = bankM,
                projectedStartingPoints = Math.round(startingXpts * 10.0) / 10.0,
                projectedCaptainPoints = Math.round(capXpts * 10.0) / 10.0,
                projectedBenchPoints = Math.round(benchXpts * 10.0) / 10.0,
                strategy = OptimizationStrategy.BALANCED,
                horizon = 1,
                summaryAr = summary,
                sourceGameweek = sourceGw,
                isFromPreviousGameweek = isPreviousGw
            )

            // حفظ التشكيلة في التخزين المؤقت المحلي للعمل دون اتصال
            val cachedPicks = CachedUserSquadPicks(
                teamId = teamId,
                sourceGameweek = sourceGw,
                isPreviousGameweek = isPreviousGw,
                startingElementIds = startingList.map { it.id },
                benchElementIds = benchList.map { it.id },
                captainId = cap.id,
                viceCaptainId = vCap.id,
                bank = bankM,
                purchasePricesMap = purchasePrices,
                sellingPricesMap = sellingPrices,
                multipliersMap = multipliers
            )
            cache.save(KEY_USER_SQUAD, gson.toJson(cachedPicks))

            Result.success(squad)
        } catch (_: Exception) {
            reconstructSquadFromCache(teamId, allPlayers)
        }
    }

    /**
     * إعادة بناء تشكيلة الفريق من الكاش المحلي عند انقطاع الإنترنت
     */
    private fun reconstructSquadFromCache(
        teamId: Int,
        allPlayers: List<PlayerModel>
    ): Result<OptimizedSquad?> {
        val cached = getSavedSquadPicks()
        if (cached == null || cached.teamId != teamId) {
            return Result.success(null)
        }

        val playersMap = allPlayers.associateBy { it.id }
        val missingIds = (cached.startingElementIds + cached.benchElementIds).filter { it !in playersMap }
        if (missingIds.isNotEmpty()) {
            return Result.success(null)
        }

        val startingList = cached.startingElementIds.mapIndexedNotNull { idx, id ->
            val p = playersMap[id] ?: return@mapIndexedNotNull null
            p.copy(
                purchasePrice = cached.purchasePricesMap[id],
                sellingPrice = cached.sellingPricesMap[id],
                fplPosition = idx + 1,
                fplMultiplier = cached.multipliersMap[id] ?: 1,
                isFplCaptain = id == cached.captainId,
                isFplViceCaptain = id == cached.viceCaptainId
            )
        }.sortedWith(
            compareBy<PlayerModel> {
                when (it.position) {
                    PlayerPosition.GKP -> 1
                    PlayerPosition.DEF -> 2
                    PlayerPosition.MID -> 3
                    PlayerPosition.FWD -> 4
                }
            }
        )

        // الحفاظ التام على ترتيب البدلاء (12 حارس، 13..15 بدلاء الملعب)
        val benchList = cached.benchElementIds.mapIndexedNotNull { idx, id ->
            val p = playersMap[id] ?: return@mapIndexedNotNull null
            p.copy(
                purchasePrice = cached.purchasePricesMap[id],
                sellingPrice = cached.sellingPricesMap[id],
                fplPosition = 12 + idx,
                fplMultiplier = 0,
                isFplCaptain = false,
                isFplViceCaptain = false
            )
        }

        if (startingList.size != 11 || benchList.size != 4) {
            return Result.success(null)
        }

        val cap = startingList.firstOrNull { it.id == cached.captainId } ?: startingList.first()
        val vCap = startingList.firstOrNull { it.id == cached.viceCaptainId && it.id != cap.id }
            ?: startingList.firstOrNull { it.id != cap.id }
            ?: startingList.getOrElse(1) { cap }

        val defCount = startingList.count { it.position == PlayerPosition.DEF }
        val midCount = startingList.count { it.position == PlayerPosition.MID }
        val fwdCount = startingList.count { it.position == PlayerPosition.FWD }
        val formation = Formation(defCount, midCount, fwdCount)

        val totalCost = (startingList + benchList).sumOf { it.price }
        val startingXpts = startingList.sumOf { it.prediction?.expectedPoints ?: 0.0 }
        val capXpts = cap.prediction?.expectedPoints ?: 0.0
        val benchXpts = benchList.sumOf { it.prediction?.expectedPoints ?: 0.0 }

        val summary = if (cached.isPreviousGameweek) {
            "تشكيلة الجولة ${cached.sourceGameweek} (محفوظة محلياً - آخر جولة مؤكدة)."
        } else {
            "تشكيلة الجولة ${cached.sourceGameweek} الرسمية (محفوظة محلياً دون اتصال)."
        }

        val squad = OptimizedSquad(
            starting11 = startingList,
            bench = benchList,
            captain = cap,
            viceCaptain = vCap,
            formation = formation,
            totalCost = Math.round(totalCost * 10.0) / 10.0,
            remainingBudget = cached.bank,
            projectedStartingPoints = Math.round(startingXpts * 10.0) / 10.0,
            projectedCaptainPoints = Math.round(capXpts * 10.0) / 10.0,
            projectedBenchPoints = Math.round(benchXpts * 10.0) / 10.0,
            strategy = OptimizationStrategy.BALANCED,
            horizon = 1,
            summaryAr = summary,
            sourceGameweek = cached.sourceGameweek,
            isFromPreviousGameweek = cached.isPreviousGameweek
        )

        return Result.success(squad)
    }
}
