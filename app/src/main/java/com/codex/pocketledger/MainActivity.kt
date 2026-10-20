package com.codex.pocketledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    private val vm: LedgerViewModel by viewModels { LedgerViewModel.Factory(application) }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        scheduleLedgerWorker(this)
        setContent {
            val themeId by vm.themeId.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = ledgerColorScheme(themeId)) {
                LedgerApp(vm) { folderPicker.launch(null) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.sync()
    }

    override fun onStop() {
        super.onStop()
        // Some launchers restart a foreground app when its enabled alias changes.
        vm.updateLauncherIconInBackground()
    }
}

@Composable
private fun LedgerApp(vm: LedgerViewModel, openFolder: () -> Unit) {
    val data by vm.snapshot.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val activeBookId by vm.activeBookId.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var editEntry by remember { mutableStateOf<Entry?>(null) }
    var deleteEntry by remember { mutableStateOf<Entry?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(status) {
        if (status.startsWith("保存失败") || status.startsWith("刷新失败") ||
            status.startsWith("文件夹权限") || status.startsWith("文件夹授权失败") ||
            status.startsWith("本地已保存；") || status.startsWith("桌面图标切换失败"))
            snackbar.showSnackbar(status)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Text("轻记账 · ${books.firstOrNull { it.id == activeBookId }?.name ?: "我的账本"}",
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(18.dp),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        bottomBar = {
            NavigationBar {
                listOf("首页", "明细", "统计", "预算", "账户", "设置").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("⌂", "≡", "◉", "▥", "▣", "⚙")[index],
                            style = MaterialTheme.typography.headlineSmall) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(data, draft, vm::updateDraft, Modifier.padding(padding),
                onSave = vm::saveHomeEntry, onBudgets = { tab = 3 }, onEdit = { editEntry = it })
            1 -> DetailsScreen(data, Modifier.padding(padding), onEdit = { editEntry = it },
                onDelete = { deleteEntry = it })
            2 -> StatisticsScreen(data, Modifier.padding(padding))
            3 -> BudgetScreen(data, Modifier.padding(padding), vm::saveBudget)
            4 -> AccountsScreen(data, Modifier.padding(padding), vm)
            else -> SettingsScreen(data, books, activeBookId, status, folder, Modifier.padding(padding), vm,
                openFolder = openFolder)
        }
    }

    if (folder == null) {
        AlertDialog(onDismissRequest = {},
            title = { Text("选择账本存储位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请选择本地文件夹。应用会先查找已有账本；找到后直接使用，只有未找到时才创建 LightAccountBook。")
                    Text("使用文件夹同步软件时，请同步应用实际使用的账本文件夹。")
                    if (status.contains("失败") || status.contains("不可读写") || status.contains("无法")) {
                        Text(status, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { Button(onClick = openFolder) { Text("选择本地文件夹") } })
    }

    if (editEntry != null) {
        EntryDialog(data, editEntry, onDismiss = { editEntry = null }, onDelete = {
            deleteEntry = editEntry
            editEntry = null
        }) { entry ->
            vm.saveEntry(entry)
            editEntry = null
        }
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text("删除这笔记录？") },
            text = { Text("删除会同步到其他设备。") },
            confirmButton = { TextButton(onClick = { vm.deleteEntry(entry); deleteEntry = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } })
    }
}

@Composable
private fun HomeScreen(data: LedgerSnapshot, draft: EntryDraft, onDraftChange: (EntryDraft) -> Unit,
                       modifier: Modifier, onSave: (Entry) -> Unit,
                       onBudgets: () -> Unit, onEdit: (Entry) -> Unit) {
    val month = YearMonth.now()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("当前活动余额", style = MaterialTheme.typography.titleMedium)
                    Text("¥ ${formatCents(data.liquidBalance())}",
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                CardActionDivider()
                IncomeExpenseBalanceRow(data.monthExpense(month), data.monthIncome(month), monthly = true)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                EntryForm(data, null, onSave, draft = draft, onDraftChange = onDraftChange)
            }
        }
        Text("本月总预算", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        run {
            val used = data.monthExpense(month)
            val budget = data.budget(month)
            val ratio = budget?.let { budgetRatio(used, it.limitCents) }
            val tone = budgetLevel(used, budget?.limitCents).color(MaterialTheme.colorScheme.outline)
            Card(Modifier.fillMaxWidth().clickable(onClick = onBudgets)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("总支出 · 点击查看预算", fontWeight = FontWeight.Bold)
                    Text("已用 ¥ ${formatCents(used)}  ·  ${budget?.let { "预算 ¥ ${formatCents(it.limitCents)}" } ?: "未设置预算"}",
                        color = tone)
                    if (ratio != null) LinearProgressIndicator(
                        progress = { ratio.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = tone)
                }
            }
        }
        Text("最近账目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (data.entries.isEmpty()) Text("暂无账目")
        else data.entries.take(5).forEach { entry -> RecentEntryCard(data, entry) { onEdit(entry) } }
        if (data.conflicts.isNotEmpty()) Text("有 ${data.conflicts.size} 项同步冲突，请到设置中处理。",
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RecentEntryCard(data: LedgerSnapshot, entry: Entry, onClick: () -> Unit) {
    val label = when (entry.kind) {
        "income" -> "收入 · ${data.categoryPath(entry.categoryId)}"
        "expense" -> "支出 · ${data.categoryPath(entry.categoryId)}"
        "transfer" -> "转账 · ${data.accountName(entry.accountId)} → ${data.accountName(entry.toAccountId)}"
        else -> "设置余额 · ${data.accountName(entry.accountId)}"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text("${entry.date}${if (entry.note.isBlank()) "" else " · ${entry.note}"}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("¥ ${formatCents(entry.amountCents)}", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetailsScreen(data: LedgerSnapshot, modifier: Modifier,
                          onEdit: (Entry) -> Unit, onDelete: (Entry) -> Unit) {
    var startText by remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var endText by remember { mutableStateOf(LocalDate.now().toString()) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    val start = runCatching { if (startText.isBlank()) null else LocalDate.parse(startText) }.getOrNull()
    val end = runCatching { if (endText.isBlank()) null else LocalDate.parse(endText) }.getOrNull()
    val valid = (startText.isBlank() || start != null) && (endText.isBlank() || end != null) &&
        (start == null || end == null || !start.isAfter(end))
    val filtered = if (valid) data.filteredEntries(start, end, accountId, categoryId) else emptyList()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarDateField(startText, "开始日期", Modifier.weight(1f),
                allowClear = true, compactDisplay = true) {
                startText = it
            }
            CalendarDateField(endText, "结束日期", Modifier.weight(1f),
                allowClear = true, compactDisplay = true) {
                endText = it
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("账户", accountId,
                listOf(null to "全部账户") + selectableAccounts(data).map { it.id to it.name },
                Modifier.weight(1f), secondaryText = { id ->
                    id?.let { "¥ ${formatCents(data.balance(it))}" }
                }) { accountId = it }
            ChoiceButton("分类", categoryId,
                listOf(null to "全部分类") + data.categories.map { it.id to data.categoryPath(it.id) },
                Modifier.weight(1f)) { categoryId = it }
        }
        if (!valid) Text("日期格式或范围不正确", color = MaterialTheme.colorScheme.error)
        val income = filtered.filter { it.kind == "income" }.sumOf { it.amountCents }
        val expense = filtered.filter { it.kind == "expense" }.sumOf { it.amountCents }
        Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            IncomeExpenseBalanceRow(expense, income, monthly = false)
        }
        val expenses = filtered.filter { it.kind == "expense" }
        val byAccount = expenses.groupBy { it.accountId }.map { (id, rows) ->
            "${data.accountName(id)} ¥${formatCents(rows.sumOf { it.amountCents })}"
        }
        val byCategory = expenses.groupBy { data.categoryParent(it.categoryId) ?: it.categoryId }.map { (id, rows) ->
            "${data.categoryName(id)} ¥${formatCents(rows.sumOf { it.amountCents })}"
        }
        if (byAccount.isNotEmpty() || byCategory.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (byAccount.isNotEmpty()) Text("账户支出：${byAccount.joinToString("  ·  ")}",
                    style = MaterialTheme.typography.bodySmall)
                if (byCategory.isNotEmpty()) Text("一级分类支出：${byCategory.joinToString("  ·  ")}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (filtered.isEmpty()) Text("当前筛选条件下没有记录")
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { entry ->
                EntryCard(data, entry, onEdit = { onEdit(entry) }, onDelete = { onDelete(entry) })
            }
        }
    }
}

@Composable
private fun EntryCard(data: LedgerSnapshot, entry: Entry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val label = when (entry.kind) {
                "transfer" -> "转账 ${data.accountName(entry.accountId)} → ${data.accountName(entry.toAccountId)}"
                "balance" -> "设置余额 · ${data.accountName(entry.accountId)}"
                "income" -> "收入 · ${data.categoryPath(entry.categoryId)} · ${data.accountName(entry.accountId)}"
                else -> "支出 · ${data.categoryPath(entry.categoryId)} · ${data.accountName(entry.accountId)}"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, modifier = Modifier.weight(1f))
                Text("${if (entry.kind == "income") "+" else if (entry.kind == "expense") "−" else ""}¥ ${formatCents(entry.amountCents)}",
                    fontWeight = FontWeight.Bold)
            }
            Text("${entry.date}${if (entry.note.isBlank()) "" else " · ${entry.note}"}",
                style = MaterialTheme.typography.bodySmall)
            }
            SplitCardActions(onEdit, onDelete)
        }
    }
}

@Composable
private fun BudgetScreen(data: LedgerSnapshot, modifier: Modifier,
                         saveBudget: (YearMonth, String?, Long) -> Unit) {
    var monthText by remember { mutableStateOf(YearMonth.now().toString()) }
    var editCategory by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val month = runCatching { YearMonth.parse(monthText) }.getOrNull()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CalendarMonthField(monthText, "月份") { monthText = it }
        if (month == null) Text("请输入有效月份", color = MaterialTheme.colorScheme.error)
        if (month != null) {
            val categories = data.categories.filter {
                it.kind == "expense" && it.parentId == null && it.id != uncategorizedExpense.id
            }
            listOf<String?>(null).plus(categories.map { it.id }).forEach { categoryId ->
                val budget = data.budget(month, categoryId)
                val used = data.monthExpense(month, categoryId)
                val tone = budgetLevel(used, budget?.limitCents).color(MaterialTheme.colorScheme.outline)
                Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (categoryId == null) "总支出预算" else data.categoryName(categoryId),
                            fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已用 ¥ ${formatCents(used)}", color = tone,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("预算 ${budget?.let { "¥ ${formatCents(it.limitCents)}" } ?: "未设置"}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (budget == null) {
                            Spacer(Modifier.fillMaxWidth().height(4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant))
                        } else {
                            LinearProgressIndicator(progress = {
                                budgetRatio(used, budget.limitCents).toFloat().coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth(), color = tone)
                        }
                        }
                        FullCardAction("设置预算") { editCategory = categoryId; editing = true }
                    }
                }
            }
        }
    }
    if (editing && month != null) {
        BudgetDialog(data.budget(month, editCategory)?.limitCents,
            if (editCategory == null) "总支出预算" else data.categoryName(editCategory),
            onDismiss = { editing = false }) { cents ->
            saveBudget(month, editCategory, cents)
            editing = false
        }
    }
}

@Composable
private fun SettingsScreen(data: LedgerSnapshot, books: List<Book>, activeBookId: String,
                           status: String, folder: String?, modifier: Modifier,
                           vm: LedgerViewModel, openFolder: () -> Unit) {
    val themeId by vm.themeId.collectAsStateWithLifecycle()
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var categoryDialog by remember { mutableStateOf(false) }
    var deletingCategory by remember { mutableStateOf<Category?>(null) }
    var categoryKind by remember { mutableStateOf("expense") }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    var syncHelp by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var bookDialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("账本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        books.forEach { book ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterChip(selected = activeBookId == book.id, onClick = { vm.chooseBook(book.id) },
                    modifier = Modifier.weight(1f),
                    label = { Text(book.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = BorderStroke(1.dp, if (activeBookId == book.id) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline))
                TextButton(onClick = { editingBook = book; bookDialog = true }) { Text("重命名") }
            }
        }
        OutlinedButton(onClick = { editingBook = null; bookDialog = true }) { Text("＋ 新建账本") }
        Text("主题色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ledgerThemes.chunked(2).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(selected = themeId == option.id,
                        onClick = { vm.setThemeId(option.id) },
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Spacer(Modifier.size(14.dp).background(option.primary, CircleShape))
                                Text(option.label)
                            }
                        })
                }
            }
        }
        Text("主题立即生效；离开应用后更新桌面图标。", style = MaterialTheme.typography.bodySmall)
        Text("同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("账本文件夹", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(folder ?: "尚未选择", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(if (status.startsWith("同步完成")) "账目和预算已写入本地文件夹" else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = openFolder,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                        Text(if (folder == null) "选择文件夹" else "更换文件夹")
                    }
                    Button(onClick = vm::sync, enabled = folder != null) { Text("立即刷新") }
                }
                TextButton(onClick = { syncHelp = true }) { Text("同步说明") }
            }
        }
        Text("分类管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("expense" to "支出", "income" to "收入").forEach { (kind, label) ->
                FilterChip(selected = categoryKind == kind, onClick = { categoryKind = kind },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                    border = BorderStroke(1.dp, if (categoryKind == kind)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
            }
        }
        OutlinedButton(onClick = {
            editingCategory = null; newCategoryParentId = null; categoryDialog = true
        }, modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
            Text("＋ 新建一级分类")
        }
        data.categories.filter { it.kind == categoryKind && it.parentId == null }
            .sortedBy { it.id in setOf(uncategorizedExpense.id, uncategorizedIncome.id) }
            .forEach { parent ->
            Card(Modifier.fillMaxWidth()) {
                Column {
                    CategorySettingsRow(parent, false, onEdit = {
                        editingCategory = parent; categoryDialog = true
                    }, onDelete = { deletingCategory = parent })
                    data.categories.filter { it.parentId == parent.id }.forEach { child ->
                        CardActionDivider()
                        CategorySettingsRow(child, true, onEdit = {
                            editingCategory = child; categoryDialog = true
                        }, onDelete = { deletingCategory = child })
                    }
                    if (parent.id !in setOf(uncategorizedExpense.id, uncategorizedIncome.id)) {
                        FullCardAction("＋ 新建二级分类") {
                            editingCategory = null; newCategoryParentId = parent.id; categoryDialog = true
                        }
                    }
                }
            }
        }
        if (data.conflicts.isNotEmpty()) {
            Text("同步冲突", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            data.conflicts.forEach { state -> ConflictCard(state, vm::resolve) }
        }
    }
    if (bookDialog) BookDialog(editingBook, onDismiss = { bookDialog = false }) { id, name ->
        vm.saveBook(id, name); bookDialog = false
    }
    if (syncHelp) AlertDialog(onDismissRequest = { syncHelp = false },
        title = { Text("同步说明") },
        text = { Text("应用会优先使用所选文件夹中已有的账本；找不到时才创建 LightAccountBook。" +
            "请用第三方软件双向同步设置页显示的账本文件夹。\n\n当前状态：$status\n\n" +
            "“已写入”表示本机文件夹写入成功，不代表其他设备已收到文件。") },
        confirmButton = { TextButton(onClick = { syncHelp = false }) { Text("知道了") } })
    deletingCategory?.let { category ->
        AlertDialog(onDismissRequest = { deletingCategory = null }, title = { Text("删除${category.name}？") },
            text = { Text("该分类及子分类的历史明细会显示在“未分类”下。") },
            confirmButton = { TextButton(onClick = {
                vm.deleteCategory(category); deletingCategory = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingCategory = null }) { Text("取消") } })
    }
    if (categoryDialog) CategoryDialog(data, editingCategory, onDismiss = { categoryDialog = false },
        initialKind = categoryKind, initialParentId = newCategoryParentId) { id, name, kind, parent ->
        vm.saveCategory(id, name, kind, parent); categoryDialog = false
    }
}

@Composable
private fun CategorySettingsRow(category: Category, child: Boolean, onEdit: () -> Unit,
                                onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (child) 22.dp else 12.dp,
        end = 12.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(category.name,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            fontWeight = if (child) FontWeight.Normal else FontWeight.Medium)
        if (category.id in setOf(uncategorizedExpense.id, uncategorizedIncome.id)) {
            Text("默认",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CompactActionPair(onEdit, onDelete)
        }
    }
}

@Composable
private fun BookDialog(existing: Book?, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "新建账本" else "重命名账本") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("账本名称") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { DialogOutlineAction("保存", emphasized = true, onClick = {
            if (name.isBlank() || name.length > 40) error = "请输入 1–40 字账本名称"
            else onSave(existing?.id, name)
        }) },
        dismissButton = { DialogOutlineAction("取消", onClick = onDismiss) })
}

@Composable
private fun ConflictCard(state: ObjectState, resolve: (ObjectState, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${state.type} · ${state.id.take(8)} 有 ${state.heads.size} 个版本")
            state.heads.forEach { head ->
                val description = if (head.isDelete()) "删除" else runCatching {
                    val p = JSONObject(head.payload)
                    p.optString("name").ifBlank { p.optString("date") + " · " + p.optString("amountCents") }
                }.getOrDefault("数据版本")
                TextButton(onClick = { resolve(state, head.opId) }) {
                    Text("保留：$description · ${head.opId.take(8)}")
                }
            }
        }
    }
}

fun selectableAccounts(data: LedgerSnapshot): List<Account> =
    data.accounts.filter { it.investmentType != "fixed" }

@Composable
fun <T> ChoiceButton(label: String, selected: T, options: List<Pair<T, String>>,
                     modifier: Modifier = Modifier, secondaryText: (T) -> String? = { null },
                     onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val viewHeightPx = LocalView.current.height
    val visibleRows = with(density) {
        // Leave room for the app's bottom bar and the system navigation area.
        val spaceBelow = viewHeightPx - anchorBounds.bottom - 120.dp.toPx()
        (spaceBelow / 48.dp.toPx()).toInt().coerceIn(1, 4)
    }
    val menuHeight = 48.dp * visibleRows
    Column(modifier) {
        OutlinedButton(onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                anchorWidthPx = it.size.width
                anchorBounds = it.boundsInWindow()
            },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Text("$label：${options.firstOrNull { it.first == selected }?.second ?: "请选择"}")
        }
        if (expanded && anchorWidthPx > 0) {
            Popup(onDismissRequest = { expanded = false },
                popupPositionProvider = remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                                       layoutDirection: LayoutDirection,
                                                       popupContentSize: IntSize): IntOffset =
                            IntOffset(anchorBounds.left.coerceIn(0,
                                (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                                anchorBounds.bottom)
                    }
                }, properties = PopupProperties(focusable = true)) {
                Surface(modifier = Modifier.width(with(density) { anchorWidthPx.toDp() })
                    .heightIn(max = menuHeight),
                    shape = MaterialTheme.shapes.medium, shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        options.forEach { (value, text) ->
                            DropdownMenuItem(text = {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(text, Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    secondaryText(value)?.let { Text(it, maxLines = 1) }
                                }
                            }, onClick = { onSelect(value); expanded = false })
                        }
                    }
                }
            }
        }
    }
}
