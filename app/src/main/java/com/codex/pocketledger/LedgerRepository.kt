package com.codex.pocketledger

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

data class SyncReport(val imported: Int, val exported: Int, val invalid: Int) {
    override fun toString(): String = "同步完成：导入 $imported，写出 $exported，异常文件 $invalid"
}

private const val SYNC_FOLDER_NAME = "LightAccountBook"
private const val FOLDER_LAYOUT_KEY = "folder_layout"
private const val NEW_FOLDER_LAYOUT = "light_account_book"
private const val DIRECT_FOLDER_LAYOUT = "direct"
private const val CHILD_FOLDER_LAYOUT = "child"
private const val CHILD_FOLDER_NAME_KEY = "folder_child_name"
private const val FOLDER_DISPLAY_NAME_KEY = "folder_display_name"

class LedgerRepository(private val context: Context) {
    private val db = Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger.db")
        .addMigrations(ledgerMigration1To2).build()
    private val dao = db.dao()
    private val prefs = context.getSharedPreferences("ledger_settings", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private val syncMutex = Mutex()

    val activeBookId = MutableStateFlow(prefs.getString("active_book_id", DEFAULT_BOOK_ID) ?: DEFAULT_BOOK_ID)
    val books: Flow<List<Book>> = dao.observeAll().map(::booksFrom)
    val snapshots: Flow<LedgerSnapshot> = combine(dao.observeAll(), activeBookId) { ops, bookId ->
        snapshotFrom(ops.filter { it.ledgerId == bookId && it.objectType != "book" })
    }
    val folderUri: Uri? get() = prefs.getString("folder_uri", null)?.let(Uri::parse)
    val syncFolderName: String? get() = folderUri?.let {
        prefs.getString(FOLDER_DISPLAY_NAME_KEY, null) ?: when (prefs.getString(FOLDER_LAYOUT_KEY, null)) {
            NEW_FOLDER_LAYOUT -> SYNC_FOLDER_NAME
            CHILD_FOLDER_LAYOUT -> prefs.getString(CHILD_FOLDER_NAME_KEY, null) ?: "已有账本文件夹"
            else -> "原有同步文件夹"
        }
    }

    fun chooseBook(id: String) {
        activeBookId.value = id
        prefs.edit().putString("active_book_id", id).apply()
    }

    suspend fun currentSnapshot(): LedgerSnapshot = withContext(Dispatchers.IO) {
        snapshotFrom(dao.all().filter { it.ledgerId == activeBookId.value && it.objectType != "book" })
    }

    fun chooseFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val selected = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开所选文件夹")
        require(selected.canRead() && selected.canWrite()) { "文件夹不可读写，请重新选择" }
        val keepLegacy = folderUri == uri && prefs.getString(FOLDER_LAYOUT_KEY, null) != NEW_FOLDER_LAYOUT
        if (keepLegacy) return
        require(selected.name != "ledger-v1") { "请选择 ledger-v1 的上一级账本文件夹" }
        val directData = selected.findFile("ledger-v1")
        require(directData == null || directData.isDirectory) { "ledger-v1 已存在但不是文件夹" }
        val children = selected.listFiles()
        val named = children.firstOrNull { it.name == SYNC_FOLDER_NAME }
        require(named == null || named.isDirectory) { "$SYNC_FOLDER_NAME 已存在但不是文件夹" }
        val existing = children.filter { child ->
            child.isDirectory && child.findFile("ledger-v1")?.isDirectory == true
        }
        val layout: String
        val rootName: String
        if (directData != null) {
            layout = DIRECT_FOLDER_LAYOUT
            rootName = selected.name ?: "所选文件夹"
        } else {
            val root = when {
                named != null && named in existing -> named
                existing.size == 1 -> existing.single()
                existing.size > 1 -> error("发现多个已有账本文件夹，请直接选择要使用的那个文件夹")
                selected.name == SYNC_FOLDER_NAME -> selected
                named != null -> named
                else -> selected.createDirectory(SYNC_FOLDER_NAME)
                    ?: error("无法创建 $SYNC_FOLDER_NAME 文件夹")
            }
            layout = when {
                root.uri == selected.uri -> DIRECT_FOLDER_LAYOUT
                root.name == SYNC_FOLDER_NAME -> NEW_FOLDER_LAYOUT
                else -> CHILD_FOLDER_LAYOUT
            }
            rootName = root.name ?: error("无法读取账本文件夹名称")
        }
        prefs.edit().putString("folder_uri", uri.toString())
            .putString(FOLDER_LAYOUT_KEY, layout)
            .putString(FOLDER_DISPLAY_NAME_KEY, rootName)
            .putString(CHILD_FOLDER_NAME_KEY, rootName.takeIf { layout == CHILD_FOLDER_LAYOUT })
            .apply()
    }

    suspend fun put(type: String, id: String?, payload: JSONObject): String = withContext(Dispatchers.IO) {
        require(type in setOf("book", "account", "category", "budget", "entry", "rate", "schedule"))
        val ledgerId = if (type == "book") DEFAULT_BOOK_ID else activeBookId.value
        val objectId = id ?: UUID.randomUUID().toString()
        val heads = currentObjects(dao.all().filter { it.ledgerId == ledgerId })[type to objectId]?.heads.orEmpty()
        val op = LedgerOp(UUID.randomUUID().toString(), type, objectId,
            heads.joinToString(",") { it.opId }, payload.toString(), deviceId, System.currentTimeMillis(),
            ledgerId)
        dao.insert(op)
        objectId
    }

    suspend fun putGenerated(id: String, payload: JSONObject, dueDate: LocalDate,
                             ledgerId: String) = withContext(Dispatchers.IO) {
        val serialized = payload.toString()
        val opId = UUID.nameUUIDFromBytes("generated:$ledgerId:$id:$serialized".toByteArray(StandardCharsets.UTF_8)).toString()
        val automationDevice = UUID.nameUUIDFromBytes("pocketledger-automation".toByteArray()).toString()
        dao.insert(LedgerOp(opId, "entry", id, "", serialized, automationDevice,
            dueDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), ledgerId))
    }

    suspend fun processAutomation(today: LocalDate = LocalDate.now()): Int = withContext(Dispatchers.IO) {
        var created = 0
        val all = dao.all()
        for (book in booksFrom(all)) {
            val bookId = book.id
            var snapshot = snapshotFrom(all.filter { it.ledgerId == bookId && it.objectType != "book" })
            for (schedule in snapshot.schedules) {
                val from = snapshot.accounts.firstOrNull { it.id == schedule.fromId } ?: continue
                if (snapshot.accounts.none { it.id == schedule.toId }) continue
                for (date in dueDates(schedule, today)) {
                    if (!from.isLiquid(date)) continue
                    val entry = scheduledTransfer(schedule, date)
                    if (snapshot.entries.none { it.sourceKey == entry.sourceKey }) {
                        if (putGenerated(entry.id, entryPayload(entry), date, bookId) != -1L) created++
                    }
                }
            }
            snapshot = snapshotFrom(dao.all().filter { it.ledgerId == bookId && it.objectType != "book" })
            for (account in snapshot.accounts) {
                if (account.investmentType == "fixed") {
                    val maturity = account.fixedMaturityDate?.let(LocalDate::parse) ?: continue
                    val sourceKey = "fixed-interest:${account.id}:$maturity"
                    if (!maturity.isAfter(today) && snapshot.entries.none { it.sourceKey == sourceKey }) {
                        val entry = fixedTermInterest(snapshot, account)
                        if (entry != null && putGenerated(entry.id, entryPayload(entry), maturity, bookId) != -1L) {
                            created++
                            snapshot = snapshotFrom(dao.all().filter { it.ledgerId == bookId && it.objectType != "book" })
                        }
                    }
                    continue
                }
                if (!account.demandDeposit) continue
                val firstRate = snapshot.rateChanges.filter { it.accountId == account.id && it.annualRateUnits > 0 }
                    .minByOrNull { it.date } ?: continue
                var month = YearMonth.from(LocalDate.parse(firstRate.date))
                while (month.isBefore(YearMonth.from(today))) {
                    val sourceKey = "interest:${account.id}:$month"
                    if (snapshot.entries.none { it.sourceKey == sourceKey }) {
                        val entry = monthlyInterest(snapshot, account, month)
                        if (entry != null && putGenerated(entry.id, entryPayload(entry), month.atEndOfMonth(), bookId) != -1L) {
                            created++
                            snapshot = snapshotFrom(dao.all().filter { it.ledgerId == bookId && it.objectType != "book" })
                        }
                    }
                    month = month.plusMonths(1)
                }
            }
        }
        created
    }

    suspend fun delete(type: String, id: String) = withContext(Dispatchers.IO) {
        val ledgerId = activeBookId.value
        val heads = currentObjects(dao.all().filter { it.ledgerId == ledgerId })[type to id]?.heads.orEmpty()
        require(heads.isNotEmpty() || (type == "account" && builtInAccounts.any { it.id == id }) ||
            (type == "category" && builtInCategories.any { it.id == id }))
        dao.insert(LedgerOp(UUID.randomUUID().toString(), type, id,
            heads.joinToString(",") { it.opId }, "", deviceId, System.currentTimeMillis(), ledgerId))
    }

    suspend fun resolve(state: ObjectState, chosenOpId: String) = withContext(Dispatchers.IO) {
        val ledgerId = activeBookId.value
        val current = currentObjects(dao.all().filter { it.ledgerId == ledgerId })[state.type to state.id]
            ?: error("对象不存在")
        require(current.conflict)
        val chosen = current.heads.firstOrNull { it.opId == chosenOpId } ?: error("版本已变化，请刷新")
        dao.insert(LedgerOp(UUID.randomUUID().toString(), state.type, state.id,
            current.heads.joinToString(",") { it.opId }, chosen.payload, deviceId,
            System.currentTimeMillis(), ledgerId))
    }

    suspend fun syncOnce(): SyncReport = syncMutex.withLock { withContext(Dispatchers.IO) {
        val uri = folderUri ?: error("请先选择同步文件夹")
        val selected = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开文件夹")
        require(selected.canRead() && selected.canWrite()) { "文件夹不可读写，请重新授权" }
        val root = when (prefs.getString(FOLDER_LAYOUT_KEY, null)) {
            NEW_FOLDER_LAYOUT -> selected.findFile(SYNC_FOLDER_NAME)?.takeIf { it.isDirectory }
                ?: error("未找到 $SYNC_FOLDER_NAME 文件夹，请检查同步目录")
            CHILD_FOLDER_LAYOUT -> {
                val name = prefs.getString(CHILD_FOLDER_NAME_KEY, null)
                    ?: error("未记录已有账本文件夹名称，请重新选择")
                selected.findFile(name)?.takeIf { it.isDirectory }
                    ?: error("未找到 $name 文件夹，请检查同步目录")
            }
            else -> selected
        }
        val folder = root.findFile("ledger-v1") ?: root.createDirectory("ledger-v1")
            ?: error("无法创建账本目录")
        require(folder.isDirectory) { "ledger-v1 不是文件夹" }

        var report = syncDirectory(folder, DEFAULT_BOOK_ID)
        val remoteBookIds = folder.listFiles().filter { it.isDirectory && it.name?.startsWith("book-") == true }
            .mapNotNull { it.name?.removePrefix("book-")?.takeIf { id ->
                runCatching { UUID.fromString(id) }.isSuccess && id != DEFAULT_BOOK_ID
            } }
        val localBookIds = dao.all().map { it.ledgerId }.filter { it != DEFAULT_BOOK_ID }
        for (bookId in (remoteBookIds + localBookIds).distinct()) {
            val bookFolder = folder.findFile("book-$bookId") ?: folder.createDirectory("book-$bookId")
                ?: error("无法创建账本目录")
            require(bookFolder.isDirectory) { "账本目录不可用" }
            val part = syncDirectory(bookFolder, bookId)
            report = SyncReport(report.imported + part.imported, report.exported + part.exported,
                report.invalid + part.invalid)
        }
        report
    } }

    private suspend fun syncDirectory(folder: DocumentFile, ledgerId: String): SyncReport {
        val files = folder.listFiles().filter { it.isFile && it.name?.matches(Regex("op-[0-9a-f-]{36}\\.json")) == true }
        val knownFiles = files.associateBy { it.name!! }.toMutableMap()
        val local = dao.all().associateBy { it.opId }
        var imported = 0
        var invalid = 0
        for (file in files) {
            try {
                val op = readOp(file)
                require(op.ledgerId == ledgerId && file.name == "op-${op.opId}.json")
                require(local[op.opId] == null || local[op.opId] == op) { "文件与本地操作 ID 冲突" }
                if (dao.insert(op) != -1L) imported++
            } catch (_: Exception) {
                invalid++
            }
        }
        var exported = 0
        for (op in dao.all().filter { it.ledgerId == ledgerId }) {
            val name = "op-${op.opId}.json"
            val existing = knownFiles[name]
            if (existing != null) {
                require(readOp(existing) == op) { "共享文件与本地操作不一致：$name" }
                continue
            }
            val temp = folder.createFile("application/octet-stream", "writing-${UUID.randomUUID()}.part")
                ?: error("无法创建临时文件")
            try {
                context.contentResolver.openOutputStream(temp.uri, "w")?.use { output ->
                    output.write(OpCodec.encode(op).toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                } ?: error("无法写入临时文件")
                require(temp.renameTo(name)) { "无法完成文件写入" }
                val written = folder.findFile(name) ?: error("写入后未找到文件")
                require(readOp(written) == op) { "写入校验失败" }
                knownFiles[name] = written
                exported++
            } catch (error: Exception) {
                temp.delete()
                throw error
            }
        }
        return SyncReport(imported, exported, invalid)
    }

    private fun readOp(file: DocumentFile): LedgerOp {
        require(file.length() <= 65_536) { "文件过大" }
        val text = context.contentResolver.openInputStream(file.uri)?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() } ?: error("无法读取 ${file.name}")
        require(text.length <= 65_536) { "文件过大" }
        return OpCodec.decode(text)
    }
}
