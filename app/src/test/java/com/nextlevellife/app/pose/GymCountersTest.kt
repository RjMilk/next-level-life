package com.nextlevellife.app.pose

import com.nextlevellife.app.data.Exercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Счёт упражнений зала: проверенные профили по Exercise.counterId, жим штанги — обе руки одним автоматом. */
class GymCountersTest {
    private fun counter(id: String): RepCounter = RepCounters.forExercise(id) ?: error("нет счётчика для $id")

    private val rack = -5f to 175f
    private val top = 178f to 180f
    private val bothRack = FrontArms(rack.first, rack.second, rack.first, rack.second)
    private val bothTop = FrontArms(top.first, top.second, top.first, top.second)

    @Test
    fun everyGymExerciseHasCounterByProfile() {
        for (ex in Exercises.gym) {
            val c = RepCounters.forExercise(ex.counterId)
            assertNotNull(ex.id, c)
            assertEquals(ex.id, CountKind.REPS, c!!.kind)
        }
        // Профили зала — новые id, пороги не тронуты: условия те же объекты, что у гиревых профилей.
        val press = ExerciseConfigs.byId.getValue("press")
        val row = ExerciseConfigs.byId.getValue("row")
        assertSame(press.far, GymCounters.pressBarbell.far)
        assertSame(press.finish, GymCounters.pressBarbell.finish)
        assertSame(press.rest, GymCounters.pressBarbell.rest)
        assertSame(press.enter, GymCounters.pressBarbell.enter)
        assertSame(press.hold, GymCounters.pressBarbell.hold)
        assertEquals(press.minRepMs, GymCounters.pressBarbell.minRepMs)
        assertSame(row.far, GymCounters.rowBarbell.far)
        assertSame(row.enter, GymCounters.rowBarbell.enter)
        assertEquals(row.minRepMs, GymCounters.rowBarbell.minRepMs)
        // Подсказки — про штангу.
        assertEquals("Штангу к плечам", GymCounters.pressBarbell.startHint)
        assertTrue("гир" !in GymCounters.rowBarbell.postureHint)
        // Профили зала не подменяют гиревые.
        assertTrue(RepCounters.forExercise("press") is EitherArmCounter)
        assertNull(ExerciseConfigs.byId["press_barbell"])
        assertNull(RepCounters.forExercise("bb_squat")) // счётчик ищется по профилю, а не по id упражнения
    }

    @Test
    fun barbellPressCountsBothArmsAsOneRep() {
        val c = counter(Exercises.barbellPress.counterId)
        assertTrue(c is PhaseCounter)
        assertFalse(c is EitherArmCounter)
        val tl = FrontTimeline().hold(bothRack, 1000).reps(bothRack, bothTop, 4, 800).hold(bothRack, 1000)
        val states = c.feed(tl.frames)
        assertEquals(4, states.last().count)
        assertTrue(states.last().tracking && states.last().inPosition && states.last().atStart)
    }

    @Test
    fun barbellPressWithNoiseAndHalfReps() {
        for (seed in 1..10) {
            val tl = FrontTimeline(Random(seed)).hold(bothRack, 1000).reps(bothRack, bothTop, 5, 800).hold(bothRack, 1000)
            assertEquals("зерно $seed", 5, counter("press_barbell").feed(tl.frames).last().count)
        }
        val half = bothRack.lerp(bothTop, 0.5f)
        val states = counter("press_barbell").feed(
            FrontTimeline().hold(bothRack, 1000).reps(bothRack, half, 5, 800).hold(bothRack, 1000).frames,
        )
        assertEquals(0, states.last().count)
        assertTrue(states.any { it.hint == "Выше" })
        val fast = FrontTimeline().hold(bothRack, 1000)
        repeat(10) { fast.hold(bothTop, 100).hold(bothRack, 100) }
        fast.hold(bothRack, 1000)
        assertEquals(0, counter("press_barbell").feed(fast.frames).last().count)
    }

    @Test
    fun squatDeadliftRowCountLikeTheirProfiles() {
        for ((exerciseId, specId) in listOf("bb_squat" to "squats", "bb_deadlift" to "deadlift", "bb_row" to "row")) {
            val ex = Exercises.byId(exerciseId)
            val spec = SPECS.first { it.id == specId }
            val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 5, spec.segmentMs).hold(spec.rest, 1000)
            assertEquals(exerciseId, 5, counter(ex.counterId).feed(tl.frames).last().count)
            val half = Timeline().hold(spec.rest, 1000).reps(spec.half, 5, spec.segmentMs).hold(spec.rest, 1000)
            assertEquals(exerciseId, 0, counter(ex.counterId).feed(half.frames).last().count)
        }
    }
}
