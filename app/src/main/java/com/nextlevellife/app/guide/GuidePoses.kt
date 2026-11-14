package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.HEAD
import com.nextlevellife.app.guide.GuideJoint.L_ANKLE
import com.nextlevellife.app.guide.GuideJoint.L_ELBOW
import com.nextlevellife.app.guide.GuideJoint.L_HIP
import com.nextlevellife.app.guide.GuideJoint.L_KNEE
import com.nextlevellife.app.guide.GuideJoint.L_SHOULDER
import com.nextlevellife.app.guide.GuideJoint.L_WRIST
import com.nextlevellife.app.guide.GuideJoint.NECK
import com.nextlevellife.app.guide.GuideJoint.PELVIS
import com.nextlevellife.app.guide.GuideJoint.R_ANKLE
import com.nextlevellife.app.guide.GuideJoint.R_ELBOW
import com.nextlevellife.app.guide.GuideJoint.R_HIP
import com.nextlevellife.app.guide.GuideJoint.R_KNEE
import com.nextlevellife.app.guide.GuideJoint.R_SHOULDER
import com.nextlevellife.app.guide.GuideJoint.R_WRIST
import com.nextlevellife.app.guide.GuideScene.ANKLE_Y
import com.nextlevellife.app.guide.GuideScene.FOREARM
import com.nextlevellife.app.guide.GuideScene.HEAD as HEAD_LEN
import com.nextlevellife.app.guide.GuideScene.HIP_HALF
import com.nextlevellife.app.guide.GuideScene.SHIN
import com.nextlevellife.app.guide.GuideScene.SHOULDER_DROP
import com.nextlevellife.app.guide.GuideScene.SHOULDER_HALF
import com.nextlevellife.app.guide.GuideScene.THIGH
import com.nextlevellife.app.guide.GuideScene.TORSO
import com.nextlevellife.app.guide.GuideScene.UPPER_ARM
import kotlin.math.cos
import kotlin.math.sin

/**
 * Где поставить позу в сцене: сустав [joint] оказывается в точке ([x], [y]).
 * Остальные суставы считаются от него по углам костей.
 */
data class Pin(val joint: GuideJoint, val x: Float, val y: Float)

/**
 * Цель для кисти или стопы: точка ([x], [y]) сцены или, если задан [joint], смещение от этого
 * сустава (таз, плечо…). Средний сустав (локоть, колено) ставится по двум костям и сгибается
 * в сторону [bend] — градусы на экране: 0 — вверх, 90 — вправо, 180 — вниз, 270 — влево
 * (на виде сбоку «вправо» — это вперёд по взгляду фигурки).
 */
data class Reach(val x: Float, val y: Float, val bend: Float, val joint: GuideJoint? = null)

/** Ближняя стопа стоит на полу в точке [x]. */
fun onFloor(x: Float) = Pin(R_ANKLE, x, ANKLE_Y)

/** Вид спереди: стоит прямо, таз по центру сцены. */
val STANDING_FRONT = Pin(PELVIS, 0.5f, ANKLE_Y - (THIGH + SHIN) * 0.998f)

/**
 * Построители поз для гидов: позу проще задать углами костей, чем 15 точками, — длины костей
 * тогда одинаковы во всех кадрах.
 *
 * Направления костей — в градусах, как у тестового «скелета» в pose/: 0 — вверх, 90 — вперёд
 * (вид сбоку: по взгляду фигурки, вправо на экране), 180 — вниз, 270 — назад.
 */
object GuidePoses {

    private fun dir(deg: Float, len: Float): Pt {
        val r = Math.toRadians(deg.toDouble())
        return Pt((sin(r) * len).toFloat(), (-cos(r) * len).toFloat())
    }

    private operator fun Pt.plus(o: Pt) = Pt(x + o.x, y + o.y)
    private operator fun Pt.minus(o: Pt) = Pt(x - o.x, y - o.y)

    /**
     * Вид сбоку, фигурка смотрит вправо. Ближняя сторона — правая ([GuideJoint.R_SHOULDER] и т. д.),
     * дальняя — левая (по умолчанию повторяет ближнюю).
     *
     * @param torso таз → шея; [head] — шея → голова (по умолчанию продолжает корпус).
     * @param thigh таз → колено, [shin] колено → лодыжка, [upperArm] плечо → локоть, [forearm] локоть → кисть.
     * @param hands вместо углов рук — кисти в точке (обе руки, если не задано [farHands]).
     * @param feet вместо углов ног — лодыжки в точке.
     */
    fun side(
        torso: Float,
        thigh: Float = 180f,
        shin: Float = 180f,
        upperArm: Float = 180f,
        forearm: Float = 180f,
        farThigh: Float = thigh,
        farShin: Float = shin,
        farUpperArm: Float = upperArm,
        farForearm: Float = forearm,
        head: Float = torso,
        pin: Pin = onFloor(0.5f),
        hands: Reach? = null,
        farHands: Reach? = hands,
        feet: Reach? = null,
    ): GuidePose {
        val p = HashMap<GuideJoint, Pt>()
        val pelvis = Pt(0f, 0f)
        p[PELVIS] = pelvis
        p[NECK] = pelvis + dir(torso, TORSO)
        p[HEAD] = p.getValue(NECK) + dir(head, HEAD_LEN)
        val shoulder = pelvis + dir(torso, TORSO - SHOULDER_DROP)
        p[R_SHOULDER] = shoulder
        p[L_SHOULDER] = shoulder
        p[R_HIP] = pelvis
        p[L_HIP] = pelvis
        p[R_KNEE] = pelvis + dir(thigh, THIGH)
        p[R_ANKLE] = p.getValue(R_KNEE) + dir(shin, SHIN)
        p[L_KNEE] = pelvis + dir(farThigh, THIGH)
        p[L_ANKLE] = p.getValue(L_KNEE) + dir(farShin, SHIN)
        p[R_ELBOW] = shoulder + dir(upperArm, UPPER_ARM)
        p[R_WRIST] = p.getValue(R_ELBOW) + dir(forearm, FOREARM)
        p[L_ELBOW] = shoulder + dir(farUpperArm, UPPER_ARM)
        p[L_WRIST] = p.getValue(L_ELBOW) + dir(farForearm, FOREARM)
        place(p, pin)
        hands?.let { reach(p, R_SHOULDER, R_ELBOW, R_WRIST, UPPER_ARM, FOREARM, it) }
        farHands?.let { reach(p, L_SHOULDER, L_ELBOW, L_WRIST, UPPER_ARM, FOREARM, it) }
        feet?.let {
            reach(p, R_HIP, R_KNEE, R_ANKLE, THIGH, SHIN, it)
            reach(p, L_HIP, L_KNEE, L_ANKLE, THIGH, SHIN, it)
        }
        return GuidePose(p)
    }

    /** Направление «наружу» для вида спереди: [out] градусов от «вниз», правая сторона — влево на экране. */
    private fun outward(right: Boolean, out: Float, len: Float): Pt {
        val r = Math.toRadians(out.toDouble())
        val sx = if (right) -1f else 1f
        return Pt((sx * sin(r) * len).toFloat(), (cos(r) * len).toFloat())
    }

    /**
     * Вид спереди: корпус вертикален, конечности задаются углом от «вниз» наружу (0 — вниз,
     * 90 — в сторону, 180 — вверх; отрицательный — внутрь, к середине тела). Левая сторона по
     * умолчанию зеркальна правой. Правая сторона фигурки — слева на экране.
     */
    fun front(
        upperArm: Float = 8f,
        forearm: Float = 4f,
        thigh: Float = 4f,
        shin: Float = 2f,
        leftUpperArm: Float = upperArm,
        leftForearm: Float = forearm,
        leftThigh: Float = thigh,
        leftShin: Float = shin,
        pin: Pin = STANDING_FRONT,
        rightHand: Reach? = null,
        leftHand: Reach? = null,
        rightFoot: Reach? = null,
        leftFoot: Reach? = null,
    ): GuidePose {
        val p = HashMap<GuideJoint, Pt>()
        val pelvis = Pt(0f, 0f)
        p[PELVIS] = pelvis
        p[NECK] = Pt(0f, -TORSO)
        p[HEAD] = Pt(0f, -TORSO - HEAD_LEN)
        p[R_SHOULDER] = Pt(-SHOULDER_HALF, -TORSO + SHOULDER_DROP)
        p[L_SHOULDER] = Pt(SHOULDER_HALF, -TORSO + SHOULDER_DROP)
        p[R_HIP] = Pt(-HIP_HALF, 0f)
        p[L_HIP] = Pt(HIP_HALF, 0f)
        p[R_ELBOW] = p.getValue(R_SHOULDER) + outward(true, upperArm, UPPER_ARM)
        p[R_WRIST] = p.getValue(R_ELBOW) + outward(true, forearm, FOREARM)
        p[L_ELBOW] = p.getValue(L_SHOULDER) + outward(false, leftUpperArm, UPPER_ARM)
        p[L_WRIST] = p.getValue(L_ELBOW) + outward(false, leftForearm, FOREARM)
        p[R_KNEE] = p.getValue(R_HIP) + outward(true, thigh, THIGH)
        p[R_ANKLE] = p.getValue(R_KNEE) + outward(true, shin, SHIN)
        p[L_KNEE] = p.getValue(L_HIP) + outward(false, leftThigh, THIGH)
        p[L_ANKLE] = p.getValue(L_KNEE) + outward(false, leftShin, SHIN)
        place(p, pin)
        rightHand?.let { reach(p, R_SHOULDER, R_ELBOW, R_WRIST, UPPER_ARM, FOREARM, it) }
        leftHand?.let { reach(p, L_SHOULDER, L_ELBOW, L_WRIST, UPPER_ARM, FOREARM, it) }
        rightFoot?.let { reach(p, R_HIP, R_KNEE, R_ANKLE, THIGH, SHIN, it) }
        leftFoot?.let { reach(p, L_HIP, L_KNEE, L_ANKLE, THIGH, SHIN, it) }
        return GuidePose(p)
    }

    private fun place(p: HashMap<GuideJoint, Pt>, pin: Pin) {
        val at = p.getValue(pin.joint)
        val shift = Pt(pin.x, pin.y) - at
        for (j in GuideJoint.entries) p[j] = p.getValue(j) + shift
    }

    private fun reach(
        p: HashMap<GuideJoint, Pt>,
        root: GuideJoint,
        mid: GuideJoint,
        end: GuideJoint,
        l1: Float,
        l2: Float,
        r: Reach,
    ) {
        val base = r.joint?.let { p.getValue(it) } ?: Pt(0f, 0f)
        val target = Pt(base.x + r.x, base.y + r.y)
        val from = p.getValue(root)
        val hint = from + dir(r.bend, l1)
        p[mid] = GuideMath.twoBone(from, target, l1, l2, hint)
        // Недостижимая цель: кисть/стопа на конце вытянутой цепочки, а не в самой цели.
        val m = p.getValue(mid)
        val dx = target.x - m.x
        val dy = target.y - m.y
        val d = kotlin.math.hypot(dx, dy)
        p[end] = if (d > 1e-5f) Pt(m.x + dx / d * l2, m.y + dy / d * l2) else target
    }
}
