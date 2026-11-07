package com.fplmanagerai.data.parser

import com.fplmanagerai.data.network.FplDataException
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object FplDataValidator {

    fun safeDouble(element: JsonElement?): Double? {
        if (element == null || element.isJsonNull) return null
        return try {
            if (element.isJsonPrimitive) {
                val primitive = element.asJsonPrimitive
                if (primitive.isNumber) {
                    primitive.asDouble
                } else if (primitive.isString) {
                    val str = primitive.asString.trim()
                    if (str.isEmpty() || str.equals("none", ignoreCase = true) || str.equals("null", ignoreCase = true)) {
                        null
                    } else {
                        str.toDoubleOrNull()
                    }
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun safeInt(element: JsonElement?, default: Int = 0): Int {
        if (element == null || element.isJsonNull) return default
        return try {
            if (element.isJsonPrimitive) {
                val primitive = element.asJsonPrimitive
                if (primitive.isNumber) {
                    primitive.asInt
                } else if (primitive.isString) {
                    val str = primitive.asString.trim()
                    str.toIntOrNull() ?: str.toDoubleOrNull()?.toInt() ?: default
                } else default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun safeNullableInt(element: JsonElement?): Int? {
        if (element == null || element.isJsonNull) return null
        return try {
            if (element.isJsonPrimitive) {
                val primitive = element.asJsonPrimitive
                if (primitive.isNumber) {
                    primitive.asInt
                } else if (primitive.isString) {
                    val str = primitive.asString.trim()
                    if (str.isEmpty() || str.equals("none", ignoreCase = true) || str.equals("null", ignoreCase = true)) {
                        null
                    } else {
                        str.toIntOrNull() ?: str.toDoubleOrNull()?.toInt()
                    }
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun safeString(element: JsonElement?, default: String = ""): String {
        if (element == null || element.isJsonNull) return default
        return try {
            if (element.isJsonPrimitive) {
                element.asString.trim()
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun safeBoolean(element: JsonElement?, default: Boolean = false): Boolean {
        if (element == null || element.isJsonNull) return default
        return try {
            if (element.isJsonPrimitive) {
                val primitive = element.asJsonPrimitive
                if (primitive.isBoolean) primitive.asBoolean
                else if (primitive.isString) {
                    val str = primitive.asString.trim().lowercase()
                    str == "true" || str == "1"
                } else if (primitive.isNumber) {
                    primitive.asInt == 1
                } else default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun validateBootstrapStructure(json: JsonObject) {
        val requiredArrays = listOf("elements", "teams", "events", "element_types")
        val missing = requiredArrays.filter { !json.has(it) || !json.get(it).isJsonArray }
        if (missing.isNotEmpty()) {
            throw FplDataException.MalformedResponseException(
                "ملف بيانات الفانتازي يفتقر للأقسام الأساسية التالية: ${missing.joinToString()}"
            )
        }

        val elements = json.getAsJsonArray("elements")
        if (elements.size() == 0) {
            throw FplDataException.MalformedResponseException("قائمة اللاعبين (elements) فارغة في استجابة الخادم")
        }

        val teams = json.getAsJsonArray("teams")
        if (teams.size() == 0) {
            throw FplDataException.MalformedResponseException("قائمة الأندية (teams) فارغة في استجابة الخادم")
        }
    }

    fun validateFixturesStructure(json: JsonArray) {
        if (json.size() == 0) {
            throw FplDataException.MalformedResponseException("قائمة المباريات (fixtures) فارغة")
        }
        val first = json.get(0).asJsonObject
        if (!first.has("id") || !first.has("team_h") || !first.has("team_a")) {
            throw FplDataException.MalformedResponseException("بنية عناصر المباريات غير متوافقة مع نموذج FPL الرسمي")
        }
    }
}
