package com.nextlevellife.app.pose

import kotlin.math.max

/** Сустав без стороны: счётчик сам выбирает, левую или правую половину тела смотреть. */
internal enum class Limb { NOSE, SHOULDER, ELBOW, WRIST, HIP, KNEE, ANKLE }

internal enum class Side(private val joints: Map<Limb, Joint>) {
    LEFT(
        mapOf(
            Limb.NOSE to Joint.NOSE,
            Limb.SHOULDER to Joint.LEFT_SHOULDER, Limb.ELBOW to Joint.LEFT_ELBOW,
            Limb.WRIST to Joint.LEFT_WRIST, Limb.HIP to Joint.LEFT_HIP,
            Limb.KNEE to Joint.LEFT_KNEE, Limb.ANKLE to Joint.LEFT_ANKLE,
        ),
    ),
    RIGHT(
        mapOf(
            Limb.NOSE to Joint.NOSE,
            Limb.SHOULDER to Joint.RIGHT_SHOULDER, Limb.ELBOW to Joint.RIGHT_ELBOW,
            Limb.WRIST to Joint.RIGHT_WRIST, Limb.HIP to Joint.RIGHT_HIP,
            Limb.KNEE to Joint.RIGHT_KNEE, Limb.ANKLE to Joint.RIGHT_ANKLE,
        ),
    ),
    ;

    fun joint(limb: Limb): Joint = joints.getValue(limb)
}

/**
 * Признак позы. Углы — в градусах, высоты — в долях масштаба тела (≈ длина торса),
 * у [WRIST_OVER_KNEE] — в долях длины бедра. [joints] — главные суставы признака:
 * по их свежести решается, можно ли по признаку подтверждать крайнюю точку.
 */
internal enum class Feature(val isAngle: Boolean, vararg limbs: Limb) {
    /** Угол в локте плечо–локоть–запястье. 180 — рука прямая. */
    ELBOW(true, Limb.SHOULDER, Limb.ELBOW, Limb.WRIST),

    /** Угол в колене бедро–колено–лодыжка. 180 — нога прямая. */
    KNEE(true, Limb.HIP, Limb.KNEE, Limb.ANKLE),

    /** Наклон торса (бедро→плечо) от вертикали: 0 — стоит прямо, 90 — лежит. */
    TORSO_TILT(true, Limb.HIP, Limb.SHOULDER),

    /** Подъём торса над горизонталью: 0 — лежит, 90 — сидит/стоит. */
    TORSO_ELEVATION(true, Limb.HIP, Limb.SHOULDER),

    /** Наклон линии лодыжка→плечо от вертикали (тело целиком). */
    BODY_TILT(true, Limb.ANKLE, Limb.SHOULDER),

    /** Насколько запястье выше плеча. */
    WRIST_OVER_SHOULDER(false, Limb.WRIST, Limb.SHOULDER),

    /** Насколько запястье выше бедра. */
    WRIST_OVER_HIP(false, Limb.WRIST, Limb.HIP),

    /** Насколько запястье выше носа. */
    WRIST_OVER_NOSE(false, Limb.WRIST, Limb.NOSE),

    /** Насколько запястье выше колена (доли длины бедра). */
    WRIST_OVER_KNEE(false, Limb.WRIST, Limb.KNEE, Limb.HIP),

    /** Насколько плечо выше бедра. */
    SHOULDER_OVER_HIP(false, Limb.SHOULDER, Limb.HIP),

    /** Насколько бедро выше лодыжки. */
    HIP_OVER_ANKLE(false, Limb.HIP, Limb.ANKLE),

    /** Насколько локоть выше запястья. */
    ELBOW_OVER_WRIST(false, Limb.ELBOW, Limb.WRIST),

    /** Угол в тазу плечо–таз–лодыжка. 180 — тело одной прямой. */
    HIP_ANGLE(true, Limb.SHOULDER, Limb.HIP, Limb.ANKLE),

    /** Отклонение таза от отрезка плечо–лодыжка: положительное — таз выше линии, отрицательное — провис. */
    HIP_OFF_LINE(false, Limb.SHOULDER, Limb.HIP, Limb.ANKLE),

    /** Наклон линии плечо–лодыжка от горизонтали, 0..90: 0 — тело лежит, 90 — стоит. */
    BODY_FROM_HORIZONTAL(true, Limb.SHOULDER, Limb.ANKLE),

    /** Насколько плечо выше локтя (положительное — локоть ниже плеча). */
    SHOULDER_OVER_ELBOW(false, Limb.SHOULDER, Limb.ELBOW),

    /** Смещение локтя от плеча по горизонтали (всегда ≥ 0). */
    ELBOW_SHOULDER_DX(false, Limb.SHOULDER, Limb.ELBOW),

    /**
     * Угол в тазу плечо–таз–колено (сгибание бедра). 180 — корпус и бедро на одной прямой
     * (верх ягодичного моста), лёжа с согнутыми ногами ≈ 130. В отличие от [HIP_ANGLE] не зависит от голени.
     */
    HIP_FLEXION(true, Limb.SHOULDER, Limb.HIP, Limb.KNEE),
    ;

    val joints: Set<Limb> = limbs.toSet()

    companion object {
        /**
         * Типичные пропорции взрослого относительно торса (плечо–бедро ≈ 1):
         * плечо+предплечье ≈ 1,15, бедро+голень ≈ 1,75.
         */
        const val ARM_TO_TORSO = 1.15f
        const val LEG_TO_TORSO = 1.75f

        /**
         * Масштаб тела ≈ длина торса: максимум из длины торса и оценок по руке и ноге —
         * ракурс может только укоротить отрезок, но не удлинить.
         */
        fun scale(p: (Limb) -> PosePoint?): Float {
            val s = p(Limb.SHOULDER)
            val e = p(Limb.ELBOW)
            val w = p(Limb.WRIST)
            val h = p(Limb.HIP)
            val k = p(Limb.KNEE)
            val a = p(Limb.ANKLE)
            var scale = 0f
            if (s != null && h != null) scale = max(scale, Geometry.dist(s, h))
            if (s != null && e != null && w != null) {
                scale = max(scale, (Geometry.dist(s, e) + Geometry.dist(e, w)) / ARM_TO_TORSO)
            }
            if (h != null && k != null && a != null) {
                scale = max(scale, (Geometry.dist(h, k) + Geometry.dist(k, a)) / LEG_TO_TORSO)
            }
            return if (scale > 0f) scale else Float.NaN
        }

        /** Значения всех признаков по точкам; NaN — нужных суставов нет. */
        fun measureAll(p: (Limb) -> PosePoint?): FloatArray {
            val v = FloatArray(entries.size) { Float.NaN }
            val n = p(Limb.NOSE)
            val s = p(Limb.SHOULDER)
            val e = p(Limb.ELBOW)
            val w = p(Limb.WRIST)
            val h = p(Limb.HIP)
            val k = p(Limb.KNEE)
            val a = p(Limb.ANKLE)
            val scale = scale(p)
            if (s != null && e != null && w != null) v[ELBOW.ordinal] = Geometry.jointAngle(s, e, w)
            if (h != null && k != null && a != null) v[KNEE.ordinal] = Geometry.jointAngle(h, k, a)
            if (h != null && s != null) {
                v[TORSO_TILT.ordinal] = Geometry.tiltFromVertical(h, s)
                v[TORSO_ELEVATION.ordinal] = Geometry.elevation(h, s)
                v[SHOULDER_OVER_HIP.ordinal] = Geometry.heightAbove(s, h, scale)
            }
            if (a != null && s != null) v[BODY_TILT.ordinal] = Geometry.tiltFromVertical(a, s)
            if (w != null && s != null) v[WRIST_OVER_SHOULDER.ordinal] = Geometry.heightAbove(w, s, scale)
            if (w != null && h != null) v[WRIST_OVER_HIP.ordinal] = Geometry.heightAbove(w, h, scale)
            if (w != null && n != null) v[WRIST_OVER_NOSE.ordinal] = Geometry.heightAbove(w, n, scale)
            if (w != null && k != null && h != null) {
                v[WRIST_OVER_KNEE.ordinal] = Geometry.heightAbove(w, k, Geometry.dist(h, k))
            }
            if (h != null && a != null) v[HIP_OVER_ANKLE.ordinal] = Geometry.heightAbove(h, a, scale)
            if (e != null && w != null) v[ELBOW_OVER_WRIST.ordinal] = Geometry.heightAbove(e, w, scale)
            if (s != null && h != null && a != null) {
                v[HIP_ANGLE.ordinal] = Geometry.jointAngle(s, h, a)
                v[HIP_OFF_LINE.ordinal] = Geometry.offsetFromLine(h, s, a, scale)
            }
            if (s != null && h != null && k != null) v[HIP_FLEXION.ordinal] = Geometry.jointAngle(s, h, k)
            if (s != null && a != null) v[BODY_FROM_HORIZONTAL.ordinal] = Geometry.tiltFromHorizontal(a, s)
            if (s != null && e != null) {
                v[SHOULDER_OVER_ELBOW.ordinal] = Geometry.heightAbove(s, e, scale)
                v[ELBOW_SHOULDER_DX.ordinal] = Geometry.horizontalGap(e, s, scale)
            }
            return v
        }
    }
}

/** Общие настройки разбора суставов. */
internal object JointTuning {
    /** Сустав считается достоверным при такой уверенности ML Kit. */
    const val MIN_CONFIDENCE = 0.5f

    /** Кратко пропавший сустав берётся из последнего достоверного положения (с пометкой «устарел»). */
    const val STALE_MS = 250L

    /**
     * Допуск выхода сустава за край кадра, доля ширины (по x) и высоты (по y). ML Kit достраивает
     * суставы за кадром, иногда с высокой уверенностью; дальше допуска такой сустав — «не виден».
     */
    const val FRAME_MARGIN = 0.05f

    /**
     * Точку можно использовать: координаты и уверенность — конечные числа, точка в кадре
     * с допуском [FRAME_MARGIN]. Если размер кадра неизвестен (≤ 0), проверяется только конечность.
     */
    fun usable(p: PosePoint, frame: PoseFrame): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite() || !p.confidence.isFinite()) return false
        if (frame.width <= 0 || frame.height <= 0) return true
        val mx = frame.width * FRAME_MARGIN
        val my = frame.height * FRAME_MARGIN
        return p.x >= -mx && p.x <= frame.width + mx && p.y >= -my && p.y <= frame.height + my
    }
}

/**
 * Суставы одной стороны тела во времени: последнее достоверное положение каждого сустава
 * и признак «свежий» (достоверен в этом кадре) или «устарел» (взят из прошлого, не старше [JointTuning.STALE_MS]).
 * Точка с нечисловыми координатами или за краем кадра ([JointTuning.usable]) считается отсутствующей.
 */
internal class JointTracker(val side: Side) {
    private val n = Limb.entries.size
    private val pos = arrayOfNulls<PosePoint>(n)
    private val freshAt = LongArray(n)
    private val freshNow = BooleanArray(n)
    private val confNow = FloatArray(n)

    fun update(frame: PoseFrame) {
        for (limb in Limb.entries) {
            val i = limb.ordinal
            val p = frame.points[side.joint(limb)]?.takeIf { JointTuning.usable(it, frame) }
            confNow[i] = p?.confidence ?: 0f
            if (p != null && p.confidence >= JointTuning.MIN_CONFIDENCE) {
                pos[i] = p
                freshAt[i] = frame.timeMs
                freshNow[i] = true
            } else {
                freshNow[i] = false
                if (pos[i] != null && frame.timeMs - freshAt[i] > JointTuning.STALE_MS) pos[i] = null
            }
        }
    }

    /** Положение сустава (свежее или устаревшее), null — нет. */
    fun point(limb: Limb): PosePoint? = pos[limb.ordinal]

    fun isFresh(limb: Limb): Boolean = freshNow[limb.ordinal]

    /** Уверенность ML Kit в этом кадре (0, если сустава нет). */
    fun confidence(limb: Limb): Float = confNow[limb.ordinal]

    fun reset() {
        pos.fill(null)
        freshNow.fill(false)
        confNow.fill(0f)
    }
}

/**
 * Признаки одной стороны тела в один момент: значение и «свежесть» (все главные суставы свежие).
 * [freshLimbs] — какие суставы были свежими в этот момент (по [Limb.ordinal]).
 */
internal class Sample(
    val timeMs: Long,
    private val values: FloatArray,
    private val fresh: BooleanArray,
    private val freshLimbs: BooleanArray = BooleanArray(Limb.entries.size),
) {
    operator fun get(f: Feature): Float = values[f.ordinal]

    fun isFresh(f: Feature): Boolean = fresh[f.ordinal]

    /** Все [limbs] были свежими в момент замера. */
    fun allFresh(limbs: Set<Limb>): Boolean = limbs.all { freshLimbs[it.ordinal] }

    companion object {
        fun measure(timeMs: Long, joints: JointTracker): Sample {
            val values = Feature.measureAll { joints.point(it) }
            val fresh = BooleanArray(Feature.entries.size) { i ->
                !values[i].isNaN() && Feature.entries[i].joints.all { joints.isFresh(it) }
            }
            val freshLimbs = BooleanArray(Limb.entries.size) { i -> joints.isFresh(Limb.entries[i]) }
            return Sample(timeMs, values, fresh, freshLimbs)
        }
    }
}

/** Всё, что счётчик ведёт по одной стороне тела: суставы и два последних замера. Стороны не смешиваются. */
internal class SideTrack(val side: Side) {
    val joints = JointTracker(side)
    var cur: Sample? = null
        private set
    var prev: Sample? = null
        private set

    fun update(frame: PoseFrame) {
        joints.update(frame)
        prev = cur
        cur = Sample.measure(frame.timeMs, joints)
    }

    /** Все [limbs] есть (свежие или устаревшие). */
    fun available(limbs: Set<Limb>) = limbs.all { joints.point(it) != null }

    /** Все [limbs] свежие в этом кадре. */
    fun allFresh(limbs: Set<Limb>) = limbs.all { joints.isFresh(it) }

    /** Суммарная уверенность [limbs] в этом кадре. */
    fun score(limbs: Set<Limb>) = limbs.sumOf { joints.confidence(it).toDouble() }.toFloat()

    fun missing(limbs: Set<Limb>) = limbs.filter { joints.point(it) == null }

    fun reset() {
        joints.reset()
        cur = null
        prev = null
    }
}

/**
 * Обе стороны тела и выбор «липкой» стороны: меняется, только если другая сторона заметно
 * увереннее непрерывно [EngineTuning.SIDE_SWITCH_MS] или текущая потеряна дольше
 * [lostSwitchMs] (по умолчанию [EngineTuning.SIDE_LOST_MS]; 0 — сразу же, как только текущая сторона
 * не свежая, а другая свежая целиком). [need] — главные суставы упражнения; [onSwitch] — после смены
 * стороны (счётчик сбрасывает фильтры, чтобы не смешивать стороны).
 */
internal class SideSelector(
    private val need: Set<Limb>,
    private val lostSwitchMs: Long = EngineTuning.SIDE_LOST_MS,
    private val onSwitch: () -> Unit = {},
) {
    val tracks = Side.entries.map { SideTrack(it) }

    var active: SideTrack? = null
        private set
    private var challengerSince: Long? = null
    private var activeStaleSince: Long? = null

    fun update(frame: PoseFrame) {
        for (tr in tracks) tr.update(frame)
    }

    /** Выбранная сторона, если все [need] на ней есть (свежие или устаревшие); иначе null. */
    fun choose(t: Long): SideTrack? {
        val cur = active
        if (cur != null) {
            val other = tracks.first { it !== cur }
            if (cur.allFresh(need)) {
                activeStaleSince = null
            } else if (activeStaleSince == null) {
                activeStaleSince = t
            }
            val staleSince = activeStaleSince
            val otherOk = other.allFresh(need)
            val lostLongEnough = staleSince != null && (lostSwitchMs <= 0L || t - staleSince > lostSwitchMs)
            if (lostLongEnough && otherOk) {
                switchTo(other)
            } else if (otherOk && other.score(need) > cur.score(need) + EngineTuning.SIDE_SWITCH_MARGIN * need.size) {
                val since = challengerSince ?: t.also { challengerSince = it }
                if (t - since >= EngineTuning.SIDE_SWITCH_MS) switchTo(other)
            } else {
                challengerSince = null
            }
        }
        if (active == null) {
            tracks.filter { it.allFresh(need) }.maxByOrNull { it.score(need) }?.let { switchTo(it) }
        }
        return active?.takeIf { it.available(need) }
    }

    /** Сторона, где видно больше всего нужных суставов (для подсказки «Не вижу …»). */
    fun closest(): SideTrack = tracks.maxBy { it.score(need) }

    /** Забыть выбранную сторону (человек пропал надолго); суставы остаются. */
    fun drop() {
        active = null
    }

    fun reset() {
        active = null
        challengerSince = null
        activeStaleSince = null
        tracks.forEach { it.reset() }
    }

    private fun switchTo(track: SideTrack) {
        active = track
        challengerSince = null
        activeStaleSince = null
        onSwitch()
    }
}

// ---------------------------------------------------------------------------------------------
// Диагностика одного кадра (без истории). Используется панелью отладки и разбором записей;
// сам счётчик работает через [SideTrack] и [Sample].

/** Видимые суставы одной стороны тела (с уверенностью не ниже порога). */
internal class Body(val side: Side, private val points: Map<Limb, PosePoint>) {
    operator fun get(limb: Limb): PosePoint? = points[limb]
}

internal sealed interface Located {
    class Found(val body: Body) : Located
    class Lost(val hint: String) : Located
}

internal object BodyLocator {
    const val MIN_CONFIDENCE = JointTuning.MIN_CONFIDENCE

    const val HINT_NOT_IN_FRAME = "Встань в кадр целиком"
    const val HINT_NO_LEGS = "Не вижу ноги"
    const val HINT_NO_ARMS = "Не вижу руки"

    private val LEGS = setOf(Limb.HIP, Limb.KNEE, Limb.ANKLE)
    private val ARMS = setOf(Limb.ELBOW, Limb.WRIST)

    /**
     * Сторона тела по одному кадру: из сторон, где видны все [required] суставы, — та,
     * у которой суммарная уверенность выше. [optional] добавляются, если видны.
     */
    fun locate(frame: PoseFrame, required: Set<Limb>, optional: Set<Limb>): Located {
        if (frame.points.isEmpty()) return Located.Lost(HINT_NOT_IN_FRAME)

        class Candidate(val side: Side, val score: Float, val missing: List<Limb>)

        val candidates = Side.entries.map { side ->
            var score = 0f
            val missing = ArrayList<Limb>()
            for (limb in required) {
                val c = frame.points[side.joint(limb)]?.takeIf { JointTuning.usable(it, frame) }?.confidence ?: 0f
                score += c
                if (c < MIN_CONFIDENCE) missing += limb
            }
            Candidate(side, score, missing)
        }
        val best = candidates.filter { it.missing.isEmpty() }.maxByOrNull { it.score }
        if (best == null) {
            val closest = candidates.maxBy { it.score }
            return Located.Lost(missingHint(closest.missing))
        }
        val points = HashMap<Limb, PosePoint>()
        for (limb in required + optional) {
            val p = frame.points[best.side.joint(limb)]?.takeIf { JointTuning.usable(it, frame) } ?: continue
            if (p.confidence >= MIN_CONFIDENCE) points[limb] = p
        }
        return Located.Found(Body(best.side, points))
    }

    fun missingHint(missing: Collection<Limb>): String = when {
        missing.isEmpty() -> HINT_NOT_IN_FRAME
        LEGS.containsAll(missing) -> HINT_NO_LEGS
        ARMS.containsAll(missing) -> HINT_NO_ARMS
        else -> HINT_NOT_IN_FRAME
    }
}

/** Признаки позы одного кадра по найденному телу (без сглаживания и истории). */
internal class Signals(private val v: FloatArray) {
    operator fun get(f: Feature): Float = v[f.ordinal]

    val elbow get() = this[Feature.ELBOW]
    val knee get() = this[Feature.KNEE]
    val torsoTilt get() = this[Feature.TORSO_TILT]
    val bodyTilt get() = this[Feature.BODY_TILT]
    val torsoElevation get() = this[Feature.TORSO_ELEVATION]
    val wristOverShoulder get() = this[Feature.WRIST_OVER_SHOULDER]
    val wristOverHip get() = this[Feature.WRIST_OVER_HIP]
    val wristOverNose get() = this[Feature.WRIST_OVER_NOSE]
    val wristOverKnee get() = this[Feature.WRIST_OVER_KNEE]
    val shoulderOverHip get() = this[Feature.SHOULDER_OVER_HIP]
    val hipOverAnkle get() = this[Feature.HIP_OVER_ANKLE]
    val elbowOverWrist get() = this[Feature.ELBOW_OVER_WRIST]
    val hipAngle get() = this[Feature.HIP_ANGLE]
    val hipOffLine get() = this[Feature.HIP_OFF_LINE]
    val bodyFromHorizontal get() = this[Feature.BODY_FROM_HORIZONTAL]
    val shoulderOverElbow get() = this[Feature.SHOULDER_OVER_ELBOW]
    val elbowShoulderDx get() = this[Feature.ELBOW_SHOULDER_DX]
    val hipFlexion get() = this[Feature.HIP_FLEXION]

    companion object {
        const val ARM_TO_TORSO = Feature.ARM_TO_TORSO
        const val LEG_TO_TORSO = Feature.LEG_TO_TORSO

        fun scale(b: Body): Float = Feature.scale { b[it] }

        fun measure(b: Body): Signals = Signals(Feature.measureAll { b[it] })
    }
}
