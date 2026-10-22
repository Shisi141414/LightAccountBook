package com.codex.pocketledger

import java.math.BigDecimal
import java.math.RoundingMode

/** Display-only precision choices; the stored amount always remains in cents. */
fun summaryAmountCandidates(cents: Long): List<String> = (2 downTo 0).map { scale ->
    BigDecimal.valueOf(cents, 2).setScale(scale, RoundingMode.HALF_UP).toPlainString()
}
