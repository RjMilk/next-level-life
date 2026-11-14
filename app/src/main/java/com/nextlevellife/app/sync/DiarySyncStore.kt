package com.nextlevellife.app.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.nextlevellife.app.block.SpendBridge
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.SharedSettings
import com.nextlevellife.app.data.SharedWalletMath
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAvailability
import com.nextlevellife.app.stats.StatsRepository

/**
 * Хранилище обмена на дневнике (diary.db). Пакеты применяются транзакциями [Diary.write] вместе с курсорами;
 * свои операции пишет только [Ledger], копии второго телефона лежат в peer_ops и в свой кошелёк не входят.
 */
class DiarySyncStore : SyncStore {
    override val deviceId: String get() = Ledger.deviceId()

    /** Неоплаченный расход по последнему погашению: его нельзя отдать. */
    @Volatile private var reserveMs = 0L

    /** Служба не ответила при погашении: этот обмен ничего не отдаёт. */
    @Volatile private var settleFailed = false

    override fun localState(peerId: String): LocalState {
        val settled = SpendBridge.settle()
        settleFailed = settled == null
        reserveMs = settled?.unpaidMs ?: 0L
        val time = TrustedClock.snapshot()
        val wallet = if (settled == null) 0L else Ledger.balanceMs() - settled.unpaidMs
        return LocalState(
            opsEpoch = Ledger.opsEpoch(),
            factsEpoch = Diary.factsEpoch(),
            day = time.day,
            zoneId = time.zone.id,
            spending = settled?.spending ?: true,
            walletMs = wallet,
            shareLeftMs = Ledger.shareLeftMs(time.day),
            healthConnected = ActivitySync.state.value.access?.availability == HealthAvailability.CONNECTED,
        )
    }

    override fun opsCursor(peerId: String): LogCursor? = synchronized(Diary) { Diary.query(
        "SELECT log, author, epoch, pos FROM sync_cursors WHERE log = ? AND author = ? ORDER BY pos DESC LIMIT 1",
        LogCursor.OPS, peerId,
    ) { LogCursor(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)) }.firstOrNull() }

    override fun factCursors(): List<LogCursor> = synchronized(Diary) {
        val data = Diary.query(
            "SELECT author, epoch, MAX(aseq) FROM (" +
                "SELECT author, epoch, aseq FROM sets UNION ALL SELECT author, epoch, aseq FROM complex_runs " +
                "UNION ALL SELECT author, epoch, aseq FROM limit_moves) " +
                "WHERE author IS NOT NULL AND epoch IS NOT NULL AND aseq IS NOT NULL GROUP BY author, epoch",
        ) { LogCursor(LogCursor.FACTS, it.getString(0), it.getString(1), it.getLong(2)) }
        val stored = Diary.query("SELECT log, author, epoch, pos FROM sync_cursors WHERE log = ?", LogCursor.FACTS) {
            LogCursor(it.getString(0), it.getString(1), it.getString(2), it.getLong(3))
        }
        SyncRules.mergeCursors(data, stored)
    }

    override fun opsAfter(afterRev: Long, maxRev: Long, limit: Int): List<OpRecord> =
        Ledger.opsAfter(afterRev, limit).filter { it.second <= maxRev }.map { (op, rev) ->
            OpRecord(
                id = op.id, idempotencyKey = op.idempotencyKey, kind = op.kind.name, deltaMs = op.deltaMs, deltaXp = op.deltaXp,
                occurredAtMs = op.occurredAtMs, recordedAtMs = op.recordedAtMs, accountingDay = op.accountingDay,
                zoneId = op.zoneId, sourceType = op.sourceType, sourceId = op.sourceId, ruleVersion = op.ruleVersion,
                reversesId = op.reversesId, metadataJson = op.metadataJson, rev = rev,
            )
        }

    override fun maxOpsRev(): Long = synchronized(Diary) {
        Diary.query("SELECT COALESCE(MAX(rev), 0) FROM balance_ops") { it.getLong(0) }.firstOrNull() ?: 0L
    }

    override fun factsAfter(author: String, epoch: String, afterAseq: Long, limit: Int): List<FactRecord> = synchronized(Diary) {
        val args = arrayOf(author, epoch, afterAseq.toString())
        val runs = Diary.query(
            "SELECT uid, author, epoch, aseq, time_ms, day, complex_id, weight_kg, duration_ms, rounds, extra_reps, " +
                "total_reps, earned_ms, xp, completed FROM complex_runs WHERE author = ? AND epoch = ? AND aseq > ? ORDER BY aseq LIMIT $limit",
            *args,
        ) {
            RunFact(
                it.getString(0), it.getString(1), it.getString(2), it.getLong(3), timeMs = it.getLong(4), day = it.getLong(5),
                complexId = it.getString(6), weightKg = it.intOrNull(7), durationMs = it.getLong(8), rounds = it.intOrNull(9),
                extraReps = it.intOrNull(10), totalReps = it.getInt(11), earnedMs = it.getLong(12), xp = it.getLong(13),
                completed = it.getInt(14) != 0,
            )
        }
        val sets = Diary.query(
            "SELECT uid, author, epoch, aseq, time_ms, day, exercise_id, exercise_name, weight_kg, reps, duration_ms, " +
                "earned_ms, xp, run_uid, measurement_type, held_ms FROM sets WHERE author = ? AND epoch = ? AND aseq > ? ORDER BY aseq LIMIT $limit",
            *args,
        ) {
            SetFact(
                it.getString(0), it.getString(1), it.getString(2), it.getLong(3), timeMs = it.getLong(4), day = it.getLong(5),
                exerciseId = it.getString(6), exerciseName = it.getString(7), weightKg = it.intOrNull(8), reps = it.getInt(9),
                durationMs = it.getLong(10), earnedMs = it.getLong(11), xp = it.getLong(12),
                runUid = if (it.isNull(13)) null else it.getString(13), measurement = it.getString(14), heldMs = it.getLong(15),
            )
        }
        val moves = Diary.query(
            "SELECT uid, author, epoch, aseq, to_device, day, ms, created_utc_ms FROM limit_moves " +
                "WHERE author = ? AND epoch = ? AND aseq > ? ORDER BY aseq LIMIT $limit",
            *args,
        ) { LimitMoveFact(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4), it.getLong(5), it.getLong(6), it.getLong(7)) }
        (runs + sets + moves).sortedBy { it.aseq }.take(limit)
    }

    override fun applyOps(peerId: String, epoch: String, reset: Boolean, rows: List<OpRecord>): Long {
        var received = 0L
        Diary.write { db ->
            val cursor = opsCursor(peerId)
            if (reset) {
                db.delete("peer_ops", "device_id = ?", arrayOf(peerId))
                db.delete("sync_cursors", "log = ? AND author = ?", arrayOf(LogCursor.OPS, peerId))
            } else if (cursor == null || cursor.epoch != epoch) {
                throw SyncProtocolException("Пакет операций другого поколения")
            }
            for (r in rows) {
                db.insertWithOnConflict("peer_ops", null, ContentValues().apply {
                    put("device_id", peerId)
                    put("id", r.id)
                    put("idempotency_key", r.idempotencyKey)
                    put("kind", r.kind)
                    put("delta_ms", r.deltaMs)
                    put("delta_xp", r.deltaXp)
                    put("occurred_at_ms", r.occurredAtMs)
                    put("recorded_at_ms", r.recordedAtMs)
                    put("accounting_day", r.accountingDay)
                    put("zone_id", r.zoneId)
                    put("source_type", r.sourceType)
                    put("source_id", r.sourceId)
                    put("rule_version", r.ruleVersion)
                    if (r.reversesId != null) put("reverses_id", r.reversesId) else putNull("reverses_id")
                    put("metadata_json", r.metadataJson)
                    put("epoch", epoch)
                    put("rev", r.rev)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val pos = maxOf(if (reset) 0L else cursor!!.pos, rows.maxOfOrNull { it.rev } ?: 0L)
            putCursor(db, LogCursor(LogCursor.OPS, peerId, epoch, pos))
            // Входящие переносы — в той же транзакции: по каждой исходящей второго телефона сюда, ровно один раз.
            val me = deviceId
            val outs = Diary.query(
                "SELECT id, idempotency_key, kind, delta_ms, source_id, rev FROM peer_ops WHERE device_id = ? AND kind = ? AND source_id = ?",
                peerId, OpKind.TRANSFER_OUT.name, me,
            ) {
                OpRecord(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), 0, 0, 0, 0, "", "", it.getString(4), 0, null, "", it.getLong(5))
            }
            val time = TrustedClock.snapshot()
            for (out in SyncRules.missingTransferIns(outs, me, Ledger.transferInKeys())) {
                if (Ledger.transferIn(out.id, peerId, -out.deltaMs, time)) received += -out.deltaMs
            }
        }
        return received
    }

    override fun applyFacts(peerId: String, rows: List<FactRecord>): Set<Long> {
        val days = HashSet<Long>()
        Diary.write { db ->
            val me = deviceId
            val myEpoch = Diary.factsEpoch()
            val maxByGroup = HashMap<Pair<String, String>, Long>()
            for (f in rows) {
                maxByGroup[f.author to f.epoch] = maxOf(maxByGroup[f.author to f.epoch] ?: 0L, f.aseq)
                if (!SyncRules.acceptFact(f, me, myEpoch)) {
                    Log.w(TAG, "Отклонён факт ${f.uid}: «свой» текущего поколения с другого телефона")
                    continue
                }
                if (insertFact(db, f)) days += f.day
            }
            for ((k, pos) in maxByGroup) {
                val old = Diary.query(
                    "SELECT pos FROM sync_cursors WHERE log = ? AND author = ? AND epoch = ?", LogCursor.FACTS, k.first, k.second,
                ) { it.getLong(0) }.firstOrNull() ?: 0L
                if (pos > old) putCursor(db, LogCursor(LogCursor.FACTS, k.first, k.second, pos))
            }
        }
        return days
    }

    /** Вставляет копию факта (свои локальные id); дубль по uid пропускается. true — вставлен. */
    private fun insertFact(db: SQLiteDatabase, f: FactRecord): Boolean {
        fun ContentValues.head() {
            put("uid", f.uid)
            put("author", f.author)
            put("epoch", f.epoch)
            put("aseq", f.aseq)
        }
        return when (f) {
            is RunFact -> {
                if (exists("complex_runs", f.uid)) return false
                val id = db.insertOrThrow("complex_runs", null, ContentValues().apply {
                    head()
                    put("time_ms", f.timeMs); put("day", f.day); put("complex_id", f.complexId); put("weight_kg", f.weightKg)
                    put("duration_ms", f.durationMs); put("rounds", f.rounds); put("extra_reps", f.extraReps)
                    put("total_reps", f.totalReps); put("earned_ms", f.earnedMs); put("xp", f.xp)
                    put("completed", if (f.completed) 1 else 0)
                })
                // Подходы этого прохождения могли прийти раньше — связываем.
                db.execSQL("UPDATE sets SET run_id = ? WHERE run_uid = ? AND run_id IS NULL", arrayOf<Any>(id, f.uid))
                true
            }
            is SetFact -> {
                if (exists("sets", f.uid)) return false
                val runId = f.runUid?.let { uid ->
                    Diary.query("SELECT id FROM complex_runs WHERE uid = ?", uid) { it.getLong(0) }.firstOrNull()
                }
                db.insertOrThrow("sets", null, ContentValues().apply {
                    head()
                    put("time_ms", f.timeMs); put("day", f.day); put("exercise_id", f.exerciseId); put("exercise_name", f.exerciseName)
                    put("weight_kg", f.weightKg); put("reps", f.reps); put("duration_ms", f.durationMs); put("earned_ms", f.earnedMs)
                    put("xp", f.xp); put("run_id", runId); put("run_uid", f.runUid); put("measurement_type", f.measurement)
                    put("held_ms", f.heldMs)
                })
                true
            }
            is LimitMoveFact -> {
                if (exists("limit_moves", f.uid)) return false
                db.insertOrThrow("limit_moves", null, ContentValues().apply {
                    head()
                    put("to_device", f.toDevice); put("day", f.day); put("ms", f.ms); put("created_utc_ms", f.createdUtcMs)
                })
                true
            }
        }
    }

    private fun exists(table: String, uid: String): Boolean =
        Diary.query("SELECT 1 FROM $table WHERE uid = ? LIMIT 1", uid) { true }.isNotEmpty()

    private fun putCursor(db: SQLiteDatabase, c: LogCursor) {
        db.insertWithOnConflict("sync_cursors", null, ContentValues().apply {
            put("log", c.log)
            put("author", c.author)
            put("epoch", c.epoch)
            put("pos", c.pos)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun isActivitySource(peerId: String): Boolean = SharedSettings.activitySource() == deviceId

    override fun activityExport(): ActivityExport? {
        val today = TrustedClock.today()
        val from = today - ACTIVITY_DAYS + 1
        val days = Diary.activityDays(from, today)
        if (days.isEmpty()) return null
        return ActivityExport(from, today, days, Diary.activityRuns(from, today))
    }

    override fun applyActivity(peerId: String, export: ActivityExport): Set<Long> {
        val source = SharedSettings.activitySource()
        if (source != peerId || source == deviceId) return emptySet()
        // Не больше окна выгрузки: копия не может стереть давнюю историю.
        if (export.toDay < export.fromDay || export.toDay - export.fromDay >= ACTIVITY_DAYS * 2) return emptySet()
        val inRange = export.fromDay..export.toDay
        Diary.write { db ->
            val range = arrayOf(export.fromDay.toString(), export.toDay.toString())
            db.delete("activity_days", "day BETWEEN ? AND ?", range)
            for (d in export.days) if (d.day in inRange) Diary.upsertActivityDay(db, d)
            Diary.replaceActivityRuns(db, export.fromDay, export.toDay, export.runs.filter { it.day in inRange })
        }
        return inRange.toSet()
    }

    override fun settings(): List<SharedWalletMath.Setting> = SharedSettings.all()

    override fun applySettings(remote: List<SharedWalletMath.Setting>): Boolean = SharedSettings.merge(remote)

    override fun ensureActivitySource(peerId: String, myHealth: Boolean, peerHealth: Boolean) =
        SharedSettings.ensureActivitySource(peerId, myHealth, peerHealth)

    override fun peerWalletMs(peerId: String): Long = synchronized(Diary) {
        Diary.query("SELECT COALESCE(SUM(delta_ms), 0) FROM peer_ops WHERE device_id = ?", peerId) { it.getLong(0) }.firstOrNull() ?: 0L
    }

    override fun giveWallet(peerId: String, ms: Long): Long {
        if (settleFailed) return 0L
        return Ledger.transferOut(peerId, ms, reserveMs)
    }

    override fun giveLimit(peerId: String, day: Long, ms: Long): Long = Diary.write { db ->
        val time = TrustedClock.snapshot()
        if (time.day != day || ms <= 0L) return@write 0L
        val share = Ledger.shareLeftMs(day) ?: return@write 0L
        val amount = minOf(ms, share)
        if (amount <= 0L) return@write 0L
        db.insertOrThrow("limit_moves", null, ContentValues().apply {
            put("uid", java.util.UUID.randomUUID().toString())
            put("author", deviceId)
            put("epoch", Diary.factsEpoch())
            put("aseq", Diary.nextFactSeq())
            put("to_device", peerId)
            put("day", day)
            put("ms", amount)
            put("created_utc_ms", time.utcMs)
        })
        amount
    }

    override fun onImported(days: Set<Long>, changed: Boolean) {
        if (!changed) return
        try {
            Store.recomputeStreak()
            if (days.isNotEmpty()) StatsRepository.invalidateDays(days)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось пересчитать после обмена", e)
        }
        Store.notifyChanged()
    }

    /** Разрыв пары: копия журнала второго телефона и курсоры операций удаляются; факты остаются. */
    fun unpair() {
        Diary.write { db ->
            db.delete("peer_ops", null, null)
            db.delete("sync_cursors", "log = ?", arrayOf(LogCursor.OPS))
        }
        Store.notifyChanged()
    }

    /** Сменился связанный телефон: копии прежнего удаляются. */
    fun forgetOtherPeers(peerId: String) {
        Diary.write { db ->
            db.delete("peer_ops", "device_id <> ?", arrayOf(peerId))
            db.delete("sync_cursors", "log = ? AND author <> ?", arrayOf(LogCursor.OPS, peerId))
        }
    }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private companion object {
        const val TAG = "DiarySyncStore"
        /** Дней активности в выгрузке источника. */
        const val ACTIVITY_DAYS = 15L
    }
}
