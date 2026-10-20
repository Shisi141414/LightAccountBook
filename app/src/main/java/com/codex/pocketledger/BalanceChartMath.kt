package com.codex.pocketledger

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class BalanceAxis(val lowerCents: Double, val upperCents: Double, val ticksCents: List<Double>) {
    fun fraction(cents: Long): Float =
        ((cents.toDouble() - lowerCents) / (upperCents - lowerCents)).toFloat().coerceIn(0f, 1f)
}

fun balanceChartDates(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()): List<LocalDate> {
    require(!end.isAfter(today)) { "结束日期不能晚于今天" }
    require(!start.isAfter(end)) { "起始日期不能晚于结束日期" }
    val count = ChronoUnit.DAYS.between(start, end) + 1
    require(count <= 366) { "图表范围最多 366 天" }
    return (0L until count).map { start.plusDays(it) }
}

/** Four or five readable ticks around the actual variation, without forcing zero into view. */
fun balanceAxis(values: List<Long>): BalanceAxis {
    val minimum = values.minOrNull()?.toDouble() ?: 0.0
    val maximum = values.maxOrNull()?.toDouble() ?: 0.0
    val spread = maximum - minimum
    val padding = if (spread == 0.0) max(abs(maximum) * 0.05, 100.0)
        else max(spread * 0.1, 1.0)
    val rawStep = (spread + padding * 2) / 4
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= rawStep } * magnitude
    val lower = floor((minimum - padding) / step) * step
    val upper = ceil((maximum + padding) / step) * step
    val count = ((upper - lower) / step).toInt().coerceIn(1, 10)
    return BalanceAxis(lower, upper, (0..count).map { lower + it * step })
}

fun balanceXTickIndices(dayCount: Int, plotWidthDp: Float): List<Int> {
    if (dayCount <= 1) return listOf(0)
    val labelCount = (plotWidthDp / 76f).toInt().coerceIn(2, 6)
    val step = ceil((dayCount - 1).toDouble() / (labelCount - 1)).toInt().coerceAtLeast(1)
    return (0 until dayCount step step).toList().let { indices ->
        if (indices.last() == dayCount - 1) indices else indices + (dayCount - 1)
    }
}
