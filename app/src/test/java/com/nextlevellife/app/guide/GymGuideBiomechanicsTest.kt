package com.nextlevellife.app.guide

import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.guide.GuideJoint.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** Условия техники независимы от фабрик поз: измеряем полученные суставы. */
class GymGuideBiomechanicsTest {
    private fun guide(id: String) = GymGuides.guides.single { it.exerciseId == id }
    private fun pose(id: String, i: Int) = guide(id).frames[i].pose
    private fun angle(p: GuidePose, a: GuideJoint, b: GuideJoint, c: GuideJoint): Float {
        val u = Pt(p[a].x - p[b].x, p[a].y - p[b].y)
        val v = Pt(p[c].x - p[b].x, p[c].y - p[b].y)
        return Math.toDegrees(acos(((u.x * v.x + u.y * v.y) /
            (hypot(u.x, u.y) * hypot(v.x, v.y))).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
    private fun elbow(p: GuidePose) = angle(p, R_SHOULDER, R_ELBOW, R_WRIST)
    private fun knee(p: GuidePose) = angle(p, R_HIP, R_KNEE, R_ANKLE)
    private fun hip(p: GuidePose) = angle(p, R_SHOULDER, R_HIP, R_KNEE)
    private fun dist(p: GuidePose, a: GuideJoint, b: GuideJoint) = hypot(p[a].x - p[b].x, p[a].y - p[b].y)
    private fun samePoint(a: Pt, b: Pt) {
        assertEquals(a.x, b.x, 0.0001f)
        assertEquals(a.y, b.y, 0.0001f)
    }
    private fun sameBody(a: GuidePose, b: GuidePose) {
        for (j in listOf(PELVIS, NECK, R_HIP, R_KNEE, R_ANKLE)) assertEquals(j.name, a[j], b[j])
    }

    @Test fun allGymExercisesHaveTheirOwnGuideAndEquipment() {
        assertEquals(Exercises.gym.map { it.id }.toSet(), GymGuides.guides.map { it.exerciseId }.toSet())
        for (g in GymGuides.guides) {
            val p = g.props
            assertTrue(g.exerciseId, p.barbell != null || p.dumbbells != null || p.bench != null ||
                p.cableMachine != null || p.legMachine != null || p.assistedMachine != null)
            assertTrue(g.exerciseId, g.camera.contains("высоте"))
        }
    }

    @Test fun squatsKeepFeetGroundedAndReachParallel() {
        for (id in listOf("bb_squat", "bb_front_squat", "db_goblet_squat")) {
            val a = pose(id, 0); val b = pose(id, 1)
            assertTrue(id, knee(a) > 155f)
            assertTrue(id, knee(b) < 110f)
            assertEquals(id, b[R_HIP].y, b[R_KNEE].y, 0.003f)
            assertEquals(id, a[R_ANKLE], b[R_ANKLE])
            assertEquals(id, GuideScene.ANKLE_Y, b[R_ANKLE].y, 0.001f)
        }
        assertEquals(BarbellGrip.SHOULDERS, guide("bb_squat").props.barbell!!.grip)
        assertEquals(BarbellGrip.FRONT_RACK, guide("bb_front_squat").props.barbell!!.grip)
        for (f in guide("bb_front_squat").frames) {
            assertTrue("front-rack elbows forward", f.pose[R_ELBOW].x > f.pose[R_SHOULDER].x + 0.12f)
            assertEquals("front-rack elbows high", f.pose[R_SHOULDER].y, f.pose[R_ELBOW].y, 0.001f)
            assertTrue("front-rack grip on deltoids", dist(f.pose, R_WRIST, R_SHOULDER) < 0.02f)
        }
        assertEquals(DumbbellGrip.GOBLET, guide("db_goblet_squat").props.dumbbells!!.grip)
    }

    @Test fun romanianIsHipHingeWithOnlySoftKnees() {
        val a = pose("bb_romanian_deadlift", 0); val b = pose("bb_romanian_deadlift", 1)
        assertTrue(hip(a) > 165f)
        assertTrue(hip(b) < 115f)
        for (p in listOf(a, b)) {
            assertTrue("soft knees", knee(p) in 145f..175f)
            assertTrue("straight pulling arms", elbow(p) > 175f)
        }
        samePoint(a[R_ANKLE], b[R_ANKLE])
    }

    @Test fun deadliftCombinesKneeAndHipExtensionAndKeepsArmsStraight() {
        val a = pose("bb_deadlift", 0); val b = pose("bb_deadlift", 1)
        assertTrue(knee(a) > 155f)
        assertTrue(knee(b) < knee(a) - 30f)
        assertTrue(b[NECK].x > b[PELVIS].x + 0.15f)
        assertTrue(b[R_WRIST].y >= b[R_KNEE].y - 0.02f)
        for (p in listOf(a, b)) assertTrue(elbow(p) > 175f)
        samePoint(a[R_ANKLE], b[R_ANKLE])
        val plates = guide("bb_deadlift").props.barbell!!
        assertEquals("plates return to floor", GuideScene.FLOOR, b[R_WRIST].y + plates.plateRadius, 0.01f)
    }

    @Test fun benchPressesHaveFullElbowCycleSupportedBodyAndVerticalForearmAtBottom() {
        for (id in listOf("bb_bench_press", "bb_incline_press", "db_bench_press", "db_incline_press")) {
            val a = pose(id, 0); val b = pose(id, 1)
            assertTrue(id, elbow(a) < 105f)
            assertTrue(id, elbow(b) > 155f)
            sameBody(a, b)
            assertTrue(id, a[R_WRIST].y < a[R_ELBOW].y)
            assertTrue(id, abs(a[R_WRIST].x - a[R_ELBOW].x) < 0.065f)
            assertTrue(id, b[R_WRIST].y < b[R_SHOULDER].y - 0.18f)
            assertTrue("$id lowered over chest", a[R_WRIST].x < a[PELVIS].x)
            assertTrue("$id close to chest at bottom", a[R_SHOULDER].y - a[R_WRIST].y < 0.06f)
            assertEquals(id, GuideScene.ANKLE_Y, a[R_ANKLE].y, 0.001f)
            assertNotNull(guide(id).props.bench)
            val torsoRise = a[PELVIS].y - a[R_SHOULDER].y
            if (id.contains("incline")) assertTrue(id, torsoRise in 0.11f..0.15f)
            else assertEquals(id, 0f, torsoRise, 0.001f)
        }
    }

    @Test fun standingAndSeatedOverheadPressesKeepTrunkStableAndFinishAboveHead() {
        for (id in listOf("bb_press", "db_seated_press")) {
            val a = pose(id, 0); val b = pose(id, 1)
            sameBody(a, b)
            assertTrue(id, elbow(a) < 115f)
            assertTrue(id, elbow(b) > 155f)
            assertTrue(id, b[R_WRIST].y < b[HEAD].y - 0.06f)
            assertEquals(id, b[NECK].x, b[PELVIS].x, 0.001f)
        }
        assertTrue(knee(pose("db_seated_press", 0)) in 85f..95f)
    }

    @Test fun rowsPreserveHingeAndPullToHip() {
        for (id in listOf("bb_row", "db_row")) {
            val a = pose(id, 0); val b = pose(id, 1)
            sameBody(a, b)
            assertTrue(id, elbow(a) > 155f)
            assertTrue(id, elbow(b) < 110f)
            assertTrue(id, dist(b, R_WRIST, PELVIS) < 0.085f)
            assertTrue(id, b[R_ELBOW].x < b[R_SHOULDER].x)
        }
        assertEquals(pose("db_row", 0)[L_WRIST], pose("db_row", 1)[L_WRIST])
        assertEquals(guide("db_row").props.bench!!.hip.y - 0.016f, pose("db_row", 0)[L_WRIST].y, 0.001f)
    }

    @Test fun curlKeepsElbowAtSideAndDoesNotMoveTrunk() {
        val a = pose("db_curl", 0); val b = pose("db_curl", 1)
        sameBody(a, b)
        assertTrue(elbow(a) > 145f)
        assertTrue(elbow(b) < 75f)
        assertEquals(a[R_ELBOW], b[R_ELBOW])
        assertEquals(a[L_WRIST], b[L_WRIST])
    }

    @Test fun lateralRaiseIsFrontalSymmetricAndStopsAtShoulderLevel() {
        val a = pose("db_lateral_raise", 0); val b = pose("db_lateral_raise", 1)
        assertEquals(GuideView.FRONT, guide("db_lateral_raise").view)
        assertTrue(angle(a, R_HIP, R_SHOULDER, R_ELBOW) < 25f)
        assertTrue(angle(b, R_HIP, R_SHOULDER, R_ELBOW) in 70f..100f)
        assertTrue(elbow(b) > 150f)
        assertEquals(b[R_WRIST].y, b[L_WRIST].y, 0.001f)
        assertTrue(b[R_WRIST].x < b[R_SHOULDER].x - 0.2f)
        assertTrue(b[L_WRIST].x > b[L_SHOULDER].x + 0.2f)
    }

    @Test fun splitStancesKeepFrontFootFixedAndRearFootOnItsSupport() {
        for (id in listOf("db_lunge", "db_bulgarian_split_squat")) {
            val a = pose(id, 0); val b = pose(id, 1)
            assertTrue(id, knee(a) > 150f)
            assertTrue(id, knee(b) < 110f)
            assertEquals(id, a[R_ANKLE], b[R_ANKLE])
            samePoint(a[L_ANKLE], b[L_ANKLE])
            assertTrue(id, b[L_ANKLE].x < b[R_ANKLE].x - 0.3f)
            assertEquals(id, b[R_KNEE].x, b[R_ANKLE].x, 0.001f)
            val target = if (id.contains("bulgarian")) Pt(0.25f, 0.635f) else Pt(0.29f, 0.85f)
            samePoint(target, a[L_ANKLE])
            for (ms in 0L until GuideMath.cycleMs(guide(id)) step 50L) {
                samePoint(target, GuideMath.poseAt(guide(id), ms)[L_ANKLE])
            }
        }
        assertTrue(pose("db_bulgarian_split_squat", 1)[L_ANKLE].y < GuideScene.ANKLE_Y - 0.18f)
    }

    @Test fun seatedMachinesMoveArmsWithoutRockingTrunk() {
        for (id in listOf("machine_lat_pulldown", "machine_seated_row", "machine_chest_press")) {
            val a = pose(id, 0); val b = pose(id, 1)
            sameBody(a, b)
            val angles = listOf(elbow(a), elbow(b))
            assertTrue(id, angles.min() < 105f)
            assertTrue(id, angles.max() > 150f)
            assertEquals(id, a[NECK].x, a[PELVIS].x, 0.001f)
            assertNotNull(guide(id).props.cableMachine)
        }
        val pull = pose("machine_lat_pulldown", 1)
        assertTrue(abs(pull[R_WRIST].y - pull[R_SHOULDER].y) < 0.06f)
        val chest = pose("machine_chest_press", 0)
        assertTrue("chest handles above abdomen", chest[R_WRIST].y < chest[R_SHOULDER].y + 0.06f)
    }

    @Test fun legMachinesKeepHipFixedAndProvideFullKneeExcursion() {
        for (id in listOf("machine_leg_extension", "machine_leg_curl", "machine_lying_leg_curl")) {
            val a = pose(id, 0); val b = pose(id, 1)
            assertEquals(id, a[PELVIS], b[PELVIS])
            assertEquals(id, a[NECK], b[NECK])
            assertEquals(id, a[R_KNEE], b[R_KNEE])
            val angles = listOf(knee(a), knee(b))
            assertTrue(id, angles.min() <= 100f)
            assertTrue(id, angles.max() > 150f)
            assertNotNull(id, guide(id).props.legMachine)
        }
        assertEquals(pose("machine_lying_leg_curl", 0)[PELVIS].y, pose("machine_lying_leg_curl", 0)[NECK].y, 0.001f)
    }

    @Test fun assistedPullupKeepsHandsFixedAndBentKneesOnMovingPlatform() {
        val a = pose("assisted_pullup", 0); val b = pose("assisted_pullup", 1)
        assertTrue(elbow(a) > 140f)
        assertTrue(elbow(b) < 100f)
        samePoint(a[R_WRIST], b[R_WRIST])
        samePoint(a[L_WRIST], b[L_WRIST])
        assertTrue(b[HEAD].y < b[R_WRIST].y)
        assertTrue(a[R_KNEE].y > b[R_KNEE].y + 0.15f)
        assertNotNull(guide("assisted_pullup").props.assistedMachine)
    }

    @Test fun hyperextensionRotatesAtHipWithoutOverextendingOrMovingLegs() {
        val a = pose("hyperextension", 0); val b = pose("hyperextension", 1)
        assertTrue(hip(a) > 165f)
        assertTrue(hip(b) < 115f)
        for (j in listOf(PELVIS, R_HIP, R_KNEE, R_ANKLE)) assertEquals(j.name, a[j], b[j])
        assertTrue(abs(dist(a, R_SHOULDER, R_HIP) - dist(b, R_SHOULDER, R_HIP)) < 0.001f)
        assertNotNull(guide("hyperextension").props.bench)
    }
}
