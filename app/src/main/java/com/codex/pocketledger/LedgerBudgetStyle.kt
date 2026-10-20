package com.codex.pocketledger

import androidx.compose.ui.graphics.Color

enum class BudgetLevel { UNSET, GREEN, YELLOW, ORANGE, RED }

fun budgetRatio(usedCents: Long, limitCents: Long): Double =
    if (limitCents == 0L) { if (usedCents > 0) 1.0 else 0.0 }
    else usedCents.toDouble() / limitCents

fun budgetLevel(usedCents: Long, limitCents: Long?): BudgetLevel {
    if (limitCents == null) return BudgetLevel.UNSET
    val ratio = budgetRatio(usedCents, limitCents)
    return when {
        ratio >= 0.95 -> BudgetLevel.RED
        ratio >= 0.85 -> BudgetLevel.ORANGE
        ratio >= 0.65 -> BudgetLevel.YELLOW
        else -> BudgetLevel.GREEN
    }
}

fun BudgetLevel.color(outline: Color): Color = when (this) {
    BudgetLevel.UNSET -> outline
    BudgetLevel.GREEN -> Color(0xFF2E7D32)
    BudgetLevel.YELLOW -> Color(0xFFF9A825)
    BudgetLevel.ORANGE -> Color(0xFFDD6500)
    BudgetLevel.RED -> Color(0xFFC62828)
}
