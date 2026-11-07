package com.fplmanagerai.domain.team

import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition

data class Formation(
    val defenders: Int,
    val midfielders: Int,
    val forwards: Int
) {
    val name: String
        get() = "$defenders-$midfielders-$forwards"

    val isValid: Boolean
        get() = defenders in 3..5 &&
                midfielders in 2..5 &&
                forwards in 1..3 &&
                (defenders + midfielders + forwards == 10)

    companion object {
        val ALL_VALID_FORMATIONS = listOf(
            Formation(3, 5, 2),
            Formation(3, 4, 3),
            Formation(4, 4, 2),
            Formation(4, 3, 3),
            Formation(4, 5, 1),
            Formation(5, 3, 2),
            Formation(5, 4, 1),
            Formation(5, 2, 3)
        )

        fun parse(str: String?): Formation? {
            if (str == null) return null
            val parts = str.split("-").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 3) return null
            val formation = Formation(parts[0], parts[1], parts[2])
            return if (formation.isValid) formation else null
        }
    }
}

data class SquadValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

class FormationValidator {

    fun validateFullSquad(
        squad: List<PlayerModel>,
        budgetLimit: Double = 100.0,
        maxPerClub: Int = 3
    ): SquadValidationResult {
        val errors = mutableListOf<String>()

        // 1. Total count
        if (squad.size != 15) {
            errors.add("التشكيلة يجب أن تضم 15 لاعباً بالضبط (العدد الحالي: ${squad.size})")
        }

        // 2. Position counts
        val gkpCount = squad.count { it.position == PlayerPosition.GKP }
        val defCount = squad.count { it.position == PlayerPosition.DEF }
        val midCount = squad.count { it.position == PlayerPosition.MID }
        val fwdCount = squad.count { it.position == PlayerPosition.FWD }

        if (gkpCount != 2) errors.add("يجب اختيار حارسين مرمى بالضبط (الحالي: $gkpCount)")
        if (defCount != 5) errors.add("يجب اختيار 5 مدافعين بالضبط (الحالي: $defCount)")
        if (midCount != 5) errors.add("يجب اختيار 5 لاعبي خط وسط بالضبط (الحالي: $midCount)")
        if (fwdCount != 3) errors.add("يجب اختيار 3 مهاجمين بالضبط (الحالي: $fwdCount)")

        // 3. Club constraints (max 3 per club)
        val clubCounts = squad.groupBy { it.teamId }
        for ((teamId, players) in clubCounts) {
            if (players.size > maxPerClub) {
                val teamName = players.first().teamName
                errors.add("تجاوز الحد المسموح للاعبي نادي $teamName (${players.size} لاعبين، والحد الأقصى هو $maxPerClub)")
            }
        }

        // 4. Budget constraint
        val totalCost = squad.sumOf { it.price }
        if (totalCost > budgetLimit + 0.001) {
            errors.add("تكلفة التشكيلة (£%.1fm) تجاوزت الميزانية المحددة (£%.1fm)".format(java.util.Locale.UK, totalCost, budgetLimit))
        }

        return SquadValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    fun validateStartingLineup(
        starting11: List<PlayerModel>
    ): SquadValidationResult {
        val errors = mutableListOf<String>()

        if (starting11.size != 11) {
            errors.add("التشكيل الأساسي يجب أن يحتوي على 11 لاعباً بالضبط (الحالي: ${starting11.size})")
        }

        val gkp = starting11.count { it.position == PlayerPosition.GKP }
        val def = starting11.count { it.position == PlayerPosition.DEF }
        val mid = starting11.count { it.position == PlayerPosition.MID }
        val fwd = starting11.count { it.position == PlayerPosition.FWD }

        if (gkp != 1) errors.add("يجب إشراك حارس مرمى أساسي واحد فقط (الحالي: $gkp)")
        if (def < 3 || def > 5) errors.add("عدد المدافعين الأساسيين يجب أن يكون بين 3 و 5 (الحالي: $def)")
        if (mid < 2 || mid > 5) errors.add("عدد لاعبي الوسط الأساسيين يجب أن يكون بين 2 و 5 (الحالي: $mid)")
        if (fwd < 1 || fwd > 3) errors.add("عدد المهاجمين الأساسيين يجب أن يكون بين 1 و 3 (الحالي: $fwd)")

        return SquadValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}
