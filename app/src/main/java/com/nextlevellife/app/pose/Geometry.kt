package com.nextlevellife.app.pose

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Плоская геометрия по точкам кадра (пиксели, y вниз). Только углы и отношения длин —
 * абсолютные пиксели нигде дальше не используются.
 */
internal object Geometry {
    private const val RAD_TO_DEG = (180.0 / PI).toFloat()
    private const val EPS = 1e-4f

    fun dist(a: PosePoint, b: PosePoint): Float = hypot(a.x - b.x, a.y - b.y)

    /** Угол в суставе [b] между отрезками b→a и b→c, градусы 0..180. NaN, если отрезок вырожден. */
    fun jointAngle(a: PosePoint, b: PosePoint, c: PosePoint): Float {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val norm = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (norm < EPS) return Float.NaN
        val cos = ((v1x * v2x + v1y * v2y) / norm).coerceIn(-1f, 1f)
        return acos(cos) * RAD_TO_DEG
    }

    /**
     * Наклон отрезка [from]→[to] от вертикали «вверх», градусы 0..180:
     * 0 — [to] строго над [from], 90 — горизонтально, 180 — строго под.
     */
    fun tiltFromVertical(from: PosePoint, to: PosePoint): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = hypot(dx, dy)
        if (len < EPS) return Float.NaN
        return acos((-dy / len).coerceIn(-1f, 1f)) * RAD_TO_DEG
    }

    /** Подъём отрезка над горизонталью, градусы −90..90: 0 — горизонтально, 90 — вверх. */
    fun elevation(from: PosePoint, to: PosePoint): Float = 90f - tiltFromVertical(from, to)

    /** Насколько [a] выше [b] на экране, в долях [scale] (положительное — [a] выше). */
    fun heightAbove(a: PosePoint, b: PosePoint, scale: Float): Float =
        if (scale > EPS) (b.y - a.y) / scale else Float.NaN

    /** Доля пути значения [x] от [from] к [to], 0..1; NaN → 0. Работает в обе стороны. */
    fun progress(x: Float, from: Float, to: Float): Float {
        if (x.isNaN() || from == to) return 0f
        return ((x - from) / (to - from)).coerceIn(0f, 1f)
    }
}
