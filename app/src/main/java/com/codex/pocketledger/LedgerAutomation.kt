package com.codex.pocketledger

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

fun generatedId(sourceKey: String): String =
    UUID.nameUUIDFromBytes(sourceKey.toByteArray(StandardCharsets.UTF_8)).toString()

fun dueDates(schedule: TransferSchedule, today: LocalDate): Sequence<LocalDate> = sequence {
    if (!schedule.active) return@sequence
    val start = LocalDate.parse(schedule.startDate)
    when (schedule.frequency) {
        "daily", "weekly" -> {
            val step = if (schedule.frequency == "daily") 1L else 7L
            var date = start
            while (!date.isAfter(today)) {
                yield(date)
                date = date.plusDays(step)
            }
        }
        "monthly" -> {
            var month = YearMonth.from(start)
            while (!month.atDay(1).isAfter(today)) {
                val date = month.atDay(start.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
                if (!date.isBefore(start) && !date.isAfter(today)) yield(date)
                month = month.plusMonths(1)
            }
        }
    }
}

fun scheduledTransfer(schedule: TransferSchedule, date: LocalDate): Entry {
    val sourceKey = "schedule:${schedule.id}:$date"
    return Entry(generatedId(sourceKey), "transfer", schedule.fromId, schedule.toId, null,
        schedule.amountCents, date.toString(), schedule.note,
        date.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC) * 1000, sourceKey)
}

fun monthlyInterest(snapshot: LedgerSnapshot, account: Account, month: YearMonth): Entry? {
    val cents = snapshot.monthInterest(account.id, month)
    if (cents <= 0) return null
    val sourceKey = "interest:${account.id}:$month"
    val date = month.atEndOfMonth()
    return Entry(generatedId(sourceKey), "income", account.id, null,
        builtInCategories.first { it.name == "利息" }.id, cents, date.toString(),
        "活期利息 $month", date.atTime(23, 59).toEpochSecond(java.time.ZoneOffset.UTC) * 1000,
        sourceKey)
}

fun fixedTermInterest(snapshot: LedgerSnapshot, account: Account): Entry? {
    val maturity = account.fixedMaturityDate?.let(LocalDate::parse) ?: return null
    val cents = snapshot.fixedInterest(account)
    if (cents <= 0) return null
    val sourceKey = "fixed-interest:${account.id}:$maturity"
    return Entry(generatedId(sourceKey), "income", account.id, null,
        builtInCategories.first { it.name == "利息" }.id, cents, maturity.toString(),
        "定期到期利息", maturity.atTime(23, 59).toEpochSecond(java.time.ZoneOffset.UTC) * 1000,
        sourceKey)
}
