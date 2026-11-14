package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Вид спереди для жима: у каждой руки свои углы от «вниз» наружу (0 — вниз, 90 — в сторону,
 * 180 — вверх), ноги прямые. Обе стороны видны одинаково хорошо.
 */
internal data class FrontArms(val rUpper: Float, val rFore: Float, val lUpper: Float, val lFore: Float) {
    fun lerp(o: FrontArms, k: Float) = FrontArms(
        rUpper + (o.rUpper - rUpper) * k, rFore + (o.rFore - rFore) * k,
        lUpper + (o.lUpper - lUpper) * k, lFore + (o.lFore - lFore) * k,
    )

    fun noisy(rnd: Random, amp: Float): FrontArms {
        fun n() = (rnd.nextFloat() * 2f - 1f) * amp
        return FrontArms(rUpper + n(), rFore + n(), lUpper + n(), lFore + n())
    }

    companion object {
        private const val U = StickFigure.UNIT

        fun points(a: FrontArms): Map<Joint, PosePoint> {
            val hx = 540f
            val hy = 1000f
            val out = HashMap<Joint, PosePoint>()
            fun put(j: Joint, x: Float, y: Float) {
                out[j] = PosePoint(x, y, 0.95f)
            }
            put(Joint.NOSE, hx, hy - 1.3f * U)
            for (right in listOf(true, false)) {
                val sx = if (right) -1f else 1f
                val sh = Pair(hx + sx * 0.4f * U, hy - U)
                val hip = Pair(hx + sx * 0.25f * U, hy)
                fun dir(deg: Float, len: Float): Pair<Float, Float> {
                    val r = Math.toRadians(deg.toDouble())
                    return Pair((sx * sin(r) * len * U).toFloat(), (cos(r) * len * U).toFloat())
                }
                val up = dir(if (right) a.rUpper else a.lUpper, StickFigure.UPPER_ARM)
                val fo = dir(if (right) a.rFore else a.lFore, StickFigure.FOREARM)
                val elbow = Pair(sh.first + up.first, sh.second + up.second)
                val wrist = Pair(elbow.first + fo.first, elbow.second + fo.second)
                val s = if (right) Side.RIGHT else Side.LEFT
                put(s.joint(Limb.SHOULDER), sh.first, sh.second)
                put(s.joint(Limb.ELBOW), elbow.first, elbow.second)
                put(s.joint(Limb.WRIST), wrist.first, wrist.second)
                put(s.joint(Limb.HIP), hip.first, hip.second)
                put(s.joint(Limb.KNEE), hip.first, hip.second + StickFigure.THIGH * U)
                put(s.joint(Limb.ANKLE), hip.first, hip.second + (StickFigure.THIGH + StickFigure.SHIN) * U)
            }
            return out
        }
    }
}

/** Кадры 30 к/с с косинусным переходом, как [Timeline], но для [FrontArms]. */
internal class FrontTimeline(private val noise: Random? = null) {
    val frames = ArrayList<PoseFrame>()
    private var t = Timeline.START_MS

    fun frame(a: FrontArms) {
        val k = if (noise != null) a.noisy(noise, 3f) else a
        frames += PoseFrame(t.toLong(), 1080, 1920, FrontArms.points(k))
        t += Timeline.STEP_MS
    }

    fun hold(a: FrontArms, ms: Long) = apply { repeat((ms / Timeline.STEP_MS).roundToInt().coerceAtLeast(1)) { frame(a) } }

    fun move(from: FrontArms, to: FrontArms, ms: Long) = apply {
        val n = (ms / Timeline.STEP_MS).roundToInt().coerceAtLeast(1)
        for (i in 1..n) frame(from.lerp(to, ((1 - cos(Math.PI * i / n)) / 2).toFloat()))
    }

    fun reps(from: FrontArms, to: FrontArms, n: Int, segmentMs: Long) = apply {
        repeat(n) { move(from, to, segmentMs).move(to, from, segmentMs) }
    }
}

/** Новые упражнения с гирей: жим одной рукой (спереди) и частные случаи остальных. */
class KettlebellCountersTest {
    private fun counter(id: String): RepCounter = RepCounters.forExercise(id) ?: error("нет счётчика для $id")

    // Свободная рука опущена; рабочая — у плеча или над головой.
    private val hang = 8f to 4f
    private val rack = -5f to 175f
    private val top = 178f to 180f

    private fun right(arm: Pair<Float, Float>) = FrontArms(arm.first, arm.second, hang.first, hang.second)
    private fun left(arm: Pair<Float, Float>) = FrontArms(hang.first, hang.second, arm.first, arm.second)

    @Test
    fun pressCountsEitherArm() {
        for (side in listOf(::right, ::left)) {
            val tl = FrontTimeline().hold(side(rack), 1000).reps(side(rack), side(top), 5, 800).hold(side(rack), 1000)
            val states = counter("press").feed(tl.frames)
            assertEquals(5, states.last().count)
            assertTrue(states.last().tracking && states.last().inPosition && states.last().atStart)
            assertTrue(states.maxOf { it.depth } > 0.99f)
        }
    }

    @Test
    fun pressAlternatingArmsAddUp() {
        val tl = FrontTimeline().hold(right(rack), 1000).reps(right(rack), right(top), 3, 800)
            .move(right(rack), FrontArms(hang.first, hang.second, hang.first, hang.second), 600)
            .move(FrontArms(hang.first, hang.second, hang.first, hang.second), left(rack), 600)
            .hold(left(rack), 800).reps(left(rack), left(top), 3, 800).hold(left(rack), 1000)
        assertEquals(6, counter("press").feed(tl.frames).last().count)
    }

    @Test
    fun pressWithBothArmsAtOnceCountsOne() {
        val bothRack = FrontArms(rack.first, rack.second, rack.first, rack.second)
        val bothTop = FrontArms(top.first, top.second, top.first, top.second)
        val tl = FrontTimeline().hold(bothRack, 1000).reps(bothRack, bothTop, 4, 800).hold(bothRack, 1000)
        assertEquals(4, counter("press").feed(tl.frames).last().count)
    }

    @Test
    fun pressHalfStillAndFastDoNotCount() {
        val half = right(rack).lerp(right(top), 0.5f)
        val halfStates = counter("press").feed(
            FrontTimeline().hold(right(rack), 1000).reps(right(rack), half, 5, 800).hold(right(rack), 1000).frames,
        )
        assertEquals(0, halfStates.last().count)
        assertTrue("нет подсказки «Выше»: ${halfStates.map { it.hint }.toSet()}", halfStates.any { it.hint == "Выше" })

        val still = FrontTimeline(Random(7)).hold(right(rack), 10_000)
        assertEquals(0, counter("press").feed(still.frames).last().count)

        // Свободная рука просто поднимается прямой через сторону — без гири у плеча это не жим.
        val raise = FrontTimeline().hold(right(hang), 1000).reps(right(hang), right(top), 5, 800)
        assertEquals(0, counter("press").feed(raise.frames).last().count)

        val fast = FrontTimeline().hold(right(rack), 1000)
        repeat(10) { fast.hold(right(top), 100).hold(right(rack), 100) }
        fast.hold(right(rack), 1000)
        assertEquals(0, counter("press").feed(fast.frames).last().count)
    }

    @Test
    fun pressWithNoiseCountsExactly() {
        for (seed in 1..10) {
            val tl = FrontTimeline(Random(seed)).hold(right(rack), 1000).reps(right(rack), right(top), 5, 800).hold(right(rack), 1000)
            assertEquals("зерно $seed", 5, counter("press").feed(tl.frames).last().count)
        }
    }

    @Test
    fun pressResetClearsBothArms() {
        val c = counter("press")
        c.feed(FrontTimeline().hold(right(rack), 1000).reps(right(rack), right(top), 2, 800).hold(right(rack), 500).frames)
        assertEquals(2, c.state.count)
        c.reset()
        assertEquals(0, c.state.count)
    }

    /** Слишком быстрые «натыкивания» по 0,1 с не считаются ни в одном новом упражнении. */
    @Test
    fun newExercisesIgnoreTwitching() {
        for (id in listOf("goblet_squat", "reverse_lunge", "curl", "glute_bridge")) {
            val spec = SPECS.first { it.id == id }
            val fast = Timeline().hold(spec.rest, 1000)
            repeat(10) { fast.hold(spec.path[1], 100).hold(spec.rest, 100) }
            fast.hold(spec.rest, 1000)
            assertEquals(id, 0, counter(id).feed(fast.frames).last().count)
        }
    }

    @Test
    fun lungeCountsEitherLegStepsBack() {
        // Видимая нога то уходит назад, то остаётся впереди (назад шагает дальняя): колено сгибается в обоих.
        val back = Key(3f, 190f, 280f, 162f, 22f)
        val frontLeg = Key(3f, 95f, 180f, 162f, 22f)
        val tl = Timeline().hold(GOBLET, 1000)
        repeat(3) { tl.move(GOBLET, back, 900).move(back, GOBLET, 900).move(GOBLET, frontLeg, 900).move(frontLeg, GOBLET, 900) }
        tl.hold(GOBLET, 1000)
        assertEquals(6, counter("reverse_lunge").feed(tl.frames).last().count)
    }

    @Test
    fun curlWithBodySwingDoesNotCount() {
        // Подъём раскачкой: корпус отклоняется назад на 25° в верхней точке.
        val down = Key(0f, 180f, 180f, 175f, 172f)
        val swungUp = Key(335f, 180f, 180f, 160f, 355f)
        val tl = Timeline().hold(down, 1000).reps(listOf(down, swungUp), 5, 900).hold(down, 1000)
        assertEquals(0, counter("curl").feed(tl.frames).last().count)
    }

    @Test
    fun bridgeFeatureMatchesGeometry() {
        // Лёжа с согнутыми ногами угол плечо–таз–колено 130°, вверху моста — прямая (180°).
        fun sig(k: Key): Signals {
            val located = BodyLocator.locate(PoseFrame(0, 1080, 1920, StickFigure.points(k)), Limb.entries.toSet(), emptySet())
            return Signals.measure((located as Located.Found).body)
        }
        assertEquals(130f, sig(BRIDGE_DOWN).hipFlexion, 0.5f)
        assertEquals(180f, sig(Key(235f, 55f, 175f, 95f, 70f)).hipFlexion, 0.5f)
        // Пресс начинается из той же позы: подъём корпуса мостом не засчитывается.
        val crunchUp = Key(345f, 40f, 150f, 0f, 0f)
        val tl = Timeline().hold(BRIDGE_DOWN, 1000).reps(listOf(BRIDGE_DOWN, crunchUp), 5, 900).hold(BRIDGE_DOWN, 1000)
        assertEquals(0, counter("glute_bridge").feed(tl.frames).last().count)
    }
}
