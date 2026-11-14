package com.nextlevellife.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.geom.Path2D
import java.awt.geom.PathIterator

/** Geometry regressions run on the JVM against the exact paths consumed by Compose. */
class GuideGeometryTest {
    private val guides = listOf(BodyweightGuides, KettlebellGuides, RoutineGuides, GymGuides).flatMap { it.guides }

    private fun samples(guide: ExerciseGuide): Sequence<Pair<String, GuidePose>> = sequence {
        guide.frames.forEachIndexed { index, frame -> yield("key $index" to frame.pose) }
        val transitions = if (guide.loop) guide.frames.size else guide.frames.size - 1
        for (index in 0 until transitions) {
            val from = guide.frames[index].pose
            val to = guide.frames[(index + 1) % guide.frames.size].pose
            for (step in 1 until 20) {
                yield("transition $index at $step/20" to GuideMath.lerp(from, to, step / 20f, guide.anchor))
            }
        }
    }

    @Test
    fun everyKeyAndTransitionProducesFiniteDrawablePaths() {
        assertEquals("all built-in exercises, LFK and yoga poses must be checked", 66, guides.size)
        for (guide in guides) for ((sample, pose) in samples(guide)) {
            val scene = GuideGeometry.scene(guide, pose)
            val label = "${guide.exerciseId}, $sample"
            assertTrue("$label: empty mannequin", scene.isNotEmpty())
            for ((shapeIndex, shape) in scene.withIndex()) {
                val at = "$label, shape $shapeIndex"
                assertTrue("$at: alpha ${shape.alpha}", shape.alpha.isFinite() && shape.alpha in 0f..1f)
                assertTrue("$at: stroke ${shape.strokeWidth}", shape.strokeWidth.isFinite() && shape.strokeWidth >= 0f)
                assertTrue("$at: missing Move", shape.path.firstOrNull() is GuidePathOp.Move)
                for (op in shape.path) {
                    val points = when (op) {
                        is GuidePathOp.Move -> listOf(op.point)
                        is GuidePathOp.Line -> listOf(op.point)
                        is GuidePathOp.Cubic -> listOf(op.control1, op.control2, op.end)
                        GuidePathOp.Close -> emptyList()
                    }
                    for (point in points) assertTrue("$at: non-finite $point", point.x.isFinite() && point.y.isFinite())
                }
            }
        }
    }

    @Test
    fun mannequinIncludingHeadHandsAndShoesFitsTheScene() {
        // Check the actual Bezier outline, not control-point bounds: control points may extend
        // beyond a curve. Props such as the hanging bar and floor shadows are intentionally exempt.
        val bodyPaints = setOf(GuidePaint.BODY, GuidePaint.BODY_SHADE)
        val tolerance = 0.003
        val xy = DoubleArray(6)
        for (guide in guides) for ((sample, pose) in samples(guide)) {
            for (shape in GuideGeometry.scene(guide, pose).filter { it.paint in bodyPaints }) {
                val path = Path2D.Float()
                for (op in shape.path) when (op) {
                    is GuidePathOp.Move -> path.moveTo(op.point.x, op.point.y)
                    is GuidePathOp.Line -> path.lineTo(op.point.x, op.point.y)
                    is GuidePathOp.Cubic -> path.curveTo(op.control1.x, op.control1.y, op.control2.x, op.control2.y, op.end.x, op.end.y)
                    GuidePathOp.Close -> path.closePath()
                }
                val outline = if (shape.strokeWidth > 0f) {
                    BasicStroke(shape.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)
                } else path
                val iterator = outline.getPathIterator(null, 0.0002)
                while (!iterator.isDone) {
                    val type = iterator.currentSegment(xy)
                    if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                        assertTrue(
                            "${guide.exerciseId}, $sample: mannequin clipped at (${xy[0]}, ${xy[1]})",
                            xy[0] >= -tolerance && xy[0] <= 1.0 + tolerance &&
                                xy[1] >= -tolerance && xy[1] <= 1.0 + tolerance,
                        )
                    }
                    iterator.next()
                }
            }
        }
    }
}

