package com.nextlevellife.app.data

import com.nextlevellife.app.health.HealthAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaitamaMathTest {
    private val day = 20_000L

    private var nextId = 1L

    private fun set(
        exerciseId: String, reps: Int, d: Long = day, runId: Long? = null,
        measurement: Measurement = Measurement.REPS, heldMs: Long = 0L,
    ) = SetEntry(nextId++, 0L, d, exerciseId, exerciseId, null, reps, 0L, 0L, 0L, runId, measurement, heldMs)

    private fun activity(d: Long = day, runM: Long = 0L, runs: Int = if (runM > 0) 1 else 0, runsRead: Boolean = true) =
        ActivityDay(d, 5_000L, runM, 0L, 0L, runs, emptyList(), 0L, 0L, runsRead)

    private fun fullSets(d: Long = day) = listOf(set("pushups", 100, d), set("abs", 100, d), set("squats", 100, d))

    // ---------- Прогресс дня ----------

    @Test
    fun progressSumsAnySetsOfTheDayIncludingComplexes() {
        val sets = listOf(
            set("pushups", 40),
            set("pushups", 23, runId = 5), // подходы Синди тоже идут в сотню
            set("squats", 15, runId = 5),
            set("abs", 30),
            set("plank", 0, measurement = Measurement.HOLD, heldMs = 120_000), // планка не считается
            set("pullups", 10),
            set("pushups", 50, d = day - 1), // вчерашний — не в счёт
        )
        val p = SaitamaMath.dayOf(day, sets, 4_200L)
        assertEquals(63, p.repsOf(SaitamaGoal.PUSHUPS))
        assertEquals(30, p.repsOf(SaitamaGoal.ABS))
        assertEquals(15, p.repsOf(SaitamaGoal.SQUATS))
        assertEquals("63/100", SaitamaMath.valueText(p, SaitamaGoal.PUSHUPS))
        assertEquals("4,2/10 км", SaitamaMath.valueText(p, SaitamaGoal.RUN))
        assertEquals(0.63f, SaitamaMath.fraction(p, SaitamaGoal.PUSHUPS), 0.0001f)
        assertFalse(p.full)
    }

    @Test
    fun extraRepsDoNotCarryOverAndFractionIsCapped() {
        val p = SaitamaMath.dayOf(day, listOf(set("pushups", 180)), null)
        assertEquals(180, p.repsOf(SaitamaGoal.PUSHUPS))
        assertTrue(p.done(SaitamaGoal.PUSHUPS))
        assertEquals(1f, SaitamaMath.fraction(p, SaitamaGoal.PUSHUPS), 0f)
        // Лишнее не уходит на завтра: следующий день с нуля.
        val next = SaitamaMath.days(day, day + 1, listOf(set("pushups", 180)), emptyList()).getValue(day + 1)
        assertEquals(0, next.repsOf(SaitamaGoal.PUSHUPS))
    }

    @Test
    fun fullDayNeedsAllFourGoals() {
        assertTrue(SaitamaMath.dayOf(day, fullSets(), 10_000L).full)
        assertFalse(SaitamaMath.dayOf(day, fullSets(), 9_999L).full)
        assertFalse(SaitamaMath.dayOf(day, fullSets().drop(1), 12_000L).full)
        assertFalse(SaitamaMath.dayOf(day, listOf(set("pushups", 100), set("abs", 99), set("squats", 100)), 12_000L).full)
        val p = SaitamaMath.dayOf(day, fullSets(), 10_500L)
        assertEquals(SaitamaMilestone.entries.toSet(), p.reached)
        assertNull(SaitamaMath.remainingText(p))
    }

    @Test
    fun unknownRunIsNotZeroAndNotFull() {
        val p = SaitamaMath.dayOf(day, fullSets(), null)
        assertFalse(p.runKnown)
        assertFalse(p.full)
        assertNull(SaitamaMath.valueText(p, SaitamaGoal.RUN))
        assertEquals("Сегодня осталось: 10 км бега (бег не подтверждён)", SaitamaMath.remainingText(p))
        assertEquals("Сегодня 100 · 100 · 100 · бег не подтверждён", SaitamaMath.cardLine(p))
        // Для характеристик: без строки активности день не полный.
        assertFalse(SaitamaMath.isFullDay(fullSets(), null))
        assertTrue(SaitamaMath.isFullDay(fullSets(), activity(runM = 10_000L)))
        assertFalse(SaitamaMath.isFullDay(fullSets(), activity(runM = 0L)))
    }

    @Test
    fun runUnknownReasons() {
        val connected = HealthAvailability.CONNECTED
        val notConnected = HealthAvailability.NOT_CONNECTED
        val read = activity(runM = 0L, runsRead = true)
        val stepsOnly = activity(runM = 0L, runsRead = false)
        // Пробежка в строке — подтверждён при любом доступе.
        assertNull(SaitamaMath.runUnknownReason(activity(runM = 4_000L, runsRead = false), notConnected, false))
        // Строка записана с прочитанными тренировками: 0 км — подтверждённый ноль.
        assertNull(SaitamaMath.runUnknownReason(read, connected, true))
        assertNull(SaitamaMath.runUnknownReason(read, null, null))
        // Строка с одними шагами — не «0 км», даже при подключённом Health Connect.
        assertNotNull(SaitamaMath.runUnknownReason(stepsOnly, connected, true))
        assertNotNull(SaitamaMath.runUnknownReason(stepsOnly, null, null))
        // Health Connect отключили после синхронизации: строка не прячет причину.
        assertEquals("Health Connect не подключён", SaitamaMath.runUnknownReason(read, notConnected, false))
        assertEquals("Health Connect не подключён", SaitamaMath.runUnknownReason(stepsOnly, notConnected, false))
        // Тренировки читать нельзя — пробежек и не видно.
        assertEquals("Нет разрешения читать тренировки и дистанцию", SaitamaMath.runUnknownReason(read, connected, false))
        assertEquals("Health Connect не подключён", SaitamaMath.runUnknownReason(null, notConnected, false))
        assertEquals("На этом телефоне нет Health Connect", SaitamaMath.runUnknownReason(null, HealthAvailability.UNAVAILABLE, false))
        assertEquals("Health Connect нужно обновить", SaitamaMath.runUnknownReason(null, HealthAvailability.UPDATE_REQUIRED, false))
        assertNotNull(SaitamaMath.runUnknownReason(null, connected, true))
        assertNotNull(SaitamaMath.runUnknownReason(null, null, null))
    }

    @Test
    fun stepsOnlyRowIsUnknownRunNotZero() {
        // Синхронизация без разрешения на тренировки (или строка до схемы 7): бег не подтверждён.
        assertNull(SaitamaMath.runMeters(activity(runM = 0L, runsRead = false)))
        assertEquals(0L, SaitamaMath.runMeters(activity(runM = 0L, runsRead = true)))
        assertEquals(5_000L, SaitamaMath.runMeters(activity(runM = 5_000L, runsRead = false)))
        val days = SaitamaMath.days(day - 1, day - 1, emptyList(), listOf(activity(day - 1, 0L, runsRead = false)))
        val p = days.getValue(day - 1)
        assertFalse(p.runKnown)
        assertEquals(SaitamaMark.NO_RUN_DATA, SaitamaMath.mark(day - 1, p, since = day - 5, today = day))
        val q = SaitamaMath.days(day - 1, day - 1, emptyList(), listOf(activity(day - 1, 0L, runsRead = true))).getValue(day - 1)
        assertEquals(SaitamaMark.PARTIAL, SaitamaMath.mark(day - 1, q, since = day - 5, today = day))
    }

    @Test
    fun activityMigrationAddsRunsRead() {
        assertTrue(Diary.SCHEMA_VERSION >= 7)
        val sql = Diary.MIGRATIONS[6].joinToString("\n")
        assertTrue("ALTER TABLE activity_days ADD COLUMN runs_read INTEGER NOT NULL DEFAULT 0" in sql)
    }

    @Test
    fun bonusRetriesWhileDatabaseIsReplaced() {
        // Подменили базу в первых двух попытках — третья по одной базе проходит.
        var calls = 0
        assertEquals(600_000L, SaitamaMath.untilSameDatabase(3) { calls++; if (calls < 3) null else 600_000L })
        assertEquals(3, calls)
        // Подменяли каждый раз — ничего не начислено, попыток не больше трёх.
        calls = 0
        assertNull(SaitamaMath.untilSameDatabase(3) { calls++; null as Long? })
        assertEquals(3, calls)
    }

    @Test
    fun montagePlayRoundTrip() {
        val p = MontagePlay(day, 4, listOf(SaitamaMilestone.SQUATS, SaitamaMilestone.DAY))
        assertEquals("20000|4|SQUATS,DAY", p.encode())
        assertEquals(p, MontagePlay.decode(p.encode()))
        assertNull(MontagePlay.decode(""))
        assertNull(MontagePlay.decode("20000|4|"))
        assertNull(MontagePlay.decode("x|4|ABS"))
    }

    @Test
    fun remainingTextAndCardLine() {
        val p = SaitamaMath.dayOf(day, listOf(set("pushups", 63), set("squats", 99), set("abs", 100)), 4_250L)
        assertEquals("Сегодня осталось: 37 отжиманий, 1 приседание, 5,8 км бега", SaitamaMath.remainingText(p))
        assertEquals("Сегодня 63 · 100 · 99 · 4,2 км", SaitamaMath.cardLine(p))
        val q = SaitamaMath.dayOf(day, listOf(set("pushups", 98), set("abs", 22)), 10_000L)
        assertEquals("Сегодня осталось: 2 отжимания, 78 на пресс, 100 приседаний", SaitamaMath.remainingText(q))
    }

    @Test
    fun kmFormatting() {
        assertEquals("0", SaitamaMath.km(0))
        assertEquals("4,2", SaitamaMath.km(4_299))
        assertEquals("9,9", SaitamaMath.km(9_960)) // сделанное — вниз: не «10» раньше времени
        assertEquals("10", SaitamaMath.km(10_000))
        assertEquals("0,1", SaitamaMath.km(40, roundUp = true)) // остаток — вверх
    }

    // ---------- Серия ----------

    @Test
    fun todayUnfinishedIsNotAMiss() {
        val full = setOf(day - 3, day - 2, day - 1)
        assertEquals(3, SaitamaMath.streak(full, since = day - 10, today = day))
        // Сегодня выполнен — он тоже в серии.
        assertEquals(4, SaitamaMath.streak(full + day, since = day - 10, today = day))
    }

    @Test
    fun missedFinishedDayResetsStreak() {
        val full = setOf(day - 5, day - 4, day - 3, day - 1)
        assertEquals(1, SaitamaMath.streak(full, since = day - 10, today = day))
        assertEquals(0, SaitamaMath.streak(setOf(day - 5, day - 4), since = day - 10, today = day))
        assertEquals(0, SaitamaMath.streak(emptySet(), since = day, today = day))
    }

    @Test
    fun recordIsLongestRun() {
        val full = setOf(day - 9, day - 8, day - 7, day - 6, day - 4, day - 1, day)
        assertEquals(4, SaitamaMath.record(full, since = day - 10, today = day))
        assertEquals(2, SaitamaMath.streak(full, since = day - 10, today = day))
        assertEquals(0, SaitamaMath.record(emptySet(), since = day, today = day))
    }

    @Test
    fun daysBeforeSinceDoNotCount() {
        val full = setOf(day - 4, day - 3, day - 2, day - 1)
        assertEquals(2, SaitamaMath.streak(full, since = day - 2, today = day))
        assertEquals(2, SaitamaMath.record(full, since = day - 2, today = day))
        assertEquals(SaitamaMark.BEFORE_START, SaitamaMath.mark(day - 5, null, since = day - 2, today = day))
        // Выполненный день раньше начала не подсвечивается полным: сначала граница начала.
        val fullBefore = SaitamaMath.dayOf(day - 3, fullSets(day - 3), 10_000L)
        assertEquals(SaitamaMark.BEFORE_START, SaitamaMath.mark(day - 3, fullBefore, since = day - 2, today = day))
        assertEquals(SaitamaMark.FULL, SaitamaMath.mark(day - 2, SaitamaMath.dayOf(day - 2, fullSets(day - 2), 10_000L), since = day - 2, today = day))
    }

    @Test
    fun marksOfDays() {
        val since = day - 13
        val known = SaitamaMath.dayOf(day - 1, listOf(set("pushups", 100, day - 1)), 3_000L)
        val noRun = SaitamaMath.dayOf(day - 2, fullSets(day - 2), null)
        val full = SaitamaMath.dayOf(day - 3, fullSets(day - 3), 10_000L)
        assertEquals(SaitamaMark.PARTIAL, SaitamaMath.mark(day - 1, known, since, day))
        assertEquals(SaitamaMark.NO_RUN_DATA, SaitamaMath.mark(day - 2, noRun, since, day))
        assertEquals(SaitamaMark.FULL, SaitamaMath.mark(day - 3, full, since, day))
        assertEquals(SaitamaMark.TODAY, SaitamaMath.mark(day, SaitamaMath.dayOf(day, emptyList(), 0L), since, day))
        assertEquals(SaitamaMark.FULL, SaitamaMath.mark(day, SaitamaMath.dayOf(day, fullSets(), 10_000L), since, day))
    }

    @Test
    fun streakFromDiaryDays() {
        val sets = (day - 3..day).flatMap { fullSets(it) }
        val acts = listOf(activity(day - 3, 10_000L), activity(day - 2, 3_000L), activity(day - 1, 12_000L))
        val days = SaitamaMath.days(day - 3, day, sets, acts)
        val full = days.values.filter { it.full }.map { it.day }.toSet()
        // Позавчера бег не добран — серия рвётся; сегодня без бега (нет данных) — не пропуск.
        assertEquals(setOf(day - 3, day - 1), full)
        assertEquals(1, SaitamaMath.streak(full, since = day - 3, today = day))
        assertEquals(1, SaitamaMath.record(full, since = day - 3, today = day))
    }

    // ---------- Бонус ----------

    @Test
    fun bonusOnlyForFullDaysSinceStartInWindow() {
        val from = day - 20
        val sets = (from..day).flatMap { fullSets(it) }
        val acts = (from..day).map { activity(it, if (it == day - 5) 2_000L else 10_000L) }
        val days = SaitamaMath.days(from, day, sets, acts)
        // since раньше окна: только последние 14 дней, без неполного дня.
        val paid = SaitamaMath.bonusDays(days, since = from, today = day)
        assertEquals(13, paid.size)
        assertEquals(day - 13, paid.first())
        assertFalse((day - 5) in paid)
        // since внутри окна: раньше since не платится.
        assertEquals(listOf(day - 1, day), SaitamaMath.bonusDays(days, since = day - 1, today = day))
    }

    // ---------- Вехи и «нарезка» ----------

    @Test
    fun newMilestonesAreReachedButNotShown() {
        val p = SaitamaMath.dayOf(day, listOf(set("pushups", 100), set("squats", 100)), 2_000L)
        assertEquals(setOf(SaitamaMilestone.PUSHUPS, SaitamaMilestone.SQUATS), p.reached)
        val first = SaitamaMath.newMilestones(p.reached, emptySet())
        assertEquals(listOf(SaitamaMilestone.PUSHUPS, SaitamaMilestone.SQUATS), first)
        // Показанное второй раз не всплывает.
        assertTrue(SaitamaMath.newMilestones(p.reached, first.toSet()).isEmpty())
        val beats = SaitamaMath.montage(first, streak = 0)
        assertEquals(listOf("100 ОТЖИМАНИЙ!", "100 ПРИСЕДАНИЙ!"), beats.map { it.text })
        beats.forEach { assertTrue(it.durationMs in 800L..1_200L) }
    }

    @Test
    fun closedDayGivesFullMontage() {
        val p = SaitamaMath.dayOf(day, fullSets(), 10_100L)
        val shown = setOf(SaitamaMilestone.PUSHUPS, SaitamaMilestone.ABS)
        val fresh = SaitamaMath.newMilestones(p.reached, shown)
        assertEquals(listOf(SaitamaMilestone.SQUATS, SaitamaMilestone.RUN, SaitamaMilestone.DAY), fresh)
        val beats = SaitamaMath.montage(fresh, streak = 5)
        assertEquals(
            listOf("100 ОТЖИМАНИЙ!", "100 НА ПРЕСС!", "100 ПРИСЕДАНИЙ!", "10 КМ!", "ДЕНЬ ВЫПОЛНЕН · СЕРИЯ 5"),
            beats.map { it.text },
        )
        val total = beats.sumOf { it.durationMs }
        assertTrue("монтаж $total мс", total in 4_000L..6_000L)
        assertEquals(BeatKind.FINAL, beats.last().kind)
        assertNotNull(beats.last().voice)
        // Повтор кнопкой — тот же монтаж.
        assertEquals(beats, SaitamaMath.replay(p.reached, streak = 5))
    }

    @Test
    fun shownMilestonesRoundTrip() {
        val s = setOf(SaitamaMilestone.RUN, SaitamaMilestone.PUSHUPS)
        assertEquals("PUSHUPS,RUN", SaitamaMath.encodeShown(s))
        assertEquals(s, SaitamaMath.decodeShown("PUSHUPS,RUN"))
        assertEquals(setOf(SaitamaMilestone.ABS), SaitamaMath.decodeShown("ABS,UNKNOWN,"))
        assertTrue(SaitamaMath.decodeShown(null).isEmpty())
        assertTrue(SaitamaMath.montage(emptyList(), 3).isEmpty())
    }

    @Test
    fun streakTextAndKeys() {
        assertEquals("🔥 Серия 1 день", SaitamaMath.streakText(1))
        assertEquals("🔥 Серия 3 дня", SaitamaMath.streakText(3))
        assertEquals("🔥 Серия 11 дней", SaitamaMath.streakText(11))
        assertEquals("saitama:20000", Saitama.bonusKey(day))
        assertEquals("saitama_shown:20000", Saitama.shownKey(day))
    }
}
