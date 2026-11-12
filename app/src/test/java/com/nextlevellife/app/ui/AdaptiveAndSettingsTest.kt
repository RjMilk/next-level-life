package com.nextlevellife.app.ui

import com.nextlevellife.app.block.BlockReason
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class AdaptiveAndSettingsTest {

    // ---------- Классы ширины ----------

    @Test
    fun widthClassBoundaries() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(360f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599.9f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(904f))
    }

    @Test
    fun widthClassLayoutHelpers() {
        assertFalse(WidthClass.COMPACT.useRail)
        assertTrue(WidthClass.MEDIUM.useRail)
        assertTrue(WidthClass.EXPANDED.useRail)
        assertEquals(16f, WidthClass.COMPACT.outerPadding.value)
        assertEquals(16f, WidthClass.MEDIUM.outerPadding.value)
        assertEquals(24f, WidthClass.EXPANDED.outerPadding.value)
        assertEquals(WidthClass.EXPANDED.outerPadding, WidthClass.EXPANDED.gutter)
        assertEquals(1, WidthClass.COMPACT.gridColumns)
        assertEquals(2, WidthClass.MEDIUM.gridColumns)
        assertEquals(2, WidthClass.EXPANDED.gridColumns)
    }

    // ---------- Тексты экрана блокировки ----------

    @Test
    fun blockTitles() {
        assertEquals("Баланс закончился", BlockText.title(BlockReason.BALANCE))
        assertEquals("Дневной лимит исчерпан", BlockText.title(BlockReason.LIMIT))
        assertEquals("Ночной режим", BlockText.title(BlockReason.NIGHT))
    }

    @Test
    fun limitMessageAgreesMinutes() {
        val balance = (2 * 60 + 15) * 60_000L
        assertEquals(
            "Использовано 120 из 120 минут. Доступ возобновится в 00:00. Баланс 2 ч 15 мин сохранён.",
            BlockText.message(BlockReason.LIMIT, balance, 120, 7 * 60, 0),
        )
        assertEquals(
            "Использовано 21 из 21 минуты. Доступ возобновится в 00:00. Баланс 2 ч 15 мин сохранён.",
            BlockText.message(BlockReason.LIMIT, balance, 21, 7 * 60, 0),
        )
        assertEquals("минуты", BlockText.minutesGenitive(1))
        assertEquals("минут", BlockText.minutesGenitive(11))
        assertEquals("минут", BlockText.minutesGenitive(90))
        assertEquals("минут", BlockText.minutesGenitive(122))
    }

    @Test
    fun genitiveMinutesForHundredsEndingInOne() {
        // «из 181 минуты», но «из 111 минут».
        assertEquals("минуты", BlockText.minutesGenitive(181))
        assertEquals("минут", BlockText.minutesGenitive(111))
    }

    @Test
    fun nightMessageAndZeroBalance() {
        assertEquals(
            "Приложение доступно с 07:00. Баланс 45 мин сохранён.",
            BlockText.message(BlockReason.NIGHT, 45 * 60_000L, 120, 7 * 60, 0),
        )
        // Пустой баланс не упоминается.
        assertEquals(
            "Приложение доступно с 06:30.",
            BlockText.message(BlockReason.NIGHT, 0, 120, 6 * 60 + 30, 0),
        )
        assertTrue(
            BlockText.message(BlockReason.BALANCE, 0, 120, 7 * 60, 5 * 60_000L)
                .endsWith("10 отжиманий = 5 мин.")
        )
    }

    // ---------- Расход и лимит ----------

    @Test
    fun usageText() {
        assertEquals("35 из 120 мин", SettingsMath.usageText(35 * 60_000L + 59_999, 120))
        assertEquals("0 из 90 мин", SettingsMath.usageText(0, 90))
        assertEquals("35 мин", SettingsMath.usageText(35 * 60_000L, 0))
        assertEquals("Сегодня использовано: 35 из 120 мин", SettingsMath.usedTodayLine(35 * 60_000L, 120))
        assertEquals("Сегодня потрачено 35 из 120 мин", SettingsMath.spentTodayLine(35 * 60_000L, 120))
        // Отрицательное (сбой чтения) не показывается как минус.
        assertEquals("0 из 120 мин", SettingsMath.usageText(-5_000, 120))
    }

    @Test
    fun usageFraction() {
        assertEquals(0.5f, SettingsMath.usageFraction(60 * 60_000L, 120), 1e-6f)
        assertEquals(1f, SettingsMath.usageFraction(200 * 60_000L, 120), 1e-6f)
        assertEquals(0f, SettingsMath.usageFraction(60 * 60_000L, 0), 1e-6f)
    }

    // ---------- Ночной режим ----------

    @Test
    fun nightClockFormat() {
        assertEquals("23:00", SettingsMath.clock(23 * 60))
        assertEquals("07:05", SettingsMath.clock(7 * 60 + 5))
        assertEquals("00:00", SettingsMath.clock(0))
        assertEquals("Ночной режим с 23:00 до 07:00", SettingsMath.nightLine(23 * 60, 7 * 60))
    }

    @Test
    fun nightPickerValidation() {
        assertEquals(0, SettingsMath.minuteOfDay(0, 0))
        assertEquals(23 * 60 + 59, SettingsMath.minuteOfDay(23, 59))
        assertNull(SettingsMath.minuteOfDay(24, 0))
        assertNull(SettingsMath.minuteOfDay(-1, 0))
        assertNull(SettingsMath.minuteOfDay(7, 60))
        assertNull(SettingsMath.nightProblem(23 * 60, 7 * 60))
        assertTrue(SettingsMath.nightProblem(7 * 60, 7 * 60)!!.contains("совпадают"))
    }

    // ---------- Резервная копия ----------

    @Test
    fun backupFileName() {
        assertEquals("next-level-life-2026-11-12.zip", SettingsMath.backupFileName(LocalDate.of(2026, 11, 12)))
        assertEquals("next-level-life-2027-01-05.zip", SettingsMath.backupFileName(LocalDate.of(2027, 1, 5)))
    }

    @Test
    fun lastBackupLine() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 11, 13, 9, 0).toInstant(zone).toEpochMilli()
        val at = LocalDateTime.of(2026, 11, 12, 22, 40).toInstant(zone).toEpochMilli()
        assertEquals("Последняя копия: 12 ноября, 22:40", SettingsMath.lastBackupLine(at, now, zone))
        val lastYear = LocalDateTime.of(2025, 12, 31, 8, 5).toInstant(zone).toEpochMilli()
        assertEquals("Последняя копия: 31 декабря 2025, 08:05", SettingsMath.lastBackupLine(lastYear, now, zone))
        assertEquals("Последняя копия: ещё не создавалась", SettingsMath.lastBackupLine(null, now, zone))
    }

    // ---------- Карточка «Сегодня» ----------

    private fun set(id: Long, exerciseId: String, name: String, reps: Int, runId: Long? = null) =
        SetEntry(id, id * 1000, 0, exerciseId, name, null, reps, 0, 0, 0, runId)

    @Test
    fun truncateActivities() {
        val lines = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b") to 3, ProgressMath.truncate(lines))
        assertEquals("и ещё 3", ProgressMath.moreLine(3))
        assertEquals(listOf("a") to 0, ProgressMath.truncate(listOf("a")))
        assertNull(ProgressMath.moreLine(0))
        assertEquals(emptyList<String>() to 0, ProgressMath.truncate(emptyList()))
        assertEquals(listOf("a", "b") to 0, ProgressMath.truncate(listOf("a", "b")))
    }

    @Test
    fun todayActivitiesShowComplexOnceAndLooseSetsByExercise() {
        val run = ComplexRun(
            id = 7, timeMs = 0, day = 0, complexId = Minotaur.ID, weightKg = 16, durationMs = (24 * 60 + 35) * 1000L,
            rounds = null, extraReps = null, totalReps = 275, earnedMs = 0, xp = 0, completed = true,
        )
        val sets = listOf(
            set(1, "swings", "Мах гирей", 55, runId = 7),
            set(2, "pushups", "Отжимания", 15),
            set(3, "squats", "Приседания", 20),
            set(4, "pushups", "Отжимания", 10),
        )
        val digest = ProgressMath.digestDay(sets, listOf(run))
        val lines = ProgressMath.todayActivities(digest)
        assertEquals(
            listOf("Минотавр 16 кг — 24:35", "Отжимания — 25 повт.", "Приседания — 20 повт."),
            lines,
        )
        val (shown, rest) = ProgressMath.truncate(lines)
        assertEquals(2, shown.size)
        assertEquals("и ещё 1", ProgressMath.moreLine(rest))
    }

    // ---------- Строки «название | показатели» и баланс ----------

    @Test
    fun cellsSplitLabelAndNumbers() {
        val zone = ZoneOffset.UTC
        val t = LocalDateTime.of(2026, 11, 12, 21, 0).toInstant(zone).toEpochMilli()
        val s = SetEntry(1, t, 0, "pushups", "Отжимания", null, 15, 0, 7 * 60_000L + 30_000, 0, null)
        assertEquals(ProgressMath.Cells("21:00  Отжимания", "15 повт. · +7,5 мин"), ProgressMath.setCells(s, zone))
        val total = com.nextlevellife.app.data.ExerciseTotal("swings", "Мах гирей", 24, 2, 35)
        assertEquals(ProgressMath.Cells("Мах гирей 24 кг", "2 подхода · 35 повт."), ProgressMath.totalCells(total))
        val day = LocalDate.of(2026, 11, 12).toEpochDay()
        assertEquals(ProgressMath.Cells("12 ноя · 24 кг", "2 подхода · 35 повт."), ProgressMath.historyCells(day, total))
    }

    @Test
    fun balanceParts() {
        assertEquals(listOf("2" to "ч", "15" to "мин"), ProgressMath.balanceParts((2 * 60 + 15) * 60_000L + 30_000))
        assertEquals(listOf("15" to "мин"), ProgressMath.balanceParts(15 * 60_000L))
        assertEquals(listOf("40" to "сек"), ProgressMath.balanceParts(40_000))
        assertEquals(listOf("0" to "сек"), ProgressMath.balanceParts(-1))
    }
}
