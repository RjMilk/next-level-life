package com.nextlevellife.app.data

import com.nextlevellife.app.pose.PoseHoldConfigs
import com.nextlevellife.app.pose.PoseSide
import org.junit.Assert.*
import org.junit.Test

class GymWarmupTest {
    private val routine = Routines.gymWarmup

    private fun fullResults(): List<RoutineStepResult> = routine.steps.mapIndexed { i, step ->
        when (step) {
            is RepsStep -> RoutineStepResult(i, reps = step.reps, durationMs = step.nominalMs)
            is HoldStep -> RoutineStepResult(i, heldMs = step.targetMs, durationMs = step.targetMs)
            is RestStep -> RoutineStepResult(i, durationMs = step.nominalMs)
        }
    }

    @Test fun sequenceAndVolumeMatchDesign() {
        val reps = routine.exercises.filterIsInstance<RepsStep>()
        assertEquals(
            listOf("elbow_flex", "arm_abduction", "knee_raise", "knee_raise", "shallow_squat",
                "quadruped_rock", "quadruped_reach", "quadruped_reach", "glute_bridge"),
            reps.map { it.moveId },
        )
        assertEquals(listOf(8, 8, 6, 6, 10, 8, 4, 4, 10), reps.map { it.reps })
        assertEquals(64, reps.sumOf { it.reps })
        assertTrue(reps.all { it.secondsPerRep == 6 })
        assertEquals(listOf("mountain", "table"), routine.exercises.filterIsInstance<HoldStep>().map { it.poseId })
        assertTrue(routine.exercises.filterIsInstance<HoldStep>().all { it.seconds == 20 && it.paid })
        assertEquals(9, routine.blocks)
        assertEquals("soft_bridge", reps.last().guideId)
    }

    @Test fun durationIncludesEveryUnpaidTransition() {
        assertEquals(424_000L, routine.exercises.sumOf { it.nominalMs })
        assertEquals(116_000L, routine.steps.filterIsInstance<RestStep>().sumOf { it.nominalMs })
        assertEquals(540_000L, routine.nominalMs)
        assertEquals(9, routine.declaredMinutes)
        assertEquals(20, (routine.steps.first() as RestStep).seconds)
        assertEquals(10, (routine.steps.last() as RestStep).seconds)
    }

    @Test fun fullCameraConfirmedWorkEarns138SecondsWithinSharedCap() {
        val score = RoutineMath.score(routine, fullResults())
        assertEquals(138_000L, score.rawEarnedMs)
        assertEquals(138_000L, RoutineMath.maxRewardMs(routine))
        assertEquals(64, score.totalReps)
        assertEquals(40_000L, score.heldMs)
        assertTrue(score.completed)
        assertEquals(138_000L, RoutineMath.capped(score.rawEarnedMs, 0))
        assertEquals(30_000L, RoutineMath.capped(score.rawEarnedMs, 270_000L))
        assertEquals(0L, RoutineMath.capped(score.rawEarnedMs, RoutineMath.DAILY_CAP_MS))
        val sets = RoutineMath.sets(routine, fullResults(), score.rawEarnedMs)
        assertEquals(11, sets.size)
        assertEquals(138_000L, sets.sumOf { it.earnedMs })
        assertTrue(sets.all { it.xp == 0L && Routines.isRoutineExercise(it.exerciseId) })
    }

    @Test fun elapsedTimeAndTransitionsAloneEarnNothing() {
        val onlyTime = routine.steps.mapIndexed { i, step -> RoutineStepResult(i, durationMs = step.nominalMs) }
        val score = RoutineMath.score(routine, onlyTime)
        assertEquals(0L, score.rawEarnedMs)
        assertFalse(score.completed)
        assertFalse(score.hasWork)
        assertTrue(RoutineMath.sets(routine, onlyTime, 0L).isEmpty())
        routine.steps.filterIsInstance<RestStep>().forEach {
            assertEquals(0L, RoutineMath.stepRewardMs(it, RoutineStepResult(0, reps = 100, heldMs = 60_000)))
        }
    }

    @Test fun designatedSidesAndExistingCameraProfilesAreAvailable() {
        for (move in listOf("knee_raise", "quadruped_reach")) {
            val steps = routine.exercises.filterIsInstance<RepsStep>().filter { it.moveId == move }
            assertEquals(listOf(PoseSide.LEFT, PoseSide.RIGHT), steps.map { it.side })
            assertEquals(steps.first().reps, steps.last().reps)
            assertTrue(steps.all { it.camera.contains("ближе") })
        }
        routine.exercises.forEach { step ->
            assertTrue(step.camera.isNotBlank() && step.distance.isNotBlank())
            when (step) {
                is RepsStep -> assertTrue(PoseHoldConfigs.hasMove(step.moveId))
                is HoldStep -> assertNotNull(PoseHoldConfigs.holdCounter(step.poseId, step.side, step.targetMs))
            }
        }
    }

    @Test fun registeredForExistingRoutineRouteAndSharedDailyAccounting() {
        assertSame(routine, Routines.byId("gym_warmup"))
        assertTrue(Routines.isRoutineId(routine.id))
        assertTrue(routine in Routines.all)
        assertFalse(routine in Routines.mobility)
        assertEquals(4, Routines.mobility.size)
    }
}
