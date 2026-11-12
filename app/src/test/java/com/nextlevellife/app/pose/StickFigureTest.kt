package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Поза «скелета из палочек», вид сбоку. Каждый отрезок задан направлением в градусах:
 * 0 — вверх, 90 — вперёд (по взгляду человека), 180 — вниз, 270 — назад.
 * torso: бедро→плечо, thigh: бедро→колено, shin: колено→лодыжка,
 * upperArm: плечо→локоть, forearm: локоть→запястье.
 */
internal data class Key(
    val torso: Float,
    val thigh: Float,
    val shin: Float,
    val upperArm: Float,
    val forearm: Float,
) {
    fun lerp(o: Key, k: Float) = Key(
        torso + (o.torso - torso) * k,
        thigh + (o.thigh - thigh) * k,
        shin + (o.shin - shin) * k,
        upperArm + (o.upperArm - upperArm) * k,
        forearm + (o.forearm - forearm) * k,
    )

    /** Повернуть всего человека (например, на 90° — лежит на боку вместо того, чтобы стоять). */
    fun rotated(d: Float) = Key(torso + d, thigh + d, shin + d, upperArm + d, forearm + d)

    fun noisy(rnd: Random, amp: Float): Key {
        fun n() = (rnd.nextFloat() * 2f - 1f) * amp
        return Key(torso + n(), thigh + n(), shin + n(), upperArm + n(), forearm + n())
    }
}

internal object StickFigure {
    /** Пикселей на длину торса. */
    const val UNIT = 150f
    const val TORSO = 1f
    const val UPPER_ARM = 0.6f
    const val FOREARM = 0.55f
    const val THIGH = 0.9f
    const val SHIN = 0.85f

    /**
     * Суставы кадра. Видимая сторона [visible] — уверенность 0,95, дальняя сторона
     * (заслонена телом) — 0,3 и чуть смещена. [facingLeft] — человек смотрит влево.
     */
    fun points(k: Key, visible: Side = Side.LEFT, facingLeft: Boolean = false): Map<Joint, PosePoint> {
        val far = if (visible == Side.LEFT) Side.RIGHT else Side.LEFT
        val out = HashMap<Joint, PosePoint>()
        for ((limb, p) in positions(k, facingLeft)) {
            if (limb == Limb.NOSE) {
                out[Joint.NOSE] = PosePoint(p.first, p.second, 0.95f)
                continue
            }
            out[visible.joint(limb)] = PosePoint(p.first, p.second, 0.95f)
            out[far.joint(limb)] = PosePoint(p.first + 6f, p.second + 4f, 0.3f)
        }
        return out
    }

    const val HIP_X = 540f
    const val HIP_Y = 1000f

    /** Положения суставов (пиксели) для позы [k]; [facingLeft] — человек смотрит влево. */
    fun positions(k: Key, facingLeft: Boolean = false): Map<Limb, Pair<Float, Float>> {
        val sx = if (facingLeft) -1f else 1f
        fun dir(deg: Float, len: Float): Pair<Float, Float> {
            val r = Math.toRadians(deg.toDouble())
            return Pair(sx * sin(r).toFloat() * len * UNIT, -cos(r).toFloat() * len * UNIT)
        }
        fun add(p: Pair<Float, Float>, d: Pair<Float, Float>) = Pair(p.first + d.first, p.second + d.second)

        val hip = Pair(HIP_X, HIP_Y)
        val shoulder = add(hip, dir(k.torso, TORSO))
        val elbow = add(shoulder, dir(k.upperArm, UPPER_ARM))
        val wrist = add(elbow, dir(k.forearm, FOREARM))
        val knee = add(hip, dir(k.thigh, THIGH))
        val ankle = add(knee, dir(k.shin, SHIN))
        val nose = add(add(shoulder, dir(k.torso, 0.25f)), dir(k.torso + 90f, 0.12f))
        return mapOf(
            Limb.NOSE to nose, Limb.SHOULDER to shoulder, Limb.ELBOW to elbow, Limb.WRIST to wrist,
            Limb.HIP to hip, Limb.KNEE to knee, Limb.ANKLE to ankle,
        )
    }
}

/** Последовательность кадров 30 кадров/с с плавным (косинусным) движением между позами. */
internal class Timeline(
    private val visible: Side = Side.LEFT,
    private val facingLeft: Boolean = false,
    private val noise: Random? = null,
    private val noiseDeg: Float = 3f,
) {
    val frames = ArrayList<PoseFrame>()
    private var tMs = 0.0

    private fun framesFor(ms: Long) = (ms / STEP_MS).roundToInt().coerceAtLeast(1)

    fun frame(key: Key?) {
        val points = if (key == null) {
            emptyMap()
        } else {
            val k = if (noise != null) key.noisy(noise, noiseDeg) else key
            StickFigure.points(k, visible, facingLeft)
        }
        frames += PoseFrame((START_MS + tMs).toLong(), 1080, 1920, points)
        tMs += STEP_MS
    }

    fun hold(key: Key, ms: Long) = apply { repeat(framesFor(ms)) { frame(key) } }

    fun empty(ms: Long) = apply { repeat(framesFor(ms)) { frame(null) } }

    fun move(from: Key, to: Key, ms: Long) = apply {
        val n = framesFor(ms)
        for (i in 1..n) {
            val e = ((1 - cos(Math.PI * i / n)) / 2).toFloat()
            frame(from.lerp(to, e))
        }
    }

    /** [n] повторений по замкнутому пути path[0] → path[1] → … → path[0]. */
    fun reps(path: List<Key>, n: Int, segmentMs: Long) = apply {
        repeat(n) {
            for (i in path.indices) move(path[i], path[(i + 1) % path.size], segmentMs)
        }
    }

    companion object {
        const val STEP_MS = 1000.0 / 30
        const val START_MS = 100_000.0
    }
}

internal fun RepCounter.feed(frames: List<PoseFrame>): List<RepState> = frames.map { onFrame(it) }

/** Проверки самого генератора: углы скелета совпадают с задуманными. */
class StickFigureTest {
    private fun body(k: Key): Body {
        val located = BodyLocator.locate(
            PoseFrame(0, 1080, 1920, StickFigure.points(k)),
            Limb.entries.toSet(),
            emptySet(),
        )
        return (located as Located.Found).body
    }

    @Test
    fun standingStraight() {
        val s = Signals.measure(body(Key(0f, 180f, 180f, 180f, 180f)))
        assertEquals(180f, s.knee, 0.1f)
        assertEquals(180f, s.elbow, 0.1f)
        assertEquals(0f, s.torsoTilt, 0.1f)
        assertEquals(1f, s.shoulderOverHip, 0.01f)
        assertEquals(1.75f, s.hipOverAnkle, 0.01f)
        assertEquals(-1.15f, s.wristOverShoulder, 0.01f)
    }

    @Test
    fun squatBottomKneeAngle() {
        val s = Signals.measure(body(Key(40f, 95f, 200f, 90f, 90f)))
        assertEquals(75f, s.knee, 0.5f)
        assertEquals(40f, s.torsoTilt, 0.5f)
    }

    @Test
    fun lyingTorsoElevation() {
        val s = Signals.measure(body(Key(270f, 45f, 145f, 0f, 0f)))
        assertEquals(0f, s.torsoElevation, 0.5f)
        val up = Signals.measure(body(Key(345f, 45f, 145f, 0f, 0f)))
        assertEquals(75f, up.torsoElevation, 0.5f)
    }

    @Test
    fun geometryBasics() {
        val a = PosePoint(0f, 0f, 1f)
        val b = PosePoint(0f, 10f, 1f)
        val c = PosePoint(10f, 10f, 1f)
        assertEquals(90f, Geometry.jointAngle(a, b, c), 0.01f)
        assertEquals(0f, Geometry.tiltFromVertical(b, a), 0.01f)
        assertEquals(90f, Geometry.tiltFromVertical(b, c), 0.01f)
        assertEquals(180f, Geometry.tiltFromVertical(a, b), 0.01f)
        assertEquals(0.5f, Geometry.progress(130f, 170f, 90f), 0.001f)
    }
}
