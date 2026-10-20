package com.codex.pocketledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun EntryDialog(data: LedgerSnapshot, existing: Entry?, onDismiss: () -> Unit,
                onDelete: () -> Unit, onSave: (Entry) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("编辑记录") },
        text = { EntryForm(data, existing, onSave, scrollable = true) },
        confirmButton = { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun EntryForm(data: LedgerSnapshot, existing: Entry?, onSave: (Entry) -> Unit,
              scrollable: Boolean = false, draft: EntryDraft? = null,
              onDraftChange: (EntryDraft) -> Unit = {}) {
    var kind by rememberSaveable(draft?.revision, existing?.id) { mutableStateOf(draft?.kind ?: existing?.kind ?: "expense") }
    var amount by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.amount ?: existing?.let { formatCents(it.amountCents) } ?: "")
    }
    var accountId by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.accountId?.ifBlank { defaultAccount.id } ?: existing?.accountId ?: defaultAccount.id)
    }
    var toAccountId by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.toAccountId ?: existing?.toAccountId.orEmpty())
    }
    val initialCategory = data.categories.firstOrNull { it.id == existing?.categoryId }
    var primaryId by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.primaryId?.ifBlank {
            if (kind == "income") uncategorizedIncome.id else uncategorizedExpense.id
        } ?: initialCategory?.parentId ?: initialCategory?.id ?: if (kind == "income") uncategorizedIncome.id else uncategorizedExpense.id)
    }
    var secondaryId by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.secondaryId ?: if (initialCategory?.parentId != null) initialCategory.id else "")
    }
    var date by rememberSaveable(draft?.revision, existing?.id) {
        mutableStateOf(draft?.date ?: existing?.date ?: LocalDate.now().toString())
    }
    var note by rememberSaveable(draft?.revision, existing?.id) { mutableStateOf(draft?.note ?: existing?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val primary = data.categories.filter { it.kind == kind && it.parentId == null }
    val secondary = data.categories.filter { it.kind == kind && it.parentId == primaryId }
    val accounts = selectableAccounts(data)
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == accountId }) accountId = defaultAccount.id
        if (accounts.none { it.id == toAccountId }) toAccountId = accounts.firstOrNull { it.id != accountId }?.id.orEmpty()
    }
    LaunchedEffect(kind, amount, accountId, toAccountId, primaryId, secondaryId, date, note) {
        if (draft != null) onDraftChange(EntryDraft(kind, amount, accountId, toAccountId,
            primaryId, secondaryId, date, note, draft.revision))
    }

    Column(if (scrollable) Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
           else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (existing == null) Text("记一笔")
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("expense" to "支出", "income" to "收入", "transfer" to "转账",
                "balance" to "设置余额").forEach { (value, label) ->
                FilterChip(selected = kind == value, onClick = {
                    kind = value
                    primaryId = if (value == "income") uncategorizedIncome.id else uncategorizedExpense.id
                    secondaryId = ""
                    error = null
                }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                    border = BorderStroke(1.dp, if (kind == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline))
            }
        }
        AmountField(amount, if (kind == "balance") "目标余额（元，可为负）" else "金额（元）",
            allowNegative = kind == "balance", modifier = Modifier.fillMaxWidth(),
            onCalculated = { expression -> note = appendFormulaToNote(note, expression) }) { amount = it }
        ChoiceButton(if (kind == "transfer") "转出账户" else "账户", accountId,
            accounts.map { it.id to it.name }, Modifier.fillMaxWidth(),
            secondaryText = { "¥ ${formatCents(data.balance(it))}" }) { accountId = it }
        if (kind == "transfer") {
            ChoiceButton("转入账户", toAccountId, accounts.map { it.id to it.name },
                Modifier.fillMaxWidth(),
                secondaryText = { "¥ ${formatCents(data.balance(it))}" }) { toAccountId = it }
        } else if (kind != "balance") {
            ChoiceButton("一级分类", primaryId, primary.map { it.id to it.name },
                Modifier.fillMaxWidth()) { primaryId = it; secondaryId = "" }
            if (secondary.isNotEmpty()) ChoiceButton("二级分类", secondaryId,
                listOf("" to "不选") + secondary.map { it.id to it.name },
                Modifier.fillMaxWidth()) { secondaryId = it }
        }
        CalendarDateField(date, "日期", Modifier.fillMaxWidth()) { date = it }
        OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") },
            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 2)
        if (kind == "balance") Text("将所选账户在该日期的余额设为目标值；不计入收入、支出或预算。")
        error?.let { Text(it) }
        Button(onClick = {
                val result = runCatching {
                    val cents = parseCents(amount, allowNegative = kind == "balance")
                    parseUserDate(date)
                    require(accountId.isNotBlank()) { "请先创建并选择账户" }
                    if (kind == "transfer") require(toAccountId.isNotBlank() && toAccountId != accountId) {
                        "请选择不同的转入账户"
                    } else if (kind != "balance") require(primaryId.isNotBlank()) { "请选择一级分类" }
                    Entry(existing?.id.orEmpty(), kind, accountId,
                        if (kind == "transfer") toAccountId else null,
                        if (kind == "transfer" || kind == "balance") null else secondaryId.ifBlank { primaryId },
                        cents, date, note, existing?.recordedAtMs?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        existing?.sourceKey.orEmpty())
                }
                result.onSuccess(onSave).onFailure { error = userFacingError(it, "输入有误") }
            }, modifier = Modifier.fillMaxWidth(), enabled = accounts.isNotEmpty()) { Text("保存记录") }
    }
}

@Composable
fun AccountDialog(existing: Account?, currentRateUnits: Int, onDismiss: () -> Unit,
                  onSave: (String?, String, Long, String, Int, String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var opening by remember { mutableStateOf(existing?.let { formatCents(it.openingCents) } ?: "0.00") }
    var investmentType by remember { mutableStateOf(existing?.investmentType ?: "none") }
    var annualRate by remember { mutableStateOf(formatAnnualRate(currentRateUnits)) }
    var maturityDate by remember { mutableStateOf(existing?.fixedMaturityDate ?: LocalDate.now().plusMonths(12).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加账户" else "编辑账户") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("账户名称") }, singleLine = true)
                AmountField(opening, "初始余额（元，可为负）", allowNegative = true,
                    modifier = Modifier.fillMaxWidth()) { opening = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "普通", "demand" to "活期", "fixed" to "定期").forEach { (value, label) ->
                        FilterChip(selected = investmentType == value, onClick = { investmentType = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                            border = BorderStroke(1.dp, if (investmentType == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline))
                    }
                }
                if (investmentType != "none") {
                    OutlinedTextField(annualRate, { annualRate = it }, label = { Text("年化利率（%，最多四位小数）") },
                        singleLine = true)
                    if (investmentType == "demand") Text("按每日余额计息，月底结算；本金可随时转入转出。")
                    else {
                        Text("开始日期：${existing?.fixedStartDate ?: LocalDate.now()}")
                        CalendarDateField(maturityDate, "到期日期", Modifier.fillMaxWidth()) {
                            maturityDate = it
                        }
                        Text("到期前不能转出；到期日按实际每日余额结算利息并转为可用余额。")
                    }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = { DialogOutlineAction("保存", emphasized = true, onClick = {
            runCatching {
                require(name.isNotBlank()) { "请输入账户名称" }
                if (investmentType == "fixed") {
                    require(parseUserDate(maturityDate).isAfter(
                        parseUserDate(existing?.fixedStartDate ?: LocalDate.now().toString()))) {
                        "到期日期须晚于开始日期"
                    }
                }
                parseCents(opening, allowNegative = true) to
                    if (investmentType != "none") parseAnnualRateUnits(annualRate) else 0
            }.onSuccess { (cents, rateUnits) -> onSave(existing?.id, name, cents, investmentType,
                rateUnits, maturityDate.takeIf { investmentType == "fixed" }) }
                .onFailure { error = userFacingError(it, "输入有误") }
        }) },
        dismissButton = { DialogOutlineAction("取消", onClick = onDismiss) })
}

@Composable
fun ScheduleDialog(data: LedgerSnapshot, onDismiss: () -> Unit,
                   onSave: (TransferSchedule) -> Unit) {
    val accounts = selectableAccounts(data)
    var fromId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var toId by remember { mutableStateOf(accounts.getOrNull(1)?.id.orEmpty()) }
    var amount by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var frequency by remember { mutableStateOf("monthly") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("定时转账") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton("转出", fromId, accounts.map { it.id to it.name },
                    secondaryText = { "¥ ${formatCents(data.balance(it))}" }) { fromId = it }
                ChoiceButton("转入", toId, accounts.map { it.id to it.name },
                    secondaryText = { "¥ ${formatCents(data.balance(it))}" }) { toId = it }
                AmountField(amount, "每次金额（元）", modifier = Modifier.fillMaxWidth()) { amount = it }
                ChoiceButton("频率", frequency, listOf("daily" to "每天", "weekly" to "每周",
                    "monthly" to "每月")) { frequency = it }
                CalendarDateField(startDate, "首次日期", Modifier.fillMaxWidth()) {
                    startDate = it
                }
                Text("每月任务按首次日期的日号执行；31 号在短月改为月末。")
                OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") })
                error?.let { Text(it) }
            }
        },
        confirmButton = { DialogOutlineAction("保存", emphasized = true, onClick = {
            runCatching {
                require(fromId.isNotBlank() && toId.isNotBlank() && fromId != toId) {
                    "请选择两个不同账户"
                }
                val date = parseUserDate(startDate)
                require(!date.isBefore(LocalDate.now())) { "首次日期不能早于今天" }
                TransferSchedule("", fromId, toId, parseCents(amount), startDate,
                    frequency, true, note)
            }.onSuccess(onSave).onFailure { error = userFacingError(it, "输入有误") }
        }) },
        dismissButton = { DialogOutlineAction("取消", onClick = onDismiss) })
}

@Composable
fun CategoryDialog(data: LedgerSnapshot, existing: Category?, onDismiss: () -> Unit,
                   initialKind: String = "expense", initialParentId: String? = null,
                   onSave: (String?, String, String, String?) -> Unit) {
    var name by remember(existing?.id, initialKind, initialParentId) {
        mutableStateOf(existing?.name.orEmpty())
    }
    var kind by remember(existing?.id, initialKind, initialParentId) {
        mutableStateOf(existing?.kind ?: initialKind)
    }
    var parentId by remember(existing?.id, initialKind, initialParentId) {
        mutableStateOf(existing?.parentId ?: initialParentId.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val parents = data.categories.filter { it.kind == kind && it.parentId == null && it.id != existing?.id &&
        it.id !in setOf(uncategorizedExpense.id, uncategorizedIncome.id) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加分类" else "编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "expense", enabled = existing == null,
                        onClick = { kind = "expense"; parentId = "" }, label = { Text("支出") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                        border = BorderStroke(1.dp, if (kind == "expense") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline))
                    FilterChip(selected = kind == "income", enabled = existing == null,
                        onClick = { kind = "income"; parentId = "" }, label = { Text("收入") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                        border = BorderStroke(1.dp, if (kind == "income") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline))
                }
                ChoiceButton("所属一级分类", parentId,
                    listOf("" to "无（创建一级分类）") + parents.map { it.id to it.name },
                    Modifier.fillMaxWidth()) { parentId = it }
                error?.let { Text(it) }
            }
        },
        confirmButton = { DialogOutlineAction("保存", emphasized = true, onClick = {
            if (name.isBlank()) error = "请输入分类名称"
            else onSave(existing?.id, name, kind, parentId.takeIf { it.isNotBlank() })
        }) },
        dismissButton = { DialogOutlineAction("取消", onClick = onDismiss) })
}

@Composable
fun BudgetDialog(existingCents: Long?, title: String, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember { mutableStateOf(existingCents?.let(::formatCents) ?: "0.00") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                AmountField(amount, "每月预算（元）", allowZero = true,
                    modifier = Modifier.fillMaxWidth()) { amount = it }
                error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { DialogOutlineAction("保存", emphasized = true, onClick = {
            runCatching { parseCents(amount, allowNegative = true).also { require(it >= 0) { "预算不能为负" } } }
                .onSuccess(onSave).onFailure { error = userFacingError(it, "输入有误") }
        }) },
        dismissButton = { DialogOutlineAction("取消", onClick = onDismiss) })
}
