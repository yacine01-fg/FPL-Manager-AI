package com.fplmanagerai.data

import com.fplmanagerai.data.network.FplDataException
import com.fplmanagerai.data.parser.FplDataValidator
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class FplDataValidatorTest {

    @Test
    fun testSafeDoubleParsing() {
        assertEquals(12.5, FplDataValidator.safeDouble(JsonPrimitive(12.5))!!, 0.001)
        assertEquals(8.4, FplDataValidator.safeDouble(JsonPrimitive("8.4"))!!, 0.001)
        assertNull(FplDataValidator.safeDouble(null))
        assertNull(FplDataValidator.safeDouble(JsonNull.INSTANCE))
        assertNull(FplDataValidator.safeDouble(JsonPrimitive("")))
        assertNull(FplDataValidator.safeDouble(JsonPrimitive("None")))
        assertNull(FplDataValidator.safeDouble(JsonPrimitive("null")))
        assertNull(FplDataValidator.safeDouble(JsonPrimitive("invalid_string")))
    }

    @Test
    fun testSafeIntParsing() {
        assertEquals(5, FplDataValidator.safeInt(JsonPrimitive(5)))
        assertEquals(7, FplDataValidator.safeInt(JsonPrimitive("7")))
        assertEquals(0, FplDataValidator.safeInt(null))
        assertEquals(10, FplDataValidator.safeInt(null, default = 10))
        assertEquals(0, FplDataValidator.safeInt(JsonPrimitive("")))
        assertEquals(0, FplDataValidator.safeInt(JsonPrimitive("invalid")))
    }

    @Test
    fun testSafeBooleanParsing() {
        assertTrue(FplDataValidator.safeBoolean(JsonPrimitive(true)))
        assertTrue(FplDataValidator.safeBoolean(JsonPrimitive("true")))
        assertTrue(FplDataValidator.safeBoolean(JsonPrimitive("1")))
        assertTrue(FplDataValidator.safeBoolean(JsonPrimitive(1)))
        assertFalse(FplDataValidator.safeBoolean(JsonPrimitive(false)))
        assertFalse(FplDataValidator.safeBoolean(JsonPrimitive("false")))
        assertFalse(FplDataValidator.safeBoolean(null))
    }

    @Test
    fun testValidateBootstrapStructureThrowsOnMissingElements() {
        val json = JsonObject()
        json.add("teams", JsonArray())
        json.add("events", JsonArray())
        json.add("element_types", JsonArray())

        try {
            FplDataValidator.validateBootstrapStructure(json)
            fail("Expected MalformedResponseException for missing 'elements'")
        } catch (e: FplDataException.MalformedResponseException) {
            assertTrue(e.message?.contains("elements") == true)
        }
    }

    @Test
    fun testValidateBootstrapStructureThrowsOnEmptyElements() {
        val json = JsonObject()
        json.add("elements", JsonArray())
        json.add("teams", JsonArray())
        json.add("events", JsonArray())
        json.add("element_types", JsonArray())

        try {
            FplDataValidator.validateBootstrapStructure(json)
            fail("Expected MalformedResponseException for empty elements array")
        } catch (e: FplDataException.MalformedResponseException) {
            assertTrue(e.message?.contains("فارغة") == true)
        }
    }

    @Test
    fun testValidateFixturesStructure() {
        val emptyArr = JsonArray()
        try {
            FplDataValidator.validateFixturesStructure(emptyArr)
            fail("Expected MalformedResponseException on empty fixtures")
        } catch (e: FplDataException.MalformedResponseException) {
            assertNotNull(e)
        }

        val validArr = JsonArray()
        val fixObj = JsonObject()
        fixObj.addProperty("id", 1)
        fixObj.addProperty("team_h", 1)
        fixObj.addProperty("team_a", 2)
        validArr.add(fixObj)

        // Should not throw
        FplDataValidator.validateFixturesStructure(validArr)
    }
}
