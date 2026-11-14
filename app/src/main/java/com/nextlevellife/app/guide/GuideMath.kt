package com.nextlevellife.app.guide

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Время и интерполяция показа техники. Чистые функции — проверяются тестами. */
object GuideMath {
    private const val EPS = 1e-5f
    private const val TWO_PI = (2 * PI).toFloat()

    /** Кисти и стопы, которые при переходе не должны скользить, если стоят на месте в обоих кадрах. */
    private val PINNABLE = setOf(GuideJoint.R_WRIST, GuideJoint.L_WRIST, GuideJoint.R_ANKLE, GuideJoint.L_ANKLE)

    /** Плавное начало и конец перехода (ease-in-out, косинус): 0 → 0, 0,5 → 0,5, 1 → 1. */
    fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return ((1.0 - cos(PI * c)) / 2.0).toFloat()
    }

    /** Длительность одного круга показа. */
    fun cycleMs(guide: ExerciseGuide): Long = guide.frames.sumOf { (it.moveMs + it.holdMs).toLong() }

    /**
     * Поза в момент [timeMs] от начала показа. Круг: остановка в кадре 0, переход в кадр 1, остановка
     * в нём… переход из последнего кадра обратно в кадр 0 (его [GuideFrame.moveMs]). Без [ExerciseGuide.loop]
     * показ останавливается в последнем кадре.
     */
    fun poseAt(guide: ExerciseGuide, timeMs: Long): GuidePose {
        val frames = guide.frames
        if (frames.size == 1) return frames[0].pose
        val cycle = cycleMs(guide)
        if (cycle <= 0L) return frames[0].pose
        var t = if (guide.loop) {
            Math.floorMod(timeMs, cycle)
        } else {
            val oneWay = cycle - frames[0].moveMs
            if (timeMs >= oneWay) return frames.last().pose
            timeMs.coerceAtLeast(0L)
        }
        // Остановка в кадре 0.
        if (t < frames[0].holdMs) return frames[0].pose
        t -= frames[0].holdMs
        for (i in 1..frames.size) {
            val to = frames[i % frames.size]
            val from = frames[i - 1]
            if (t < to.moveMs) return lerp(from.pose, to.pose, ease(t.toFloat() / to.moveMs), guide.anchor)
            t -= to.moveMs
            if (i == frames.size) break
            if (t < to.holdMs) return to.pose
            t -= to.holdMs
        }
        return frames[0].pose
    }

    /** Дерево костей с корнем [root]: для каждого сустава — его родитель, в порядке обхода от корня. */
    internal fun tree(root: GuideJoint): List<Pair<GuideJoint, GuideJoint>> {
        val out = ArrayList<Pair<GuideJoint, GuideJoint>>()
        val seen = HashSet<GuideJoint>().apply { add(root) }
        val queue = ArrayDeque<GuideJoint>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for ((a, b) in GuideScene.BONES) {
                val child = when (p) {
                    a -> b
                    b -> a
                    else -> null
                } ?: continue
                if (seen.add(child)) {
                    out += child to p
                    queue.addLast(child)
                }
            }
        }
        return out
    }

    private val trees = HashMap<GuideJoint, List<Pair<GuideJoint, GuideJoint>>>()

    private fun cachedTree(root: GuideJoint) = synchronized(trees) { trees.getOrPut(root) { tree(root) } }

    /**
     * Поза между [a] и [b] на доле [k] (0 — [a], 1 — [b]). Корень [root] идёт по прямой, каждая кость
     * поворачивается по кратчайшей дуге и плавно меняет длину (при равных длинах — сохраняет её),
     * поэтому руки и ноги не «складываются» посреди перехода. Кисть или стопа, стоящая в [a] и [b]
     * на одном месте, остаётся на месте: средний сустав (локоть, колено) ставится по двум костям.
     */
    fun lerp(a: GuidePose, b: GuidePose, k: Float, root: GuideJoint = GuideJoint.R_ANKLE): GuidePose {
        if (k <= 0f) return a
        if (k >= 1f) return b
        val out = HashMap<GuideJoint, Pt>(GuideJoint.entries.size)
        out[root] = mix(a[root], b[root], k)
        val parents = HashMap<GuideJoint, GuideJoint>()
        for ((child, parent) in cachedTree(root)) {
            parents[child] = parent
            val pa = a[parent]
            val pb = b[parent]
            val ca = a[child]
            val cb = b[child]
            val la = hypot(ca.x - pa.x, ca.y - pa.y)
            val lb = hypot(cb.x - pb.x, cb.y - pb.y)
            val angA = if (la > EPS) atan2(ca.y - pa.y, ca.x - pa.x) else Float.NaN
            val angB = if (lb > EPS) atan2(cb.y - pb.y, cb.x - pb.x) else Float.NaN
            val ang = when {
                angA.isNaN() && angB.isNaN() -> 0f
                angA.isNaN() -> angB
                angB.isNaN() -> angA
                else -> lerpAngle(angA, angB, k)
            }
            val len = la + (lb - la) * k
            val p = out.getValue(parent)
            out[child] = Pt(p.x + cos(ang) * len, p.y + sin(ang) * len)
        }
        // Неподвижные кисти и стопы: не скользят, локоть/колено — по двум костям.
        for (j in PINNABLE) {
            if (j == root) continue
            val pa = a[j]
            if (abs(pa.x - b[j].x) > EPS || abs(pa.y - b[j].y) > EPS) continue
            val mid = parents[j] ?: continue
            val top = parents[mid] ?: continue
            val l1 = mixLen(a, b, top, mid, k)
            val l2 = mixLen(a, b, mid, j, k)
            out[mid] = twoBone(out.getValue(top), pa, l1, l2, out.getValue(mid))
            out[j] = pa
        }
        return GuidePose(out)
    }

    /**
     * Средний сустав цепочки из двух костей [l1], [l2] от [root] до [target]: из двух решений —
     * ближайшее к [hint]. Если цель недостижима, сустав ставится на прямой к ней.
     */
    fun twoBone(root: Pt, target: Pt, l1: Float, l2: Float, hint: Pt): Pt {
        val dx = target.x - root.x
        val dy = target.y - root.y
        val d0 = hypot(dx, dy)
        if (d0 < EPS) return Pt(root.x + l1, root.y)
        val d = d0.coerceIn(abs(l1 - l2) + EPS, l1 + l2 - EPS)
        val ux = dx / d0
        val uy = dy / d0
        val along = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
        val h = sqrt((l1 * l1 - along * along).coerceAtLeast(0f))
        val bx = root.x + ux * along
        val by = root.y + uy * along
        val p1 = Pt(bx - uy * h, by + ux * h)
        val p2 = Pt(bx + uy * h, by - ux * h)
        val d1 = hypot(p1.x - hint.x, p1.y - hint.y)
        val d2 = hypot(p2.x - hint.x, p2.y - hint.y)
        return if (d1 <= d2) p1 else p2
    }

    private fun mixLen(a: GuidePose, b: GuidePose, from: GuideJoint, to: GuideJoint, k: Float): Float {
        val la = hypot(a[to].x - a[from].x, a[to].y - a[from].y)
        val lb = hypot(b[to].x - b[from].x, b[to].y - b[from].y)
        return la + (lb - la) * k
    }

    private fun mix(a: Pt, b: Pt, k: Float) = Pt(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

    /** Угол между [a] и [b] (радианы) по кратчайшей дуге. */
    internal fun lerpAngle(a: Float, b: Float, k: Float): Float {
        var d = (b - a) % TWO_PI
        if (d > PI) d -= TWO_PI
        if (d < -PI) d += TWO_PI
        return a + d * k
    }
}
