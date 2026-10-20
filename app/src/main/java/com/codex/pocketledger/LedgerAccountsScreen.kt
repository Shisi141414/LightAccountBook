package com.codex.pocketledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun AccountsScreen(data: LedgerSnapshot, modifier: Modifier, vm: LedgerViewModel) {
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var accountDialog by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf<Account?>(null) }
    var scheduleDialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("全部余额", style = MaterialTheme.typography.titleMedium)
                Text("¥ ${formatCents(data.totalBalance())}",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("其中活动余额 ¥ ${formatCents(data.liquidBalance())}")
            }
        }
        Text("账户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        (data.accounts.filter { it.id != defaultAccount.id } +
            data.accounts.filter { it.id == defaultAccount.id }).forEach { account ->
            Card(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(account.name, fontWeight = FontWeight.Bold)
                        Text("¥ ${formatCents(data.balance(account.id))}", fontWeight = FontWeight.Bold)
                    }
                    Text("初始余额 ¥ ${formatCents(account.openingCents)}",
                        style = MaterialTheme.typography.bodySmall)
                    when (account.investmentType) {
                        "demand" -> Text("活期 · 年化 ${formatAnnualRate(data.rateOn(account.id, LocalDate.now()))}%",
                            style = MaterialTheme.typography.bodySmall)
                        "fixed" -> Text("定期 · 年化 ${formatAnnualRate(data.rateOn(account.id, LocalDate.now()))}% · " +
                            "${account.fixedMaturityDate} 到期" +
                            if (account.isLiquid(LocalDate.now())) " · 已可取用" else " · 暂不可取用",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (account.id == defaultAccount.id) {
                        Text("固定默认账户", style = MaterialTheme.typography.bodySmall)
                        if (data.removedAccounts.isNotEmpty()) Text("包含已删除账户的余额与明细",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    }
                    if (account.id != defaultAccount.id) SplitCardActions(
                        onEdit = { editingAccount = account; accountDialog = true },
                        onDelete = { deletingAccount = account })
                }
            }
        }
        OutlinedButton(onClick = { editingAccount = null; accountDialog = true }) { Text("＋ 添加账户") }
        Text("定时转账", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        data.schedules.forEach { schedule ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val frequency = when (schedule.frequency) {
                        "daily" -> "每天"; "weekly" -> "每周"; else -> "每月"
                    }
                    Text("${data.accountName(schedule.fromId)} → ${data.accountName(schedule.toId)}\n" +
                        "$frequency ¥ ${formatCents(schedule.amountCents)} · ${schedule.startDate}" +
                        if (schedule.active) "" else " · 已停用", modifier = Modifier.weight(1f))
                    if (schedule.active) TextButton(onClick = { vm.stopSchedule(schedule) }) { Text("停用") }
                }
            }
        }
        OutlinedButton(onClick = { scheduleDialog = true }) { Text("＋ 添加定时转账") }
    }
    if (accountDialog) AccountDialog(editingAccount,
        editingAccount?.let { data.rateOn(it.id, LocalDate.now()) } ?: 0,
        onDismiss = { accountDialog = false }) { id, name, cents, type, rate, maturity ->
        vm.saveAccount(id, name, cents, type, rate, maturity)
        accountDialog = false
    }
    if (scheduleDialog) ScheduleDialog(data, onDismiss = { scheduleDialog = false }) { schedule ->
        vm.saveSchedule(schedule)
        scheduleDialog = false
    }
    deletingAccount?.let { account ->
        AlertDialog(onDismissRequest = { deletingAccount = null }, title = { Text("删除${account.name}？") },
            text = { Text("历史明细和余额会归入“未指定账户”；相关定时转账将停用。") },
            confirmButton = { TextButton(onClick = {
                vm.deleteAccount(account)
                deletingAccount = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingAccount = null }) { Text("取消") } })
    }
}
