package com.nextlevellife.app.stats

import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.Programs
import com.nextlevellife.app.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Домашние программы (правила версии 6): подходы — обычные подходы упражнений и дают силу,
 * прохождение не даёт ни бонуса комплекса, ни времени выносливости.
 */
class StatsProgramTest {
    private val day = 20_000L

    private fun set(id: String, reps: Int = 0, heldMs: Long = 0L) = SetEntry(
        id = 1, timeMs = 0, day = day, exerciseId = id, exerciseName = id, weightKg = null,
        reps = reps, durationMs = 0, earnedMs = 0, xp = 0, runId = null,
        measurement = if (heldMs > 0) Measurement.HOLD else Measurement.REPS, heldMs = heldMs,
    )

    private fun run(id: String, completed: Boolean = true, durationMs: Long = 20 * 60_000L) = ComplexRun(
        id = 7, timeMs = 0, day = day, complexId = id, weightKg = null, durationMs = durationMs,
        rounds = null, extraReps = null, totalReps = 96, earnedMs = 0, xp = 0, completed = completed,
    )

    @Test
    fun programRunsGiveNoComplexBonusButSetsCount() {
        // Подходы «Новичка» записаны обычными подходами (run_id нет).
        val sets = listOf(
            set("squats", reps = 10), set("squats", reps = 10), set("pushups", reps = 6),
            set("plank", heldMs = 20_000L), set("plank", heldMs = 20_000L), set("plank", heldMs = 20_000L),
        )
        val runs = Programs.all.map { run(it.id) }
        val input = StatsMath.strengthInput(sets, runs)
        assertEquals(26, input.reps)
        assertEquals(60_000L, input.heldMs)
        assertEquals(10, input.bestSet)
        assertEquals(0, input.completedComplexes)
    }

    @Test
    fun programRunsGiveNoEndurance() {
        val e = StatsMath.enduranceInput(null, Programs.all.map { run(it.id, durationMs = 25 * 60_000L) })
        assertEquals(0L, e.complexMs)
        assertEquals(0, StatsMath.endurance(e).points)
    }

    @Test
    fun anyHomePrefixIsExcluded() {
        // Будущие свои программы — тот же префикс.
        assertFalse(StatsMath.countsAsComplex("home_custom_123"))
        assertFalse(StatsMath.countsAsComplex("yoga_morning"))
        assertTrue(StatsMath.countsAsComplex("cindy"))
        assertTrue(StatsMath.countsAsComplex("minotaur"))
        val runs = listOf(run("minotaur"), run("home_beginner"))
        assertEquals(1, StatsMath.strengthInput(emptyList(), runs).completedComplexes)
        assertEquals(20 * 60_000L, StatsMath.enduranceInput(null, runs).complexMs)
    }

    @Test
    fun ruleVersionRaised() {
        assertEquals(6, StatsMath.RULE_VERSION)
    }
}
