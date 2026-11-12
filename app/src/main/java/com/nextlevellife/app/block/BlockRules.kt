package com.nextlevellife.app.block

import java.time.LocalTime

/** Почему заблокированное приложение сейчас не открывается. Порядок — приоритет: ночь важнее лимита, лимит важнее баланса. */
enum class BlockReason { NIGHT, LIMIT, BALANCE }

/**
 * Настройки, от которых зависит блокировка. Снимок берётся из [com.nextlevellife.app.data.Settings.blockSettings].
 * [dailyLimitMin] = 0 — лимита нет. Ночь — с [nightStartMin] (включительно) до [nightEndMin] (исключительно),
 * минуты от полуночи; окно может переходить через полночь, начало == конец — ночного режима нет.
 */
data class BlockSettings(
    val dailyLimitMin: Int,
    val nightEnabled: Boolean,
    val nightStartMin: Int,
    val nightEndMin: Int,
)

/** [reason] == null — смотреть можно ещё [availableMs]; иначе [availableMs] == 0. */
data class BlockDecision(val reason: BlockReason?, val availableMs: Long)

/** Правила блокировки без Android: их проверяют юнит-тесты. */
object BlockRules {
    const val MINUTES_PER_DAY = 24 * 60
    /** Допустимый дневной лимит: 0 (без лимита) … сутки. */
    val LIMIT_RANGE = 0..MINUTES_PER_DAY
    /** Допустимое время суток в минутах. */
    val MINUTE_RANGE = 0 until MINUTES_PER_DAY

    fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    /** Попадает ли минута суток в ночное окно. */
    fun isNight(minuteOfDay: Int, s: BlockSettings): Boolean {
        if (!s.nightEnabled) return false
        val start = s.nightStartMin
        val end = s.nightEndMin
        return when {
            start == end -> false
            start < end -> minuteOfDay in start until end
            // Через полночь: 23:00–07:00 — это [23:00, 24:00) и [00:00, 07:00).
            else -> minuteOfDay >= start || minuteOfDay < end
        }
    }

    /** Сколько ещё можно потратить сегодня по лимиту; null — лимита нет. */
    fun limitLeftMs(spentTodayMs: Long, s: BlockSettings): Long? {
        if (s.dailyLimitMin <= 0) return null
        return (s.dailyLimitMin * 60_000L - spentTodayMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /**
     * Решение для текущего момента: причина блокировки (NIGHT, затем LIMIT, затем BALANCE) или
     * сколько ещё можно смотреть — min(баланс, лимит − потрачено сегодня).
     */
    fun evaluate(minuteOfDay: Int, balanceMs: Long, spentTodayMs: Long, s: BlockSettings): BlockDecision {
        if (isNight(minuteOfDay, s)) return BlockDecision(BlockReason.NIGHT, 0L)
        val limitLeft = limitLeftMs(spentTodayMs, s)
        if (limitLeft != null && limitLeft <= 0L) return BlockDecision(BlockReason.LIMIT, 0L)
        val balance = balanceMs.coerceAtLeast(0L)
        if (balance <= 0L) return BlockDecision(BlockReason.BALANCE, 0L)
        return BlockDecision(null, if (limitLeft == null) balance else minOf(balance, limitLeft))
    }

    fun evaluate(time: LocalTime, balanceMs: Long, spentTodayMs: Long, s: BlockSettings): BlockDecision =
        evaluate(minuteOfDay(time), balanceMs, spentTodayMs, s)

    /** «07:00» для минут от полуночи. */
    fun formatClock(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
