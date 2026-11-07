package com.fplmanagerai.data.repository

import com.fplmanagerai.data.FplApi
import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.FileBasedDataCache
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.data.model.FixtureModel
import com.fplmanagerai.data.model.TeamModel
import com.fplmanagerai.data.model.UpcomingFixture
import com.fplmanagerai.data.parser.FplDataValidator
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FixtureRepository(
    private val api: FplApi = FplApi.create(),
    private val cache: DataCacheStore = LocalDataCache.defaultCache
) {

    private var inMemoryFixtures: List<FixtureModel>? = null

    suspend fun getFixtures(forceRefresh: Boolean = false): List<FixtureModel> = withContext(Dispatchers.IO) {
        // 1. Check in-memory first if not forcing refresh
        if (!forceRefresh && inMemoryFixtures != null) {
            return@withContext inMemoryFixtures!!
        }

        // 2. Check persistent cache freshness
        val isCacheFresh = cache.isFresh(FileBasedDataCache.KEY_FIXTURES, FileBasedDataCache.FIXTURES_TTL_MILLIS)
        if (!forceRefresh && isCacheFresh) {
            val cachedJson = cache.read(FileBasedDataCache.KEY_FIXTURES)
            if (cachedJson != null) {
                try {
                    val parsed = parseFixturesJson(JsonParser.parseString(cachedJson).asJsonArray)
                    if (parsed.isNotEmpty()) {
                        inMemoryFixtures = parsed
                        return@withContext parsed
                    }
                } catch (_: Exception) {
                    // Fall through to network on corrupt cache
                }
            }
        }

        // 3. Network Fetch
        try {
            val jsonArray: JsonArray = api.getFixtures()
            FplDataValidator.validateFixturesStructure(jsonArray)

            // Save to cache
            cache.save(FileBasedDataCache.KEY_FIXTURES, jsonArray.toString())

            val list = parseFixturesJson(jsonArray)
            inMemoryFixtures = list
            list
        } catch (e: Exception) {
            // 4. Fallback to cache even if stale on network failure
            val staleCache = cache.read(FileBasedDataCache.KEY_FIXTURES)
            if (staleCache != null) {
                try {
                    val parsed = parseFixturesJson(JsonParser.parseString(staleCache).asJsonArray)
                    if (parsed.isNotEmpty()) {
                        inMemoryFixtures = parsed
                        return@withContext parsed
                    }
                } catch (_: Exception) {}
            }
            inMemoryFixtures ?: emptyList()
        }
    }

    private fun parseFixturesJson(jsonArray: JsonArray): List<FixtureModel> {
        val list = mutableListOf<FixtureModel>()
        for (element in jsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject

            val id = FplDataValidator.safeInt(obj.get("id"))
            val event = FplDataValidator.safeNullableInt(obj.get("event"))
            val kickoffTime = if (obj.has("kickoff_time") && !obj.get("kickoff_time").isJsonNull) {
                FplDataValidator.safeString(obj.get("kickoff_time"))
            } else null

            val homeTeamId = FplDataValidator.safeInt(obj.get("team_h"))
            val awayTeamId = FplDataValidator.safeInt(obj.get("team_a"))

            val homeDifficulty = FplDataValidator.safeInt(obj.get("team_h_difficulty"), default = 3)
            val awayDifficulty = FplDataValidator.safeInt(obj.get("team_a_difficulty"), default = 3)

            val isFinished = FplDataValidator.safeBoolean(obj.get("finished"), default = false)
            val isStarted = FplDataValidator.safeBoolean(obj.get("started"), default = false)

            val homeScore = FplDataValidator.safeNullableInt(obj.get("team_h_score"))
            val awayScore = FplDataValidator.safeNullableInt(obj.get("team_a_score"))

            list.add(
                FixtureModel(
                    id = id,
                    event = event,
                    kickoffTime = kickoffTime,
                    homeTeamId = homeTeamId,
                    awayTeamId = awayTeamId,
                    homeDifficulty = homeDifficulty,
                    awayDifficulty = awayDifficulty,
                    isFinished = isFinished,
                    isStarted = isStarted,
                    homeScore = homeScore,
                    awayScore = awayScore
                )
            )
        }
        return list
    }

    suspend fun getUpcomingFixturesForTeam(
        teamId: Int,
        currentGameweek: Int,
        horizon: Int = 5,
        teamsMap: Map<Int, TeamModel>
    ): List<UpcomingFixture> {
        val fixtures = getFixtures()
        return fixtures
            .filter { it.event != null && it.event >= currentGameweek && !it.isFinished && it.isTeamInvolved(teamId) }
            .sortedBy { it.event }
            .take(horizon)
            .map { fixture ->
                val isHome = fixture.isHome(teamId)
                val opponentId = fixture.getOpponentId(teamId)
                val opponent = teamsMap[opponentId]
                val difficulty = fixture.getDifficulty(teamId)

                UpcomingFixture(
                    gameweek = fixture.event ?: currentGameweek,
                    opponentId = opponentId,
                    opponentName = opponent?.name ?: "Unknown",
                    opponentShortName = opponent?.shortName ?: "TBD",
                    isHome = isHome,
                    difficulty = difficulty
                )
            }
    }
}
