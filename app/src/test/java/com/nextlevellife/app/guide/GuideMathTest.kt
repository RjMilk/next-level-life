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
    fun everyGuideKeepsSegmentLengthsThroughoutEveryTransition() {
        val failures = mutableListOf<String>()
        val guides = listOf(BodyweightGuides, KettlebellGuides, RoutineGuides, GymGuides).flatMap { it.guides }
        for (g in guides) for (i in g.frames.indices) {
            val a = g.frames[i].pose
            val b = g.frames[(i + 1) % g.frames.size].pose
            for (step in 0..20) {
                val p = GuideMath.lerp(a, b, step / 20f, g.anchor)
                for ((from, to) in GuideScene.BONES) {
                    val expected = len(g.frames.first().pose, from, to)
                    if (kotlin.math.abs(expected - len(p, from, to)) > 1e-4f) {
                        failures += "${g.exerciseId}, переход $i, $step/20: $from–$to $expected → ${len(p, from, to)}"
                    }
                }
            }
        }
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun conflictingSecondaryPinNeverStretchesTheArm() {
        // Плечо проходит по правой полуокружности: в середине до кисти дальше,
        // чем сумма плеча и предплечья, хотя обе ключевые позы достижимы.
        val root = Pt(0f, 0f)
        val wrist = Pt(-0.1f, 0f)
        fun pose(y: Float): GuidePose {
            val shoulder = Pt(0f, y)
            val p = stand.points.toMutableMap()
            p[GuideJoint.NECK] = root
            p[GuideJoint.R_SHOULDER] = shoulder
            p[GuideJoint.R_ELBOW] = GuideMath.twoBone(shoulder, wrist, GuideScene.UPPER_ARM, GuideScene.FOREARM, Pt(-0.15f, y))
            p[GuideJoint.R_WRIST] = wrist
            return GuidePose(p)
        }
        val a = pose(-0.2f)
        val b = pose(0.2f)
        val mid = GuideMath.lerp(a, b, 0.5f, GuideJoint.NECK)
        assertEquals(root, mid[GuideJoint.NECK])
        assertEquals(GuideScene.UPPER_ARM, len(mid, GuideJoint.R_SHOULDER, GuideJoint.R_ELBOW), 1e-5f)
        assertEquals(GuideScene.FOREARM, len(mid, GuideJoint.R_ELBOW, GuideJoint.R_WRIST), 1e-5f)
        assertTrue("недостижимая вторичная опора должна уступить длине кости", kotlin.math.hypot(mid[GuideJoint.R_WRIST].x - wrist.x, mid[GuideJoint.R_WRIST].y - wrist.y) > 0.01f)
    }

    @Test
    fun allGuideTransitionsAreContinuousAtBothKeyframesAndKeepReachableContacts() {
        val contacts = listOf(GuideJoint.R_ANKLE, GuideJoint.L_ANKLE, GuideJoint.R_WRIST, GuideJoint.L_WRIST)
        val failures = mutableListOf<String>()
        for (g in listOf(BodyweightGuides, KettlebellGuides, RoutineGuides, GymGuides).flatMap { it.guides }) {
            for (i in g.frames.indices) {
                val a = g.frames[i].pose
                val b = g.frames[(i + 1) % g.frames.size].pose
                // Почти прямой локоть имеет sqrt-зависимость IK от расстояния до опоры.
                // Проверяем предел у ключевого кадра, а не линейную скорость его координат.
                for ((k, end) in listOf(0.00001f to a, 0.99999f to b)) {
                    val p = GuideMath.lerp(a, b, k, g.anchor)
                    for (j in GuideJoint.entries) {
                        if (kotlin.math.hypot(p[j].x - end[j].x, p[j].y - end[j].y) > 0.001f) failures += "${g.exerciseId}: скачок $j при $k"
                    }
                }
                for (step in 1..19) {
                    val p = GuideMath.lerp(a, b, step / 20f, g.anchor)
                    for (j in contacts) {
                        if (kotlin.math.hypot(a[j].x - b[j].x, a[j].y - b[j].y) > 1e-4f) continue
                        // These built-in transitions keep their stationary supports reachable.
                        val target = Pt(a[j].x + (b[j].x - a[j].x) * step / 20f, a[j].y + (b[j].y - a[j].y) * step / 20f)
                        if (kotlin.math.hypot(p[j].x - target.x, p[j].y - target.y) > 1e-4f) failures += "${g.exerciseId}: опора $j скользит при $step/20"
                    }
                }
            }
        }
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun hangingBodyMovesVerticallyBetweenTwoStationaryHands() {
        val guide = Guides.of("pullups")!!
        val a = guide.frames.first().pose
        val b = guide.frames.last().pose
        for (step in 0..40) {
            val k = step / 40f
            val p = GuideMath.lerp(a, b, k, guide.anchor)
            assertEquals(0.5f, p[GuideJoint.NECK].x, 1e-5f)
            assertEquals(a[GuideJoint.NECK].y + (b[GuideJoint.NECK].y - a[GuideJoint.NECK].y) * k, p[GuideJoint.NECK].y, 1e-5f)
            for (wrist in listOf(GuideJoint.R_WRIST, GuideJoint.L_WRIST)) {
                assertEquals(a[wrist].x, p[wrist].x, 1e-4f)
                assertEquals(a[wrist].y, p[wrist].y, 1e-4f)
            }
        }
    }

    @Test
    fun foldedTwoBoneUsesBendHintAtCoincidentEndpoints() {
        val elbow = GuideMath.twoBone(Pt(0f, 0f), Pt(0f, 0f), 0.1f, 0.1f, Pt(0f, -1f))
        assertEquals(0f, elbow.x, 1e-6f)
        assertEquals(-0.1f, elbow.y, 1e-6f)
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
