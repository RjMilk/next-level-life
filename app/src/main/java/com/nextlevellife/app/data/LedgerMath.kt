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
    EMERGENCY_FEE,
    /**
     * Перенос расхода при восстановлении копии (отрицательная дельта): расход, сделанный после копии,
     * дописывается в восстановленный журнал по своим суткам и приложениям — баланс и дневной расход не откатываются.
     */
    RESTORE_CARRY,
    /**
     * Перенос минут на второй телефон (отрицательная дельта, пишется отправителем первым). source_id — device_id
     * получателя (metadata `to`). Не расход: в дневной лимит и статистику не входит.
     */
    TRANSFER_OUT,
    /**
     * Минуты, полученные со второго телефона (положительная дельта), ровно один раз на исходящую операцию:
     * ключ `transfer_in:<id исходящей>`, source_id — id исходящей (metadata `from` — device_id отправителя).
     * Не заработок: в «заработано за день» и статистику не входит.
     */
    TRANSFER_IN;

    companion object {
        /** Начисления за занятия: их сумма — «заработано за день». */
        val REWARDS = setOf(SET_REWARD, COMPLEX_REWARD, STEPS_REWARD, RUN_REWARD, CARD_REWARD)

        /** Расход экранного времени: из них считаются дневной расход (лимит) и расход по приложениям. */
        val SPENDING = setOf(USAGE, RESTORE_CARRY)

        /** Переносы между телефонами пары: меняют кошелёк, но не заработок и не расход. */
        val TRANSFERS = setOf(TRANSFER_OUT, TRANSFER_IN)

        /** Вид по имени из базы или с другого телефона; неизвестное имя (из более новой версии) — null. */
        fun parse(name: String?): OpKind? = entries.firstOrNull { it.name == name }
    }
}

/** Подпись операции журнала для экранов. */
fun OpKind.label(): String = when (this) {
    OpKind.OPENING -> "Перенос баланса"
    OpKind.SET_REWARD -> "Подходы"
    OpKind.COMPLEX_REWARD -> "Комплекс"
    OpKind.USAGE -> "Расход"
    OpKind.STEPS_REWARD -> "Шаги и бег"
    OpKind.RUN_REWARD -> "Бег"
    OpKind.CARD_REWARD -> "Карточки"
    OpKind.DEV_ADJUSTMENT -> "Поправка"
    OpKind.REVERSAL -> "Отмена начисления"
    OpKind.EMERGENCY_FEE -> "Аварийный доступ"
    OpKind.RESTORE_CARRY -> "Расход после копии"
    OpKind.TRANSFER_OUT -> "Отдано второму телефону"
    OpKind.TRANSFER_IN -> "Получено со второго телефона"
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

/**
 * Неоплаченный просмотр (журнал не принял списание): интервал [startMs, endMs) доверенного времени
 * в приложении [packageName], сессия службы [sessionId]. Списывается позже по своим суткам.
 */
data class UsageDebt(val startMs: Long, val endMs: Long, val packageName: String, val sessionId: String) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Часть списания: приложение [packageName], доля суток [share]; [debtSessionId] — сессия долга (null — текущий просмотр). */
data class ChargePart(val packageName: String, val share: DayShare, val debtSessionId: String? = null)

/** Строка расхода (USAGE или RESTORE_CARRY): ключ, учётные сутки, приложение, потрачено (мс, > 0). */
data class SpentRow(val key: String, val day: Long, val packageName: String, val spentMs: Long)

/** Перенос расхода при восстановлении: [ms] в учётные сутки [day] по приложению [packageName]. */
data class CarryPart(val day: Long, val packageName: String, val ms: Long)

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
    fun restoreCarryKey(restoreId: String, day: Long, packageName: String) = "restore_carry:$restoreId:$day:$packageName"
    fun transferOutKey(transferId: String) = "transfer_out:$transferId"
    /** Ключ входящего переноса: один на исходящую операцию [outOpId] второго телефона. */
    fun transferInKey(outOpId: String) = "transfer_in:$outOpId"

    /**
     * Что осталось неоплаченным после списания [chargedMs] по плану [planCharges] (тот же порядок: долги [debts]
     * по концу интервала, затем текущий просмотр [elapsedMs] приложения [packageName], закончившийся в [endMs]).
     * У каждой части списывается её конец, поэтому остаток — начало интервала. Без остатка — пустой список.
     * Так долг, на который не хватило баланса, не пропадает, а ждёт следующего списания.
     */
    fun leftoverDebts(
        debts: List<UsageDebt>,
        packageName: String,
        sessionId: String,
        endMs: Long,
        elapsedMs: Long,
        chargedMs: Long,
    ): List<UsageDebt> {
        var left = chargedMs.coerceAtLeast(0L)
        val out = ArrayList<UsageDebt>()
        for (d in debts.sortedBy { it.endMs }) {
            val c = minOf(d.durationMs, left)
            left -= c
            if (d.durationMs - c > 0L) out += d.copy(endMs = d.endMs - c)
        }
        val current = elapsedMs.coerceAtLeast(0L)
        val c = minOf(current, left)
        if (current - c > 0L) out += UsageDebt(endMs - current, endMs - c, packageName, sessionId)
        return out
    }

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

    /**
     * Что списать за один тик: сначала долги [debts] (старые первыми), каждый по своим суткам — по концу
     * своего интервала, затем текущий просмотр [elapsedMs] приложения [packageName], закончившийся в [endMs].
     * Всего — не больше баланса [balanceMs]; не хватает — у части списывается её конец (как и раньше).
     */
    fun planCharges(
        debts: List<UsageDebt>,
        packageName: String,
        endMs: Long,
        elapsedMs: Long,
        balanceMs: Long,
        zone: ZoneId,
    ): List<ChargePart> {
        var left = balanceMs.coerceAtLeast(0L)
        val out = ArrayList<ChargePart>()
        fun take(pkg: String, end: Long, ms: Long, debtSession: String?) {
            val charge = minOf(ms, left)
            if (charge <= 0L) return
            for (share in splitByDay(end, charge, zone)) out += ChargePart(pkg, share, debtSession)
            left -= charge
        }
        for (d in debts.sortedBy { it.endMs }) take(d.packageName, d.endMs, d.durationMs, d.sessionId)
        take(packageName, endMs, elapsedMs.coerceAtLeast(0L), null)
        return out
    }

    /**
     * Перенос расхода при восстановлении копии. [current] — строки расхода текущего журнала (до подмены),
     * [backup] — расход по ключам в журнале копии. Переносится всё, чего в копии нет или меньше
     * (расход после копии, в том числе прежние переносы), по суткам и приложениям. Всего — не больше
     * баланса копии [restoredBalanceMs] (баланс не уходит ниже нуля); не хватает — сначала поздние сутки
     * (сегодняшний расход важнее для лимита). Результат — по возрастанию суток, затем приложения.
     */
    fun restoreCarry(current: List<SpentRow>, backup: Map<String, Long>, restoredBalanceMs: Long): List<CarryPart> {
        val acc = LinkedHashMap<Pair<Long, String>, Long>()
        for (r in current) {
            val diff = r.spentMs - (backup[r.key] ?: 0L).coerceAtLeast(0L)
            if (diff > 0L) acc[r.day to r.packageName] = (acc[r.day to r.packageName] ?: 0L) + diff
        }
        var left = restoredBalanceMs.coerceAtLeast(0L)
        val out = ArrayList<CarryPart>()
        val latestFirst = acc.entries.sortedWith(compareBy({ -it.key.first }, { it.key.second }))
        for ((k, ms) in latestFirst) {
            val c = minOf(ms, left)
            if (c <= 0L) break
            out += CarryPart(k.first, k.second, c)
            left -= c
        }
        return out.sortedWith(compareBy({ it.day }, { it.packageName }))
    }
}
