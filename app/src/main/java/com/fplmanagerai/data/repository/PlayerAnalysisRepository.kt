package com.fplmanagerai.data.repository

import com.fplmanagerai.data.FplApi
import com.fplmanagerai.data.cache.DataCacheStore
import com.fplmanagerai.data.cache.FileBasedDataCache
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.data.model.*
import com.fplmanagerai.data.network.DataResult
import com.fplmanagerai.data.network.FplDataException
import com.fplmanagerai.data.parser.FplDataValidator
import com.fplmanagerai.domain.analysis.ExpectedPointsCalculator
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlayerAnalysisRepository(
    private val api: FplApi = FplApi.create(),
    private val fixtureRepository: FixtureRepository = FixtureRepository(api),
    private val calculator: ExpectedPointsCalculator = ExpectedPointsCalculator(),
    private val cache: DataCacheStore = LocalDataCache.defaultCache
) {

    private var cachedPlayers: List<PlayerModel>? = null
    private var cachedTeams: Map<Int, TeamModel> = emptyMap()
    private var cachedGameweek: GameweekModel? = null
    private var cachedNextGameweek: GameweekModel? = null

    var isLastLoadedFromCache: Boolean = false
        private set

    var lastUpdatedTimestamp: Long = 0L
        private set

    suspend fun loadAndAnalyzeResult(forceRefresh: Boolean = false): DataResult<List<PlayerModel>> = withContext(Dispatchers.IO) {
        try {
            val players = loadAndAnalyze(forceRefresh)
            DataResult.Success(
                data = players,
                isFromCache = isLastLoadedFromCache,
                lastUpdatedMillis = lastUpdatedTimestamp
            )
        } catch (e: Exception) {
            DataResult.Error(
                message = e.localizedMessage ?: "فشل تحميل بيانات الفانتازي",
                cause = e,
                cachedData = cachedPlayers
            )
        }
    }

    suspend fun loadAndAnalyze(forceRefresh: Boolean = false): List<PlayerModel> = withContext(Dispatchers.IO) {
        // 1. Check in-memory first if not forcing refresh
        if (!forceRefresh && cachedPlayers != null) {
            return@withContext cachedPlayers!!
        }

        // 2. Check local persistent cache
        val isCacheFresh = cache.isFresh(FileBasedDataCache.KEY_BOOTSTRAP, FileBasedDataCache.BOOTSTRAP_TTL_MILLIS)
        if (!forceRefresh && isCacheFresh) {
            val cachedJson = cache.read(FileBasedDataCache.KEY_BOOTSTRAP)
            if (cachedJson != null) {
                try {
                    val jsonObj = JsonParser.parseString(cachedJson).asJsonObject
                    FplDataValidator.validateBootstrapStructure(jsonObj)
                    val players = processBootstrapJson(jsonObj, isFromCache = true)
                    if (players.isNotEmpty()) {
                        isLastLoadedFromCache = true
                        lastUpdatedTimestamp = cache.getTimestamp(FileBasedDataCache.KEY_BOOTSTRAP)
                        cachedPlayers = players
                        return@withContext players
                    }
                } catch (_: Exception) {
                    // Fallback to network if cache was corrupt
                }
            }
        }

        // 3. Fetch from Network
        val rawJson: JsonObject = try {
            val fetched = api.getBootstrap()
            FplDataValidator.validateBootstrapStructure(fetched)
            // Save to persistent cache
            cache.save(FileBasedDataCache.KEY_BOOTSTRAP, fetched.toString())
            isLastLoadedFromCache = false
            lastUpdatedTimestamp = System.currentTimeMillis()
            fetched
        } catch (e: Exception) {
            // 4. Network failure: Attempt to load stale cache as emergency fallback
            val staleJson = cache.read(FileBasedDataCache.KEY_BOOTSTRAP)
            if (staleJson != null) {
                try {
                    val jsonObj = JsonParser.parseString(staleJson).asJsonObject
                    FplDataValidator.validateBootstrapStructure(jsonObj)
                    val players = processBootstrapJson(jsonObj, isFromCache = true)
                    if (players.isNotEmpty()) {
                        isLastLoadedFromCache = true
                        lastUpdatedTimestamp = cache.getTimestamp(FileBasedDataCache.KEY_BOOTSTRAP)
                        cachedPlayers = players
                        return@withContext players
                    }
                } catch (_: Exception) {}
            }
            throw FplDataException.NetworkUnavailableException(
                "تعذر الاتصال بخوادم FPL الرسمية، ولا توجد بيانات محلية صالحة: ${e.message}",
                e
            )
        }

        val result = processBootstrapJson(rawJson, isFromCache = false)
        cachedPlayers = result
        result
    }

    private suspend fun processBootstrapJson(json: JsonObject, isFromCache: Boolean): List<PlayerModel> {
        // 1. Teams Map
        val teamsMap = json.getAsJsonArray("teams").associate { item ->
            val obj = item.asJsonObject
            val id = FplDataValidator.safeInt(obj.get("id"))
            val team = TeamModel(
                id = id,
                name = FplDataValidator.safeString(obj.get("name"), default = "Unknown"),
                shortName = FplDataValidator.safeString(obj.get("short_name"), default = "TBD"),
                strength = FplDataValidator.safeInt(obj.get("strength"), default = 3),
                strengthOverallHome = FplDataValidator.safeInt(obj.get("strength_overall_home"), default = 1000),
                strengthOverallAway = FplDataValidator.safeInt(obj.get("strength_overall_away"), default = 1000),
                strengthAttackHome = FplDataValidator.safeInt(obj.get("strength_attack_home"), default = 1000),
                strengthAttackAway = FplDataValidator.safeInt(obj.get("strength_attack_away"), default = 1000),
                strengthDefenceHome = FplDataValidator.safeInt(obj.get("strength_defence_home"), default = 1000),
                strengthDefenceAway = FplDataValidator.safeInt(obj.get("strength_defence_away"), default = 1000)
            )
            id to team
        }
        cachedTeams = teamsMap

        // 2. Gameweeks
        val events = json.getAsJsonArray("events").map { it.asJsonObject }
        val currentEventObj = events.firstOrNull { FplDataValidator.safeBoolean(it.get("is_current")) }
        val nextEventObj = events.firstOrNull { FplDataValidator.safeBoolean(it.get("is_next")) }

        // إذا كانت الجولة الحالية منتهية، فإن الجولة المستهدفة لاتخاذ القرارات هي الجولة القادمة
        val isCurrentFinished = currentEventObj?.let { FplDataValidator.safeBoolean(it.get("finished")) } ?: false
        val activeDecisionEvent = if (isCurrentFinished && nextEventObj != null) {
            nextEventObj
        } else {
            currentEventObj ?: nextEventObj ?: events.firstOrNull()
        }

        fun parseGameweek(ev: JsonObject?): GameweekModel? {
            if (ev == null) return null
            return GameweekModel(
                id = FplDataValidator.safeInt(ev.get("id"), default = 1),
                name = FplDataValidator.safeString(ev.get("name"), default = "Gameweek 1"),
                deadlineTime = FplDataValidator.safeString(ev.get("deadline_time")),
                isPrevious = FplDataValidator.safeBoolean(ev.get("is_previous")),
                isCurrent = FplDataValidator.safeBoolean(ev.get("is_current")),
                isNext = FplDataValidator.safeBoolean(ev.get("is_next")),
                isFinished = FplDataValidator.safeBoolean(ev.get("finished")),
                averageScore = FplDataValidator.safeNullableInt(ev.get("average_entry_score")),
                highestScore = FplDataValidator.safeNullableInt(ev.get("highest_score"))
            )
        }

        val gwModel = parseGameweek(activeDecisionEvent)
            ?: GameweekModel(1, "Gameweek 1", "", isPrevious = false, isCurrent = true, isNext = false, isFinished = false)
        cachedGameweek = gwModel
        cachedNextGameweek = parseGameweek(nextEventObj)

        val currentGwNumber = gwModel.id

        // 3. Preload all upcoming fixtures
        val fixturesList = fixtureRepository.getFixtures(forceRefresh = !isFromCache)

        // 4. Parse elements safely
        val rawElements = json.getAsJsonArray("elements").map { it.asJsonObject }

        val players = rawElements.map { p ->
            val id = FplDataValidator.safeInt(p.get("id"))
            val firstName = FplDataValidator.safeString(p.get("first_name"))
            val secondName = FplDataValidator.safeString(p.get("second_name"))
            val webName = FplDataValidator.safeString(p.get("web_name"), default = "$firstName $secondName")
            val teamId = FplDataValidator.safeInt(p.get("team"))
            val teamObj = teamsMap[teamId]
            val teamName = teamObj?.name ?: "Unknown"
            val teamShortName = teamObj?.shortName ?: "TBD"
            val positionId = FplDataValidator.safeInt(p.get("element_type"), default = 3)
            val position = PlayerPosition.fromId(positionId)
            val rawCost = FplDataValidator.safeInt(p.get("now_cost"), default = 50)
            val price = rawCost / 10.0
            val totalPoints = FplDataValidator.safeInt(p.get("total_points"))
            val form = FplDataValidator.safeDouble(p.get("form")) ?: 0.0
            val selectedByPercent = FplDataValidator.safeDouble(p.get("selected_by_percent")) ?: 0.0
            val minutes = FplDataValidator.safeInt(p.get("minutes"))
            val starts = FplDataValidator.safeNullableInt(p.get("starts")) ?: (minutes / 75)

            val stats = PlayerStatistics(
                goals = FplDataValidator.safeInt(p.get("goals_scored")),
                assists = FplDataValidator.safeInt(p.get("assists")),
                cleanSheets = FplDataValidator.safeInt(p.get("clean_sheets")),
                goalsConceded = FplDataValidator.safeInt(p.get("goals_conceded")),
                saves = FplDataValidator.safeInt(p.get("saves")),
                bonus = FplDataValidator.safeInt(p.get("bonus")),
                bps = FplDataValidator.safeInt(p.get("bps")),
                expectedGoals = FplDataValidator.safeDouble(p.get("expected_goals")),
                expectedAssists = FplDataValidator.safeDouble(p.get("expected_assists")),
                expectedGoalInvolvements = FplDataValidator.safeDouble(p.get("expected_goal_involvements")),
                expectedGoalsConceded = FplDataValidator.safeDouble(p.get("expected_goals_conceded")),
                influence = FplDataValidator.safeDouble(p.get("influence")),
                creativity = FplDataValidator.safeDouble(p.get("creativity")),
                threat = FplDataValidator.safeDouble(p.get("threat")),
                ictIndex = FplDataValidator.safeDouble(p.get("ict_index")),
                pointsPerGame = FplDataValidator.safeDouble(p.get("points_per_game")) ?: 0.0,
                transfersInRound = FplDataValidator.safeInt(p.get("transfers_in_event")),
                transfersOutRound = FplDataValidator.safeInt(p.get("transfers_out_event")),
                yellowCards = FplDataValidator.safeInt(p.get("yellow_cards")),
                redCards = FplDataValidator.safeInt(p.get("red_cards"))
            )

            // Availability
            val status = FplDataValidator.safeString(p.get("status"), default = "a")
            val chance = FplDataValidator.safeNullableInt(p.get("chance_of_playing_next_round"))
            val news = FplDataValidator.safeString(p.get("news"))
            val availability = PlayerAvailability.parse(status, chance, news)

            // Upcoming Fixtures for this player's team
            val teamFixtures = fixturesList
                .filter { it.event != null && it.event >= currentGwNumber && !it.isFinished && it.isTeamInvolved(teamId) }
                .sortedBy { it.event }
                .take(5)
                .map { fix ->
                    val isHome = fix.isHome(teamId)
                    val oppId = fix.getOpponentId(teamId)
                    val oppTeam = teamsMap[oppId]
                    UpcomingFixture(
                        gameweek = fix.event ?: currentGwNumber,
                        opponentId = oppId,
                        opponentName = oppTeam?.name ?: "Unknown",
                        opponentShortName = oppTeam?.shortName ?: "TBD",
                        isHome = isHome,
                        difficulty = fix.getDifficulty(teamId)
                    )
                }

            val basePlayer = PlayerModel(
                id = id,
                webName = webName,
                firstName = firstName,
                secondName = secondName,
                teamId = teamId,
                teamName = teamName,
                teamShortName = teamShortName,
                position = position,
                price = price,
                totalPoints = totalPoints,
                form = form,
                selectedByPercent = selectedByPercent,
                minutesPlayed = minutes,
                starts = starts,
                statistics = stats,
                availability = availability,
                upcomingFixtures = teamFixtures
            )

            val prediction = calculator.calculateExpectedPoints(basePlayer, currentGwNumber)
            basePlayer.copy(prediction = prediction)
        }

        return players
    }

    fun getCachedPlayers(): List<PlayerModel> = cachedPlayers ?: emptyList()

    fun getCachedGameweek(): GameweekModel? = cachedGameweek

    fun getCachedNextGameweek(): GameweekModel? = cachedNextGameweek

    fun getCachedTeams(): Map<Int, TeamModel> = cachedTeams

    fun searchAndFilter(
        query: String = "",
        position: PlayerPosition? = null,
        teamId: Int? = null,
        maxPrice: Double? = null,
        onlyAvailable: Boolean = false
    ): List<PlayerModel> {
        return getCachedPlayers().filter { player ->
            val matchesQuery = query.isBlank() ||
                    player.webName.contains(query, ignoreCase = true) ||
                    player.fullName.contains(query, ignoreCase = true) ||
                    player.teamName.contains(query, ignoreCase = true) ||
                    player.teamShortName.contains(query, ignoreCase = true)

            val matchesPosition = position == null || player.position == position
            val matchesTeam = teamId == null || player.teamId == teamId
            val matchesPrice = maxPrice == null || player.price <= maxPrice
            val matchesAvailable = !onlyAvailable || player.availability.isAvailable

            matchesQuery && matchesPosition && matchesTeam && matchesPrice && matchesAvailable
        }
    }
}
