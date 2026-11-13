package com.nextlevellife.app.stats

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.BlockEvent
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.sleep.NightMode
import com.nextlevellife.app.sleep.ObservationGap
import com.nextlevellife.app.sleep.SleepMath
import com.nextlevellife.app.sleep.SleepNight
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Характеристики персонажа — очки ПРИВЫЧЕК, а не оценка физических способностей.
 * Сила — повторения и планка, Выносливость — ходьба, бег и время комплексов,
 * Дисциплина — ночь без телефона, расход в пределах лимита и ночь без попыток открыть заблокированное.
 */
enum class Stat(val title: String) {
    STRENGTH("Сила"),
    ENDURANCE("Выносливость"),
    DISCIPLINE("Дисциплина"),
}

/**
 * Откуда очки. [PointSource.amount] в своих единицах: REPS — повторения, PLANK — секунды,
 * COMPLEX_DONE — число завершённых комплексов, WALK — шаги ходьбы (без шагов бега), RUN — метры,
 * COMPLEX_TIME — мс завершённых комплексов, PHONE_FREE_NIGHT — 1, LIMIT_KEPT — процент лимита,
 * NIGHT_NO_BLOCKS — 0 (блокировок не было).
 */
enum class SourceKind(val stat: Stat) {
    REPS(Stat.STRENGTH),
    PLANK(Stat.STRENGTH),
    COMPLEX_DONE(Stat.STRENGTH),
    WALK(Stat.ENDURANCE),
    RUN(Stat.ENDURANCE),
    COMPLEX_TIME(Stat.ENDURANCE),
    PHONE_FREE_NIGHT(Stat.DISCIPLINE),
    LIMIT_KEPT(Stat.DISCIPLINE),
    NIGHT_NO_BLOCKS(Stat.DISCIPLINE),

    /** Штраф за простой: [PointSource.amount] — какой по счёту день подряд без очков, очки отрицательные. */
    STRENGTH_IDLE(Stat.STRENGTH),
    ENDURANCE_IDLE(Stat.ENDURANCE),
    DISCIPLINE_IDLE(Stat.DISCIPLINE),
}

/** Один источник очков дня: [raw] — сколько дал бы без потолков, [points] — сколько засчитано. */
data class PointSource(val kind: SourceKind, val amount: Long, val points: Int, val raw: Int = points) {
    /** Срезан потолком. */
    val capped: Boolean get() = points < raw
}

/** Очки одной характеристики за день и их источники (в порядке правил). */
data class StatDay(val points: Int, val sources: List<PointSource>)

/**
 * Очки дня по характеристикам. [provisional] — день ещё идёт (сегодня): очки могут измениться,
 * дисциплина за сутки ещё не начислена.
 */
data class DayPoints(
    val day: Long,
    val strength: Int,
    val endurance: Int,
    val discipline: Int,
    val sources: List<PointSource>,
    val provisional: Boolean = false,
) {
    fun of(stat: Stat): Int = when (stat) {
        Stat.STRENGTH -> strength
        Stat.ENDURANCE -> endurance
        Stat.DISCIPLINE -> discipline
    }

    val total: Int get() = strength + endurance + discipline

    fun sourcesOf(stat: Stat): List<PointSource> = sources.filter { it.kind.stat == stat }

    companion object {
        fun empty(day: Long, provisional: Boolean = false) = DayPoints(day, 0, 0, 0, emptyList(), provisional)
    }
}

/**
 * Сила за день: [reps] — все повторения подходов-повторений (комплексы — по их подходам), [heldMs] — засчитанная планка,
 * [bestSet] — самый большой подход за день (повторения считаются, только если он полный — от [StatsMath.FULL_SET_REPS]).
 */
data class StrengthInput(
    val reps: Int,
    val heldMs: Long,
    val completedComplexes: Int,
    val bestSet: Int = Int.MAX_VALUE,
)

/**
 * Выносливость за день: [steps] — все шаги (null — данных нет, это не ноль), [runSteps] — шаги пробежек,
 * [runMeters] — метры пробежек, [complexMs] — длительность завершённых комплексов.
 */
data class EnduranceInput(val steps: Long?, val runSteps: Long, val runMeters: Long, val complexMs: Long)

/**
 * Дисциплина за день.
 * Ночь — с днём пробуждения = этот день: [nightKnown] — расчёт ночи есть, [phoneFree] — «Ночь без телефона»,
 * [nightModeOn] — ночной режим действовал этой ночью, [nightEnded] — окно ночного режима уже прошло,
 * [nightObserved] — служба наблюдала всё окно без пропусков, [nightBlocks] — блокировок с причиной NIGHT за окно.
 * Сутки: [dayEnded] — сутки кончились, [limitMin] — дневной лимит (0 — выключен), [spentMs] — расход за сутки,
 * [dayObserved] — служба наблюдала все сутки без пропусков.
 */
data class DisciplineInput(
    val dayEnded: Boolean,
    val nightKnown: Boolean = false,
    val phoneFree: Boolean = false,
    val nightModeOn: Boolean = false,
    val nightEnded: Boolean = false,
    val nightObserved: Boolean = false,
    val nightBlocks: Int = 0,
    val limitMin: Int = 0,
    val spentMs: Long = 0L,
    val dayObserved: Boolean = false,
)

/** Уровень и путь до следующего: [from] — очки начала уровня, [to] — следующего. */
data class LevelProgress(val level: Int, val points: Long, val from: Long, val to: Long) {
    val left: Long get() = (to - points).coerceAtLeast(0L)
    val fraction: Float get() = ((points - from).toFloat() / (to - from).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

/**
 * Чистая логика характеристик, без Android: её проверяют юнит-тесты.
 * Очки считаются по дням из данных-источников (дневник, шаги, ночи, расход, блокировки);
 * отдельного журнала очков нет. Числа — стартовые, их можно настраивать (тогда поднять [RULE_VERSION]).
 */
object StatsMath {
    /** Версия правил: при смене кеш итогов дней ([StatsRepository]) пересчитывается. */
    /**
     * 2 — пороги: подход от 10, планка от минуты, ходьба от 5 000 шагов.
     * 3 — источник ходьбы и при 0 шагов (штраф выносливости отличает «мало шагов» от «нет данных»).
     * Правила дисциплины с версии 1 не менялись: её очки из кеша прежних версий переносятся ([keepOldDiscipline]).
     */
    const val RULE_VERSION = 3

    /** Потолок очков одной характеристики за день. */
    const val DAY_CAP = 10

    // Сила
    const val REPS_PER_POINT = 10
    /** Повторения дня засчитываются, только если был хотя бы один полный подход (или завершён комплекс). */
    const val FULL_SET_REPS = 10
    const val PLANK_SECONDS_PER_POINT = 30
    /** Планка засчитывается от минуты за день суммарно. */
    const val PLANK_MIN_SECONDS = 60L
    const val STRENGTH_WORK_CAP = 8
    const val COMPLEX_BONUS = 2

    // Выносливость
    /** Ходьба: первое очко — за [WALK_FIRST_POINT_STEPS], дальше — по очку за каждые [WALK_STEPS_PER_POINT]. */
    const val WALK_FIRST_POINT_STEPS = 5000L
    const val WALK_STEPS_PER_POINT = 2000L
    const val RUN_METERS_PER_POINT = 1000L
    const val COMPLEX_MS_PER_POINT = 5 * 60_000L
    const val COMPLEX_TIME_CAP = 4

    // Дисциплина
    const val PHONE_FREE_POINTS = 4
    const val LIMIT_POINTS = 3
    const val NIGHT_NO_BLOCKS_POINTS = 3

    /** Расход не больше этой доли лимита (в процентах) — очки за лимит. */
    const val LIMIT_SHARE_PERCENT = 80

    /** Причина блокировки ночью (BlockReason.NIGHT). */
    const val NIGHT_REASON = "NIGHT"

    // ---------- Формулы ----------

    /**
     * Сила: min(8, ⌊R/10⌋ + ⌊H/30⌋) + 2 за завершённые комплексы; не больше 10.
     * Повторения — только если за день был полный подход (от 10) или завершён комплекс; планка — от минуты за день.
     */
    fun strength(input: StrengthInput): StatDay {
        val sources = ArrayList<PointSource>()
        var left = STRENGTH_WORK_CAP
        if (input.reps > 0) {
            val full = input.bestSet >= FULL_SET_REPS || input.completedComplexes > 0
            val raw = if (full) input.reps / REPS_PER_POINT else 0
            val pts = minOf(raw, left)
            left -= pts
            sources += PointSource(SourceKind.REPS, input.reps.toLong(), pts, raw)
        }
        val heldSec = input.heldMs.coerceAtLeast(0L) / 1000
        if (heldSec > 0) {
            val raw = if (heldSec < PLANK_MIN_SECONDS) 0 else (heldSec / PLANK_SECONDS_PER_POINT).toInt()
            val pts = minOf(raw, left)
            left -= pts
            sources += PointSource(SourceKind.PLANK, heldSec, pts, raw)
        }
        if (input.completedComplexes > 0) {
            sources += PointSource(SourceKind.COMPLEX_DONE, input.completedComplexes.toLong(), COMPLEX_BONUS)
        }
        return StatDay(sources.sumOf { it.points }.coerceAtMost(DAY_CAP), sources)
    }

    /**
     * Выносливость: min(10, ходьба + ⌊M/1000⌋ + min(4, ⌊C/5 мин⌋)), ходьба — [walkPoints] от W = шаги − шаги бега.
     * Шаги неизвестны — ходьбы нет (это не «подтверждённый ноль», но и очков за неё нет).
     */
    fun endurance(input: EnduranceInput): StatDay {
        val sources = ArrayList<PointSource>()
        var left = DAY_CAP
        fun add(kind: SourceKind, amount: Long, raw: Int, ownCap: Int = Int.MAX_VALUE) {
            val pts = minOf(raw, ownCap, left)
            left -= pts
            sources += PointSource(kind, amount, pts, raw)
        }
        // Шаги известны (даже 0) — источник ходьбы есть: по нему штраф за простой отличает «мало шагов» от «нет данных».
        if (input.steps != null) {
            val walk = walkSteps(input.steps, input.runSteps)
            add(SourceKind.WALK, walk, walkPoints(walk))
        }
        if (input.runMeters > 0) add(SourceKind.RUN, input.runMeters, (input.runMeters / RUN_METERS_PER_POINT).toInt())
        if (input.complexMs > 0) {
            add(SourceKind.COMPLEX_TIME, input.complexMs, (input.complexMs / COMPLEX_MS_PER_POINT).toInt(), COMPLEX_TIME_CAP)
        }
        return StatDay(sources.sumOf { it.points }, sources)
    }

    /** Очки ходьбы: меньше 5 000 шагов — 0; 5 000 — 1 очко, дальше +1 за каждые 2 000. */
    fun walkPoints(walk: Long): Int =
        if (walk < WALK_FIRST_POINT_STEPS) 0 else (1 + (walk - WALK_FIRST_POINT_STEPS) / WALK_STEPS_PER_POINT).toInt()

    /** Шаги ходьбы: все шаги без шагов бега; неизвестно — 0. */
    fun walkSteps(steps: Long?, runSteps: Long): Long = ((steps ?: 0L) - runSteps.coerceAtLeast(0L)).coerceAtLeast(0L)

    /**
     * Дисциплина: +4 «Ночь без телефона»; +3 ни одной ночной блокировки (ночной режим включён, окно прошло,
     * наблюдение полное); +3 расход ≤ 80 % лимита (сутки кончились, лимит включён, наблюдение полное).
     * Данных недостаточно — 0, не штраф.
     */
    fun discipline(input: DisciplineInput): StatDay {
        val sources = ArrayList<PointSource>()
        if (input.nightKnown && input.phoneFree) {
            sources += PointSource(SourceKind.PHONE_FREE_NIGHT, 1, PHONE_FREE_POINTS)
        }
        if (input.nightKnown && input.nightModeOn && input.nightEnded && input.nightObserved && input.nightBlocks == 0) {
            sources += PointSource(SourceKind.NIGHT_NO_BLOCKS, 0, NIGHT_NO_BLOCKS_POINTS)
        }
        if (input.dayEnded && input.limitMin > 0 && input.dayObserved && limitKept(input.spentMs, input.limitMin)) {
            sources += PointSource(SourceKind.LIMIT_KEPT, limitPercent(input.spentMs, input.limitMin), LIMIT_POINTS)
        }
        return StatDay(sources.sumOf { it.points }.coerceAtMost(DAY_CAP), sources)
    }

    /** Расход не больше 80 % лимита (точно, без округления). */
    fun limitKept(spentMs: Long, limitMin: Int): Boolean =
        limitMin > 0 && spentMs.coerceAtLeast(0L) * 100 <= limitMin * 60_000L * LIMIT_SHARE_PERCENT

    /** Расход в процентах лимита, с округлением. */
    fun limitPercent(spentMs: Long, limitMin: Int): Long {
        if (limitMin <= 0) return 0
        val limitMs = limitMin * 60_000L
        return (spentMs.coerceAtLeast(0L) * 100 + limitMs / 2) / limitMs
    }

    /** Очки дня целиком. */
    fun day(day: Long, s: StrengthInput, e: EnduranceInput, d: DisciplineInput, provisional: Boolean = false): DayPoints {
        val st = strength(s)
        val en = endurance(e)
        val di = discipline(d)
        return DayPoints(day, st.points, en.points, di.points, st.sources + en.sources + di.sources, provisional)
    }

    // ---------- Уровни ----------

    /** Уровень: 1 + ⌊√(очки / 50)⌋. Уровень n начинается с 50·(n−1)² очков: 0, 50, 200, 450, 800… */
    fun levelFor(points: Long): Int = floor(sqrt(points.coerceAtLeast(0L) / 50.0)).toInt() + 1

    fun pointsForLevel(level: Int): Long = 50L * (level - 1) * (level - 1)

    fun progress(points: Long): LevelProgress {
        val level = levelFor(points)
        return LevelProgress(level, points, pointsForLevel(level), pointsForLevel(level + 1))
    }

    // ---------- Штрафы за простой ----------

    /** После первого штрафа следующий — через столько дней простоя подряд: медленно, с запасом на отдых. */
    const val IDLE_PERIOD_DAYS = 3

    /** Сколько очков снимает один штраф. */
    const val IDLE_PENALTY = 1

    /** Сила: 2 дня отдыха без штрафа, 3-й — день, когда нужно успеть потренироваться. */
    const val STRENGTH_REST_DAYS = 2

    /**
     * На какой день простоя подряд приходит первый штраф. Сила — на 4-й (2 дня отдыха, 3-й — на тренировку),
     * выносливость и дисциплина — на 3-й.
     */
    fun firstIdlePenaltyDay(stat: Stat): Int = when (stat) {
        Stat.STRENGTH -> STRENGTH_REST_DAYS + 2
        Stat.ENDURANCE, Stat.DISCIPLINE -> IDLE_PERIOD_DAYS
    }

    /**
     * Штраф за [idleDay]-й день простоя подряд: −1 в день первого штрафа ([firstIdlePenaltyDay]) и дальше
     * каждые 3 дня (сила — 4-й, 7-й, 10-й…; остальные — 3-й, 6-й, 9-й…), в остальные дни — 0.
     */
    fun idlePenalty(idleDay: Int, stat: Stat): Int {
        val first = firstIdlePenaltyDay(stat)
        return if (idleDay >= first && (idleDay - first) % IDLE_PERIOD_DAYS == 0) -IDLE_PENALTY else 0
    }

    /**
     * Простаивала ли характеристика в день [p]: true — день простоя, false — очки были, null — неизвестно
     * (сбрасывает счёт, как день с очками). Выносливость: простой — шаги известны, а очков нет
     * (меньше 5 000 шагов, без бега и комплексов); без данных о шагах (нет Health Connect) — неизвестно.
     */
    fun idle(p: DayPoints, stat: Stat): Boolean? = when {
        p.of(stat) > 0 -> false
        stat == Stat.ENDURANCE && p.sources.none { it.kind == SourceKind.WALK } -> null
        else -> true
    }

    private fun idleKind(stat: Stat): SourceKind = when (stat) {
        Stat.STRENGTH -> SourceKind.STRENGTH_IDLE
        Stat.ENDURANCE -> SourceKind.ENDURANCE_IDLE
        Stat.DISCIPLINE -> SourceKind.DISCIPLINE_IDLE
    }

    /**
     * [days] — очки дней со штрафами; [risk] — какой штраф придёт, если сегодняшний день закончится
     * без очков характеристики (0 — не придёт или уже заработано).
     */
    class Penalized(val days: Map<Long, DayPoints>, val risk: Map<Stat, Int>)

    /**
     * Штрафы за простой поверх очков дней [days]. Действуют с дня [since] (раньше этих правил не было) и только
     * на завершённые дни до [today]: сегодня штрафа нет, пока день идёт. Простой — [idle]; штраф — [idlePenalty]
     * (1 очко: сила с 4-го дня простоя подряд, остальные с 3-го, дальше каждые 3 дня). Предварительный день (данные о ночи не прочитаны) обрывает подсчёт:
     * его результат ещё неизвестен, и за него и после него штрафов в этом расчёте нет.
     */
    fun withPenalties(days: Map<Long, DayPoints>, since: Long, today: Long): Penalized {
        val out = HashMap(days)
        val idleRun = HashMap<Stat, Int>()
        var broken = false
        for (day in since until today) {
            val p = days[day] ?: DayPoints.empty(day)
            if (p.provisional) {
                broken = true
                break
            }
            var q = p
            for (stat in Stat.entries) {
                val run = if (idle(p, stat) == true) (idleRun[stat] ?: 0) + 1 else 0
                idleRun[stat] = run
                val penalty = idlePenalty(run, stat)
                if (penalty == 0) continue
                q = q.copy(
                    strength = q.strength + if (stat == Stat.STRENGTH) penalty else 0,
                    endurance = q.endurance + if (stat == Stat.ENDURANCE) penalty else 0,
                    discipline = q.discipline + if (stat == Stat.DISCIPLINE) penalty else 0,
                    sources = q.sources + PointSource(idleKind(stat), run.toLong(), penalty),
                )
            }
            if (q !== p) out[day] = q
        }
        val risk = HashMap<Stat, Int>()
        if (!broken && today >= since) {
            val t = days[today] ?: DayPoints.empty(today)
            for (stat in Stat.entries) {
                // Сегодня ещё может набраться: риск — если день так и закончится без очков. День без данных
                // о шагах счёт сбросит, поэтому риска для выносливости нет, пока шаги не пришли.
                risk[stat] = if (idle(t, stat) == true) idlePenalty((idleRun[stat] ?: 0) + 1, stat) else 0
            }
        }
        return Penalized(out, risk)
    }

    /** Очки за всё время по характеристикам: дни по порядку, штраф не опускает итог ниже нуля. */
    fun totals(days: Map<Long, DayPoints>): Map<Stat, Long> {
        val acc = HashMap<Stat, Long>()
        for (day in days.keys.sorted()) {
            val p = days.getValue(day)
            for (stat in Stat.entries) acc[stat] = ((acc[stat] ?: 0L) + p.of(stat)).coerceAtLeast(0L)
        }
        return Stat.entries.associateWith { acc[it] ?: 0L }
    }

    // ---------- Сбор входных данных дня ----------

    /**
     * Сила по записям дня: повторения — только подходы-повторения (подходы комплексов в том числе,
     * каждая строка один раз; total_reps прохождений не добавляется), планка — сумма held_ms,
     * комплексы — завершённые прохождения.
     */
    fun strengthInput(sets: List<SetEntry>, runs: List<ComplexRun>): StrengthInput {
        val repSets = sets.filter { it.measurement == Measurement.REPS }
        return StrengthInput(
            reps = repSets.sumOf { it.reps.coerceAtLeast(0) },
            heldMs = sets.filter { it.measurement == Measurement.HOLD }.sumOf { it.heldMs.coerceAtLeast(0L) },
            completedComplexes = runs.count { it.completed },
            bestSet = repSets.maxOfOrNull { it.reps } ?: 0,
        )
    }

    fun enduranceInput(activity: ActivityDay?, runs: List<ComplexRun>): EnduranceInput = EnduranceInput(
        steps = activity?.steps,
        runSteps = activity?.runSteps ?: 0L,
        runMeters = activity?.runDistanceM ?: 0L,
        complexMs = runs.filter { it.completed }.sumOf { it.durationMs.coerceAtLeast(0L) },
    )

    /**
     * Наблюдение на [fromMs, toMs) было полным: события начались не позже [fromMs] ([observedSinceMs]),
     * отрезок уже прошёл ([nowMs]) и ни один пропуск его не задевает.
     */
    fun observed(fromMs: Long, toMs: Long, observedSinceMs: Long?, gaps: List<ObservationGap>, nowMs: Long): Boolean =
        observedSinceMs != null && observedSinceMs <= fromMs && toMs <= nowMs &&
            gaps.none { it.startMs < toMs && it.endMs > fromMs }

    /**
     * Дисциплина по данным: ночь с днём пробуждения [day] ([night]; её окно ночного режима — по режиму и поясу,
     * записанным в ночи), события блокировки (любых суток — фильтр по времени), пропуски наблюдения,
     * начало наблюдения, лимит и расход. [dayStartMs]/[dayEndMs] — границы учётных суток.
     */
    fun disciplineInput(
        day: Long,
        nowMs: Long,
        dayStartMs: Long,
        dayEndMs: Long,
        night: SleepNight?,
        blockEvents: List<BlockEvent>,
        gaps: List<ObservationGap>,
        observedSinceMs: Long?,
        limitMin: Int,
        spentMs: Long,
    ): DisciplineInput {
        val modeStart = night?.nightStartMin
        val modeEnd = night?.nightEndMin
        val interval = if (night != null && modeStart != null && modeEnd != null && modeStart != modeEnd) {
            val zone = runCatching { ZoneId.of(night.zoneId) }.getOrNull()
            zone?.let { SleepMath.nightModeInterval(day, it, NightMode(modeStart, modeEnd)) }
        } else {
            null
        }
        return DisciplineInput(
            dayEnded = nowMs >= dayEndMs,
            nightKnown = night != null,
            phoneFree = night?.phoneFree == true,
            nightModeOn = interval != null,
            nightEnded = interval != null && interval.second <= nowMs,
            nightObserved = interval != null && observed(interval.first, interval.second, observedSinceMs, gaps, nowMs),
            nightBlocks = if (interval == null) 0 else blockEvents.count {
                it.reason == NIGHT_REASON && it.utcMs >= interval.first && it.utcMs < interval.second
            },
            limitMin = limitMin,
            spentMs = spentMs,
            dayObserved = observed(dayStartMs, dayEndMs, observedSinceMs, gaps, nowMs),
        )
    }

    // ---------- Кеш итогов дня ----------

    /**
     * Строка кеша итогов дня: «версия|сила|выносливость|дисциплина|источники», источники —
     * «ВИД:количество:очки:без потолка» через «;». Неверная строка или другая версия правил — null.
     */
    fun encode(p: DayPoints, ruleVersion: Int = RULE_VERSION): String =
        "$ruleVersion|${p.strength}|${p.endurance}|${p.discipline}|" +
            p.sources.joinToString(";") { "${it.kind.name}:${it.amount}:${it.points}:${it.raw}" }

    fun decode(day: Long, s: String, ruleVersion: Int = RULE_VERSION): DayPoints? = runCatching {
        val parts = s.split('|')
        if (parts.size != 5 || parts[0].toInt() != ruleVersion) return null
        val sources = if (parts[4].isEmpty()) emptyList() else parts[4].split(';').map { item ->
            val f = item.split(':')
            require(f.size == 4)
            PointSource(SourceKind.valueOf(f[0]), f[1].toLong(), f[2].toInt(), f[3].toInt())
        }
        DayPoints(day, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), sources)
    }.getOrNull()

    /**
     * Перенос дисциплины из кеша прежних правил ([old]) в свежий расчёт [fresh]: правила дисциплины не менялись,
     * а её исходные события старше 90 дней уже удалены — пересчёт обнулил бы заработанное.
     */
    fun keepOldDiscipline(fresh: Map<Long, DayPoints>, old: Map<Long, DayPoints>): Map<Long, DayPoints> {
        if (old.isEmpty()) return fresh
        val out = HashMap(fresh)
        for ((day, o) in old) {
            val p = fresh[day] ?: continue
            out[day] = p.copy(
                discipline = o.discipline,
                sources = p.sources.filter { it.kind.stat != Stat.DISCIPLINE } + o.sourcesOf(Stat.DISCIPLINE),
            )
        }
        return out
    }

    // ---------- Заморозка ----------

    /**
     * Какие дни из свежего расчёта [fresh] заморозить в кеше: не моложе [settledThrough] и ещё не в кеше
     * ([cached]). Если источники прочитаны не полностью ([sourcesComplete] = false: ошибка или тайм-аут
     * чтения ночей) — ни одного: потерянные очки не должны застыть навсегда.
     */
    fun daysToFreeze(
        fresh: Map<Long, DayPoints>, cached: Set<Long>, settledThrough: Long, sourcesComplete: Boolean,
    ): Map<Long, DayPoints> =
        if (!sourcesComplete) emptyMap() else fresh.filterKeys { it <= settledThrough && it !in cached }

    /**
     * Первый день [first]..[today], очки которого ещё не заморожены ([frozen]); всё заморожено — [today] + 1.
     * Раньше этого дня сырые события экрана для характеристик уже не нужны.
     */
    fun firstUnfrozenDay(first: Long, today: Long, frozen: Set<Long>): Long {
        var day = first
        while (day <= today && day in frozen) day++
        return day
    }
}
