package com.nextlevellife.app.health

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.ui.ProgressMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ActivityMathTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val nb = ActivityMath.THOUSANDS_SEPARATOR
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val min = 60_000L

    // ---------- Порции ----------

    @Test
    fun walkingPaysThirtySecondsPerFullHundredSteps() {
        assertEquals(0L, ActivityMath.walkRewardMs(0))
        assertEquals(0L, ActivityMath.walkRewardMs(99))
        assertEquals(30_000L, ActivityMath.walkRewardMs(100))
        assertEquals(30_000L, ActivityMath.walkRewardMs(199))
        assertEquals(5 * min, ActivityMath.walkRewardMs(1_000))
        assertEquals(42 * min, ActivityMath.walkRewardMs(8_420)) // 84 порции × 30 с
        assertEquals(0L, ActivityMath.walkRewardMs(-500))
    }

    @Test
    fun runningPaysMinutePerFullHundredMeters() {
        assertEquals(0L, ActivityMath.runRewardMs(99))
        assertEquals(1 * min, ActivityMath.runRewardMs(100))
        assertEquals(10 * min, ActivityMath.runRewardMs(1_000))
        assertEquals(51 * min, ActivityMath.runRewardMs(5_199))
        assertEquals(0L, ActivityMath.runRewardMs(-1))
    }

    @Test
    fun runStepsAreSubtractedFromWalkingNotBelowZero() {
        assertEquals(3_000L, ActivityMath.walkSteps(8_000, 5_000))
        assertEquals(0L, ActivityMath.walkSteps(4_000, 5_000))
        assertEquals(0L, ActivityMath.walkSteps(null, 0))
        assertEquals(8_000L, ActivityMath.walkSteps(8_000, 0))
        // Шаги пробежки не оплачиваются второй раз: 8000 шагов, из них 5000 на пробежке 4 км.
        val due = ActivityMath.due(totalSteps = 8_000, runSteps = 5_000, runMeters = 4_000)
        assertEquals(15 * min, due.walkMs)
        assertEquals(40 * min, due.runMs)
    }

    @Test
    fun dailyCapIsSixtyMinutesForWalkAndRunTogether() {
        val onlyWalk = ActivityMath.due(totalSteps = 30_000, runSteps = 0, runMeters = 0)
        assertEquals(60 * min, onlyWalk.totalMs)
        val onlyRun = ActivityMath.due(totalSteps = 0, runSteps = 0, runMeters = 10_000)
        assertEquals(60 * min, onlyRun.runMs)
        assertEquals(0L, onlyRun.walkMs)
        // Бег 5 км (50 мин) + ходьба 10 000 шагов (50 мин) → вместе 60, ходьба добирает 10.
        val both = ActivityMath.due(totalSteps = 16_000, runSteps = 6_000, runMeters = 5_000)
        assertEquals(50 * min, both.runMs)
        assertEquals(10 * min, both.walkMs)
        assertEquals(ActivityMath.DAILY_CAP_MS, both.totalMs)
    }

    // ---------- Начисление ----------

    @Test
    fun onlyTheDifferenceIsPaidAndRepeatPaysNothing() {
        val since = day(2026, 11, 10)
        val d = day(2026, 11, 12)
        var rewarded = 0L
        var paid = 0L
        val keys = HashSet<String>()
        // Синхронизации в течение дня: шаги растут, потом повтор без изменений.
        for (steps in listOf(1_000L, 3_050L, 3_050L, 6_000L, 6_000L)) {
            val due = ActivityMath.due(steps, 0, 0).totalMs
            val delta = ActivityMath.rewardDelta(due, rewarded, enabled = true, day = d, sinceDay = since)
            if (delta > 0) {
                assertTrue("ключ не должен повторяться", keys.add(ActivityMath.rewardKey(d, due)))
                paid += delta
                rewarded = due
            }
        }
        assertEquals(30 * min, paid)
        assertEquals(30 * min, rewarded)
        assertEquals(3, keys.size)
        assertEquals("steps:v2:$d:${30 * min}", ActivityMath.rewardKey(d, 30 * min))
    }

    @Test
    fun shrinkingDataNeverTakesBack() {
        val d = day(2026, 11, 12)
        assertEquals(0L, ActivityMath.rewardDelta(dueMs = 10 * min, rewardedMs = 20 * min, enabled = true, day = d, sinceDay = d))
        // Данные вернулись к прежнему уровню — снова ничего: та же сумма уже оплачена.
        assertEquals(0L, ActivityMath.rewardDelta(20 * min, 20 * min, true, d, d))
        assertEquals(5 * min, ActivityMath.rewardDelta(25 * min, 20 * min, true, d, d))
    }

    @Test
    fun daysBeforeEnablingAndDisabledAreNotPaid() {
        val since = day(2026, 11, 12)
        assertEquals(0L, ActivityMath.rewardDelta(30 * min, 0, enabled = true, day = since - 1, sinceDay = since))
        assertEquals(30 * min, ActivityMath.rewardDelta(30 * min, 0, enabled = true, day = since, sinceDay = since))
        assertEquals(0L, ActivityMath.rewardDelta(30 * min, 0, enabled = false, day = since, sinceDay = since))
        assertEquals(0L, ActivityMath.rewardDelta(30 * min, 0, enabled = true, day = since, sinceDay = null))
    }

    @Test
    fun xpDeltaHasNoRoundingLoss() {
        // Опыт = 1 за 10 секунд; по частям — столько же, сколько сразу.
        val parts = listOf(0L, 30_000L, 90_000L, 3_600_000L)
        val sum = parts.zipWithNext { a, b -> ActivityMath.xpDelta(b, a) }.sum()
        assertEquals(360L, sum)
        assertEquals(0L, ActivityMath.xpDelta(10_000, 20_000))
    }

    @Test
    fun pastDayRewardLandsInThatDay() {
        val now = ms(2026, 11, 13, 9, 0)
        val past = day(2026, 11, 11)
        val t = ActivityMath.occurredAtMs(past, now, zone)
        assertEquals(past, ActivityMath.dayOf(t, zone))
        assertEquals(now, ActivityMath.occurredAtMs(day(2026, 11, 13), now, zone))
    }

    // ---------- Синхронизация ----------

    @Test
    fun syncIsRateLimitedToFiveMinutesExceptButton() {
        val t = 1_000_000_000L
        assertTrue(ActivityMath.shouldSync(null, t, force = false))
        assertFalse(ActivityMath.shouldSync(t, t + 4 * min, force = false))
        assertTrue(ActivityMath.shouldSync(t, t + 5 * min, force = false))
        assertTrue(ActivityMath.shouldSync(t, t + 1_000, force = true))
        // Часы перевели назад — не застревать.
        assertTrue(ActivityMath.shouldSync(t, t - 1, force = false))
    }

    @Test
    fun syncWindowIsTodayAndFourteenDaysBack() {
        val today = day(2026, 11, 13)
        val days = ActivityMath.syncDays(today)
        assertEquals(15, days.size)
        assertEquals(today - 14, days.first())
        assertEquals(today, days.last())
    }

    // ---------- Пробежки ----------

    private fun run(id: String, start: Long, end: Long, m: Long, src: String = "g", byDay: Map<Long, Long> = emptyMap()) =
        RunSession(id, start, end, src, 56, m, byDay.values.sum(), byDay)

    @Test
    fun overlappingDuplicateRunsCollapseToLongestDistance() {
        val a = run("garmin", ms(2026, 11, 12, 7, 0), ms(2026, 11, 12, 7, 31), 5_200, "com.garmin.android.apps.connectmobile")
        val b = run("samsung", ms(2026, 11, 12, 7, 2), ms(2026, 11, 12, 7, 30), 4_900, "com.sec.android.app.shealth")
        val c = run("evening", ms(2026, 11, 12, 19, 0), ms(2026, 11, 12, 19, 20), 3_000)
        // Касается концом — отдельная пробежка.
        val d = run("touch", ms(2026, 11, 12, 19, 20), ms(2026, 11, 12, 19, 30), 1_000)
        val merged = ActivityMath.mergeOverlapping(listOf(c, b, d, a))
        assertEquals(listOf("garmin", "evening", "touch"), merged.map { it.id })
    }

    @Test
    fun runAcrossMidnightSplitsStepsByDay() {
        val pieces = ActivityMath.dayPieces(ms(2026, 11, 12, 23, 40), ms(2026, 11, 13, 0, 20), zone)
        assertEquals(listOf(day(2026, 11, 12), day(2026, 11, 13)), pieces.map { it.day })
        assertEquals(ms(2026, 11, 13, 0, 0), pieces[0].endMs)
        assertEquals(pieces[0].endMs, pieces[1].startMs)
        assertTrue(ActivityMath.dayPieces(10, 10, zone).isEmpty())
    }

    @Test
    fun buildDaysAttributesRunsAndSubtractsOnlyRunsWithDistance() {
        val d12 = day(2026, 11, 12)
        val d13 = day(2026, 11, 13)
        val night = run("n", ms(2026, 11, 12, 23, 40), ms(2026, 11, 13, 0, 20), 6_000, byDay = mapOf(d12 to 2_000L, d13 to 2_500L))
        val treadmill = run("t", ms(2026, 11, 13, 10, 0), ms(2026, 11, 13, 10, 30), 0, byDay = mapOf(d13 to 3_000L))
        val totals = ActivityMath.buildDays(
            days = listOf(d12, d13),
            stepsByDay = mapOf(d12 to 9_000L, d13 to 7_000L),
            sourcesByDay = mapOf(d12 to setOf("com.sec.android.app.shealth")),
            runs = listOf(night, treadmill),
            zone = zone,
        )
        val t12 = totals[0]
        assertEquals(6_000L, t12.runDistanceM)
        assertEquals(1, t12.runsCount)
        assertEquals(2_000L, t12.runSteps)
        assertEquals(listOf("com.sec.android.app.shealth", "g"), t12.sources)
        val t13 = totals[1]
        assertEquals(0L, t13.runDistanceM)
        assertEquals(1, t13.runsCount) // дорожка без дистанции — пробежка, но не оплачивается
        assertEquals(2_500L, t13.runSteps) // шаги дорожки остаются ходьбой
        assertNull(ActivityMath.buildDays(listOf(d13 + 1), emptyMap(), emptyMap(), emptyList(), zone).single().steps)
    }

    @Test
    fun runStartedBeforeWindowStillCountsInWindow() {
        val d12 = day(2026, 11, 12)
        val d13 = day(2026, 11, 13)
        val d14 = day(2026, 11, 14)
        // Окно 13–14: пробежка с 12-го на 13-е задевает окно, пробежка целиком 12-го — нет.
        assertTrue(ActivityMath.runTouchesWindow(ms(2026, 11, 12, 23, 40), ms(2026, 11, 13, 0, 20), d13, d14, zone))
        assertFalse(ActivityMath.runTouchesWindow(ms(2026, 11, 12, 22, 0), ms(2026, 11, 12, 23, 0), d13, d14, zone))
        assertFalse(ActivityMath.runTouchesWindow(ms(2026, 11, 12, 23, 0), ms(2026, 11, 13, 0, 0), d13, d14, zone))
        assertTrue(ActivityMath.runTouchesWindow(ms(2026, 11, 14, 23, 0), ms(2026, 11, 15, 0, 30), d13, d14, zone))
        assertFalse(ActivityMath.runTouchesWindow(ms(2026, 11, 15, 0, 10), ms(2026, 11, 15, 0, 30), d13, d14, zone))

        // Шаги такой пробежки в окне вычитаются из ходьбы; дистанция и счёт — дню начала (вне окна).
        val night = run("n", ms(2026, 11, 12, 23, 40), ms(2026, 11, 13, 0, 20), 6_000, byDay = mapOf(d12 to 2_000L, d13 to 2_500L))
        val t13 = ActivityMath.buildDays(listOf(d13, d14), mapOf(d13 to 7_000L), emptyMap(), listOf(night), zone).first()
        assertEquals(2_500L, t13.runSteps)
        assertEquals(0L, t13.runDistanceM)
        assertEquals(0, t13.runsCount)
        assertEquals(4_500L, ActivityMath.walkSteps(t13.steps, t13.runSteps))
    }

    // ---------- Подписи ----------

    @Test
    fun numbersAreFormattedWithSpacesAndKmWithOneDecimal() {
        assertEquals("0", ActivityMath.formatThousands(0))
        assertEquals("999", ActivityMath.formatThousands(999))
        assertEquals("8${nb}420", ActivityMath.formatThousands(8_420))
        assertEquals("1${nb}234${nb}567", ActivityMath.formatThousands(1_234_567))
        assertEquals("—", ActivityMath.stepsValue(null))
        assertEquals("0", ActivityMath.stepsValue(0))
        assertEquals("5,2 км", ActivityMath.formatKm(5_200))
        assertEquals("5,2 км", ActivityMath.formatKm(5_150))
        assertEquals("0,0 км", ActivityMath.formatKm(40))
        assertEquals("12,0 км", ActivityMath.formatKm(11_990))
        assertEquals("31 мин", ActivityMath.formatMinutes(31 * min))
        assertEquals("1 ч 5 мин", ActivityMath.formatMinutes(65 * min))
        assertEquals("Бег · 5,2 км · 31 мин", ActivityMath.runLine(5_200, 31 * min))
        assertEquals("Шаги · 8${nb}420", ActivityMath.stepsLine(8_420))
        assertEquals("8${nb}420 шагов", ActivityMath.stepsPhrase(8_420))
        assertEquals("1 шаг", ActivityMath.stepsPhrase(1))
        assertEquals("2 шага", ActivityMath.stepsPhrase(2))
        assertEquals("12 шагов", ActivityMath.stepsPhrase(12))
        assertEquals("+12 мин за шаги", ActivityMath.rewardLine(12 * min))
        assertEquals("+2,5 мин за шаги", ActivityMath.rewardLine(150_000))
        assertNull(ActivityMath.rewardLine(0))
    }

    @Test
    fun sourcesAndUpdatedLines() {
        assertEquals(
            "Источники: Garmin Connect, Samsung Health",
            ActivityMath.sourcesLine(listOf("com.garmin.android.apps.connectmobile", "com.sec.android.app.shealth")),
        )
        assertEquals("Источники: com.example.app", ActivityMath.sourcesLine(listOf("com.example.app")))
        assertEquals("Источники: —", ActivityMath.sourcesLine(emptyList()))
        val now = ms(2026, 11, 13, 20, 0)
        assertEquals("Обновлено в 18:42", ActivityMath.updatedLine(ms(2026, 11, 13, 18, 42), now, zone))
        assertEquals("Обновлено 12 ноября в 07:05", ActivityMath.updatedLine(ms(2026, 11, 12, 7, 5), now, zone))
        assertEquals("Ещё не обновлялось", ActivityMath.updatedLine(null, now, zone))
    }

    // ---------- Экран «Прогресс»: день только с шагами не пустой ----------

    private fun activity(steps: Long?, runs: Int = 0) =
        ActivityDay(day(2026, 11, 12), steps, 0, 0, 0, runs, emptyList(), 0, 0)

    @Test
    fun dayWithOnlyStepsOrRunIsNotEmpty() {
        val stepsOnly = ProgressMath.digestDay(emptyList(), emptyList(), activity(8_420))
        assertFalse(stepsOnly.isEmpty)
        assertFalse(stepsOnly.hasWorkout)
        assertTrue(stepsOnly.hasActivity)
        val run = ActivityRun("x", 0, 60_000, day(2026, 11, 12), 1_000, 900, "g", 56)
        assertFalse(ProgressMath.digestDay(emptyList(), emptyList(), activity(null, 1), listOf(run)).isEmpty)
        // Подтверждённый ноль и «нет данных» — день пустой.
        assertTrue(ProgressMath.digestDay(emptyList(), emptyList(), activity(0)).isEmpty)
        assertTrue(ProgressMath.digestDay(emptyList(), emptyList(), activity(null)).isEmpty)
        assertTrue(ProgressMath.digestDay(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun chartMetricsAndTodayLines() {
        val reps = listOf(10, 0)
        val steps = listOf<Long?>(8_000, null)
        val meters = listOf(0L, 5_200L)
        assertEquals(listOf(10L, 0L), ProgressMath.chartValues(ProgressMath.ChartMetric.REPS, reps, steps, meters))
        assertEquals(listOf(8_000L, 0L), ProgressMath.chartValues(ProgressMath.ChartMetric.STEPS, reps, steps, meters))
        assertEquals(listOf(0L, 5_200L), ProgressMath.chartValues(ProgressMath.ChartMetric.RUN_KM, reps, steps, meters))
        assertEquals(listOf(1f, 0f), ProgressMath.barFractionsLong(listOf(8_000L, 0L)))
        assertEquals("За 14 дней: 5,2 км бега", ProgressMath.chartSummary(ProgressMath.ChartMetric.RUN_KM, 14, meters, 0))
        assertEquals(
            "За 14 дней: 8${nb}000 шагов",
            ProgressMath.chartSummary(ProgressMath.ChartMetric.STEPS, 14, listOf(8_000L, 0L), 0),
        )
        assertEquals(listOf("Шаги · —"), ProgressMath.todayActivityLines(null))
        val withRun = ActivityDay(0, 8_420, 5_200, 31 * min, 0, 1, emptyList(), 0, 0)
        assertEquals(listOf("Шаги · 8${nb}420", "Бег · 5,2 км · 31 мин"), ProgressMath.todayActivityLines(withRun))
        assertEquals("—", ProgressMath.stepsCells(null).value)
        assertEquals("0", ProgressMath.stepsCells(0).value)
    }
}
