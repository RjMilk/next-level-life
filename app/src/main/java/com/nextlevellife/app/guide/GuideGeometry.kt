package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.*
import kotlin.math.abs
import kotlin.math.hypot

/** Portable paths in scene coordinates. Compose and the JVM frame exporter draw these verbatim. */
sealed interface GuidePathOp {
    data class Move(val point: Pt) : GuidePathOp
    data class Line(val point: Pt) : GuidePathOp
    data class Cubic(val control1: Pt, val control2: Pt, val end: Pt) : GuidePathOp
    data object Close : GuidePathOp
}

enum class GuidePaint { BODY, BODY_SHADE, ACCENT, FLOOR, MAT, METAL, CHAIR, SHADOW, DETAIL }

/** A positive [strokeWidth] means a round-cap, round-join stroke; zero means a filled path. */
data class GuideShape(
    val path: List<GuidePathOp>,
    val paint: GuidePaint,
    val alpha: Float = 1f,
    val strokeWidth: Float = 0f,
)

/** The same dark-theme palette as Theme.kt, also available without Android to the PNG exporter. */
object GuidePalette {
    fun argb(paint: GuidePaint): Long = when (paint) {
        GuidePaint.BODY -> 0xFFECEEF2
        GuidePaint.BODY_SHADE -> 0xFF798394
        GuidePaint.ACCENT -> 0xFFF5A524
        GuidePaint.FLOOR -> 0xFFA3A9B6
        GuidePaint.MAT -> 0xFF2C3140
        GuidePaint.METAL -> 0xFFA3A9B6
        GuidePaint.CHAIR -> 0xFF6B7385
        GuidePaint.SHADOW -> 0xFF000000
        GuidePaint.DETAIL -> 0xFF232731
    }
}

/**
 * Filled, articulated mannequin. Only joint coordinates enter this geometry: no Android/AWT types,
 * separate preview silhouettes, or screen-size-dependent anatomy. Shapes are ordered back to front.
 */
object GuideGeometry {
    private operator fun Pt.plus(p: Pt) = Pt(x + p.x, y + p.y)
    private operator fun Pt.minus(p: Pt) = Pt(x - p.x, y - p.y)
    private operator fun Pt.times(k: Float) = Pt(x * k, y * k)
    private fun unit(p: Pt): Pt {
        val d = hypot(p.x, p.y)
        return if (d < 1e-6f) Pt(0f, -1f) else p * (1f / d)
    }
    private fun normal(p: Pt) = Pt(-p.y, p.x)
    private fun mix(a: Pt, b: Pt, t: Float) = a + (b - a) * t

    private class Builder {
        val shapes = ArrayList<GuideShape>()
        fun path(paint: GuidePaint, alpha: Float = 1f, stroke: Float = 0f, ops: List<GuidePathOp>) {
            shapes += GuideShape(ops, paint, alpha, stroke)
        }
        fun line(a: Pt, b: Pt, paint: GuidePaint, width: Float, alpha: Float = 1f) =
            path(paint, alpha, width, listOf(GuidePathOp.Move(a), GuidePathOp.Line(b)))

        fun ellipse(c: Pt, across: Pt, up: Pt, paint: GuidePaint, alpha: Float = 1f) {
            val k = 0.5522848f
            path(paint, alpha, ops = listOf(
                GuidePathOp.Move(c + across),
                GuidePathOp.Cubic(c + across + up * k, c + up + across * k, c + up),
                GuidePathOp.Cubic(c + up - across * k, c - across + up * k, c - across),
                GuidePathOp.Cubic(c - across - up * k, c - up - across * k, c - up),
                GuidePathOp.Cubic(c - up + across * k, c + across - up * k, c + across),
                GuidePathOp.Close,
            ))
        }

        /** Rounded tapered volume; joints share centers, so articulation never opens a seam. */
        fun capsule(a: Pt, b: Pt, ra: Float, rb: Float, paint: GuidePaint, alpha: Float = 1f) {
            val u = unit(b - a)
            val n = normal(u)
            val k = 0.5522848f
            path(paint, alpha, ops = listOf(
                GuidePathOp.Move(a + n * ra), GuidePathOp.Line(b + n * rb),
                GuidePathOp.Cubic(b + n * rb + u * (rb * k), b + u * rb + n * (rb * k), b + u * rb),
                GuidePathOp.Cubic(b + u * rb - n * (rb * k), b - n * rb + u * (rb * k), b - n * rb),
                GuidePathOp.Line(a - n * ra),
                GuidePathOp.Cubic(a - n * ra - u * (ra * k), a - u * ra - n * (ra * k), a - u * ra),
                GuidePathOp.Cubic(a - u * ra + n * (ra * k), a + n * ra - u * (ra * k), a + n * ra),
                GuidePathOp.Close,
            ))
        }
    }

    fun scene(guide: ExerciseGuide, pose: GuidePose): List<GuideShape> {
        val b = Builder()
        val side = guide.view == GuideView.SIDE
        val floor = GuideScene.FLOOR
        // Several translucent ellipses make a soft contact shadow without platform blur APIs.
        if (!guide.props.bar) {
            val lo = minOf(pose[R_ANKLE].x, pose[L_ANKLE].x, pose[PELVIS].x, pose[HEAD].x)
            val hi = maxOf(pose[R_ANKLE].x, pose[L_ANKLE].x, pose[PELVIS].x, pose[HEAD].x)
            val center = Pt((lo + hi) / 2f, floor + 0.008f)
            for (i in 5 downTo 1) {
                b.ellipse(center, Pt((hi - lo) / 2f + 0.04f + i * 0.009f, 0f),
                    Pt(0f, 0.003f + i * 0.0028f), GuidePaint.SHADOW, 0.035f)
            }
            b.line(Pt(0f, floor), Pt(1f, floor), GuidePaint.FLOOR, 0.0025f, 0.3f)
        }
        if (guide.props.mat) {
            b.capsule(Pt(0.075f, floor - 0.005f), Pt(0.925f, floor - 0.005f), 0.007f, 0.007f, GuidePaint.MAT)
            b.line(Pt(0.08f, floor - 0.01f), Pt(0.92f, floor - 0.01f), GuidePaint.FLOOR, 0.002f, 0.18f)
        }
        guide.props.chair?.let { ch ->
            val left = Pt(ch.x0 + 0.008f, ch.seatY)
            val right = Pt(ch.x1 - 0.008f, ch.seatY)
            val back = if (ch.backLeft) ch.x0 else ch.x1
            b.line(left, Pt(left.x - 0.012f, floor - 0.004f), GuidePaint.CHAIR, 0.011f)
            b.line(right, Pt(right.x + 0.012f, floor - 0.004f), GuidePaint.CHAIR, 0.011f)
            b.line(Pt(back, ch.seatY), Pt(back, ch.seatY - Chair.BACK), GuidePaint.CHAIR, 0.012f)
            b.capsule(Pt(ch.x0, ch.seatY), Pt(ch.x1, ch.seatY), 0.011f, 0.011f, GuidePaint.CHAIR)
            b.line(Pt(ch.x0, ch.seatY - 0.005f), Pt(ch.x1, ch.seatY - 0.005f), GuidePaint.METAL, 0.003f, 0.55f)
            b.capsule(Pt(back, ch.seatY - Chair.BACK + 0.01f), Pt(back, ch.seatY - 0.095f),
                0.016f, 0.016f, GuidePaint.CHAIR)
        }
        if (guide.props.bar) {
            val y = guide.frames.first().pose[R_WRIST].y - 0.004f
            b.line(Pt(0.18f, 0f), Pt(0.18f, y), GuidePaint.CHAIR, 0.009f)
            b.line(Pt(0.82f, 0f), Pt(0.82f, y), GuidePaint.CHAIR, 0.009f)
            b.capsule(Pt(0.18f, y), Pt(0.82f, y), 0.008f, 0.008f, GuidePaint.METAL)
            b.line(Pt(0.19f, y - 0.003f), Pt(0.81f, y - 0.003f), GuidePaint.BODY, 0.002f, 0.6f)
        }
        guide.props.bench?.let { bench(b, it) }
        guide.props.cableMachine?.let { cableMachine(b, it, pose) }
        guide.props.legMachine?.let { machine ->
            val ankle = mix(pose[R_ANKLE], pose[L_ANKLE], 0.5f) + machine.rollerOffset
            b.line(machine.pivot, Pt(machine.pivot.x, floor - 0.01f), GuidePaint.CHAIR, 0.014f)
            b.line(machine.pivot, ankle, GuidePaint.METAL, 0.01f)
            b.ellipse(machine.pivot, Pt(0.025f, 0f), Pt(0f, 0.025f), GuidePaint.METAL)
            b.capsule(ankle - Pt(0.045f, 0f), ankle + Pt(0.045f, 0f), 0.024f, 0.024f, GuidePaint.CHAIR)
        }
        guide.props.assistedMachine?.let { machine ->
            val knees = mix(pose[R_KNEE], pose[L_KNEE], 0.5f) + Pt(0f, 0.026f)
            val tower = Pt(machine.towerX, machine.topY)
            b.line(Pt(machine.towerX, floor), tower, GuidePaint.CHAIR, 0.02f)
            b.line(tower, Pt(knees.x, machine.topY), GuidePaint.CHAIR, 0.012f)
            b.line(Pt(knees.x + 0.11f, knees.y), Pt(knees.x + 0.11f, machine.topY), GuidePaint.METAL, 0.003f)
            b.line(Pt(knees.x + 0.11f, machine.topY), tower, GuidePaint.METAL, 0.003f)
            b.capsule(knees - Pt(0.09f, 0f), knees + Pt(0.1f, 0f), 0.015f, 0.015f, GuidePaint.CHAIR)
            val weight = Pt(machine.towerX, 0.82f - (knees.y - 0.55f))
            b.capsule(weight - Pt(0.028f, 0f), weight + Pt(0.028f, 0f), 0.04f, 0.04f, GuidePaint.METAL)
        }
        if (!side && guide.props.barbell?.grip == BarbellGrip.SHOULDERS) barbell(b, guide.props.barbell, pose, side)

        fun limb(a: Pt, end: Pt, ra: Float, rb: Float, far: Boolean, highlight: Boolean) {
            b.capsule(a, end, ra, rb, if (far) GuidePaint.BODY_SHADE else GuidePaint.BODY)
            // A slim shaded flank gives volume; muscle colour occupies a restrained central inset.
            if (!far) {
                val n = normal(unit(end - a))
                b.capsule(mix(a, end, 0.1f) + n * (ra * 0.48f), mix(a, end, 0.87f) + n * (rb * 0.48f),
                    ra * 0.27f, rb * 0.22f, GuidePaint.BODY_SHADE, 0.25f)
            }
            if (highlight) b.capsule(mix(a, end, 0.19f), mix(a, end, 0.76f),
                ra * 0.65f, rb * 0.6f, GuidePaint.ACCENT, if (far) 0.42f else 0.85f)
        }
        fun shoe(knee: Pt, ankle: Pt, right: Boolean, far: Boolean) {
            val grounded = !guide.props.bar && abs(ankle.y - GuideScene.ANKLE_Y) < 0.018f
            val shin = unit(ankle - knee)
            val forward = if (side) {
                if (grounded) Pt(1f, 0f) else unit(Pt(abs(shin.y).coerceAtLeast(0.45f), -shin.x))
            } else Pt(if (right) -1f else 1f, 0f)
            val down = if (side) normal(forward) else Pt(0f, 1f)
            val heel = ankle - forward * 0.014f + down * 0.004f
            val toe = ankle + forward * (if (side) 0.05f else 0.028f) + down * 0.01f
            val paint = if (far) GuidePaint.BODY_SHADE else GuidePaint.BODY
            b.path(paint, ops = listOf(
                GuidePathOp.Move(heel - down * 0.009f),
                GuidePathOp.Cubic(ankle - down * 0.026f, ankle + forward * 0.024f - down * 0.008f, toe - down * 0.008f),
                GuidePathOp.Cubic(toe + forward * 0.012f - down * 0.006f, toe + forward * 0.012f + down * 0.01f, toe + down * 0.012f),
                GuidePathOp.Line(heel + down * 0.016f),
                GuidePathOp.Cubic(heel - forward * 0.008f + down * 0.016f, heel - forward * 0.006f, heel - down * 0.009f),
                GuidePathOp.Close,
            ))
            b.line(heel + down * 0.012f, toe + down * 0.009f, GuidePaint.DETAIL, 0.004f, if (far) 0.5f else 0.7f)
        }
        fun crease(a: Pt, joint: Pt, end: Pt, radius: Float, far: Boolean) {
            val n = normal(unit(end - a))
            b.path(GuidePaint.DETAIL, if (far) 0.22f else 0.32f, 0.002f, listOf(
                GuidePathOp.Move(joint - n * (radius * 0.65f)),
                GuidePathOp.Cubic(joint - n * (radius * 0.23f), joint + n * (radius * 0.23f), joint + n * (radius * 0.65f)),
            ))
        }
        fun leg(right: Boolean, far: Boolean) {
            // Small fixed projection offset exposes the far silhouette when joint data overlap.
            val depth = if (far && side) Pt(-0.008f, 0f) else Pt(0f, 0f)
            val hip = pose[if (right) R_HIP else L_HIP] + depth
            val knee = pose[if (right) R_KNEE else L_KNEE] + depth
            val ankle = pose[if (right) R_ANKLE else L_ANKLE] + depth
            val hl = GuideHighlight.LEGS in guide.highlight
            limb(hip, knee, 0.032f, 0.021f, far, hl)
            limb(knee, ankle, 0.021f, 0.012f, far, hl)
            crease(hip, knee, ankle, 0.019f, far)
            shoe(knee, ankle, right, far)
        }
        fun arm(right: Boolean, far: Boolean) {
            val depth = if (far && side) Pt(-0.008f, 0f) else Pt(0f, 0f)
            val shoulder = pose[if (right) R_SHOULDER else L_SHOULDER] + depth
            val elbow = pose[if (right) R_ELBOW else L_ELBOW] + depth
            val wrist = pose[if (right) R_WRIST else L_WRIST] + depth
            val hl = GuideHighlight.ARMS in guide.highlight || (right && GuideHighlight.WORKING_ARM in guide.highlight)
            limb(shoulder, elbow, 0.023f, 0.016f, far, hl)
            limb(elbow, wrist, 0.0175f, 0.010f, far, hl)
            crease(shoulder, elbow, wrist, 0.014f, far)
            val u = unit(wrist - elbow)
            val n = normal(u)
            b.ellipse(wrist + u * 0.009f, n * 0.012f, u * 0.020f,
                if (far) GuidePaint.BODY_SHADE else GuidePaint.BODY)
            b.ellipse(wrist + u * 0.004f + n * 0.009f, n * 0.006f, u * 0.011f,
                if (far) GuidePaint.BODY_SHADE else GuidePaint.BODY)
        }
        fun torso() {
            val pelvis = pose[PELVIS]
            val neck = pose[NECK]
            val u = unit(neck - pelvis)
            val n = normal(u)
            fun at(t: Float, w: Float) = mix(pelvis, neck, t) + n * w
            val shoulder = if (side) 0.042f else 0.074f
            val waist = if (side) 0.027f else 0.041f
            val hip = if (side) 0.036f else 0.052f
            val contour = listOf(
                GuidePathOp.Move(at(0.92f, -shoulder * 0.67f)),
                GuidePathOp.Cubic(at(0.9f, -shoulder), at(0.75f, -shoulder), at(0.58f, -waist * 1.14f)),
                GuidePathOp.Cubic(at(0.38f, -waist), at(0.23f, -waist), at(0.08f, -hip)),
                GuidePathOp.Cubic(at(-0.12f, -hip), at(-0.12f, hip), at(0.08f, hip)),
                GuidePathOp.Cubic(at(0.23f, waist), at(0.38f, waist), at(0.58f, waist * 1.14f)),
                GuidePathOp.Cubic(at(0.75f, shoulder), at(0.9f, shoulder), at(0.92f, shoulder * 0.67f)),
                GuidePathOp.Cubic(at(1.01f, shoulder * 0.27f), at(1.01f, -shoulder * 0.27f), at(0.92f, -shoulder * 0.67f)),
                GuidePathOp.Close,
            )
            b.path(GuidePaint.BODY, ops = contour)
            b.path(GuidePaint.BODY_SHADE, 0.2f, ops = listOf(
                GuidePathOp.Move(at(0.86f, -shoulder * 0.66f)),
                GuidePathOp.Cubic(at(0.55f, -waist * 0.85f), at(0.24f, -waist * 0.65f), at(0.02f, -hip * 0.72f)),
                GuidePathOp.Cubic(at(-0.08f, -hip * 0.32f), at(0.16f, -hip * 0.18f), at(0.25f, -waist * 0.25f)),
                GuidePathOp.Cubic(at(0.5f, -waist * 0.38f), at(0.7f, -shoulder * 0.3f), at(0.86f, -shoulder * 0.66f)),
                GuidePathOp.Close,
            ))
            if (GuideHighlight.TORSO in guide.highlight) {
                b.ellipse(at(0.43f, 0f), n * (waist * 0.65f), u * 0.052f, GuidePaint.ACCENT, 0.7f)
            }
            b.line(at(0.16f, -hip * 0.65f), at(0.16f, hip * 0.65f), GuidePaint.BODY_SHADE, 0.003f, 0.5f)
            // Neck is a separate volume rather than a long line connecting a floating circle.
            val head = pose[HEAD]
            val hu = unit(head - neck)
            val hn = normal(hu)
            b.capsule(neck - hu * 0.006f, head - hu * 0.027f, 0.017f, 0.015f, GuidePaint.BODY)
            b.ellipse(head, hn * 0.034f, hu * 0.046f, GuidePaint.BODY)
            b.ellipse(head - hn * 0.022f - hu * 0.004f, hn * 0.009f, hu * 0.027f, GuidePaint.BODY_SHADE, 0.2f)
            if (side) {
                // Minimal nose and ear make the viewing direction legible without facial clutter.
                val nose = head + hn * 0.032f + hu * 0.002f
                b.path(GuidePaint.BODY, ops = listOf(
                    GuidePathOp.Move(nose + hu * 0.01f),
                    GuidePathOp.Line(nose + hn * 0.01f - hu * 0.004f),
                    GuidePathOp.Line(nose - hu * 0.012f), GuidePathOp.Close,
                ))
                b.ellipse(head - hn * 0.01f - hu * 0.003f, hn * 0.0045f, hu * 0.008f, GuidePaint.BODY_SHADE, 0.38f)
            }
        }

        if (side) {
            leg(false, true)
            dumbbell(b, guide.props.dumbbells, pose, right = false, far = true, side = true)
            arm(false, true)
            torso()
            leg(true, false)
            bell(b, guide, pose)
            arm(true, false)
            // The near plate/sleeve faces the camera and occludes the gripping hand.
            guide.props.barbell?.let { barbell(b, it, pose, side = true) }
            dumbbell(b, guide.props.dumbbells, pose, right = true, far = false, side = true)
        } else {
            leg(false, false)
            leg(true, false)
            torso()
            bell(b, guide, pose)
            if (guide.props.barbell != null && guide.props.barbell.grip != BarbellGrip.SHOULDERS) barbell(b, guide.props.barbell, pose, side = false)
            dumbbell(b, guide.props.dumbbells, pose, right = false, far = false, side = false)
            dumbbell(b, guide.props.dumbbells, pose, right = true, far = false, side = false)
            arm(false, false)
            arm(true, false)
        }
        return b.shapes
    }

    private fun bench(b: Builder, bench: Bench) {
        val floor = GuideScene.FLOOR - 0.008f
        val seatEnd = bench.seatEnd ?: bench.hip
        val support = mix(bench.hip, bench.head, 0.72f)
        val foot0 = Pt(seatEnd.x, floor)
        val foot1 = Pt(support.x, floor)
        b.line(seatEnd, foot0, GuidePaint.CHAIR, 0.014f)
        b.line(support, foot1, GuidePaint.CHAIR, 0.014f)
        b.line(foot0 - Pt(0.04f, 0f), foot0 + Pt(0.04f, 0f), GuidePaint.CHAIR, 0.012f)
        b.line(foot1 - Pt(0.04f, 0f), foot1 + Pt(0.04f, 0f), GuidePaint.CHAIR, 0.012f)
        b.capsule(bench.hip, bench.head, 0.019f, 0.019f, GuidePaint.CHAIR)
        b.line(bench.hip, bench.head, GuidePaint.METAL, 0.003f, 0.55f)
        bench.seatEnd?.let {
            b.capsule(bench.hip, it, 0.019f, 0.019f, GuidePaint.CHAIR)
        }
    }

    private fun cableMachine(b: Builder, machine: CableMachine, pose: GuidePose) {
        val grip = when (machine.grip) {
            CableGrip.HANDS -> mix(pose[R_WRIST], pose[L_WRIST], 0.5f)
            CableGrip.RIGHT_HAND -> pose[R_WRIST]
            CableGrip.LEFT_HAND -> pose[L_WRIST]
        }
        b.line(machine.base, machine.top, GuidePaint.CHAIR, 0.018f)
        b.line(machine.base - Pt(0.06f, 0f), machine.base + Pt(0.06f, 0f), GuidePaint.CHAIR, 0.014f)
        b.line(machine.top, machine.pulley, GuidePaint.CHAIR, 0.012f)
        machine.seat?.let { seat ->
            val center = Pt((seat.x0 + seat.x1) / 2f, seat.seatY)
            b.line(center, Pt(center.x, machine.base.y), GuidePaint.CHAIR, 0.012f)
            b.line(Pt(center.x - 0.05f, machine.base.y), Pt(center.x + 0.05f, machine.base.y), GuidePaint.CHAIR, 0.012f)
            b.capsule(Pt(seat.x0, seat.seatY), Pt(seat.x1, seat.seatY), 0.014f, 0.014f, GuidePaint.CHAIR)
            val backX = if (seat.backLeft) seat.x0 else seat.x1
            b.capsule(Pt(backX, seat.seatY - 0.025f), Pt(backX, seat.seatY - Chair.BACK),
                0.014f, 0.014f, GuidePaint.CHAIR)
        }
        val route = listOf(machine.top) + machine.cableVia + machine.pulley + grip
        b.path(GuidePaint.METAL, stroke = 0.0035f, ops = listOf(GuidePathOp.Move(route.first())) +
            route.drop(1).map { GuidePathOp.Line(it) })
        for (pulley in machine.cableVia + machine.pulley) {
            b.ellipse(pulley, Pt(0.021f, 0f), Pt(0f, 0.021f), GuidePaint.METAL)
            b.ellipse(pulley, Pt(0.009f, 0f), Pt(0f, 0.009f), GuidePaint.DETAIL)
        }
        val axis = if (machine.grip == CableGrip.HANDS && hypot(pose[L_WRIST].x - pose[R_WRIST].x,
                pose[L_WRIST].y - pose[R_WRIST].y) > 0.03f) {
            unit(pose[L_WRIST] - pose[R_WRIST])
        } else normal(unit(grip - machine.pulley))
        val halfLength = if (machine.grip == CableGrip.HANDS) {
            (hypot(pose[L_WRIST].x - pose[R_WRIST].x, pose[L_WRIST].y - pose[R_WRIST].y) / 2f + 0.025f)
                .coerceAtLeast(0.045f)
        } else 0.032f
        b.line(grip - axis * halfLength, grip + axis * halfLength, GuidePaint.METAL, 0.012f)
    }

    /** End-on plate and sleeve: the shaft points towards the viewer in SIDE projection. */
    private fun endOnWeight(b: Builder, grip: Pt, radius: Float, alpha: Float = 1f) {
        b.ellipse(grip + Pt(-0.009f, -0.004f), Pt(radius, 0f), Pt(0f, radius), GuidePaint.DETAIL, alpha)
        b.ellipse(grip, Pt(radius, 0f), Pt(0f, radius), GuidePaint.METAL, alpha)
        b.ellipse(grip, Pt(radius * 0.76f, 0f), Pt(0f, radius * 0.76f), GuidePaint.DETAIL, alpha * 0.55f)
        b.ellipse(grip, Pt(0.012f, 0f), Pt(0f, 0.012f), GuidePaint.DETAIL, alpha)
        b.ellipse(grip, Pt(0.007f, 0f), Pt(0f, 0.007f), GuidePaint.METAL, alpha)
        b.ellipse(grip + Pt(-0.002f, -0.002f), Pt(0.003f, 0f), Pt(0f, 0.003f), GuidePaint.BODY, alpha)
    }

    private fun barbell(b: Builder, barbell: Barbell, pose: GuidePose, side: Boolean) {
        val grip = when (barbell.grip) {
            BarbellGrip.HANDS -> mix(pose[R_WRIST], pose[L_WRIST], 0.5f)
            BarbellGrip.SHOULDERS, BarbellGrip.FRONT_RACK -> mix(pose[R_SHOULDER], pose[L_SHOULDER], 0.5f)
        } + barbell.offset
        if (side) {
            endOnWeight(b, grip, barbell.plateRadius)
            return
        }
        val half = barbell.halfLength
        b.line(grip - Pt(half, 0f), grip + Pt(half, 0f), GuidePaint.METAL, 0.009f)
        b.line(grip - Pt(half, 0.002f), grip + Pt(half, -0.002f), GuidePaint.BODY, 0.002f, 0.6f)
        for (sign in listOf(-1f, 1f)) {
            val plate = grip + Pt(sign * (half - 0.025f), 0f)
            b.ellipse(plate, Pt(0.014f, 0f), Pt(0f, barbell.plateRadius), GuidePaint.METAL)
            b.ellipse(plate + Pt(sign * 0.012f, 0f), Pt(0.009f, 0f), Pt(0f, barbell.plateRadius * 0.84f), GuidePaint.CHAIR)
            b.line(plate - Pt(0f, barbell.plateRadius * 0.7f), plate + Pt(0f, barbell.plateRadius * 0.7f),
                GuidePaint.DETAIL, 0.003f, 0.55f)
            b.line(grip + Pt(sign * (half - 0.048f), -0.012f), grip + Pt(sign * (half - 0.048f), 0.012f),
                GuidePaint.METAL, 0.008f)
        }
    }

    private fun dumbbell(b: Builder, dumbbells: Dumbbells?, pose: GuidePose, right: Boolean, far: Boolean, side: Boolean) {
        if (dumbbells == null) return
        val draw = when (dumbbells.grip) {
            DumbbellGrip.EACH_HAND -> true
            DumbbellGrip.RIGHT_HAND, DumbbellGrip.BOTH_HANDS, DumbbellGrip.GOBLET -> right
            DumbbellGrip.LEFT_HAND -> !right
        }
        if (!draw) return
        val depth = if (far) Pt(-0.008f, 0f) else Pt(0f, 0f)
        val wrist = pose[if (right) R_WRIST else L_WRIST] + depth
        val elbow = pose[if (right) R_ELBOW else L_ELBOW] + depth
        val grip = when (dumbbells.grip) {
            DumbbellGrip.BOTH_HANDS -> mix(pose[R_WRIST], pose[L_WRIST], 0.5f)
            DumbbellGrip.GOBLET -> mix(pose[R_WRIST], pose[L_WRIST], 0.5f) + Pt(0f, 0.032f)
            else -> wrist
        }
        val axis = if (dumbbells.grip == DumbbellGrip.GOBLET) Pt(0f, 1f) else normal(unit(wrist - elbow))
        val cross = normal(axis)
        val alpha = if (far) 0.65f else 1f
        // Goblet is held vertically: its shaft stays in the image plane even from the side.
        if (side && dumbbells.grip != DumbbellGrip.GOBLET) {
            endOnWeight(b, grip, 0.034f, alpha)
            return
        }
        b.line(grip - axis * 0.043f, grip + axis * 0.043f, GuidePaint.METAL, 0.009f, alpha)
        for (sign in listOf(-1f, 1f)) {
            val center = grip + axis * (sign * 0.032f)
            b.capsule(center - cross * 0.022f, center + cross * 0.022f, 0.012f, 0.012f, GuidePaint.METAL, alpha)
            b.line(center - cross * 0.018f, center + cross * 0.018f, GuidePaint.DETAIL, 0.003f, alpha * 0.55f)
        }
    }

    private fun bell(b: Builder, guide: ExerciseGuide, pose: GuidePose) {
        val bell = guide.props.bell ?: return
        val r = GuideScene.BELL_RADIUS
        val down = if (bell.hang == BellHang.ALONG_FOREARM) unit(pose[R_WRIST] - pose[R_ELBOW]) else Pt(0f, 1f)
        val n = normal(down)
        val grip = when (bell.grip) {
            BellGrip.HANDS -> mix(pose[R_WRIST], pose[L_WRIST], 0.5f)
            BellGrip.RIGHT_HAND -> pose[R_WRIST]
            BellGrip.PELVIS -> pose[PELVIS] - Pt(0f, r * 2.5f)
        }
        val c = grip + down * (r * 1.45f)
        fun at(x: Float, y: Float) = c + n * (x * r) + down * (y * r)
        // Closed rounded handle with a visible open center: a stroked arch, not a second ball.
        b.path(GuidePaint.ACCENT, stroke = 0.009f, ops = listOf(
            GuidePathOp.Move(at(-0.57f, -0.65f)),
            GuidePathOp.Cubic(at(-0.9f, -1.7f), at(0.9f, -1.7f), at(0.57f, -0.65f)),
        ))
        b.path(GuidePaint.ACCENT, ops = listOf(
            GuidePathOp.Move(at(-0.6f, -0.8f)),
            GuidePathOp.Cubic(at(-1.25f, -0.35f), at(-1.2f, 0.6f), at(-0.55f, 0.86f)),
            GuidePathOp.Line(at(0.55f, 0.86f)),
            GuidePathOp.Cubic(at(1.2f, 0.6f), at(1.25f, -0.35f), at(0.6f, -0.8f)),
            GuidePathOp.Close,
        ))
        b.path(GuidePaint.DETAIL, 0.23f, ops = listOf(
            GuidePathOp.Move(at(0.45f, -0.66f)),
            GuidePathOp.Cubic(at(1.0f, -0.24f), at(0.98f, 0.48f), at(0.45f, 0.74f)),
            GuidePathOp.Line(at(0.03f, 0.74f)),
            GuidePathOp.Cubic(at(0.61f, 0.32f), at(0.63f, -0.2f), at(0.45f, -0.66f)),
            GuidePathOp.Close,
        ))
        b.line(at(-0.48f, 0.8f), at(0.48f, 0.8f), GuidePaint.DETAIL, 0.004f, 0.5f)
        b.ellipse(at(-0.4f, -0.25f), n * 0.004f, down * 0.011f, GuidePaint.BODY, 0.32f)
    }
}


