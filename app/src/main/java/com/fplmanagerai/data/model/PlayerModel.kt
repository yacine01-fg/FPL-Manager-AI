package com.fplmanagerai.data.model

enum class PlayerPosition(
    val id: Int,
    val shortName: String,
    val nameAr: String,
    val singularAr: String,
    val minInStarting11: Int,
    val maxInStarting11: Int,
    val squadCount: Int
) {
    GKP(1, "GKP", "حراس المرمى", "حارس مرمى", 1, 1, 2),
    DEF(2, "DEF", "خط الدفاع", "مدافع", 3, 5, 5),
    MID(3, "MID", "خط الوسط", "لاعب وسط", 2, 5, 5),
    FWD(4, "FWD", "خط الهجوم", "مهاجم", 1, 3, 3);

    companion object {
        fun fromId(id: Int): PlayerPosition = values().firstOrNull { it.id == id } ?: MID
        fun fromShortName(name: String): PlayerPosition = values().firstOrNull { it.shortName.equals(name, ignoreCase = true) } ?: MID
    }
}

data class PlayerAvailability(
    val status: String,
    val chanceOfPlayingNextRound: Int?,
    val news: String,
    val isAvailable: Boolean,
    val labelAr: String
) {
    companion object {
        fun parse(status: String?, chance: Int?, news: String?): PlayerAvailability {
            val safeStatus = status ?: "a"
            val safeNews = news ?: ""
            val isAvailable = safeStatus == "a" && (chance == null || chance >= 75)

            val label = when {
                safeStatus == "i" -> "مصاب"
                safeStatus == "s" -> "موقوف"
                safeStatus == "u" -> "غير متاح"
                safeStatus == "d" -> "مشكوك في مشاركته ($chance%)"
                chance != null && chance < 100 -> "احتمالية $chance%"
                else -> "جاهز للمشاركة"
            }

            return PlayerAvailability(
                status = safeStatus,
                chanceOfPlayingNextRound = chance,
                news = safeNews,
                isAvailable = isAvailable,
                labelAr = label
            )
        }
    }
}

data class UpcomingFixture(
    val gameweek: Int,
    val opponentId: Int,
    val opponentName: String,
    val opponentShortName: String,
    val isHome: Boolean,
    val difficulty: Int
)

data class PlayerModel(
    val id: Int,
    val webName: String,
    val firstName: String,
    val secondName: String,
    val teamId: Int,
    val teamName: String,
    val teamShortName: String,
    val position: PlayerPosition,
    val price: Double,
    val totalPoints: Int,
    val form: Double,
    val selectedByPercent: Double,
    val minutesPlayed: Int,
    val starts: Int,
    val statistics: PlayerStatistics,
    val availability: PlayerAvailability,
    val upcomingFixtures: List<UpcomingFixture> = emptyList(),
    val prediction: PlayerPrediction? = null
) {
    val fullName: String
        get() = "$firstName $secondName".trim()

    val formattedPrice: String
        get() = "£%.1fm".format(java.util.Locale.UK, price)
}
