package com.nextlevellife.app.pose

import com.nextlevellife.app.pose.Geometry.progress
import kotlin.math.abs

/**
 * Счётчики повторений по камере. Один общий автомат ([PhaseCounter]) и конфигурации упражнений;
 * упражнения одной рукой — по автомату на руку ([EitherArmCounter]).
 * Все пороги — в [CounterThresholds]: углы в градусах, высоты в долях длины торса
 * (у становой — в долях длины бедра).
 */
object RepCounters {
    /**
     * Счётчик для упражнения по его id; null, если камера это упражнение считать не умеет.
     * Вид счёта — [RepCounter.kind]: повторения или удержание («plank»).
     */
    fun forExercise(exerciseId: String): RepCounter? =
        ExerciseConfigs.byId[exerciseId]?.let {
            if (exerciseId in ExerciseConfigs.eitherArm) EitherArmCounter(it) else PhaseCounter(it)
        }
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
     * Присед с гирей у груди, камера сбоку. Колени — как у приседаний; руки у груди проверяются
     * в позе (если запястья видны): с опущенными руками это обычный присед, он здесь не считается.
     */
    object GobletSquat {
        const val KNEE_BOTTOM = Squats.KNEE_BOTTOM
        const val KNEE_TOP = Squats.KNEE_TOP
        const val KNEE_STRAIGHT = Squats.KNEE_STRAIGHT

        /** Вход: запястья у груди — ниже плеча не больше чем на столько торса… */
        const val ENTER_WRIST_OVER_SHOULDER_MIN = -0.6f

        /** …и не выше плеча больше чем на столько. */
        const val ENTER_WRIST_OVER_SHOULDER_MAX = 0.2f

        /** В движении — те же границы шире (кисти сползают, гиря закрывает запястья). */
        const val HOLD_WRIST_OVER_SHOULDER_MIN = -0.8f
        const val HOLD_WRIST_OVER_SHOULDER_MAX = 0.35f

        /** Полный цикл короче — рывок, а не присед. */
        const val MIN_REP_MS = 600L
    }

    /**
     * Выпад назад с гирей у груди, камера сбоку. Счёт по колену видимой стороны: при шаге назад
     * любой ногой сгибаются оба колена (переднее ≈ 90°, заднее ≈ 90–110°), поэтому повтор виден
     * по любой ноге и путаница левой/правой при перекрытии ног счёту не мешает.
     * Разнос стоп по горизонтали (шаг назад) по одной стороне тела не проверить — не проверяется.
     */
    object ReverseLunge {
        /** Низ: колено согнуто сильнее (заднее колено в выпаде сгибается меньше переднего). */
        const val KNEE_BOTTOM = 125f
        const val KNEE_TOP = 155f
        const val KNEE_STRAIGHT = 170f
        const val MIN_REP_MS = 600L
    }

    /** Жим гири одной рукой стоя, камера спереди. Каждая рука считается своим автоматом ([EitherArmCounter]). */
    object Press {
        /** Гиря у плеча: локоть согнут сильнее… */
        const val RACK_ELBOW_MAX = 115f

        /** …и запястье ниже плеча не больше чем на столько торса (кисть у плеча, а не у пояса). */
        const val RACK_WRIST_OVER_SHOULDER_MIN = -0.5f

        /** Верх: рука почти прямая (каталог: 155, на 5° мягче)… */
        const val TOP_ELBOW = 150f

        /** …и запястье выше носа хотя бы на столько торса (полужим с локтем у уха ≈ 0,25). */
        const val TOP_WRIST_OVER_NOSE = 0.35f

        /** Для индикатора: запястье у плеча — ниже носа примерно на столько. */
        const val RACK_WRIST_OVER_NOSE = -0.3f

        /** В движении (если видно): колени почти прямые — без подседа и толчка ногами. */
        const val HOLD_KNEE_MIN = 140f

        /** В движении: корпус не наклонён. */
        const val HOLD_TORSO_TILT_MAX = 25f

        const val MIN_REP_MS = 500L

        /** Повторы двух рук ближе по времени — это жим двумя руками разом: засчитывается один. */
        const val SIMULTANEOUS_MS = 600L
    }

    /** Сгибание рук с гирей двумя руками, камера сбоку. */
    object Curl {
        /** Низ: рука почти прямая. */
        const val ELBOW_BOTTOM = 145f

        /** Верх: локоть согнут сильнее (каталог: 70, на 5° мягче)… */
        const val ELBOW_TOP = 75f

        /** …запястья выше таза хотя бы на столько торса… */
        const val WRIST_OVER_HIP_TOP = 0.4f

        /** …и корпус почти вертикален: подъём раскачкой с отклонением назад не засчитывается. */
        const val TORSO_TILT_TOP = 20f

        const val ELBOW_STRAIGHT = 165f
        const val MIN_REP_MS = 600L
    }

    /**
     * Ягодичный мост с гирей на тазу, камера сбоку и низко. Без калибровки высоты: верх — бедро
     * на одной прямой с корпусом (угол плечо–таз–колено) и таз заметно выше плеч.
     */
    object GluteBridge {
        /** Верх: угол плечо–таз–колено больше (каталог: 155; полуподъём ≈ 152–158, с шумом до ~162)… */
        const val HIP_FLEXION_TOP = 163f

        /** …и плечо ниже таза хотя бы на столько торса (корпус поднят на ~25°; полуподъём ≈ 0,3). */
        const val SHOULDER_BELOW_HIP_TOP = 0.42f

        /** Низ: таз опущен — угол меньше… */
        const val HIP_FLEXION_DOWN = 145f

        /** …и плечо почти на высоте таза. */
        const val SHOULDER_BELOW_HIP_DOWN = 0.15f

        /** Для индикатора: угол лёжа с согнутыми ногами. */
        const val HIP_FLEXION_FLAT = 130f

        /** Вход: лежит — торс от вертикали в этих пределах… */
        const val ENTER_TORSO_TILT_MIN = 65f
        const val ENTER_TORSO_TILT_MAX = 115f

        /** …колени согнуты (если видны)… */
        const val ENTER_KNEE_MAX = 120f

        /** …таз и лодыжки примерно на одной высоте (если видны). */
        const val ENTER_HIP_ANKLE_LEVEL = 0.4f

        /** В движении: торс не поднят (не сел) и не перевёрнут. */
        const val HOLD_TORSO_TILT_MIN = 50f
        const val HOLD_TORSO_TILT_MAX = 150f

        const val MIN_REP_MS = 600L
    }

    /**
     * Планка на предплечьях, камера строго сбоку. ENTER — вход (непрерывно [ENTER_MS]),
     * HOLD — пока отрезок идёт (гистерезис). Ровная планка: таз и колено ≈ 180°, локоть ≈ 90°,
     * локоть под плечом, линия плечо–лодыжка поднята над горизонталью на ~10°.
     */
    object Plank {
        /** Угол в тазу плечо–таз–лодыжка не меньше. Главный критерий ровного корпуса. */
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

        /**
         * Грубая страховка: таз отклонён от отрезка плечо–лодыжка не больше чем на столько торса
         * (вверх или вниз). Главное — угол в тазу; по знаку отклонения выбирается «Таз выше/ниже».
         * Прямые ноги, угол ровно на пороге: отклонение ≈ 0,17 (165°) и ≈ 0,22 (160°) — страховка шире,
         * чтобы не спорить с углом, и ловит только грубые случаи (например, согнутую ногу, которую угол не видит).
         */
        const val ENTER_HIP_OFF_LINE = 0.20f
        const val HOLD_HIP_OFF_LINE = 0.28f

        /** Линия плечо–лодыжка наклонена от горизонтали не больше (и на входе, и в удержании). */
        const val BODY_TILT_MAX = 30f

        /**
         * Корпус поднят на предплечьях: плечо выше локтя хотя бы на столько торса
         * (в планке ≈ 0,6 — длина плеча; лёжа на животе ≈ 0–0,1).
         */
        const val ENTER_SHOULDER_OVER_ELBOW = 0.25f
        const val HOLD_SHOULDER_OVER_ELBOW = 0.20f

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

        /** Одна непрерывная серия оплачивается не больше (дальше поза держится, но время не растёт). */
        const val MAX_SEGMENT_MS = 180_000L

        /**
         * Живой вход: удержание начинается, только если не раньше чем за столько до входа
         * человек был виден НЕ в планке (а потом лёг в неё). Защита от фотографии планки.
         */
        const val LIVE_WINDOW_MS = 15_000L

        /** «Не в планке» засчитывается, если такая поза видна непрерывно хотя бы столько (не дрожь порога). */
        const val LIVE_OUT_MS = 300L
    }
}

internal object HoldConfigs {
    // Коротко: на 360 dp плашка состояния вмещает это крупным шрифтом в 2 строки.
    private const val LIE_DOWN = "Ляг боком к телефону"
    private const val HIP_UP = "Таз выше"
    private const val HIP_DOWN = "Таз ниже"
    private const val STRAIGHTEN_LEGS = "Выпрями ноги"
    private const val ELBOWS_UNDER = "Локти под плечи"
    private const val RISE_ON_FOREARMS = "Поднимись на предплечья"
    private const val START_OVER = "Опустись на колени и снова прими планку"
    private const val MAX_REACHED = "Максимум за подход — 3 минуты"

    /** Пороги одного набора (вход или удержание). Сравнения записаны так, что NaN — нарушение. */
    private class PlankLimits(
        val hipAngle: Float,
        val knee: Float,
        val elbowMin: Float,
        val elbowMax: Float,
        val hipOffLine: Float,
        val shoulderOverElbow: Float,
    )

    private val plankEnter = with(CounterThresholds.Plank) {
        PlankLimits(
            ENTER_HIP_ANGLE, ENTER_KNEE, ENTER_ELBOW_MIN, ENTER_ELBOW_MAX, ENTER_HIP_OFF_LINE, ENTER_SHOULDER_OVER_ELBOW,
        )
    }
    private val plankHold = with(CounterThresholds.Plank) {
        PlankLimits(
            HOLD_HIP_ANGLE, HOLD_KNEE, HOLD_ELBOW_MIN, HOLD_ELBOW_MAX, HOLD_HIP_OFF_LINE, HOLD_SHOULDER_OVER_ELBOW,
        )
    }

    /**
     * Что не так с планкой (подсказка) или null. Порядок — от грубого к тонкому.
     * Таз: главный критерий — угол плечо–таз–лодыжка; отклонение таза от линии — грубая страховка
     * и направление подсказки (ниже линии — «Таз выше», выше — «Таз ниже»).
     */
    private fun plankFault(s: Sample, strict: Boolean): String? = with(CounterThresholds.Plank) {
        val l = if (strict) plankEnter else plankHold
        val elbow = s[Feature.ELBOW]
        val off = s[Feature.HIP_OFF_LINE]
        when {
            !(s[Feature.BODY_FROM_HORIZONTAL] <= BODY_TILT_MAX) -> LIE_DOWN
            !(s[Feature.SHOULDER_OVER_ELBOW] >= l.shoulderOverElbow) -> RISE_ON_FOREARMS
            !(elbow >= l.elbowMin && elbow <= l.elbowMax) ||
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
            maxSegmentMs = MAX_SEGMENT_MS,
            liveWindowMs = LIVE_WINDOW_MS,
            liveOutMs = LIVE_OUT_MS,
            startOverHint = START_OVER,
            maxHint = MAX_REACHED,
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

    /** Гиря у груди: запястья около плеча (NaN — не видно, например, закрыты гирей — не нарушение). */
    private fun wristsAtChest(s: Sample, min: Float, max: Float) =
        ok(s[Feature.WRIST_OVER_SHOULDER]) { it > min && it < max }

    private val gobletSquat = with(CounterThresholds.GobletSquat) {
        ExerciseConfig(
            id = "goblet_squat",
            postureHint = "Встань прямо, гиря у груди",
            startHint = STAND_UP,
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = {
                standingEnter(it, shoulderOverHip = CounterThresholds.Squats.ENTER_SHOULDER_OVER_HIP) &&
                    wristsAtChest(it, ENTER_WRIST_OVER_SHOULDER_MIN, ENTER_WRIST_OVER_SHOULDER_MAX)
            },
            hold = { standingHold(it) && wristsAtChest(it, HOLD_WRIST_OVER_SHOULDER_MIN, HOLD_WRIST_OVER_SHOULDER_MAX) },
            rest = listOf(Feature.KNEE.above(KNEE_TOP)),
            far = listOf(Feature.KNEE.below(KNEE_BOTTOM)),
            finish = listOf(Feature.KNEE.above(KNEE_TOP)),
            depth = { progress(it[Feature.KNEE], KNEE_STRAIGHT, KNEE_BOTTOM) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val reverseLunge = with(CounterThresholds.ReverseLunge) {
        val chest = CounterThresholds.GobletSquat
        ExerciseConfig(
            id = "reverse_lunge",
            postureHint = "Встань прямо, гиря у груди",
            startHint = STAND_UP,
            farHint = LOWER,
            finishHint = ALL_THE_WAY,
            enter = {
                standingEnter(it) &&
                    wristsAtChest(it, chest.ENTER_WRIST_OVER_SHOULDER_MIN, chest.ENTER_WRIST_OVER_SHOULDER_MAX)
            },
            hold = {
                standingHold(it) &&
                    wristsAtChest(it, chest.HOLD_WRIST_OVER_SHOULDER_MIN, chest.HOLD_WRIST_OVER_SHOULDER_MAX)
            },
            rest = listOf(Feature.KNEE.above(KNEE_TOP)),
            far = listOf(Feature.KNEE.below(KNEE_BOTTOM)),
            finish = listOf(Feature.KNEE.above(KNEE_TOP)),
            depth = { progress(it[Feature.KNEE], KNEE_STRAIGHT, KNEE_BOTTOM) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val press = with(CounterThresholds.Press) {
        val rack = listOf(
            Feature.ELBOW.below(RACK_ELBOW_MAX),
            Feature.WRIST_OVER_SHOULDER.above(RACK_WRIST_OVER_SHOULDER_MIN),
        )
        ExerciseConfig(
            id = "press",
            postureHint = STAND_UP,
            startHint = "Гирю к плечу",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = { standingEnter(it) },
            hold = { s ->
                standingHold(s) &&
                    ok(s[Feature.KNEE]) { it > HOLD_KNEE_MIN } &&
                    ok(s[Feature.TORSO_TILT]) { it < HOLD_TORSO_TILT_MAX }
            },
            rest = rack,
            far = listOf(Feature.ELBOW.above(TOP_ELBOW), Feature.WRIST_OVER_NOSE.above(TOP_WRIST_OVER_NOSE)),
            finish = rack,
            depth = { progress(it[Feature.WRIST_OVER_NOSE], RACK_WRIST_OVER_NOSE, TOP_WRIST_OVER_NOSE) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val curl = with(CounterThresholds.Curl) {
        ExerciseConfig(
            id = "curl",
            postureHint = STAND_UP,
            startHint = "Опусти руки",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = { standingEnter(it) },
            hold = { standingHold(it) },
            rest = listOf(Feature.ELBOW.above(ELBOW_BOTTOM)),
            far = listOf(
                Feature.ELBOW.below(ELBOW_TOP),
                Feature.WRIST_OVER_HIP.above(WRIST_OVER_HIP_TOP),
                Feature.TORSO_TILT.below(TORSO_TILT_TOP),
            ),
            finish = listOf(Feature.ELBOW.above(ELBOW_BOTTOM)),
            depth = { progress(it[Feature.ELBOW], ELBOW_STRAIGHT, ELBOW_TOP) },
            minRepMs = MIN_REP_MS,
        )
    }

    private val gluteBridge = with(CounterThresholds.GluteBridge) {
        val down = listOf(
            Feature.HIP_FLEXION.below(HIP_FLEXION_DOWN),
            Feature.SHOULDER_OVER_HIP.above(-SHOULDER_BELOW_HIP_DOWN),
        )
        ExerciseConfig(
            id = "glute_bridge",
            postureHint = "Ляг на спину, согни ноги",
            startHint = "Опусти таз",
            farHint = HIGHER,
            finishHint = ALL_THE_WAY,
            enter = { s ->
                val tilt = s[Feature.TORSO_TILT]
                tilt > ENTER_TORSO_TILT_MIN && tilt < ENTER_TORSO_TILT_MAX &&
                    s[Feature.HIP_FLEXION] < HIP_FLEXION_DOWN &&
                    ok(s[Feature.KNEE]) { it < ENTER_KNEE_MAX } &&
                    ok(s[Feature.HIP_OVER_ANKLE]) { abs(it) < ENTER_HIP_ANKLE_LEVEL }
            },
            hold = { s -> ok(s[Feature.TORSO_TILT]) { it > HOLD_TORSO_TILT_MIN && it < HOLD_TORSO_TILT_MAX } },
            rest = down,
            far = listOf(
                Feature.HIP_FLEXION.above(HIP_FLEXION_TOP),
                Feature.SHOULDER_OVER_HIP.below(-SHOULDER_BELOW_HIP_TOP),
            ),
            finish = down,
            depth = { progress(it[Feature.HIP_FLEXION], HIP_FLEXION_FLAT, HIP_FLEXION_TOP) },
            minRepMs = MIN_REP_MS,
        )
    }

    val all: List<ExerciseConfig> = listOf(
        pushups, squats, abs, pullups, thruster, swings, sumoPull, row, deadlift,
        gobletSquat, press, reverseLunge, curl, gluteBridge,
    )

    val byId: Map<String, ExerciseConfig> = all.associateBy { it.id }

    /** Упражнения одной рукой: считается любая рука ([EitherArmCounter]). */
    val eitherArm: Set<String> = setOf("press")
}

/**
 * Упражнение одной рукой, камера спереди (жим): обе руки видны одинаково хорошо, и «липкий» выбор
 * стороны в [SideSelector] мог бы застрять на свободной руке. Поэтому — по автомату на каждую руку:
 * каждому достаётся кадр без локтя и кисти другой руки, так что он видит только свою.
 * Повторы рук складываются; если обе руки засчитали повтор почти одновременно
 * ([CounterThresholds.Press.SIMULTANEOUS_MS]) — это жим двумя руками разом, засчитывается один.
 * Показания (подсказка, глубина, исходное положение) — от руки, которая сейчас работает.
 */
internal class EitherArmCounter(cfg: ExerciseConfig) : RepCounter {
    private class Arm(side: Side, cfg: ExerciseConfig) {
        val counter = PhaseCounter(cfg)
        var seen = 0
        var lastRepMs = Long.MIN_VALUE
        private val other = if (side == Side.LEFT) Side.RIGHT else Side.LEFT
        private val hidden = setOf(other.joint(Limb.ELBOW), other.joint(Limb.WRIST))

        fun only(frame: PoseFrame): PoseFrame =
            if (frame.points.isEmpty()) frame else PoseFrame(frame.timeMs, frame.width, frame.height, frame.points.filterKeys { it !in hidden })
    }

    private val arms = Side.entries.map { Arm(it, cfg) }
    private var count = 0
    private var shown = 0

    override var state: RepState = RepState()
        private set

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        val states = arms.map { it.counter.onFrame(it.only(frame)) }
        arms.forEachIndexed { i, arm ->
            val grown = states[i].count - arm.seen
            arm.seen = states[i].count
            if (grown > 0) {
                val otherArm = arms[1 - i]
                val together = otherArm.lastRepMs != Long.MIN_VALUE && t - otherArm.lastRepMs <= CounterThresholds.Press.SIMULTANEOUS_MS
                count += if (together) grown - 1 else grown
                arm.lastRepMs = t
                shown = i
            }
        }
        if (score(states[1 - shown]) > score(states[shown]) + SWITCH_MARGIN) shown = 1 - shown
        state = states[shown].copy(count = count)
        return state
    }

    /** Насколько рука «в работе»: не видна < в позе < у плеча или в движении (+ глубина). */
    private fun score(s: RepState): Float = when {
        !s.tracking -> 0f
        !s.inPosition -> 1f
        else -> 2f + (if (s.atStart || s.depth > ACTIVE_DEPTH) 1f else 0f) + s.depth
    }

    override fun reset() {
        arms.forEach {
            it.counter.reset()
            it.seen = 0
            it.lastRepMs = Long.MIN_VALUE
        }
        count = 0
        shown = 0
        state = RepState()
    }

    private companion object {
        const val SWITCH_MARGIN = 0.5f
        const val ACTIVE_DEPTH = 0.05f
    }
}
