package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AngularProfileTest {
    private fun profile(
        side: AngularSide = AngularSide.BEST_VISIBLE,
        visibility: AngularVisibility = AngularVisibility(),
        hysteresis: Float = 0f,
        positions: List<AngularPosition> = emptyList(),
        minCycleMs: Long = 500L,
    ) = AngularProfile(
        id = "test_elbow", signal = Feature.ELBOW,
        startThreshold = 150f, farThreshold = 100f, hysteresis = hysteresis,
        side = side, visibility = visibility, positions = positions, minCycleMs = minCycleMs,
        postureHint = "Встань прямо", startHint = "Выпрями руки", farHint = "Согни руки", finishHint = "Выпрями руки",
    )

    private class Motion(val counter: RepCounter) {
        var time = 0L

        fun hold(
            left: Float?, right: Float? = left, duration: Long = 400L,
            confidence: Float = 0.95f, showHip: Boolean = true,
        ): Motion {
            repeat((duration / 50L).toInt()) {
                time += 50L
                counter.onFrame(frame(time, left, right, confidence, showHip))
            }
            return this
        }

        fun move(leftFrom: Float, leftTo: Float, rightFrom: Float = leftFrom, rightTo: Float = leftTo): Motion {
            repeat(12) { step ->
                val fraction = (step + 1) / 12f
                hold(leftFrom + (leftTo - leftFrom) * fraction, rightFrom + (rightTo - rightFrom) * fraction, 50L)
            }
            return this
        }

        fun rep(start: Float = 170f, far: Float = 80f): Motion =
            hold(start).move(start, far).hold(far).move(far, start).hold(start)

        companion object {
            fun frame(time: Long, left: Float?, right: Float?, confidence: Float, showHip: Boolean): PoseFrame {
                val points = mutableMapOf<Joint, PosePoint>()
                for ((side, angle) in listOf(Side.LEFT to left, Side.RIGHT to right)) {
                    if (angle == null) continue
                    val x = if (side == Side.LEFT) 350f else 650f
                    val radians = Math.toRadians(angle.toDouble())
                    points[side.joint(Limb.SHOULDER)] = PosePoint(x, 300f, confidence)
                    points[side.joint(Limb.ELBOW)] = PosePoint(x, 400f, confidence)
                    points[side.joint(Limb.WRIST)] = PosePoint(x + sin(radians).toFloat() * 100f, 400f - cos(radians).toFloat() * 100f, confidence)
                    if (showHip) points[side.joint(Limb.HIP)] = PosePoint(x, 550f, confidence)
                }
                return PoseFrame(time, 1000, 1000, points)
            }
        }
    }

    @Test
    fun fullCyclesCountAndHalfCyclesDoNot() {
        val motion = Motion(profile().counter())
        repeat(3) { motion.rep() }
        assertEquals(3, motion.counter.state.count)
        repeat(3) { motion.rep(far = 125f) }
        assertEquals(3, motion.counter.state.count)
    }

    @Test
    fun startPositionMustBeSeenBeforeFirstCycle() {
        val motion = Motion(profile().counter()).hold(80f).move(80f, 170f).hold(170f)
        assertEquals(0, motion.counter.state.count)
        motion.rep()
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun lossOfPersonDiscardsUnfinishedCycleAndKeepsCompletedReps() {
        val motion = Motion(profile().counter()).rep().hold(170f).move(170f, 80f).hold(80f)
        motion.hold(null, null, duration = 2000L)
        assertFalse(motion.counter.state.tracking)
        motion.hold(170f)
        assertEquals(1, motion.counter.state.count)
        motion.rep()
        assertEquals(2, motion.counter.state.count)
    }

    @Test
    fun fixedSideDoesNotUseVisibleOppositeSide() {
        val motion = Motion(profile(side = AngularSide.RIGHT).counter()).hold(170f, null)
        assertFalse(motion.counter.state.tracking)
        motion.rep()
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun bothSidesMustReachEndpointsTogether() {
        val motion = Motion(profile(side = AngularSide.BOTH).counter()).hold(170f)
        motion.move(170f, 80f, 170f, 130f).hold(80f, 130f).move(80f, 170f, 130f, 170f).hold(170f)
        assertEquals(0, motion.counter.state.count)
        motion.rep()
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun bothSidesRequireVisibility() {
        val motion = Motion(profile(side = AngularSide.BOTH).counter()).hold(170f, null)
        assertFalse(motion.counter.state.tracking)
        motion.hold(170f)
        assertTrue(motion.counter.state.tracking)
    }

    @Test
    fun configuredConfidenceAndRequiredJointsAreEnforced() {
        val motion = Motion(profile(visibility = AngularVisibility(setOf(Limb.HIP), minConfidence = 0.9f)).counter())
        motion.hold(170f, confidence = 0.8f)
        assertFalse(motion.counter.state.tracking)
        motion.hold(170f, showHip = false)
        assertFalse(motion.counter.state.tracking)
        motion.rep()
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun freshRequiredJointCannotBeReplacedWithItsPreviousPosition() {
        val motion = Motion(profile(visibility = AngularVisibility(setOf(Limb.HIP), requireFresh = true)).counter())
        motion.hold(170f)
        assertTrue(motion.counter.state.tracking)
        motion.hold(170f, duration = 50L, showHip = false)
        assertFalse(motion.counter.state.tracking)
        motion.hold(170f, duration = 50L)
        assertTrue(motion.counter.state.tracking)

        val tolerant = Motion(profile(visibility = AngularVisibility(setOf(Limb.HIP), requireFresh = false)).counter())
        tolerant.hold(170f).hold(170f, duration = 50L, showHip = false)
        assertTrue(tolerant.counter.state.tracking)
    }

    @Test
    fun bestVisibleSideSwitchCannotCompleteTheOtherArmsUnfinishedRep() {
        val motion = Motion(profile().counter()).hold(170f, null).move(170f, 80f).hold(80f, null)
        assertEquals(0, motion.counter.state.count)
        // Левая рука ушла в нижнюю точку. После её исчезновения видна правая, уже прямая.
        motion.hold(null, 170f, duration = 700L)
        assertEquals(0, motion.counter.state.count)
        motion.rep()
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun shoulderAngleMeasuresArmAlongTorsoAndAtShoulderHeight() {
        fun measured(elbow: PosePoint): Float {
            val joints = JointTracker(Side.LEFT)
            joints.update(PoseFrame(0L, 1000, 1000, mapOf(
                Joint.LEFT_HIP to PosePoint(400f, 600f, 0.95f),
                Joint.LEFT_SHOULDER to PosePoint(400f, 400f, 0.95f),
                Joint.LEFT_ELBOW to elbow,
            )))
            return Sample.measure(0L, joints)[Feature.SHOULDER_ANGLE]
        }
        assertEquals(0f, measured(PosePoint(400f, 500f, 0.95f)), 0.01f)
        assertEquals(90f, measured(PosePoint(500f, 400f, 0.95f)), 0.01f)
    }

    @Test
    fun profileChecksPostureOnEntry() {
        val upright = AngularPosition(Feature.TORSO_TILT, enterMin = 30f, enterMax = 60f)
        val motion = Motion(profile(positions = listOf(upright)).counter()).rep()
        assertTrue(motion.counter.state.tracking)
        assertFalse(motion.counter.state.inPosition)
        assertEquals(0, motion.counter.state.count)
    }

    @Test
    fun hysteresisRequiresTravelBeyondNominalThresholds() {
        val motion = Motion(profile(hysteresis = 5f).counter()).rep(far = 98f)
        assertEquals(0, motion.counter.state.count)
        motion.rep(far = 90f)
        assertEquals(1, motion.counter.state.count)
    }

    @Test
    fun minimumCycleRejectsQuickMovement() {
        val motion = Motion(profile(minCycleMs = 5000L).counter()).rep()
        assertEquals(0, motion.counter.state.count)
    }

    @Test
    fun increasingSignalWorksWithTheSameEngine() {
        val increasing = profile().copy(startThreshold = 100f, farThreshold = 150f)
        val motion = Motion(increasing.counter()).rep(start = 80f, far = 170f)
        assertEquals(1, motion.counter.state.count)
    }
}
