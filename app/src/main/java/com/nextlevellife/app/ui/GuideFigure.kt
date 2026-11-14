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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nextlevellife.app.guide.BellGrip
import com.nextlevellife.app.guide.BellHang
import com.nextlevellife.app.guide.Chair
import com.nextlevellife.app.guide.ExerciseGuide
import com.nextlevellife.app.guide.GuideHighlight
import com.nextlevellife.app.guide.GuideJoint
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
import com.nextlevellife.app.guide.GuideMath
import com.nextlevellife.app.guide.GuidePose
import com.nextlevellife.app.guide.GuideScene
import com.nextlevellife.app.guide.GuideView
import kotlin.math.hypot
import kotlin.math.min

/** Цвета фигурки на тёмном фоне. */
private val FigureBody = Color(0xFFECEEF2)
private val FloorColor = Color(0xFFA3A9B6).copy(alpha = 0.35f)
private val MatColor = Color(0xFF2C3140)
private val BarColor = Color(0xFFA3A9B6)
private val ChairColor = Color(0xFF6B7385)

/** Дальняя сторона на виде сбоку — приглушённо, чтобы читалась глубина. */
private const val FAR_ALPHA = 0.42f

/**
 * Анимированная фигурка показа техники на Canvas. Сцена гида (квадрат 0…1) вписывается по меньшей
 * стороне и стоит по центру; линия пола тянется на всю ширину. [playing] = false — пауза на текущем
 * кадре, снова true — продолжение с того же места. Время идёт от кадров отрисовки (withFrameNanos),
 * а поза читается только при рисовании: показ не перекомпонует экран на каждом кадре.
 */
@Composable
fun GuideFigure(guide: ExerciseGuide, modifier: Modifier = Modifier, playing: Boolean = true) {
    var nanos by remember(guide) { mutableLongStateOf(0L) }
    LaunchedEffect(guide, playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // Длинный провал (окно было скрыто) не перематывает показ рывком.
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
    val s = min(size.width, size.height)
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    fun at(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    fun at(j: GuideJoint) = at(pose[j].x, pose[j].y)

    val side = guide.view == GuideView.SIDE
    val props = guide.props
    val floorY = oy + GuideScene.FLOOR * s

    // Сцена: перекладина или пол, коврик.
    if (props.bar) {
        val bar = guide.frames[0].pose[R_WRIST].y - 0.004f
        val y = oy + bar * s
        drawLine(BarColor, Offset(ox + 0.18f * s, y), Offset(ox + 0.82f * s, y), 0.014f * s, StrokeCap.Round)
        drawLine(BarColor.copy(alpha = 0.5f), Offset(ox + 0.18f * s, y), Offset(ox + 0.18f * s, 0f), 0.01f * s)
        drawLine(BarColor.copy(alpha = 0.5f), Offset(ox + 0.82f * s, y), Offset(ox + 0.82f * s, 0f), 0.01f * s)
    } else {
        drawLine(FloorColor, Offset(0f, floorY), Offset(size.width, floorY), 0.006f * s, StrokeCap.Round)
    }
    props.chair?.let { ch ->
        val w = 0.014f * s
        val seat = oy + ch.seatY * s
        val x0 = ox + ch.x0 * s
        val x1 = ox + ch.x1 * s
        val back = if (ch.backLeft) x0 else x1
        drawLine(ChairColor, Offset(x0, seat), Offset(x1, seat), w, StrokeCap.Round)
        drawLine(ChairColor, Offset(x0 + w, seat), Offset(x0 + w, floorY), w * 0.8f, StrokeCap.Round)
        drawLine(ChairColor, Offset(x1 - w, seat), Offset(x1 - w, floorY), w * 0.8f, StrokeCap.Round)
        drawLine(ChairColor, Offset(back, seat), Offset(back, seat - Chair.BACK * s), w, StrokeCap.Round)
    }
    if (props.mat) {
        val h = 0.014f * s
        drawRoundRect(
            MatColor,
            topLeft = Offset(ox + 0.07f * s, floorY - h),
            size = Size(0.86f * s, h),
            cornerRadius = CornerRadius(h / 2f),
        )
    }

    val limb = 0.026f * s
    val trunk = 0.034f * s
    val hl = guide.highlight
    val arms = GuideHighlight.ARMS in hl
    val legs = GuideHighlight.LEGS in hl
    val torso = GuideHighlight.TORSO in hl
    fun color(accent: Boolean, far: Boolean): Color {
        val c = if (accent) Accent else FigureBody
        return if (far) c.copy(alpha = FAR_ALPHA) else c
    }

    fun bone(a: GuideJoint, b: GuideJoint, c: Color, w: Float = limb) = drawLine(c, at(a), at(b), w, StrokeCap.Round)

    fun foot(knee: GuideJoint, ankle: GuideJoint, right: Boolean, c: Color) {
        val k = pose[knee]
        val a = pose[ankle]
        var dx: Float
        var dy: Float
        if (side) {
            // Стопа перпендикулярна голени, носком вперёд по взгляду фигурки.
            val len = hypot(a.x - k.x, a.y - k.y).coerceAtLeast(1e-4f)
            dx = (a.y - k.y) / len * GuideScene.FOOT
            dy = -(a.x - k.x) / len * GuideScene.FOOT
        } else {
            dx = (if (right) -1f else 1f) * GuideScene.FOOT * 0.6f
            dy = 0.004f
        }
        // Носок не уходит под пол.
        val maxY = GuideScene.FLOOR - 0.004f
        if (!props.bar && a.y < maxY && a.y + dy > maxY && dy > 0f) {
            val k2 = (maxY - a.y) / dy
            dx *= k2
            dy *= k2
        }
        drawLine(c, at(a.x, a.y), at(a.x + dx, a.y + dy), limb * 0.85f, StrokeCap.Round)
    }

    fun leg(right: Boolean, far: Boolean) {
        val c = color(legs, far)
        val hip = if (right) R_HIP else L_HIP
        val knee = if (right) R_KNEE else L_KNEE
        val ankle = if (right) R_ANKLE else L_ANKLE
        bone(hip, knee, c)
        bone(knee, ankle, c)
        foot(knee, ankle, right, c)
    }

    fun arm(right: Boolean, far: Boolean) {
        val accent = arms || (right && GuideHighlight.WORKING_ARM in hl)
        val c = color(accent, far)
        bone(if (right) R_SHOULDER else L_SHOULDER, if (right) R_ELBOW else L_ELBOW, c)
        bone(if (right) R_ELBOW else L_ELBOW, if (right) R_WRIST else L_WRIST, c)
    }

    fun trunk() {
        val c = color(torso, false)
        bone(PELVIS, NECK, c, trunk)
        if (!side) {
            bone(R_SHOULDER, L_SHOULDER, c, limb)
            bone(R_HIP, L_HIP, c, limb)
        }
        bone(NECK, HEAD, FigureBody, limb)
        drawCircle(FigureBody, GuideScene.HEAD_RADIUS * s, at(HEAD))
    }

    if (side) {
        leg(right = false, far = true)
        arm(right = false, far = true)
        trunk()
        leg(right = true, far = false)
        drawBell(guide, pose, s, ::at)
        arm(right = true, far = false)
    } else {
        leg(right = true, far = false)
        leg(right = false, far = false)
        trunk()
        drawBell(guide, pose, s, ::at)
        arm(right = true, far = false)
        arm(right = false, far = false)
    }
}

/** Гиря: корпус-круг и дужка, акцентным цветом. */
private fun DrawScope.drawBell(guide: ExerciseGuide, pose: GuidePose, s: Float, at: (Float, Float) -> Offset) {
    val bell = guide.props.bell ?: return
    val r = GuideScene.BELL_RADIUS
    // Направление «от ручки к корпусу».
    var downX = 0f
    var downY = 1f
    val cx: Float
    val cy: Float
    when (bell.grip) {
        BellGrip.PELVIS -> {
            val p = pose[PELVIS]
            cx = p.x
            cy = p.y - r - 0.022f
        }
        BellGrip.HANDS, BellGrip.RIGHT_HAND -> {
            val g = if (bell.grip == BellGrip.HANDS) {
                val a = pose[R_WRIST]
                val b = pose[L_WRIST]
                Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            } else {
                Pair(pose[R_WRIST].x, pose[R_WRIST].y)
            }
            if (bell.hang == BellHang.ALONG_FOREARM) {
                val e = pose[R_ELBOW]
                val w = pose[R_WRIST]
                val len = hypot(w.x - e.x, w.y - e.y)
                if (len > 1e-4f) {
                    downX = (w.x - e.x) / len
                    downY = (w.y - e.y) / len
                }
            }
            cx = g.first + downX * r * 1.45f
            cy = g.second + downY * r * 1.45f
        }
    }
    val perpX = -downY
    val perpY = downX
    val ax = cx - perpX * r * 0.6f - downX * r * 0.7f
    val ay = cy - perpY * r * 0.6f - downY * r * 0.7f
    val bx = cx + perpX * r * 0.6f - downX * r * 0.7f
    val by = cy + perpY * r * 0.6f - downY * r * 0.7f
    val handle = Path().apply {
        val a = at(ax, ay)
        val b = at(bx, by)
        val c1 = at(ax - downX * r, ay - downY * r)
        val c2 = at(bx - downX * r, by - downY * r)
        moveTo(a.x, a.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
    }
    drawPath(handle, Accent, style = Stroke(width = 0.012f * s, cap = StrokeCap.Round))
    drawCircle(Accent, r * s, at(cx, cy))
    // Плоское дно гири — тёмная полоска, чтобы читался предмет, а не шар.
    val baseX = cx + downX * r * 0.82f
    val baseY = cy + downY * r * 0.82f
    drawLine(
        OnAccent.copy(alpha = 0.35f),
        at(baseX - perpX * r * 0.5f, baseY - perpY * r * 0.5f),
        at(baseX + perpX * r * 0.5f, baseY + perpY * r * 0.5f),
        0.006f * s,
        StrokeCap.Round,
    )
}
