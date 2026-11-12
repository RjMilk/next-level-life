package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutMathTest {

    @Test
    fun streakContinuesFromYesterday() {
        assertEquals(4, WorkoutMath.nextStreak(lastDay = 99, today = 100, current = 3))
    }

    @Test
    fun streakSameDayDoesNotGrow() {
        assertEquals(3, WorkoutMath.nextStreak(100, 100, 3))
        assertEquals(1, WorkoutMath.nextStreak(100, 100, 0))
    }

    @Test
    fun streakResetsAfterGapOrFirstTime() {
        assertEquals(1, WorkoutMath.nextStreak(97, 100, 10))
        assertEquals(1, WorkoutMath.nextStreak(Long.MIN_VALUE, 100, 0))
    }

    @Test
    fun splitKeepsExactSum() {
        val parts = WorkoutMath.split(100, listOf(1, 1, 1))
        assertEquals(100L, parts.sum())
        assertEquals(listOf(34L, 33L, 33L), parts)
    }

    @Test
    fun splitIsProportional() {
        assertEquals(listOf(25L, 75L), WorkoutMath.split(100, listOf(1, 3)))
        assertEquals(listOf(0L, 10L), WorkoutMath.split(10, listOf(0, 5)))
    }

    @Test
    fun splitWithZeroWeightsIsEven() {
        assertEquals(listOf(4L, 3L, 3L), WorkoutMath.split(10, listOf(0, 0, 0)))
        assertEquals(emptyList<Long>(), WorkoutMath.split(10, emptyList()))
    }

    @Test
    fun minotaurSetsSumToFixedReward() {
        for (w in Weights.all) {
            val earned = Minotaur.rewardMs(w)
            val xp = Minotaur.xp(w)
            val sets = WorkoutMath.minotaurSets(w, earned, xp)
            assertEquals(5, sets.size)
            assertEquals(Minotaur.exercises.map { it.id }, sets.map { it.exerciseId })
            assertTrue(sets.all { it.reps == 55 && it.weightKg == w })
            assertEquals(Minotaur.totalReps, sets.sumOf { it.reps })
            assertEquals(earned, sets.sumOf { it.earnedMs })
            assertEquals(xp, sets.sumOf { it.xp })
        }
    }

    @Test
    fun cindySetsFollowRoundOrderAndSumToReward() {
        val reps = mapOf(Exercises.squats to 75, Exercises.pullups to 26, Exercises.pushups to 50)
        val earned = Cindy.rewardMs(reps)
        val xp = Exercises.xp(earned)
        val sets = WorkoutMath.cindySets(reps, xp)
        assertEquals(listOf("pullups", "pushups", "squats"), sets.map { it.exerciseId })
        assertEquals(listOf(26, 50, 75), sets.map { it.reps })
        assertTrue(sets.all { it.weightKg == null })
        assertEquals(earned, sets.sumOf { it.earnedMs })
        assertEquals(xp, sets.sumOf { it.xp })
    }

    @Test
    fun cindySetsSkipEmptyExercises() {
        val sets = WorkoutMath.cindySets(mapOf(Exercises.pullups to 5, Exercises.pushups to 0), 1)
        assertEquals(listOf("pullups"), sets.map { it.exerciseId })
    }

    @Test
    fun cindyCompletedNearTwentyMinutes() {
        assertTrue(WorkoutMath.cindyCompleted(Cindy.DURATION_MS))
        assertTrue(WorkoutMath.cindyCompleted(Cindy.DURATION_MS - 2_000))
        assertFalse(WorkoutMath.cindyCompleted(10 * 60_000L))
    }

    @Test
    fun bestOfTakesMinimumOfKnown() {
        assertNull(WorkoutMath.bestOf(null, null))
        assertEquals(5L, WorkoutMath.bestOf(5, null))
        assertEquals(5L, WorkoutMath.bestOf(null, 5))
        assertEquals(3L, WorkoutMath.bestOf(5, 3))
    }
}
