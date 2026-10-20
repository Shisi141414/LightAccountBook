package com.codex.pocketledger

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

const val DEFAULT_BOOK_ID = "00000000-0000-0000-0000-000000000001"

data class Book(val id: String, val name: String)
data class Account(
    val id: String, val name: String, val openingCents: Long,
    val demandDeposit: Boolean = false,
    val investmentType: String = if (demandDeposit) "demand" else "none",
    val fixedStartDate: String? = null,
    val fixedMaturityDate: String? = null,
) {
    fun isLiquid(date: LocalDate): Boolean = investmentType != "fixed" ||
        (fixedMaturityDate != null && !date.isBefore(LocalDate.parse(fixedMaturityDate)))
}
data class Category(val id: String, val name: String, val kind: String, val parentId: String?)
data class Budget(val id: String, val month: String, val categoryId: String?, val limitCents: Long)
data class RateChange(val id: String, val accountId: String, val date: String,
                      val annualRateUnits: Int, val recordedAtMs: Long)
data class TransferSchedule(val id: String, val fromId: String, val toId: String,
                            val amountCents: Long, val startDate: String,
                            val frequency: String, val active: Boolean, val note: String)
data class Entry(
    val id: String,
    val kind: String,
    val accountId: String,
    val toAccountId: String?,
    val categoryId: String?,
    val amountCents: Long,
    val date: String,
    val note: String,
    val recordedAtMs: Long = 0,
    val sourceKey: String = "",
)

private fun stableId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

val defaultAccount = Account(stableId("account:unassigned"), "未指定账户", 0)
val builtInAccounts: List<Account> = listOf(
    defaultAccount,
    Account(stableId("account:wechat"), "微信", 0),
    Account(stableId("account:alipay"), "支付宝", 0),
    Account(stableId("account:cash"), "现金", 0),
)
val uncategorizedExpense = Category(stableId("expense:uncategorized"), "未分类", "expense", null)
val uncategorizedIncome = Category(stableId("income:uncategorized"), "未分类", "income", null)

val builtInCategories: List<Category> = listOf(
    uncategorizedExpense,
    uncategorizedIncome,
    Category(stableId("expense:food"), "餐饮", "expense", null),
    Category(stableId("expense:food:meal"), "正餐", "expense", stableId("expense:food")),
    Category(stableId("expense:food:drink"), "饮品", "expense", stableId("expense:food")),
    Category(stableId("expense:transport"), "交通", "expense", null),
    Category(stableId("expense:transport:public"), "公共交通", "expense", stableId("expense:transport")),
    Category(stableId("expense:transport:taxi"), "打车", "expense", stableId("expense:transport")),
    Category(stableId("expense:shopping"), "购物", "expense", null),
    Category(stableId("expense:shopping:daily"), "日用品", "expense", stableId("expense:shopping")),
    Category(stableId("expense:study"), "学习", "expense", null),
    Category(stableId("expense:study:books"), "书籍资料", "expense", stableId("expense:study")),
    Category(stableId("expense:other"), "其他支出", "expense", null),
    Category(stableId("income:salary"), "工资", "income", null),
    Category(stableId("income:salary:parttime"), "兼职", "income", stableId("income:salary")),
    Category(stableId("income:gift"), "礼金", "income", null),
    Category(stableId("income:interest"), "利息", "income", null),
    Category(stableId("income:other"), "其他收入", "income", null),
)

data class LedgerSnapshot(
    val accounts: List<Account>,
    val categories: List<Category>,
    val budgets: List<Budget>,
    val entries: List<Entry>,
    val conflicts: List<ObjectState>,
    val rateChanges: List<RateChange> = emptyList(),
    val schedules: List<TransferSchedule> = emptyList(),
    val removedAccounts: List<Account> = emptyList(),
) {
    fun accountName(id: String?): String = accounts.firstOrNull { it.id == id }?.name ?: defaultAccount.name
    fun categoryName(id: String?): String = categories.firstOrNull { it.id == id }?.name ?: "未分类"
    fun categoryParent(id: String?): String? = categories.firstOrNull { it.id == id }?.parentId
    fun effectiveCategoryId(id: String?, kind: String): String {
        val category = categories.firstOrNull { it.id == id && it.kind == kind }
        return if (category == null || (category.parentId != null && categories.none { it.id == category.parentId })) {
            if (kind == "income") uncategorizedIncome.id else uncategorizedExpense.id
        } else category.id
    }
    fun categoryPath(id: String?): String {
        val category = categories.firstOrNull { it.id == id } ?: return "未分类"
        if (category.parentId != null && categories.none { it.id == category.parentId }) return "未分类"
        return category.parentId?.let { "${categoryName(it)} / ${category.name}" } ?: category.name
    }

    fun balance(accountId: String): Long = balanceAt(accountId, null)

    fun balanceAt(accountId: String, through: LocalDate?, excludeSourceKey: String? = null): Long =
        rawBalanceAt(accountId, through, excludeSourceKey) + if (accountId == defaultAccount.id) {
            removedAccounts.sumOf { rawBalanceAt(it.id, through, excludeSourceKey) }
        } else 0L

    private fun rawBalanceAt(accountId: String, through: LocalDate?, excludeSourceKey: String?): Long {
        val opening = (accounts + removedAccounts).firstOrNull { it.id == accountId }?.openingCents ?: 0L
        return entries.filter { (through == null || LocalDate.parse(it.date) <= through) &&
            it.sourceKey != excludeSourceKey }
            .sortedWith(compareBy<Entry> { it.date }.thenBy { it.recordedAtMs }.thenBy { it.id })
            .fold(opening) { sum, entry ->
            when (entry.kind) {
                "income" -> if (entry.accountId == accountId) sum + entry.amountCents else sum
                "expense" -> if (entry.accountId == accountId) sum - entry.amountCents else sum
                "transfer" -> sum - (if (entry.accountId == accountId) entry.amountCents else 0L) +
                    (if (entry.toAccountId == accountId) entry.amountCents else 0L)
                "balance" -> if (entry.accountId == accountId) entry.amountCents else sum
                else -> sum
            }
        }
    }

    fun rateOn(accountId: String, date: LocalDate): Int = rateChanges
        .filter { it.accountId == accountId && LocalDate.parse(it.date) <= date }
        .maxWithOrNull(compareBy<RateChange> { it.date }.thenBy { it.recordedAtMs }.thenBy { it.id })
        ?.annualRateUnits ?: 0

    fun monthInterest(accountId: String, month: YearMonth, through: LocalDate = month.atEndOfMonth()): Long {
        val sourceKey = "interest:$accountId:$month"
        val days = if (YearMonth.from(through) == month) through.dayOfMonth else month.lengthOfMonth()
        val daily = (1..days).fold(BigDecimal.ZERO) { sum, day ->
            val date = month.atDay(day)
            val balance = balanceAt(accountId, date, sourceKey).coerceAtLeast(0)
            val rateUnits = rateOn(accountId, date)
            sum + BigDecimal.valueOf(balance).multiply(BigDecimal.valueOf(rateUnits.toLong()))
                .divide(BigDecimal.valueOf(365_000_000L), 12, RoundingMode.HALF_UP)
        }
        return daily.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun fixedInterest(account: Account): Long {
        val start = account.fixedStartDate?.let(LocalDate::parse) ?: return 0
        val maturity = account.fixedMaturityDate?.let(LocalDate::parse) ?: return 0
        val sourceKey = "fixed-interest:${account.id}:$maturity"
        var date = start
        var sum = BigDecimal.ZERO
        while (date.isBefore(maturity)) {
            val principal = balanceAt(account.id, date, sourceKey).coerceAtLeast(0)
            sum += BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(rateOn(account.id, date).toLong()))
                .divide(BigDecimal.valueOf(365_000_000L), 12, RoundingMode.HALF_UP)
            date = date.plusDays(1)
        }
        return sum.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun totalBalance(): Long = totalBalanceAt(null)
    fun totalBalanceAt(through: LocalDate?): Long = accounts.sumOf { balanceAt(it.id, through) }
    fun liquidBalance(date: LocalDate = LocalDate.now()): Long = accounts.filter { it.isLiquid(date) }
        .sumOf { balanceAt(it.id, date) }
    fun monthIncome(month: YearMonth): Long = entries
        .filter { it.kind == "income" && YearMonth.from(LocalDate.parse(it.date)) == month }
        .sumOf { it.amountCents }
    fun monthExpense(month: YearMonth, categoryId: String? = null): Long = entries
        .filter { it.kind == "expense" && YearMonth.from(LocalDate.parse(it.date)) == month }
        .filter { entry ->
            val effective = effectiveCategoryId(entry.categoryId, "expense")
            categoryId == null || effective == categoryId || categoryParent(effective) == categoryId
        }
        .sumOf { it.amountCents }

    fun budget(month: YearMonth, categoryId: String? = null): Budget? =
        budgets.firstOrNull { it.month == month.toString() && it.categoryId == categoryId }

    fun filteredEntries(start: LocalDate?, end: LocalDate?, accountId: String?, categoryId: String?): List<Entry> =
        entries.filter { entry ->
            val day = LocalDate.parse(entry.date)
            (start == null || !day.isBefore(start)) && (end == null || !day.isAfter(end)) &&
                (accountId == null || entry.accountId == accountId || entry.toAccountId == accountId ||
                    (accountId == defaultAccount.id &&
                        (removedAccounts.any { it.id == entry.accountId || it.id == entry.toAccountId }))) &&
                (categoryId == null || effectiveCategoryId(entry.categoryId, entry.kind) == categoryId ||
                    categoryParent(effectiveCategoryId(entry.categoryId, entry.kind)) == categoryId)
        }
}

fun snapshotFrom(ops: List<LedgerOp>): LedgerSnapshot {
    val statesById = currentObjects(ops)
    val states = statesById.values
    val live = states.mapNotNull { it.chosen }
    fun parseAccount(op: LedgerOp): Account? = runCatching {
        val payload = op.payloadObject()!!
        val type = payload.optString("investmentType").ifBlank {
            if (payload.optBoolean("demandDeposit", false)) "demand" else "none"
        }
        Account(op.objectId, payload.getString("name"), payload.getLong("openingCents"),
            type == "demand", type,
            payload.optString("fixedStartDate").takeIf { it.isNotBlank() },
            payload.optString("fixedMaturityDate").takeIf { it.isNotBlank() })
    }.getOrNull()
    val builtInAccountIds = builtInAccounts.map { it.id }.toSet()
    val accounts = (builtInAccounts.mapNotNull { account ->
        val state = statesById["account" to account.id]
        if (state == null) account else state.chosen?.let(::parseAccount)
    } + live.filter { it.objectType == "account" && it.objectId !in builtInAccountIds }
        .mapNotNull(::parseAccount)).sortedWith(compareBy<Account> { it.id != defaultAccount.id }.thenBy { it.name })
    val removedAccounts = states.filter { it.type == "account" && it.chosen == null }
        .mapNotNull { state ->
            ops.filter { it.objectType == "account" && it.objectId == state.id && !it.isDelete() }
                .maxWithOrNull(compareBy<LedgerOp> { it.createdAtMs }.thenBy { it.opId })
                ?.let(::parseAccount) ?: builtInAccounts.firstOrNull { it.id == state.id }
        }
    val customCategories = live.filter { it.objectType == "category" }.mapNotNull { op ->
        runCatching {
            val p = op.payloadObject()!!
            Category(op.objectId, p.getString("name"), p.getString("kind"),
                p.optString("parentId").takeIf { it.isNotBlank() })
        }.getOrNull()
    }
    val customById = customCategories.associateBy { it.id }
    val builtInCategoryIds = builtInCategories.map { it.id }.toSet()
    val categories = builtInCategories.mapNotNull { category ->
        val state = statesById["category" to category.id]
        if (state == null) category else if (state.chosen == null) null else customById[category.id]
    } + customCategories.filter { it.id !in builtInCategoryIds }
    val budgets = live.filter { it.objectType == "budget" }.mapNotNull { op ->
        runCatching {
            val p = op.payloadObject()!!
            Budget(op.objectId, p.getString("month"), p.optString("categoryId").takeIf { it.isNotBlank() },
                p.getLong("limitCents"))
        }.getOrNull()
    }
    val rateChanges = live.filter { it.objectType == "rate" }.mapNotNull { op ->
        runCatching {
            val p = op.payloadObject()!!
            RateChange(op.objectId, p.getString("accountId"), p.getString("date"),
                if (p.has("annualRateUnits")) p.getInt("annualRateUnits")
                else p.getInt("annualRateBps") * 100,
                p.getLong("recordedAtMs"))
        }.getOrNull()
    }
    val schedules = live.filter { it.objectType == "schedule" }.mapNotNull { op ->
        runCatching {
            val p = op.payloadObject()!!
            TransferSchedule(op.objectId, p.getString("fromId"), p.getString("toId"),
                p.getLong("amountCents"), p.getString("startDate"), p.getString("frequency"),
                p.getBoolean("active"), p.optString("note"))
        }.getOrNull()
    }
    val entries = live.filter { it.objectType == "entry" }.mapNotNull { op ->
        runCatching {
            val p = op.payloadObject()!!
            Entry(op.objectId, p.getString("kind"), p.getString("accountId"),
                p.optString("toAccountId").takeIf { it.isNotBlank() },
                p.optString("categoryId").takeIf { it.isNotBlank() }, p.getLong("amountCents"),
                p.getString("date"), p.optString("note"), p.optLong("recordedAtMs", 0),
                p.optString("sourceKey"))
        }.getOrNull()
    }.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.recordedAtMs }
        .thenByDescending { it.id })
    return LedgerSnapshot(accounts, categories, budgets, entries,
        states.filter { it.conflict }.sortedWith(compareBy({ it.type }, { it.id })),
        rateChanges, schedules, removedAccounts)
}

fun booksFrom(ops: List<LedgerOp>): List<Book> {
    val named = currentObjects(ops.filter { it.objectType == "book" }).values
        .mapNotNull { state -> state.chosen?.let { op ->
            runCatching { Book(op.objectId, op.payloadObject()!!.getString("name")) }.getOrNull()
        } }
    return (listOf(Book(DEFAULT_BOOK_ID, named.firstOrNull { it.id == DEFAULT_BOOK_ID }?.name
        ?: "我的账本")) + named.filter { it.id != DEFAULT_BOOK_ID }).sortedWith(
        compareBy<Book> { it.id != DEFAULT_BOOK_ID }.thenBy { it.name })
}

fun parseCents(input: String, allowNegative: Boolean = false): Long {
    val trimmed = input.trim()
    require(trimmed.matches(Regex("-?\\d+(\\.\\d{1,2})?"))) { "请输入有效金额，最多两位小数" }
    val cents = try {
        BigDecimal(trimmed).movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("金额超出范围")
    }
    require(if (allowNegative) cents != Long.MIN_VALUE else cents > 0) { "金额必须大于 0" }
    return cents
}

fun formatCents(cents: Long): String = BigDecimal.valueOf(cents, 2).setScale(2).toPlainString()

fun parseAnnualRateUnits(input: String): Int {
    val rate = try {
        BigDecimal(input.trim())
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("请输入有效的年化利率")
    }
    require(rate >= BigDecimal.ZERO && rate <= BigDecimal("1000")) {
        "年化利率须在 0%–1000% 之间"
    }
    return try {
        rate.movePointRight(4).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("年化利率最多支持四位小数，例如 0.8860")
    }
}

fun formatAnnualRate(rateUnits: Int): String = BigDecimal.valueOf(rateUnits.toLong(), 4)
    .stripTrailingZeros().toPlainString()

fun accountPayload(name: String, openingCents: Long, demandDeposit: Boolean = false,
                   investmentType: String = if (demandDeposit) "demand" else "none",
                   fixedStartDate: String? = null, fixedMaturityDate: String? = null): JSONObject = JSONObject()
    .put("name", name.trim()).put("openingCents", openingCents).put("demandDeposit", investmentType == "demand")
    .put("investmentType", investmentType).put("fixedStartDate", fixedStartDate ?: "")
    .put("fixedMaturityDate", fixedMaturityDate ?: "")

fun bookPayload(name: String): JSONObject = JSONObject().put("name", name.trim())

fun ratePayload(accountId: String, date: String, annualRateUnits: Int, recordedAtMs: Long): JSONObject =
    JSONObject().put("accountId", accountId).put("date", date)
        .put("annualRateUnits", annualRateUnits).put("recordedAtMs", recordedAtMs)

fun schedulePayload(schedule: TransferSchedule): JSONObject = JSONObject()
    .put("fromId", schedule.fromId).put("toId", schedule.toId)
    .put("amountCents", schedule.amountCents).put("startDate", schedule.startDate)
    .put("frequency", schedule.frequency).put("active", schedule.active).put("note", schedule.note)

fun categoryPayload(name: String, kind: String, parentId: String?): JSONObject = JSONObject()
    .put("name", name.trim()).put("kind", kind).put("parentId", parentId ?: "")

fun budgetPayload(month: String, categoryId: String?, limitCents: Long): JSONObject = JSONObject()
    .put("month", month).put("categoryId", categoryId ?: "").put("limitCents", limitCents)

fun entryPayload(entry: Entry): JSONObject = JSONObject()
    .put("kind", entry.kind).put("accountId", entry.accountId)
    .put("toAccountId", entry.toAccountId ?: "").put("categoryId", entry.categoryId ?: "")
    .put("amountCents", entry.amountCents).put("date", entry.date).put("note", entry.note.trim())
    .put("recordedAtMs", entry.recordedAtMs).put("sourceKey", entry.sourceKey)

fun budgetId(month: YearMonth, categoryId: String?): String = stableId("budget:$month:${categoryId ?: "all"}")
