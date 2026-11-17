package com.nextlevellife.app.data

import com.nextlevellife.app.pose.FreeCloseReason
import com.nextlevellife.app.pose.FreeEvent
import com.nextlevellife.app.pose.FreeResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWorkoutLogTest {
    private fun reps(id: String, n: Int, at: Long, start: Long = at) = FreeEvent.Reps(id, n, start, at)
    private fun closed(at: Long) = FreeEvent.Closed(FreeCloseReason.CHANGE, at)

    @Test
    fun eventsMakeSets() {
        val log = FreeWorkoutLog().applyAll(
            listOf(
                reps("squats", 1, 1_000, start = 0), reps("squats", 1, 3_000), reps("squats", 2, 5_000),
                closed(6_000),
                reps("pushups", 1, 8_000, start = 7_000), reps("pushups", 1, 9_000),
            ),
        )
        assertEquals(listOf("squats" to 4, "pushups" to 2), log.sets.map { it.exerciseId to it.reps })
        assertEquals(FreeSetStatus.CLOSED, log.sets[0].status)
        assertEquals(FreeSetStatus.OPEN, log.sets[1].status)
        assertEquals(5_000L, log.sets[0].durationMs)
        assertEquals(log.sets[1], log.open)
        assertEquals(listOf(log.sets[0]), log.toRecord)
        assertEquals(FreeResume("pushups", 9_000), log.resume())
    }

    @Test
    fun otherExerciseClosesOpenSetByItself() {
        // Классификатор пересоздан и о подходе не знает: другое упражнение всё равно закрывает подход.
        val log = FreeWorkoutLog().applyAll(listOf(reps("squats", 3, 1_000), reps("abs", 1, 2_000)))
        assertEquals(listOf(FreeSetStatus.CLOSED, FreeSetStatus.OPEN), log.sets.map { it.status })
        assertEquals(listOf(1, 2), log.sets.map { it.number })
    }

    @Test
    fun heldSetIsNotShorterThanHeld() {
        val log = FreeWorkoutLog().applyAll(
            listOf(FreeEvent.Held("plank", 10_000, 0, 10_000), FreeEvent.Held("plank", 5_000, 0, 15_000), closed(16_000)),
        )
        val s = log.sets.single()
        assertEquals(15_000L, s.heldMs)
        assertTrue(s.hold)
        assertTrue(s.durationMs >= s.heldMs)
        // 15 с планки по 5 с за секунду.
        assertEquals(75_000L, FreeWorkoutMath.rewardMs(s))
    }

    @Test
    fun earnedAndResults() {
        var log = FreeWorkoutLog().applyAll(listOf(reps("squats", 5, 1_000), closed(2_000), reps("pushups", 2, 3_000)))
        // Обычные цены: приседание 40 с, отжимание 30 с.
        assertEquals(200_000L + 60_000L, log.earnedMs)
        log = log.withResult(1, saved = true, earnedMs = 200_000L)
        assertEquals(200_000L, log.recordedMs)
        assertEquals(FreeSetStatus.RECORDED, log.sets[0].status)
        log = log.closeOpen().withResult(2, saved = false, earnedMs = 0L)
        assertEquals(1, log.failed)
        assertEquals(listOf(2), log.toRecord.map { it.number })
        assertNull(log.open)
    }

    @Test
    fun sessionKeysAreDistinctPerSet() {
        assertEquals("free:abc:1", FreeWorkoutMath.setSessionId("abc", 1))
        val keys = (1..5).map { FreeWorkoutMath.setSessionId("abc", it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val log = FreeWorkoutLog().applyAll(
            listOf(reps("squats", 5, 1_000), closed(2_000), FreeEvent.Held("plank", 12_000, 3_000, 15_000)),
        ).withResult(1, saved = true, earnedMs = 200_000L)
        assertEquals(log, FreeWorkoutLog.decode(log.encode()))
        assertEquals(FreeWorkoutLog(), FreeWorkoutLog.decode(""))
        // Испорченная строка и неизвестное упражнение пропускаются, остальное читается.
        val broken = FreeWorkoutLog.decode("1,squats,5,0,0,1000,CLOSED,0;мусор;2,thruster_x,1,0,0,1,OPEN,0")
        assertEquals(listOf("squats" to 5), broken.sets.map { it.exerciseId to it.reps })
        // Новый подход после испорченной записи не повторяет занятый номер.
        val next = FreeWorkoutLog.decode("3,squats,5,0,0,1000,CLOSED,0").apply(reps("abs", 1, 2_000))
        assertEquals(listOf(3, 4), next.sets.map { it.number })
    }
}
