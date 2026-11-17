package com.nextlevellife.app.pose

import kotlin.math.min

/** Пороги «руки ушли за верхний край кадра» ([OverheadEdgeCounter]). */
internal object OverheadEdgeTuning {
    /** Верхняя полоса кадра, доля высоты: кисть в последний раз видна в ней — ушла за верхний край. */
    const val TOP_BAND = 0.12f

    /**
     * Кисть в последний раз была выше плеча хотя бы на столько торса — руки шли над голову.
     * Нос (он должен быть виден) выше плеча на ≈ 0,25–0,3, так что кисть уходит за край не ниже этого.
     */
    const val WRIST_OVER_SHOULDER_MIN = 0.2f

    /**
     * Локоть при этом не ниже плеча больше чем на столько торса: на выжиме кисть уходит за край, когда локоть
     * ещё около плеча (на синтетике до 0,27 ниже: кадр 30 к/с ловит кисть чуть ниже края);
     * гиря у груди — локоть ниже плеча на длину плеча (≈ 0,5–0,6).
     */
    const val ELBOW_BELOW_SHOULDER_MAX = 0.35f

    /** Кисть за краем дольше — больше не достраивается (человек не держит руки над головой так долго). */
    const val MAX_OUT_MS = 2500L
}

/**
 * Упражнения с руками над головой (трастер), телефон близко или низко: в верхней точке кисти и локти уходят
 * за верхний край кадра. ML Kit тогда отдаёт их с низкой уверенностью или далеко за кадром, счётчик «теряет»
 * человека («Встань в кадр целиком») и не видит верх повторения.
 *
 * Обёртка достраивает ушедшую кисть (и локоть), только если всё сразу:
 * - в этом кадре видны плечо и таз той же стороны и голова (нос) — человек на месте;
 * - в последний раз кисть была видна в верхней полосе кадра ([OverheadEdgeTuning.TOP_BAND]) и выше плеча
 *   на [OverheadEdgeTuning.WRIST_OVER_SHOULDER_MIN] торса, а локоть — около плеча или выше
 *   ([OverheadEdgeTuning.ELBOW_BELOW_SHOULDER_MAX]) или тоже ушёл за верхний край
 *   (гиря у груди так не выглядит: кисти там у плеч, локти ниже плеч на длину плеча);
 * - с тех пор прошло не больше [OverheadEdgeTuning.MAX_OUT_MS].
 * Достроенная кисть стоит над последним видимым положением, не ниже вытянутой вверх руки
 * (плечо − длина руки), — то есть «руки над головой». Пустой кадр (человека нет) ничего не достраивает
 * и забывает уход рук.
 *
 * Чтобы достроенные точки над кадром не отбрасывались проверкой края ([JointTuning.usable]), кадр для счётчика
 * сдвигается вниз на свою высоту (признаки — только углы и отношения длин, сдвиг их не меняет), а точки,
 * негодные в исходном кадре, отбрасываются заранее — по исходным правилам.
 */
internal class OverheadEdgeCounter(private val inner: RepCounter) : RepCounter by inner {
    private class Seen(var point: PosePoint? = null, var atMs: Long = 0L)

    private class ArmState {
        val wrist = Seen()
        val elbow = Seen()
        /** Последний видимый кадр кисти подходит под «уходит над головой за верхний край». */
        var wristLeaving = false

        fun clear() {
            wrist.point = null
            elbow.point = null
            wristLeaving = false
        }
    }

    private val arms = Side.entries.associateWith { ArmState() }

    override fun onFrame(frame: PoseFrame): RepState = inner.onFrame(prepare(frame))

    override fun reset() {
        arms.values.forEach { it.clear() }
        inner.reset()
    }

    private fun prepare(frame: PoseFrame): PoseFrame {
        if (frame.points.isEmpty()) {
            arms.values.forEach { it.clear() }
            return frame
        }
        val t = frame.timeMs
        val usable = frame.points.filterValues { JointTuning.usable(it, frame) }
        fun fresh(j: Joint): PosePoint? = usable[j]?.takeIf { it.confidence >= JointTuning.MIN_CONFIDENCE }

        val out = HashMap(usable)
        val band = frame.height * OverheadEdgeTuning.TOP_BAND
        val nose = fresh(Joint.NOSE)
        for ((side, arm) in arms) {
            val shoulder = fresh(side.joint(Limb.SHOULDER))
            val hip = fresh(side.joint(Limb.HIP))
            val torso = if (shoulder != null && hip != null) Geometry.dist(shoulder, hip) else 0f
            val body = torso > 0f && nose != null
            val wrist = fresh(side.joint(Limb.WRIST))
            val elbow = fresh(side.joint(Limb.ELBOW))

            if (elbow != null) {
                arm.elbow.point = elbow
                arm.elbow.atMs = t
            }
            fun recent(s: Seen) = s.point != null && t - s.atMs <= OverheadEdgeTuning.MAX_OUT_MS
            val elbowOut = elbow == null && recent(arm.elbow) && arm.elbow.point!!.y <= band

            if (wrist != null) {
                arm.wrist.point = wrist
                arm.wrist.atMs = t
                arm.wristLeaving = body && shoulder != null && wrist.y <= band &&
                    (shoulder.y - wrist.y) / torso >= OverheadEdgeTuning.WRIST_OVER_SHOULDER_MIN &&
                    (elbow?.let { (it.y - shoulder.y) / torso <= OverheadEdgeTuning.ELBOW_BELOW_SHOULDER_MAX } ?: elbowOut)
                continue
            }
            if (!body || shoulder == null || !arm.wristLeaving || !recent(arm.wrist)) continue

            // Кисть ушла за верхний край: руки над головой.
            val last = arm.wrist.point!!
            val reach = Feature.ARM_TO_TORSO * torso
            out[side.joint(Limb.WRIST)] = PosePoint(last.x, min(last.y, shoulder.y - reach), last.confidence)
            if (elbowOut) {
                val e = arm.elbow.point!!
                out[side.joint(Limb.ELBOW)] =
                    PosePoint(e.x, min(e.y, shoulder.y - reach * StickRatio.UPPER_ARM_SHARE), e.confidence)
            }
        }
        val shift = frame.height.toFloat()
        val shifted = out.mapValues { (_, p) -> p.copy(y = p.y + shift) }
        return PoseFrame(frame.timeMs, frame.width, frame.height * 2, shifted)
    }

    private object StickRatio {
        /** Доля плеча в длине руки (плечо ≈ 0,6, предплечье ≈ 0,55 торса). */
        const val UPPER_ARM_SHARE = 0.52f
    }
}
