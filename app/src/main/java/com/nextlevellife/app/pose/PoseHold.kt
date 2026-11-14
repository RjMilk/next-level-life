package com.nextlevellife.app.pose

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Произвольные позы и мягкие движения программ ЛФК и йоги (docs/exercise-catalog.md, разделы 2–4).
 *
 * Обобщение планки ([HoldCounter]) через ОПИСАНИЕ позы: условие — набор диапазонов признаков
 * ([PoseCond]) с порогами входа (строже) и удержания (шире), кадр бывает ХОРОШИЙ / ПЛОХОЙ / НЕИЗВЕСТНЫЙ,
 * вход «вживую» (переход в позу из другой позы — защита от фотографии), допуск на дрожание точек
 * (лёгкое сглаживание + гистерезис) и на кратковременную потерю точек (разрыв до [PoseHoldTiming.breakMs]
 * сохраняет отрезок, сам разрыв не оплачивается). Для несимметричных поз — рабочая сторона и смена стороны.
 *
 * В отличие от [Feature] (признаки одной стороны тела) здесь признаки считаются по обеим сторонам сразу:
 * у Воина II одно колено согнуто, другое прямое, у Горы обе руки над головой. Поэтому разбор суставов
 * свой ([PoseJoints]), а геометрия, допуск кадра и уверенность — общие ([Geometry], [JointTuning]).
 * Все пороги — первое приближение, настраиваются по записям (как [CounterThresholds]).
 */

/** Сторона тела по анатомии пользователя (зеркальное превью смысл не меняет: точки ML Kit анатомические). */
enum class PoseSide(
    /** «левая», «правая» — сторона, нога, рука. */
    val fem: String,
    /** «левую», «правую» — «согни левую ногу». */
    val femAcc: String,
    /** «левое», «правое» — колено. */
    val neut: String,
) {
    LEFT("левая", "левую", "левое"),
    RIGHT("правая", "правую", "правое"),
    ;

    val other: PoseSide get() = if (this == LEFT) RIGHT else LEFT

    /** Сустав этой стороны; null — у признака такого сустава нет. */
    internal fun joint(limb: Limb): Joint? = when (limb) {
        Limb.NOSE -> Joint.NOSE
        Limb.SHOULDER -> if (this == LEFT) Joint.LEFT_SHOULDER else Joint.RIGHT_SHOULDER
        Limb.ELBOW -> if (this == LEFT) Joint.LEFT_ELBOW else Joint.RIGHT_ELBOW
        Limb.WRIST -> if (this == LEFT) Joint.LEFT_WRIST else Joint.RIGHT_WRIST
        Limb.HIP -> if (this == LEFT) Joint.LEFT_HIP else Joint.RIGHT_HIP
        Limb.KNEE -> if (this == LEFT) Joint.LEFT_KNEE else Joint.RIGHT_KNEE
        Limb.ANKLE -> if (this == LEFT) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE
        else -> null
    }
}

/** Чью сторону проверяет условие: рабочую, другую или обе (каждая должна пройти). */
internal enum class Of { WORK, OTHER, BOTH }

/**
 * Признак произвольной позы. Углы — градусы, высоты и расстояния — доли масштаба тела S
 * ([Feature.scale], ≈ длина торса). [cross] — признак сразу двух сторон (рабочей и другой).
 */
internal enum class PoseMetric(val isAngle: Boolean, val cross: Boolean, vararg limbs: Limb) {
    /** Колено бедро–колено–лодыжка: 180 — нога прямая. */
    KNEE(true, false, Limb.HIP, Limb.KNEE, Limb.ANKLE),

    /** Локоть плечо–локоть–запястье: 180 — рука прямая. */
    ELBOW(true, false, Limb.SHOULDER, Limb.ELBOW, Limb.WRIST),

    /** Сгибание в тазу плечо–таз–колено (каталог: H): 180 — корпус и бедро на одной прямой. */
    HIP_FLEX(true, false, Limb.SHOULDER, Limb.HIP, Limb.KNEE),

    /** Подъём руки таз–плечо–локоть (каталог: A): ≈ 0 — рука опущена вдоль тела, 180 — над головой. */
    ARM_RAISE(true, false, Limb.HIP, Limb.SHOULDER, Limb.ELBOW),

    /** Наклон торса (таз→плечо) от вертикали (каталог: T): 0 — прямо, 90 — лёжа. */
    TORSO_TILT(true, false, Limb.HIP, Limb.SHOULDER),

    /** Подъём торса над горизонталью, −90..90: плечо выше таза — положительный. */
    TORSO_ELEVATION(true, false, Limb.HIP, Limb.SHOULDER),

    /** Отклонение торса от горизонтали без знака, 0..90: 0 — корпус горизонтален. */
    TORSO_FLAT(true, false, Limb.HIP, Limb.SHOULDER),

    /** Отклонение голени (колено→лодыжка) от горизонтали, 0..90: 0 — голень горизонтальна. */
    SHIN_FLAT(true, false, Limb.KNEE, Limb.ANKLE),

    /** Наклон ноги (лодыжка→таз) от вертикали, 0..180: отведение прямой ноги в сторону при виде спереди. */
    LEG_TILT(true, false, Limb.HIP, Limb.ANKLE),

    /** Насколько запястье выше носа. */
    WRIST_OVER_NOSE(false, false, Limb.WRIST, Limb.NOSE),

    /** Насколько запястье выше плеча. */
    WRIST_OVER_SHOULDER(false, false, Limb.WRIST, Limb.SHOULDER),

    /** Насколько плечо выше таза (отрицательное — плечо ниже таза). */
    SHOULDER_OVER_HIP(false, false, Limb.SHOULDER, Limb.HIP),

    /** Насколько плечо выше локтя. */
    SHOULDER_OVER_ELBOW(false, false, Limb.SHOULDER, Limb.ELBOW),

    /** Насколько таз выше колена. */
    HIP_OVER_KNEE(false, false, Limb.HIP, Limb.KNEE),

    /** Расстояние запястье–плечо по горизонтали (≥ 0): кисть под плечом — около 0. */
    WRIST_SHOULDER_DX(false, false, Limb.WRIST, Limb.SHOULDER),

    /** Расстояние локоть–плечо по горизонтали (≥ 0). */
    ELBOW_SHOULDER_DX(false, false, Limb.ELBOW, Limb.SHOULDER),

    /** Расстояние колено–таз по горизонтали (≥ 0): колено под тазом — около 0. */
    KNEE_HIP_DX(false, false, Limb.KNEE, Limb.HIP),

    /** Расстояние лодыжка–таз по горизонтали (≥ 0). */
    ANKLE_HIP_DX(false, false, Limb.ANKLE, Limb.HIP),

    /**
     * Запястье впереди плеча: смещение по горизонтали в сторону «таз → плечо» (куда смотрит корпус
     * у лежащего и стоящего на четвереньках). NaN, если торс вертикален и «вперёд» не определено.
     */
    WRIST_AHEAD(false, false, Limb.WRIST, Limb.SHOULDER, Limb.HIP),

    /** Разнос лодыжек рабочей и другой ноги по горизонтали (≥ 0). */
    ANKLE_SPREAD(false, true, Limb.ANKLE),

    /** Насколько колено другой ноги ниже колена рабочей (выпад: заднее колено у пола). */
    KNEE_DROP(false, true, Limb.KNEE),
    ;

    val limbs: Set<Limb> = limbs.toSet()

    /** Значение на стороне [p] ([q] — другая сторона, нужна только для [cross]); NaN — суставов нет. */
    fun measure(p: (Limb) -> PosePoint?, q: (Limb) -> PosePoint?, s: Float): Float {
        val n = p(Limb.NOSE)
        val sh = p(Limb.SHOULDER)
        val el = p(Limb.ELBOW)
        val wr = p(Limb.WRIST)
        val hip = p(Limb.HIP)
        val kn = p(Limb.KNEE)
        val an = p(Limb.ANKLE)
        return when (this) {
            KNEE -> if (hip != null && kn != null && an != null) Geometry.jointAngle(hip, kn, an) else Float.NaN
            ELBOW -> if (sh != null && el != null && wr != null) Geometry.jointAngle(sh, el, wr) else Float.NaN
            HIP_FLEX -> if (sh != null && hip != null && kn != null) Geometry.jointAngle(sh, hip, kn) else Float.NaN
            ARM_RAISE -> if (hip != null && sh != null && el != null) Geometry.jointAngle(hip, sh, el) else Float.NaN
            TORSO_TILT -> if (hip != null && sh != null) Geometry.tiltFromVertical(hip, sh) else Float.NaN
            TORSO_ELEVATION -> if (hip != null && sh != null) Geometry.elevation(hip, sh) else Float.NaN
            TORSO_FLAT -> if (hip != null && sh != null) Geometry.tiltFromHorizontal(hip, sh) else Float.NaN
            SHIN_FLAT -> if (kn != null && an != null) Geometry.tiltFromHorizontal(kn, an) else Float.NaN
            LEG_TILT -> if (an != null && hip != null) Geometry.tiltFromVertical(an, hip) else Float.NaN
            WRIST_OVER_NOSE -> if (wr != null && n != null) Geometry.heightAbove(wr, n, s) else Float.NaN
            WRIST_OVER_SHOULDER -> if (wr != null && sh != null) Geometry.heightAbove(wr, sh, s) else Float.NaN
            SHOULDER_OVER_HIP -> if (sh != null && hip != null) Geometry.heightAbove(sh, hip, s) else Float.NaN
            SHOULDER_OVER_ELBOW -> if (sh != null && el != null) Geometry.heightAbove(sh, el, s) else Float.NaN
            HIP_OVER_KNEE -> if (hip != null && kn != null) Geometry.heightAbove(hip, kn, s) else Float.NaN
            WRIST_SHOULDER_DX -> if (wr != null && sh != null) Geometry.horizontalGap(wr, sh, s) else Float.NaN
            ELBOW_SHOULDER_DX -> if (el != null && sh != null) Geometry.horizontalGap(el, sh, s) else Float.NaN
            KNEE_HIP_DX -> if (kn != null && hip != null) Geometry.horizontalGap(kn, hip, s) else Float.NaN
            ANKLE_HIP_DX -> if (an != null && hip != null) Geometry.horizontalGap(an, hip, s) else Float.NaN
            WRIST_AHEAD -> {
                if (wr == null || sh == null || hip == null || !(s > 0f)) return Float.NaN
                val dir = sh.x - hip.x
                if (abs(dir) < s * MIN_FORWARD_DX) Float.NaN else (wr.x - sh.x) * (if (dir > 0f) 1f else -1f) / s
            }
            ANKLE_SPREAD -> {
                val b = q(Limb.ANKLE)
                if (an != null && b != null) Geometry.horizontalGap(an, b, s) else Float.NaN
            }
            KNEE_DROP -> {
                val b = q(Limb.KNEE)
                if (kn != null && b != null && s > 0f) (b.y - kn.y) / s else Float.NaN
            }
        }
    }

    companion object {
        /** «Вперёд» для [WRIST_AHEAD] определено, если торс отклонён по горизонтали хотя бы на столько S. */
        const val MIN_FORWARD_DX = 0.3f
    }
}

/**
 * Условие позы: [metric] на стороне [of] в диапазоне [enter] при входе (строже) и [hold] — пока поза
 * держится (шире: гистерезис против дрожания на пороге). [hint] — короткая подсказка, если не выполнено;
 * [lowHint]/[highHint] — если значение ниже/выше диапазона (null — [hint]). NaN — нарушение.
 */
internal class PoseCond(
    val metric: PoseMetric,
    val of: Of,
    val enter: ClosedFloatingPointRange<Float>,
    val hold: ClosedFloatingPointRange<Float>,
    val hint: String,
    val lowHint: String? = null,
    val highHint: String? = null,
) {
    fun range(strict: Boolean) = if (strict) enter else hold

    override fun toString() = "$metric[$of] ∈ $enter / $hold"
}

/** Невыполненное условие [cond] и значение [value], на котором оно не прошло. */
internal class PoseFailure(val cond: PoseCond, val value: Float, private val strict: Boolean) {
    val hint: String
        get() {
            val r = cond.range(strict)
            return when {
                value.isNaN() -> cond.hint
                value < r.start -> cond.lowHint ?: cond.hint
                value > r.endInclusive -> cond.highHint ?: cond.hint
                else -> cond.hint
            }
        }
}

/** Что видно в кадре: позу оценить нельзя / поза достоверно неправильная / правильная. */
enum class PoseVerdict { UNKNOWN, BAD, GOOD }

/**
 * Суставы обеих сторон во времени: свежие (достоверны в этом кадре) и устаревшие (не старше
 * [JointTuning.STALE_MS]). Свежие точки слегка сглаживаются (экспонента с постоянной [tauMs],
 * каталог: «лёгкое сглаживание 80–120 мс») — это допуск на дрожание, а не расширение позы.
 */
internal class PoseJoints(private val tauMs: Float = PoseHoldTiming.SMOOTH_TAU_MS) {
    private val n = Joint.entries.size
    private val pos = arrayOfNulls<PosePoint>(n)
    private val freshAt = LongArray(n)
    private val freshNow = BooleanArray(n)
    private val conf = FloatArray(n)

    fun update(frame: PoseFrame) {
        val t = frame.timeMs
        for (j in Joint.entries) {
            val i = j.ordinal
            val raw = frame.points[j]?.takeIf { JointTuning.usable(it, frame) }
            conf[i] = raw?.confidence ?: 0f
            val p = raw?.takeIf { it.confidence >= JointTuning.MIN_CONFIDENCE }
            if (p != null) {
                val old = pos[i]
                val dt = t - freshAt[i]
                pos[i] = if (old == null || !freshNow[i] || dt <= 0L || dt > JointTuning.STALE_MS || tauMs <= 0f) {
                    p
                } else {
                    val a = 1f - exp(-dt / tauMs)
                    PosePoint(old.x + (p.x - old.x) * a, old.y + (p.y - old.y) * a, p.confidence)
                }
                freshAt[i] = t
                freshNow[i] = true
            } else {
                freshNow[i] = false
                if (pos[i] != null && t - freshAt[i] > JointTuning.STALE_MS) pos[i] = null
            }
        }
    }

    fun point(side: PoseSide, limb: Limb): PosePoint? = side.joint(limb)?.let { pos[it.ordinal] }

    fun fresh(side: PoseSide, limb: Limb): Boolean = side.joint(limb)?.let { freshNow[it.ordinal] } ?: false

    fun confidence(side: PoseSide, limb: Limb): Float = side.joint(limb)?.let { conf[it.ordinal] } ?: 0f

    fun reset() {
        pos.fill(null)
        freshNow.fill(false)
        conf.fill(0f)
    }
}

/** Сустав конкретной стороны — элемент списка обязательных точек позы. */
internal data class SideLimb(val side: PoseSide, val limb: Limb)

/** Проверка набора условий на суставах. Чистые функции — проверяются тестами. */
internal object PoseCheck {
    /** Обязательные суставы условий [conds] при рабочей стороне [work] (отдельно для каждой стороны). */
    fun required(conds: List<PoseCond>, work: PoseSide): Set<SideLimb> {
        val out = LinkedHashSet<SideLimb>()
        for (c in conds) {
            val sides = when {
                c.metric.cross -> listOf(work, work.other)
                c.of == Of.WORK -> listOf(work)
                c.of == Of.OTHER -> listOf(work.other)
                else -> listOf(work, work.other)
            }
            for (side in sides) for (limb in c.metric.limbs) out += SideLimb(side, limb)
        }
        // Масштаб тела — по торсу рабочей стороны.
        out += SideLimb(work, Limb.SHOULDER)
        out += SideLimb(work, Limb.HIP)
        return out
    }

    /** Масштаб тела S по рабочей стороне, если там его не оценить — по другой. */
    fun scale(j: PoseJoints, work: PoseSide): Float {
        val s = Feature.scale { j.point(work, it) }
        return if (s > 0f) s else Feature.scale { j.point(work.other, it) }
    }

    /** Значение условия [c] на стороне [side] (рабочая — [work]). */
    fun value(c: PoseCond, j: PoseJoints, side: PoseSide, scale: Float): Float =
        c.metric.measure({ j.point(side, it) }, { j.point(side.other, it) }, scale)

    /** Первое невыполненное условие (порядок — от грубого к тонкому) или null — все выполнены. */
    fun firstFailing(conds: List<PoseCond>, j: PoseJoints, work: PoseSide, strict: Boolean): PoseFailure? {
        val s = scale(j, work)
        for (c in conds) {
            val sides = when {
                c.metric.cross || c.of == Of.WORK -> listOf(work)
                c.of == Of.OTHER -> listOf(work.other)
                else -> listOf(work, work.other)
            }
            val r = c.range(strict)
            for (side in sides) {
                val v = value(c, j, side, s)
                if (!(v >= r.start && v <= r.endInclusive)) return PoseFailure(c, v, strict)
            }
        }
        return null
    }

    fun allFresh(j: PoseJoints, need: Set<SideLimb>): Boolean = need.all { j.fresh(it.side, it.limb) }

    fun score(j: PoseJoints, need: Set<SideLimb>): Float = need.sumOf { j.confidence(it.side, it.limb).toDouble() }.toFloat()

    /** Подсказка, каких суставов не хватает. */
    fun missingHint(missing: Collection<Limb>): String = when {
        missing.isEmpty() -> PoseHints.NOT_IN_FRAME
        LEGS.containsAll(missing) -> PoseHints.NO_LEGS
        ARMS.containsAll(missing) -> PoseHints.NO_ARMS
        else -> PoseHints.NOT_IN_FRAME
    }

    private val LEGS = setOf(Limb.HIP, Limb.KNEE, Limb.ANKLE)
    private val ARMS = setOf(Limb.ELBOW, Limb.WRIST)
}

/** Общие подсказки поз и движений. Коротко: плашка на 360 dp вмещает их крупным шрифтом. */
object PoseHints {
    const val NOT_SEEN = "Не вижу тебя"
    const val NOT_IN_FRAME = "Встань в кадр целиком"
    const val NO_LEGS = "Не вижу ноги"
    const val NO_ARMS = "Не вижу руки"
    const val START_OVER = "Выйди из позы и прими её снова"
    const val SLOWER = "Медленнее"
}

/**
 * Время удержания (каталог, раздел 4). Первое приближение, настраивается по записям.
 * @param enterMs столько непрерывно хороших кадров (строгие пороги) нужно для входа («Держи»).
 * @param liveOutMs «не в позе» засчитывается, если такие кадры идут непрерывно хотя бы столько.
 * @param liveWindowMs вход возможен, только если человек был виден не в позе не раньше чем за столько.
 * @param paidGapMs интервал между соседними хорошими кадрами оплачивается, только если не длиннее.
 * @param breakMs без хорошего кадра дольше — отрезок завершается (короткий разрыв прогресс сохраняет).
 * @param minSegmentMs отрезок короче не засчитывается (кроме добирающего цель до конца).
 * @param lostResetMs человека нет дольше — допуск на живой вход сгорает (вернуться мог другой).
 */
internal data class PoseHoldTiming(
    val enterMs: Long = ENTER_MS,
    val liveOutMs: Long = LIVE_OUT_MS,
    val liveWindowMs: Long = LIVE_WINDOW_MS,
    val paidGapMs: Long = JointTuning.STALE_MS,
    val breakMs: Long = BREAK_MS,
    val minSegmentMs: Long = MIN_SEGMENT_MS,
    val lostResetMs: Long = LOST_RESET_MS,
) {
    companion object {
        /** Каталог: «условия входа выполняются непрерывно около 800 мс». */
        const val ENTER_MS = 800L

        /** Каталог: «человек вне целевой позы не менее 300–500 мс». */
        const val LIVE_OUT_MS = 400L

        /** Как у планки: переход в позу не позже 15 с после того, как человек был виден не в позе. */
        const val LIVE_WINDOW_MS = 15_000L

        /** Каталог: «короткий разрыв до примерно 700 мс сохраняет прогресс». */
        const val BREAK_MS = 700L

        /** Каталог: «минимальный зачётный отрезок — 10 с». */
        const val MIN_SEGMENT_MS = 10_000L

        const val LOST_RESET_MS = 1_500L

        /** Сглаживание свежих точек: экспонента ≈ 50 мс (окно порядка 100 мс). */
        const val SMOOTH_TAU_MS = 50f
    }
}

/**
 * Описание позы для удержания: [conds] — обязательные условия (И, по порядку — первое невыполненное
 * даёт подсказку). [sided] — несимметричная поза: условия заданы для рабочей стороны (WORK) и другой
 * (OTHER), рабочую сторону назначает шаг программы. У симметричной позы рабочая сторона — лучше видимая.
 * [postureHint] — когда человека не видно вовсе; [sideHint] — поза выполнена зеркально (не той стороной).
 */
internal class PoseShape(
    val id: String,
    val sided: Boolean,
    val conds: List<PoseCond>,
    val postureHint: String,
    val sideHint: (PoseSide) -> String = { "Другая сторона: ${it.fem}" },
)

/** Что ещё знает счётчик позы, кроме [RepState]. */
data class PoseHoldInfo(
    /** Набрано целевое время шага: дальше время не идёт. */
    val done: Boolean = false,
    /** Идёт вход: поза правильная, ждём [PoseHoldTiming.enterMs] перед «Держи». */
    val entering: Boolean = false,
    /** Какой стороной человек делает позу (только для несимметричных; null — не понятно). */
    val observedSide: PoseSide? = null,
    val verdict: PoseVerdict = PoseVerdict.UNKNOWN,
)

/**
 * Удержание произвольной позы [shape] до цели [targetMs]. Автомат как у [HoldCounter]:
 * ВНЕ ПОЗЫ → (хороший кадр по строгим порогам + живой вход) ВХОД → (непрерывно [PoseHoldTiming.enterMs])
 * УДЕРЖАНИЕ. Время — сумма интервалов между соседними хорошими кадрами (не длиннее paidGapMs, без плохих
 * кадров между ними, без смены стороны); время входа засчитывается задним числом. Плохой кадр сразу
 * останавливает накопление, провал до breakMs отрезок сохраняет, дольше — завершает. Отрезок засчитывается,
 * если он не короче minSegmentMs или добрал цель до конца. Набрана цель — [PoseHoldInfo.done], время стоит.
 *
 * Несимметричная поза: условия проверяются для назначенной стороны [side]; если поза не проходит, а
 * зеркальная (другой стороной) проходит — подсказка [PoseShape.sideHint] и [PoseHoldInfo.observedSide].
 * [switchSide] — новая сторона: текущий отрезок закрывается, нужен видимый выход и новый вход.
 *
 * Между шагами программы (отдых) кадры можно подавать в [observe]: счётчик следующего шага запоминает,
 * что человек был виден не в позе (допуск на живой вход), но не входит в позу и не платит.
 */
class PoseHoldCounter internal constructor(
    private val shape: PoseShape,
    side: PoseSide?,
    targetMs: Long,
    private val timing: PoseHoldTiming = PoseHoldTiming(),
) : RepCounter {
    private enum class Phase { OUT, ENTERING, HOLDING }

    override val kind: CountKind get() = CountKind.HOLD

    override var state: RepState = RepState()
        private set

    var info: PoseHoldInfo = PoseHoldInfo()
        private set

    /** Рабочая сторона несимметричной позы (null у симметричной). */
    var side: PoseSide? = if (shape.sided) side ?: PoseSide.LEFT else null
        private set

    val targetMs: Long = targetMs.coerceAtLeast(1L)

    private val joints = PoseJoints()
    private var autoSide: PoseSide? = null
    private var phase = Phase.OUT
    private var enterSince = 0L
    private var lastFrameMs: Long? = null
    private var lastGoodMs: Long? = null
    private var pairOpen = false
    private var segmentMs = 0L
    private var confirmedMs = 0L
    private var done = false
    private var outSince: Long? = null
    private var liveAt: Long? = null
    private var lastTrackedMs: Long? = null
    private var lastHint = shape.postureHint

    override fun onFrame(frame: PoseFrame): RepState = process(frame, observeOnly = false)

    /** Кадр без входа и оплаты: только допуск на живой вход (отдых перед шагом). */
    fun observe(frame: PoseFrame): RepState = process(frame, observeOnly = true)

    private fun process(frame: PoseFrame, observeOnly: Boolean): RepState {
        val t = frame.timeMs
        val prevFrame = lastFrameMs
        if (prevFrame != null && t <= prevFrame) return state
        lastFrameMs = t
        joints.update(frame)

        val lastTracked = lastTrackedMs
        if (lastTracked != null && t - lastTracked > timing.lostResetMs) {
            lastTrackedMs = null
            outSince = null
            liveAt = null
            autoSide = null
        }
        val lastGood = lastGoodMs
        if (!observeOnly && phase == Phase.HOLDING && lastGood != null && t - lastGood > timing.breakMs) closeSegment()

        val work = workSide()
        val need = PoseCheck.required(shape.conds, work)
        var verdict = PoseVerdict.UNKNOWN
        var observed: PoseSide? = null
        var hint: String
        if (frame.points.isEmpty()) {
            hint = PoseHints.NOT_SEEN
        } else if (!PoseCheck.allFresh(joints, need)) {
            val missing = need.filter { joints.point(it.side, it.limb) == null }.map { it.limb }
            hint = if (missing.isEmpty()) lastHint else PoseCheck.missingHint(missing)
        } else {
            lastTrackedMs = t
            val failing = PoseCheck.firstFailing(shape.conds, joints, work, strict = phase != Phase.HOLDING)
            if (failing == null) {
                verdict = PoseVerdict.GOOD
                hint = ""
                if (shape.sided) observed = work
            } else {
                verdict = PoseVerdict.BAD
                hint = failing.hint
                val other = work.other
                if (shape.sided && PoseCheck.allFresh(joints, PoseCheck.required(shape.conds, other)) &&
                    PoseCheck.firstFailing(shape.conds, joints, other, strict = false) == null
                ) {
                    // Поза верная, но зеркальная: не той стороной.
                    observed = other
                    hint = shape.sideHint(work)
                }
            }
        }

        // Живой вход: человек виден не в позе непрерывно хотя бы liveOutMs.
        when (verdict) {
            PoseVerdict.BAD -> {
                val since = outSince ?: t.also { outSince = it }
                if (t - since >= timing.liveOutMs) liveAt = t
            }
            PoseVerdict.GOOD -> outSince = null
            PoseVerdict.UNKNOWN -> Unit
        }

        if (observeOnly || done) {
            if (verdict == PoseVerdict.GOOD && liveAt == null && !done) hint = PoseHints.START_OVER
            lastHint = hint
            return publish(verdict, observed, hint)
        }

        val good = verdict == PoseVerdict.GOOD
        when (phase) {
            Phase.OUT -> if (good && !startEntering(t)) hint = PoseHints.START_OVER
            Phase.ENTERING -> when (verdict) {
                PoseVerdict.BAD -> {
                    phase = Phase.OUT
                    segmentMs = 0L
                }
                PoseVerdict.GOOD -> {
                    val prevGood = lastGoodMs
                    if (prevGood == null || t - prevGood > timing.paidGapMs) {
                        phase = Phase.OUT
                        segmentMs = 0L
                        if (!startEntering(t)) hint = PoseHints.START_OVER
                    } else {
                        pay(t)
                        if (!done && t - enterSince >= timing.enterMs) {
                            phase = Phase.HOLDING
                            liveAt = null
                        }
                    }
                }
                PoseVerdict.UNKNOWN -> Unit
            }
            Phase.HOLDING -> if (good) pay(t)
        }
        when (verdict) {
            PoseVerdict.GOOD -> {
                lastGoodMs = t
                pairOpen = true
            }
            PoseVerdict.BAD -> pairOpen = false
            PoseVerdict.UNKNOWN -> Unit
        }
        lastHint = hint
        return publish(verdict, observed, hint)
    }

    private fun publish(verdict: PoseVerdict, observed: PoseSide?, hint: String): RepState {
        val holding = phase == Phase.HOLDING && !done
        val counted = holding && segmentMs >= minOf(timing.minSegmentMs, targetMs - confirmedMs)
        state = RepState(
            count = 0,
            tracking = verdict != PoseVerdict.UNKNOWN,
            inPosition = holding,
            hint = hint,
            atStart = holding,
            heldMs = confirmedMs + if (counted) segmentMs else 0L,
            currentSegmentMs = if (holding) segmentMs else 0L,
            pendingMs = if (holding && !counted) segmentMs else 0L,
            holding = holding && verdict == PoseVerdict.GOOD,
        )
        info = PoseHoldInfo(
            done = done,
            entering = phase == Phase.ENTERING && !done,
            observedSide = observed,
            verdict = verdict,
        )
        return state
    }

    /** Рабочая сторона: назначенная у несимметричной позы, лучше видимая (липкая) — у симметричной. */
    private fun workSide(): PoseSide {
        side?.let { return it }
        val cur = autoSide
        if (cur != null && PoseCheck.allFresh(joints, PoseCheck.required(shape.conds, cur))) return cur
        val best = PoseSide.entries
            .filter { PoseCheck.allFresh(joints, PoseCheck.required(shape.conds, it)) }
            .maxByOrNull { PoseCheck.score(joints, PoseCheck.required(shape.conds, it)) }
        if (best != null && best != cur) {
            // Смена стороны рвёт оплачиваемую пару кадров: стороны не смешиваем.
            autoSide = best
            pairOpen = false
            return best
        }
        return cur ?: PoseSide.entries.maxBy { PoseCheck.score(joints, PoseCheck.required(shape.conds, it)) }
    }

    private fun startEntering(t: Long): Boolean {
        val live = liveAt
        if (live == null || t - live > timing.liveWindowMs) return false
        phase = Phase.ENTERING
        enterSince = t
        segmentMs = 0L
        return true
    }

    private fun pay(t: Long) {
        val last = lastGoodMs ?: return
        val dt = t - last
        if (!pairOpen || dt > timing.paidGapMs) return
        segmentMs = min(segmentMs + dt, targetMs - confirmedMs)
        if (confirmedMs + segmentMs >= targetMs) {
            confirmedMs = targetMs
            segmentMs = 0L
            done = true
            phase = Phase.OUT
        }
    }

    private fun closeSegment() {
        if (segmentMs >= minOf(timing.minSegmentMs, targetMs - confirmedMs)) confirmedMs += segmentMs
        segmentMs = 0L
        phase = Phase.OUT
        lastGoodMs = null
        pairOpen = false
    }

    /**
     * Сменить рабочую сторону несимметричной позы: текущий отрезок закрывается (засчитанное по прежней
     * стороне вызывающий берёт из [state] до смены), допуск на живой вход сгорает — новая сторона начинается
     * только после видимого выхода из позы и собственного входа. Цель — та же, время новой стороны
     * копится заново: [RepState.heldMs] после смены — только новая сторона.
     */
    fun switchSide(newSide: PoseSide) {
        if (!shape.sided || newSide == side) return
        closeSegment()
        side = newSide
        confirmedMs = 0L
        done = false
        liveAt = null
        outSince = null
        state = RepState()
        info = PoseHoldInfo()
    }

    override fun reset() {
        phase = Phase.OUT
        enterSince = 0L
        lastFrameMs = null
        lastGoodMs = null
        pairOpen = false
        segmentMs = 0L
        confirmedMs = 0L
        done = false
        outSince = null
        liveAt = null
        lastTrackedMs = null
        autoSide = null
        lastHint = shape.postureHint
        joints.reset()
        state = RepState()
        info = PoseHoldInfo()
    }
}

/**
 * Мягкое движение ЛФК для [PoseRepCounter]: повторение — «исходное ([start]) → крайняя точка ([peak]) →
 * исходное». [always] — условия корпуса на всё время подхода (по широким порогам): нарушение дольше
 * [PoseRepTiming.POSTURE_GRACE_MS] — снова ждём исходное положение. [amplitudeHint] — вернулся, не дойдя
 * до крайней точки. [sided] — работает одна конечность (рабочая сторона — из шага программы).
 */
internal class PoseMove(
    val id: String,
    val sided: Boolean,
    val start: List<PoseCond>,
    val peak: List<PoseCond>,
    val always: List<PoseCond>,
    val postureHint: String,
    val amplitudeHint: String,
    val sideHint: (PoseSide) -> String = { "Другая сторона: ${it.fem}" },
) {
    val allConds: List<PoseCond> get() = always + start + peak
}

/** Тайминги мягких повторений. Первое приближение, настраивается по записям. */
internal object PoseRepTiming {
    /** Исходное положение подтверждается столько мс подряд. */
    const val START_MS = 500L

    /** Крайняя точка подтверждается столько мс подряд (медленное движение проходит её не меньше чем за это). */
    const val PEAK_MS = 150L

    /** Нарушение корпуса дольше — человек вышел из упражнения. */
    const val POSTURE_GRACE_MS = 700L

    /** Каталог: «полный цикл не быстрее 5 с» — между засчитанными повторениями не меньше. */
    const val MIN_PERIOD_MS = 5_000L

    /** Человека нет дольше — снова ждём исходное положение (счёт сохраняется). */
    const val LOST_RESET_MS = 1_500L
}

/**
 * Счётчик мягких повторений ЛФК: ПОИСК → (исходное [PoseRepTiming.START_MS]) ИСХОДНОЕ → (ушёл из исходного)
 * ДВИЖЕНИЕ → (крайняя точка [PoseRepTiming.PEAK_MS]) ВОЗВРАТ → (снова исходное) +1 и ИСХОДНОЕ. Повторение
 * быстрее [PoseRepTiming.MIN_PERIOD_MS] после прошлого засчитанного не засчитывается («Медленнее»).
 * Само движение доказывает, что перед камерой живой человек, поэтому отдельного «живого входа» нет.
 */
class PoseRepCounter internal constructor(
    private val move: PoseMove,
    side: PoseSide?,
    private val target: Int = Int.MAX_VALUE,
) : RepCounter {
    private enum class Phase { SEARCH, START, MOVE, PEAK }

    override val kind: CountKind get() = CountKind.REPS

    override var state: RepState = RepState()
        private set

    /** Какой стороной человек делает движение (только для одностороннего; null — не понятно). */
    var observedSide: PoseSide? = null
        private set

    val side: PoseSide? = if (move.sided) side ?: PoseSide.LEFT else null

    private val joints = PoseJoints()
    private var autoSide: PoseSide? = null
    private var phase = Phase.SEARCH
    private var count = 0
    private var lastFrameMs: Long? = null
    private var startSince: Long? = null
    private var peakSince: Long? = null
    private var badSince: Long? = null
    private var lastKnownMs: Long? = null
    private var lastRepAt = 0L
    private var lastHint = move.postureHint

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        val prev = lastFrameMs
        if (prev != null && t <= prev) return state
        lastFrameMs = t
        joints.update(frame)

        val work = workSide()
        val need = PoseCheck.required(move.allConds, work)
        if (frame.points.isEmpty() || !PoseCheck.allFresh(joints, need)) {
            val known = lastKnownMs
            if (known != null && t - known > PoseRepTiming.LOST_RESET_MS) toSearch()
            val hint = if (frame.points.isEmpty()) {
                PoseHints.NOT_SEEN
            } else {
                val missing = need.filter { joints.point(it.side, it.limb) == null }.map { it.limb }
                if (missing.isEmpty()) lastHint else PoseCheck.missingHint(missing)
            }
            return publish(tracking = false, hint = hint)
        }
        lastKnownMs = t

        val postureFail = PoseCheck.firstFailing(move.always, joints, work, strict = phase == Phase.SEARCH)
        val startFail = PoseCheck.firstFailing(move.start, joints, work, strict = true)
        val atStart = postureFail == null && startFail == null
        val atStartLoose = postureFail == null && PoseCheck.firstFailing(move.start, joints, work, strict = false) == null
        val atPeak = postureFail == null && PoseCheck.firstFailing(move.peak, joints, work, strict = true) == null
        observedSide = when {
            !move.sided -> null
            atPeak -> work
            PoseCheck.allFresh(joints, PoseCheck.required(move.allConds, work.other)) &&
                PoseCheck.firstFailing(move.always, joints, work.other, strict = false) == null &&
                PoseCheck.firstFailing(move.peak, joints, work.other, strict = true) == null -> work.other
            else -> null
        }
        var hint = ""

        if (postureFail != null) {
            hint = postureFail.hint
            val since = badSince ?: t.also { badSince = it }
            if (phase != Phase.SEARCH && t - since > PoseRepTiming.POSTURE_GRACE_MS) toSearch()
        } else {
            badSince = null
        }

        when (phase) {
            Phase.SEARCH -> {
                if (atStart) {
                    val since = startSince ?: t.also { startSince = it }
                    if (t - since >= PoseRepTiming.START_MS) {
                        phase = Phase.START
                        lastRepAt = t
                    }
                } else {
                    startSince = null
                    if (hint.isEmpty()) hint = startFail?.hint ?: move.postureHint
                }
            }
            Phase.START -> if (!atStartLoose && postureFail == null) {
                phase = Phase.MOVE
                peakSince = null
            }
            Phase.MOVE -> when {
                atPeak -> {
                    val since = peakSince ?: t.also { peakSince = it }
                    if (t - since >= PoseRepTiming.PEAK_MS) phase = Phase.PEAK
                }
                atStart -> {
                    // Вернулся, не дойдя до крайней точки.
                    phase = Phase.START
                    hint = move.amplitudeHint
                }
                else -> peakSince = null
            }
            Phase.PEAK -> if (atStart) {
                phase = Phase.START
                if (t - lastRepAt >= PoseRepTiming.MIN_PERIOD_MS) {
                    if (count < target) count++
                    lastRepAt = t
                } else {
                    hint = PoseHints.SLOWER
                }
            }
        }
        // Движение другой стороной: подсказка о стороне важнее подсказки об опорной ноге.
        if (move.sided && observedSide == work.other && phase != Phase.PEAK) hint = move.sideHint(work)
        lastHint = hint
        return publish(tracking = true, hint = hint)
    }

    private fun toSearch() {
        phase = Phase.SEARCH
        startSince = null
        peakSince = null
        badSince = null
    }

    private fun publish(tracking: Boolean, hint: String): RepState {
        state = RepState(
            count = count,
            tracking = tracking,
            inPosition = phase != Phase.SEARCH,
            hint = hint,
            depth = if (phase == Phase.PEAK) 1f else 0f,
            atStart = phase == Phase.START,
        )
        return state
    }

    private fun workSide(): PoseSide {
        side?.let { return it }
        val cur = autoSide
        if (cur != null && PoseCheck.allFresh(joints, PoseCheck.required(move.allConds, cur))) return cur
        val best = PoseSide.entries
            .filter { PoseCheck.allFresh(joints, PoseCheck.required(move.allConds, it)) }
            .maxByOrNull { PoseCheck.score(joints, PoseCheck.required(move.allConds, it)) }
        if (best != null && best != cur) {
            // Другая сторона — фаза повторения с нуля: стороны не смешиваем.
            autoSide = best
            if (cur != null) toSearch()
            return best
        }
        return cur ?: PoseSide.entries.maxBy { PoseCheck.score(joints, PoseCheck.required(move.allConds, it)) }
    }

    override fun reset() {
        joints.reset()
        autoSide = null
        count = 0
        lastFrameMs = null
        lastKnownMs = null
        lastRepAt = 0L
        observedSide = null
        lastHint = move.postureHint
        toSearch()
        state = RepState()
    }
}
