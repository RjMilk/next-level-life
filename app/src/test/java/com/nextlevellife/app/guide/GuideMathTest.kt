package com.nextlevellife.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GuideMathTest {
    private fun len(p: GuidePose, a: GuideJoint, b: GuideJoint) = hypot(p[a].x - p[b].x, p[a].y - p[b].y)

    private val stand = GuidePoses.side(torso = 0f, pin = onFloor(0.5f))
    private val squat = GuidePoses.side(torso = 40f, thigh = 95f, shin = 200f, upperArm = 90f, forearm = 90f, pin = onFloor(0.5f))

    @Test
    fun easeIsSmoothAndSymmetric() {
        assertEquals(0f, GuideMath.ease(0f), 1e-6f)
        assertEquals(1f, GuideMath.ease(1f), 1e-6f)
        assertEquals(0.5f, GuideMath.ease(0.5f), 1e-6f)
        assertEquals(1f - GuideMath.ease(0.3f), GuideMath.ease(0.7f), 1e-5f)
        // Медленно в начале и в конце.
        assertTrue(GuideMath.ease(0.1f) < 0.1f)
        assertTrue(GuideMath.ease(0.9f) > 0.9f)
        var prev = 0f
        for (i in 1..100) {
            val e = GuideMath.ease(i / 100f)
            assertTrue(e >= prev)
            prev = e
        }
        // За пределами 0…1 — зажато.
        assertEquals(0f, GuideMath.ease(-1f), 1e-6f)
        assertEquals(1f, GuideMath.ease(2f), 1e-6f)
    }

    @Test
    fun lerpEndsAreTheKeyframes() {
        assertSame(stand, GuideMath.lerp(stand, squat, 0f))
        assertSame(squat, GuideMath.lerp(stand, squat, 1f))
        val almost = GuideMath.lerp(stand, squat, 0.9999f)
        for (j in GuideJoint.entries) {
            assertEquals("$j", squat[j].x, almost[j].x, 1e-3f)
            assertEquals("$j", squat[j].y, almost[j].y, 1e-3f)
        }
    }

    @Test
    fun lerpKeepsBoneLengthsAndPinnedFeet() {
        for (k in listOf(0.25f, 0.5f, 0.75f)) {
            val p = GuideMath.lerp(stand, squat, k)
            for ((a, b) in GuideScene.BONES) {
                assertEquals("$a-$b при $k", len(stand, a, b), len(p, a, b), 1e-4f)
            }
            // Стопы стоят на месте в обоих кадрах — не скользят.
            assertEquals(stand[GuideJoint.R_ANKLE].x, p[GuideJoint.R_ANKLE].x, 1e-5f)
            assertEquals(stand[GuideJoint.L_ANKLE].y, p[GuideJoint.L_ANKLE].y, 1e-5f)
        }
        // Таз опускается монотонно.
        val ys = (0..10).map { GuideMath.lerp(stand, squat, it / 10f)[GuideJoint.PELVIS].y }
        assertTrue(ys.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
    }

    @Test
    fun pinnedWristStaysOnBarWithOtherRoot() {
        val g = Guides.of("pullups")!!
        val a = g.frames[0].pose
        val b = g.frames[1].pose
        val mid = GuideMath.lerp(a, b, 0.5f, g.anchor)
        assertEquals(a[GuideJoint.L_WRIST].x, mid[GuideJoint.L_WRIST].x, 1e-5f)
        assertEquals(a[GuideJoint.L_WRIST].y, mid[GuideJoint.L_WRIST].y, 1e-5f)
        assertEquals(len(a, GuideJoint.L_SHOULDER, GuideJoint.L_ELBOW), len(mid, GuideJoint.L_SHOULDER, GuideJoint.L_ELBOW), 1e-4f)
        assertEquals(len(a, GuideJoint.L_ELBOW, GuideJoint.L_WRIST), len(mid, GuideJoint.L_ELBOW, GuideJoint.L_WRIST), 1e-4f)
    }

    @Test
    fun angleTakesShortestArc() {
        val a = Math.toRadians(170.0).toFloat()
        val b = Math.toRadians(-170.0).toFloat()
        val mid = GuideMath.lerpAngle(a, b, 0.5f)
        assertEquals(180.0, Math.toDegrees(mid.toDouble()), 1e-3)
    }

    @Test
    fun timelineHoldsMovesAndLoops() {
        val g = ExerciseGuide(
            exerciseId = "t", view = GuideView.SIDE,
            frames = listOf(GuideFrame(stand, 1000, 500), GuideFrame(squat, 1000, 500)),
            distance = "2 м", angle = "сбоку", camera = "к", focus = "ф", tips = listOf("а"), mistakes = listOf("б"),
        )
        assertEquals(3000L, GuideMath.cycleMs(g))
        assertSame(stand, GuideMath.poseAt(g, 0))
        assertSame(stand, GuideMath.poseAt(g, 499))
        // Середина перехода в присед: ease(0,5) = 0,5.
        val mid = GuideMath.poseAt(g, 1000)
        val expected = GuideMath.lerp(stand, squat, 0.5f)
        assertEquals(expected[GuideJoint.PELVIS].y, mid[GuideJoint.PELVIS].y, 1e-5f)
        assertSame(squat, GuideMath.poseAt(g, 1600))
        // Обратный переход и новый круг.
        val back = GuideMath.poseAt(g, 2500)
        assertEquals(expected[GuideJoint.PELVIS].y, back[GuideJoint.PELVIS].y, 1e-5f)
        assertSame(stand, GuideMath.poseAt(g, 3000))
        assertSame(stand, GuideMath.poseAt(g, 3000L * 7 + 100))
        // Без зацикливания — остановка в последнем кадре.
        val once = g.copy(loop = false)
        assertSame(squat, GuideMath.poseAt(once, 2400))
        assertSame(squat, GuideMath.poseAt(once, 100_000))
    }
}
