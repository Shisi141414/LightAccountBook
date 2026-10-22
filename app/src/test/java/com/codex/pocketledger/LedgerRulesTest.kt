package com.codex.pocketledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class LedgerRulesTest {
    @Test fun summaryPrecisionRoundsForDisplayWithoutFloatingPointLoss() {
        assertEquals(listOf("999.99", "1000.0", "1000"), summaryAmountCandidates(99_999))
        assertEquals(listOf("-0.01", "0.0", "0"), summaryAmountCandidates(-1))
        assertEquals(listOf("-12.35", "-12.4", "-12"), summaryAmountCandidates(-1_235))
        assertEquals("92233720368547758.07", summaryAmountCandidates(Long.MAX_VALUE).first())
    }

    private val device = UUID.randomUUID().toString()
    private fun op(type: String, id: String, parents: List<String>, payload: String): LedgerOp =
        LedgerOp(UUID.randomUUID().toString(), type, id, parents.joinToString(","), payload,
            device, System.currentTimeMillis())

    @Test fun transferPreservesTotalBalanceAndDoesNotSpendBudget() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val transfer = Entry(UUID.randomUUID().toString(), "transfer", a, b, null,
            12_345, "2026-10-19", "")
        val snapshot = snapshotFrom(listOf(
            op("account", a, emptyList(), accountPayload("现金", 20_000).toString()),
            op("account", b, emptyList(), accountPayload("银行卡", 30_000).toString()),
            op("entry", transfer.id, emptyList(), entryPayload(transfer).toString()),
        ))
        assertEquals(7_655L, snapshot.balance(a))
        assertEquals(42_345L, snapshot.balance(b))
        assertEquals(50_000L, snapshot.totalBalance())
        assertEquals(0L, snapshot.monthExpense(YearMonth.of(2026, 10)))
    }

    @Test fun concurrentEditsRemainVisibleAsConflictUntilResolved() {
        val id = UUID.randomUUID().toString()
        val base = op("account", id, emptyList(), accountPayload("现金", 0).toString())
        val left = op("account", id, listOf(base.opId), accountPayload("现金", 100).toString())
        val right = op("account", id, listOf(base.opId), accountPayload("现金", 200).toString())
        val state = currentObjects(listOf(base, left, right))["account" to id]!!
        assertTrue(state.conflict)
        assertEquals(setOf(left.opId, right.opId), state.heads.map { it.opId }.toSet())
        val resolved = op("account", id, state.heads.map { it.opId }, left.payload)
        assertEquals(listOf(resolved.opId),
            currentObjects(listOf(base, left, right, resolved))["account" to id]!!.heads.map { it.opId })
    }

    @Test fun dateAndParentCategoryFiltersShareTheSameExpenseBase() {
        val account = UUID.randomUUID().toString()
        val primary = builtInCategories.first { it.name == "餐饮" }
        val secondary = builtInCategories.first { it.name == "正餐" }
        val oct = Entry(UUID.randomUUID().toString(), "expense", account, null, secondary.id,
            2_500, "2026-10-19", "午饭")
        val sep = oct.copy(id = UUID.randomUUID().toString(), amountCents = 1_000, date = "2026-09-30")
        val snapshot = snapshotFrom(listOf(
            op("account", account, emptyList(), accountPayload("现金", 0).toString()),
            op("entry", oct.id, emptyList(), entryPayload(oct).toString()),
            op("entry", sep.id, emptyList(), entryPayload(sep).toString()),
        ))
        assertEquals(2_500L, snapshot.monthExpense(YearMonth.of(2026, 10), primary.id))
        assertEquals(listOf(oct), snapshot.filteredEntries(LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-10-31"), account, primary.id))
        assertEquals("请输入有效金额，最多两位小数",
            assertThrows(IllegalArgumentException::class.java) { parseCents("12.345") }.message)
    }

    @Test fun syncFileRoundTripKeepsOperationIdentity() {
        val accountId = UUID.randomUUID().toString()
        val local = op("account", accountId, emptyList(), accountPayload("校园卡", 1_234).toString())
        assertEquals(local, OpCodec.decode(OpCodec.encode(local)))
    }

    @Test fun exportedEntriesAndBudgetsRestoreWithEditsAndDeletes() {
        val month = YearMonth.of(2026, 10)
        val entryId = UUID.randomUUID().toString()
        val foodId = builtInCategories.first { it.name == "餐饮" }.id
        val entry = Entry(entryId, "expense", defaultAccount.id, null, foodId,
            2_600, "2026-10-20", "午餐")
        val firstEntry = op("entry", entryId, emptyList(), entryPayload(entry).toString())
        val firstBudget = op("budget", budgetId(month, null), emptyList(),
            budgetPayload(month.toString(), null, 3_000).toString())
        fun restored(operations: List<LedgerOp>) = snapshotFrom(operations.map {
            OpCodec.decode(OpCodec.encode(it))
        })
        assertEquals(2_600L, restored(listOf(firstEntry, firstBudget)).monthExpense(month))
        assertEquals(3_000L, restored(listOf(firstEntry, firstBudget)).budget(month)?.limitCents)

        val editedEntry = op("entry", entryId, listOf(firstEntry.opId),
            entryPayload(entry.copy(amountCents = 2_800)).toString())
        val editedBudget = op("budget", firstBudget.objectId, listOf(firstBudget.opId),
            budgetPayload(month.toString(), null, 5_000).toString())
        val updated = restored(listOf(firstEntry, firstBudget, editedEntry, editedBudget))
        assertEquals(2_800L, updated.monthExpense(month))
        assertEquals(5_000L, updated.budget(month)?.limitCents)

        val deletedEntry = op("entry", entryId, listOf(editedEntry.opId), "")
        val afterDelete = restored(listOf(firstEntry, firstBudget, editedEntry, editedBudget, deletedEntry))
        assertTrue(afterDelete.entries.isEmpty())
        assertEquals(5_000L, afterDelete.budget(month)?.limitCents)
    }

    @Test fun settingBalanceReplacesAccountBalanceWithoutChangingExpense() {
        val account = UUID.randomUUID().toString()
        val food = builtInCategories.first { it.name == "餐饮" }
        val expense = Entry(UUID.randomUUID().toString(), "expense", account, null, food.id,
            2_000, "2026-10-18", "")
        val setBalance = Entry(UUID.randomUUID().toString(), "balance", account, null, null,
            5_000, "2026-10-19", "校准", 123L)
        val laterIncome = Entry(UUID.randomUUID().toString(), "income", account, null,
            builtInCategories.first { it.name == "工资" }.id, 1_000, "2026-10-20", "")
        val snapshot = snapshotFrom(listOf(
            op("account", account, emptyList(), accountPayload("银行卡", 10_000).toString()),
            op("entry", expense.id, emptyList(), entryPayload(expense).toString()),
            op("entry", setBalance.id, emptyList(), entryPayload(setBalance).toString()),
            op("entry", laterIncome.id, emptyList(), entryPayload(laterIncome).toString()),
        ))
        assertEquals(6_000L, snapshot.balance(account))
        assertEquals(2_000L, snapshot.monthExpense(YearMonth.of(2026, 10)))
        assertEquals(setBalance, snapshot.entries.first { it.id == setBalance.id })
    }

    @Test fun monthlyScheduleUsesLastDayAndGeneratedIdentityIsStable() {
        val schedule = TransferSchedule(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1_000, "2026-01-31", "monthly", true, "房租")
        val dates = dueDates(schedule, LocalDate.parse("2026-03-31")).toList()
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), dates.map { it.toString() })
        assertEquals(scheduledTransfer(schedule, dates[1]), scheduledTransfer(schedule, dates[1]))
        assertEquals(3, dates.map { scheduledTransfer(schedule, it).id }.distinct().size)
    }

    @Test fun demandDepositAccruesDailyAndPostsAsIncome() {
        val account = UUID.randomUUID().toString()
        val rate = RateChange(UUID.randomUUID().toString(), account, "2026-10-01", 10_000, 1L)
        val snapshot = snapshotFrom(listOf(
            op("account", account, emptyList(), accountPayload("活期", 365_000, true).toString()),
            op("rate", rate.id, emptyList(), ratePayload(account, rate.date, rate.annualRateUnits,
                rate.recordedAtMs).toString()),
        ))
        assertEquals(310L, snapshot.monthInterest(account, YearMonth.of(2026, 10)))
        val changedRate = RateChange(UUID.randomUUID().toString(), account, "2026-10-16", 20_000, 2L)
        val changed = snapshot.copy(rateChanges = snapshot.rateChanges + changedRate)
        assertEquals(470L, changed.monthInterest(account, YearMonth.of(2026, 10)))
        val interest = monthlyInterest(snapshot, snapshot.accounts.first { it.id == account }, YearMonth.of(2026, 10))!!
        assertEquals(310L, interest.amountCents)
        assertEquals("income", interest.kind)
        assertEquals(365_310L, snapshot.copy(entries = listOf(interest)).balance(account))
    }

    @Test fun fourDecimalRateIsExactAndLegacyRateRemainsReadable() {
        assertEquals(8_860, parseAnnualRateUnits("0.8860"))
        assertEquals("0.886", formatAnnualRate(8_860))
        assertEquals("年化利率最多支持四位小数，例如 0.8860",
            assertThrows(IllegalArgumentException::class.java) {
                parseAnnualRateUnits("0.88601")
            }.message)
        val account = UUID.randomUUID().toString()
        val legacyId = UUID.randomUUID().toString()
        val legacyPayload = JSONObject().put("accountId", account).put("date", "2026-09-01")
            .put("annualRateBps", 100).put("recordedAtMs", 1L)
        val legacy = snapshotFrom(listOf(
            op("account", account, emptyList(), accountPayload("活期", 365_000, true).toString()),
            op("rate", legacyId, emptyList(), legacyPayload.toString()),
        ))
        assertEquals(10_000, legacy.rateOn(account, LocalDate.parse("2026-09-02")))
        val exactRate = RateChange(UUID.randomUUID().toString(), account, "2026-10-01", 8_860, 2L)
        val updated = legacy.copy(rateChanges = legacy.rateChanges + exactRate)
        assertEquals(275L, updated.monthInterest(account, YearMonth.of(2026, 10)))
    }

    @Test fun deletedAccountKeepsHistoryAndBalanceUnderUnassigned() {
        val accountId = UUID.randomUUID().toString()
        val original = op("account", accountId, emptyList(), accountPayload("旧账户", 5_000).toString())
        val removed = op("account", accountId, listOf(original.opId), "")
        val entry = Entry(UUID.randomUUID().toString(), "income", accountId, null,
            uncategorizedIncome.id, 2_000, "2026-10-20", "")
        val snapshot = snapshotFrom(listOf(original, removed,
            op("entry", entry.id, emptyList(), entryPayload(entry).toString())))
        assertTrue(snapshot.accounts.none { it.id == accountId })
        assertEquals("未指定账户", snapshot.accountName(accountId))
        assertEquals(7_000L, snapshot.balance(defaultAccount.id))
        assertEquals(7_000L, snapshot.totalBalance())
        assertEquals(listOf(entry), snapshot.filteredEntries(null, null, defaultAccount.id, null))
    }

    @Test fun presetCategoriesCanBeEditedAndDeletedWithoutLosingEntries() {
        val food = builtInCategories.first { it.name == "餐饮" }
        val renamed = op("category", food.id, emptyList(), categoryPayload("吃饭", "expense", null).toString())
        val entry = Entry(UUID.randomUUID().toString(), "expense", defaultAccount.id, null,
            food.id, 800, "2026-10-20", "")
        val entryOp = op("entry", entry.id, emptyList(), entryPayload(entry).toString())
        assertEquals("吃饭", snapshotFrom(listOf(renamed, entryOp)).categoryName(food.id))
        val deleted = op("category", food.id, listOf(renamed.opId), "")
        val snapshot = snapshotFrom(listOf(renamed, deleted, entryOp))
        assertEquals("未分类", snapshot.categoryPath(food.id))
        assertEquals(800L, snapshot.monthExpense(YearMonth.of(2026, 10), uncategorizedExpense.id))
        assertEquals(listOf(entry), snapshot.filteredEntries(null, null, null, uncategorizedExpense.id))
    }

    @Test fun bookIdsKeepOperationsSeparateAndOldFilesRemainInFirstBook() {
        val secondBook = UUID.randomUUID().toString()
        val first = op("account", UUID.randomUUID().toString(), emptyList(),
            accountPayload("第一本", 100).toString())
        val second = op("account", UUID.randomUUID().toString(), emptyList(),
            accountPayload("第二本", 200).toString()).copy(ledgerId = secondBook)
        val metadata = op("book", secondBook, emptyList(), bookPayload("旅行").toString())
        val ops = listOf(first, second, metadata)
        assertEquals(listOf("我的账本", "旅行"), booksFrom(ops).map { it.name })
        assertEquals(100L, snapshotFrom(ops.filter { it.ledgerId == DEFAULT_BOOK_ID }).totalBalance())
        assertEquals(200L, snapshotFrom(ops.filter { it.ledgerId == secondBook }).totalBalance())
        assertEquals(second, OpCodec.decode(OpCodec.encode(second)))
        val legacy = JSONObject(OpCodec.encode(first)).apply { remove("ledgerId") }
        assertEquals(first, OpCodec.decode(legacy.toString()))
    }

    @Test fun fixedTermLocksPrincipalAndCreditsInterestAtMaturity() {
        val accountId = UUID.randomUUID().toString()
        val account = Account(accountId, "定期", 365_000, investmentType = "fixed",
            fixedStartDate = "2026-01-01", fixedMaturityDate = "2026-02-01")
        val rate = RateChange(UUID.randomUUID().toString(), accountId, "2026-01-01", 10_000, 1L)
        val snapshot = LedgerSnapshot(listOf(defaultAccount, account), builtInCategories,
            emptyList(), emptyList(), emptyList(), listOf(rate))
        assertTrue(!account.isLiquid(LocalDate.parse("2026-01-31")))
        assertTrue(account.isLiquid(LocalDate.parse("2026-02-01")))
        assertEquals(310L, snapshot.fixedInterest(account))
        val interest = fixedTermInterest(snapshot, account)!!
        assertEquals("2026-02-01", interest.date)
        assertEquals(310L, interest.amountCents)
        assertEquals(365_310L, snapshot.copy(entries = listOf(interest)).totalBalance())
    }

    @Test fun calculatorKeepsFullExpressionAndUsesPrecedence() {
        val keys = listOf("1", "5", "×", "0", ".", "8", "+", "2", "0", "+", "8", "+", "8", "+", "1", "8", "=")
        val calculated = keys.fold(CalculatorState()) { state, key -> state.press(key) }
        assertEquals("15×0.8+20+8+8+18", calculated.expression)
        assertEquals("66.00", calculated.preview)
        assertEquals(CalculatorResult("66.00", calculated.expression), calculated.finish(false))
        assertEquals(calculated, calculated.press("a"))
        assertEquals("5.00", CalculatorState(expression = "12.34", justEvaluated = true)
            .press("5").finish(false).amount)
        assertEquals("100.00", listOf("1", "00").fold(CalculatorState()) { s, k -> s.press(k) }
            .finish(false).amount)
        assertEquals("14.00", listOf("2", "+", "3", "×", "4")
            .fold(CalculatorState()) { s, k -> s.press(k) }.finish(false).amount)
    }

    @Test fun calculatorSupportsParenthesesPercentAndEditing() {
        val keys = listOf("2", "00", "×", "(", "1", "0", "+", "5", ")", "%")
        val state = keys.fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("200×(10+5)%", state.expression)
        assertEquals("30.00", state.finish(false).amount)
        assertEquals("20.00", listOf("2", "00", "×", "1", "0", "%")
            .fold(CalculatorState()) { s, k -> s.press(k) }.finish(false).amount)
        assertEquals("0.33", listOf("1", "÷", "3")
            .fold(CalculatorState()) { s, k -> s.press(k) }.finish(false).amount)
        assertEquals("200×(10+5)", state.press("⌫").expression)
        assertEquals("", state.press("C").expression)
        val negative = listOf("−", "5").fold(CalculatorState()) { s, k -> s.press(k) }
        assertEquals("-5.00", negative.finish(true).amount)
        assertEquals("金额不能为负数",
            assertThrows(IllegalArgumentException::class.java) { negative.finish(false) }.message)
    }

    @Test fun calculatedExpressionUpdatesTheNoteWithoutDroppingManualText() {
        val first = appendFormulaToNote("午餐", "15×0.8+20")
        assertEquals("午餐；算式：15×0.8+20", first)
        assertEquals("午餐；算式：15×0.8+18", appendFormulaToNote(first, "15×0.8+18"))
        assertEquals("算式：2+3", appendFormulaToNote("", "2+3"))
        assertEquals(null, CalculatorState(expression = "15").finish(false).expression)
    }

    @Test fun savedEntryClearsAmountAndNoteButKeepsSelections() {
        val before = EntryDraft(kind = "income", amount = "32.00", accountId = "wechat",
            primaryId = "salary", date = "2026-10-20", note = "算式：15×0.8+20", revision = 4)
        assertEquals(before.copy(amount = "", note = "", revision = 5), before.afterSave())
    }

    @Test fun balanceChartZoomsToSmallChangesAndAdaptsDateTicks() {
        val axis = balanceAxis(listOf(100_000L, 100_010L))
        assertTrue(axis.upperCents - axis.lowerCents < 100.0)
        assertTrue(axis.fraction(100_010L) - axis.fraction(100_000L) > 0.4f)
        assertTrue(balanceAxis(listOf(-500L, -500L)).upperCents >
            balanceAxis(listOf(-500L, -500L)).lowerCents)
        assertEquals(31, balanceChartDates(LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)).size)
        assertEquals(30, balanceXTickIndices(31, 300f).last())
        assertEquals(365, balanceXTickIndices(366, 300f).last())
        assertThrows(IllegalArgumentException::class.java) {
            balanceChartDates(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 1))
        }
    }

    @Test fun calculatorAndErrorsStayInChinese() {
        val dividedByZero = listOf("8", "÷", "0", "=")
            .fold(CalculatorState()) { state, key -> state.press(key) }
        assertEquals("不能除以 0", dividedByZero.error)
        assertEquals("请输入有效数字", userFacingError(NumberFormatException("For input string")))
        assertEquals("保存失败，请重试", userFacingError(IllegalStateException("SQL error"), "保存失败，请重试"))
        assertEquals("日期无效，请按年-月-日填写",
            assertThrows(IllegalArgumentException::class.java) { parseUserDate("2026-13-01") }.message)
    }

    @Test fun budgetColorsChangeAtRequestedBoundaries() {
        assertEquals(BudgetLevel.UNSET, budgetLevel(1, null))
        assertEquals(BudgetLevel.GREEN, budgetLevel(64, 100))
        assertEquals(BudgetLevel.YELLOW, budgetLevel(65, 100))
        assertEquals(BudgetLevel.YELLOW, budgetLevel(84, 100))
        assertEquals(BudgetLevel.ORANGE, budgetLevel(85, 100))
        assertEquals(BudgetLevel.ORANGE, budgetLevel(94, 100))
        assertEquals(BudgetLevel.RED, budgetLevel(95, 100))
        assertEquals(BudgetLevel.RED, budgetLevel(120, 100))
        assertEquals(BudgetLevel.RED, budgetLevel(1, 0))
    }
}
