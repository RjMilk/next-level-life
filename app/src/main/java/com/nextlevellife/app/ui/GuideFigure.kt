package com.nextlevellife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nextlevellife.app.guide.ExerciseGuide
import com.nextlevellife.app.guide.GuideGeometry
import com.nextlevellife.app.guide.GuideMath
import com.nextlevellife.app.guide.GuidePalette
import com.nextlevellife.app.guide.GuidePathOp
import com.nextlevellife.app.guide.GuidePose
import kotlin.math.min

/**
 * Animated technique mannequin. The same platform-independent paths are used by the JVM PNG
 * exporter. The square scene is centered; pausing preserves the elapsed animation time.
 */
@Composable
fun GuideFigure(guide: ExerciseGuide, modifier: Modifier = Modifier, playing: Boolean = true) {
    var nanos by remember(guide) { mutableLongStateOf(0L) }
    LaunchedEffect(guide, playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // Resuming a hidden window must not jump through the entire animation.
                nanos += (now - last).coerceIn(0L, MAX_STEP_NANOS)
                last = now
            }
        }
    }
    Canvas(modifier.semantics { contentDescription = "Анимация: ${guide.focus}" }) {
        drawGuide(guide, GuideMath.poseAt(guide, nanos / 1_000_000L))
    }
}

private const val MAX_STEP_NANOS = 100_000_000L

private fun DrawScope.drawGuide(guide: ExerciseGuide, pose: GuidePose) {
    val scale = min(size.width, size.height)
    val ox = (size.width - scale) / 2f
    val oy = (size.height - scale) / 2f
    for (shape in GuideGeometry.scene(guide, pose)) {
        val path = Path()
        for (op in shape.path) {
            when (op) {
                is GuidePathOp.Move -> path.moveTo(ox + op.point.x * scale, oy + op.point.y * scale)
                is GuidePathOp.Line -> path.lineTo(ox + op.point.x * scale, oy + op.point.y * scale)
                is GuidePathOp.Cubic -> path.cubicTo(
                    ox + op.control1.x * scale, oy + op.control1.y * scale,
                    ox + op.control2.x * scale, oy + op.control2.y * scale,
                    ox + op.end.x * scale, oy + op.end.y * scale,
                )
                GuidePathOp.Close -> path.close()
            }
        }
        drawPath(
            path,
            Color(GuidePalette.argb(shape.paint)).copy(alpha = shape.alpha),
            style = if (shape.strokeWidth > 0f) {
                Stroke(shape.strokeWidth * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            } else Fill,
        )
    }
}
