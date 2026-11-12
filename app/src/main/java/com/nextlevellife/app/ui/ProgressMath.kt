package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.DiaryMath
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.SetEntry
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

    /** «3 подхода · 35 повторений · +22 мин». */
    fun summaryLine(sets: Int, reps: Int, earnedMs: Long): String =
        "$sets ${setsWord(sets)} · $reps ${repsWord(reps)} · +${formatEarned(earnedMs)}"

    /** «Мах гирей 24 кг» или «Отжимания». */
    fun exerciseLabel(name: String, weightKg: Int?): String =
        if (weightKg != null) "$name $weightKg кг" else name

    /** «Отжимания — 2 подхода, 35 повт.». */
    fun totalLine(t: ExerciseTotal): String =
        "${exerciseLabel(t.exerciseName, t.weightKg)} — ${t.sets} ${setsWord(t.sets)}, ${t.reps} повт."

    /** «Отжимания — 20 повт.» — короткая строка для карточки «Сегодня». */
    fun totalRepsLine(t: ExerciseTotal): String =
        "${exerciseLabel(t.exerciseName, t.weightKg)} — ${t.reps} повт."

    /** «21:00 · Отжимания · 15 повт. · +7,5 мин». */
    fun setLine(s: SetEntry, zone: ZoneId = ZoneId.systemDefault()): String =
        "${clock(s.timeMs, zone)} · ${exerciseLabel(s.exerciseName, s.weightKg)} · ${s.reps} повт. · +${formatEarned(s.earnedMs)}"

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

    // ---------- Итог дня ----------

    /**
     * Что показать за день.
     * [looseSets] — отдельные подходы (не из комплексов) по времени; подходы комплексов
     * входят в итоги и [totals], но строкой в списке подходов не повторяются.
     */
    data class DayDigest(
        val sets: Int,
        val reps: Int,
        val earnedMs: Long,
        val runs: List<ComplexRun>,
        val totals: List<ExerciseTotal>,
        val looseSets: List<SetEntry>,
    ) {
        val isEmpty: Boolean get() = sets == 0 && runs.isEmpty()
    }

    /**
     * Собирает итог дня без двойного учёта: повторения и награда комплекса уже разложены
     * по его подходам, поэтому прохождение добавляется к сумме, только если у него
     * в дневнике нет ни одного подхода.
     */
    fun digestDay(sets: List<SetEntry>, runs: List<ComplexRun>): DayDigest {
        val sorted = sets.sortedWith(compareBy({ it.timeMs }, { it.id }))
        val runIdsWithSets = sorted.mapNotNullTo(HashSet()) { it.runId }
        val orphanRuns = runs.filter { it.id !in runIdsWithSets }
        return DayDigest(
            sets = sorted.size,
            reps = sorted.sumOf { it.reps } + orphanRuns.sumOf { it.totalReps },
            earnedMs = sorted.sumOf { it.earnedMs } + orphanRuns.sumOf { it.earnedMs },
            runs = runs.sortedWith(compareBy({ it.timeMs }, { it.id })),
            totals = DiaryMath.totals(sorted),
            looseSets = sorted.filter { it.runId == null },
        )
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
