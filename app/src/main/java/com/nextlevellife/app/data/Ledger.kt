package com.nextlevellife.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/** Одна операция журнала баланса. [deltaMs] со знаком: начисления > 0, расход < 0. */
data class BalanceOp(
    val seq: Long,
    val id: String,
    val deviceId: String,
    val idempotencyKey: String,
    val kind: OpKind,
    val deltaMs: Long,
    val deltaXp: Long,
    val occurredAtMs: Long,
    val recordedAtMs: Long,
    val accountingDay: Long,
    val zoneId: String,
    val sourceType: String,
    val sourceId: String,
    val ruleVersion: Int,
    val reversesId: String?,
    val metadataJson: String,
)

/**
 * Журнал операций баланса (таблица balance_ops в diary.db) и итог счёта (таблица account).
 *
 * Правила:
 * - каждая операция меняет account в той же транзакции, что и вставляется; account = сумма дельт;
 * - операции не меняются и не удаляются; исключение — «открытая» строка USAGE, которая копит
 *   до [LedgerMath.USAGE_CHUNK_MS] расхода одной сессии;
 * - [balanceMs] и [xp] читаются из кеша в памяти; он перечитывается из базы после каждой
 *   внешней транзакции [Diary.write] (успешной или откатившейся).
 *
 * Блокировка — монитор [Diary]: так журнал, дневник и account никогда не пишутся вразнобой.
 */
object Ledger {
    private const val TAG = "Ledger"

    @Volatile private var cachedBalanceMs = 0L
    @Volatile private var cachedXp = 0L
    @Volatile private var cachedDeviceId: String? = null

    /** Открытая строка расхода текущей сессии службы (только в памяти: после перезапуска процесса — новая сессия). */
    private var usageCursor: UsageCursor? = null

    // ---------- Запуск и перенос ----------

    /**
     * Готовит журнал: device_id, перенос баланса и опыта из SharedPreferences одной операцией
     * OPENING (только один раз — ключ [LedgerMath.OPENING_KEY]), затем читает кеш.
     * Вызывается из [Store.init] после [Diary.init].
     */
    internal fun init(openingBalanceMs: Long, openingXp: Long) = synchronized(Diary) {
        Diary.write { db ->
            ensureDeviceId()
            if (opByKey(LedgerMath.OPENING_KEY) == null) {
                insertOp(
                    db, OpKind.OPENING, LedgerMath.OPENING_KEY,
                    deltaMs = openingBalanceMs.coerceAtLeast(0L), deltaXp = openingXp.coerceAtLeast(0L),
                    sourceType = "migration", sourceId = "shared_prefs",
                    occurredAtMs = System.currentTimeMillis(),
                    metadataJson = JSONObject().put("from", "shared_prefs").toString(),
                )
            }
        }
    }

    /** Идентификатор этой установки: создаётся один раз и хранится в meta. */
    fun deviceId(): String = cachedDeviceId ?: synchronized(Diary) { ensureDeviceId() }

    private fun ensureDeviceId(): String {
        cachedDeviceId?.let { return it }
        val id = Diary.metaGet(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { Diary.metaPut(KEY_DEVICE_ID, it) }
        cachedDeviceId = id
        return id
    }

    /** После подмены базы (восстановление копии): забыть кешированный device_id и закрыть открытую строку расхода. */
    internal fun onDatabaseReplaced() = synchronized(Diary) {
        cachedDeviceId = null
        // Номер строки в сессии сохраняется, чтобы ключи не повторились; сама строка считается закрытой.
        usageCursor = usageCursor?.copy(accumulatedMs = LedgerMath.USAGE_CHUNK_MS)
        reloadCacheQuietly()
    }

    // ---------- Чтение ----------

    fun balanceMs(): Long = cachedBalanceMs

    fun xp(): Long = cachedXp

    /** Сколько экранного времени потрачено в локальные сутки [day] (положительное число). */
    fun spentMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT COALESCE(-SUM(delta_ms), 0) FROM balance_ops WHERE kind = ? AND accounting_day = ?",
            OpKind.USAGE.name, day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    /** Сколько заработано в сутки [day]: сумма начислений за занятия ([OpKind.REWARDS]), без переноса и поправок. */
    fun earnedMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT COALESCE(SUM(delta_ms), 0) FROM balance_ops WHERE accounting_day = ? AND kind IN ($REWARD_KINDS_SQL)",
            day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    /** Расход по дням (день → мс, положительное), только дни с расходом. */
    fun spendByDay(fromDay: Long, toDay: Long): Map<Long, Long> = synchronized(Diary) {
        Diary.query(
            "SELECT accounting_day, -SUM(delta_ms) FROM balance_ops " +
                "WHERE kind = ? AND accounting_day BETWEEN ? AND ? GROUP BY accounting_day ORDER BY accounting_day",
            OpKind.USAGE.name, fromDay.toString(), toDay.toString(),
        ) { it.getLong(0) to it.getLong(1) }.toMap()
    }

    /** Расход по приложениям за дни [fromDay]..[toDay] (пакет → мс), по убыванию. */
    fun usageByPackage(fromDay: Long, toDay: Long): Map<String, Long> = synchronized(Diary) {
        Diary.query(
            "SELECT source_id, -SUM(delta_ms) AS spent FROM balance_ops " +
                "WHERE kind = ? AND accounting_day BETWEEN ? AND ? GROUP BY source_id ORDER BY spent DESC",
            OpKind.USAGE.name, fromDay.toString(), toDay.toString(),
        ) { it.getString(0) to it.getLong(1) }.toMap(LinkedHashMap())
    }

    /** Последние операции, новые сверху. */
    fun recentOps(limit: Int): List<BalanceOp> {
        if (limit <= 0) return emptyList()
        return synchronized(Diary) {
            Diary.query("SELECT $OP_COLS FROM balance_ops ORDER BY seq DESC LIMIT $limit") { it.toOp() }
        }
    }

    /** Операция по ключу идемпотентности или null. */
    fun opByKey(idempotencyKey: String): BalanceOp? = synchronized(Diary) {
        Diary.query("SELECT $OP_COLS FROM balance_ops WHERE idempotency_key = ?", idempotencyKey) { it.toOp() }
            .firstOrNull()
    }

    /** Сходится ли account с суммой журнала (для проверок и отладки). */
    fun isConsistent(): Boolean = synchronized(Diary) {
        Diary.query(
            "SELECT (SELECT balance_ms FROM account WHERE id = 1) = (SELECT COALESCE(SUM(delta_ms), 0) FROM balance_ops) " +
                "AND (SELECT xp FROM account WHERE id = 1) = (SELECT COALESCE(SUM(delta_xp), 0) FROM balance_ops)",
        ) { it.getInt(0) != 0 }.firstOrNull() ?: false
    }

    // ---------- Запись ----------

    /**
     * Начисляет что-либо один раз: вставляет операцию с ключом [idempotencyKey] и меняет account
     * одной транзакцией. Возвращает false, если операция с таким ключом уже есть (ничего не меняется).
     * Можно вызывать внутри уже открытой [Diary.write] — тогда операция войдёт в ту же транзакцию
     * и откатится вместе с ней. Ошибки базы пробрасываются.
     * [metadata] сохраняется как JSON (значения — строки, числа, булевы, null, вложенные Map/List).
     */
    fun reward(
        kind: OpKind,
        idempotencyKey: String,
        deltaMs: Long,
        deltaXp: Long,
        sourceType: String,
        sourceId: String,
        occurredAtMs: Long = System.currentTimeMillis(),
        metadata: Map<String, Any?> = emptyMap(),
    ): Boolean {
        require(kind != OpKind.USAGE) { "Расход пишется через Ledger.spend" }
        require(kind != OpKind.OPENING) { "OPENING создаётся только при переносе" }
        require(idempotencyKey.isNotBlank())
        return synchronized(Diary) {
            Diary.write { db ->
                if (opByKey(idempotencyKey) != null) return@write false
                insertOp(db, kind, idempotencyKey, deltaMs, deltaXp, sourceType, sourceId, occurredAtMs, JSONObject(metadata).toString())
                true
            }
        }
    }

    /**
     * Списывает [elapsedMs] расхода приложения [packageName] в сессии [sessionId], не больше баланса.
     * Возвращает сколько реально списано. Одна открытая строка USAGE на сессию копит до минуты
     * расхода, затем начинается следующая (`usage:<sessionId>:<номер>`); смена локальных суток
     * тоже начинает новую строку. Ошибки базы пробрасываются (открытая строка тогда закрывается).
     */
    fun spend(sessionId: String, packageName: String, elapsedMs: Long): Long {
        if (elapsedMs <= 0L) return 0L
        return synchronized(Diary) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val day = LedgerMath.accountingDay(now, zone)
            var plan: UsagePlan? = null
            try {
                val charged = Diary.write { db ->
                    val charge = minOf(elapsedMs, readAccount(db).first)
                    if (charge <= 0L) return@write 0L
                    val p = LedgerMath.planUsage(usageCursor, sessionId, packageName, day, charge)
                    if (p.addToOpen > 0L) {
                        val open = usageCursor!!
                        val key = LedgerMath.usageKey(open.sessionId, open.index)
                        val changed = db.compileStatement(
                            "UPDATE balance_ops SET delta_ms = delta_ms - ? WHERE idempotency_key = ? AND kind = ?",
                        ).use { st ->
                            st.bindLong(1, p.addToOpen)
                            st.bindString(2, key)
                            st.bindString(3, OpKind.USAGE.name)
                            st.executeUpdateDelete()
                        }
                        check(changed == 1) { "Открытая строка расхода $key не найдена" }
                        addToAccount(db, -p.addToOpen, 0L, now)
                    }
                    p.newRows.forEachIndexed { i, ms ->
                        insertOp(
                            db, OpKind.USAGE, LedgerMath.usageKey(sessionId, p.firstNewIndex + i),
                            deltaMs = -ms, deltaXp = 0L, sourceType = "app", sourceId = packageName,
                            occurredAtMs = now, metadataJson = "{}", zone = zone, day = day,
                        )
                    }
                    plan = p
                    charge
                }
                plan?.let { usageCursor = it.next }
                charged
            } catch (e: Exception) {
                usageCursor = usageCursor?.copy(accumulatedMs = LedgerMath.USAGE_CHUNK_MS)
                throw e
            }
        }
    }

    // ---------- Внутреннее ----------

    /** Перечитывает кеш баланса и опыта из account. Вызывается [Diary.write] после внешней транзакции. */
    internal fun reloadCacheQuietly() {
        try {
            val (balance, xp) = synchronized(Diary) { readAccount(Diary.db()) }
            val changed = balance != cachedBalanceMs || xp != cachedXp
            cachedBalanceMs = balance
            cachedXp = xp
            if (changed) Store.notifyChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать account", e)
        }
    }

    private fun readAccount(db: SQLiteDatabase): Pair<Long, Long> =
        db.rawQuery("SELECT balance_ms, xp FROM account WHERE id = 1", null).use { c ->
            check(c.moveToFirst()) { "Нет строки account" }
            c.getLong(0) to c.getLong(1)
        }

    private fun addToAccount(db: SQLiteDatabase, deltaMs: Long, deltaXp: Long, now: Long) {
        db.execSQL(
            "UPDATE account SET balance_ms = balance_ms + ?, xp = xp + ?, updated_at_ms = ? WHERE id = 1",
            arrayOf<Any>(deltaMs, deltaXp, now),
        )
    }

    private fun insertOp(
        db: SQLiteDatabase,
        kind: OpKind,
        key: String,
        deltaMs: Long,
        deltaXp: Long,
        sourceType: String,
        sourceId: String,
        occurredAtMs: Long,
        metadataJson: String,
        zone: ZoneId = ZoneId.systemDefault(),
        day: Long = LedgerMath.accountingDay(occurredAtMs, zone),
    ) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("device_id", ensureDeviceId())
            put("idempotency_key", key)
            put("kind", kind.name)
            put("delta_ms", deltaMs)
            put("delta_xp", deltaXp)
            put("occurred_at_ms", occurredAtMs)
            put("recorded_at_ms", now)
            put("accounting_day", day)
            put("zone_id", zone.id)
            put("source_type", sourceType)
            put("source_id", sourceId)
            put("rule_version", LedgerMath.RULE_VERSION)
            putNull("reverses_id")
            put("metadata_json", metadataJson)
        }
        db.insertOrThrow("balance_ops", null, values)
        addToAccount(db, deltaMs, deltaXp, now)
    }

    internal const val KEY_DEVICE_ID = "device_id"

    private val REWARD_KINDS_SQL = OpKind.REWARDS.joinToString(",") { "'${it.name}'" }

    private const val OP_COLS =
        "seq, id, device_id, idempotency_key, kind, delta_ms, delta_xp, occurred_at_ms, recorded_at_ms, " +
            "accounting_day, zone_id, source_type, source_id, rule_version, reverses_id, metadata_json"

    private fun Cursor.toOp() = BalanceOp(
        seq = getLong(0), id = getString(1), deviceId = getString(2), idempotencyKey = getString(3),
        kind = runCatching { OpKind.valueOf(getString(4)) }.getOrDefault(OpKind.DEV_ADJUSTMENT),
        deltaMs = getLong(5), deltaXp = getLong(6), occurredAtMs = getLong(7), recordedAtMs = getLong(8),
        accountingDay = getLong(9), zoneId = getString(10), sourceType = getString(11), sourceId = getString(12),
        ruleVersion = getInt(13), reversesId = if (isNull(14)) null else getString(14), metadataJson = getString(15),
    )
}
