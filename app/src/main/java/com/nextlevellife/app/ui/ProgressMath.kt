package com.nextlevellife.app.ui

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.DiaryMath
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.health.ActivityMath
import com.nextlevellife.app.sleep.NightStatus
import com.nextlevellife.app.sleep.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Чистая логика экрана «Прогресс», без Android и Compose: её проверяют юнит-тесты.
 * Дни — номера дней от эпохи (`LocalDate.toEpochDay()`), как в дневнике.
 * Месяц — индекс `год * 12 + (месяц − 1)`: одно число легко хранить в rememberSaveable.
 */
internal object ProgressMath {

    // ---------- Месяцы и календарь ----------

    private val monthsNominative = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )
    private val monthsGenitive = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val monthsShort = listOf(
        "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
    )
    private val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    /** Подписи столбцов календаря: неделя с понедельника. */
    val weekdayHeaders = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    fun monthIndex(date: LocalDate): Int = date.year * 12 + (date.monthValue - 1)

    fun monthIndexOfDay(day: Long): Int = monthIndex(LocalDate.ofEpochDay(day))

    fun firstOfMonth(monthIndex: Int): LocalDate =
        LocalDate.of(Math.floorDiv(monthIndex, 12), Math.floorMod(monthIndex, 12) + 1, 1)

    fun firstDayOfMonth(monthIndex: Int): Long = firstOfMonth(monthIndex).toEpochDay()

    fun lastDayOfMonth(monthIndex: Int): Long {
        val first = firstOfMonth(monthIndex)
        return first.plusDays(first.lengthOfMonth() - 1L).toEpochDay()
    }

    /** Листать вперёд можно только до месяца, в котором сегодня. */
    fun canGoNext(monthIndex: Int, today: Long): Boolean = monthIndex < monthIndexOfDay(today)

    /** Следующий месяц, но не дальше текущего. */
    fun nextMonth(monthIndex: Int, today: Long): Int =
        if (canGoNext(monthIndex, today)) monthIndex + 1 else monthIndex

    /**
     * Ячейки сетки месяца по неделям с понедельника: номер дня или null для пустых клеток
     * до первого и после последнего числа. Размер всегда кратен 7.
     */
    fun monthGrid(monthIndex: Int): List<Long?> {
        val first = firstOfMonth(monthIndex)
        val lead = first.dayOfWeek.value - 1 // Пн = 1 → 0 пустых клеток
        val length = first.lengthOfMonth()
        val start = first.toEpochDay()
        val cells = ArrayList<Long?>(42)
        repeat(lead) { cells.add(null) }
        for (i in 0 until length) cells.add(start + i)
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    fun weeksInMonth(monthIndex: Int): Int = monthGrid(monthIndex).size / 7

    // ---------- Подписи ----------

    /** «Ноябрь 2026» — заголовок календаря, именительный падеж. */
    fun monthTitle(monthIndex: Int): String {
        val first = firstOfMonth(monthIndex)
        return "${monthsNominative[first.monthValue - 1]} ${first.year}"
    }

    /** «12 ноября, четверг»; год добавляется, только если он не текущий. */
    fun dayTitle(day: Long, today: Long): String {
        val d = LocalDate.ofEpochDay(day)
        val year = if (d.year != LocalDate.ofEpochDay(today).year) " ${d.year}" else ""
        return "${d.dayOfMonth} ${monthsGenitive[d.monthValue - 1]}$year, ${weekdays[d.dayOfWeek.value - 1]}"
    }

    /** «12 ноября»; год добавляется, только если он не текущий: «12 ноября 2025». */
    fun dateLabel(day: Long, today: Long): String {
        val d = LocalDate.ofEpochDay(day)
        val year = if (d.year != LocalDate.ofEpochDay(today).year) " ${d.year}" else ""
        return "${d.dayOfMonth} ${monthsGenitive[d.monthValue - 1]}$year"
    }

    /** «12 ноя». */
    fun shortDate(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        return "${d.dayOfMonth} ${monthsShort[d.monthValue - 1]}"
    }

    fun dayOfMonth(day: Long): Int = LocalDate.ofEpochDay(day).dayOfMonth

    /** «21:05» по местному времени. */
    fun clock(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    fun setsWord(n: Int) = pluralRu(n, "подход", "подхода", "подходов")
    fun repsWord(n: Int) = pluralRu(n, "повторение", "повторения", "повторений")
    fun daysWord(n: Int) = pluralRu(n, "день", "дня", "дней")
    fun roundsWord(n: Int) = pluralRu(n, "круг", "круга", "кругов")

    /** «🔥 5 дней подряд». */
    fun streakLabel(n: Int) = "🔥 $n ${daysWord(n)} подряд"

    /**
     * Заработанное время: до часа — минуты с одной десятой («7,5 мин», «22 мин»),
     * от часа — часы и минуты через [formatShort] («2 ч 30 мин»).
     */
    fun formatEarned(ms: Long): String {
        if (ms <= 0) return "0 мин"
        val tenths = (ms + 3_000) / 6_000 // десятые доли минуты с округлением
        if (tenths >= 600) return formatShort(ms)
        if (tenths == 0L) return formatShort(ms) // меньше 3 секунд: «2 сек»
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) "$whole мин" else "$whole,$frac мин"
    }

    /**
     * «3 подхода · 35 повторений · +22 мин»; с планкой — «4 подхода · 35 повторений · 2:40 планки · +34 мин»;
     * только планка — «1 подход · 1:20 планки · +6,7 мин». Секунды с повторениями не складываются.
     */
    fun summaryLine(sets: Int, reps: Int, earnedMs: Long, heldMs: Long = 0L): String = buildList {
        add("$sets ${setsWord(sets)}")
        if (reps > 0 || heldMs <= 0L) add("$reps ${repsWord(reps)}")
        if (heldMs > 0L) add("${formatHold(heldMs)} планки")
        add("+${formatEarned(earnedMs)}")
    }.joinToString(" · ")

    /**
     * Время удержания «м:сс» по целым секундам: «0:45», «1:20», «12:30»; от часа — «1:05:00».
     * Отрицательное — как ноль.
     */
    fun formatHold(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Показатель подхода или итога: «35 повт.» или у удержания «1:20». */
    private fun amount(measurement: Measurement, reps: Int, heldMs: Long): String =
        if (measurement == Measurement.HOLD) formatHold(heldMs) else "$reps повт."

    /** «Мах гирей 24 кг» или «Отжимания». */
    fun exerciseLabel(name: String, weightKg: Int?): String =
        if (weightKg != null) "$name $weightKg кг" else name

    /** «Отжимания — 2 подхода, 35 повт.»; «Планка — 2 подхода, 2:40». */
    fun totalLine(t: ExerciseTotal): String =
        "${exerciseLabel(t.exerciseName, t.weightKg)} — ${t.sets} ${setsWord(t.sets)}, ${amount(t.measurement, t.reps, t.heldMs)}"

    /** «Отжимания — 20 повт.»; «Планка — 2:40» — короткая строка для карточки «Сегодня». */
    fun totalRepsLine(t: ExerciseTotal): String =
        "${exerciseLabel(t.exerciseName, t.weightKg)} — ${amount(t.measurement, t.reps, t.heldMs)}"

    /** «21:00 · Отжимания · 15 повт. · +7,5 мин»; «21:00 · Планка · 1:20 · +6,7 мин». */
    fun setLine(s: SetEntry, zone: ZoneId = ZoneId.systemDefault()): String =
        "${clock(s.timeMs, zone)} · ${exerciseLabel(s.exerciseName, s.weightKg)} · " +
            "${amount(s.measurement, s.reps, s.heldMs)} · +${formatEarned(s.earnedMs)}"

    // ---------- Рекорды упражнений ----------

    /** «Лучший подход: 15 повт. · 24 кг», «Лучший подход: 1:20», не было отдельных подходов — «Лучший подход: —». */
    fun recordBestLine(r: ExerciseRecord): String {
        if (r.measurement == Measurement.HOLD) return "Лучший подход: ${r.bestHeldMs?.let { formatHold(it) } ?: "—"}"
        val best = r.bestReps
        return when {
            best == null -> "Лучший подход: —"
            r.bestWeightKg != null -> "Лучший подход: $best повт. · ${r.bestWeightKg} кг"
            else -> "Лучший подход: $best повт."
        }
    }

    /** Итог «всего» справа в строке рекорда: число и подпись под ним («350» / «повторений всего», «12:30» / «всего»). */
    fun recordTotal(r: ExerciseRecord): Pair<String, String> =
        if (r.measurement == Measurement.HOLD) {
            formatHold(r.totalHeldMs) to "всего"
        } else {
            "${r.totalReps}" to "${repsWord((r.totalReps % 100).toInt())} всего"
        }

    /** Одной строкой: «Планка — лучший подход 1:20, всего 12:30», «Отжимания — лучший подход 30 повт., всего 350 повт.». */
    fun recordLine(r: ExerciseRecord): String {
        val (best, total) = if (r.measurement == Measurement.HOLD) {
            (r.bestHeldMs?.let { formatHold(it) } ?: "—") to formatHold(r.totalHeldMs)
        } else {
            val w = r.bestWeightKg?.let { " · $it кг" } ?: ""
            (r.bestReps?.let { "$it повт.$w" } ?: "—") to "${r.totalReps} повт."
        }
        return "${r.exerciseName} — лучший подход $best, всего $total"
    }

    /** «Минотавр 16 кг — 24:35», «Синди — 12 кругов + 7 повт.», незавершённый — с пометкой. */
    fun complexLine(r: ComplexRun): String {
        val base = when (r.complexId) {
            Minotaur.ID -> "${exerciseLabel("Минотавр", r.weightKg)} — ${formatDuration(r.durationMs)}"
            Cindy.ID -> {
                val rounds = r.rounds
                if (rounds != null) "Синди — ${cindyResult(rounds, r.extraReps ?: 0)}"
                else "Синди — ${r.totalReps} повт."
            }
            else -> "${exerciseLabel("Комплекс", r.weightKg)} — ${r.totalReps} повт."
        }
        return if (r.completed) base else "$base · не завершён"
    }

    /** «12 кругов + 7 повт.». */
    fun cindyResult(rounds: Int, extra: Int): String = "$rounds ${roundsWord(rounds)} + $extra повт."

    // ---------- Строки «название слева, показатели справа» ----------

    /** Строка таблицы: [label] слева, [value] справа табличными цифрами. */
    data class Cells(val label: String, val value: String)

    /** «Отжимания» | «2 подхода · 35 повт.»; «Планка» | «2 подхода · 2:40». */
    fun totalCells(t: ExerciseTotal): Cells =
        Cells(
            exerciseLabel(t.exerciseName, t.weightKg),
            "${t.sets} ${setsWord(t.sets)} · ${amount(t.measurement, t.reps, t.heldMs)}",
        )

    /** «21:00  Отжимания» | «15 повт. · +7,5 мин»; «21:00  Планка» | «1:20 · +6,7 мин». */
    fun setCells(s: SetEntry, zone: ZoneId = ZoneId.systemDefault()): Cells =
        Cells(
            "${clock(s.timeMs, zone)}  ${exerciseLabel(s.exerciseName, s.weightKg)}",
            "${amount(s.measurement, s.reps, s.heldMs)} · +${formatEarned(s.earnedMs)}",
        )

    /** «Минотавр 16 кг» | «24:35»; незавершённый — «… · не завершён» в названии. */
    fun complexCells(r: ComplexRun): Cells {
        val (label, value) = when (r.complexId) {
            Minotaur.ID -> exerciseLabel("Минотавр", r.weightKg) to formatDuration(r.durationMs)
            Cindy.ID -> {
                val rounds = r.rounds
                "Синди" to (if (rounds != null) cindyResult(rounds, r.extraReps ?: 0) else "${r.totalReps} повт.")
            }
            else -> exerciseLabel("Комплекс", r.weightKg) to "${r.totalReps} повт."
        }
        return Cells(if (r.completed) label else "$label · не завершён", value)
    }

    /** История упражнения: «12 ноя · 24 кг» | «2 подхода · 35 повт.»; у планки — «2 подхода · 2:40». */
    fun historyCells(day: Long, t: ExerciseTotal): Cells =
        Cells(
            shortDate(day) + (t.weightKg?.let { " · $it кг" } ?: ""),
            "${t.sets} ${setsWord(t.sets)} · ${amount(t.measurement, t.reps, t.heldMs)}",
        )

    // ---------- Карточка «Сегодня» на главной ----------

    /** Сколько строк активности показывать на карточке «Сегодня». */
    const val TODAY_MAX_LINES = 2

    /**
     * Активности дня одной строкой каждая: сначала комплексы («Минотавр 16 кг — 24:35»),
     * затем отдельные подходы, свёрнутые по упражнению («Отжимания — 35 повт.»).
     * Упражнения из комплексов отдельно не перечисляются — их покрывает строка комплекса.
     */
    fun todayActivities(digest: DayDigest): List<String> =
        digest.runs.map { complexLine(it) } + DiaryMath.totals(digest.looseSets).map { totalRepsLine(it) }

    /** Первые [max] строк и сколько осталось за ними. */
    fun truncate(lines: List<String>, max: Int = TODAY_MAX_LINES): Pair<List<String>, Int> {
        val shown = lines.take(max.coerceAtLeast(0))
        return shown to (lines.size - shown.size)
    }

    /** «и ещё 3»; ничего не осталось — null. */
    fun moreLine(rest: Int): String? = if (rest > 0) "и ещё $rest" else null

    // ---------- Баланс на главной ----------

    /**
     * Баланс по частям для табло: цифры и единица отдельно.
     * Как [com.nextlevellife.app.block.BlockerService.formatMinutes]: «2 ч 15 мин», «15 мин», «40 сек».
     */
    fun balanceParts(ms: Long): List<Pair<String, String>> {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> listOf("$h" to "ч", "$m" to "мин")
            m > 0 -> listOf("$m" to "мин")
            else -> listOf("$s" to "сек")
        }
    }

    // ---------- Итог дня ----------

    /**
     * Что показать за день.
     * [looseSets] — отдельные подходы (не из комплексов) по времени; подходы комплексов
     * входят в итоги и [totals], но строкой в списке подходов не повторяются.
     */
    data class DayDigest(
        val sets: Int,
        /** Только повторения: секунды удержаний сюда не входят. */
        val reps: Int,
        val earnedMs: Long,
        val runs: List<ComplexRun>,
        val totals: List<ExerciseTotal>,
        val looseSets: List<SetEntry>,
        /** Шаги и пробежки дня из Health Connect; null — за день ничего не записано. */
        val activity: ActivityDay? = null,
        /** Пробежки дня по времени. */
        val activityRuns: List<ActivityRun> = emptyList(),
        /** Ночной перерыв перед этим днём (день пробуждения); null — расчёта нет. */
        val night: SleepNight? = null,
        /** Засчитанное время удержаний (планка) за день. */
        val heldMs: Long = 0L,
    ) {
        /** Итог тренировки дня одной строкой: «3 подхода · 35 повторений · 2:40 планки · +20 мин». */
        val summary: String get() = summaryLine(sets, reps, earnedMs, heldMs)

        /** Были подходы или комплексы. */
        val hasWorkout: Boolean get() = sets > 0 || runs.isNotEmpty()

        /** Были шаги (больше нуля) или пробежка. */
        val hasActivity: Boolean get() = activity?.hasActivity == true || activityRuns.isNotEmpty()

        /** Есть ночной перерыв (подтверждённый или с неполными данными); «нет данных» не считается. */
        val hasNight: Boolean get() = night != null && night.status != NightStatus.UNKNOWN

        /** День пустой: ни тренировок, ни шагов, ни пробежек, ни ночного перерыва. */
        val isEmpty: Boolean get() = !hasWorkout && !hasActivity && !hasNight
    }

    /**
     * Собирает итог дня без двойного учёта: повторения и награда комплекса уже разложены
     * по его подходам, поэтому прохождение добавляется к сумме, только если у него
     * в дневнике нет ни одного подхода. Шаги и пробежки ([activity]) в эти суммы не входят.
     */
    fun digestDay(
        sets: List<SetEntry>,
        runs: List<ComplexRun>,
        activity: ActivityDay? = null,
        activityRuns: List<ActivityRun> = emptyList(),
        night: SleepNight? = null,
    ): DayDigest {
        val sorted = sets.sortedWith(compareBy({ it.timeMs }, { it.id }))
        val runIdsWithSets = sorted.mapNotNullTo(HashSet()) { it.runId }
        val orphanRuns = runs.filter { it.id !in runIdsWithSets }
        val (holdSets, repSets) = sorted.partition { it.measurement == Measurement.HOLD }
        return DayDigest(
            sets = sorted.size,
            reps = repSets.sumOf { it.reps } + orphanRuns.sumOf { it.totalReps },
            heldMs = holdSets.sumOf { it.heldMs },
            earnedMs = sorted.sumOf { it.earnedMs } + orphanRuns.sumOf { it.earnedMs },
            runs = runs.sortedWith(compareBy({ it.timeMs }, { it.id })),
            totals = DiaryMath.totals(sorted),
            looseSets = sorted.filter { it.runId == null },
            activity = activity,
            activityRuns = activityRuns.sortedWith(compareBy({ it.startMs }, { it.externalId })),
            night = night,
        )
    }

    // ---------- Ночной перерыв ----------
    // Это перерыв в использовании телефона, а не сон: телефон не отличает сон от чтения книги.

    /** Подпись: перерыв считается только по этому телефону. */
    const val THIS_PHONE = "по этому телефону"

    fun nightsWord(n: Int) = pluralRu(n, "ночь", "ночи", "ночей")

    /** «7 ч 35 мин», «45 мин» — длительность перерыва с точностью до минуты. */
    fun breakDuration(ms: Long): String {
        val totalMin = ms.coerceAtLeast(0L) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            else -> "$m мин"
        }
    }

    /** Часы в учётном поясе ночи (если пояс не читается — пояс телефона). */
    private fun nightZone(n: SleepNight): ZoneId = runCatching { ZoneId.of(n.zoneId) }.getOrElse { ZoneId.systemDefault() }

    /** «7 ч 35 мин · 23:20 → 07:00»; перерыва нет — null. */
    fun nightLine(n: SleepNight): String? {
        val d = n.durationMs ?: return null
        val zone = nightZone(n)
        return "${breakDuration(d)} · ${clock(n.startMs!!, zone)} → ${clock(n.endMs!!, zone)}"
    }

    /** «1 короткое обращение», «2 коротких обращения»; не было — null. */
    fun interruptionsLine(count: Int): String? =
        if (count <= 0) null else "$count ${pluralRu(count, "короткое обращение", "коротких обращения", "коротких обращений")}"

    /**
     * Что показать в карточке дня: главная строка и мелкая строка под ней.
     * OK — «7 ч 35 мин · 23:20 → 07:00» и «2 коротких обращения · по этому телефону»;
     * PARTIAL — «неполные данные» и «не меньше 5 ч 10 мин · …»; UNKNOWN — «нет данных».
     * Ещё не окончательная ночь помечается «предварительно».
     */
    fun nightCells(n: SleepNight): Pair<String, String> {
        val tail = buildList {
            if (!n.final) add("предварительно")
            add(THIS_PHONE)
        }
        return when (n.status) {
            NightStatus.OK -> (nightLine(n) ?: "нет данных") to
                (listOfNotNull(interruptionsLine(n.interruptionCount)) + tail).joinToString(" · ")
            NightStatus.PARTIAL -> "неполные данные" to
                (listOfNotNull(n.durationMs?.let { "не меньше ${breakDuration(it)}" }) + tail).joinToString(" · ")
            NightStatus.UNKNOWN -> "нет данных" to tail.joinToString(" · ")
        }
    }

    /** Строка карточки «Сегодня» на главной: «Ночь: 7 ч 35 мин 🌙»; без подтверждённого перерыва — null. */
    fun nightTodayLine(n: SleepNight?): String? {
        if (n == null || n.status != NightStatus.OK) return null
        val d = n.durationMs ?: return null
        return "Ночь: ${breakDuration(d)}" + if (n.phoneFree) " 🌙" else ""
    }

    /** Столбик «Перерыв, ч»: только подтверждённые ночи; неполные и без данных — разрыв (null). */
    fun breakChartValue(n: SleepNight?): Long? = n?.takeIf { it.status == NightStatus.OK }?.durationMs

    // ---------- Шаги и пробежки ----------

    /** «Шаги» | «8 420»; нет данных — «—», подтверждённый ноль — «0». */
    fun stepsCells(steps: Long?): Cells = Cells("Шаги", ActivityMath.stepsValue(steps))

    /** «Бег · 07:12» | «5,2 км · 31 мин». */
    fun activityRunCells(r: ActivityRun, zone: ZoneId = ZoneId.systemDefault()): Cells =
        Cells("Бег · ${clock(r.startMs, zone)}", "${ActivityMath.formatKm(r.distanceM)} · ${ActivityMath.formatMinutes(r.durationMs)}")

    /** Строки «Сегодня» на главной: «Шаги · 8 420» и, если бегал, «Бег · 5,2 км · 31 мин». */
    fun todayActivityLines(activity: ActivityDay?): List<String> {
        val lines = mutableListOf(ActivityMath.stepsLine(activity?.steps))
        if (activity != null && activity.runsCount > 0) lines += ActivityMath.runLine(activity.runDistanceM, activity.runDurationMs)
        return lines
    }

    /** Что показывает диаграмма за 14 дней: один показатель за раз. */
    enum class ChartMetric(val label: String) {
        REPS("Повторы"),
        STEPS("Шаги"),
        RUN_KM("Бег, км"),
        BREAK_H("Перерыв, ч"),
    }

    /**
     * Значения столбиков: повторения, шаги или метры бега по дням (нет данных — 0), ночной перерыв
     * в мс по дням пробуждения ([breakMs]; нет данных — null, на диаграмме это разрыв, а не ноль).
     */
    fun chartValues(
        metric: ChartMetric, reps: List<Int>, steps: List<Long?>, runMeters: List<Long>,
        breakMs: List<Long?> = emptyList(),
    ): List<Long?> = when (metric) {
        ChartMetric.REPS -> reps.map { it.toLong() }
        ChartMetric.STEPS -> steps.map { it ?: 0L }
        ChartMetric.RUN_KM -> runMeters
        ChartMetric.BREAK_H -> breakMs
    }

    /** Высоты столбиков для длинных значений (шаги, метры): как [barFractions]. */
    fun barFractionsLong(values: List<Long>, minVisible: Float = 0.04f): List<Float> {
        val max = values.maxOrNull() ?: return emptyList()
        if (max <= 0) return values.map { 0f }
        return values.map { v -> if (v <= 0) 0f else (v.toDouble() / max).toFloat().coerceIn(minVisible, 1f) }
    }

    /** Как [barFractionsLong], но null (нет данных) остаётся null — столбика нет совсем. */
    fun barFractionsNullable(values: List<Long?>, minVisible: Float = 0.04f): List<Float?> {
        val max = values.filterNotNull().maxOrNull() ?: return values.map { null }
        return values.map { v ->
            when {
                v == null -> null
                max <= 0 || v <= 0 -> 0f
                else -> (v.toDouble() / max).toFloat().coerceIn(minVisible, 1f)
            }
        }
    }

    /** Подпись под заголовком диаграммы: итог за [days] дней по выбранному показателю. */
    fun chartSummary(metric: ChartMetric, days: Int, values: List<Long?>, earnedMs: Long): String {
        val known = values.filterNotNull()
        val total = known.sum()
        return when (metric) {
            ChartMetric.REPS -> "За $days ${daysWord(days)}: $total ${repsWord((total % 1000).toInt())}, +${formatEarned(earnedMs)}"
            ChartMetric.STEPS -> "За $days ${daysWord(days)}: ${ActivityMath.formatThousands(total)} ${ActivityMath.stepsWord(total)}"
            ChartMetric.RUN_KM -> "За $days ${daysWord(days)}: ${ActivityMath.formatKm(total)} бега"
            ChartMetric.BREAK_H ->
                if (known.isEmpty()) "Ночной перерыв: нет данных · $THIS_PHONE"
                else "Ночной перерыв в среднем ${breakDuration(total / known.size)} · ${known.size} ${nightsWord(known.size)} · $THIS_PHONE"
        }
    }

    /** Описание столбика для TalkBack: «12 ноя: 8 420 шагов», «12 ноя: перерыв 7 ч 35 мин». */
    fun barDescription(metric: ChartMetric, day: Long, value: Long?): String = "${shortDate(day)}: " + when (metric) {
        ChartMetric.REPS -> "${value ?: 0} ${repsWord(((value ?: 0) % 1000).toInt())}"
        ChartMetric.STEPS -> "${ActivityMath.formatThousands(value ?: 0)} ${ActivityMath.stepsWord(value ?: 0)}"
        ChartMetric.RUN_KM -> "${ActivityMath.formatKm(value ?: 0)} бега"
        ChartMetric.BREAK_H -> if (value == null) "ночной перерыв — нет данных" else "ночной перерыв ${breakDuration(value)}"
    }

    // ---------- Диаграмма ----------

    /** Последние [count] дней, заканчивая [today], по возрастанию. */
    fun lastDays(today: Long, count: Int): List<Long> = (0 until count).map { today - (count - 1 - it) }

    /**
     * Высоты столбиков в долях от самого высокого (0..1). Ноль остаётся нулём,
     * а любой ненулевой день виден хотя бы на [minVisible].
     */
    fun barFractions(values: List<Int>, minVisible: Float = 0.04f): List<Float> {
        val max = values.maxOrNull() ?: return emptyList()
        if (max <= 0) return values.map { 0f }
        return values.map { v -> if (v <= 0) 0f else (v.toFloat() / max).coerceIn(minVisible, 1f) }
    }

    /** До следующего уровня: сколько опыта осталось и доля пройденного. */
    fun levelProgress(xp: Long, from: Long, to: Long): Pair<Long, Float> {
        val span = (to - from).coerceAtLeast(1)
        val left = (to - xp).coerceAtLeast(0)
        return left to ((xp - from).toFloat() / span).coerceIn(0f, 1f)
    }
}
