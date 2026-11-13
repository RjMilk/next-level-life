package com.nextlevellife.app.stats

import android.util.Log
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.data.TrustedClockMath
import com.nextlevellife.app.sleep.ObservationGap
import com.nextlevellife.app.sleep.PhoneEvents
import com.nextlevellife.app.sleep.SleepMath
import com.nextlevellife.app.sleep.SleepRepository

/**
 * Характеристики за всю историю. [strength], [endurance], [discipline] — очки за всё время,
 * [days] — очки по дням (с первого дня с данными по [today]; сегодня — предварительно),
 * [legacyXp] — опыт до 0.6 (за минуты), только для истории.
 */
data class StatsSummary(
    val today: Long,
    val strength: Long,
    val endurance: Long,
    val discipline: Long,
    val days: Map<Long, DayPoints>,
    val legacyXp: Long,
) {
    fun total(stat: Stat): Long = when (stat) {
        Stat.STRENGTH -> strength
        Stat.ENDURANCE -> endurance
        Stat.DISCIPLINE -> discipline
    }

    /** Общие очки — сумма трёх характеристик; общий уровень — по той же формуле. */
    val overall: Long get() = strength + endurance + discipline
    val overallLevel: Int get() = StatsMath.levelFor(overall)

    val todayPoints: DayPoints get() = days[today] ?: DayPoints.empty(today, provisional = true)

    companion object {
        fun empty(today: Long, legacyXp: Long = 0L) = StatsSummary(today, 0, 0, 0, emptyMap(), legacyXp)
    }
}

/**
 * Собирает входные данные дней из дневника, журнала, ночей и событий блокировки и считает очки
 * ([StatsMath]). Только с фонового потока.
 *
 * Очки считаются из источников на лету. Дни старше [SETTLE_DAYS] суток замораживаются в кеше
 * (meta базы, ключ `stats_day:<день>`, строка с версией правил): к этому времени шаги из Health Connect
 * уже не пересинхронизируются, а сырые события экрана (нужные для проверки наблюдения) ещё не почищены
 * (они живут 90 дней). Так очки прошлых дней не «уплывают», когда сырые данные удаляются или меняются
 * настройки (лимит). Смена [StatsMath.RULE_VERSION] — кеш дня не читается и пересчитывается.
 */
object StatsRepository {
    private const val TAG = "StatsRepository"

    /** Дни не моложе этого (today − день ≥ SETTLE_DAYS) замораживаются в кеше. */
    const val SETTLE_DAYS = 16

    internal const val CACHE_PREFIX = "stats_day:"

    @Volatile private var last: StatsSummary? = null

    /** Последний посчитанный итог (без пересчёта); null — ещё не считали в этом процессе. */
    fun cached(): StatsSummary? = last

    /**
     * Итог за всю историю. Ошибка базы — последний известный итог или пустой (в интерфейсе — нули).
     */
    @Synchronized
    fun load(): StatsSummary {
        val snap = TrustedClock.snapshot()
        val legacy = runCatching { Ledger.xp() }.getOrDefault(0L)
        return try {
            compute(snap.utcMs, snap.day, snap.zone, legacy).also { last = it }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось посчитать характеристики", e)
            last ?: StatsSummary.empty(snap.day, legacy)
        }
    }

    /** Сила за день [day] по записям дневника (для экрана результата). Вызывать можно внутри транзакции записи. */
    fun strengthOfDay(day: Long): Int =
        StatsMath.strength(StatsMath.strengthInput(Diary.setsForDay(day), Diary.runsForDay(day))).points

    private fun compute(now: Long, today: Long, zone: java.time.ZoneId, legacyXp: Long): StatsSummary {
        val device = Ledger.deviceId()
        val firstEventMs = Diary.firstPhoneEventMs(device)
        val firstEventDay = firstEventMs?.let { SleepMath.dayOf(it, zone) }
        val first = listOfNotNull(Diary.firstDataDay(), firstEventDay).minOrNull()?.coerceAtMost(today)
            ?: return StatsSummary.empty(today, legacyXp)

        val settledThrough = today - SETTLE_DAYS
        val cached = HashMap<Long, DayPoints>()
        if (first <= settledThrough) {
            for ((key, value) in Diary.metaWithPrefix(CACHE_PREFIX)) {
                val day = key.removePrefix(CACHE_PREFIX).toLongOrNull() ?: continue
                if (day < first || day > settledThrough) continue
                StatsMath.decode(day, value)?.let { cached[day] = it }
            }
        }
        // Пересчитываются все дни без кеша и все незамороженные; диапазон — от самого раннего из них.
        val from = (first..today).firstOrNull { it !in cached } ?: today
        val fresh = computeRange(from, today, now, zone, device, firstEventMs)

        val toFreeze = fresh.filterKeys { it <= settledThrough && it !in cached }
        if (toFreeze.isNotEmpty()) {
            try {
                Diary.write {
                    for ((day, p) in toFreeze) Diary.metaPut(CACHE_PREFIX + day, StatsMath.encode(p))
                }
            } catch (e: Exception) {
                // Без кеша очки всё равно посчитаны; заморозка повторится при следующем расчёте.
                Log.e(TAG, "Не удалось записать кеш характеристик", e)
            }
        }

        val days = HashMap<Long, DayPoints>(cached)
        days.putAll(fresh.filterKeys { it !in cached })
        var s = 0L
        var e = 0L
        var d = 0L
        for (p in days.values) {
            s += p.strength
            e += p.endurance
            d += p.discipline
        }
        return StatsSummary(today, s, e, d, days, legacyXp)
    }

    /** Очки дней [from]..[to], данные читаются диапазонами (а не по дню). */
    private fun computeRange(
        from: Long, to: Long, now: Long, zone: java.time.ZoneId, device: String, firstEventMs: Long?,
    ): Map<Long, DayPoints> {
        val sets = Diary.setsInRange(from, to).groupBy { it.day }
        val runs = Diary.runsInRange(from, to).groupBy { it.day }
        val activity = Diary.activityDays(from, to).associateBy { it.day }
        val spent = Ledger.spendByDay(from, to)
        // Ночь дня D начинается вечером D−1: события блокировки берутся с запасом в сутки.
        val blocks = Diary.blockEvents(from - 1, to)
        val nights = SleepRepository.nights(from, to)
        val rangeStart = TrustedClockMath.dayStartMs(from - 1, zone)
        val gaps = Diary.observationGaps(device, rangeStart, Long.MAX_VALUE).toMutableList()
        if (!PhoneEvents.observing) {
            // Служба сейчас не наблюдает: после последнего события ничего не известно (как в SleepRepository).
            val lastEvent = Diary.lastPhoneEvent(device)?.utcMs
            if (lastEvent != null && now > lastEvent) gaps += ObservationGap(lastEvent, now)
        }
        val limitMin = Settings.dailyLimitMin

        val out = HashMap<Long, DayPoints>()
        for (day in from..to) {
            val daySets = sets[day].orEmpty()
            val dayRuns = runs[day].orEmpty()
            val discipline = StatsMath.disciplineInput(
                day = day,
                nowMs = now,
                dayStartMs = TrustedClockMath.dayStartMs(day, zone),
                dayEndMs = TrustedClockMath.dayStartMs(day + 1, zone),
                night = nights[day],
                blockEvents = blocks,
                gaps = gaps,
                observedSinceMs = firstEventMs,
                limitMin = limitMin,
                spentMs = spent[day] ?: 0L,
            )
            out[day] = StatsMath.day(
                day,
                StatsMath.strengthInput(daySets, dayRuns),
                StatsMath.enduranceInput(activity[day], dayRuns),
                discipline,
                provisional = day == to && !discipline.dayEnded,
            )
        }
        return out
    }
}
