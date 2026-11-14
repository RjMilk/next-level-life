package com.nextlevellife.app.stats

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Полный день «Сайтамы» засчитывается силе как завершённый комплекс (правила версии 4). */
class StatsSaitamaTest {
    private val day = 20_000L

    private fun set(exerciseId: String, reps: Int, measurement: Measurement = Measurement.REPS, heldMs: Long = 0L) =
        SetEntry(1L, 0L, day, exerciseId, exerciseId, null, reps, 0L, 0L, 0L, null, measurement, heldMs)

    private fun activity(runM: Long) = ActivityDay(day, 14_000L, runM, 3_600_000L, 11_000L, 1, emptyList(), 0L, 0L)

    private val full = listOf(set("pushups", 100), set("abs", 100), set("squats", 100))

    @Test
    fun fullSaitamaDayCountsAsCompletedComplex() {
        val input = StatsMath.strengthInput(full, emptyList(), activity(10_000L))
        assertEquals(1, input.completedComplexes)
        val s = StatsMath.strength(input)
        // 300 повторений → потолок работы 8, плюс 2 за комплекс = 10.
        assertEquals(StatsMath.DAY_CAP, s.points)
        assertTrue(s.sources.any { it.kind == SourceKind.COMPLEX_DONE && it.points == StatsMath.COMPLEX_BONUS })
    }

    @Test
    fun incompleteOrUnconfirmedDayGivesNoComplex() {
        assertEquals(0, StatsMath.strengthInput(full, emptyList(), activity(9_999L)).completedComplexes)
        // Нет данных о беге — день не полный.
        assertEquals(0, StatsMath.strengthInput(full, emptyList(), null).completedComplexes)
        assertEquals(0, StatsMath.strengthInput(full, emptyList()).completedComplexes)
        // Планка вместо пресса не считается.
        val withPlank = listOf(set("pushups", 100), set("plank", 0, Measurement.HOLD, 600_000L), set("squats", 100))
        assertEquals(0, StatsMath.strengthInput(withPlank, emptyList(), activity(12_000L)).completedComplexes)
    }

    @Test
    fun complexBonusStaysOncePerDayWithOtherComplexes() {
        val cindy = ComplexRun(7L, 0L, day, "cindy", null, 20 * 60_000L, 6, 0, 180, 0L, 0L, completed = true)
        val input = StatsMath.strengthInput(full, listOf(cindy), activity(10_000L))
        assertEquals(2, input.completedComplexes)
        val s = StatsMath.strength(input)
        assertEquals(StatsMath.COMPLEX_BONUS, s.sources.single { it.kind == SourceKind.COMPLEX_DONE }.points)
    }

    @Test
    fun enduranceDoesNotGetComplexTimeFromSaitama() {
        val e = StatsMath.enduranceInput(activity(10_000L), emptyList())
        assertEquals(0L, e.complexMs)
        assertEquals(10_000L, e.runMeters)
    }

    @Test
    fun ruleVersionIsFourAndGuideMentionsSaitama() {
        // 5 — программы ЛФК и йоги исключены из силы и выносливости (StatsRoutineTest).
        // 6 — прохождения домашних программ тоже исключены (StatsProgramTest).
        assertEquals(6, StatsMath.RULE_VERSION)
        val rule = StatsGuide.forStat(Stat.STRENGTH, 0).rules.single { it.what == "Завершённый комплекс" }
        assertTrue(rule.note.orEmpty().contains("«Сайтамы»"))
    }
}
