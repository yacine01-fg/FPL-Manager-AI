package com.fplmanagerai.domain.chips

import com.fplmanagerai.data.chips.ChipSeasonConfig

/**
 * Official FPL Chip Rules Engine (2026/27 Specifications)
 * Enforces all restrictions, state transitions, validation checks, and transfer preservation rules.
 */
class ChipRulesEngine(
    private val config: ChipSeasonConfig = ChipSeasonConfig.DEFAULT_2026_27
) {

    /**
     * Validates whether a chip can be activated or set to pending for a target Gameweek
     */
    fun validateChipActivation(
        chip: ChipType,
        half: ChipHalf,
        targetGw: Int,
        userHistory: UserChipHistory
    ): ChipValidationResult {
        // 1. Gameweek Range Validation
        if (targetGw !in 1..config.totalGameweeks) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "الجولة المستهدفة ($targetGw) خارج نطاق موسم الفانتازي (1-${config.totalGameweeks})."
            )
        }

        // 2. Rule: Free Hit and Wildcard cannot be activated in Gameweek 1
        if (targetGw == 1 && chip in config.disallowedChipsInGw1) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "وفقاً للوائح الرسمية، لا يمكن تفعيل ${chip.titleAr} في الجولة الأولى (GW 1) حيث تكون التبديلات مفتوحة ومجانية افتراضياً."
            )
        }

        // 3. Rule: First set expires after Gameweek 19 deadline. No carry-over between halves.
        if (half == ChipHalf.FIRST_HALF && targetGw > config.firstHalfDeadlineGw) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "انتهت صلاحية رقاقات المجموعة الأولى (Set 1) مع بداية الجولة ${config.secondHalfStartGw}. لا يتم ترحيل الرقاقات غير المستخدمة بين نصفي الموسم."
            )
        }

        // 4. Rule: Second set becomes available from Gameweek 20
        if (half == ChipHalf.SECOND_HALF && targetGw < config.secondHalfStartGw) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "رقاقات المجموعة الثانية (Set 2) مغلقة ولن تصبح متاحة إلا ابتداءً من الجولة ${config.secondHalfStartGw}."
            )
        }

        // 5. Check if this specific chip instance has already been used or expired
        val instance = ChipInstance(chip, half)
        val currentState = userHistory.getChipState(chip, half, targetGw)
        if (currentState == ChipStatus.USED) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "تم استهلاك هذه الرقاقة (${chip.titleAr} - ${half.titleAr}) مسبقاً في هذا الموسم."
            )
        }
        if (currentState == ChipStatus.EXPIRED) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "هذه الرقاقة منتهية الصلاحية ولا يمكن تفعيلها."
            )
        }

        // 6. Rule: Only ONE chip per Gameweek
        if (userHistory.activeChipThisGw != null && userHistory.activeChipThisGw != chip) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "قاعدة رسمية صارمة: لا يمكن تفعيل أكثر من رقاقة واحدة في نفس الجولة. يوجد رقاقة نشطة بالفعل (${userHistory.activeChipThisGw.titleAr})."
            )
        }
        if (userHistory.pendingChip != null && userHistory.pendingChipGameweek == targetGw && userHistory.pendingChip != instance) {
            return ChipValidationResult(
                isValid = false,
                ruleViolationAr = "توجد رقاقة أخرى معلقة بالفعل للجولة $targetGw (${userHistory.pendingChip.chipType.titleAr}). يجب إلغاؤها أولاً."
            )
        }

        // 7. Rule: Free Hit cannot be used in consecutive Gameweeks
        if (chip == ChipType.FREE_HIT && !config.allowConsecutiveFreeHit) {
            val wasPlayedLastGw = userHistory.lastUsedFreeHitGw == (targetGw - 1)
            if (wasPlayedLastGw) {
                return ChipValidationResult(
                    isValid = false,
                    ruleViolationAr = "قاعدة رسمية: يُمنع استخدام فري هيت (Free Hit) في جولتين متتاليتين (تم استخدامها في GW ${targetGw - 1})."
                )
            }
        }

        // Warnings (e.g. nearing first-half expiration)
        var warning: String? = null
        if (half == ChipHalf.FIRST_HALF && targetGw in (config.firstHalfDeadlineGw - 1)..config.firstHalfDeadlineGw) {
            warning = "تنبيه: تنتهي صلاحية رقاقات المجموعة الأولى مع انطلاق الجولة ${config.secondHalfStartGw}. لن يتم ترحيلها إذا لم تُستخدم الآن!"
        }

        return ChipValidationResult(
            isValid = true,
            warningAr = warning
        )
    }

    /**
     * Preserves banked free transfers according to official rules when Wildcard or Free Hit is used.
     * In official FPL rules, playing a Wildcard or Free Hit does not forfeit accumulated banked transfers.
     */
    fun calculateBankedTransfersAfterChip(
        chip: ChipType,
        currentBankedTransfers: Int
    ): Int {
        val safeBanked = currentBankedTransfers.coerceIn(config.minBankedFreeTransfers, config.maxBankedFreeTransfers)
        return if (config.preserveBankedTransfersOnChip) {
            if (chip == ChipType.WILDCARD || chip == ChipType.FREE_HIT) {
                // Banked transfers are preserved
                safeBanked
            } else {
                safeBanked
            }
        } else {
            1
        }
    }

    /**
     * Sets a chip as pending for the target gameweek (prior to deadline)
     */
    fun setPending(
        chip: ChipType,
        half: ChipHalf,
        targetGw: Int,
        userHistory: UserChipHistory
    ): Pair<UserChipHistory, ChipValidationResult> {
        val validation = validateChipActivation(chip, half, targetGw, userHistory)
        if (!validation.isValid) {
            return userHistory to validation
        }

        val updated = userHistory.copy(
            pendingChip = ChipInstance(chip, half),
            pendingChipGameweek = targetGw
        )
        return updated to validation
    }

    /**
     * Cancels any pending chip
     */
    fun cancelPending(userHistory: UserChipHistory): UserChipHistory {
        return userHistory.copy(
            pendingChip = null,
            pendingChipGameweek = null
        )
    }

    /**
     * Confirms activation when the gameweek deadline passes
     */
    fun confirmActivation(
        chip: ChipType,
        half: ChipHalf,
        gw: Int,
        userHistory: UserChipHistory
    ): Pair<UserChipHistory, ChipValidationResult> {
        val validation = validateChipActivation(chip, half, gw, userHistory)
        if (!validation.isValid) {
            return userHistory to validation
        }

        val instance = ChipInstance(chip, half)
        val updatedHistory = userHistory.copy(
            pendingChip = null,
            pendingChipGameweek = null,
            activeChipThisGw = chip,
            activeChipGameweek = gw,
            lastUsedFreeHitGw = if (chip == ChipType.FREE_HIT) gw else userHistory.lastUsedFreeHitGw,
            chipPlayedGameweekMap = userHistory.chipPlayedGameweekMap + (instance to gw),
            set1UsedChips = if (half == ChipHalf.FIRST_HALF) userHistory.set1UsedChips + chip else userHistory.set1UsedChips,
            set2UsedChips = if (half == ChipHalf.SECOND_HALF) userHistory.set2UsedChips + chip else userHistory.set2UsedChips,
            firstHalfWildcardUsed = if (chip == ChipType.WILDCARD && half == ChipHalf.FIRST_HALF) true else userHistory.firstHalfWildcardUsed,
            secondHalfWildcardUsed = if (chip == ChipType.WILDCARD && half == ChipHalf.SECOND_HALF) true else userHistory.secondHalfWildcardUsed,
            bankedFreeTransfers = calculateBankedTransfersAfterChip(chip, userHistory.bankedFreeTransfers)
        )
        return updatedHistory to validation
    }

    /**
     * Checks all chips and marks expired Set 1 chips if Gameweek is >= 20
     */
    fun getEffectiveState(
        chip: ChipType,
        half: ChipHalf,
        currentGw: Int,
        userHistory: UserChipHistory
    ): ChipStatus {
        return userHistory.getChipState(chip, half, currentGw)
    }
}
