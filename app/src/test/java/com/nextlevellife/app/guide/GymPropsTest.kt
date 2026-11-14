package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class GymPropsTest {
    private val standing = GuidePoses.side(torso = 0f)

    private fun guide(props: GuideProps, pose: GuidePose = standing, view: GuideView = GuideView.SIDE) = ExerciseGuide(
        exerciseId = "gym_props_test",
        view = view,
        frames = listOf(GuideFrame(pose, 800, 200), GuideFrame(pose, 800, 200)),
        props = props,
        distance = "2–3 м",
        angle = "сбоку",
        camera = "Всё тело в кадре",
        focus = "Проверка реквизита",
        tips = emptyList(),
        mistakes = emptyList(),
    )

    private fun shaft(scene: List<GuideShape>, width: Float) = scene.first {
        it.paint == GuidePaint.METAL && it.strokeWidth == width &&
            it.path.size == 2 && it.path[0] is GuidePathOp.Move && it.path[1] is GuidePathOp.Line
    }

    private fun center(shape: GuideShape): Pt {
        val start = (shape.path[0] as GuidePathOp.Move).point
        val end = (shape.path[1] as GuidePathOp.Line).point
        return Pt((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    }

    private fun assertPoint(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 0.00001f)
        assertEquals(expected.y, actual.y, 0.00001f)
    }

    @Test
    fun barbellFollowsHandsAndShouldersThroughoutMotion() {
        val poses = listOf(standing, GuidePoses.side(torso = 45f, thigh = 125f, shin = 220f,
            upperArm = 120f, forearm = 160f))
        for (pose in poses) for (grip in BarbellGrip.entries) {
            val offset = Pt(-0.015f, -0.025f)
            val props = GuideProps(barbell = Barbell(grip, offset))
            val scene = GuideGeometry.scene(guide(props, pose), pose)
            val right = pose[if (grip == BarbellGrip.HANDS) R_WRIST else R_SHOULDER]
            val left = pose[if (grip == BarbellGrip.HANDS) L_WRIST else L_SHOULDER]
            assertPoint(Pt((right.x + left.x) / 2f + offset.x, (right.y + left.y) / 2f + offset.y),
                center(shaft(scene, 0.009f)))
            val body = scene.indexOfFirst { it.paint == GuidePaint.BODY_SHADE }
            val bar = scene.indexOf(shaft(scene, 0.009f))
            if (grip == BarbellGrip.SHOULDERS) assertTrue("rack bar behind body", bar < body)
            else assertTrue("hand-held bar in front of body", bar > body)
        }
    }

    @Test
    fun dumbbellsStayAtTheirSelectedHandsAndRotateWithForearms() {
        val pose = GuidePoses.front(upperArm = 40f, forearm = 120f, leftUpperArm = 60f, leftForearm = 15f)
        for ((grip, joints) in listOf(
            DumbbellGrip.EACH_HAND to listOf(L_WRIST, R_WRIST),
            DumbbellGrip.RIGHT_HAND to listOf(R_WRIST),
            DumbbellGrip.LEFT_HAND to listOf(L_WRIST),
            DumbbellGrip.BOTH_HANDS to listOf(R_WRIST),
        )) {
            val props = GuideProps(dumbbells = Dumbbells(grip))
            val scene = GuideGeometry.scene(guide(props, pose, GuideView.FRONT), pose)
            val handles = scene.filter { it.paint == GuidePaint.METAL && it.strokeWidth == 0.009f }
            assertEquals("$grip handle count", joints.size, handles.size)
            for ((handle, wristJoint) in handles.zip(joints)) {
                val wrist = pose[wristJoint]
                val elbow = pose[if (wristJoint == R_WRIST) R_ELBOW else L_ELBOW]
                val expected = if (grip == DumbbellGrip.BOTH_HANDS) {
                    Pt((pose[R_WRIST].x + pose[L_WRIST].x) / 2f, (pose[R_WRIST].y + pose[L_WRIST].y) / 2f)
                } else wrist
                assertPoint(expected, center(handle))
                val start = (handle.path[0] as GuidePathOp.Move).point
                val end = (handle.path[1] as GuidePathOp.Line).point
                val dot = (end.x - start.x) * (wrist.x - elbow.x) + (end.y - start.y) * (wrist.y - elbow.y)
                assertEquals("handle perpendicular to forearm", 0f, dot, 0.00001f)
            }
        }
    }

    @Test
    fun cableEndsAtSelectedGripAndEquipmentIsBehindMannequin() {
        val pose = GuidePoses.front(upperArm = 100f, forearm = 150f, leftUpperArm = 70f, leftForearm = 100f)
        for (grip in CableGrip.entries) {
            val machine = CableMachine(Pt(0.85f, 0.89f), Pt(0.85f, 0.12f), Pt(0.7f, 0.16f),
                Chair(0.4f, 0.6f, 0.65f), grip, listOf(Pt(0.8f, 0.16f)))
            val scene = GuideGeometry.scene(guide(GuideProps(cableMachine = machine), pose), pose)
            val cable = scene.single { it.paint == GuidePaint.METAL && it.strokeWidth == 0.0035f }
            val end = (cable.path.last() as GuidePathOp.Line).point
            val expected = when (grip) {
                CableGrip.HANDS -> Pt((pose[R_WRIST].x + pose[L_WRIST].x) / 2f, (pose[R_WRIST].y + pose[L_WRIST].y) / 2f)
                CableGrip.RIGHT_HAND -> pose[R_WRIST]
                CableGrip.LEFT_HAND -> pose[L_WRIST]
            }
            assertPoint(expected, end)
            assertTrue("cable before body", scene.indexOf(cable) < scene.indexOfFirst { it.paint == GuidePaint.BODY_SHADE })
            assertEquals(4, cable.path.size)
        }
    }

    @Test
    fun gobletGripSupportsTheUpperPlateWithBothHands() {
        val pose = GuidePoses.side(torso = 0f, upperArm = 155f, forearm = 15f)
        val scene = GuideGeometry.scene(guide(GuideProps(dumbbells = Dumbbells(DumbbellGrip.GOBLET)), pose), pose)
        val handle = shaft(scene, 0.009f)
        val center = center(handle)
        assertPoint(Pt((pose[R_WRIST].x + pose[L_WRIST].x) / 2f,
            (pose[R_WRIST].y + pose[L_WRIST].y) / 2f + 0.032f), center)
        val start = (handle.path[0] as GuidePathOp.Move).point
        val end = (handle.path[1] as GuidePathOp.Line).point
        assertEquals(start.x, end.x, 0.00001f)
    }

    private fun previews(): List<Pair<String, ExerciseGuide>> {
        val flatPinY = GuideScene.ANKLE_Y - GuideScene.THIGH * 0.5f - GuideScene.SHIN
        val seatedPinY = GuideScene.ANKLE_Y - GuideScene.SHIN
        val flatDelta = flatPinY - 0.62f
        val inclineDelta = seatedPinY - 0.64f
        val seatedDelta = seatedPinY - 0.61f
        val flat = GuidePoses.side(torso = 270f, thigh = 120f, shin = 180f, upperArm = 0f, forearm = 0f,
            pin = Pin(PELVIS, 0.61f, flatPinY))
        val incline = GuidePoses.side(torso = 315f, thigh = 90f, shin = 180f, upperArm = 0f, forearm = 0f,
            pin = Pin(PELVIS, 0.56f, seatedPinY))
        val seated = GuidePoses.side(torso = 0f, thigh = 90f, shin = 180f, upperArm = 20f, forearm = 30f,
            pin = Pin(PELVIS, 0.5f, seatedPinY))
        val row = GuidePoses.side(torso = 0f, thigh = 90f, shin = 180f, upperArm = 80f, forearm = 100f,
            pin = Pin(PELVIS, 0.5f, seatedPinY))
        val front = GuidePoses.front(upperArm = 60f, forearm = 60f)
        val goblet = GuidePoses.side(torso = 0f, upperArm = 155f, forearm = 15f)
        return listOf(
            "Barbell hands" to guide(GuideProps(barbell = Barbell(halfLength = 0.15f))),
            "Barbell shoulders" to guide(GuideProps(barbell = Barbell(BarbellGrip.SHOULDERS, Pt(-0.012f, -0.02f), 0.15f))),
            "Dumbbells pair" to guide(GuideProps(dumbbells = Dumbbells()), front, GuideView.FRONT),
            "Single dumbbell" to guide(GuideProps(dumbbells = Dumbbells(DumbbellGrip.RIGHT_HAND))),
            "Goblet grip" to guide(GuideProps(dumbbells = Dumbbells(DumbbellGrip.GOBLET)), goblet),
            "Flat bench" to guide(GuideProps(bench = Bench(Pt(0.65f, 0.66f + flatDelta), Pt(0.31f, 0.66f + flatDelta)),
                dumbbells = Dumbbells()), flat),
            "Incline bench" to guide(GuideProps(bench = Bench(Pt(0.56f, 0.67f + inclineDelta),
                Pt(0.36f, 0.47f + inclineDelta), Pt(0.68f, 0.67f + inclineDelta)),
                dumbbells = Dumbbells()), incline),
            "High pulley" to guide(GuideProps(cableMachine = CableMachine(Pt(0.88f, 0.89f), Pt(0.88f, 0.12f),
                Pt(0.68f, 0.15f), Chair(0.46f, 0.62f, 0.65f + seatedDelta))), seated),
            "Low pulley" to guide(GuideProps(cableMachine = CableMachine(Pt(0.88f, 0.89f), Pt(0.88f, 0.12f),
                Pt(0.88f, 0.55f), Chair(0.46f, 0.62f, 0.65f + seatedDelta))), row),
        )
    }

    @Test
    fun equipmentScenesProduceFinitePathsWithinScene() {
        for ((label, guide) in previews()) {
            val pose = guide.frames.first().pose
            if (guide.props.bench != null || guide.props.cableMachine != null) {
                for (ankle in listOf(R_ANKLE, L_ANKLE)) {
                    assertEquals("$label $ankle must rest on floor", GuideScene.ANKLE_Y, pose[ankle].y, 0.00001f)
                }
            }
            val scene = GuideGeometry.scene(guide, pose)
            for (shape in scene) {
                assertTrue("$label invalid stroke", shape.strokeWidth.isFinite() && shape.strokeWidth >= 0f)
                assertTrue("$label invalid alpha", shape.alpha.isFinite() && shape.alpha in 0f..1f)
                val points = shape.path.flatMap { op -> when (op) {
                    is GuidePathOp.Move -> listOf(op.point)
                    is GuidePathOp.Line -> listOf(op.point)
                    is GuidePathOp.Cubic -> listOf(op.control1, op.control2, op.end)
                    GuidePathOp.Close -> emptyList()
                } }
                for (point in points) assertTrue("$label invalid/clipped point $point",
                    point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f)
            }
            val firstBody = scene.indexOfFirst { it.paint == GuidePaint.BODY || it.paint == GuidePaint.BODY_SHADE }
            if (guide.props.bench != null) {
                assertTrue("bench drawn behind mannequin", scene.indexOfLast { it.paint == GuidePaint.CHAIR } < firstBody)
            }
        }
    }

    @Test
    fun exportEquipmentContactSheet() {
        val examples = previews()
        val size = 320
        val labelHeight = 28
        val image = BufferedImage(size * 3, (size + labelHeight) * 3, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            for ((index, example) in examples.withIndex()) {
                val (title, guide) = example
                val x = index % 3 * size
                val y = index / 3 * (size + labelHeight)
                graphics.color = GuideAwtRenderer.background
                graphics.fillRect(x, y, size, size + labelHeight)
                graphics.drawImage(GuideAwtRenderer.render(guide, guide.frames.first().pose, size), x, y + labelHeight, null)
                graphics.color = Color.WHITE
                graphics.drawString(title, x + 12, y + 20)
            }
        } finally {
            graphics.dispose()
        }
        val directory = File(System.getProperty("guide.frames.dir", "app/build/guide-frames"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        assertTrue(ImageIO.write(image, "png", File(directory, "gym-props.png")))
    }
}
