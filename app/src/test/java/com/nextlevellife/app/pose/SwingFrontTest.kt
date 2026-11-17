package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Объёмный скелет: одно и то же движение снимается анфас (камера перед человеком) и сбоку.
 * Оси: X — вбок (к левой руке человека), Y — вверх, Z — вперёд, куда смотрит человек. Единица — длина торса.
 *
 * Углы сегментов: α — от «вниз» к «вперёд» (0 вниз, 90 вперёд, 180 вверх, отрицательные — назад),
 * β — наружу от середины тела (отрицательный — внутрь, к гире). Руки либо по углам ([ik] = false, мах:
 * прямые руки идут по дуге), либо к точке кисти через сгиб локтя ([ik] = true, тяга сумо: гиря идёт
 * вертикально у тела, локти уходят вверх и в стороны).
 */
internal data class Body3(
    val tilt: Float,
    val thigh: Float,
    val shin: Float,
    val legOut: Float,
    val upperA: Float = 0f,
    val upperB: Float = 0f,
    val foreA: Float = 0f,
    val foreB: Float = 0f,
    val ik: Boolean = false,
    val wristX: Float = 0f,
    val wristY: Float = 0f,
    val wristZ: Float = 0f,
) {
    fun lerp(o: Body3, k: Float): Body3 {
        fun m(a: Float, b: Float) = a + (b - a) * k
        return Body3(
            m(tilt, o.tilt), m(thigh, o.thigh), m(shin, o.shin), m(legOut, o.legOut),
            m(upperA, o.upperA), m(upperB, o.upperB), m(foreA, o.foreA), m(foreB, o.foreB),
            ik, m(wristX, o.wristX), m(wristY, o.wristY), m(wristZ, o.wristZ),
        )
    }

    private class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Float) = V(x * k, y * k, z * k)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        val len get() = sqrt(dot(this))
        fun unit() = this * (1f / len)
    }

    private fun rad(d: Float) = Math.toRadians(d.toDouble())

    private fun dir(a: Float, b: Float, s: Float) = V(
        s * sin(rad(b)).toFloat(),
        -(cos(rad(b)) * cos(rad(a))).toFloat(),
        (cos(rad(b)) * sin(rad(a))).toFloat(),
    )

    /** Положения суставов (X, Y, Z) обеих сторон. */
    fun joints(): Map<Joint, Triple<Float, Float, Float>> {
        val out = HashMap<Joint, Triple<Float, Float, Float>>()
        fun put(j: Joint, v: V) {
            out[j] = Triple(v.x, v.y, v.z)
        }
        val up = V(0f, cos(rad(tilt)).toFloat(), sin(rad(tilt)).toFloat())
        val fwd = V(0f, -sin(rad(tilt)).toFloat(), cos(rad(tilt)).toFloat())
        put(Joint.NOSE, up * 1.3f + fwd * 0.12f)
        for (side in Side.entries) {
            val s = if (side == Side.LEFT) 1f else -1f
            val hip = V(s * 0.25f, 0f, 0f)
            val shoulder = V(s * 0.4f, 0f, 0f) + up
            val knee = hip + dir(thigh, legOut, s) * StickFigure.THIGH
            val ankle = knee + dir(shin, legOut, s) * StickFigure.SHIN
            val elbow: V
            val wrist: V
            if (!ik) {
                elbow = shoulder + dir(upperA, upperB, s) * StickFigure.UPPER_ARM
                wrist = elbow + dir(foreA, foreB, s) * StickFigure.FOREARM
            } else {
                val target = V(s * wristX, wristY, wristZ)
                val d = target - shoulder
                val reach = StickFigure.UPPER_ARM + StickFigure.FOREARM
                if (d.len >= reach * 0.999f) {
                    val u = d.unit()
                    elbow = shoulder + u * StickFigure.UPPER_ARM
                    wrist = shoulder + u * reach
                } else {
                    // Два звена до точки кисти; локоть — наружу и вверх (локти разведены, как в тяге сумо).
                    val u = d.unit()
                    val dl = d.len
                    val a = (StickFigure.UPPER_ARM * StickFigure.UPPER_ARM -
                        StickFigure.FOREARM * StickFigure.FOREARM + dl * dl) / (2 * dl)
                    val h = sqrt((StickFigure.UPPER_ARM * StickFigure.UPPER_ARM - a * a).coerceAtLeast(0f))
                    val pole = V(s, 1f, 0f)
                    val p = (pole - u * pole.dot(u)).unit()
                    elbow = shoulder + u * a + p * h
                    wrist = target
                }
            }
            put(side.joint(Limb.SHOULDER), shoulder)
            put(side.joint(Limb.ELBOW), elbow)
            put(side.joint(Limb.WRIST), wrist)
            put(side.joint(Limb.HIP), hip)
            put(side.joint(Limb.KNEE), knee)
            put(side.joint(Limb.ANKLE), ankle)
        }
        return out
    }
}

/** Ракурс камеры для [Body3]. */
internal enum class View { FRONT, SIDE }

/** Кадры 30 к/с с косинусным переходом между позами [Body3], снятые с ракурса [view]. */
internal class Timeline3(private val view: View, private val noise: Random? = null, private val noisePx: Float = 3f) {
    val frames = ArrayList<PoseFrame>()
    private var t = Timeline.START_MS

    fun frame(b: Body3) {
        val u = StickFigure.UNIT
        val out = HashMap<Joint, PosePoint>()
        for ((j, p) in b.joints()) {
            val (x, y, z) = p
            fun n() = if (noise != null) (noise.nextFloat() * 2f - 1f) * noisePx else 0f
            out[j] = when (view) {
                // Анфас видны обе стороны; z — глубина, меньше — ближе к камере.
                View.FRONT -> PosePoint(540f - x * u + n(), 1000f - y * u + n(), 0.95f, -z * u)
                // Сбоку (человек смотрит вправо) дальняя сторона заслонена телом.
                View.SIDE -> {
                    val far = j.name.startsWith("RIGHT")
                    PosePoint(540f + z * u + n(), 1000f - y * u + n(), if (far) 0.3f else 0.95f, x * u)
                }
            }
        }
        frames += PoseFrame(t.toLong(), 1080, 1920, out)
        t += Timeline.STEP_MS
    }

    fun hold(b: Body3, ms: Long) = apply { repeat((ms / Timeline.STEP_MS).roundToInt().coerceAtLeast(1)) { frame(b) } }

    fun move(from: Body3, to: Body3, ms: Long) = apply {
        val n = (ms / Timeline.STEP_MS).roundToInt().coerceAtLeast(1)
        for (i in 1..n) frame(from.lerp(to, ((1 - cos(Math.PI * i / n)) / 2).toFloat()))
    }
}

/**
 * Мах гирей анфас и сбоку; тяга сумо к подбородку махом не считается ни с какого ракурса.
 * Запись тестировщика: стоя лицом к камере мах почти не считался (наклон в кадре 4–8° при настоящих ~45°).
 */
class SwingFrontTest {
    private fun counter(id: String): RepCounter = RepCounters.forExercise(id) ?: error("нет счётчика для $id")

    // Стоит, гиря висит в руках.
    private val stand = Body3(0f, 0f, 0f, 5f, upperA = 0f, upperB = -8f, foreA = 0f, foreB = -8f)

    // Низ маха: наклон 55°, колени мягкие, прямые руки ушли назад между бёдер.
    private val swingBottom = Body3(55f, 25f, -5f, 5f, upperA = -15f, upperB = -10f, foreA = -15f, foreB = -10f)

    // Верх маха: стоит прямо, прямые руки вперёд чуть ниже горизонтали, гиря на уровне груди.
    private val swingTop = Body3(0f, 0f, 0f, 5f, upperA = 85f, upperB = -12f, foreA = 85f, foreB = -12f)

    // Неполный мах: руки поднялись только до пояса.
    private val waistTop = swingTop.copy(upperA = 35f, foreA = 35f)

    // Тяга сумо: широкая стойка, низ — наклон 45° и присед, руки висят; верх — кисти у подбородка, локти вверх и в стороны.
    private val sumoBottom = Body3(45f, 60f, 15f, 25f, ik = true, wristX = 0.12f, wristY = -0.35f, wristZ = 0.3f)
    private val sumoTop = Body3(0f, 0f, 0f, 20f, ik = true, wristX = 0.12f, wristY = 1.15f, wristZ = 0.3f)

    private fun swings(view: View, top: Body3, n: Int, noise: Random? = null): Timeline3 {
        val tl = Timeline3(view, noise).hold(stand, 1000).move(stand, swingBottom, 700)
        repeat(n) { tl.move(swingBottom, top, 600).move(top, swingBottom, 600) }
        return tl.move(swingBottom, stand, 700).hold(stand, 1000)
    }

    private fun sumo(view: View, n: Int, noise: Random? = null): Timeline3 {
        val tl = Timeline3(view, noise).hold(sumoBottom, 1000)
        repeat(n) { tl.move(sumoBottom, sumoTop, 800).move(sumoTop, sumoBottom, 800) }
        return tl.hold(sumoBottom, 1000)
    }

    private fun count(id: String, tl: Timeline3) = counter(id).feed(tl.frames).last().count

    @Test
    fun swingFrontCounts() {
        assertEquals(5, count("swings", swings(View.FRONT, swingTop, 5)))
    }

    @Test
    fun swingSideCounts() {
        assertEquals(5, count("swings", swings(View.SIDE, swingTop, 5)))
    }

    @Test
    fun swingWithNoiseCountsExactly() {
        for (seed in 1..10) {
            for (view in View.entries) {
                val n = count("swings", swings(view, swingTop, 5, Random(seed)))
                assertEquals("зерно $seed, $view", 5, n)
            }
        }
    }

    @Test
    fun frontHingeIsSeenInDepthIndicator() {
        // Анфас наклон корпуса в кадре почти не виден, а глубина маха всё равно доходит до конца — по опусканию плеч.
        val states = counter("swings").feed(swings(View.FRONT, swingTop, 2).frames)
        assertTrue("глубина ${states.maxOf { it.depth }}", states.maxOf { it.depth } > 0.99f)
    }

    @Test
    fun sumoPullIsNotASwing() {
        for (view in View.entries) {
            assertEquals("тяга сумо, $view", 0, count("swings", sumo(view, 5)))
            for (seed in 1..5) assertEquals("тяга сумо с шумом, $view, зерно $seed", 0, count("swings", sumo(view, 5, Random(seed))))
        }
    }

    @Test
    fun syntheticSumoIsARealSumoPull() {
        // Проверка самой синтетики: счётчик тяги сумо видит в ней тягу сумо.
        for (view in View.entries) assertEquals("$view", 5, count("sumo_pull", sumo(view, 5)))
    }

    @Test
    fun swingToWaistDoesNotCount() {
        for (view in View.entries) {
            val states = counter("swings").feed(swings(view, waistTop, 5).frames)
            assertEquals("$view", 0, states.last().count)
            assertTrue("$view: нет подсказки «Выше»: ${states.map { it.hint }.toSet()}", states.any { it.hint == "Выше" })
        }
    }

    @Test
    fun halfHingeFrontDoesNotCount() {
        // Анфас: присел на четверть, кисти у середины бедра — не низ маха.
        val half = stand.lerp(swingBottom, 0.45f)
        val tl = Timeline3(View.FRONT).hold(stand, 1000)
        repeat(5) { tl.move(stand, half, 600).move(half, swingTop, 600).move(swingTop, stand, 600) }
        assertEquals(0, counter("swings").feed(tl.hold(stand, 1000).frames).last().count)
    }

    @Test
    fun syntheticDepthIsWritten() {
        // Синтетика анфас отдаёт глубину: кисть в верху маха ближе к камере, чем плечо.
        val p = Timeline3(View.FRONT).apply { frame(swingTop) }.frames[0].points
        assertTrue(p.getValue(Joint.LEFT_WRIST).z < p.getValue(Joint.LEFT_SHOULDER).z)
    }
}
