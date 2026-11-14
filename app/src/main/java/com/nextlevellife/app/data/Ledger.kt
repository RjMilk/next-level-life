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
                    occurredAtMs = TrustedClock.nowMs(),
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
        forgetPeerCache()
        // Номер строки в сессии сохраняется, чтобы ключи не повторились; сама строка считается закрытой.
        usageCursor = usageCursor?.copy(accumulatedMs = LedgerMath.USAGE_CHUNK_MS)
        reloadCacheQuietly()
        // Пояс копии мог прийти отложенной сменой.
        TrustedClock.onDatabaseReplaced()
    }

    // ---------- Чтение ----------

    fun balanceMs(): Long = cachedBalanceMs

    fun xp(): Long = cachedXp

    /** Сколько экранного времени потрачено в учётные сутки сейчас ([TrustedClock.today]). */
    fun spentTodayMs(): Long = spentMs(TrustedClock.today())

    /**
     * Сколько экранного времени потрачено в учётные сутки [day] (положительное число): расход
     * и перенос расхода при восстановлении копии ([OpKind.SPENDING]).
     * «Сегодня» брать из [TrustedClock.today] или [TimeSnapshot.day], а не из системных часов.
     */
    fun spentMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT COALESCE(-SUM(delta_ms), 0) FROM ${bothJournals()} WHERE kind IN ($SPENDING_KINDS_SQL) AND accounting_day = ?",
            peerArg(), day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    /**
     * Сколько заработано в сутки [day] (на обоих телефонах пары): сумма начислений за занятия ([OpKind.REWARDS]),
     * без переноса, поправок и переводов между телефонами.
     */
    fun earnedMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT COALESCE(SUM(delta_ms), 0) FROM ${bothJournals()} WHERE accounting_day = ? AND kind IN ($REWARD_KINDS_SQL)",
            peerArg(), day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    /** Расход по дням на обоих телефонах пары (день → мс, положительное), только дни с расходом. */
    fun spendByDay(fromDay: Long, toDay: Long): Map<Long, Long> = synchronized(Diary) {
        Diary.query(
            "SELECT accounting_day, -SUM(delta_ms) FROM ${bothJournals()} " +
                "WHERE kind IN ($SPENDING_KINDS_SQL) AND accounting_day BETWEEN ? AND ? GROUP BY accounting_day ORDER BY accounting_day",
            peerArg(), fromDay.toString(), toDay.toString(),
        ) { it.getLong(0) to it.getLong(1) }.toMap()
    }

    /** Расход по приложениям за дни [fromDay]..[toDay] на обоих телефонах пары (пакет → мс), по убыванию. */
    fun usageByPackage(fromDay: Long, toDay: Long): Map<String, Long> = synchronized(Diary) {
        Diary.query(
            "SELECT source_id, -SUM(delta_ms) AS spent FROM ${bothJournals()} " +
                "WHERE kind IN ($SPENDING_KINDS_SQL) AND accounting_day BETWEEN ? AND ? GROUP BY source_id ORDER BY spent DESC",
            peerArg(), fromDay.toString(), toDay.toString(),
        ) { it.getString(0) to it.getLong(1) }.toMap(LinkedHashMap())
    }

    /**
     * Свой журнал и копия журнала связанного телефона одной выборкой (первый параметр запроса — [peerArg]).
     * Без пары копия пуста: условие device_id = '' не совпадает ни с одной строкой.
     */
    private fun bothJournals(): String =
        "(SELECT kind, delta_ms, accounting_day, source_id FROM balance_ops UNION ALL " +
            "SELECT kind, delta_ms, accounting_day, source_id FROM peer_ops WHERE device_id = ?)"

    private fun peerArg(): String = peerId() ?: ""

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
     * Учётные сутки — по [occurredAtMs] (по умолчанию доверенное «сейчас») в учётном поясе [zone]
     * (по умолчанию [TrustedClock.zone]; запись тренировки передаёт пояс своего снимка [TimeSnapshot]).
     */
    fun reward(
        kind: OpKind,
        idempotencyKey: String,
        deltaMs: Long,
        deltaXp: Long,
        sourceType: String,
        sourceId: String,
        occurredAtMs: Long = TrustedClock.nowMs(),
        metadata: Map<String, Any?> = emptyMap(),
        zone: ZoneId = TrustedClock.zone(),
    ): Boolean {
        require(kind != OpKind.USAGE) { "Расход пишется через Ledger.spend" }
        require(kind != OpKind.OPENING) { "OPENING создаётся только при переносе" }
        require(kind !in OpKind.TRANSFERS) { "Переносы пишутся через Ledger.transferOut и transferIn" }
        require(idempotencyKey.isNotBlank())
        return synchronized(Diary) {
            Diary.write { db ->
                if (opByKey(idempotencyKey) != null) return@write false
                insertOp(db, kind, idempotencyKey, deltaMs, deltaXp, sourceType, sourceId, occurredAtMs, JSONObject(metadata).toString(), zone = zone)
                true
            }
        }
    }

    /**
     * Списывает [elapsedMs] расхода приложения [packageName] в сессии [sessionId], не больше баланса.
     * Возвращает сколько реально списано. Одна открытая строка USAGE на сессию копит до минуты
     * расхода, затем начинается следующая (`usage:<sessionId>:<номер>`); смена учётных суток
     * тоже начинает новую строку. Просмотр, закончившийся в [time] и начавшийся до полуночи учётного
     * пояса, делится на строки по суткам ([LedgerMath.splitByDay]). Сутки и время операций — из [time]
     * (доверенный момент и учётный пояс, [TrustedClock.snapshot]). Ошибки базы пробрасываются
     * (открытая строка тогда закрывается).
     *
     * [debts] — неоплаченные прошлые просмотры ([UsageDebt]): списываются первыми, каждый по своим суткам
     * (по своему интервалу доверенного времени) и своему приложению, строками текущей сессии [sessionId]
     * (ключи сессии не повторяются). Всё вместе — не больше баланса ([LedgerMath.planCharges]).
     */
    fun spend(
        sessionId: String,
        packageName: String,
        elapsedMs: Long,
        time: TimeSnapshot = TrustedClock.snapshot(),
        debts: List<UsageDebt> = emptyList(),
    ): Long {
        if (elapsedMs <= 0L && debts.none { it.durationMs > 0L }) return 0L
        return synchronized(Diary) {
            val now = time.utcMs
            val zone = time.zone
            var cursor = usageCursor
            try {
                val charged = Diary.write { db ->
                    val parts = LedgerMath.planCharges(debts, packageName, now, elapsedMs, readAccount(db).first, zone)
                    if (parts.isEmpty()) return@write 0L
                    for (part in parts) {
                        val share = part.share
                        val p = LedgerMath.planUsage(cursor, sessionId, part.packageName, share.day, share.ms)
                        if (p.addToOpen > 0L) {
                            val open = cursor!!
                            val key = LedgerMath.usageKey(open.sessionId, open.index)
                            // Только своя строка; новый номер изменения — второй телефон заберёт её свежее значение.
                            val changed = db.compileStatement(
                                "UPDATE balance_ops SET delta_ms = delta_ms - ?, rev = ? " +
                                    "WHERE idempotency_key = ? AND kind = ? AND device_id = ?",
                            ).use { st ->
                                st.bindLong(1, p.addToOpen)
                                st.bindLong(2, nextRev(db))
                                st.bindString(3, key)
                                st.bindString(4, OpKind.USAGE.name)
                                st.bindString(5, ensureDeviceId())
                                st.executeUpdateDelete()
                            }
                            check(changed == 1) { "Открытая строка расхода $key не найдена" }
                            addToAccount(db, -p.addToOpen, 0L, now)
                        }
                        p.newRows.forEachIndexed { i, ms ->
                            insertOp(
                                db, OpKind.USAGE, LedgerMath.usageKey(sessionId, p.firstNewIndex + i),
                                deltaMs = -ms, deltaXp = 0L, sourceType = "app", sourceId = part.packageName,
                                occurredAtMs = share.occurredAtMs,
                                metadataJson = part.debtSessionId?.let { JSONObject().put("debt_session", it).toString() } ?: "{}",
                                zone = zone, day = share.day, recordedAtMs = now,
                            )
                        }
                        cursor = p.next
                    }
                    parts.sumOf { it.share.ms }
                }
                usageCursor = cursor
                charged
            } catch (e: Exception) {
                usageCursor = usageCursor?.copy(accumulatedMs = LedgerMath.USAGE_CHUNK_MS)
                throw e
            }
        }
    }

    // ---------- Восстановление из копии ----------

    /** Строки расхода текущего журнала (USAGE и RESTORE_CARRY) — для переноса при восстановлении. */
    internal fun spentRowsForCarry(): List<SpentRow> = synchronized(Diary) {
        Diary.query(
            "SELECT idempotency_key, accounting_day, source_id, -delta_ms FROM balance_ops WHERE kind IN ($SPENDING_KINDS_SQL)",
        ) { SpentRow(it.getString(0), it.getLong(1), it.getString(2), it.getLong(3)) }
    }

    /**
     * Дописывает в базу копии [db] (ещё не установленную) перенос расхода: всё, что потрачено в текущем
     * журнале [current] и чего нет в журнале копии ([LedgerMath.restoreCarry]), — операциями
     * [OpKind.RESTORE_CARRY] по своим суткам и приложениям, целиком (баланс может уйти в минус). Переносы со вторым
     * телефоном ([transfers]), которых нет в копии, возвращаются той же операцией. Account меняется
     * той же транзакцией. Возвращает сколько перенесено расхода и исходящих переносов (мс).
     */
    internal fun appendRestoreCarry(
        db: SQLiteDatabase, current: List<SpentRow>, deviceId: String, zone: ZoneId, nowMs: Long,
        transfers: List<BalanceOp> = emptyList(),
    ): Long {
        val backupSpent = HashMap<String, Long>()
        db.rawQuery("SELECT idempotency_key, -delta_ms FROM balance_ops WHERE kind IN ($SPENDING_KINDS_SQL)", null).use { c ->
            while (c.moveToNext()) backupSpent[c.getString(0)] = c.getLong(1)
        }
        // Переносы, которых нет в копии, возвращаются в журнал как есть — та же операция (id и ключ), без ограничения
        // балансом. Исходящие: второй телефон их уже получил (или получит) — иначе восстановление вернуло бы отданные
        // минуты. Входящие: без своего ключа transfer_in:<id> телефон принял бы тот же исходящий второго телефона ещё
        // раз и потратил бы те же минуты дважды.
        val backupKeys = HashSet<String>()
        db.rawQuery(
            "SELECT idempotency_key FROM balance_ops WHERE kind IN (?, ?)",
            arrayOf(OpKind.TRANSFER_OUT.name, OpKind.TRANSFER_IN.name),
        ).use { c ->
            while (c.moveToNext()) backupKeys += c.getString(0)
        }
        val missingTransfers = transfers.filter { it.kind in OpKind.TRANSFERS && it.idempotencyKey !in backupKeys }
        var carriedTransfers = 0L
        if (missingTransfers.isNotEmpty()) {
            db.beginTransaction()
            try {
                for (t in missingTransfers) {
                    val values = ContentValues().apply {
                        put("id", t.id)
                        put("device_id", deviceId)
                        put("idempotency_key", t.idempotencyKey)
                        put("kind", t.kind.name)
                        put("delta_ms", t.deltaMs)
                        put("delta_xp", 0L)
                        put("occurred_at_ms", t.occurredAtMs)
                        put("recorded_at_ms", t.recordedAtMs)
                        put("accounting_day", t.accountingDay)
                        put("zone_id", t.zoneId)
                        put("source_type", t.sourceType)
                        put("source_id", t.sourceId)
                        put("rule_version", t.ruleVersion)
                        putNull("reverses_id")
                        put("metadata_json", t.metadataJson)
                        put("rev", nextRev(db))
                    }
                    db.insertWithOnConflict("balance_ops", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                        .takeIf { it != -1L } ?: continue
                    addToAccount(db, t.deltaMs, 0L, nowMs)
                    if (t.deltaMs < 0L) carriedTransfers += -t.deltaMs
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        val parts = LedgerMath.restoreCarry(current, backupSpent)
        if (parts.isEmpty()) return carriedTransfers
        val restoreId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            for (p in parts) {
                // Время операции — внутри её суток: для прошлых суток последняя миллисекунда.
                val occurred = minOf(nowMs, TrustedClockMath.dayStartMs(p.day + 1, zone) - 1)
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("device_id", deviceId)
                    put("idempotency_key", LedgerMath.restoreCarryKey(restoreId, p.day, p.packageName))
                    put("kind", OpKind.RESTORE_CARRY.name)
                    put("delta_ms", -p.ms)
                    put("delta_xp", 0L)
                    put("occurred_at_ms", occurred)
                    put("recorded_at_ms", nowMs)
                    put("accounting_day", p.day)
                    put("zone_id", zone.id)
                    put("source_type", "restore_carry")
                    put("source_id", p.packageName)
                    put("rule_version", LedgerMath.RULE_VERSION)
                    putNull("reverses_id")
                    put("metadata_json", JSONObject().put("restore_id", restoreId).toString())
                    put("rev", nextRev(db))
                }
                db.insertOrThrow("balance_ops", null, values)
                addToAccount(db, -p.ms, 0L, nowMs)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return carriedTransfers + parts.sumOf { it.ms }
    }

    /** Переносы текущего журнала (исходящие и входящие) — для переноса при восстановлении копии. */
    internal fun transfersForCarry(): List<BalanceOp> = synchronized(Diary) {
        Diary.query(
            "SELECT $OP_COLS FROM balance_ops WHERE kind IN (?, ?)", OpKind.TRANSFER_OUT.name, OpKind.TRANSFER_IN.name,
        ) { it.toOp() }
    }

    /**
     * Есть ли ПОЛНАЯ копия журнала второго телефона [peer]: после сопряжения хотя бы один обмен дошёл до конца
     * ([markPeerCopyComplete]). Принятые первые страницы при оборванном обмене полной копией не считаются: иначе расход
     * второго телефона, оставшийся на непринятых страницах, выглядел бы нулевым.
     */
    private fun hasPeerCopy(peer: String): Boolean = Diary.metaGet(KEY_PEER_COMPLETE) == peer

    /** Обмен с [peer] завершён целиком: копия его журнала полная (см. [hasPeerCopy]). */
    internal fun markPeerCopyComplete(peer: String) = synchronized(Diary) {
        if (Diary.metaGet(KEY_PEER_COMPLETE) != peer) Diary.metaPut(KEY_PEER_COMPLETE, peer)
    }

    /** Есть ли операция с ключом [key] в копии журнала второго телефона (идемпотентность общих начислений). */
    fun peerHasKey(key: String): Boolean = synchronized(Diary) {
        Diary.query("SELECT 1 FROM peer_ops WHERE idempotency_key = ? LIMIT 1", key) { true }.isNotEmpty()
    }

    /** Сколько начислено за шаги и бег за учётные сутки [day] на обоих телефонах (свой журнал и копия второго). */
    fun stepsPaidBothMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT (SELECT COALESCE(SUM(delta_ms), 0) FROM balance_ops WHERE kind = ? AND source_id = ?) + " +
                "(SELECT COALESCE(SUM(delta_ms), 0) FROM peer_ops WHERE kind = ? AND source_id = ?)",
            OpKind.STEPS_REWARD.name, day.toString(), OpKind.STEPS_REWARD.name, day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    // ---------- Синхронизация ----------

    /** Поколение своего журнала операций (meta `ops_epoch`); меняется при восстановлении копии. */
    fun opsEpoch(): String = synchronized(Diary) {
        Diary.metaGet(Diary.KEY_OPS_EPOCH) ?: UUID.randomUUID().toString().also { Diary.metaPut(Diary.KEY_OPS_EPOCH, it) }
    }

    /** Свои операции с номером изменения больше [afterRev], по возрастанию номера, не больше [limit]. */
    internal fun opsAfter(afterRev: Long, limit: Int): List<Pair<BalanceOp, Long>> = synchronized(Diary) {
        Diary.query(
            "SELECT $OP_COLS, rev FROM balance_ops WHERE rev > ? ORDER BY rev LIMIT $limit",
            afterRev.toString(),
        ) { it.toOp() to it.getLong(OP_COL_COUNT) }
    }

    /**
     * Перенос [ms] минут на второй телефон [peerId]: операция TRANSFER_OUT, не больше баланса за вычетом [reserveMs]
     * (неоплаченный расход). Внутри уже открытой [Diary.write] — в той же транзакции. Возвращает сколько перенесено.
     */
    internal fun transferOut(peerId: String, ms: Long, reserveMs: Long, time: TimeSnapshot = TrustedClock.snapshot()): Long =
        synchronized(Diary) {
            Diary.write { db ->
                val amount = minOf(ms, readAccount(db).first - reserveMs.coerceAtLeast(0L))
                if (amount <= 0L) return@write 0L
                val transferId = UUID.randomUUID().toString()
                insertOp(
                    db, OpKind.TRANSFER_OUT, LedgerMath.transferOutKey(transferId),
                    deltaMs = -amount, deltaXp = 0L, sourceType = SOURCE_TRANSFER, sourceId = peerId,
                    occurredAtMs = time.utcMs, metadataJson = JSONObject().put("to", peerId).toString(),
                    zone = time.zone, day = time.day, recordedAtMs = time.utcMs,
                )
                amount
            }
        }

    /**
     * Входящий перенос по исходящей операции [outOpId] второго телефона [fromPeer]: TRANSFER_IN на [ms], один раз
     * (ключ [LedgerMath.transferInKey]). Внутри открытой [Diary.write] — в той же транзакции. false — уже был.
     */
    internal fun transferIn(outOpId: String, fromPeer: String, ms: Long, time: TimeSnapshot = TrustedClock.snapshot()): Boolean =
        synchronized(Diary) {
            require(ms > 0L)
            Diary.write { db ->
                val key = LedgerMath.transferInKey(outOpId)
                if (opByKey(key) != null) return@write false
                insertOp(
                    db, OpKind.TRANSFER_IN, key, deltaMs = ms, deltaXp = 0L, sourceType = SOURCE_TRANSFER, sourceId = outOpId,
                    occurredAtMs = time.utcMs, metadataJson = JSONObject().put("from", fromPeer).toString(),
                    zone = time.zone, day = time.day, recordedAtMs = time.utcMs,
                )
                true
            }
        }

    /** Ключи своих входящих переносов (для проверки «уже получено»). */
    internal fun transferInKeys(): Set<String> = synchronized(Diary) {
        Diary.query("SELECT idempotency_key FROM balance_ops WHERE kind = ?", OpKind.TRANSFER_IN.name) { it.getString(0) }.toSet()
    }

    /**
     * Связанный телефон для расчёта общих сумм (meta `sync_peer`, кеш в памяти); null — пары нет.
     * Пишет [setPeer] (обмен при сопряжении и разрыве).
     */
    fun peerId(): String? = cachedPeer.let { if (it == NO_PEER_LOADED) loadPeer() else it }

    private fun loadPeer(): String? = synchronized(Diary) {
        val v = runCatching { Diary.metaGet(KEY_SYNC_PEER) }.getOrNull()
        cachedPeer = v
        v
    }

    internal fun setPeer(peerId: String?) = synchronized(Diary) {
        val old = runCatching { Diary.metaGet(KEY_SYNC_PEER) }.getOrNull()
        // Источник активности выбирается для конкретной пары: разрыв или новая пара — выбрать заново.
        if (old != peerId) Diary.db().delete("shared_settings", "key = ?", arrayOf(SharedSettings.ACTIVITY_SOURCE))
        if (peerId == null) {
            Diary.metaDelete(KEY_SYNC_PEER)
            Diary.metaDelete(KEY_PAIRED_DAY)
            Diary.metaDelete(KEY_PEER_COMPLETE)
        } else {
            Diary.metaPut(KEY_SYNC_PEER, peerId)
            if (old != peerId) {
                // Новая пара: сутки сопряжения (особое правило доли) и копия журнала ещё не полная.
                Diary.metaPut(KEY_PAIRED_DAY, TrustedClock.today().toString())
                Diary.metaDelete(KEY_PEER_COMPLETE)
            }
        }
        cachedPeer = peerId
    }

    /** Учётные сутки сопряжения с нынешним вторым телефоном; null — пары нет или день не записан. */
    private fun pairedDay(): Long? = Diary.metaGet(KEY_PAIRED_DAY)?.toLongOrNull()

    /** Кошелёк второго телефона по копии его журнала (сумма peer_ops); 0 — пары или копии нет. */
    fun peerBalanceMs(): Long {
        val peer = peerId() ?: return 0L
        return synchronized(Diary) {
            Diary.query("SELECT COALESCE(SUM(delta_ms), 0) FROM peer_ops WHERE device_id = ?", peer) { it.getLong(0) }
                .firstOrNull() ?: 0L
        }
    }

    /** Свой расход в учётные сутки [day] (только этот телефон; [spentMs] — общий для пары). */
    fun ownSpentMs(day: Long): Long = synchronized(Diary) {
        Diary.query(
            "SELECT COALESCE(-SUM(delta_ms), 0) FROM balance_ops WHERE kind IN ($SPENDING_KINDS_SQL) AND accounting_day = ?",
            day.toString(),
        ) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    /** Расход второго телефона по копии в сутки [day]; 0 — пары нет. */
    fun peerSpentMs(day: Long): Long {
        val peer = peerId() ?: return 0L
        return synchronized(Diary) {
            Diary.query(
                "SELECT COALESCE(-SUM(delta_ms), 0) FROM peer_ops WHERE device_id = ? AND kind IN ($SPENDING_KINDS_SQL) AND accounting_day = ?",
                peer, day.toString(),
            ) { it.getLong(0) }.firstOrNull() ?: 0L
        }
    }

    /** Переносы доли лимита за сутки [day]: (получено этим телефоном, отдано этим телефоном). */
    fun limitMoves(day: Long): Pair<Long, Long> {
        val me = deviceId()
        val peer = peerId()
        return synchronized(Diary) {
            val outgoing = Diary.query(
                "SELECT COALESCE(SUM(ms), 0) FROM limit_moves WHERE author = ? AND day = ?", me, day.toString(),
            ) { it.getLong(0) }.firstOrNull() ?: 0L
            val incoming = if (peer == null) 0L else Diary.query(
                "SELECT COALESCE(SUM(ms), 0) FROM limit_moves WHERE author = ? AND to_device = ? AND day = ?",
                peer, me, day.toString(),
            ) { it.getLong(0) }.firstOrNull() ?: 0L
            incoming to outgoing
        }
    }

    /**
     * Оставшаяся доля лимита второго телефона в сутки [day] по копии его журнала и переносам; null — пары или лимита нет.
     * Только для подсказок («на втором телефоне есть ещё…»): блокировка считает свою долю ([shareLeftMs]).
     */
    fun peerShareLeftMs(day: Long, limitMin: Int = Settings.dailyLimitMin): Long? {
        val peer = peerId() ?: return null
        val (mineIn, mineOut) = limitMoves(day)
        return SharedWalletMath.shareLeftMs(
            limitMs = limitMin * 60_000L, me = peer, peer = deviceId(),
            incomingMs = mineOut, outgoingMs = mineIn, ownSpentMs = peerSpentMs(day), peerSpentMs = ownSpentMs(day),
        )
    }

    /**
     * Сколько ещё этот телефон может потратить в сутки [day] по своей доле дневного лимита ([SharedWalletMath.shareLeftMs]);
     * null — лимита нет. Несвязанный телефон — лимит целиком.
     */
    fun shareLeftMs(day: Long, limitMin: Int = Settings.dailyLimitMin): Long? {
        val peer = peerId()
        val (incoming, outgoing) = if (peer == null) 0L to 0L else limitMoves(day)
        // Расход второго телефона за сутки по копии. Полной копии ещё нет (после сопряжения ни один обмен не дошёл до
        // конца): в сутки сопряжения его расход неизвестен — null (доля не больше нуля, он мог тратить до пары без
        // ограничения половиной); в последующие сутки оба телефона уже ограничены половиной — 0 (обычное правило).
        val peerSpent = if (peer == null) 0L else synchronized(Diary) {
            when {
                hasPeerCopy(peer) -> peerSpentMs(day)
                pairedDay() == day -> null
                else -> 0L
            }
        }
        return SharedWalletMath.shareLeftMs(
            limitMs = limitMin * 60_000L, me = deviceId(), peer = peer,
            incomingMs = incoming, outgoingMs = outgoing, ownSpentMs = ownSpentMs(day), peerSpentMs = peerSpent,
        )
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
        zone: ZoneId = TrustedClock.zone(),
        day: Long = LedgerMath.accountingDay(occurredAtMs, zone),
        recordedAtMs: Long = TrustedClock.nowMs(),
        id: String = UUID.randomUUID().toString(),
    ) {
        val now = recordedAtMs
        val values = ContentValues().apply {
            put("id", id)
            put("rev", nextRev(db))
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

    /**
     * Следующий номер изменения своего журнала (meta `ops_rev` базы [db], не меньше наибольшего rev в журнале).
     * Только внутри транзакции записи — вместе с самим изменением: номера видны второму телефону по порядку.
     */
    internal fun nextRev(db: SQLiteDatabase): Long {
        val meta = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(Diary.KEY_OPS_REV)).use { c ->
            if (c.moveToFirst()) c.getString(0).toLongOrNull() else null
        } ?: 0L
        val max = db.rawQuery("SELECT COALESCE(MAX(rev), 0) FROM balance_ops", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        val next = maxOf(meta, max) + 1
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf<Any>(Diary.KEY_OPS_REV, next.toString()))
        return next
    }

    internal const val KEY_DEVICE_ID = "device_id"

    /** meta: device_id связанного телефона (нет ключа — пары нет). Локальное значение: копия его не переносит. */
    internal const val KEY_SYNC_PEER = "sync_peer"

    /** meta: учётные сутки сопряжения с нынешним вторым телефоном. */
    internal const val KEY_PAIRED_DAY = "sync_paired_day"

    /** meta: device_id второго телефона, с которым хотя бы один обмен дошёл до конца (копия журнала полная). */
    internal const val KEY_PEER_COMPLETE = "sync_peer_complete"

    /** source_type переносов между телефонами. */
    internal const val SOURCE_TRANSFER = "transfer"

    /** Маркер «значение ещё не читали» для [cachedPeer]. */
    private const val NO_PEER_LOADED = "\u0000"
    @Volatile private var cachedPeer: String? = NO_PEER_LOADED

    /** После подмены базы связанный телефон перечитывается из meta. */
    internal fun forgetPeerCache() {
        cachedPeer = NO_PEER_LOADED
    }

    private val REWARD_KINDS_SQL = OpKind.REWARDS.joinToString(",") { "'${it.name}'" }
    private val SPENDING_KINDS_SQL = OpKind.SPENDING.joinToString(",") { "'${it.name}'" }

    private const val OP_COLS =
        "seq, id, device_id, idempotency_key, kind, delta_ms, delta_xp, occurred_at_ms, recorded_at_ms, " +
            "accounting_day, zone_id, source_type, source_id, rule_version, reverses_id, metadata_json"
    private const val OP_COL_COUNT = 16

    private fun Cursor.toOp() = BalanceOp(
        seq = getLong(0), id = getString(1), deviceId = getString(2), idempotencyKey = getString(3),
        kind = runCatching { OpKind.valueOf(getString(4)) }.getOrDefault(OpKind.DEV_ADJUSTMENT),
        deltaMs = getLong(5), deltaXp = getLong(6), occurredAtMs = getLong(7), recordedAtMs = getLong(8),
        accountingDay = getLong(9), zoneId = getString(10), sourceType = getString(11), sourceId = getString(12),
        ruleVersion = getInt(13), reversesId = if (isNull(14)) null else getString(14), metadataJson = getString(15),
    )
}
