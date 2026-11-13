package com.nextlevellife.app.health

import com.nextlevellife.app.data.Exercises
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Пробежка, как её прочитали из Health Connect.
 * [distanceM] — дистанция источника сессии за её интервал, [steps] — все шаги за интервал (без двойного счёта),
 * [stepsByDay] — те же шаги по локальным суткам (пробежка через полночь делится на два дня).
 */
data class RunSession(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val exerciseType: Int,
    val distanceM: Long,
    val steps: Long = 0L,
    val stepsByDay: Map<Long, Long> = emptyMap(),
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Часть интервала внутри одних локальных суток [day]: [startMs] включительно, [endMs] исключительно. */
data class DayPiece(val day: Long, val startMs: Long, val endMs: Long)

/** Итог дня до начисления: что прочитано из Health Connect и сведено по правилам. */
data class DayTotals(
    val day: Long,
    val steps: Long?,
    val runDistanceM: Long,
    val runDurationMs: Long,
    val runSteps: Long,
    val runsCount: Int,
    val sources: List<String>,
)

/** Сколько положено за день: за ходьбу и за бег, вместе не больше потолка. */
data class Due(val walkMs: Long, val runMs: Long) {
    val totalMs: Long get() = walkMs + runMs
}

/**
 * Чистая логика шагов и пробежек, без Android: её проверяют юнит-тесты.
 * Дни — номера локальных суток (`LocalDate.toEpochDay()`), как в дневнике.
 */
object ActivityMath {
    /** Сколько прошлых дней перечитывается при синхронизации (плюс сегодня). */
    const val SYNC_PAST_DAYS = 14

    /** Ходьба: 30 секунд за каждые полные 100 шагов (5 минут за 1000). */
    const val STEP_PORTION = 100L
    const val MS_PER_STEP_PORTION = 30_000L

    /** Бег: минута за каждые полные 100 метров (10 минут за километр). */
    const val METER_PORTION = 100L
    const val MS_PER_METER_PORTION = 60_000L

    /** Потолок начисления за ходьбу и бег вместе — 60 минут в сутки. */
    const val DAILY_CAP_MS = 60 * 60_000L

    /** Автоматическая синхронизация — не чаще раза в 5 минут (кнопка «Обновить» — без ограничения). */
    const val SYNC_INTERVAL_MS = 5 * 60_000L

    /** Префикс ключа операции журнала: `steps:<day>:<due>`. */
    const val KEY_PREFIX = "steps"

    // ---------- Дни ----------

    /** Окно синхронизации: [SYNC_PAST_DAYS] прошлых дней и сегодня, по возрастанию. */
    fun syncDays(today: Long, pastDays: Int = SYNC_PAST_DAYS): List<Long> = (today - pastDays..today).toList()

    fun dayOf(timeMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()

    /** Начало локальных суток [day] в мс эпохи (с учётом перехода на летнее время). */
    fun dayStartMs(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Делит интервал [startMs, endMs) по локальным суткам. Пустой или перевёрнутый интервал — пустой список. */
    fun dayPieces(startMs: Long, endMs: Long, zone: ZoneId): List<DayPiece> {
        if (endMs <= startMs) return emptyList()
        val out = ArrayList<DayPiece>(2)
        var from = startMs
        while (from < endMs) {
            val day = dayOf(from, zone)
            val to = minOf(endMs, dayStartMs(day + 1, zone))
            out += DayPiece(day, from, to)
            from = to
        }
        return out
    }

    // ---------- Пробежки ----------

    /** Сколько суток до окна дополнительно читать пробежки: пробежка через полночь начинается накануне. */
    const val RUNS_LOOKBACK_DAYS = 1L

    /**
     * Нужна ли пробежка для окна дней [firstDay]..[lastDay]: начата не позже последнего дня окна
     * и заканчивается после его начала. Пробежка, начатая до окна, но перешедшая через полночь в окно,
     * нужна: её шаги в днях окна — бег, их нельзя оплачивать второй раз как ходьбу.
     */
    fun runTouchesWindow(startMs: Long, endMs: Long, firstDay: Long, lastDay: Long, zone: ZoneId): Boolean =
        endMs > startMs && endMs > dayStartMs(firstDay, zone) && dayOf(startMs, zone) <= lastDay

    /**
     * Одна и та же пробежка может прийти дважды (часы Garmin и автоопределение тренировки
     * в Samsung Health). Пересекающиеся по времени сессии сводятся к одной: остаётся та,
     * где дистанция больше (затем длиннее, затем раньше начата, затем меньший id).
     * Касание концами («одна кончилась — другая началась») пересечением не считается.
     */
    fun mergeOverlapping(runs: List<RunSession>): List<RunSession> {
        val sorted = runs.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { it.id }))
        val out = ArrayList<RunSession>()
        var cluster = ArrayList<RunSession>()
        var clusterEnd = Long.MIN_VALUE
        for (r in sorted) {
            if (cluster.isNotEmpty() && r.startMs >= clusterEnd) {
                out += best(cluster)
                cluster = ArrayList()
            }
            cluster += r
            clusterEnd = if (cluster.size == 1) r.endMs else maxOf(clusterEnd, r.endMs)
        }
        if (cluster.isNotEmpty()) out += best(cluster)
        return out
    }

    private fun best(cluster: List<RunSession>): RunSession = cluster.sortedWith(
        compareByDescending<RunSession> { it.distanceM }
            .thenByDescending { it.durationMs }
            .thenBy { it.startMs }
            .thenBy { it.id }
    ).first()

    /**
     * Сводит прочитанное в итоги по дням [days]. Дистанция, время и число пробежек относятся
     * к дню начала пробежки; шаги пробежки вычитаются по тем суткам, где они сделаны.
     * Пробежка без дистанции (дорожка без датчика) не оплачивается как бег — поэтому её шаги
     * остаются шагами ходьбы и не вычитаются.
     */
    fun buildDays(
        days: List<Long>,
        stepsByDay: Map<Long, Long?>,
        sourcesByDay: Map<Long, Set<String>>,
        runs: List<RunSession>,
        zone: ZoneId,
    ): List<DayTotals> = days.map { day ->
        val started = runs.filter { dayOf(it.startMs, zone) == day }
        val runSteps = runs.filter { it.distanceM > 0 }.sumOf { it.stepsByDay[day] ?: 0L }
        val sources = (sourcesByDay[day].orEmpty() + started.map { it.source }).filter { it.isNotBlank() }.sorted()
        DayTotals(
            day = day,
            steps = stepsByDay[day],
            runDistanceM = started.sumOf { it.distanceM.coerceAtLeast(0L) },
            runDurationMs = started.sumOf { it.durationMs },
            runSteps = runSteps,
            runsCount = started.size,
            sources = sources.distinct(),
        )
    }

    // ---------- Начисление ----------

    /** Шаги ходьбы: все шаги дня минус шаги пробежек, не меньше нуля; нет данных — 0. */
    fun walkSteps(totalSteps: Long?, runSteps: Long): Long =
        ((totalSteps ?: 0L) - runSteps.coerceAtLeast(0L)).coerceAtLeast(0L)

    /** За ходьбу: 30 секунд за каждые полные 100 шагов. */
    fun walkRewardMs(walkSteps: Long): Long = walkSteps.coerceAtLeast(0L) / STEP_PORTION * MS_PER_STEP_PORTION

    /** За бег: минута за каждые полные 100 метров. */
    fun runRewardMs(meters: Long): Long = meters.coerceAtLeast(0L) / METER_PORTION * MS_PER_METER_PORTION

    /**
     * Положенное за день. Бег идёт первым (он труднее), ходьба добирает до общего потолка [capMs].
     */
    fun due(totalSteps: Long?, runSteps: Long, runMeters: Long, capMs: Long = DAILY_CAP_MS): Due {
        val run = minOf(runRewardMs(runMeters), capMs)
        val walk = minOf(walkRewardMs(walkSteps(totalSteps, runSteps)), capMs - run)
        return Due(walkMs = walk, runMs = run)
    }

    fun due(t: DayTotals, capMs: Long = DAILY_CAP_MS): Due = due(t.steps, t.runSteps, t.runDistanceM, capMs)

    /**
     * Сколько доначислить за день: только «вверх» и только если начисление включено и день
     * не раньше дня включения [sinceDay]. Уменьшение данных ничего не списывает.
     */
    fun rewardDelta(dueMs: Long, rewardedMs: Long, enabled: Boolean, day: Long, sinceDay: Long?): Long = when {
        !enabled || sinceDay == null || day < sinceDay -> 0L
        else -> (dueMs - rewardedMs).coerceAtLeast(0L)
    }

    /** Опыт за доначисление: разница опыта за всё положенное и за уже начисленное — без потерь на округлении. */
    fun xpDelta(dueMs: Long, rewardedMs: Long): Long =
        (Exercises.xp(dueMs) - Exercises.xp(rewardedMs.coerceAtLeast(0L))).coerceAtLeast(0L)

    /** Ключ идемпотентности: каждая новая положенная сумма дня оплачивается один раз. */
    fun rewardKey(day: Long, dueMs: Long): String = "$KEY_PREFIX:$day:$dueMs"

    /** Время операции: для прошлых дней — последняя миллисекунда того дня, чтобы начисление легло в свой день. */
    fun occurredAtMs(day: Long, nowMs: Long, zone: ZoneId): Long = minOf(nowMs, dayStartMs(day + 1, zone) - 1)

    // ---------- Частота синхронизации ----------

    /**
     * Пора ли читать Health Connect: по кнопке — всегда; иначе, если ещё не читали,
     * прошло не меньше [intervalMs] или часы перевели назад.
     */
    fun shouldSync(lastMs: Long?, nowMs: Long, force: Boolean, intervalMs: Long = SYNC_INTERVAL_MS): Boolean =
        force || lastMs == null || nowMs < lastMs || nowMs - lastMs >= intervalMs

    // ---------- Подписи ----------

    /** «8 420», «1 234 567»: тысячи разделены неразрывным пробелом. */
    fun formatThousands(n: Long): String {
        val digits = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(THOUSANDS_SEPARATOR)
            sb.append(c)
        }
        return if (n < 0) "−$sb" else sb.toString()
    }

    /** Шаги: «8 420»; нет данных — «—»; подтверждённый ноль — «0». */
    fun stepsValue(steps: Long?): String = steps?.let { formatThousands(it) } ?: "—"

    /** «5,2 км», «0,4 км», «12,0 км» — одна цифра после запятой, с округлением. */
    fun formatKm(meters: Long): String {
        val tenths = (meters.coerceAtLeast(0L) + 50) / 100
        return "${formatThousands(tenths / 10)},${tenths % 10} км"
    }

    /** «31 мин», «1 ч 5 мин», «1 ч»; меньше минуты — «0 мин». С округлением до минуты. */
    fun formatMinutes(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0L) + 30_000) / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            else -> "$m мин"
        }
    }

    fun stepsWord(n: Long): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> "шагов"
            mod10 == 1L -> "шаг"
            mod10 in 2..4 -> "шага"
            else -> "шагов"
        }
    }

    /** «8 420 шагов», «1 шаг»; нет данных — «нет данных». */
    fun stepsPhrase(steps: Long?): String =
        if (steps == null) "нет данных" else "${formatThousands(steps)} ${stepsWord(steps)}"

    /** «Шаги · 8 420». */
    fun stepsLine(steps: Long?): String = "Шаги · ${stepsValue(steps)}"

    /** «Бег · 5,2 км · 31 мин». */
    fun runLine(distanceM: Long, durationMs: Long): String = "Бег · ${formatKm(distanceM)} · ${formatMinutes(durationMs)}"

    /** «+12 мин за шаги», «+2,5 мин за шаги» (порции по 30 секунд); начислений не было — null. */
    fun rewardLine(rewardedMs: Long): String? {
        if (rewardedMs <= 0) return null
        val tenths = (rewardedMs + 3_000) / 6_000
        val text = if (tenths % 10 == 0L) "${tenths / 10} мин" else "${tenths / 10},${tenths % 10} мин"
        return "+$text за шаги"
    }

    private val KNOWN_SOURCES = mapOf(
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.android.healthconnect.controller" to "Health Connect",
        "com.strava" to "Strava",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.huawei.health" to "Huawei Health",
        "com.xiaomi.wearable" to "Mi Fitness",
        "android" to "Телефон",
    )

    /** Понятное название приложения-источника; неизвестное — имя пакета как есть. */
    fun sourceLabel(packageName: String): String = KNOWN_SOURCES[packageName] ?: packageName

    /** «Источники: Garmin Connect, Samsung Health»; без источников — «Источники: —». */
    fun sourcesLine(packages: Collection<String>): String {
        val labels = packages.filter { it.isNotBlank() }.map { sourceLabel(it) }.distinct()
        return "Источники: " + if (labels.isEmpty()) "—" else labels.joinToString(", ")
    }

    /** «Обновлено в 18:42» сегодня, «Обновлено 11 ноября в 18:42» раньше, «Ещё не обновлялось» — ни разу. */
    fun updatedLine(lastMs: Long?, nowMs: Long, zone: ZoneId): String {
        if (lastMs == null) return "Ещё не обновлялось"
        val t = Instant.ofEpochMilli(lastMs).atZone(zone)
        val clock = "%02d:%02d".format(t.hour, t.minute)
        return if (t.toLocalDate().toEpochDay() == dayOf(nowMs, zone)) "Обновлено в $clock"
        else "Обновлено ${t.dayOfMonth} ${MONTHS_GENITIVE[t.monthValue - 1]} в $clock"
    }

    private val MONTHS_GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Неразрывный пробел: «8 420» не переносится посередине. */
    const val THOUSANDS_SEPARATOR = ' '
}
