package com.nextlevellife.app.pose

import com.nextlevellife.app.data.Exercises
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Каждая строка каталога проверяется реальными координатами кадров, а не подменой углов движка. */
@RunWith(Parameterized::class)
class GymCatalogCountersTest(private val id: String) {
    private val ex get() = Exercises.byId(id)
    private val start get() = path(id).first
    private val far get() = path(id).second
    private fun counter() = RepCounters.forExercise(ex.counterId)!!

    private fun points(k: Key, visible: Side = Side.LEFT): Map<Joint, PosePoint> {
        val p = StickFigure.points(k, visible).toMutableMap()
        val other = if (visible == Side.LEFT) Side.RIGHT else Side.LEFT
        if (id == "db_lunge" || id == "db_bulgarian_split_squat") {
            val a = p.getValue(visible.joint(Limb.ANKLE))
            p[other.joint(Limb.ANKLE)] = PosePoint(a.x - 190f,
                a.y - if (id == "db_bulgarian_split_squat") 75f else 0f, 0.95f)
        }
        if (id == "db_lateral_raise") {
            val originX = p.getValue(visible.joint(Limb.HIP)).x
            for (limb in listOf(Limb.SHOULDER, Limb.ELBOW, Limb.WRIST, Limb.HIP, Limb.KNEE, Limb.ANKLE)) {
                val a = p.getValue(visible.joint(limb))
                p[other.joint(limb)] = PosePoint(2 * originX - a.x, a.y, 0.95f)
            }
        }
        return p
    }

    private inner class Motion(val c: RepCounter, val side: Side = Side.LEFT) {
        var t = 100_000L
        fun hold(k: Key?, ms: Long = 400L, edit: (MutableMap<Joint, PosePoint>) -> Unit = {}): Motion {
            repeat((ms / 50).toInt()) {
                t += 50
                val p = k?.let { points(it, side).toMutableMap().also(edit) } ?: emptyMap()
                c.onFrame(PoseFrame(t, 1080, 1920, p))
            }
            return this
        }
        fun move(a: Key, b: Key, ms: Long = 1_600L, edit: (MutableMap<Joint, PosePoint>) -> Unit = {}): Motion {
            val n = (ms / 50).toInt()
            repeat(n) { hold(a.lerp(b, (it + 1f) / n), 50L, edit) }
            return this
        }
        fun rep(target: Key = far) = hold(start).move(start, target).hold(target).move(target, start).hold(start)
    }

    @Test fun fullCycleCountsOnce() {
        val m = Motion(counter())
        repeat(3) { m.rep() }
        assertEquals(id, 3, m.c.state.count)
        assertTrue(id, m.c.state.tracking && m.c.state.inPosition && m.c.state.atStart)
        shortGapAlsoDiscardsStrictAngularCycle()
        hiddenRearFootCannotCompleteBulgarianCycle()
        visibleFrontLegWinsOverMoreConfidentRearLegOnEntry()
    }

    @Test fun incompleteAmplitudeDoesNotCount() {
        val m = Motion(counter())
        repeat(3) { m.rep(start.lerp(far, 0.45f)) }
        assertEquals(id, 0, m.c.state.count)
    }

    @Test fun designTempoWithShortEndpointHoldsStillCounts() {
        // 3.5 s cycle, 200 ms at the far endpoint: >= 2–2.5 s and 150–250 ms in the design.
        val m = Motion(counter()).hold(start)
        m.move(start, far, 1_650).hold(far, 200).move(far, start, 1_650).hold(start)
        assertEquals(id, 1, m.c.state.count)
    }

    @Test fun losingPersonDiscardsUnfinishedCycle() {
        val m = Motion(counter()).rep().hold(start).move(start, far).hold(far)
        m.hold(null, 2_000).hold(start)
        assertEquals(id, 1, m.c.state.count)
        m.rep()
        assertEquals(id, 2, m.c.state.count)
    }

    @Test fun sameMotionWorksOnOtherVisibleSide() {
        val m = Motion(counter(), Side.RIGHT).rep()
        assertEquals(id, 1, m.c.state.count)
    }

    private fun shortGapAlsoDiscardsStrictAngularCycle() {
        if (id !in GymAngularProfiles.byId) return
        val m = Motion(counter()).rep().hold(start).move(start, far).hold(far)
        m.hold(null, 450).hold(start)
        assertEquals(id, 1, m.c.state.count)
        m.rep()
        assertEquals(id, 2, m.c.state.count)
    }

    private fun hiddenRearFootCannotCompleteBulgarianCycle() {
        if (id != "db_bulgarian_split_squat") return
        val m = Motion(counter()).rep().hold(start).move(start, far).hold(far)
        m.hold(far, 500) { it.remove(Joint.RIGHT_ANKLE) }
        m.move(far, start, 1600) { it.remove(Joint.RIGHT_ANKLE) }
        assertEquals(1, m.c.state.count)
        assertFalse(m.c.state.inPosition)
        m.hold(start).rep()
        assertEquals(2, m.c.state.count)
    }

    private fun visibleFrontLegWinsOverMoreConfidentRearLegOnEntry() {
        if (id != "db_bulgarian_split_squat") return
        val c = counter()
        var t = 100_000L
        val sequence = listOf(start to 500, far to 2900, start to 1700)
        for ((key, duration) in sequence) repeat(duration / 50) {
            val p = points(key, Side.RIGHT).toMutableMap()
            for (limb in Limb.entries.filter { it != Limb.NOSE }) {
                p[Side.RIGHT.joint(limb)] = p.getValue(Side.RIGHT.joint(limb)).copy(confidence = 0.70f)
                p[Side.LEFT.joint(limb)] = p.getValue(Side.LEFT.joint(limb)).copy(confidence = 0.95f)
            }
            t += 50
            c.onFrame(PoseFrame(t, 1080, 1920, p))
        }
        assertEquals(1, c.state.count)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = Exercises.gym.filter { it.id !in setOf("bb_squat", "bb_deadlift", "bb_press", "bb_row") }
            .map { arrayOf(it.id) }

        private fun path(id: String): Pair<Key, Key> = when (id) {
            "bb_front_squat" -> SPECS.first { it.id == "squats" }.path.let { it[0] to it[1] }
            "db_row" -> SPECS.first { it.id == "row" }.path.let { it[0] to it[1] }
            "db_curl" -> SPECS.first { it.id == "curl" }.path.let { it[0] to it[1] }
            "db_goblet_squat" -> SPECS.first { it.id == "goblet_squat" }.path.let { it[0] to it[1] }
            "assisted_pullup" -> SPECS.first { it.id == "pullups" }.path.let { it[0] to it[1] }
            "bb_romanian_deadlift" -> Key(0f, 170f, 180f, 180f, 180f) to Key(65f, 170f, 180f, 180f, 180f)
            "bb_bench_press", "db_bench_press" -> Key(270f, 90f, 180f, 100f, 0f) to Key(270f, 90f, 180f, 0f, 0f)
            "bb_incline_press", "db_incline_press" -> Key(315f, 90f, 180f, 100f, 0f) to Key(315f, 90f, 180f, 0f, 0f)
            "db_seated_press" -> Key(0f, 90f, 180f, 150f, 10f) to Key(0f, 90f, 180f, 0f, 0f)
            "db_lateral_raise" -> Key(0f, 180f, 180f, 180f, 180f) to Key(0f, 180f, 180f, 90f, 90f)
            "db_lunge", "db_bulgarian_split_squat" -> Key(0f, 150f, 165f, 180f, 180f) to Key(5f, 90f, 180f, 180f, 180f)
            "machine_lat_pulldown" -> Key(0f, 90f, 180f, 0f, 0f) to Key(0f, 90f, 180f, 130f, -15f)
            "machine_seated_row" -> Key(0f, 90f, 180f, 90f, 90f) to Key(0f, 90f, 180f, 200f, 90f)
            "machine_chest_press" -> Key(0f, 90f, 180f, 180f, 90f) to Key(0f, 90f, 180f, 90f, 90f)
            "machine_leg_extension" -> Key(0f, 90f, 180f, 180f, 180f) to Key(0f, 90f, 95f, 180f, 180f)
            "machine_leg_curl" -> Key(0f, 90f, 95f, 180f, 180f) to Key(0f, 90f, 200f, 180f, 180f)
            "machine_lying_leg_curl" -> Key(270f, 90f, 90f, 180f, 180f) to Key(270f, 90f, -5f, 180f, 180f)
            "hyperextension" -> Key(315f, 135f, 135f, 95f, 80f) to Key(240f, 135f, 135f, 95f, 80f)
            "bars_dip" -> BARS_DIP_TOP to BARS_DIP_BOTTOM
            "bars_leg_raise" -> BARS_HANG to BARS_LEGS_UP
            else -> error("Нет синтетической траектории $id")
        }
    }
}

class GymCatalogPostureTest {
    @Test fun squattingWithFeetTogetherIsNotALungeOrBulgarianSquat() {
        val spec = SPECS.first { it.id == "squats" }
        for (id in listOf("db_lunge", "db_bulgarian_split_squat")) {
            val c = RepCounters.forExercise(id)!!
            val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 3, 1800).hold(spec.rest, 1000)
            c.feed(tl.frames)
            assertEquals(id, 0, c.state.count)
        }
    }

    @Test fun bulgarianRequiresVisibleElevatedRearFoot() {
        val c = RepCounters.forExercise("db_bulgarian_split_squat")!!
        val up = Key(0f, 150f, 165f, 180f, 180f)
        val down = Key(5f, 90f, 180f, 180f, 180f)
        c.feed(Timeline().hold(up, 1000).reps(listOf(up, down), 3, 1800).hold(up, 1000).frames)
        assertEquals(0, c.state.count)
        assertFalse(c.state.inPosition)
    }

    @Test fun briefExtremeCannotCompleteAnAngularCycle() {
        val c = RepCounters.forExercise("machine_chest_press")!!
        val start = Key(0f, 90f, 180f, 180f, 90f)
        // 140° at the elbow, below the 150° far threshold (115/90 would be 155°).
        val middle = Key(0f, 90f, 180f, 130f, 90f)
        val top = Key(0f, 90f, 180f, 90f, 90f)
        c.feed(Timeline().hold(start, 1000).move(start, middle, 1800).hold(top, 66)
            .hold(middle, 300).move(middle, start, 1800).hold(start, 1000).frames)
        assertEquals(0, c.state.count)
    }
}
