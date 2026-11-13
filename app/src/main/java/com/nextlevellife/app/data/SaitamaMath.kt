package com.nextlevellife.app.data

import com.nextlevellife.app.health.HealthAvailability

/** Цель дня «Сайтамы»: три сотни повторений и бег. [exerciseId] — упражнение камеры (у бега null). */
enum class SaitamaGoal(val exerciseId: String?, val title: String) {
    PUSHUPS("pushups", "Отжимания"),
    ABS("abs", "Пресс"),
    SQUATS("squats", "Приседания"),
    RUN(null, "Бег");

    /** Веха «нарезки», которой отмечается закрытие этой цели. */
    val milestone: SaitamaMilestone get() = SaitamaMilestone.valueOf(name)

    companion object {
        /** Цели-повторения (без бега), в порядке экрана. */
        val reps: List<SaitamaGoal> = listOf(PUSHUPS, ABS, SQUATS)
    }
}

/** Веха дня для «нарезки»: закрыта цель или весь день ([DAY]). Имя пишется в meta — не переименовывать. */
enum class SaitamaMilestone { PUSHUPS, ABS, SQUATS, RUN, DAY }

/**
 * Прогресс одного учётного дня. [reps] — повторения каждой цели-повторений (сколько сделано, без потолка),
 * [runM] — метры бега за день; null — бег не подтверждён (нет данных Health Connect за день).
 */
data class SaitamaDay(val day: Long, val reps: Map<SaitamaGoal, Int>, val runM: Long?) {
    fun repsOf(goal: SaitamaGoal): Int = reps[goal] ?: 0

    val runKnown: Boolean get() = runM != null

    fun done(goal: SaitamaGoal): Boolean = when (goal) {
        SaitamaGoal.RUN -> (runM ?: 0L) >= Saitama.RUN_GOAL_M
        else -> repsOf(goal) >= Saitama.REPS_GOAL
    }

    /** Полный день: все четыре цели. */
    val full: Boolean get() = SaitamaGoal.entries.all { done(it) }

    /** Вехи, которых день уже достиг (в порядке «нарезки»). */
    val reached: Set<SaitamaMilestone>
        get() = buildSet {
            SaitamaGoal.entries.filter { done(it) }.forEach { add(it.milestone) }
            if (full) add(SaitamaMilestone.DAY)
        }
}

/** Отметка дня в полосе последних 14 дней. */
enum class SaitamaMark {
    /** Все четыре цели. */
    FULL,

    /** День закончился неполным (данные о беге были). */
    PARTIAL,

    /** День закончился неполным, данных о беге за него нет. */
    NO_RUN_DATA,

    /** Сегодня, ещё не выполнен: не пропуск. */
    TODAY,

    /** Раньше начала «Сайтамы»: в серию не идёт. */
    BEFORE_START,
}

/** Тип кадра «нарезки»: веха цели (число и подпись) или финал дня. */
enum class BeatKind { GOAL, FINAL }

/**
 * Один кадр «нарезки»: [big] — крупно (число или «ДЕНЬ ВЫПОЛНЕН»), [caption] — подпись под ним,
 * [durationMs] — сколько держится, [voice] — фраза голосом (null — только сигналы).
 */
data class MontageBeat(
    val kind: BeatKind,
    val big: String,
    val caption: String,
    val durationMs: Long,
    val voice: String?,
) {
    /** Надпись кадра целиком: «100 ОТЖИМАНИЙ!», «ДЕНЬ ВЫПОЛНЕН · СЕРИЯ 3». */
    val text: String get() = if (kind == BeatKind.FINAL) "$big · $caption" else "$big $caption"
}

/**
 * Чистая логика «Сайтамы», без Android: прогресс дня по подходам и активности, полный день, серия
 * и рекорд, новые вехи, недоплаченные бонусы, план «нарезки» и тексты. Её проверяют юнит-тесты.
 */
object SaitamaMath {
    /** Короткий акцент одной вехи. */
    const val ACCENT_MS = 1_000L

    /** Быстрая смена надписи в монтаже закрытого дня. */
    const val QUICK_MS = 650L

    /** Финал монтажа: «ДЕНЬ ВЫПОЛНЕН · СЕРИЯ N». */
    const val FINAL_MS = 2_000L

    // ---------- Прогресс дня ----------

    /**
     * Прогресс дня [day]: повторения целей — сумма подходов-повторений (measurement REPS) упражнений
     * «Сайтамы» с этим днём, в том числе подходы комплексов; планка (HOLD) не считается.
     * [runM] — метры бега дня (null — не подтверждён).
     */
    fun dayOf(day: Long, sets: List<SetEntry>, runM: Long?): SaitamaDay {
        val reps = SaitamaGoal.reps.associateWith { goal ->
            sets.filter { it.day == day && it.measurement == Measurement.REPS && it.exerciseId == goal.exerciseId }
                .sumOf { it.reps.coerceAtLeast(0) }
        }
        return SaitamaDay(day, reps, runM?.coerceAtLeast(0L))
    }

    /** Метры бега по строке активности дня: нет строки — бег не подтверждён (null). */
    fun runMeters(activity: ActivityDay?): Long? = activity?.runDistanceM

    /** Полный ли день по его подходам и строке активности (для характеристик). */
    fun isFullDay(sets: List<SetEntry>, activity: ActivityDay?): Boolean {
        // Без строки активности бег не подтверждён — день не полный.
        val a = activity ?: return false
        return dayOf(a.day, sets, runMeters(a)).full
    }

    /** Прогресс дней [from]..[to] по подходам и строкам активности этих дней. */
    fun days(from: Long, to: Long, sets: List<SetEntry>, activities: List<ActivityDay>): Map<Long, SaitamaDay> {
        if (from > to) return emptyMap()
        val setsByDay = sets.groupBy { it.day }
        val runByDay = activities.associate { it.day to it.runDistanceM }
        return (from..to).associateWith { d -> dayOf(d, setsByDay[d].orEmpty(), runByDay[d]) }
    }

    /**
     * Почему бег сегодня не подтверждён; null — подтверждён (может быть и 0 км).
     * [hasRow] — есть строка активности за день, [runsCount] — пробежек в ней, [availability] —
     * доступ к Health Connect (null — ещё не проверяли), [canReadRuns] — выдано чтение тренировок
     * и дистанции (null — не известно). Пробежка в строке есть — бег подтверждён при любом доступе.
     */
    fun runUnknownReason(
        hasRow: Boolean, runsCount: Int, availability: HealthAvailability?, canReadRuns: Boolean?,
    ): String? {
        if (hasRow && runsCount > 0) return null
        if (hasRow && availability == HealthAvailability.CONNECTED && canReadRuns == false) {
            return "Нет разрешения читать тренировки и дистанцию"
        }
        if (hasRow) return null
        return when (availability) {
            HealthAvailability.UNAVAILABLE -> "На этом телефоне нет Health Connect"
            HealthAvailability.UPDATE_REQUIRED -> "Health Connect нужно обновить"
            HealthAvailability.NOT_CONNECTED -> "Health Connect не подключён"
            HealthAvailability.CONNECTED ->
                if (canReadRuns == false) "Нет разрешения читать тренировки и дистанцию"
                else "За сегодня ещё нет данных из Health Connect"
            null -> "Данные о беге ещё не прочитаны"
        }
    }

    // ---------- Серия ----------

    /**
     * Серия: полные дни подряд ([fullDays]) не раньше [since]. Если сегодня ([today]) уже полный — он в серии;
     * если нет — серия считается до вчера (незавершённый сегодняшний день не пропуск).
     */
    fun streak(fullDays: Set<Long>, since: Long, today: Long): Int {
        var day = if (today in fullDays) today else today - 1
        var n = 0
        while (day >= since && day in fullDays) {
            n++
            day--
        }
        return n
    }

    /** Рекорд серии: самая длинная цепочка полных дней в [since]..[today]. */
    fun record(fullDays: Set<Long>, since: Long, today: Long): Int {
        var best = 0
        var run = 0
        for (day in since..today) {
            if (day in fullDays) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /** Отметка дня [day] в полосе: [p] — его прогресс (null — данных нет). */
    fun mark(day: Long, p: SaitamaDay?, since: Long, today: Long): SaitamaMark = when {
        p != null && p.full -> SaitamaMark.FULL
        day == today -> SaitamaMark.TODAY
        day < since -> SaitamaMark.BEFORE_START
        p == null || !p.runKnown -> SaitamaMark.NO_RUN_DATA
        else -> SaitamaMark.PARTIAL
    }

    // ---------- Бонус ----------

    /**
     * Дни, за которые положен бонус: полные, не раньше [since] и в последних [window] днях по [today]
     * включительно. Оплачен ли уже — решает ключ операции журнала ([Saitama.bonusKey]).
     */
    fun bonusDays(days: Map<Long, SaitamaDay>, since: Long, today: Long, window: Int = Saitama.CHECK_DAYS): List<Long> {
        val from = maxOf(since, today - window + 1)
        return days.values.filter { it.full && it.day in from..today }.map { it.day }.sorted()
    }

    // ---------- Вехи и «нарезка» ----------

    /** Новые вехи: достигнутые и ещё не показанные, в порядке «нарезки». */
    fun newMilestones(reached: Set<SaitamaMilestone>, shown: Set<SaitamaMilestone>): List<SaitamaMilestone> =
        SaitamaMilestone.entries.filter { it in reached && it !in shown }

    /** Показанные вехи для meta: имена через запятую. */
    fun encodeShown(shown: Set<SaitamaMilestone>): String =
        SaitamaMilestone.entries.filter { it in shown }.joinToString(",") { it.name }

    /** Разбор строки meta; неизвестные имена пропускаются. */
    fun decodeShown(s: String?): Set<SaitamaMilestone> =
        s.orEmpty().split(',').mapNotNull { name -> SaitamaMilestone.entries.firstOrNull { it.name == name.trim() } }.toSet()

    /**
     * План «нарезки» для новых вех [milestones]. Если среди них закрытый день — полный монтаж
     * ([dayMontage]); иначе — короткие акценты по одному на веху, одной цепочкой.
     */
    fun montage(milestones: List<SaitamaMilestone>, streak: Int): List<MontageBeat> = when {
        milestones.isEmpty() -> emptyList()
        SaitamaMilestone.DAY in milestones -> dayMontage(streak)
        else -> milestones.mapNotNull { m -> goalOf(m)?.let { accent(it) } }
    }

    /** Повтор «нарезки» дня по кнопке: закрыт день — полный монтаж, иначе акценты достигнутых вех. */
    fun replay(reached: Set<SaitamaMilestone>, streak: Int): List<MontageBeat> =
        montage(SaitamaMilestone.entries.filter { it in reached }, streak)

    /** Монтаж закрытого дня: четыре быстрые надписи и финал «ДЕНЬ ВЫПОЛНЕН · СЕРИЯ N» (4,6 с). */
    fun dayMontage(streak: Int): List<MontageBeat> =
        SaitamaGoal.entries.map { goalBeat(it, QUICK_MS, voice = null) } +
            MontageBeat(
                BeatKind.FINAL, "ДЕНЬ ВЫПОЛНЕН", "СЕРИЯ $streak", FINAL_MS,
                voice = "День выполнен! Серия $streak.",
            )

    /** Короткий акцент одной цели: надпись и фраза голосом. */
    fun accent(goal: SaitamaGoal): MontageBeat = goalBeat(goal, ACCENT_MS, voice(goal))

    private fun goalBeat(goal: SaitamaGoal, durationMs: Long, voice: String?): MontageBeat = when (goal) {
        SaitamaGoal.PUSHUPS -> MontageBeat(BeatKind.GOAL, "100", "ОТЖИМАНИЙ!", durationMs, voice)
        SaitamaGoal.ABS -> MontageBeat(BeatKind.GOAL, "100", "НА ПРЕСС!", durationMs, voice)
        SaitamaGoal.SQUATS -> MontageBeat(BeatKind.GOAL, "100", "ПРИСЕДАНИЙ!", durationMs, voice)
        SaitamaGoal.RUN -> MontageBeat(BeatKind.GOAL, "10", "КМ!", durationMs, voice)
    }

    private fun voice(goal: SaitamaGoal): String = when (goal) {
        SaitamaGoal.PUSHUPS -> "Сто отжиманий!"
        SaitamaGoal.ABS -> "Сто на пресс!"
        SaitamaGoal.SQUATS -> "Сто приседаний!"
        SaitamaGoal.RUN -> "Десять километров!"
    }

    private fun goalOf(m: SaitamaMilestone): SaitamaGoal? = SaitamaGoal.entries.firstOrNull { it.name == m.name }

    // ---------- Тексты ----------

    /** «63/100» у повторений; у бега — «4,2 / 10 км» (null — бег не подтверждён). */
    fun valueText(p: SaitamaDay, goal: SaitamaGoal): String? = when (goal) {
        SaitamaGoal.RUN -> p.runM?.let { "${km(it)} / ${Saitama.RUN_GOAL_M / 1000} км" }
        else -> "${p.repsOf(goal)}/${Saitama.REPS_GOAL}"
    }

    /** Доля выполнения цели для полосы: 0..1. */
    fun fraction(p: SaitamaDay, goal: SaitamaGoal): Float = when (goal) {
        SaitamaGoal.RUN -> ((p.runM ?: 0L).toFloat() / Saitama.RUN_GOAL_M).coerceIn(0f, 1f)
        else -> (p.repsOf(goal).toFloat() / Saitama.REPS_GOAL).coerceIn(0f, 1f)
    }

    /**
     * «Сегодня осталось: 37 отжиманий, 100 на пресс, 5,8 км бега»; бег не подтверждён — «10 км бега
     * (бег не подтверждён)». Всё сделано — null.
     */
    fun remainingText(p: SaitamaDay): String? {
        val parts = ArrayList<String>()
        for (goal in SaitamaGoal.reps) {
            val left = Saitama.REPS_GOAL - p.repsOf(goal)
            if (left <= 0) continue
            parts += when (goal) {
                SaitamaGoal.PUSHUPS -> "$left ${plural(left, "отжимание", "отжимания", "отжиманий")}"
                SaitamaGoal.ABS -> "$left на пресс"
                else -> "$left ${plural(left, "приседание", "приседания", "приседаний")}"
            }
        }
        if (!p.done(SaitamaGoal.RUN)) {
            val leftM = Saitama.RUN_GOAL_M - (p.runM ?: 0L)
            parts += "${km(leftM, roundUp = true)} км бега" + if (p.runM == null) " (бег не подтверждён)" else ""
        }
        return if (parts.isEmpty()) null else "Сегодня осталось: " + parts.joinToString(", ")
    }

    /** Строка карточки на главной: «Сегодня 63 · 0 · 100 · 4,2 км»; бег не подтверждён — «· ? км». */
    fun cardLine(p: SaitamaDay): String =
        "Сегодня " + SaitamaGoal.reps.joinToString(" · ") { p.repsOf(it).toString() } +
            " · " + (p.runM?.let { km(it) } ?: "?") + " км"

    /** «🔥 Серия 3 дня». */
    fun streakText(n: Int): String = "🔥 Серия $n ${daysWord(n)}"

    /** «день», «дня», «дней». */
    fun daysWord(n: Int): String = plural(n, "день", "дня", "дней")

    /**
     * Километры с одной цифрой после запятой: «4,2», «10». Сделанное округляется вниз (9,96 км не выглядит
     * как 10), остаток — вверх ([roundUp]): «осталось 0,1 км», а не «0 км».
     */
    fun km(meters: Long, roundUp: Boolean = false): String {
        val m = meters.coerceAtLeast(0L)
        val tenths = if (roundUp) (m + 99) / 100 else m / 100
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) "$whole" else "$whole,$frac"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
