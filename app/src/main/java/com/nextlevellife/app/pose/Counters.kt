package com.nextlevellife.app.pose

import com.nextlevellife.app.pose.Geometry.progress
import kotlin.math.abs

/**
 * Счётчики повторений по камере. Один общий автомат ([PhaseCounter]) и девять конфигураций.
 * Все пороги — в [CounterThresholds]: углы в градусах, высоты в долях длины торса
 * (у становой — в долях длины бедра).
 */
object RepCounters {
    /**
     * Счётчик для упражнения по его id; null, если камера это упражнение считать не умеет.
     * Вид счёта — [RepCounter.kind]: повторения или удержание («plank»).
     */
    fun forExercise(exerciseId: String): RepCounter? =
        ExerciseConfigs.byId[exerciseId]?.let { PhaseCounter(it) }
            ?: HoldConfigs.byId[exerciseId]?.let { HoldCounter(it) }
}

/**
 * Все пороги в одном месте. «Высота A над B» = (B.y − A.y) / длина торса:
 * положительная — A выше на экране.
 *
 * ВСЕ ЧИСЛА — ПЕРВОЕ ПРИБЛИЖЕНИЕ, НАСТРАИВАЕТСЯ ПО ЗАПИСЯМ С ТЕЛЕФОНА.
 * Пороги крайних точек проверяются по несглаженному сигналу (с защитой от одиночного
 * выброса), поэтому они на ~10° мягче первой версии, а полуамплитуда всё равно не проходит.
 * У поз два набора: ENTER — строгий вход в упражнение, HOLD — шире, пока человек двигается.
 */
internal object CounterThresholds {
    /** Стоящий человек (упражнения стоя). */
    object Standing {
        /** Вход: плечо выше бедра хотя бы на столько (доли торса). Наклон торса до ~80° ещё проходит. */
        const val ENTER_SHOULDER_OVER_HIP = 0.15f

        /** Вход: бедро выше лодыжки хотя бы на столько. Лёжа ≈ 0, в глубоком приседе ≈ 0,8. */
        const val ENTER_HIP_OVER_ANKLE = 0.5f

        /** Во время движения: плечи не ниже бёдер заметно (не упал, не лёг). */
        const val HOLD_SHOULDER_OVER_HIP = -0.05f

        /** Во время движения: бёдра выше лодыжек хотя бы на столько (глубокий присед ещё проходит). */
        const val HOLD_HIP_OVER_ANKLE = 0.25f
    }

    object Pushups {
        /** Нижняя точка: угол в локте меньше (было 95). */
        const val ELBOW_BOTTOM = 105f

        /** Верхняя (исходная) точка: рука почти прямая (было 155). */
        const val ELBOW_TOP = 150f

        /** Для индикатора глубины: «совсем прямая» рука. */
        const val ELBOW_STRAIGHT = 165f

        /** Вход: запястья ниже плеч. */
        const val ENTER_WRIST_OVER_SHOULDER_MAX = 0f

        /** Вход: тело не стоит — линия лодыжка→плечо отклонена от вертикали больше. */
        const val ENTER_BODY_TILT_MIN = 45f

        /** Вход без лодыжек (анфас): плечи выше бёдер не больше чем на столько торса. */
        const val ENTER_SHOULDER_OVER_HIP_MAX = 0.6f

        /** Вход анфас с видимыми лодыжками: бёдра не выше лодыжек больше чем на столько. */
        const val ENTER_HIP_OVER_ANKLE_MAX = 0.5f

        /** В движении: запястья не выше плеч больше чем на столько. */
        const val HOLD_WRIST_OVER_SHOULDER_MAX = 0.3f

        /** В движении: тело наклонено больше… */
        const val HOLD_BODY_TILT_MIN = 30f

        /** …или плечи выше бёдер меньше чем на столько. */
        const val HOLD_SHOULDER_OVER_HIP_MAX = 0.8f
    }

    object Squats {
        /** Низ: угол в колене меньше (было 100). */
        const val KNEE_BOTTOM = 110f

        /** Верх: (было 160). */
        const val KNEE_TOP = 155f
        const val KNEE_STRAIGHT = 170f

        /** Вход: плечи выше бёдер, бёдра выше лодыжек. */
        const val ENTER_SHOULDER_OVER_HIP = 0.3f
        const val ENTER_HIP_OVER_ANKLE = Standing.ENTER_HIP_OVER_ANKLE
    }

    object Abs {
        /** Лёжа: подъём торса над горизонталью меньше (было 25). */
        const val ELEVATION_DOWN = 30f

        /**
         * Верх: подъём торса больше. Оставлен 55: в три четверти сжатие по горизонтали
         * поднимает видимый угол торса (полуподъём 37° выглядит как 45°), мягче — пройдёт половина.
         */
        const val ELEVATION_UP = 55f

        /** Для индикатора: «совсем лёжа». */
        const val ELEVATION_FLAT = 15f

        /** Вход: на полу — бедро и лодыжка по высоте отличаются меньше чем на столько торса… */
        const val ENTER_HIP_ANKLE_LEVEL = 0.4f

        /** …и торс почти лежит. */
        const val ENTER_ELEVATION_MAX = 45f

        /** В движении: бедро и лодыжка по высоте отличаются меньше. */
        const val HOLD_HIP_ANKLE_LEVEL = 0.7f
    }

    object Pullups {
        /** Вход: запястья выше плеч. */
        const val ENTER_WRIST_OVER_SHOULDER_MIN = 0f

        /** В движении (подтягивание до груди): запястья не ниже плеч больше чем на столько. */
        const val HOLD_WRIST_OVER_SHOULDER_MIN = -0.25f

        /** Низ (исходное, вис на прямых руках): локоть больше (было 150). */
        const val ELBOW_HANG = 140f

        /** Верх: локоть меньше (было 90)… */
        const val ELBOW_TOP = 100f

        /** …и нос не ниже запястий больше чем на столько торса (было 0,15). */
        const val NOSE_BELOW_WRIST_MAX = 0.25f

        const val ELBOW_STRAIGHT = 165f

        /** Минимальный полный цикл. */
        const val MIN_REP_MS = 500L
    }

    object Thruster {
        /** Низ: (было 100). */
        const val KNEE_BOTTOM = 110f

        /** Встал: (было 160). */
        const val KNEE_TOP = 155f
        const val KNEE_STRAIGHT = 170f

        /**
         * Верх: запястья выше носа хотя бы на столько торса — руки над головой
         * (макушка ≈ 0,2 торса над носом; полувыжим с локтями у плеч ≈ 0,3).
         */
        const val WRIST_OVER_NOSE_TOP = 0.4f

        const val MIN_REP_MS = 500L
    }

    object Swings {
        /**
         * Низ: торс наклонён от вертикали больше. Оставлен 35: в три четверти видимый наклон
         * меньше настоящего, а половинный мах (наклон ~28°) должен не проходить.
         */
        const val TORSO_TILT_BOTTOM = 35f

        /** …и запястья ниже бёдер (высота над бедром меньше; было 0). */
        const val WRIST_OVER_HIP_BOTTOM = 0.1f

        /** Верх: торс почти вертикален (было 20)… */
        const val TORSO_TILT_TOP = 25f

        /** …и запястья на уровне груди: не ниже плеча больше чем на столько торса (было 0,35). */
        const val WRIST_BELOW_SHOULDER_TOP = 0.45f

        /** Для индикатора: торс «совсем прямо». */
        const val TORSO_TILT_UPRIGHT = 10f
    }

    object SumoPull {
        /** Низ (исходное): запястья ниже бёдер. */
        const val WRIST_OVER_HIP_BOTTOM = 0f

        /** Верх: запястья не ниже плеч больше чем на столько торса (было 0,2)… */
        const val WRIST_BELOW_SHOULDER_TOP = 0.3f

        /** …локти не ниже запястий заметно (было строго выше)… */
        const val ELBOW_OVER_WRIST_TOP = -0.05f

        /** …торс почти вертикален (было 20). */
        const val TORSO_TILT_TOP = 25f

        /** Для индикатора: типичная высота запястий внизу (над плечом, доли торса). */
        const val WRIST_OVER_SHOULDER_LOW = -1.3f
    }

    object Row {
        /** Вход: торс наклонён от вертикали больше… */
        const val ENTER_TORSO_TILT_MIN = 35f

        /** …но не лежит. */
        const val ENTER_TORSO_TILT_MAX = 100f

        /** В движении: торс наклонён в этих пределах. */
        const val HOLD_TORSO_TILT_MIN = 25f
        const val HOLD_TORSO_TILT_MAX = 110f

        /** Если лодыжки видны: бёдра выше лодыжек (человек стоит, а не лежит). */
        const val ENTER_HIP_OVER_ANKLE = Standing.ENTER_HIP_OVER_ANKLE

        /** Низ (исходное): рука прямая (было 150). */
        const val ELBOW_BOTTOM = 140f

        /** Верх: локоть согнут сильнее (было 100)… */
        const val ELBOW_TOP = 110f

        /** …и запястье у пояса: не ниже бедра больше чем на столько торса (было 0,3). */
        const val WRIST_BELOW_HIP_TOP = 0.4f

        const val ELBOW_STRAIGHT = 165f
    }

    object Deadlift {
        /**
         * Низ: торс наклонён больше. Оставлен 45: половинная тяга (наклон ~35°)
         * не должна проходить и в три четверти.
         */
        const val TORSO_TILT_BOTTOM = 45f

        /** …и запястья у коленей или ниже: выше колена не больше чем на столько длины бедра (было 0,2). */
        const val WRIST_OVER_KNEE_BOTTOM = 0.3f

        /** Верх: торс почти вертикален (было 15)… */
        const val TORSO_TILT_TOP = 20f

        /** …колени выпрямлены (было 160)… */
        const val KNEE_TOP = 155f

        /** …запястья ниже бёдер (руки опущены, гиря у бёдер). */
        const val WRIST_OVER_HIP_TOP = 0f

        /** Вход: в низу становой торс бывает почти горизонтален — плечи выше бёдер хоть немного. */
        const val ENTER_SHOULDER_OVER_HIP = 0.1f

        const val TORSO_TILT_UPRIGHT = 10f
    }

    /**
     * Планка на предплечьях, камера строго сбоку. ENTER — вход (непрерывно [ENTER_MS]),
     * HOLD — пока отрезок идёт (гистерезис). Ровная планка: таз и колено ≈ 180°, локоть ≈ 90°,
     * локоть под плечом, линия плечо–лодыжка поднята над горизонталью на ~10°.
     */
    object Plank {
        /** Угол в тазу плечо–таз–лодыжка не меньше. */
        const val ENTER_HIP_ANGLE = 165f
        const val HOLD_HIP_ANGLE = 160f

        /** Угол в колене не меньше. */
        const val ENTER_KNEE = 160f
        const val HOLD_KNEE = 155f

        /** Угол в локте в этих пределах (предплечье на полу, плечо вертикально ≈ 90°). */
        const val ENTER_ELBOW_MIN = 70f
        const val ENTER_ELBOW_MAX = 110f
        const val HOLD_ELBOW_MIN = 65f
        const val HOLD_ELBOW_MAX = 115f

        /** Таз отклонён от отрезка плечо–лодыжка не больше чем на столько торса (вверх или вниз). */
        const val ENTER_HIP_OFF_LINE = 0.15f
        const val HOLD_HIP_OFF_LINE = 0.20f

        /** Линия плечо–лодыжка наклонена от горизонтали не больше (и на входе, и в удержании). */
        const val BODY_TILT_MAX = 30f

        /** Локоть ниже плеча: плечо выше локтя больше чем на столько торса. */
        const val SHOULDER_OVER_ELBOW_MIN = 0f

        /** Локоть под плечом: смещение по горизонтали не больше стольких торса. */
        const val ELBOW_SHOULDER_DX_MAX = 0.35f

        /** Вход: столько мс подряд правильных кадров. */
        const val ENTER_MS = 500L

        /** Оплачивается интервал между соседними хорошими кадрами не длиннее (как устаревание сустава). */
        const val PAID_GAP_MS = JointTuning.STALE_MS

        /** Провал (плохие или пропавшие кадры) дольше — отрезок завершается. */
        const val BREAK_MS = EngineTuning.POSTURE_GRACE_MS

        /** Отрезок короче не засчитывается. */
        const val MIN_SEGMENT_MS = 10_000L
    }
}

internal object HoldConfigs {
    private const val LIE_DOWN = "Ляг в планку боком к телефону"
    private const val HIP_UP = "Таз выше"
    private const val HIP_DOWN = "Таз ниже"
    private const val STRAIGHTEN_LEGS = "Выпрями ноги"
    private const val ELBOWS_UNDER = "Локти под плечи"

    /** Пороги одного набора (вход или удержание). Сравнения записаны так, что NaN — нарушение. */
    private class PlankLimits(
        val hipAngle: Float,
        val knee: Float,
        val elbowMin: Float,
        val elbowMax: Float,
        val hipOffLine: Float,
    )

    private val plankEnter = with(CounterThresholds.Plank) {
        PlankLimits(ENTER_HIP_ANGLE, ENTER_KNEE, ENTER_ELBOW_MIN, ENTER_ELBOW_MAX, ENTER_HIP_OFF_LINE)
    }
    private val plankHold = with(CounterThresholds.Plank) {
        PlankLimits(HOLD_HIP_ANGLE, HOLD_KNEE, HOLD_ELBOW_MIN, HOLD_ELBOW_MAX, HOLD_HIP_OFF_LINE)
    }

    /** Что не так с планкой (подсказка) или null. Порядок — от грубого к тонкому. */
    private fun plankFault(s: Sample, strict: Boolean): String? = with(CounterThresholds.Plank) {
        val l = if (strict) plankEnter else plankHold
        val elbow = s[Feature.ELBOW]
        val off = s[Feature.HIP_OFF_LINE]
        when {
            !(s[Feature.BODY_FROM_HORIZONTAL] <= BODY_TILT_MAX) -> LIE_DOWN
            !(elbow >= l.elbowMin && elbow <= l.elbowMax) ||
                !(s[Feature.SHOULDER_OVER_ELBOW] > SHOULDER_OVER_ELBOW_MIN) ||
                !(s[Feature.ELBOW_SHOULDER_DX] <= ELBOW_SHOULDER_DX_MAX) -> ELBOWS_UNDER
            !(s[Feature.KNEE] >= l.knee) -> STRAIGHTEN_LEGS
            off.isNaN() -> LIE_DOWN
            !(s[Feature.HIP_ANGLE] >= l.hipAngle) || !(abs(off) <= l.hipOffLine) -> if (off < 0f) HIP_UP else HIP_DOWN
            else -> null
        }
    }

    private val plank = with(CounterThresholds.Plank) {
        HoldConfig(
            id = "plank",
            required = setOf(Limb.SHOULDER, Limb.ELBOW, Limb.WRIST, Limb.HIP, Limb.KNEE, Limb.ANKLE),
            postureHint = LIE_DOWN,
            fault = ::plankFault,
            enterMs = ENTER_MS,
            paidGapMs = PAID_GAP_MS,
            breakMs = BREAK_MS,
            minSegmentMs = MIN_SEGMENT_MS,
        )
    }

    val all: List<HoldConfig> = listOf(plank)

    val byId: Map<String, HoldConfig> = all.associateBy { it.id }
}

internal object ExerciseConfigs {
    private const val STAND_UP = "Встань прямо"
    private const val LOWER = "Ниже"
    private const val HIGHER = "Выше"
    private const val ALL_THE_WAY = "До конца"

    /** Условие на признак, который может быть не виден: NaN нарушением позы не считается. */
    private inline fun ok(v: Float, cond: (Float) -> Boolean) = v.isNaN() || cond(v)

    private fun standingEnter(s: Sample, shoulderOverHip: Float = CounterThresholds.Standing.ENTER_SHOULDER_OVER_HIP) =
        s[Feature.SHOULDER_OVER_HIP] > shoulderOverHip &&
            s[Feature.HIP_OVER_ANKLE] > CounterThresholds.Standing.ENTER_HIP_OVER_ANKLE

    private fun standingHold(s: Sample) =
        ok(s[Feature.SHOULDER_OVER_HIP]) { it > CounterThresholds.Standing.HOLD_SHOULDER_OVER_HIP } &&
            ok(s[Feature.HIP_OVER_ANKLE]) { it > CounterThresholds.Standing.HOLD_HIP_OVER_ANKLE }

    private val pushups = with(CounterThresholds.Pushups) {
        ExerciseConfig(
            id = "pushups",
            postureHint = "Прими упор лёжа",
            startHint = "Выпрями руки",
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = { s ->
                val bodyTilt = s[Feature.BODY_TILT]
                val shoulderOverHip = s[Feature.SHOULDER_OVER_HIP]
                val notStanding = if (bodyTilt.isNaN()) {
                    shoulderOverHip < ENTER_SHOULDER_OVER_HIP_MAX
                } else {
                    bodyTilt > ENTER_BODY_TILT_MIN ||
                        (shoulderOverHip < ENTER_SHOULDER_OVER_HIP_MAX &&
                            s[Feature.HIP_OVER_ANKLE] < ENTER_HIP_OVER_ANKLE_MAX)
                }
                s[Feature.WRIST_OVER_SHOULDER] < ENTER_WRIST_OVER_SHOULDER_MAX && notStanding
            },
            hold = { s ->
                ok(s[Feature.WRIST_OVER_SHOULDER]) { it < HOLD_WRIST_OVER_SHOULDER_MAX } &&
                    (s[Feature.BODY_TILT].let { it.isNaN() || it > HOLD_BODY_TILT_MIN } ||
                        ok(s[Feature.SHOULDER_OVER_HIP]) { it < HOLD_SHOULDER_OVER_HIP_MAX })
            },
            rest = listOf(Feature.ELBOW.above(ELBOW_TOP)),
            far = listOf(Feature.ELBOW.below(ELBOW_BOTTOM)),
            finish = listOf(Feature.ELBOW.above(ELBOW_TOP)),
            depth = { progress(it[Feature.ELBOW], ELBOW_STRAIGHT, ELBOW_BOTTOM) },
        )
    }

    private val squats = with(CounterThresholds.Squats) {
        ExerciseConfig(
            id = "squats",
            postureHint = STAND_UP,
            startHint = STAND_UP,
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = {
                it[Feature.SHOULDER_OVER_HIP] > ENTER_SHOULDER_OVER_HIP &&
                    it[Feature.HIP_OVER_ANKLE] > ENTER_HIP_OVER_ANKLE
            },
            hold = { standingHold(it) },
            rest = listOf(Feature.KNEE.above(KNEE_TOP)),
            far = listOf(Feature.KNEE.below(KNEE_BOTTOM)),
            finish = listOf(Feature.KNEE.above(KNEE_TOP)),
            depth = { progress(it[Feature.KNEE], KNEE_STRAIGHT, KNEE_BOTTOM) },
        )
    }

    private val abs = with(CounterThresholds.Abs) {
        ExerciseConfig(
            id = "abs",
            postureHint = "Ляг на спину, согни ноги",
            startHint = "Ляг на спину",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = {
                abs(it[Feature.HIP_OVER_ANKLE]) < ENTER_HIP_ANKLE_LEVEL &&
                    it[Feature.TORSO_ELEVATION] < ENTER_ELEVATION_MAX
            },
            hold = { s -> ok(s[Feature.HIP_OVER_ANKLE]) { abs(it) < HOLD_HIP_ANKLE_LEVEL } },
            rest = listOf(Feature.TORSO_ELEVATION.below(ELEVATION_DOWN)),
            far = listOf(Feature.TORSO_ELEVATION.above(ELEVATION_UP)),
            finish = listOf(Feature.TORSO_ELEVATION.below(ELEVATION_DOWN)),
            depth = { progress(it[Feature.TORSO_ELEVATION], ELEVATION_FLAT, ELEVATION_UP) },
        )
    }

    private val pullups = with(CounterThresholds.Pullups) {
        ExerciseConfig(
            id = "pullups",
            postureHint = "Повисни на турнике",
            startHint = "Повисни на прямых руках",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = { it[Feature.WRIST_OVER_SHOULDER] > ENTER_WRIST_OVER_SHOULDER_MIN },
            hold = { s -> ok(s[Feature.WRIST_OVER_SHOULDER]) { it > HOLD_WRIST_OVER_SHOULDER_MIN } },
            rest = listOf(Feature.ELBOW.above(ELBOW_HANG)),
            // wristOverNose ≤ 0,25 ⇔ nose.y ≤ wrist.y + 0,25 торса
            far = listOf(Feature.ELBOW.below(ELBOW_TOP), Feature.WRIST_OVER_NOSE.below(NOSE_BELOW_WRIST_MAX)),
            finish = listOf(Feature.ELBOW.above(ELBOW_HANG)),
            depth = { progress(it[Feature.ELBOW], ELBOW_STRAIGHT, ELBOW_TOP) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val thruster = with(CounterThresholds.Thruster) {
        ExerciseConfig(
            id = "thruster",
            postureHint = STAND_UP,
            startHint = STAND_UP,
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = { standingEnter(it) },
            hold = { standingHold(it) },
            rest = listOf(Feature.KNEE.above(KNEE_TOP)),
            far = listOf(Feature.KNEE.below(KNEE_BOTTOM)),
            finish = listOf(Feature.KNEE.above(KNEE_TOP), Feature.WRIST_OVER_NOSE.above(WRIST_OVER_NOSE_TOP)),
            depth = { progress(it[Feature.KNEE], KNEE_STRAIGHT, KNEE_BOTTOM) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val swings = with(CounterThresholds.Swings) {
        ExerciseConfig(
            id = "swings",
            postureHint = STAND_UP,
            farHint = LOWER,
            finishHint = HIGHER,
            enter = { standingEnter(it) },
            hold = { standingHold(it) },
            rest = null,
            far = listOf(Feature.TORSO_TILT.above(TORSO_TILT_BOTTOM), Feature.WRIST_OVER_HIP.below(WRIST_OVER_HIP_BOTTOM)),
            finish = listOf(
                Feature.TORSO_TILT.below(TORSO_TILT_TOP),
                Feature.WRIST_OVER_SHOULDER.above(-WRIST_BELOW_SHOULDER_TOP),
            ),
            depth = { progress(it[Feature.TORSO_TILT], TORSO_TILT_UPRIGHT, TORSO_TILT_BOTTOM) },
        )
    }

    private val sumoPull = with(CounterThresholds.SumoPull) {
        ExerciseConfig(
            id = "sumo_pull",
            postureHint = STAND_UP,
            startHint = "Опусти гирю",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = { standingEnter(it) },
            hold = { standingHold(it) },
            rest = listOf(Feature.WRIST_OVER_HIP.below(WRIST_OVER_HIP_BOTTOM)),
            far = listOf(
                Feature.WRIST_OVER_SHOULDER.above(-WRIST_BELOW_SHOULDER_TOP),
                Feature.ELBOW_OVER_WRIST.above(ELBOW_OVER_WRIST_TOP),
                Feature.TORSO_TILT.below(TORSO_TILT_TOP),
            ),
            finish = listOf(Feature.WRIST_OVER_HIP.below(WRIST_OVER_HIP_BOTTOM)),
            depth = {
                progress(it[Feature.WRIST_OVER_SHOULDER], WRIST_OVER_SHOULDER_LOW, -WRIST_BELOW_SHOULDER_TOP)
            },
        )
    }

    private val row = with(CounterThresholds.Row) {
        ExerciseConfig(
            id = "row",
            postureHint = "Наклонись к гире",
            startHint = "Выпрями руку",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = {
                val tilt = it[Feature.TORSO_TILT]
                tilt > ENTER_TORSO_TILT_MIN && tilt < ENTER_TORSO_TILT_MAX &&
                    ok(it[Feature.HIP_OVER_ANKLE]) { h -> h > ENTER_HIP_OVER_ANKLE }
            },
            hold = { s -> ok(s[Feature.TORSO_TILT]) { it > HOLD_TORSO_TILT_MIN && it < HOLD_TORSO_TILT_MAX } },
            rest = listOf(Feature.ELBOW.above(ELBOW_BOTTOM)),
            far = listOf(Feature.ELBOW.below(ELBOW_TOP), Feature.WRIST_OVER_HIP.above(-WRIST_BELOW_HIP_TOP)),
            finish = listOf(Feature.ELBOW.above(ELBOW_BOTTOM)),
            depth = { progress(it[Feature.ELBOW], ELBOW_STRAIGHT, ELBOW_TOP) },
        )
    }

    private val deadlift = with(CounterThresholds.Deadlift) {
        ExerciseConfig(
            id = "deadlift",
            postureHint = STAND_UP,
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = { standingEnter(it, shoulderOverHip = ENTER_SHOULDER_OVER_HIP) },
            hold = { standingHold(it) },
            rest = null,
            far = listOf(Feature.TORSO_TILT.above(TORSO_TILT_BOTTOM), Feature.WRIST_OVER_KNEE.below(WRIST_OVER_KNEE_BOTTOM)),
            finish = listOf(
                Feature.TORSO_TILT.below(TORSO_TILT_TOP),
                Feature.KNEE.above(KNEE_TOP),
                Feature.WRIST_OVER_HIP.below(WRIST_OVER_HIP_TOP),
            ),
            depth = { progress(it[Feature.TORSO_TILT], TORSO_TILT_UPRIGHT, TORSO_TILT_BOTTOM) },
        )
    }

    val all: List<ExerciseConfig> =
        listOf(pushups, squats, abs, pullups, thruster, swings, sumoPull, row, deadlift)

    val byId: Map<String, ExerciseConfig> = all.associateBy { it.id }
}
