package com.nextlevellife.app.stats

import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.Routines
import com.nextlevellife.app.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Программы ЛФК и йоги не дают ни силы, ни выносливости (правила версии 5). */
class StatsRoutineTest {
    private val day = 20_000L

    private fun set(id: String, reps: Int = 0, heldMs: Long = 0L, runId: Long? = null) = SetEntry(
        id = 1, timeMs = 0, day = day, exerciseId = id, exerciseName = id, weightKg = null,
        reps = reps, durationMs = 0, earnedMs = 0, xp = 0, runId = runId,
        measurement = if (heldMs > 0) Measurement.HOLD else Measurement.REPS, heldMs = heldMs,
    )

    private fun run(id: String, completed: Boolean = true, durationMs: Long = 9 * 60_000L) = ComplexRun(
        id = 7, timeMs = 0, day = day, complexId = id, weightKg = null, durationMs = durationMs,
        rounds = null, extraReps = null, totalReps = 40, earnedMs = 90_000, xp = 0, completed = completed,
    )

    @Test
    fun routineRunsAndSetsGiveNoStrength() {
        val sets = listOf(
            set("lfk_arm_raise_front", reps = 8, runId = 7),
            set("lfk_shallow_squat", reps = 16, runId = 7),
            set("yoga_mountain", heldMs = 120_000, runId = 8),
        )
        val runs = Routines.all.map { run(it.id) }
        val input = StatsMath.strengthInput(sets, runs)
        assertEquals(0, input.reps)
        assertEquals(0L, input.heldMs)
        assertEquals(0, input.completedComplexes)
        assertEquals(0, StatsMath.strength(input).points)
    }

    @Test
    fun routineRunsGiveNoEndurance() {
        val e = StatsMath.enduranceInput(null, Routines.all.map { run(it.id, durationMs = 12 * 60_000L) })
        assertEquals(0L, e.complexMs)
        assertEquals(0, StatsMath.endurance(e).points)
    }

    @Test
    fun otherComplexesStillCount() {
        val sets = listOf(set("pushups", reps = 20), set("plank", heldMs = 60_000), set("yoga_table", heldMs = 40_000))
        val runs = listOf(run("cindy", durationMs = 20 * 60_000L), run("yoga_evening", durationMs = 12 * 60_000L))
        val s = StatsMath.strengthInput(sets, runs)
        assertEquals(20, s.reps)
        assertEquals(60_000L, s.heldMs)
        assertEquals(1, s.completedComplexes)
        assertEquals(20 * 60_000L, StatsMath.enduranceInput(null, runs).complexMs)
    }
}
