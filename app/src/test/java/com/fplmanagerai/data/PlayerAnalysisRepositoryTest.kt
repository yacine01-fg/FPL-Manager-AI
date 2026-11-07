package com.fplmanagerai.data

import com.fplmanagerai.data.cache.FileBasedDataCache
import com.fplmanagerai.data.cache.InMemoryDataCache
import com.fplmanagerai.data.network.FplDataException
import com.fplmanagerai.data.repository.FixtureRepository
import com.fplmanagerai.data.repository.PlayerAnalysisRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlayerAnalysisRepositoryTest {

    private fun createValidBootstrapJson(): JsonObject {
        val root = JsonObject()

        // teams
        val teams = JsonArray()
        val team1 = JsonObject()
        team1.addProperty("id", 1)
        team1.addProperty("name", "Arsenal")
        team1.addProperty("short_name", "ARS")
        team1.addProperty("strength", 4)
        team1.addProperty("strength_overall_home", 1200)
        team1.addProperty("strength_overall_away", 1250)
        team1.addProperty("strength_attack_home", 1220)
        team1.addProperty("strength_attack_away", 1280)
        team1.addProperty("strength_defence_home", 1180)
        team1.addProperty("strength_defence_away", 1220)
        teams.add(team1)
        root.add("teams", teams)

        // events
        val events = JsonArray()
        val ev1 = JsonObject()
        ev1.addProperty("id", 1)
        ev1.addProperty("name", "Gameweek 1")
        ev1.addProperty("deadline_time", "2026-08-20T17:30:00Z")
        ev1.addProperty("is_previous", false)
        ev1.addProperty("is_current", true)
        ev1.addProperty("is_next", false)
        ev1.addProperty("finished", false)
        events.add(ev1)
        root.add("events", events)

        // element_types
        val types = JsonArray()
        val type1 = JsonObject()
        type1.addProperty("id", 4)
        type1.addProperty("singular_name_short", "FWD")
        types.add(type1)
        root.add("element_types", types)

        // elements
        val elements = JsonArray()
        val p1 = JsonObject()
        p1.addProperty("id", 100)
        p1.addProperty("web_name", "Saka")
        p1.addProperty("first_name", "Bukayo")
        p1.addProperty("second_name", "Saka")
        p1.addProperty("team", 1)
        p1.addProperty("element_type", 4)
        p1.addProperty("now_cost", 100)
        p1.addProperty("total_points", 80)
        p1.addProperty("form", "7.5")
        p1.addProperty("selected_by_percent", "35.2")
        p1.addProperty("minutes", 900)
        p1.addProperty("goals_scored", 5)
        p1.addProperty("assists", 4)
        p1.addProperty("clean_sheets", 3)
        p1.addProperty("goals_conceded", 8)
        p1.addProperty("bonus", 6)
        p1.addProperty("bps", 180)
        p1.addProperty("expected_goals", "4.8")
        p1.addProperty("expected_assists", "3.9")
        p1.addProperty("status", "a")
        elements.add(p1)
        root.add("elements", elements)

        return root
    }

    class FakeSuccessFplApi(private val bootstrap: JsonObject) : FplApi {
        override suspend fun getBootstrap(): JsonObject = bootstrap
        override suspend fun getFixtures(): JsonArray = JsonArray()
    }

    class FakeFailingFplApi : FplApi {
        override suspend fun getBootstrap(): JsonObject = throw IOException("Network connection lost")
        override suspend fun getFixtures(): JsonArray = throw IOException("Network connection lost")
    }

    @Test
    fun testRepositoryParsesBootstrapAndCaches() = runBlocking {
        val cache = InMemoryDataCache()
        val api = FakeSuccessFplApi(createValidBootstrapJson())
        val fixtureRepo = FixtureRepository(api, cache)
        val repo = PlayerAnalysisRepository(api, fixtureRepo, cache = cache)

        val players = repo.loadAndAnalyze(forceRefresh = true)

        assertEquals(1, players.size)
        val saka = players[0]
        assertEquals("Saka", saka.webName)
        assertEquals(10.0, saka.price, 0.01) // 100 / 10.0 = £10.0m
        assertEquals(80, saka.totalPoints)
        assertEquals("Arsenal", saka.teamName)

        // Verify it was stored in cache
        assertNotNull(cache.read(FileBasedDataCache.KEY_BOOTSTRAP))
        assertFalse(repo.isLastLoadedFromCache)
    }

    @Test
    fun testRepositoryFallsBackToCacheOnNetworkFailure() = runBlocking {
        val cache = InMemoryDataCache()
        val validJson = createValidBootstrapJson()
        cache.save(FileBasedDataCache.KEY_BOOTSTRAP, validJson.toString())

        // API that fails
        val failingApi = FakeFailingFplApi()
        val fixtureRepo = FixtureRepository(failingApi, cache)
        val repo = PlayerAnalysisRepository(failingApi, fixtureRepo, cache = cache)

        // Should not throw, should return cached players
        val players = repo.loadAndAnalyze(forceRefresh = true)

        assertEquals(1, players.size)
        assertEquals("Saka", players[0].webName)
        assertTrue("Must indicate data was loaded from cache", repo.isLastLoadedFromCache)
    }

    @Test
    fun testRepositoryThrowsWhenNetworkFailsAndNoCache() = runBlocking {
        val emptyCache = InMemoryDataCache()
        val failingApi = FakeFailingFplApi()
        val fixtureRepo = FixtureRepository(failingApi, emptyCache)
        val repo = PlayerAnalysisRepository(failingApi, fixtureRepo, cache = emptyCache)

        try {
            repo.loadAndAnalyze(forceRefresh = true)
            fail("Expected NetworkUnavailableException when network fails and no cache")
        } catch (e: FplDataException.NetworkUnavailableException) {
            assertNotNull(e)
        }
    }
}
