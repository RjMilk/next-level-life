package com.nextlevellife.app.pose

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Реалистичный поток» ML Kit:
 * - [fps] кадров/с с неравномерными интервалами (±30 %), движение без остановок в крайних точках,
 *   1 повторение за 1,2–2 с;
 * - шум ±3° на направлении каждого отрезка и ±2 % длины торса по координатам каждого сустава;
 * - в 12 % кадров один случайный сустав (запястье, лодыжка или локоть случайной стороны) — уверенность 0,2;
 * - уверенность левой и правой стороны «дышит»: лучшая сторона меняется каждые несколько кадров;
 * - ракурс три четверти: x сжаты на cos 40°, дальняя сторона смещена и немного иначе согнута
 *   (углы в её суставах отличаются на несколько градусов).
 */
internal class RealisticStream(seed: Int, private val fps: Double = 12.0) {
    private val rnd = Random(seed)
    val frames = ArrayList<PoseFrame>()
    private var t = 100_000.0

    /** Период «дыхания» уверенности: 4–7 кадров при 12 к/с. */
    private val breathPeriodMs = 330.0 + rnd.nextDouble() * 250.0
    private val breathPhase = rnd.nextDouble() * 2 * PI

    private fun coordNoise() = (rnd.nextFloat() * 2f - 1f) * COORD_NOISE * StickFigure.UNIT

    private fun emit(key: Key) {
        val near = StickFigure.positions(key.noisy(rnd, ANGLE_NOISE))
        val farKey = Key(
            key.torso, key.thigh + FAR_THIGH_BIAS, key.shin - FAR_THIGH_BIAS,
            key.upperArm + FAR_ARM_BIAS, key.forearm - FAR_ARM_BIAS,
        )
        val far = StickFigure.positions(farKey.noisy(rnd, ANGLE_NOISE))
        val b = sin(2 * PI * t / breathPeriodMs + breathPhase).toFloat()
        val confNear = 0.75f + 0.15f * b
        val confFar = 0.75f - 0.15f * b

        val points = HashMap<Joint, PosePoint>()
        fun put(joint: Joint, p: Pair<Float, Float>, dx: Float, dy: Float, conf: Float) {
            val x = StickFigure.HIP_X + (p.first + dx - StickFigure.HIP_X) * COMPRESS + coordNoise()
            val y = p.second + dy + coordNoise()
            points[joint] = PosePoint(x, y, conf)
        }
        for (limb in Limb.entries) {
            if (limb == Limb.NOSE) continue
            put(Side.LEFT.joint(limb), near.getValue(limb), 0f, 0f, confNear)
            put(Side.RIGHT.joint(limb), far.getValue(limb), FAR_DX, FAR_DY, confFar)
        }
        put(Joint.NOSE, near.getValue(Limb.NOSE), 0f, 0f, 0.9f)

        if (rnd.nextDouble() < DROP_RATE) {
            val side = if (rnd.nextBoolean()) Side.LEFT else Side.RIGHT
            val limb = DROP_LIMBS[rnd.nextInt(DROP_LIMBS.size)]
            val j = side.joint(limb)
            points[j] = points.getValue(j).copy(confidence = 0.2f)
        }
        frames += PoseFrame(t.toLong(), 1080, 1920, points)
        t += 1000.0 / fps * (0.7 + 0.6 * rnd.nextDouble())
    }

    fun hold(key: Key, ms: Long) = apply {
        val end = t + ms
        while (t < end) emit(key)
    }

    /** [n] повторений по замкнутому пути без остановок, каждое за 1,2–2 с. */
    fun reps(path: List<Key>, n: Int) = apply {
        repeat(n) {
            val dur = 1200.0 + 800.0 * rnd.nextDouble()
            val seg = dur / path.size
            val start = t
            while (t - start < dur) {
                val local = t - start
                val i = min((local / seg).toInt(), path.size - 1)
                val e = ((1 - cos(PI * (local - i * seg) / seg)) / 2).toFloat()
                emit(path[i].lerp(path[(i + 1) % path.size], e))
            }
        }
    }

    companion object {
        const val ANGLE_NOISE = 3f
        const val COORD_NOISE = 0.02f
        const val DROP_RATE = 0.12
        val DROP_LIMBS = listOf(Limb.WRIST, Limb.ANKLE, Limb.ELBOW)
        val COMPRESS = cos(Math.toRadians(40.0)).toFloat()
        const val FAR_DX = 0.25f * StickFigure.UNIT
        const val FAR_DY = -0.03f * StickFigure.UNIT
        const val FAR_ARM_BIAS = 4f
        const val FAR_THIGH_BIAS = 3f
    }
}

class RealisticStreamTest {
    private val seeds = 1..10

    private fun count(spec: Spec, path: List<Key>, seed: Int, fps: Double, reps: Int = 15): Int {
        val stream = RealisticStream(seed * 7919 + spec.id.hashCode(), fps)
            .hold(spec.rest, 1500).reps(path, reps).hold(spec.rest, 1000)
        val counter = RepCounters.forExercise(spec.id) ?: error("нет счётчика ${spec.id}")
        return counter.feed(stream.frames).last().count
    }

    private fun table(title: String, results: Map<String, List<Int>>) {
        println("== $title")
        for ((id, counts) in results) println("  ${id.padEnd(10)} ${counts.joinToString(" ")}")
    }

    @Test
    fun fifteenRepsAt12Fps() {
        val results = SPECS.associate { spec -> spec.id to seeds.map { count(spec, spec.path, it, 12.0) } }
        table("15 повторений, 12 к/с, 10 зёрен", results)
        val failures = results.filter { (_, c) ->
            val fifteen = c.count { it == 15 }
            val fourteen = c.count { it == 14 }
            !(fifteen == c.size || (fourteen == 1 && fifteen == c.size - 1))
        }
        assertTrue("ожидалось 15 (допускается одно зерно с 14): $failures", failures.isEmpty())
    }

    @Test
    fun halfAmplitudeAt12FpsCountsNothing() {
        val results = SPECS.associate { spec -> spec.id to seeds.map { count(spec, spec.half, it, 12.0) } }
        table("полуамплитуда, 12 к/с, 10 зёрен", results)
        val failures = results.filter { (_, c) -> c.any { it != 0 } }
        assertTrue("полуамплитуда засчиталась: $failures", failures.isEmpty())
    }

    @Test
    fun fifteenRepsAt8Fps() {
        val results = SPECS.associate { spec -> spec.id to seeds.map { count(spec, spec.path, it, 8.0) } }
        table("15 повторений, 8 к/с, 10 зёрен", results)
        val failures = results.filter { (_, c) -> c.any { it < 14 || it > 15 } }
        assertTrue("на 8 к/с меньше 14 (или больше 15): $failures", failures.isEmpty())
    }

    @Test
    fun stickySideDoesNotFlipWithBreathingConfidence() {
        // Уверенность сторон меняется каждые несколько кадров — сторона не должна прыгать:
        // проверяем косвенно — счёт стоя на месте с «дыханием» и провалами остаётся 0, а человек виден.
        val spec = SPECS.first { it.id == "pushups" }
        val stream = RealisticStream(42).hold(spec.rest, 10_000)
        val states = RepCounters.forExercise(spec.id)!!.feed(stream.frames)
        assertTrue(states.last().count == 0)
        val tracked = states.count { it.tracking }
        assertTrue("человек терялся: $tracked из ${states.size}", tracked >= states.size - 2)
        assertTrue(states.drop(5).all { it.inPosition })
    }
}
