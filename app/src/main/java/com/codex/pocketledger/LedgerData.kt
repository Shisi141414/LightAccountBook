package com.codex.pocketledger

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** An immutable change. A new operation, rather than an overwritten file, records every edit. */
@Entity(tableName = "operations")
data class LedgerOp(
    @PrimaryKey val opId: String,
    val objectType: String,
    val objectId: String,
    val parents: String,
    val payload: String,
    val deviceId: String,
    val createdAtMs: Long,
    @ColumnInfo(defaultValue = "'$DEFAULT_BOOK_ID'") val ledgerId: String = DEFAULT_BOOK_ID,
) {
    fun parentIds(): List<String> = parents.split(',').filter { it.isNotBlank() }
    fun isDelete(): Boolean = payload.isEmpty()
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM operations")
    fun observeAll(): Flow<List<LedgerOp>>

    @Query("SELECT * FROM operations")
    suspend fun all(): List<LedgerOp>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: LedgerOp): Long
}

@Database(entities = [LedgerOp::class], version = 2, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
}

val ledgerMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE operations ADD COLUMN ledgerId TEXT NOT NULL DEFAULT '$DEFAULT_BOOK_ID'")
    }
}

data class ObjectState(val type: String, val id: String, val heads: List<LedgerOp>) {
    val conflict: Boolean get() = heads.size > 1
    val chosen: LedgerOp? get() = heads.filterNot { it.isDelete() }
        .maxWithOrNull(compareBy<LedgerOp> { it.createdAtMs }.thenBy { it.opId })
    val visible: Boolean get() = chosen != null || conflict
}

/** Every parent referenced by a later operation stops being a current head. */
fun currentObjects(ops: List<LedgerOp>): Map<Pair<String, String>, ObjectState> =
    ops.groupBy { it.objectType to it.objectId }.mapValues { (key, group) ->
        val superseded = group.flatMap { it.parentIds() }.toSet()
        val heads = group.filterNot { it.opId in superseded }.sortedBy { it.opId }
        ObjectState(key.first, key.second, heads)
    }

object OpCodec {
    fun encode(op: LedgerOp): String = JSONObject()
        .put("schema", 1)
        .put("opId", op.opId)
        .put("objectType", op.objectType)
        .put("objectId", op.objectId)
        .put("parents", JSONArray(op.parentIds()))
        .put("payload", if (op.isDelete()) JSONObject.NULL else JSONObject(op.payload))
        .put("deviceId", op.deviceId)
        .put("createdAtMs", op.createdAtMs)
        .put("ledgerId", op.ledgerId)
        .toString()

    fun decode(text: String): LedgerOp {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1)
        val opId = json.getString("opId")
        val type = json.getString("objectType")
        val id = json.getString("objectId")
        val deviceId = json.getString("deviceId")
        val ledgerId = json.optString("ledgerId", DEFAULT_BOOK_ID)
        UUID.fromString(opId)
        UUID.fromString(id)
        UUID.fromString(deviceId)
        UUID.fromString(ledgerId)
        require(type in setOf("book", "account", "category", "budget", "entry", "rate", "schedule"))
        val parentArray = json.getJSONArray("parents")
        val parents = (0 until parentArray.length()).map { parentArray.getString(it) }
        require(parents.size <= 32 && parents.distinct().size == parents.size)
        parents.forEach { UUID.fromString(it) }
        require(opId !in parents)
        val payload = if (json.isNull("payload")) "" else json.getJSONObject("payload").also {
            validatePayload(type, it)
        }.toString()
        val createdAt = json.getLong("createdAtMs")
        require(createdAt > 0)
        return LedgerOp(opId, type, id, parents.joinToString(","), payload, deviceId,
            createdAt, ledgerId)
    }

    private fun validatePayload(type: String, payload: JSONObject) {
        when (type) {
            "book" -> require(payload.getString("name").isNotBlank())
            "account" -> {
                require(payload.getString("name").isNotBlank())
                payload.getLong("openingCents")
                payload.optBoolean("demandDeposit", false)
                val investmentType = payload.optString("investmentType").ifBlank { "none" }
                require(investmentType in setOf("none", "demand", "fixed"))
                if (investmentType == "fixed") {
                    val start = LocalDate.parse(payload.getString("fixedStartDate"))
                    require(LocalDate.parse(payload.getString("fixedMaturityDate")).isAfter(start))
                }
            }
            "rate" -> {
                UUID.fromString(payload.getString("accountId"))
                LocalDate.parse(payload.getString("date"))
                require(if (payload.has("annualRateUnits")) {
                    payload.getInt("annualRateUnits") in 0..10_000_000
                } else {
                    payload.getInt("annualRateBps") in 0..100_000
                })
                require(payload.getLong("recordedAtMs") > 0)
            }
            "schedule" -> {
                val from = payload.getString("fromId")
                val to = payload.getString("toId")
                UUID.fromString(from); UUID.fromString(to)
                require(from != to && payload.getLong("amountCents") > 0)
                LocalDate.parse(payload.getString("startDate"))
                require(payload.getString("frequency") in setOf("daily", "weekly", "monthly"))
                payload.getBoolean("active")
                require(payload.optString("note").length <= 300)
            }
            "category" -> {
                require(payload.getString("name").isNotBlank())
                require(payload.getString("kind") in setOf("income", "expense"))
                payload.optString("parentId").takeIf { it.isNotBlank() }?.let(UUID::fromString)
            }
            "budget" -> {
                YearMonth.parse(payload.getString("month"))
                payload.optString("categoryId").takeIf { it.isNotBlank() }?.let(UUID::fromString)
                require(payload.getLong("limitCents") >= 0)
            }
            "entry" -> {
                val kind = payload.getString("kind")
                require(kind in setOf("income", "expense", "transfer", "balance"))
                UUID.fromString(payload.getString("accountId"))
                val to = payload.optString("toAccountId").takeIf { it.isNotBlank() }
                val category = payload.optString("categoryId").takeIf { it.isNotBlank() }
                to?.let(UUID::fromString)
                category?.let(UUID::fromString)
                require(when (kind) {
                    "transfer" -> to != null && to != payload.getString("accountId") && category == null
                    "balance" -> to == null && category == null
                    else -> to == null && category != null
                })
                if (kind != "balance") require(payload.getLong("amountCents") > 0)
                else payload.getLong("amountCents")
                LocalDate.parse(payload.getString("date"))
                require(payload.optString("note").length <= 300)
                require(payload.optLong("recordedAtMs", 0) >= 0)
                require(payload.optString("sourceKey").length <= 100)
            }
        }
    }
}

fun LedgerOp.payloadObject(): JSONObject? = if (isDelete()) null else JSONObject(payload)
