package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.DiaryMath
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.WorkoutMath
import com.nextlevellife.app.pose.HoldSpeech
import com.nextlevellife.app.pose.RepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Планка: цена, единицы в дневнике и строках «Прогресса», табло и голос удержания. */
class PlankTest {

    // ---------- Цена и запись ----------

    @Test
    fun plankPrice() {
        val plank = Exercises.plank
        assertTrue(plank.hold)
        assertFalse(plank.kettlebell)
        assertTrue(plank in Exercises.bodyweight)
        assertEquals(plank, Exercises.byId("plank"))
        // 5 секунд за секунду: минута = 5 минут = 10 отжиманий.
        assertEquals(5_000L, Exercises.holdRewardMs(plank, 1_000))
        assertEquals(300_000L, Exercises.holdRewardMs(plank, 60_000))
        assertEquals(Exercises.rewardMs(Exercises.pushups, 10, Weights.DEFAULT), Exercises.holdRewardMs(plank, 60_000))
        // Платятся только целые секунды.
        assertEquals(400_000L, Exercises.holdRewardMs(plank, 80_999))
        assertEquals(0L, Exercises.holdRewardMs(plank, 999))
        assertEquals(0L, Exercises.holdRewardMs(plank, -5_000))
        // Опыт — как у всех: очко за 10 секунд заработанного.
        assertEquals(40L, Exercises.xp(Exercises.holdRewardMs(plank, 80_000)))
        assertEquals("1 сек = 5 сек", exercisePriceLine(plank))
        assertEquals("1 повт = 30 сек", exercisePriceLine(Exercises.pushups))
    }

    @Test
    fun creditedHoldIsWholeSecondsWithinDuration() {
        assertEquals(80_000L, WorkoutMath.creditedHoldMs(80_700, 120_000))
        // Засчитанное не больше полного времени подхода.
        assertEquals(50_000L, WorkoutMath.creditedHoldMs(80_000, 50_400))
        // Длительность не известна — ограничения нет.
        assertEquals(80_000L, WorkoutMath.creditedHoldMs(80_000, 0))
        assertEquals(0L, WorkoutMath.creditedHoldMs(-1, 10_000))
    }

    @Test
    fun migrationAddsMeasurementColumns() {
        assertEquals(6, Diary.SCHEMA_VERSION)
        val sql = Diary.MIGRATIONS[5].joinToString("\n")
        assertTrue("ALTER TABLE sets ADD COLUMN measurement_type TEXT NOT NULL DEFAULT 'REPS'" in sql)
        assertTrue("ALTER TABLE sets ADD COLUMN held_ms INTEGER NOT NULL DEFAULT 0" in sql)
        assertEquals(Measurement.HOLD, Measurement.parse("HOLD"))
        assertEquals(Measurement.REPS, Measurement.parse("REPS"))
        assertEquals(Measurement.REPS, Measurement.parse("SOMETHING_NEW"))
        assertEquals(Measurement.REPS, Measurement.parse(null))
    }

    // ---------- Формат времени и строк ----------

    @Test
    fun holdTimeFormat() {
        assertEquals("0:00", ProgressMath.formatHold(0))
        assertEquals("0:09", ProgressMath.formatHold(9_999))
        assertEquals("1:20", ProgressMath.formatHold(80_000))
        assertEquals("12:30", ProgressMath.formatHold(750_000))
        assertEquals("1:05:00", ProgressMath.formatHold(65 * 60_000L))
        assertEquals("0:00", ProgressMath.formatHold(-1_000))
    }

    @Test
    fun summaryLineWithUnits() {
        // Без планки — как раньше.
        assertEquals("3 подхода · 35 повторений · +22 мин", ProgressMath.summaryLine(3, 35, 22 * 60_000L))
        assertEquals(
            "4 подхода · 35 повторений · 2:40 планки · +20 мин",
            ProgressMath.summaryLine(4, 35, 20 * 60_000L, heldMs = 160_000),
        )
        // Только планка — повторений не пишем.
        assertEquals("1 подход · 1:20 планки · +6,7 мин", ProgressMath.summaryLine(1, 0, 400_000, heldMs = 80_000))
        assertEquals("0 подходов · 0 повторений · +0 мин", ProgressMath.summaryLine(0, 0, 0))
    }

    @Test
    fun setAndTotalLinesForHold() {
        val zone = ZoneOffset.UTC
        val t = LocalDateTime.of(2026, 11, 12, 21, 0).toInstant(zone).toEpochMilli()
        val plankSet = hold(1, t, held = 80_000, earned = 400_000)
        assertEquals("21:00 · Планка · 1:20 · +6,7 мин", ProgressMath.setLine(plankSet, zone))
        assertEquals(ProgressMath.Cells("21:00  Планка", "1:20 · +6,7 мин"), ProgressMath.setCells(plankSet, zone))

        val total = ExerciseTotal("plank", "Планка", null, 2, 0, heldMs = 160_000, measurement = Measurement.HOLD)
        assertEquals("Планка — 2 подхода, 2:40", ProgressMath.totalLine(total))
        assertEquals("Планка — 2:40", ProgressMath.totalRepsLine(total))
        assertEquals(ProgressMath.Cells("Планка", "2 подхода · 2:40"), ProgressMath.totalCells(total))
        assertEquals("2 подхода · 2:40", ProgressMath.historyCells(0, total).value)

        // Повторения не изменились.
        assertEquals("Отжимания — 2 подхода, 35 повт.", ProgressMath.totalLine(ExerciseTotal("pushups", "Отжимания", null, 2, 35)))
    }

    @Test
    fun resultAndPriceLines() {
        assertEquals("1:20 засчитано · +6 мин 40 сек", HoldMath.resultLine(80_000, Exercises.holdRewardMs(Exercises.plank, 80_000)))
        assertEquals("1 сек = 5 сек", HoldMath.priceLine(Exercises.plank))
    }

    // ---------- Итоги дня и рекорды ----------

    private fun reps(id: Long, time: Long, ex: String, n: Int, earned: Long, runId: Long? = null) =
        SetEntry(id, time, 0, ex, ex, null, n, 0, earned, earned / 10_000, runId)

    private fun hold(id: Long, time: Long, held: Long, earned: Long = held * 5, day: Long = 0, runId: Long? = null) =
        SetEntry(
            id, time, day, "plank", "Планка", null, 0, held + 5_000, earned, earned / 10_000, runId,
            measurement = Measurement.HOLD, heldMs = held,
        )

    @Test
    fun dayTotalsKeepUnitsApart() {
        val sets = listOf(
            reps(1, 1_000, "pushups", 20, 600_000),
            hold(2, 2_000, held = 80_000),
            reps(3, 3_000, "pushups", 15, 450_000),
            hold(4, 4_000, held = 80_000),
        )
        val totals = DiaryMath.totals(sets)
        assertEquals(
            listOf(
                ExerciseTotal("pushups", "pushups", null, 2, 35),
                ExerciseTotal("plank", "Планка", null, 2, 0, heldMs = 160_000, measurement = Measurement.HOLD),
            ),
            totals,
        )
        val d = ProgressMath.digestDay(sets, emptyList())
        assertEquals(4, d.sets)
        assertEquals(35, d.reps) // секунды планки в повторения не попадают
        assertEquals(160_000L, d.heldMs)
        assertEquals(1_050_000L + 800_000L, d.earnedMs)
        assertEquals("4 подхода · 35 повторений · 2:40 планки · +30,8 мин", d.summary)
        assertEquals(listOf("pushups — 35 повт.", "Планка — 2:40"), ProgressMath.todayActivities(d))
    }

    @Test
    fun holdRecords() {
        val sets = listOf(
            hold(1, 1_000, held = 60_000, day = 10),
            reps(2, 1_500, "pushups", 30, 900_000),
            hold(3, 2_000, held = 80_000, day = 11),
            hold(4, 3_000, held = 610_000, day = 12),
            reps(5, 3_500, "pushups", 20, 600_000),
        )
        val records = DiaryMath.records(sets).associateBy { it.exerciseId }
        val plank = records.getValue("plank")
        assertEquals(Measurement.HOLD, plank.measurement)
        assertEquals(610_000L, plank.bestHeldMs)
        assertEquals(750_000L, plank.totalHeldMs)
        assertEquals(3, plank.sets)
        assertEquals(12L, plank.lastDay)
        assertNull(plank.bestReps)
        assertEquals(0L, plank.totalReps)
        assertEquals("Планка — лучший подход 10:10, всего 12:30", ProgressMath.recordLine(plank))
        assertEquals("Лучший подход: 10:10", ProgressMath.recordBestLine(plank))
        assertEquals("12:30" to "всего", ProgressMath.recordTotal(plank))

        val pushups = records.getValue("pushups")
        assertEquals(Measurement.REPS, pushups.measurement)
        assertEquals(30, pushups.bestReps)
        assertEquals(50L, pushups.totalReps)
        assertNull(pushups.bestHeldMs)
        assertEquals("Лучший подход: 30 повт.", ProgressMath.recordBestLine(pushups))
        assertEquals("50" to "повторений всего", ProgressMath.recordTotal(pushups))
    }

    @Test
    fun repRecordsMatchOldRules() {
        // Лучший — больше повторений, при равенстве тяжелее гиря; комплексы — только во «всего».
        fun swing(id: Long, n: Int, w: Int, runId: Long? = null) =
            SetEntry(id, id * 1000, id, "swings", "Мах гирей", w, n, 0, 0, 0, runId)
        val r = DiaryMath.records(listOf(swing(1, 20, 16), swing(2, 20, 24), swing(3, 55, 32, runId = 9), swing(4, 15, 32)))
            .single()
        assertEquals(20, r.bestReps)
        assertEquals(24, r.bestWeightKg)
        assertEquals(110L, r.totalReps)
        assertEquals(4, r.sets)
        assertEquals(4L, r.lastDay)
        // Только в комплексах — лучшего отдельного подхода нет.
        val onlyRuns = DiaryMath.records(listOf(swing(1, 55, 16, runId = 1))).single()
        assertNull(onlyRuns.bestReps)
        assertEquals("Лучший подход: —", ProgressMath.recordBestLine(onlyRuns))
        assertEquals(
            "Лучший подход: —",
            ProgressMath.recordBestLine(ExerciseRecord("plank", "Планка", null, null, 0, 1, 0, Measurement.HOLD, null, 0)),
        )
    }

    // ---------- Табло удержания ----------

    @Test
    fun creditedTimeSurvivesRecreation() {
        // До пересоздания движок засчитал 50 с — их сохранили.
        val before = HoldMath.board(0L, RepState(heldMs = 50_400, currentSegmentMs = 50_400, holding = true, inPosition = true))
        assertEquals(50_000L, before.creditedMs)
        // После пересоздания новый движок начинает с нуля, засчитанное прибавляется.
        assertEquals(50_000L, HoldMath.board(before.creditedMs, RepState()).creditedMs)
        val after = HoldMath.board(before.creditedMs, RepState(heldMs = 20_000, currentSegmentMs = 20_000, holding = true, inPosition = true))
        assertEquals(70_000L, after.creditedMs)
        assertEquals(70_000L, after.shownMs)
        assertEquals(70_000L, HoldMath.total(50_000, 20_000))
        assertEquals(20_000L, HoldMath.total(-5, 20_000))
    }

    @Test
    fun pendingSegmentIsShownButNotCredited() {
        // Засчитано 1:20, идёт новый отрезок 5 с — на табло 1:25 приглушённо.
        val s = RepState(heldMs = 0, currentSegmentMs = 5_300, pendingMs = 5_300, holding = true, inPosition = true)
        val b = HoldMath.board(80_000, s)
        assertEquals(85_000L, b.shownMs)
        assertEquals(80_000L, b.creditedMs)
        assertTrue(b.pending)
        assertEquals(HoldTone.PENDING, b.tone)
        assertEquals("засчитается с 10 секунд", HoldMath.caption(b, shortNotice = false))
        assertEquals("Меньше 10 секунд — не засчитано", HoldMath.caption(b, shortNotice = true))

        // С 10 секунд отрезок засчитан: яркое время и «засчитано».
        val counted = HoldMath.board(80_000, RepState(heldMs = 10_000, currentSegmentMs = 10_000, holding = true, inPosition = true))
        assertFalse(counted.pending)
        assertEquals(HoldTone.LIVE, counted.tone)
        assertEquals("засчитано 1:30", HoldMath.caption(counted, shortNotice = false))

        // Удержания нет — приглушено как «ожидание», подпись — засчитанное.
        val idle = HoldMath.board(80_000, RepState())
        assertEquals(HoldTone.IDLE, idle.tone)
        assertEquals("засчитано 1:20", HoldMath.caption(idle, shortNotice = false))
        // Потолок 3 минуты: поза держится, но время стоит — не «идёт».
        val max = HoldMath.board(0, RepState(heldMs = 180_000, currentSegmentMs = 180_000, holding = true, inPosition = true))
        assertEquals(HoldTone.IDLE, max.tone)
    }

    @Test
    fun segmentEventsAndVoiceMarks() {
        val out = RepState()
        val entering = RepState(inPosition = true, holding = true, currentSegmentMs = 500, pendingMs = 500)
        val short = RepState(inPosition = true, currentSegmentMs = 7_000, pendingMs = 7_000)
        val long = RepState(inPosition = true, currentSegmentMs = 25_000, heldMs = 25_000)
        assertEquals(HoldMath.SegmentEvent.START, HoldMath.segmentEvent(out, entering))
        assertEquals(HoldMath.SegmentEvent.STOP_SHORT, HoldMath.segmentEvent(short, out))
        assertEquals(HoldMath.SegmentEvent.STOP, HoldMath.segmentEvent(long, out.copy(heldMs = 25_000)))
        assertNull(HoldMath.segmentEvent(entering, short))
        assertNull(HoldMath.segmentEvent(out, out))

        assertNull(HoldMath.tenSecondMark(0, 9_999))
        assertEquals(10, HoldMath.tenSecondMark(9_950, 10_010))
        assertNull(HoldMath.tenSecondMark(10_010, 10_100))
        assertEquals(20, HoldMath.tenSecondMark(19_990, 20_000))
        // Конец отрезка (время → 0) ничего не называет.
        assertNull(HoldMath.tenSecondMark(25_000, 0))
        assertEquals(180, HoldMath.tenSecondMark(179_970, 180_000))

        assertTrue(HoldMath.maxReached(179_970, 180_000))
        assertFalse(HoldMath.maxReached(180_000, 180_000))
        assertFalse(HoldMath.maxReached(100_000, 100_100))
    }

    @Test
    fun statusLine() {
        assertEquals("Ляг в планку боком к телефону" to false, HoldMath.status(RepState()))
        assertEquals(
            "Опустись на колени и снова прими планку" to false,
            HoldMath.status(RepState(tracking = true, hint = "Опустись на колени и снова прими планку")),
        )
        assertEquals("Держи планку" to true, HoldMath.status(RepState(inPosition = true, holding = true, currentSegmentMs = 3_000)))
        assertEquals("Таз выше" to false, HoldMath.status(RepState(inPosition = true, hint = "Таз выше", currentSegmentMs = 3_000)))
        assertEquals(
            HoldSpeech.MAX to false,
            HoldMath.status(RepState(inPosition = true, holding = true, currentSegmentMs = 180_000)),
        )
    }

    @Test
    fun secondsInWords() {
        assertEquals("десять", HoldSpeech.seconds(10))
        assertEquals("двадцать", HoldSpeech.seconds(20))
        assertEquals("девяносто", HoldSpeech.seconds(90))
        assertEquals("сто", HoldSpeech.seconds(100))
        assertEquals("сто двадцать", HoldSpeech.seconds(120))
        assertEquals("сто восемьдесят", HoldSpeech.seconds(180))
        assertEquals("15", HoldSpeech.seconds(15))
        assertEquals("1000", HoldSpeech.seconds(1000))
    }

    @Test
    fun holdTimerFitsLikeRepCounter() {
        // Узкий телефон: 4 знака при 112 sp не влезают в ~292 dp — уменьшается до 104.
        assertEquals(104, Scoreboard.holdSp(WidthClass.COMPACT, 292f, 400f, 4))
        // Места вдоволь — крупнейший размер класса.
        assertEquals(160, Scoreboard.holdSp(WidthClass.EXPANDED, 800f, 600f, 4))
        assertEquals(128, Scoreboard.holdSp(WidthClass.MEDIUM, 800f, 600f, 4))
        // Панель 300 dp на Fold: «12:30» — 5 знаков.
        val sp = Scoreboard.holdSp(WidthClass.EXPANDED, 300f, 600f, 5)
        assertTrue(5 * Scoreboard.DIGIT_EM * sp + 2 * Scoreboard.BLOCK_PAD_H_DP <= 300f)
        // Совсем тесно — не меньше нижней границы.
        assertEquals(Scoreboard.MIN_HOLD_SP, Scoreboard.holdSp(WidthClass.COMPACT, 100f, 100f, 4))
        // Меньше 4 знаков не бывает: 0:59 → 1:00 не меняет размер.
        assertEquals(Scoreboard.holdSp(WidthClass.COMPACT, 292f, 400f, 4), Scoreboard.holdSp(WidthClass.COMPACT, 292f, 400f, 1))
    }
}
