package com.nextlevellife.app.sync

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.LedgerMath
import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.SharedWalletMath
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Хранилище обмена в памяти для тестов: те же правила, что у дневника (кошелёк — сумма своих операций,
 * доля лимита — [SharedWalletMath], приём — [SyncRules]). Каждый apply меняет состояние целиком или никак.
 */
class MemorySyncStore(
    override val deviceId: String,
    var limitMs: Long = 0L,
    var day: Long = 100L,
) : SyncStore {
    var opsEpoch = "ops-$deviceId-1"
    var factsEpoch = "facts-$deviceId-1"
    private var rev = 0L
    private var factSeq = 0L

    /** Свой журнал: id → операция. */
    val ops = LinkedHashMap<String, OpRecord>()

    /** Копии журналов: автор → (id → операция); поколение копии — в курсоре. */
    val peerOps = HashMap<String, LinkedHashMap<String, OpRecord>>()
    val opsCursors = HashMap<String, LogCursor>()

    /** Факты всех авторов по uid. */
    val facts = LinkedHashMap<String, FactRecord>()

    val settingRows = HashMap<String, SharedWalletMath.Setting>()
    var activity: ActivityExport? = null
    val activityDays = HashMap<Long, ActivityDay>()

    var spending = false
    var debtMs = 0L
    var healthConnected = false
    var importedCalls = 0

    /** Сбой записи: следующий apply бросает исключение (до изменения состояния). */
    var failNextApply = false

    fun balance(): Long = ops.values.sumOf { it.deltaMs }

    fun peerBalance(peer: String): Long = peerOps[peer]?.values?.sumOf { it.deltaMs } ?: 0L

    fun ownSpent(d: Long = day): Long =
        -ops.values.filter { it.kind == OpKind.USAGE.name && it.accountingDay == d }.sumOf { it.deltaMs }

    fun shareLeft(peer: String?, d: Long = day): Long? {
        val incoming = if (peer == null) 0L else facts.values.filterIsInstance<LimitMoveFact>()
            .filter { it.author == peer && it.toDevice == deviceId && it.day == d }.sumOf { it.ms }
        val outgoing = facts.values.filterIsInstance<LimitMoveFact>().filter { it.author == deviceId && it.day == d }.sumOf { it.ms }
        return SharedWalletMath.shareLeftMs(limitMs, deviceId, peer, incoming, outgoing, ownSpent(d))
    }

    private fun addOp(kind: OpKind, deltaMs: Long, key: String = "${kind.name.lowercase()}:${UUID.randomUUID()}", sourceId: String = "", id: String = UUID.randomUUID().toString()): OpRecord {
        val r = OpRecord(
            id = id, idempotencyKey = key, kind = kind.name, deltaMs = deltaMs, deltaXp = 0L,
            occurredAtMs = day * 86_400_000L, recordedAtMs = day * 86_400_000L, accountingDay = day, zoneId = "UTC",
            sourceType = if (kind in OpKind.TRANSFERS) "transfer" else "test", sourceId = sourceId, ruleVersion = 1,
            reversesId = null, metadataJson = "{}", rev = ++rev,
        )
        ops[r.id] = r
        return r
    }

    fun earn(ms: Long) = addOp(OpKind.SET_REWARD, ms)

    /**
     * Потратить до [ms]: не больше кошелька и своей доли лимита (как служба блокировки). [peer] — связанный телефон.
     * Возвращает, сколько потрачено.
     */
    fun spend(ms: Long, peer: String?): Long {
        val share = shareLeft(peer)
        val can = minOf(ms, balance(), share ?: Long.MAX_VALUE).coerceAtLeast(0L)
        if (can > 0L) addOp(OpKind.USAGE, -can)
        return can
    }

    /** Дописать к последней строке расхода (открытая USAGE): новый rev. */
    fun extendLastUsage(ms: Long) {
        val last = ops.values.last { it.kind == OpKind.USAGE.name }
        ops[last.id] = last.copy(deltaMs = last.deltaMs - ms, rev = ++rev)
    }

    fun addSet(reps: Int): SetFact {
        val f = SetFact(
            uid = UUID.randomUUID().toString(), author = deviceId, epoch = factsEpoch, aseq = ++factSeq,
            timeMs = day * 86_400_000L, day = day, exerciseId = "pushups", exerciseName = "Отжимания", weightKg = null,
            reps = reps, durationMs = 0L, earnedMs = 0L, xp = 0L, runUid = null, measurement = "REPS", heldMs = 0L,
        )
        facts[f.uid] = f
        return f
    }

    /**
     * Восстановление копии [backup]: журнал и факты копии, новые поколения, курсоры сброшены; исходящие переносы
     * текущего журнала, которых нет в копии, возвращаются той же операцией (как Ledger.appendRestoreCarry).
     */
    fun restoreFrom(backup: MemorySyncStore) {
        val outs = ops.values.filter { it.kind == OpKind.TRANSFER_OUT.name }
        ops.clear()
        ops.putAll(backup.ops)
        rev = maxOf(rev, backup.rev)
        for (o in outs) if (backup.ops.values.none { it.idempotencyKey == o.idempotencyKey }) ops[o.id] = o.copy(rev = ++rev)
        facts.clear()
        facts.putAll(backup.facts)
        peerOps.clear()
        opsCursors.clear()
        opsEpoch = "ops-$deviceId-${UUID.randomUUID()}"
        factsEpoch = "facts-$deviceId-${UUID.randomUUID()}"
        factSeq = 0L
    }

    fun snapshot(): MemorySyncStore = MemorySyncStore(deviceId, limitMs, day).also { s ->
        s.ops.putAll(ops)
        s.facts.putAll(facts)
        s.rev = rev
        s.opsEpoch = opsEpoch
        s.factsEpoch = factsEpoch
        s.factSeq = factSeq
    }

    // ---------- SyncStore ----------

    override fun localState(peerId: String) = LocalState(
        opsEpoch = opsEpoch, factsEpoch = factsEpoch, day = day, zoneId = "UTC", spending = spending,
        walletMs = balance() - debtMs, shareLeftMs = shareLeft(peerId), healthConnected = healthConnected,
    )

    override fun opsCursor(peerId: String): LogCursor? = opsCursors[peerId]

    override fun factCursors(): List<LogCursor> =
        facts.values.groupBy { it.author to it.epoch }.map { (k, v) -> LogCursor(LogCursor.FACTS, k.first, k.second, v.maxOf { it.aseq }) }

    override fun opsAfter(afterRev: Long, maxRev: Long, limit: Int): List<OpRecord> =
        ops.values.filter { it.rev > afterRev && it.rev <= maxRev }.sortedBy { it.rev }.take(limit)

    override fun maxOpsRev(): Long = ops.values.maxOfOrNull { it.rev } ?: 0L

    override fun factsAfter(author: String, epoch: String, afterAseq: Long, limit: Int): List<FactRecord> =
        facts.values.filter { it.author == author && it.epoch == epoch && it.aseq > afterAseq }.sortedBy { it.aseq }.take(limit)

    private fun checkFail() {
        if (failNextApply) {
            failNextApply = false
            throw IllegalStateException("сбой записи")
        }
    }

    override fun applyOps(peerId: String, epoch: String, reset: Boolean, rows: List<OpRecord>): Long {
        checkFail()
        val cursor = opsCursors[peerId]
        if (!reset && (cursor == null || cursor.epoch != epoch)) throw SyncProtocolException("Пакет не того поколения")
        val copy = if (reset) LinkedHashMap() else LinkedHashMap(peerOps[peerId].orEmpty())
        for (r in rows) copy[r.id] = r
        val pos = rows.maxOfOrNull { it.rev } ?: if (reset) 0L else cursor!!.pos
        // Применение целиком.
        peerOps[peerId] = copy
        opsCursors[peerId] = LogCursor(LogCursor.OPS, peerId, epoch, maxOf(pos, if (reset) 0L else cursor!!.pos))
        val have = ops.values.filter { it.kind == OpKind.TRANSFER_IN.name }.map { it.idempotencyKey }.toSet()
        var received = 0L
        for (out in SyncRules.missingTransferIns(copy.values, deviceId, have)) {
            addOp(OpKind.TRANSFER_IN, -out.deltaMs, key = LedgerMath.transferInKey(out.id), sourceId = out.id)
            received += -out.deltaMs
        }
        return received
    }

    override fun applyFacts(peerId: String, rows: List<FactRecord>): Set<Long> {
        checkFail()
        val days = HashSet<Long>()
        for (f in rows) {
            if (!SyncRules.acceptFact(f, deviceId, factsEpoch)) continue
            if (f.uid in facts) continue
            facts[f.uid] = f
            days += f.day
        }
        return days
    }

    override fun isActivitySource(peerId: String): Boolean = settingRows["activity_source"]?.value == deviceId

    override fun activityExport(): ActivityExport? = activity

    override fun applyActivity(peerId: String, export: ActivityExport): Set<Long> {
        if (settingRows["activity_source"]?.value != peerId) return emptySet()
        for (d in export.fromDay..export.toDay) activityDays.remove(d)
        for (d in export.days) activityDays[d.day] = d
        return (export.fromDay..export.toDay).toSet()
    }

    override fun settings(): List<SharedWalletMath.Setting> = settingRows.values.toList()

    override fun applySettings(remote: List<SharedWalletMath.Setting>): Boolean {
        val merged = SharedWalletMath.mergeAll(settingRows.values, remote)
        val changed = merged != settingRows
        settingRows.clear()
        settingRows.putAll(merged)
        return changed
    }

    /** Локальное изменение общей настройки (как сеттер Settings). */
    fun setSetting(key: String, value: String) {
        settingRows[key] = SharedWalletMath.Setting(key, value, SharedWalletMath.nextVersion(settingRows.values, key), deviceId)
    }

    override fun ensureActivitySource(peerId: String, myHealth: Boolean, peerHealth: Boolean) {
        if ("activity_source" in settingRows) return
        val src = SharedWalletMath.defaultActivitySource(deviceId, myHealth, peerId, peerHealth)
        settingRows["activity_source"] = SharedWalletMath.Setting("activity_source", src, 1L, minOf(deviceId, peerId))
    }

    override fun peerWalletMs(peerId: String): Long = peerBalance(peerId)

    override fun giveWallet(peerId: String, ms: Long): Long {
        val amount = minOf(ms, balance() - debtMs)
        if (amount <= 0L) return 0L
        addOp(OpKind.TRANSFER_OUT, -amount, key = LedgerMath.transferOutKey(UUID.randomUUID().toString()), sourceId = peerId)
        return amount
    }

    override fun giveLimit(peerId: String, day: Long, ms: Long): Long {
        if (day != this.day) return 0L
        val amount = minOf(ms, shareLeft(peerId) ?: 0L)
        if (amount <= 0L) return 0L
        val f = LimitMoveFact(UUID.randomUUID().toString(), deviceId, factsEpoch, ++factSeq, peerId, day, amount, 0L)
        facts[f.uid] = f
        return amount
    }

    override fun onImported(days: Set<Long>, changed: Boolean) {
        importedCalls++
    }
}

/**
 * Пара каналов в памяти: что отправил один — получает другой, по порядку. [breakAfter] — после стольких отправленных
 * сообщений (суммарно с обеих сторон) связь рвётся: отправка и ожидание бросают IOException.
 */
class MemoryChannelPair(a: String, b: String, breakAfter: Int = Int.MAX_VALUE) {
    private val toA = LinkedBlockingQueue<ByteArray>()
    private val toB = LinkedBlockingQueue<ByteArray>()
    private val budget = AtomicInteger(breakAfter)
    @Volatile private var broken = false
    private val poison = ByteArray(0)

    val sent = AtomicInteger(0)

    /** Канал телефона [a] (открывший соединение). */
    val forA: SyncChannel = Ch(peerDeviceId = b, initiator = true, out = toB, inbox = toA)

    /** Канал телефона [b]. */
    val forB: SyncChannel = Ch(peerDeviceId = a, initiator = false, out = toA, inbox = toB)

    private fun breakLink() {
        broken = true
        toA.offer(poison)
        toB.offer(poison)
    }

    private inner class Ch(
        override val peerDeviceId: String,
        override val initiator: Boolean,
        val out: LinkedBlockingQueue<ByteArray>,
        val inbox: LinkedBlockingQueue<ByteArray>,
    ) : SyncChannel {
        override fun send(message: ByteArray) {
            if (broken) throw IOException("связь оборвана")
            require(message.size <= SyncChannel.MAX_MESSAGE_BYTES)
            if (budget.getAndDecrement() <= 0) {
                breakLink()
                throw IOException("связь оборвана")
            }
            sent.incrementAndGet()
            out.put(message)
        }

        override fun receive(): ByteArray {
            val m = inbox.poll(10, TimeUnit.SECONDS) ?: throw IOException("тайм-аут")
            if (m === poison) {
                breakLink()
                throw IOException("связь оборвана")
            }
            return m
        }

        override fun close() {
            breakLink()
        }
    }
}
