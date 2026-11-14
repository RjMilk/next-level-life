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
import java.time.ZoneId

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
    /** Штраф, который придёт, если сегодня закончится без очков характеристики ([StatsMath.withPenalties]). */
    val risk: Map<Stat, Int> = emptyMap(),
) {
    fun riskOf(stat: Stat): Int = risk[stat] ?: 0

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
 * уже не пересинхронизируются. Сырые события экрана (нужные для проверки наблюдения) чистятся не раньше
 * первого незамороженного дня ([firstUnfrozenDay], [SleepRepository.maintainOnWriter]). Так очки прошлых
 * дней не «уплывают», когда сырые данные удаляются или меняются настройки (лимит). Смена
 * [StatsMath.RULE_VERSION] — кеш дня не читается и пересчитывается.
 *
 * Расчёт — много отдельных чтений, и между ними базу может подменить восстановление копии. Поэтому
 * поколение базы ([Diary.generation]) запоминается в начале и проверяется перед записью кеша (под
 * блокировкой Diary) и перед публикацией; изменилось — расчёт повторяется. Под блокировкой Diary
 * расчёт не держится: чтение ночей ждёт очередь [PhoneEvents], которой самой нужна эта блокировка.
 * Ошибка или тайм-аут чтения ночей — в этом проходе ничего не замораживается, а дни показываются
 * предварительными.
 */
object StatsRepository {
    private const val TAG = "StatsRepository"

    /** Дни не моложе этого (today − день ≥ SETTLE_DAYS) замораживаются в кеше. */
    const val SETTLE_DAYS = 16

    /** Сколько раз пересчитать, если базу подменили во время расчёта. */
    private const val MAX_ATTEMPTS = 3

    internal const val CACHE_PREFIX = "stats_day:"

    /** meta: учётный день, с которого действуют штрафы за простой. */
    internal const val PENALTY_SINCE_KEY = "stats_penalty_since"

    @Volatile private var last: StatsSummary? = null

    /** Последний посчитанный итог (без пересчёта); null — ещё не считали в этом процессе. */
    fun cached(): StatsSummary? = last

    /**
     * Итог за всю историю. Ошибка базы — последний известный итог или пустой (в интерфейсе — нули).
     */
    @Synchronized
    fun load(): StatsSummary {
        var snap = TrustedClock.snapshot()
        var legacy = 0L
        try {
            repeat(MAX_ATTEMPTS) {
                val gen = Diary.generation
                snap = TrustedClock.snapshot()
                legacy = runCatching { Ledger.xp() }.getOrDefault(0L)
                val summary = compute(snap.utcMs, snap.day, snap.zone, legacy, gen)
                // Публикуется только итог по той базе, что сейчас на месте.
                if (summary != null && Diary.generation == gen) {
                    last = summary
                    return summary
                }
                Log.w(TAG, "База подменена во время расчёта характеристик: пересчёт")
            }
            Log.w(TAG, "База подменялась во время каждого расчёта: показан прежний итог")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось посчитать характеристики", e)
        }
        return last ?: StatsSummary.empty(snap.day, legacy)
    }

    /**
     * Замороженные дни [days] пересчитать: в дневник пришли записи второго телефона (поздний импорт). Метка версии
     * кеша дня переписывается на предыдущую версию правил: день не читается как замороженный и считается заново,
     * а дисциплина берётся из прежнего кеша ([StatsMath.keepOldDiscipline]) — её исходные события могли быть удалены.
     */
    fun invalidateDays(days: Collection<Long>) {
        if (days.isEmpty()) return
        Diary.write {
            for (day in days) {
                val key = CACHE_PREFIX + day
                val raw = Diary.metaGet(key) ?: continue
                val p = StatsMath.decode(day, raw) ?: continue
                Diary.metaPut(key, StatsMath.encode(p, StatsMath.RULE_VERSION - 1))
            }
        }
    }

    /**
     * Сила за день [day] по записям дневника и строке активности (полный день «Сайтамы» — завершённый комплекс),
     * для экрана результата. Вызывать можно внутри транзакции записи.
     */
    fun strengthOfDay(day: Long): Int =
        StatsMath.strength(
            StatsMath.strengthInput(Diary.setsForDay(day), Diary.runsForDay(day), Diary.activityDay(day)),
        ).points

    /**
     * Первый день характеристик, очки которого ещё не заморожены в кеше по действующим правилам
     * (сегодня — всегда незаморожен); null — данных нет. Только чтения Diary, очередь [PhoneEvents]
     * не ждёт: можно звать с потока записи (обслуживание перед чисткой сырых данных).
     */
    internal fun firstUnfrozenDay(today: Long, zone: ZoneId): Long? {
        val device = Ledger.deviceId()
        val first = firstDay(device, today, zone) ?: return null
        return StatsMath.firstUnfrozenDay(first, today, frozenDays(first, today - SETTLE_DAYS).keys)
    }

    /** Первый день с данными (дневник, ночи или события экрана), не позже [today]; null — данных нет. */
    private fun firstDay(device: String, today: Long, zone: ZoneId): Long? {
        val firstEventDay = Diary.firstPhoneEventMs(device)?.let { SleepMath.dayOf(it, zone) }
        return listOfNotNull(Diary.firstDataDay(), firstEventDay).minOrNull()?.coerceAtMost(today)
    }

    /** Замороженные дни [first]..[settledThrough] из кеша (только читаемые по действующим правилам). */
    private fun frozenDays(first: Long, settledThrough: Long): Map<Long, DayPoints> {
        val cached = HashMap<Long, DayPoints>()
        if (first > settledThrough) return cached
        for ((key, value) in Diary.metaWithPrefix(CACHE_PREFIX)) {
            val day = key.removePrefix(CACHE_PREFIX).toLongOrNull() ?: continue
            if (day < first || day > settledThrough) continue
            StatsMath.decode(day, value)?.let { cached[day] = it }
        }
        return cached
    }

    /**
     * Замороженные дни [first]..[settledThrough] по прежним правилам (любая версия ниже действующей): из них
     * нужна только дисциплина, сила и выносливость считаются заново из дневника и шагов, которые не удаляются.
     */
    private fun oldDiscipline(first: Long, settledThrough: Long): Map<Long, DayPoints> {
        val old = HashMap<Long, DayPoints>()
        if (first > settledThrough) return old
        for ((key, value) in Diary.metaWithPrefix(CACHE_PREFIX)) {
            val day = key.removePrefix(CACHE_PREFIX).toLongOrNull() ?: continue
            if (day < first || day > settledThrough) continue
            (StatsMath.RULE_VERSION - 1 downTo 1).firstNotNullOfOrNull { StatsMath.decode(day, value, ruleVersion = it) }
                ?.let { old[day] = it }
        }
        return old
    }

    /** Итог по базе поколения [gen]; null — базу подменили до записи кеша (расчёт надо повторить). */
    private fun compute(now: Long, today: Long, zone: ZoneId, legacyXp: Long, gen: Long): StatsSummary? {
        val device = Ledger.deviceId()
        val firstEventMs = Diary.firstPhoneEventMs(device)
        val first = firstDay(device, today, zone) ?: return StatsSummary.empty(today, legacyXp)

        val settledThrough = today - SETTLE_DAYS
        val cached = frozenDays(first, settledThrough)
        // Пересчитываются все дни без кеша и все незамороженные; диапазон — от самого раннего из них.
        val from = StatsMath.firstUnfrozenDay(first, today, cached.keys).coerceAtMost(today)
        val fresh = computeRange(from, today, now, zone, device, firstEventMs)
        // Дисциплину из кеша прежних правил переносим как есть: её исходные события могли быть уже удалены.
        val range = Range(StatsMath.keepOldDiscipline(fresh.days, oldDiscipline(first, settledThrough)), fresh.complete)

        val toFreeze = StatsMath.daysToFreeze(range.days, cached.keys, settledThrough, range.complete)
        if (toFreeze.isNotEmpty()) {
            val sameDb = try {
                Diary.write {
                    // Поколение меняется только под блокировкой Diary: проверка и запись — атомарно.
                    if (Diary.generation != gen) return@write false
                    for ((day, p) in toFreeze) Diary.metaPut(CACHE_PREFIX + day, StatsMath.encode(p))
                    true
                }
            } catch (e: Exception) {
                // Без кеша очки всё равно посчитаны; заморозка повторится при следующем расчёте.
                Log.e(TAG, "Не удалось записать кеш характеристик", e)
                true
            }
            if (!sameDb) return null
        }

        val days = HashMap<Long, DayPoints>(cached)
        days.putAll(range.days.filterKeys { it !in cached })
        // Штрафы за простой не хранятся в кеше: они зависят от соседних дней и считаются поверх очков.
        val since = penaltySince(today, gen) ?: return null
        val penalized = StatsMath.withPenalties(days, since, today)
        val totals = StatsMath.totals(penalized.days)
        return StatsSummary(
            today,
            totals.getValue(Stat.STRENGTH),
            totals.getValue(Stat.ENDURANCE),
            totals.getValue(Stat.DISCIPLINE),
            penalized.days,
            legacyXp,
            penalized.risk,
        )
    }

    /**
     * С какого дня действуют штрафы за простой: день первого расчёта с ними (в meta). Прошлые дни,
     * когда правил ещё не было, не штрафуются. null — базу подменили до записи (расчёт надо повторить).
     */
    private fun penaltySince(today: Long, gen: Long): Long? {
        Diary.metaGet(PENALTY_SINCE_KEY)?.toLongOrNull()?.let { return it }
        return try {
            Diary.write {
                if (Diary.generation != gen) return@write null
                Diary.metaGet(PENALTY_SINCE_KEY)?.toLongOrNull()
                    ?: today.also { Diary.metaPut(PENALTY_SINCE_KEY, it.toString()) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать начало штрафов", e)
            today
        }
    }

    /** Очки дней; [complete] = false — ночи прочитать не удалось, дни предварительные. */
    private class Range(val days: Map<Long, DayPoints>, val complete: Boolean)

    /** Очки дней [from]..[to], данные читаются диапазонами (а не по дню). */
    private fun computeRange(
        from: Long, to: Long, now: Long, zone: ZoneId, device: String, firstEventMs: Long?,
    ): Range {
        val sets = Diary.setsInRange(from, to).groupBy { it.day }
        val runs = Diary.runsInRange(from, to).groupBy { it.day }
        val activity = Diary.activityDays(from, to).associateBy { it.day }
        val spent = Ledger.spendByDay(from, to)
        // Ночь дня D начинается вечером D−1: события блокировки берутся с запасом в сутки.
        val blocks = Diary.blockEvents(from - 1, to)
        // null — ошибка или тайм-аут (а не «ночей нет»): считаем без ночей, но ничего не замораживаем.
        val nightsOrNull = SleepRepository.nightsOrNull(from, to)
        val nights = nightsOrNull.orEmpty()
        val complete = nightsOrNull != null
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
                StatsMath.strengthInput(daySets, dayRuns, activity[day]),
                StatsMath.enduranceInput(activity[day], dayRuns),
                discipline,
                provisional = !complete || (day == to && !discipline.dayEnded),
            )
        }
        return Range(out, complete)
    }
}
