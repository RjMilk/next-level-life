package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Проверяем технику по суставам, независимо от визуального стиля. */
class GuideBiomechanicsTest {
    private fun guide(id: String) = (BodyweightGuides.guides + KettlebellGuides.guides + RoutineGuides.guides).first { it.exerciseId == id }
    private fun pose(id: String, frame: Int = 1) = guide(id).frames[frame].pose
    private fun distance(p: GuidePose, a: GuideJoint, b: GuideJoint) = hypot(p[a].x - p[b].x, p[a].y - p[b].y)
    private fun straight(p: GuidePose, a: GuideJoint, mid: GuideJoint, b: GuideJoint, tolerance: Float = 0.003f) {
        assertEquals("$a-$mid-$b", distance(p, a, mid) + distance(p, mid, b), distance(p, a, b), tolerance)
    }

    @Test fun sumoPullElbowsTravelOutwardAndAboveHands() {
        val p = pose("sumo_pull")
        assertTrue(p[R_ELBOW].x < p[R_SHOULDER].x - 0.03f)
        assertTrue(p[L_ELBOW].x > p[L_SHOULDER].x + 0.03f)
        assertTrue(p[R_ELBOW].y < p[R_WRIST].y - 0.01f)
        assertTrue(p[L_ELBOW].y < p[L_WRIST].y - 0.01f)
        for ((elbow, shoulder) in listOf(R_ELBOW to R_SHOULDER, L_ELBOW to L_SHOULDER)) {
            assertTrue("elbow below eye level", p[elbow].y >= p[HEAD].y)
            assertTrue("elbow near shoulder height", abs(p[elbow].y - p[shoulder].y) < 0.035f)
            assertEquals("relaxed shoulder", p[NECK].y + GuideScene.SHOULDER_DROP, p[shoulder].y, 0.001f)
        }
        val chinY = p[HEAD].y + 0.046f
        val gripY = (p[R_WRIST].y + p[L_WRIST].y) / 2f
        assertTrue("bell handle below chin", gripY - 0.009f >= chinY)
        val bellCenterY = gripY + GuideScene.BELL_RADIUS * 1.45f
        assertTrue("bell at upper chest", bellCenterY < (p[NECK].y + p[PELVIS].y) / 2f + 0.04f)
        for (f in guide("sumo_pull").frames) {
            assertTrue(f.pose[R_KNEE].x <= f.pose[R_HIP].x)
            assertTrue(f.pose[L_KNEE].x >= f.pose[L_HIP].x)
        }
    }

    @Test fun gobletAndThrusterKeepBellAtChestAndSquatToParallel() {
        for (id in listOf("goblet_squat", "thruster")) {
            for (i in listOf(0, 1)) {
                val p = pose(id, i)
                assertTrue("$id rack distance", distance(p, R_SHOULDER, R_WRIST) < 0.09f)
                assertTrue("$id elbows down", p[R_ELBOW].y > p[R_WRIST].y)
            }
            val bottom = pose(id)
            assertEquals(id, bottom[R_HIP].y, bottom[R_KNEE].y, 0.005f)
        }
        val squat = pose("squats")
        assertEquals(squat[R_HIP].y, squat[R_KNEE].y, 0.005f)
        val overhead = pose("thruster", 3)
        straight(overhead, R_SHOULDER, R_ELBOW, R_WRIST)
        assertTrue(abs(overhead[R_WRIST].x - overhead[R_SHOULDER].x) < 0.02f)
    }

    @Test fun swingUsesStraightArmsAndStopsAtChest() {
        for (f in guide("swings").frames) straight(f.pose, R_SHOULDER, R_ELBOW, R_WRIST)
        val top = pose("swings", 2)
        assertEquals(top[R_SHOULDER].y, top[R_WRIST].y, 0.005f)
        val hinge = pose("swings", 0)
        assertTrue(hinge[NECK].x > hinge[PELVIS].x + 0.14f)
        assertTrue(hinge[R_WRIST].x < hinge[PELVIS].x)
    }

    @Test fun pressFinishesAboveShoulderWithoutLeaningOrDipping() {
        val a = pose("press", 0)
        val b = pose("press")
        assertEquals(a[PELVIS], b[PELVIS])
        assertEquals(a[NECK], b[NECK])
        assertEquals(b[R_SHOULDER].x, b[R_WRIST].x, 0.001f)
        straight(b, R_SHOULDER, R_ELBOW, R_WRIST)
        assertTrue(a[R_ELBOW].y > a[R_WRIST].y)
    }

    @Test fun rowKeepsHipHingeAndSupportHandAtKnee() {
        val a = pose("row", 0)
        val b = pose("row")
        assertEquals(a[NECK], b[NECK])
        assertEquals(a[PELVIS], b[PELVIS])
        for (p in listOf(a, b)) assertTrue(distance(p, L_WRIST, L_KNEE) < 0.04f)
        straight(a, R_SHOULDER, R_ELBOW, R_WRIST)
        assertTrue(b[R_ELBOW].x < b[R_SHOULDER].x)
        assertTrue(distance(b, R_WRIST, PELVIS) < 0.09f)
    }

    @Test fun reverseLungeFrontShinIsVerticalAndBackKneeNearFloor() {
        val p = pose("reverse_lunge")
        assertEquals(p[R_KNEE].x, p[R_ANKLE].x, 0.001f)
        assertTrue(p[L_KNEE].y in 0.84f..0.89f)
        assertTrue(p[L_ANKLE].x < p[PELVIS].x)
    }

    @Test fun bridgeAndPlankHaveAlignedLoadBearingChain() {
        val bridge = pose("glute_bridge")
        straight(bridge, R_SHOULDER, PELVIS, R_KNEE, 0.004f)
        assertTrue(abs(bridge[R_KNEE].x - bridge[R_ANKLE].x) < 0.03f)
        val plank = pose("plank")
        straight(plank, R_SHOULDER, PELVIS, R_ANKLE)
        assertEquals(plank[R_SHOULDER].x, plank[R_ELBOW].x, 0.001f)
        assertEquals(0.88f, plank[R_ELBOW].y, 0.001f)
    }

    @Test fun pullupUsesStraightHangAndFixedGrips() {
        val a = pose("pullups", 0)
        val b = pose("pullups")
        straight(a, R_SHOULDER, R_ELBOW, R_WRIST)
        assertEquals(a[R_WRIST].x, b[R_WRIST].x, 0.001f)
        assertEquals(a[R_WRIST].y, b[R_WRIST].y, 0.001f)
        assertEquals(a[L_WRIST].x, b[L_WRIST].x, 0.001f)
        assertEquals(a[L_WRIST].y, b[L_WRIST].y, 0.001f)
        assertTrue(b[HEAD].y + GuideScene.HEAD_RADIUS < b[R_WRIST].y)
    }

    @Test fun floorYogaKeepsHandsOnMatAndWarriorBackLegStraight() {
        for (id in listOf("puppy", "child")) assertEquals(id, 0.88f, pose(id)[R_WRIST].y, 0.003f)
        val sphinx = pose("sphinx")
        assertEquals(sphinx[R_SHOULDER].x, sphinx[R_ELBOW].x, 0.001f)
        assertEquals(0.88f, sphinx[R_ELBOW].y, 0.004f)
        val warrior = pose("warrior2")
        straight(warrior, L_HIP, L_KNEE, L_ANKLE)
        assertEquals(warrior[R_KNEE].x, warrior[R_ANKLE].x, 0.004f)
    }

    @Test fun heelSlideKeepsBothHeelsAtSameFloorHeight() {
        for (f in guide("heel_slide").frames) {
            assertEquals(GuideScene.ANKLE_Y, f.pose[R_ANKLE].y, 0.001f)
            assertEquals(GuideScene.ANKLE_Y, f.pose[L_ANKLE].y, 0.001f)
        }
        assertEquals(pose("heel_slide", 0)[L_ANKLE], pose("heel_slide")[L_ANKLE])
    }
}
