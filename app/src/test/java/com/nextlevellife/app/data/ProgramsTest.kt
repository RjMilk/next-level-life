package com.nextlevellife.app.data

import com.nextlevellife.app.guide.Guides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Каталог домашних программ, ключи записи, длительность и итоги ([ProgramMath]). */
class ProgramsTest {
    @Test
    fun catalogIdsUniqueWithPrefix() {
        assertEquals(4, Programs.all.size)
        assertEquals(Programs.all.size, Programs.all.map { it.id }.toSet().size)
        Programs.all.forEach {
            assertTrue(it.id, it.id.startsWith(Programs.PREFIX))
            assertTrue(Programs.isProgramId(it.id))
            assertEquals(it, Programs.byId(it.id))
        }
        // Не пересекаются с комплексами и программами ЛФК/йоги.
        val others = setOf(Minotaur.ID, Cindy.ID, Saitama.ID) + Routines.ids
        Programs.all.forEach { assertFalse(it.id, it.id in others) }
        others.forEach { assertFalse(it, Programs.isProgramId(it)) }
    }

    @Test
    fun exercisesExistAndHaveGuides() {
        Programs.all.forEach { p ->
            assertTrue(p.steps.isNotEmpty())
            p.steps.forEach { step ->
                assertEquals(step.exercise, Exercises.byId(step.exercise.id))
                assertNotNull("${p.id}: нет гида ${step.exercise.id}", Guides.of(step.exercise.id))
                assertTrue(step.sets > 0 && step.target > 0 && step.restSeconds > 0)
            }
        }
    }

    @Test
    fun catalogMatchesDesign() {
        val b = Programs.beginner
        assertEquals(
            listOf("squats" to 10, "pushups" to 6, "abs" to 10, "plank" to 20),
            b.steps.map { it.exercise.id to it.target },
        )
        assertTrue(b.steps.all { it.sets == 3 && it.restSeconds == 60 })
        assertEquals(listOf("squats", "abs", "plank"), Programs.legsCore.steps.map { it.exercise.id })
        assertTrue(Programs.legsCore.steps.all { it.restSeconds == 45 })
        assertEquals(
            listOf("goblet_squat", "deadlift", "press", "row"),
            Programs.kettlebellStrength.steps.map { it.exercise.id },
        )
        assertTrue(Programs.kettlebellStrength.steps.all { it.sets == 3 && it.target == 10 && it.restSeconds == 75 })
        val e = Programs.kettlebellEndurance
        assertEquals(listOf(4 to 20, 3 to 10, 3 to 12), e.steps.map { it.sets to it.target })
        assertEquals(listOf("swings", "thruster", "goblet_squat"), e.steps.map { it.exercise.id })
        assertFalse(b.kettlebell)
        assertTrue(e.kettlebell)
    }

    @Test
    fun slotsInOrder() {
        val p = Programs.legsCore
        assertEquals(9, p.totalSets)
        assertEquals(ProgramSlot(0, 0), p.slots.first())
        assertEquals(ProgramSlot(2, 2), p.slots.last())
        assertEquals(ProgramSlot(1, 0), p.slots[3])
    }

    @Test
    fun idempotencyKeysAreDistinctPerSet() {
        val keys = Programs.beginner.slots.map { ProgramMath.setSessionId("s1", it.stepIndex, it.setIndex) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("program:s1:1:2", ProgramMath.setSessionId("s1", 1, 2))
        assertEquals("set:program:s1:1:2", LedgerMath.setKey(ProgramMath.setSessionId("s1", 1, 2)))
        // Тот же подход того же прохождения — тот же ключ (повтор записи не платит дважды).
        assertEquals(ProgramMath.setSessionId("s1", 0, 0), ProgramMath.setSessionId("s1", 0, 0))
        assertNotEquals(ProgramMath.setSessionId("s1", 0, 0), ProgramMath.setSessionId("s2", 0, 0))
        assertEquals("complex:program:s1", LedgerMath.complexKey(ProgramMath.runSessionId("s1")))
        assertFalse(LedgerMath.complexKey(ProgramMath.runSessionId("s1")) in keys.map { LedgerMath.setKey(it) })
    }

    @Test
    fun restBetweenSetsButNotAfterLast() {
        val p = Programs.beginner
        assertEquals(60_000L, ProgramMath.restAfterMs(p, 0))
        assertEquals(60_000L, ProgramMath.restAfterMs(p, 2)) // перед сменой упражнения тоже отдых
        assertEquals(0L, ProgramMath.restAfterMs(p, p.slots.lastIndex))
        assertTrue(ProgramMath.changesExercise(p, 2))
        assertFalse(ProgramMath.changesExercise(p, 1))
        assertFalse(ProgramMath.changesExercise(p, p.slots.lastIndex))
    }

    @Test
    fun durationEstimate() {
        // Новичок: старт 30 с + 12 × 10 с подготовки + работа 294 с + 11 × 60 с отдыха + 3 × 30 с перестановки.
        assertEquals((30 + 120 + 294 + 660 + 90) * 1000L, ProgramMath.estimateMs(Programs.beginner))
        assertEquals(20, ProgramMath.estimateMinutes(Programs.beginner))
        Programs.all.forEach { assertTrue(it.id, ProgramMath.estimateMinutes(it) in 10..30) }
        assertEquals(20_000L, ProgramMath.workMs(Programs.beginner.steps[3]))
        assertEquals(30_000L, ProgramMath.workMs(Programs.beginner.steps[0]))
    }

    @Test
    fun rewardsUseRegularPrices() {
        val squats = Programs.beginner.steps[0]
        assertEquals(Exercises.rewardMs(Exercises.squats, 10, 16), ProgramMath.setRewardMs(squats, 10, 0L, 16))
        val plank = Programs.beginner.steps[3]
        assertEquals(100_000L, ProgramMath.setRewardMs(plank, 0, 20_000L, 16)) // 20 с × 5 с
        val press = Programs.kettlebellStrength.steps[2]
        assertEquals(Exercises.rewardMs(Exercises.press, 10, 24), ProgramMath.setRewardMs(press, 10, 0L, 24))
        // Максимум — все подходы ровно до цели.
        val p = Programs.legsCore
        val expected = 3 * Exercises.rewardMs(Exercises.squats, 12, 16) + 3 * Exercises.rewardMs(Exercises.abs, 10, 16) +
            3 * Exercises.holdRewardMs(Exercises.plank, 30_000L)
        assertEquals(expected, ProgramMath.maxRewardMs(p))
        assertTrue(ProgramMath.maxRewardMs(Programs.kettlebellStrength, 32) > ProgramMath.maxRewardMs(Programs.kettlebellStrength, 16))
    }

    @Test
    fun capsAndGoal() {
        val squats = Programs.beginner.steps[0]
        assertEquals(10, ProgramMath.cappedReps(squats, 13))
        assertEquals(0, ProgramMath.cappedReps(squats, -1))
        assertTrue(ProgramMath.reached(squats, 10, 0L))
        assertFalse(ProgramMath.reached(squats, 9, 0L))
        val plank = Programs.beginner.steps[3]
        assertEquals(20_000L, ProgramMath.cappedHeldMs(plank, 25_000L))
        assertTrue(ProgramMath.reached(plank, 0, 20_000L))
        assertFalse(ProgramMath.reached(plank, 0, 19_000L))
    }

    @Test
    fun summaryOfFullRun() {
        val p = Programs.legsCore
        val results = p.slots.map { slot ->
            val step = p.step(slot)
            ProgramSetResult(
                slot.stepIndex, slot.setIndex,
                reps = if (step.hold) 0 else step.target,
                heldMs = if (step.hold) step.targetMs else 0L,
                earnedMs = ProgramMath.setRewardMs(step, step.target, step.targetMs, 16),
            )
        }
        val s = ProgramMath.summary(p, results)
        assertTrue(s.completed)
        assertEquals(9, s.setsDone)
        assertEquals(9, s.goalSets)
        assertEquals(3 * 12 + 3 * 10, s.totalReps)
        assertEquals(90_000L, s.heldMs)
        assertEquals(ProgramMath.maxRewardMs(p), s.earnedMs)
    }

    @Test
    fun summaryOfPartialRun() {
        val p = Programs.beginner
        val results = listOf(
            ProgramSetResult(0, 0, reps = 10, earnedMs = 400_000L),
            ProgramSetResult(0, 1, reps = 7, earnedMs = 280_000L), // «Закончить подход» до цели
            ProgramSetResult(0, 1, reps = 7, earnedMs = 280_000L), // дубль не считается дважды
            ProgramSetResult(0, 2), // пропущенный без работы
            ProgramSetResult(3, 0, heldMs = 20_000L, earnedMs = 100_000L),
        )
        val s = ProgramMath.summary(p, results)
        assertFalse(s.completed)
        assertEquals(3, s.setsDone)
        assertEquals(2, s.goalSets)
        assertEquals(12, s.totalSets)
        assertEquals(17, s.totalReps)
        assertEquals(20_000L, s.heldMs)
        assertEquals(780_000L, s.earnedMs)
        assertTrue(s.hasWork)
        assertFalse(ProgramMath.summary(p, emptyList()).hasWork)
    }
}
