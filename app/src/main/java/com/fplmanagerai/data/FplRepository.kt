package com.fplmanagerai.data

data class PlayerRow(
    val name: String,
    val team: String,
    val position: String,
    val price: String,
    val points: Int
)

data class DashboardData(
    val gameweek: String,
    val deadline: String,
    val totalPlayers: String,
    val topPlayers: List<PlayerRow>
)

class FplRepository(private val api: FplApi = FplApi.create()) {
    suspend fun loadDashboard(): DashboardData {
        val json = api.getBootstrap()
        val teams = json.getAsJsonArray("teams").associate { item ->
            val obj = item.asJsonObject
            obj.get("id").asInt to obj.get("name").asString
        }
        val positions = json.getAsJsonArray("element_types").associate { item ->
            val obj = item.asJsonObject
            obj.get("id").asInt to obj.get("singular_name_short").asString
        }
        val events = json.getAsJsonArray("events").map { it.asJsonObject }
        val current = events.firstOrNull { it.get("is_current").asBoolean }
            ?: events.firstOrNull { it.get("is_next").asBoolean }
        val eventName = current?.get("name")?.asString ?: "الجولة غير محددة"
        val deadline = current?.get("deadline_time")?.asString ?: "غير متاح"

        val players = json.getAsJsonArray("elements").map { it.asJsonObject }
            .sortedByDescending { it.get("total_points").asInt }
            .take(10)
            .map { p ->
                val first = p.get("first_name").asString
                val second = p.get("second_name").asString
                val teamId = p.get("team").asInt
                val positionId = p.get("element_type").asInt
                val price = p.get("now_cost").asInt / 10.0
                PlayerRow(
                    name = "$first $second",
                    team = teams[teamId] ?: "غير معروف",
                    position = positions[positionId] ?: "-",
                    price = "£%.1fm".format(java.util.Locale.UK, price),
                    points = p.get("total_points").asInt
                )
            }

        return DashboardData(
            gameweek = eventName,
            deadline = deadline,
            totalPlayers = json.get("total_players")?.asString ?: "—",
            topPlayers = players
        )
    }
}
