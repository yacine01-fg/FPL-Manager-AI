package com.fplmanagerai.data.model

import com.fplmanagerai.domain.chips.ChipType

/**
 * بيانات الحساب الشخصي لمدرب الفانتازي المستوردة من endpoint: /entry/{team_id}/
 */
data class FplAccountProfile(
    val teamId: Int,
    val teamName: String,
    val playerFirstName: String,
    val playerLastName: String,
    val overallPoints: Int,
    val overallRank: Int?,
    val currentGameweek: Int,
    val bank: Double, // in millions, e.g. 1.5
    val teamValue: Double, // in millions, e.g. 102.5
    val totalTransfers: Int,
    val isLinked: Boolean = true
) {
    val managerFullName: String
        get() = "$playerFirstName $playerLastName".trim().ifBlank { "مدرب FPL" }

    val formattedBank: String
        get() = "£%.1fm".format(java.util.Locale.US, bank)

    val formattedTeamValue: String
        get() = "£%.1fm".format(java.util.Locale.US, teamValue)
}

/**
 * سجل شريحة تم لعبها في جولة سابقة مستورد من /entry/{team_id}/history/
 */
data class FplPlayedChip(
    val chipType: ChipType?,
    val chipCode: String,
    val gameweek: Int,
    val playedTime: String? = null
)

/**
 * لاعب محدد ضمن تشكيلة المستخدم في جولة معينة من /entry/{team_id}/event/{gw}/picks/
 */
data class FplPickItem(
    val elementId: Int,
    val position: Int, // 1..11 أساسيون، 12..15 احتياط
    val multiplier: Int, // 1 عادي، 2 كابتن، 3 تريبل كابتن، 0 دكة
    val isCaptain: Boolean,
    val isViceCaptain: Boolean,
    val purchasePrice: Double? = null,
    val sellingPrice: Double? = null
)

/**
 * التشكيلة الحقيقية المسحوبة من حساب المستخدم
 */
data class FplUserSquad(
    val teamId: Int,
    val gameweek: Int,
    val activeChip: String?,
    val bank: Double,
    val teamValue: Double,
    val eventTransfers: Int,
    val eventTransfersCost: Int,
    val starting11: List<PlayerModel>,
    val bench: List<PlayerModel>,
    val captain: PlayerModel,
    val viceCaptain: PlayerModel
)

/**
 * بنية التخزين المؤقت لتشكيلة المستخدم للحفاظ على البيانات عند انقطاع الإنترنت
 */
data class CachedUserSquadPicks(
    val teamId: Int,
    val sourceGameweek: Int,
    val isPreviousGameweek: Boolean,
    val startingElementIds: List<Int>,
    val benchElementIds: List<Int>,
    val captainId: Int,
    val viceCaptainId: Int,
    val bank: Double,
    val purchasePricesMap: Map<Int, Double> = emptyMap(),
    val sellingPricesMap: Map<Int, Double> = emptyMap(),
    val multipliersMap: Map<Int, Int> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)


