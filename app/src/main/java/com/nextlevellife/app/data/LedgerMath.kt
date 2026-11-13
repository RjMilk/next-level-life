package com.nextlevellife.app.data

import java.time.Instant
import java.time.ZoneId

/** Вид операции журнала баланса. Имя пишется в базу как есть — не переименовывать. */
enum class OpKind {
    /** Перенос баланса и опыта из SharedPreferences при обновлении на 0.3. */
    OPENING,
    SET_REWARD,
    COMPLEX_REWARD,
    /** Расход экранного времени (отрицательная дельта). */
    USAGE,
    STEPS_REWARD,
    RUN_REWARD,
    CARD_REWARD,
    DEV_ADJUSTMENT,
    /** Пока только объявлено. */
    REVERSAL,
    /** Пока только объявлено. */
    EMERGENCY_FEE;

    companion object {
        /** Начисления за занятия: их сумма — «заработано за день». */
        val REWARDS = setOf(SET_REWARD, COMPLEX_REWARD, STEPS_REWARD, RUN_REWARD, CARD_REWARD)
    }
}

/** Где сейчас «открытая» строка расхода: её дельту ещё можно наращивать. */
data class UsageCursor(
    val sessionId: String,
    val packageName: String,
    /** Номер строки внутри сессии: ключ `usage:<sessionId>:<index>`. */
    val index: Int,
    val day: Long,
    val accumulatedMs: Long,
)

/**
 * Как разложить списание по строкам: [addToOpen] — добавить к открытой строке,
 * [newRows] — новые строки (каждая не больше порции), первая из них получает номер [firstNewIndex].
 * [next] — курсор после успешной записи.
 */
data class UsagePlan(
    val addToOpen: Long,
    val newRows: List<Long>,
    val firstNewIndex: Int,
    val next: UsageCursor,
)

/** Часть списания в учётных сутках [day]: [ms] расхода, время операции [occurredAtMs]. */
data class DayShare(val day: Long, val ms: Long, val occurredAtMs: Long)

/** Чистая логика журнала, без Android: её проверяют юнит-тесты. */
object LedgerMath {
    /** Одна строка USAGE копит не больше минуты расхода. */
    const val USAGE_CHUNK_MS = 60_000L

    /** Версия правил начисления; пишется в каждую операцию. */
    const val RULE_VERSION = 1

    const val OPENING_KEY = "opening"

    fun setKey(sessionId: String) = "set:$sessionId"
    fun complexKey(sessionId: String) = "complex:$sessionId"
    fun usageKey(sessionId: String, index: Int) = "usage:$sessionId:$index"

    /**
     * Учётные сутки (LocalDate.toEpochDay) момента [timeMs] в поясе [zone].
     * В приложении пояс — учётный ([TrustedClock.zone]), а момент — доверенный ([TrustedClock.nowMs]).
     */
    fun accountingDay(timeMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Делит просмотр длиной [durationMs], закончившийся в [endMs] (интервал [endMs − durationMs, endMs)),
     * по учётным суткам пояса [zone]. Части — по возрастанию суток; у каждой [DayShare.occurredAtMs] —
     * конец части внутри её суток. Пустая длительность — пустой список.
     */
    fun splitByDay(endMs: Long, durationMs: Long, zone: ZoneId): List<DayShare> {
        require(durationMs >= 0) { "durationMs должен быть ≥ 0" }
        val out = ArrayList<DayShare>(2)
        var to = endMs
        var rest = durationMs
        while (rest > 0) {
            // Миллисекунда to − 1 — последняя в части: по ней и сутки.
            val day = accountingDay(to - 1, zone)
            val dayStart = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val part = minOf(rest, to - dayStart)
            val occurred = if (to == endMs && accountingDay(endMs, zone) == day) endMs else to - 1
            out += DayShare(day, part, occurred)
            to -= part
            rest -= part
        }
        out.reverse()
        return out
    }

    /**
     * Раскладывает списание [chargeMs] на открытую строку и новые строки по [chunkMs].
     * Открытую строку можно наращивать, только если это та же сессия, тот же пакет и те же сутки,
     * и в ней ещё есть место. Номера строк внутри сессии не повторяются.
     */
    fun planUsage(
        open: UsageCursor?,
        sessionId: String,
        packageName: String,
        day: Long,
        chargeMs: Long,
        chunkMs: Long = USAGE_CHUNK_MS,
    ): UsagePlan {
        require(chargeMs > 0) { "chargeMs должен быть > 0" }
        require(chunkMs > 0)
        val sameSession = open != null && open.sessionId == sessionId
        val canExtend = sameSession && open!!.packageName == packageName && open.day == day &&
            open.accumulatedMs < chunkMs
        val addToOpen = if (canExtend) minOf(chargeMs, chunkMs - open!!.accumulatedMs) else 0L
        var rest = chargeMs - addToOpen
        val rows = ArrayList<Long>()
        while (rest > 0) {
            val part = minOf(rest, chunkMs)
            rows += part
            rest -= part
        }
        val firstNewIndex = if (sameSession) open!!.index + 1 else 0
        val next = when {
            rows.isNotEmpty() -> UsageCursor(sessionId, packageName, firstNewIndex + rows.size - 1, day, rows.last())
            else -> open!!.copy(accumulatedMs = open.accumulatedMs + addToOpen)
        }
        return UsagePlan(addToOpen, rows, firstNewIndex, next)
    }
}
