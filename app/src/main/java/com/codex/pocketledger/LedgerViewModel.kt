package com.codex.pocketledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

data class EntryDraft(
    val kind: String = "expense", val amount: String = "", val accountId: String = "",
    val toAccountId: String = "", val primaryId: String = "", val secondaryId: String = "",
    val date: String = LocalDate.now().toString(), val note: String = "", val revision: Int = 0,
)

fun EntryDraft.afterSave(): EntryDraft = copy(amount = "", note = "", revision = revision + 1)

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LedgerRepository(application)
    private val writeMutex = Mutex()
    private val settings = application.getSharedPreferences("ledger_settings", Application.MODE_PRIVATE)
    val snapshot: StateFlow<LedgerSnapshot> = repository.snapshots.stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5_000), LedgerSnapshot(emptyList(), builtInCategories,
            emptyList(), emptyList(), emptyList()))
    val syncStatus = MutableStateFlow("尚未同步")
    val folder = MutableStateFlow(repository.syncFolderName)
    val themeId = MutableStateFlow(settings.getString("theme_id", "lavender") ?: "lavender")
    val activeBookId = repository.activeBookId
    val books: StateFlow<List<Book>> = repository.books.stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5_000), listOf(Book(DEFAULT_BOOK_ID, "我的账本")))
    private fun loadDraft(bookId: String): EntryDraft = runCatching {
        val key = if (bookId == DEFAULT_BOOK_ID) "entry_draft" else "entry_draft_$bookId"
        val saved = JSONObject(settings.getString(key, "{}") ?: "{}")
        EntryDraft(saved.optString("kind", "expense"), saved.optString("amount"),
            saved.optString("accountId"), saved.optString("toAccountId"),
            saved.optString("primaryId"), saved.optString("secondaryId"),
            saved.optString("date", LocalDate.now().toString()), saved.optString("note"))
    }.getOrDefault(EntryDraft())
    val draft = MutableStateFlow(loadDraft(activeBookId.value))

    fun updateDraft(value: EntryDraft) {
        if (value.revision != draft.value.revision) return
        storeDraft(value)
    }

    private fun storeDraft(value: EntryDraft) {
        draft.value = value
        val key = if (activeBookId.value == DEFAULT_BOOK_ID) "entry_draft"
            else "entry_draft_${activeBookId.value}"
        settings.edit().putString(key, JSONObject()
            .put("kind", value.kind).put("amount", value.amount).put("accountId", value.accountId)
            .put("toAccountId", value.toAccountId).put("primaryId", value.primaryId)
            .put("secondaryId", value.secondaryId).put("date", value.date).put("note", value.note)
            .toString()).apply()
    }

    private fun clearDraft() = storeDraft(draft.value.afterSave())

    fun chooseBook(id: String) {
        require(books.value.any { it.id == id })
        viewModelScope.launch {
            writeMutex.withLock {
                repository.chooseBook(id)
                draft.value = loadDraft(id)
            }
        }
    }

    fun saveBook(id: String?, name: String) = write {
        require(name.isNotBlank() && name.length <= 40) { "账本名称应为 1–40 字" }
        val bookId = repository.put("book", id, bookPayload(name))
        if (id == null) {
            repository.chooseBook(bookId)
            draft.value = loadDraft(bookId)
        }
    }

    fun setThemeId(id: String) {
        require(ledgerThemes.any { it.id == id })
        themeId.value = id
        settings.edit().putString("theme_id", id).apply()
    }

    fun updateLauncherIconInBackground() {
        runCatching { ThemeLauncherIcon.apply(getApplication(), themeId.value) }
            .onFailure { syncStatus.value = "桌面图标切换失败，请重试" }
    }

    fun chooseFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            syncStatus.value = "正在准备同步文件夹…"
            runCatching {
                repository.chooseFolder(uri)
                folder.value = repository.syncFolderName
            }.onFailure { syncStatus.value = userFacingError(it, "文件夹授权失败，请重新选择") }
                .onSuccess { sync() }
        }
    }

    fun sync() {
        viewModelScope.launch {
            syncStatus.value = "正在检查到期任务…"
            syncStatus.value = runCatching {
                if (folder.value != null) repository.syncOnce()
                val created = repository.processAutomation()
                val report = if (folder.value != null) repository.syncOnce().toString() else "本地账本已更新"
                "$report；自动生成 $created 笔"
            }.getOrElse { userFacingError(it, "刷新失败，请重试") }
        }
    }

    private fun write(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            writeMutex.withLock {
                val result = runCatching { action() }
                if (result.isFailure) {
                    syncStatus.value = userFacingError(result.exceptionOrNull()!!, "保存失败，请重试")
                } else {
                    onSuccess()
                    if (folder.value != null) {
                        syncStatus.value = runCatching {
                            val created = repository.processAutomation()
                            "${repository.syncOnce()}；自动生成 $created 笔"
                        }.getOrElse { "本地已保存；${userFacingError(it, "同步失败，请重试")}" }
                    } else {
                        val created = repository.processAutomation()
                        syncStatus.value = "已保存在本机；自动生成 $created 笔；选择文件夹后可同步"
                    }
                }
            }
        }
    }

    fun saveAccount(id: String?, name: String, openingCents: Long,
                    investmentType: String, annualRateUnits: Int, maturityDate: String?) = write {
        require(name.isNotBlank() && name.length <= 40) { "账户名称应为 1–40 字" }
        require(annualRateUnits in 0..10_000_000)
        require(investmentType in setOf("none", "demand", "fixed"))
        val existing = repository.currentSnapshot().accounts.firstOrNull { it.id == id }
        val startDate = if (investmentType == "fixed") existing?.fixedStartDate ?: LocalDate.now().toString() else null
        if (investmentType == "fixed") {
            require(maturityDate != null && parseUserDate(maturityDate).isAfter(
                parseUserDate(startDate ?: LocalDate.now().toString()))) {
                "到期日期须晚于开始日期"
            }
        }
        val accountId = repository.put("account", id,
            accountPayload(name, openingCents, investmentType == "demand", investmentType,
                startDate, maturityDate))
        val currentRate = repository.currentSnapshot().rateOn(accountId, LocalDate.now())
        val desiredRate = if (investmentType != "none") annualRateUnits else 0
        if (desiredRate != currentRate) {
            val now = System.currentTimeMillis()
            repository.put("rate", null, ratePayload(accountId, LocalDate.now().toString(), desiredRate, now))
        }
    }

    fun saveSchedule(schedule: TransferSchedule) = write {
        require(schedule.fromId != schedule.toId && schedule.amountCents > 0)
        require(schedule.frequency in setOf("daily", "weekly", "monthly"))
        require(parseUserDate(schedule.startDate) >= LocalDate.now()) { "开始日期不能早于今天" }
        require(schedule.note.length <= 300)
        val accounts = repository.currentSnapshot().accounts
        require(accounts.any { it.id == schedule.fromId } && accounts.any { it.id == schedule.toId })
        require(accounts.first { it.id == schedule.fromId }.isLiquid(parseUserDate(schedule.startDate))) {
            "定期账户到期前不能转出"
        }
        repository.put("schedule", null, schedulePayload(schedule))
    }

    fun stopSchedule(schedule: TransferSchedule) = write {
        repository.put("schedule", schedule.id, schedulePayload(schedule.copy(active = false)))
    }

    fun saveCategory(id: String?, name: String, kind: String, parentId: String?) = write {
        require(name.isNotBlank() && name.length <= 40) { "分类名称应为 1–40 字" }
        require(kind in setOf("income", "expense"))
        require(id !in setOf(uncategorizedExpense.id, uncategorizedIncome.id)) { "默认分类不可更改" }
        val categories = repository.currentSnapshot().categories
        require(id == null || categories.firstOrNull { it.id == id }?.kind == kind) { "已有分类不能改变收支类型" }
        val parent = categories.firstOrNull { it.id == parentId }
        require(parentId == null || (parentId != id && parent != null && parent.parentId == null && parent.kind == kind)) {
            "二级分类必须属于同类型的一级分类"
        }
        repository.put("category", id, categoryPayload(name, kind, parentId))
    }

    fun deleteAccount(account: Account) = write {
        require(account.id != defaultAccount.id) { "默认账户不可删除" }
        repository.currentSnapshot().schedules.filter {
            it.active && (it.fromId == account.id || it.toId == account.id)
        }.forEach { repository.put("schedule", it.id, schedulePayload(it.copy(active = false))) }
        repository.delete("account", account.id)
    }

    fun deleteCategory(category: Category) = write {
        require(category.id !in setOf(uncategorizedExpense.id, uncategorizedIncome.id)) {
            "默认分类不可删除"
        }
        val children = repository.currentSnapshot().categories.filter { it.parentId == category.id }
        children.forEach { repository.delete("category", it.id) }
        repository.delete("category", category.id)
    }

    fun saveBudget(month: YearMonth, categoryId: String?, limitCents: Long) = write {
        require(limitCents >= 0) { "预算不能为负数" }
        require(categoryId == null || snapshot.value.categories.any {
            it.id == categoryId && it.kind == "expense" && it.parentId == null
        }) { "预算只能设置在一级支出分类" }
        repository.put("budget", budgetId(month, categoryId),
            budgetPayload(month.toString(), categoryId, limitCents))
    }

    private suspend fun persistEntry(entry: Entry) {
        if (entry.kind != "balance") require(entry.amountCents > 0) { "金额必须大于 0" }
        require(entry.note.length <= 300) { "备注最多 300 字" }
        val date = parseUserDate(entry.date)
        val snapshot = repository.currentSnapshot()
        val accounts = snapshot.accounts
        require(accounts.any { it.id == entry.accountId }) { "请选择账户" }
        if (entry.kind == "expense" || entry.kind == "transfer") {
            require(accounts.first { it.id == entry.accountId }.isLiquid(date)) { "定期账户到期前不能转出" }
        }
        if (entry.kind == "balance") {
            require(entry.toAccountId == null && entry.categoryId == null)
        } else if (entry.kind == "transfer") {
            require(entry.toAccountId != null && entry.toAccountId != entry.accountId &&
                accounts.any { it.id == entry.toAccountId }) { "请选择不同的转入账户" }
        } else {
            require(entry.kind in setOf("income", "expense"))
            require(snapshot.categories.any { it.id == entry.categoryId && it.kind == entry.kind }) {
                "请选择分类"
            }
        }
        repository.put("entry", entry.id.takeIf { it.isNotBlank() }, entryPayload(entry))
    }

    fun saveEntry(entry: Entry) = write { persistEntry(entry) }
    fun saveHomeEntry(entry: Entry) = write(onSuccess = ::clearDraft) { persistEntry(entry) }

    fun deleteEntry(entry: Entry) = write { repository.delete("entry", entry.id) }
    fun resolve(state: ObjectState, chosenOpId: String) = write { repository.resolve(state, chosenOpId) }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(application) as T
    }
}
