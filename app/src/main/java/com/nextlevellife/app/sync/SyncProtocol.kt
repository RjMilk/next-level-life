package com.nextlevellife.app.sync

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.SharedWalletMath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

// ---------- Записи журналов, которыми обмениваются телефоны (чистые данные, без Android) ----------

/**
 * Операция журнала баланса автора. Автор на проводе не передаётся: им считается телефон на другом конце
 * проверенного канала ([SyncChannel.peerDeviceId]) — чужую операцию «от имени» второго телефона не подсунуть.
 * [rev] — номер изменения у автора.
 */
data class OpRecord(
    val id: String,
    val idempotencyKey: String,
    val kind: String,
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
    val rev: Long,
)

/**
 * «Факт» — запись, которая не меняется и не удаляется: подход, прохождение комплекса, перенос доли лимита,
 * приём пищи и его удаление (удаление — тоже факт, сам приём остаётся в журнале), ответ на карточку.
 * Объединяется по (автор, поколение, номер); [uid] — постоянный идентификатор (повтор не дублирует).
 */
sealed interface FactRecord {
    val uid: String
    val author: String
    val epoch: String
    val aseq: Long
    val day: Long
}

/** Подход. [runUid] — uid прохождения комплекса (null — отдельный подход). */
data class SetFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val timeMs: Long,
    override val day: Long,
    val exerciseId: String,
    val exerciseName: String,
    val weightKg: Int?,
    val reps: Int,
    val durationMs: Long,
    val earnedMs: Long,
    val xp: Long,
    val runUid: String?,
    val measurement: String,
    val heldMs: Long,
) : FactRecord

/** Прохождение комплекса. */
data class RunFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val timeMs: Long,
    override val day: Long,
    val complexId: String,
    val weightKg: Int?,
    val durationMs: Long,
    val rounds: Int?,
    val extraReps: Int?,
    val totalReps: Int,
    val earnedMs: Long,
    val xp: Long,
    val completed: Boolean,
) : FactRecord

/** Перенос доли дневного лимита [ms] за учётные сутки [day] от автора телефону [toDevice]. */
data class LimitMoveFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val toDevice: String,
    override val day: Long,
    val ms: Long,
    val createdUtcMs: Long,
) : FactRecord

/**
 * Приём пищи (дневник питания). [day] — учётные сутки снимка, [capturedUtcMs] — доверенное время снимка,
 * [zoneId] — учётный пояс в момент снимка. Фото по сети не передаётся: [hasPhoto] — снимок есть у автора
 * (у получателя файла нет — «Фото на другом телефоне»). [note] — короткая заметка, задаётся только при создании.
 */
data class MealFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val capturedUtcMs: Long,
    override val day: Long,
    val zoneId: String,
    val hasPhoto: Boolean,
    val note: String?,
) : FactRecord

/**
 * Удаление приёма [mealUid] — отдельный факт: может прийти раньше самого приёма и переживает восстановление копии.
 * [day] — учётные сутки удаления, [utcMs] — доверенное время удаления.
 */
data class MealDeleteFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val mealUid: String,
    override val day: Long,
    val utcMs: Long,
) : FactRecord

/**
 * Первый ответ на карточку ([com.nextlevellife.app.data.Cards]). [cardId] — стабильный id карточки, [deck] — колода,
 * [utcMs] — доверенное время ответа, [day] — учётные сутки, [zoneId] — учётный пояс. [paidMs] — сколько начислил
 * телефон-автор (получатель по нему не начисляет, а только учитывает: карточка оплачена, потолок дня израсходован).
 */
data class CardAttemptFact(
    override val uid: String,
    override val author: String,
    override val epoch: String,
    override val aseq: Long,
    val cardId: String,
    val deck: String,
    val utcMs: Long,
    override val day: Long,
    val zoneId: String,
    val firstCorrect: Boolean,
    val paidMs: Long,
) : FactRecord

/**
 * Докуда принят журнал [log] автора [author] в поколении [epoch]: [pos] — последний принятый номер
 * (rev у операций, aseq у фактов).
 */
data class LogCursor(val log: String, val author: String, val epoch: String, val pos: Long) {
    companion object {
        const val OPS = "ops"
        const val FACTS = "facts"
    }
}

/** Активность за дни [fromDay]..[toDay] от источника активности: получатель заменяет эти дни целиком. */
data class ActivityExport(val fromDay: Long, val toDay: Long, val days: List<ActivityDay>, val runs: List<ActivityRun>)

// ---------- Сообщения ----------

sealed interface SyncMessage {
    /**
     * Приветствие. [walletMs] — свой кошелёк, который можно отдать (баланс минус неоплаченный расход),
     * [shareLeftMs] — оставшаяся доля лимита (null — без лимита), [spending] — сейчас тратит.
     * [cursors] — что у этого телефона уже есть: операции второго телефона и факты всех авторов.
     */
    data class Hello(
        val protocol: Int,
        val deviceId: String,
        val opsEpoch: String,
        val factsEpoch: String,
        val day: Long,
        val zoneId: String,
        val spending: Boolean,
        val walletMs: Long,
        val shareLeftMs: Long?,
        val healthConnected: Boolean,
        val cursors: List<LogCursor>,
    ) : SyncMessage

    /** Все общие настройки отправителя. */
    data class Settings(val rows: List<SharedWalletMath.Setting>) : SyncMessage

    /**
     * Пакет своих операций отправителя поколения [epoch], по возрастанию rev. [reset] — получатель сначала стирает
     * прежнюю копию журнала этого автора (у него записано другое поколение или ничего).
     */
    data class Ops(val epoch: String, val reset: Boolean, val rows: List<OpRecord>) : SyncMessage

    /** Пакет фактов (любых авторов), по возрастанию номера внутри (автор, поколение). */
    data class Facts(val rows: List<FactRecord>) : SyncMessage

    /** Активность от источника активности. */
    data class Activity(val export: ActivityExport) : SyncMessage

    /** Конец потока журналов (или переносов) отправителя. */
    data object EndLogs : SyncMessage

    /** Обмен закончен. */
    data object Done : SyncMessage
}

/** Нарушение протокола: неизвестное сообщение, другая версия, чужой автор. */
class SyncProtocolException(message: String) : IOException(message)

/**
 * Компактный двоичный формат сообщений: байт-метка формата, байт типа, затем поля. Строки — длина (int) и UTF-8,
 * необязательные поля — байт-признак. Неизвестный тип или обрезанное сообщение — [SyncProtocolException].
 */
object SyncCodec {
    /** 2 — приёмы пищи и их удаления ([MealFact], [MealDeleteFact]); 3 — ответы на карточки ([CardAttemptFact]). */
    const val PROTOCOL_VERSION = 3
    private const val MAGIC = 0x4E // 'N'

    private const val T_HELLO = 1
    private const val T_SETTINGS = 2
    private const val T_OPS = 3
    private const val T_FACTS = 4
    private const val T_ACTIVITY = 5
    private const val T_END = 6
    private const val T_DONE = 7

    private const val F_SET = 1
    private const val F_RUN = 2
    private const val F_MOVE = 3
    private const val F_MEAL = 4
    private const val F_MEAL_DELETE = 5
    private const val F_CARD = 6

    fun encode(m: SyncMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        val o = DataOutputStream(bytes)
        o.writeByte(MAGIC)
        when (m) {
            is SyncMessage.Hello -> {
                o.writeByte(T_HELLO)
                o.writeInt(m.protocol)
                o.str(m.deviceId); o.str(m.opsEpoch); o.str(m.factsEpoch)
                o.writeLong(m.day); o.str(m.zoneId)
                o.writeBoolean(m.spending); o.writeLong(m.walletMs); o.optLong(m.shareLeftMs)
                o.writeBoolean(m.healthConnected)
                o.writeInt(m.cursors.size)
                for (c in m.cursors) { o.str(c.log); o.str(c.author); o.str(c.epoch); o.writeLong(c.pos) }
            }
            is SyncMessage.Settings -> {
                o.writeByte(T_SETTINGS)
                o.writeInt(m.rows.size)
                for (s in m.rows) { o.str(s.key); o.str(s.value); o.writeLong(s.version); o.str(s.author) }
            }
            is SyncMessage.Ops -> {
                o.writeByte(T_OPS)
                o.str(m.epoch); o.writeBoolean(m.reset)
                o.writeInt(m.rows.size)
                for (r in m.rows) writeOp(o, r)
            }
            is SyncMessage.Facts -> {
                o.writeByte(T_FACTS)
                o.writeInt(m.rows.size)
                for (f in m.rows) writeFact(o, f)
            }
            is SyncMessage.Activity -> {
                o.writeByte(T_ACTIVITY)
                val e = m.export
                o.writeLong(e.fromDay); o.writeLong(e.toDay)
                o.writeInt(e.days.size)
                for (d in e.days) {
                    o.writeLong(d.day); o.optLong(d.steps); o.writeLong(d.runDistanceM); o.writeLong(d.runDurationMs)
                    o.writeLong(d.runSteps); o.writeInt(d.runsCount); o.str(d.sources.joinToString(","))
                    o.writeLong(d.rewardedMs); o.writeLong(d.updatedAtMs); o.writeBoolean(d.runsRead)
                }
                o.writeInt(e.runs.size)
                for (r in e.runs) {
                    o.str(r.externalId); o.writeLong(r.startMs); o.writeLong(r.endMs); o.writeLong(r.day)
                    o.writeLong(r.distanceM); o.writeLong(r.steps); o.str(r.source); o.writeInt(r.exerciseType)
                }
            }
            SyncMessage.EndLogs -> o.writeByte(T_END)
            SyncMessage.Done -> o.writeByte(T_DONE)
        }
        o.flush()
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): SyncMessage = try {
        val i = DataInputStream(ByteArrayInputStream(bytes))
        if (i.readUnsignedByte() != MAGIC) throw SyncProtocolException("Неизвестный формат сообщения")
        val m = when (val type = i.readUnsignedByte()) {
            T_HELLO -> {
                val protocol = i.readInt()
                SyncMessage.Hello(
                    protocol = protocol, deviceId = i.str(), opsEpoch = i.str(), factsEpoch = i.str(),
                    day = i.readLong(), zoneId = i.str(), spending = i.readBoolean(), walletMs = i.readLong(),
                    shareLeftMs = i.optLong(), healthConnected = i.readBoolean(),
                    cursors = List(i.count()) { LogCursor(i.str(), i.str(), i.str(), i.readLong()) },
                )
            }
            T_SETTINGS -> SyncMessage.Settings(List(i.count()) { SharedWalletMath.Setting(i.str(), i.str(), i.readLong(), i.str()) })
            T_OPS -> {
                val epoch = i.str()
                val reset = i.readBoolean()
                SyncMessage.Ops(epoch, reset, List(i.count()) { readOp(i) })
            }
            T_FACTS -> SyncMessage.Facts(List(i.count()) { readFact(i) })
            T_ACTIVITY -> {
                val from = i.readLong()
                val to = i.readLong()
                val days = List(i.count()) {
                    ActivityDay(
                        day = i.readLong(), steps = i.optLong(), runDistanceM = i.readLong(), runDurationMs = i.readLong(),
                        runSteps = i.readLong(), runsCount = i.readInt(),
                        sources = i.str().split(',').filter { it.isNotBlank() },
                        rewardedMs = i.readLong(), updatedAtMs = i.readLong(), runsRead = i.readBoolean(),
                    )
                }
                val runs = List(i.count()) {
                    ActivityRun(
                        externalId = i.str(), startMs = i.readLong(), endMs = i.readLong(), day = i.readLong(),
                        distanceM = i.readLong(), steps = i.readLong(), source = i.str(), exerciseType = i.readInt(),
                    )
                }
                SyncMessage.Activity(ActivityExport(from, to, days, runs))
            }
            T_END -> SyncMessage.EndLogs
            T_DONE -> SyncMessage.Done
            else -> throw SyncProtocolException("Неизвестный тип сообщения $type")
        }
        if (i.available() > 0) throw SyncProtocolException("Лишние байты в сообщении")
        m
    } catch (e: EOFException) {
        throw SyncProtocolException("Сообщение обрезано")
    }

    private fun writeOp(o: DataOutputStream, r: OpRecord) {
        o.str(r.id); o.str(r.idempotencyKey); o.str(r.kind); o.writeLong(r.deltaMs); o.writeLong(r.deltaXp)
        o.writeLong(r.occurredAtMs); o.writeLong(r.recordedAtMs); o.writeLong(r.accountingDay); o.str(r.zoneId)
        o.str(r.sourceType); o.str(r.sourceId); o.writeInt(r.ruleVersion); o.optStr(r.reversesId)
        o.str(r.metadataJson); o.writeLong(r.rev)
    }

    private fun readOp(i: DataInputStream) = OpRecord(
        id = i.str(), idempotencyKey = i.str(), kind = i.str(), deltaMs = i.readLong(), deltaXp = i.readLong(),
        occurredAtMs = i.readLong(), recordedAtMs = i.readLong(), accountingDay = i.readLong(), zoneId = i.str(),
        sourceType = i.str(), sourceId = i.str(), ruleVersion = i.readInt(), reversesId = i.optStr(),
        metadataJson = i.str(), rev = i.readLong(),
    )

    private fun writeFact(o: DataOutputStream, f: FactRecord) {
        when (f) {
            is SetFact -> {
                o.writeByte(F_SET); head(o, f)
                o.writeLong(f.timeMs); o.writeLong(f.day); o.str(f.exerciseId); o.str(f.exerciseName); o.optInt(f.weightKg)
                o.writeInt(f.reps); o.writeLong(f.durationMs); o.writeLong(f.earnedMs); o.writeLong(f.xp); o.optStr(f.runUid)
                o.str(f.measurement); o.writeLong(f.heldMs)
            }
            is RunFact -> {
                o.writeByte(F_RUN); head(o, f)
                o.writeLong(f.timeMs); o.writeLong(f.day); o.str(f.complexId); o.optInt(f.weightKg); o.writeLong(f.durationMs)
                o.optInt(f.rounds); o.optInt(f.extraReps); o.writeInt(f.totalReps); o.writeLong(f.earnedMs); o.writeLong(f.xp)
                o.writeBoolean(f.completed)
            }
            is LimitMoveFact -> {
                o.writeByte(F_MOVE); head(o, f)
                o.str(f.toDevice); o.writeLong(f.day); o.writeLong(f.ms); o.writeLong(f.createdUtcMs)
            }
            is MealFact -> {
                o.writeByte(F_MEAL); head(o, f)
                o.writeLong(f.capturedUtcMs); o.writeLong(f.day); o.str(f.zoneId); o.writeBoolean(f.hasPhoto); o.optStr(f.note)
            }
            is MealDeleteFact -> {
                o.writeByte(F_MEAL_DELETE); head(o, f)
                o.str(f.mealUid); o.writeLong(f.day); o.writeLong(f.utcMs)
            }
            is CardAttemptFact -> {
                o.writeByte(F_CARD); head(o, f)
                o.str(f.cardId); o.str(f.deck); o.writeLong(f.utcMs); o.writeLong(f.day); o.str(f.zoneId)
                o.writeBoolean(f.firstCorrect); o.writeLong(f.paidMs)
            }
        }
    }

    private fun head(o: DataOutputStream, f: FactRecord) {
        o.str(f.uid); o.str(f.author); o.str(f.epoch); o.writeLong(f.aseq)
    }

    private fun readFact(i: DataInputStream): FactRecord {
        val kind = i.readUnsignedByte()
        val uid = i.str()
        val author = i.str()
        val epoch = i.str()
        val aseq = i.readLong()
        return when (kind) {
            F_SET -> SetFact(
                uid, author, epoch, aseq, timeMs = i.readLong(), day = i.readLong(), exerciseId = i.str(),
                exerciseName = i.str(), weightKg = i.optInt(), reps = i.readInt(), durationMs = i.readLong(),
                earnedMs = i.readLong(), xp = i.readLong(), runUid = i.optStr(), measurement = i.str(), heldMs = i.readLong(),
            )
            F_RUN -> RunFact(
                uid, author, epoch, aseq, timeMs = i.readLong(), day = i.readLong(), complexId = i.str(),
                weightKg = i.optInt(), durationMs = i.readLong(), rounds = i.optInt(), extraReps = i.optInt(),
                totalReps = i.readInt(), earnedMs = i.readLong(), xp = i.readLong(), completed = i.readBoolean(),
            )
            F_MOVE -> LimitMoveFact(uid, author, epoch, aseq, toDevice = i.str(), day = i.readLong(), ms = i.readLong(), createdUtcMs = i.readLong())
            F_MEAL -> MealFact(
                uid, author, epoch, aseq, capturedUtcMs = i.readLong(), day = i.readLong(), zoneId = i.str(),
                hasPhoto = i.readBoolean(), note = i.optStr(),
            )
            F_MEAL_DELETE -> MealDeleteFact(uid, author, epoch, aseq, mealUid = i.str(), day = i.readLong(), utcMs = i.readLong())
            F_CARD -> CardAttemptFact(
                uid, author, epoch, aseq, cardId = i.str(), deck = i.str(), utcMs = i.readLong(), day = i.readLong(),
                zoneId = i.str(), firstCorrect = i.readBoolean(), paidMs = i.readLong(),
            )
            else -> throw SyncProtocolException("Неизвестный вид факта $kind")
        }
    }

    // ---------- Примитивы ----------

    /** Предел одной строки: защищает от поддельной длины. */
    private const val MAX_STRING_BYTES = 256 * 1024

    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.str(): String {
        val n = readInt()
        if (n < 0 || n > MAX_STRING_BYTES || n > available()) throw SyncProtocolException("Неверная длина строки")
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun DataInputStream.count(): Int {
        val n = readInt()
        if (n < 0 || n > available()) throw SyncProtocolException("Неверное число записей")
        return n
    }

    private fun DataOutputStream.optStr(s: String?) {
        writeBoolean(s != null)
        if (s != null) str(s)
    }

    private fun DataInputStream.optStr(): String? = if (readBoolean()) str() else null

    private fun DataOutputStream.optLong(v: Long?) {
        writeBoolean(v != null)
        if (v != null) writeLong(v)
    }

    private fun DataInputStream.optLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.optInt(v: Int?) {
        writeBoolean(v != null)
        if (v != null) writeInt(v)
    }

    private fun DataInputStream.optInt(): Int? = if (readBoolean()) readInt() else null
}
