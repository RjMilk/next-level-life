package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProgressMathTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun month(y: Int, m: Int) = ProgressMath.monthIndex(LocalDate.of(y, m, 1))

    // ---------- Календарь ----------

    @Test
    fun monthStartingOnSundayHasSixEmptyCellsAndSixWeeks() {
        // 1 ноября 2026 — воскресенье.
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 11, 1).dayOfWeek)
        val grid = ProgressMath.monthGrid(month(2026, 11))
        assertEquals(List(6) { null }, grid.take(6))
        assertEquals(day(2026, 11, 1), grid[6])
        assertEquals(day(2026, 11, 30), grid.filterNotNull().last())
        assertEquals(30, grid.count { it != null })
        assertEquals(42, grid.size)
        assertEquals(6, ProgressMath.weeksInMonth(month(2026, 11)))
    }

    @Test
    fun leapFebruaryHas29DaysFromTuesday() {
        // 2028 — високосный, 1 февраля — вторник.
        val grid = ProgressMath.monthGrid(month(2028, 2))
        assertEquals(listOf(null), grid.take(1))
        assertEquals(day(2028, 2, 1), grid[1])
        assertEquals(29, grid.count { it != null })
        assertEquals(day(2028, 2, 29), grid.filterNotNull().last())
        assertEquals(5, ProgressMath.weeksInMonth(month(2028, 2)))
        assertEquals(0, grid.size % 7)
    }

    @Test
    fun februaryFromMondayFitsInFourWeeks() {
        // Февраль 2027: 28 дней, начинается с понедельника — ровно 4 недели без пустых клеток.
        val grid = ProgressMath.monthGrid(month(2027, 2))
        assertEquals(28, grid.size)
        assertTrue(grid.all { it != null })
        assertEquals(4, ProgressMath.weeksInMonth(month(2027, 2)))
    }

    @Test
    fun gridDaysAreConsecutiveAndAlignedToWeekdays() {
        for (m in 1..12) {
            val idx = month(2026, m)
            val grid = ProgressMath.monthGrid(idx)
            grid.forEachIndexed { i, d ->
                if (d != null) assertEquals(i % 7 + 1, LocalDate.ofEpochDay(d).dayOfWeek.value)
            }
            val days = grid.filterNotNull()
            assertEquals((days.first()..days.last()).toList(), days)
            assertEquals(ProgressMath.firstDayOfMonth(idx), days.first())
            assertEquals(ProgressMath.lastDayOfMonth(idx), days.last())
        }
    }

    @Test
    fun cannotGoIntoFuture() {
        val today = day(2026, 11, 12)
        assertFalse(ProgressMath.canGoNext(month(2026, 11), today))
        assertEquals(month(2026, 11), ProgressMath.nextMonth(month(2026, 11), today))
        assertTrue(ProgressMath.canGoNext(month(2026, 10), today))
        assertEquals(month(2026, 11), ProgressMath.nextMonth(month(2026, 10), today))
        // Через границу года.
        assertTrue(ProgressMath.canGoNext(month(2025, 12), today))
        assertEquals(month(2026, 1), month(2025, 12) + 1)
        assertEquals(LocalDate.of(2026, 1, 1), ProgressMath.firstOfMonth(month(2025, 12) + 1))
    }

    // ---------- Подписи и склонения ----------

    @Test
    fun monthTitleIsNominativeAndDayTitleIsGenitive() {
        assertEquals("Ноябрь 2026", ProgressMath.monthTitle(month(2026, 11)))
        assertEquals("Май 2026", ProgressMath.monthTitle(month(2026, 5)))
        val today = day(2026, 11, 12)
        assertEquals("12 ноября, четверг", ProgressMath.dayTitle(today, today))
        assertEquals("3 мая, воскресенье", ProgressMath.dayTitle(day(2026, 5, 3), today))
        assertEquals("31 декабря 2025, среда", ProgressMath.dayTitle(day(2025, 12, 31), today))
        assertEquals("12 ноя", ProgressMath.shortDate(today))
        assertEquals("1 мая", ProgressMath.shortDate(day(2026, 5, 1)))
    }

    @Test
    fun pluralForms() {
        assertEquals(
            listOf("подход", "подхода", "подходов", "подходов", "подход", "подхода"),
            listOf(1, 2, 5, 11, 21, 22).map { ProgressMath.setsWord(it) },
        )
        assertEquals(
            listOf("повторение", "повторения", "повторений", "повторений", "повторений"),
            listOf(1, 3, 0, 12, 35).map { ProgressMath.repsWord(it) },
        )
        assertEquals(listOf("день", "дня", "дней", "дней"), listOf(1, 4, 5, 14).map { ProgressMath.daysWord(it) })
        assertEquals(listOf("круг", "круга", "кругов"), listOf(101, 2, 12).map { ProgressMath.roundsWord(it) })
        assertEquals("🔥 3 дня подряд", ProgressMath.streakLabel(3))
    }

    @Test
    fun earnedTimeFormat() {
        assertEquals("7,5 мин", ProgressMath.formatEarned(450_000))
        assertEquals("22 мин", ProgressMath.formatEarned(22 * 60_000L))
        assertEquals("0,5 мин", ProgressMath.formatEarned(30_000))
        assertEquals("2 ч 30 мин", ProgressMath.formatEarned(150 * 60_000L))
        assertEquals("1 ч", ProgressMath.formatEarned(60 * 60_000L))
        assertEquals("0 мин", ProgressMath.formatEarned(0))
        assertEquals(
            "3 подхода · 35 повторений · +22 мин",
            ProgressMath.summaryLine(3, 35, 22 * 60_000L),
        )
    }

    @Test
    fun lineFormats() {
        val zone = ZoneOffset.UTC
        val t = LocalDateTime.of(2026, 11, 12, 21, 0).toInstant(zone).toEpochMilli()
        val pushups = SetEntry(1, t, 0, "pushups", "Отжимания", null, 15, 0, 450_000, 45, null)
        assertEquals("21:00 · Отжимания · 15 повт. · +7,5 мин", ProgressMath.setLine(pushups, zone))
        val swings = pushups.copy(exerciseId = "swings", exerciseName = "Мах гирей", weightKg = 24, reps = 30, earnedMs = 18 * 60_000L)
        assertEquals("21:00 · Мах гирей 24 кг · 30 повт. · +18 мин", ProgressMath.setLine(swings, zone))
        assertEquals(
            "Отжимания — 2 подхода, 35 повт.",
            ProgressMath.totalLine(ExerciseTotal("pushups", "Отжимания", null, 2, 35)),
        )
        assertEquals(
            "Мах гирей 24 кг — 30 повт.",
            ProgressMath.totalRepsLine(ExerciseTotal("swings", "Мах гирей", 24, 1, 30)),
        )
    }

    @Test
    fun complexLines() {
        val minotaur = run(1, Minotaur.ID, weight = 16, durationMs = (24 * 60 + 35) * 1000L)
        assertEquals("Минотавр 16 кг — 24:35", ProgressMath.complexLine(minotaur))
        val cindy = run(2, Cindy.ID, rounds = 12, extra = 7)
        assertEquals("Синди — 12 кругов + 7 повт.", ProgressMath.complexLine(cindy))
        assertEquals(
            "Синди — 3 круга + 0 повт. · не завершён",
            ProgressMath.complexLine(run(3, Cindy.ID, rounds = 3, extra = null, completed = false)),
        )
    }

    // ---------- Диаграмма ----------

    @Test
    fun barsAllZero() {
        assertEquals(List(14) { 0f }, ProgressMath.barFractions(List(14) { 0 }))
        assertEquals(emptyList<Float>(), ProgressMath.barFractions(emptyList()))
    }

    @Test
    fun barsOneHugeDayKeepsSmallDaysVisible() {
        val f = ProgressMath.barFractions(listOf(0, 1000, 5, 0, 500), minVisible = 0.04f)
        assertEquals(0f, f[0], 0f)
        assertEquals(1f, f[1], 0f)
        assertEquals(0.04f, f[2], 1e-6f) // 5/1000 меньше порога — поднят до видимого
        assertEquals(0f, f[3], 0f)
        assertEquals(0.5f, f[4], 1e-6f)
    }

    @Test
    fun lastDaysEndWithToday() {
        val today = day(2026, 11, 12)
        val days = ProgressMath.lastDays(today, 14)
        assertEquals(14, days.size)
        assertEquals(today, days.last())
        assertEquals(day(2026, 10, 30), days.first())
    }

    @Test
    fun levelProgress() {
        assertEquals(150L to 0.25f, ProgressMath.levelProgress(100, 50, 250).let { it.first to it.second })
        assertEquals(0L, ProgressMath.levelProgress(300, 50, 250).first)
    }

    // ---------- Итог дня ----------

    private fun set(id: Long, time: Long, ex: String, reps: Int, earned: Long, runId: Long? = null, weight: Int? = null) =
        SetEntry(id, time, 0, ex, ex, weight, reps, 0, earned, earned / 10_000, runId)

    private fun run(
        id: Long, complexId: String, weight: Int? = null, durationMs: Long = 0,
        rounds: Int? = null, extra: Int? = null, totalReps: Int = 0, earned: Long = 0, completed: Boolean = true,
    ) = ComplexRun(id, id * 1000, 0, complexId, weight, durationMs, rounds, extra, totalReps, earned, earned / 10_000, completed)

    @Test
    fun dayDigestDoesNotDoubleCountComplexSets() {
        val sets = listOf(
            set(3, 3_000, "pushups", 15, 450_000),
            set(1, 1_000, "pullups", 5, 300_000, runId = 7),
            set(2, 1_000, "pushups", 10, 300_000, runId = 7),
        )
        val cindy = run(7, Cindy.ID, rounds = 1, extra = 0, totalReps = 15, earned = 600_000)
        val d = ProgressMath.digestDay(sets, listOf(cindy))
        assertEquals(3, d.sets)
        assertEquals(30, d.reps) // 15 + 5 + 10, без повторного totalReps комплекса
        assertEquals(1_050_000L, d.earnedMs) // награда комплекса уже разложена по его подходам
        assertEquals(listOf(3L), d.looseSets.map { it.id })
        assertEquals(listOf(cindy), d.runs)
        assertEquals(
            listOf(ExerciseTotal("pullups", "pullups", null, 1, 5), ExerciseTotal("pushups", "pushups", null, 2, 25)),
            d.totals,
        )
        assertFalse(d.isEmpty)
    }

    @Test
    fun dayDigestCountsRunWithoutSetsOnce() {
        val orphan = run(9, Minotaur.ID, weight = 24, totalReps = 275, earned = 180 * 60_000L)
        val d = ProgressMath.digestDay(emptyList(), listOf(orphan))
        assertEquals(0, d.sets)
        assertEquals(275, d.reps)
        assertEquals(180 * 60_000L, d.earnedMs)
        assertFalse(d.isEmpty)
    }

    @Test
    fun emptyDay() {
        val d = ProgressMath.digestDay(emptyList(), emptyList())
        assertTrue(d.isEmpty)
        assertEquals(0, d.reps)
        assertNull(d.looseSets.firstOrNull())
    }
}
