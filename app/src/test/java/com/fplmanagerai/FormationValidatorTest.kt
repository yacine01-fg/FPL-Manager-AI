package com.fplmanagerai

import com.fplmanagerai.data.model.*
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.FormationValidator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FormationValidatorTest {

    private lateinit var validator: FormationValidator

    @Before
    fun setUp() {
        validator = FormationValidator()
    }

    private fun mockPlayer(id: Int, teamId: Int, position: PlayerPosition, price: Double): PlayerModel {
        return PlayerModel(
            id = id,
            webName = "P$id",
            firstName = "Player",
            secondName = "$id",
            teamId = teamId,
            teamName = "Team $teamId",
            teamShortName = "T$teamId",
            position = position,
            price = price,
            totalPoints = 50,
            form = 5.0,
            selectedByPercent = 10.0,
            minutesPlayed = 900,
            starts = 10,
            statistics = PlayerStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, null, null, null, null, null, 5.0, 0, 0, 0, 0),
            availability = PlayerAvailability.parse("a", null, null)
        )
    }

    private fun createValid15Squad(budget: Double = 100.0): List<PlayerModel> {
        val squad = mutableListOf<PlayerModel>()
        var id = 1

        // 2 GKP (teams 1, 2)
        squad.add(mockPlayer(id++, 1, PlayerPosition.GKP, 5.0))
        squad.add(mockPlayer(id++, 2, PlayerPosition.GKP, 4.0))

        // 5 DEF (teams 3, 4, 5, 6, 7)
        squad.add(mockPlayer(id++, 3, PlayerPosition.DEF, 5.5))
        squad.add(mockPlayer(id++, 4, PlayerPosition.DEF, 5.0))
        squad.add(mockPlayer(id++, 5, PlayerPosition.DEF, 4.5))
        squad.add(mockPlayer(id++, 6, PlayerPosition.DEF, 4.5))
        squad.add(mockPlayer(id++, 7, PlayerPosition.DEF, 4.0))

        // 5 MID (teams 8, 9, 10, 11, 12)
        squad.add(mockPlayer(id++, 8, PlayerPosition.MID, 8.5))
        squad.add(mockPlayer(id++, 9, PlayerPosition.MID, 8.0))
        squad.add(mockPlayer(id++, 10, PlayerPosition.MID, 7.5))
        squad.add(mockPlayer(id++, 11, PlayerPosition.MID, 6.5))
        squad.add(mockPlayer(id++, 12, PlayerPosition.MID, 5.5))

        // 3 FWD (teams 13, 14, 15)
        squad.add(mockPlayer(id++, 13, PlayerPosition.FWD, 9.0))
        squad.add(mockPlayer(id++, 14, PlayerPosition.FWD, 7.5))
        squad.add(mockPlayer(id++, 15, PlayerPosition.FWD, 6.0))

        return squad
    }

    @Test
    fun testValidSquadPassesValidation() {
        val squad = createValid15Squad()
        val result = validator.validateFullSquad(squad, budgetLimit = 100.0)

        assertTrue("Valid squad must pass validation", result.isValid)
        assertTrue("Errors list should be empty", result.errors.isEmpty())
    }

    @Test
    fun testSquadExceedingBudgetFailsValidation() {
        val squad = createValid15Squad()
        // Budget limit set below squad cost (£81m total cost > £80m limit)
        val result = validator.validateFullSquad(squad, budgetLimit = 75.0)

        assertFalse("Squad over budget must fail validation", result.isValid)
        assertTrue("Error should mention budget", result.errors.any { it.contains("الميزانية") })
    }

    @Test
    fun testMoreThanThreePlayersFromSameClubFails() {
        val squad = createValid15Squad().toMutableList()
        // Replace 4 players so that teamId 1 has 4 players
        squad[0] = mockPlayer(101, teamId = 1, PlayerPosition.GKP, 4.5)
        squad[2] = mockPlayer(102, teamId = 1, PlayerPosition.DEF, 4.5)
        squad[3] = mockPlayer(103, teamId = 1, PlayerPosition.DEF, 4.5)
        squad[4] = mockPlayer(104, teamId = 1, PlayerPosition.DEF, 4.5)

        val result = validator.validateFullSquad(squad, budgetLimit = 100.0)

        assertFalse("More than 3 players per club must fail", result.isValid)
        assertTrue("Error should mention club limit", result.errors.any { it.contains("الحد الأقصى") })
    }

    @Test
    fun testValidFormationsPassLineupValidation() {
        // Test 3-5-2
        val formation352 = Formation(3, 5, 2)
        assertTrue("3-5-2 must be valid", formation352.isValid)

        // Test 3-4-3
        val formation343 = Formation(3, 4, 3)
        assertTrue("3-4-3 must be valid", formation343.isValid)

        // Test 4-4-2
        val formation442 = Formation(4, 4, 2)
        assertTrue("4-4-2 must be valid", formation442.isValid)

        // Test invalid 2-5-3 (only 2 defenders, min is 3)
        val invalidFormation = Formation(2, 5, 3)
        assertFalse("2-5-3 must be invalid (min 3 DEF)", invalidFormation.isValid)
    }
}
