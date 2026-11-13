package com.nextlevellife.app.sleep

import com.nextlevellife.app.data.BackupMath
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.ui.ProgressMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Подписи ночного перерыва в интерфейсе, «пустой день» и схема базы. */
class SleepPresentationTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val wake = LocalDate.of(2026, 11, 13).toEpochDay()

    private fun t(dayOffset: Int, h: Int, m: Int) =
        LocalDate.ofEpochDay(wake + dayOffset).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun night(
        status: NightStatus = NightStatus.OK,
        interruptions: Int = 2,
        interruptionMs: Long = 5 * 60_000L,
        phoneFree: Boolean = false,
        final: Boolean = true,
        start: Long? = t(-1, 23, 20),
        end: Long? = t(0, 7, 0),
    ) = SleepNight(
        wakeDay = wake, status = status, startMs = start, endMs = end,
        interruptionMs = interruptionMs, interruptionCount = interruptions, phoneFree = phoneFree,
        nightStartMin = 23 * 60, nightEndMin = 7 * 60, zoneId = zone.id, final = final,
    )

    @Test
    fun okNightLines() {
        val n = night()
        assertEquals(7 * 60 + 35L, n.durationMs!! / 60_000)
        val (main, note) = ProgressMath.nightCells(n)
        assertEquals("7 ч 35 мин · 23:20 → 07:00", main)
        assertEquals("2 коротких обращения · по этому телефону", note)
        assertEquals("Ночь: 7 ч 35 мин", ProgressMath.nightTodayLine(n))
        assertEquals("Ночь: 7 ч 35 мин 🌙", ProgressMath.nightTodayLine(n.copy(phoneFree = true)))
        assertEquals(n.durationMs, ProgressMath.breakChartValue(n))
    }

    @Test
    fun provisionalPartialAndUnknown() {
        assertEquals(
            "предварительно · по этому телефону",
            ProgressMath.nightCells(night(interruptions = 0, interruptionMs = 0, final = false)).second,
        )
        val partial = night(status = NightStatus.PARTIAL, interruptions = 0, interruptionMs = 0, start = t(-1, 20, 0), end = t(0, 5, 10))
        assertEquals("неполные данные" to "не меньше 9 ч 10 мин · по этому телефону", ProgressMath.nightCells(partial))
        assertNull(ProgressMath.nightTodayLine(partial))
        assertNull("неполная ночь на диаграмме — разрыв", ProgressMath.breakChartValue(partial))
        val unknown = night(status = NightStatus.UNKNOWN, interruptions = 0, interruptionMs = 0, start = null, end = null)
        assertEquals("нет данных" to "по этому телефону", ProgressMath.nightCells(unknown))
        assertNull(ProgressMath.nightTodayLine(unknown))
        assertNull(ProgressMath.breakChartValue(unknown))
        assertNull(ProgressMath.breakChartValue(null))
    }

    @Test
    fun wordingNeverMentionsSleep() {
        val texts = listOf(
            ProgressMath.nightCells(night()),
            ProgressMath.nightCells(night(status = NightStatus.PARTIAL)),
            ProgressMath.nightCells(night(status = NightStatus.UNKNOWN, start = null, end = null)),
        ).flatMap { listOf(it.first, it.second) } +
            listOfNotNull(ProgressMath.nightTodayLine(night(phoneFree = true))) +
            ProgressMath.chartSummary(ProgressMath.ChartMetric.BREAK_H, 14, listOf(1L, null), 0) +
            ProgressMath.ChartMetric.BREAK_H.label
        for (s in texts) {
            val lower = s.lowercase()
            for (word in listOf("сон", "засн", "сна", "просну")) assertFalse("«$s» содержит «$word»", word in lower)
        }
    }

    @Test
    fun provisionalNightIsMarkedOnHomeAndInChart() {
        val provisional = night(final = false)
        assertEquals("Ночь: 7 ч 35 мин · предварительно", ProgressMath.nightTodayLine(provisional))
        assertEquals("Ночь: 7 ч 35 мин 🌙 · предварительно", ProgressMath.nightTodayLine(provisional.copy(phoneFree = true)))
        assertTrue(ProgressMath.breakChartProvisional(listOf(night(), provisional)))
        assertFalse(ProgressMath.breakChartProvisional(listOf(night(), null)))
        // Предварительная ночь без столбика (нет данных) подпись не меняет.
        assertFalse(ProgressMath.breakChartProvisional(listOf(night(status = NightStatus.UNKNOWN, final = false, start = null, end = null))))
        assertEquals(
            "Ночной перерыв в среднем 7 ч · 1 ночь · предварительно · по этому телефону",
            ProgressMath.chartSummary(ProgressMath.ChartMetric.BREAK_H, 3, listOf(7 * 3_600_000L, null, null), 0, breakProvisional = true),
        )
    }

    @Test
    fun interruptionsWording() {
        assertNull(ProgressMath.interruptionsLine(0))
        assertEquals("1 короткое обращение", ProgressMath.interruptionsLine(1))
        assertEquals("3 коротких обращения", ProgressMath.interruptionsLine(3))
        assertEquals("5 коротких обращений", ProgressMath.interruptionsLine(5))
        assertEquals("45 мин", ProgressMath.breakDuration(45 * 60_000L))
        assertEquals("8 ч", ProgressMath.breakDuration(8 * 3_600_000L))
    }

    @Test
    fun breakChartHasGapsNotZeros() {
        val values = ProgressMath.chartValues(
            ProgressMath.ChartMetric.BREAK_H, listOf(0, 0, 0), listOf(null, null, null), listOf(0, 0, 0),
            breakMs = listOf(7 * 3_600_000L, null, 6 * 3_600_000L),
        )
        assertEquals(listOf(7 * 3_600_000L, null, 6 * 3_600_000L), values)
        val fractions = ProgressMath.barFractionsNullable(values)
        assertEquals(1f, fractions[0]!!, 0.001f)
        assertNull(fractions[1])
        assertEquals(6f / 7f, fractions[2]!!, 0.001f)
        assertEquals(listOf(null, null), ProgressMath.barFractionsNullable(listOf(null, null)))
        assertEquals(
            "Ночной перерыв в среднем 6 ч 30 мин · 2 ночи · по этому телефону",
            ProgressMath.chartSummary(ProgressMath.ChartMetric.BREAK_H, 3, values, 0),
        )
        assertEquals(
            "Ночной перерыв: нет данных · по этому телефону",
            ProgressMath.chartSummary(ProgressMath.ChartMetric.BREAK_H, 3, listOf(null, null, null), 0),
        )
        assertTrue(ProgressMath.barDescription(ProgressMath.ChartMetric.BREAK_H, wake, null).endsWith("нет данных"))
    }

    @Test
    fun dayWithOnlyANightIsNotEmpty() {
        val d = ProgressMath.digestDay(emptyList(), emptyList(), night = night())
        assertTrue(d.hasNight)
        assertFalse(d.isEmpty)
        assertFalse(d.hasWorkout)
        // «Нет данных» день не наполняет.
        val unknown = ProgressMath.digestDay(emptyList(), emptyList(), night = night(status = NightStatus.UNKNOWN, start = null, end = null))
        assertTrue(unknown.isEmpty)
        assertFalse(ProgressMath.digestDay(emptyList(), emptyList(), night = night(status = NightStatus.PARTIAL)).isEmpty)
    }

    @Test
    fun schemaHasSleepTables() {
        // Таблицы ночного перерыва — шаг 4 → 5; дальше схема растёт (5 → 6 — подходы-удержания).
        assertTrue(Diary.SCHEMA_VERSION >= 5)
        val sql = Diary.MIGRATIONS[4].joinToString("\n")
        for (table in BackupMath.SLEEP_TABLES) assertTrue(table, "CREATE TABLE $table" in sql)
        assertTrue("dedup_key TEXT NOT NULL UNIQUE" in sql)
        assertTrue("PRIMARY KEY (device_id, wake_day)" in sql)
        // Копия новой схемы обязана содержать таблицы ночного перерыва; старая — догонит миграцией.
        assertTrue(BackupMath.requiredTables(5).containsAll(BackupMath.SLEEP_TABLES))
        assertFalse(BackupMath.requiredTables(4).contains("sleep_nights"))
        assertTrue(BackupMath.requiredTables(4).containsAll(BackupMath.REQUIRED_TABLES))
    }
}
