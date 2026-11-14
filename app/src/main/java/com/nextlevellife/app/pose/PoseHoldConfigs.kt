package com.nextlevellife.app.pose

import com.nextlevellife.app.pose.Of.BOTH
import com.nextlevellife.app.pose.Of.OTHER
import com.nextlevellife.app.pose.Of.WORK
import com.nextlevellife.app.pose.PoseMetric.ANKLE_HIP_DX
import com.nextlevellife.app.pose.PoseMetric.ANKLE_SPREAD
import com.nextlevellife.app.pose.PoseMetric.ARM_RAISE
import com.nextlevellife.app.pose.PoseMetric.ELBOW
import com.nextlevellife.app.pose.PoseMetric.ELBOW_SHOULDER_DX
import com.nextlevellife.app.pose.PoseMetric.HIP_FLEX
import com.nextlevellife.app.pose.PoseMetric.HIP_OVER_KNEE
import com.nextlevellife.app.pose.PoseMetric.KNEE
import com.nextlevellife.app.pose.PoseMetric.KNEE_DROP
import com.nextlevellife.app.pose.PoseMetric.KNEE_HIP_DX
import com.nextlevellife.app.pose.PoseMetric.LEG_TILT
import com.nextlevellife.app.pose.PoseMetric.SHIN_FLAT
import com.nextlevellife.app.pose.PoseMetric.SHOULDER_OVER_ELBOW
import com.nextlevellife.app.pose.PoseMetric.SHOULDER_OVER_HIP
import com.nextlevellife.app.pose.PoseMetric.TORSO_ELEVATION
import com.nextlevellife.app.pose.PoseMetric.TORSO_FLAT
import com.nextlevellife.app.pose.PoseMetric.TORSO_TILT
import com.nextlevellife.app.pose.PoseMetric.WRIST_AHEAD
import com.nextlevellife.app.pose.PoseMetric.WRIST_OVER_NOSE
import com.nextlevellife.app.pose.PoseMetric.WRIST_OVER_SHOULDER
import com.nextlevellife.app.pose.PoseMetric.WRIST_SHOULDER_DX

/**
 * Позы и мягкие движения программ ЛФК и йоги — геометрические условия из docs/exercise-catalog.md
 * (разделы 2 и 3). Диапазоны в условиях — диапазоны ВХОДА; удержание шире на [ANGLE_SLACK] для углов
 * и [RATIO_SLACK] для долей масштаба тела (каталог: «при удержании расширять примерно на 5° и 0,05S»).
 * Всё — первое приближение: пороги настраиваются по записям подходов.
 *
 * Камера не видит повороты шеи, кисти и стопы, давление на суставы и боль: такие элементы в условия
 * не входят — подтверждается только положение крупных суставов и время.
 */
object PoseHoldConfigs {
    /** Допуск удержания для углов, градусы. */
    const val ANGLE_SLACK = 5f

    /** Допуск удержания для высот и расстояний, доли масштаба тела. */
    const val RATIO_SLACK = 0.05f

    private const val INF = Float.POSITIVE_INFINITY

    private fun slack(m: PoseMetric) = if (m.isAngle) ANGLE_SLACK else RATIO_SLACK

    /** Значение в [min]..[max] (вход), удержание — шире на допуск. */
    private fun within(
        m: PoseMetric, of: Of, min: Float, max: Float, hint: String,
        low: String? = null, high: String? = null,
    ): PoseCond {
        val d = slack(m)
        val lo = if (min == -INF) -INF else min - d
        val hi = if (max == INF) INF else max + d
        return PoseCond(m, of, min..max, lo..hi, hint, low, high)
    }

    private fun atLeast(m: PoseMetric, of: Of, min: Float, hint: String) = within(m, of, min, INF, hint)

    private fun atMost(m: PoseMetric, of: Of, max: Float, hint: String) = within(m, of, -INF, max, hint)

    // ---------------------------------------------------------------------------------------------
    // Удержания
    // ---------------------------------------------------------------------------------------------

    private const val LIE_SIDE = "Ляг боком к телефону"
    private const val STAND_FRONT = "Встань лицом к телефону"
    private const val STAND_SIDE = "Встань боком к телефону"

    /** ЛФК: спокойное сидение с опорой стоп (подготовка, без награды). Сбоку. */
    private val sitUpright = PoseShape(
        "sit_upright", sided = false,
        listOf(
            within(HIP_FLEX, WORK, 65f, 125f, "Сядь на стул"),
            atMost(TORSO_TILT, WORK, 15f, "Сядь ровно"),
            within(KNEE, WORK, 80f, 110f, "Стопы на пол, колени под углом"),
        ),
        postureHint = "Сядь на стул боком к телефону",
    )

    /** ЛФК и йога: колено к груди лёжа на спине. Сбоку, согнутая нога ближе к телефону. */
    private val kneeToChest = PoseShape(
        "knee_to_chest", sided = true,
        listOf(
            atMost(TORSO_FLAT, WORK, 15f, "Ляг на спину"),
            within(HIP_FLEX, WORK, 35f, 75f, "Колено к груди", low = "Не тяни так сильно", high = "Колено ближе к груди"),
            within(KNEE, WORK, 40f, 100f, "Согни колено"),
        ),
        postureHint = LIE_SIDE,
        sideHint = { "Подтяни ${it.neut} колено" },
    )

    /** ЛФК: отдых лёжа, голени на устойчивом стуле (завершение, без награды). Сбоку. */
    private val restChair = PoseShape(
        "rest_chair", sided = false,
        listOf(
            atMost(TORSO_FLAT, WORK, 15f, "Ляг на спину"),
            within(HIP_FLEX, WORK, 70f, 120f, "Голени на стул"),
            within(KNEE, WORK, 80f, 110f, "Голени на стул"),
            atMost(SHIN_FLAT, WORK, 20f, "Голени на стул"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Гора с поднятыми руками. Спереди, 2,5–3 м: обе руки и обе ноги. */
    private val mountain = PoseShape(
        "mountain", sided = false,
        listOf(
            atLeast(KNEE, BOTH, 160f, "Выпрями ноги"),
            atMost(TORSO_TILT, WORK, 12f, "Стой прямо"),
            atLeast(ARM_RAISE, BOTH, 155f, "Руки вверх"),
            atLeast(ELBOW, BOTH, 155f, "Выпрями руки"),
            atLeast(WRIST_OVER_NOSE, BOTH, 0.3f, "Руки выше головы"),
        ),
        postureHint = STAND_FRONT,
    )

    /** Йога: Стул, неглубокий вариант, руки вперёд. Сбоку, 2–3 м. */
    private val chair = PoseShape(
        "chair", sided = false,
        listOf(
            within(KNEE, WORK, 120f, 145f, "Согни колени", low = "Не так глубоко", high = "Согни колени"),
            within(HIP_FLEX, WORK, 110f, 150f, "Таз назад", low = "Корпус выше", high = "Таз назад"),
            within(TORSO_TILT, WORK, 10f, 35f, "Чуть наклонись вперёд", low = "Чуть наклонись вперёд", high = "Корпус выше"),
            within(ARM_RAISE, WORK, 70f, 110f, "Руки вперёд", low = "Руки выше", high = "Руки ниже"),
            atLeast(ELBOW, WORK, 150f, "Выпрями руки"),
        ),
        postureHint = STAND_SIDE,
    )

    /** Йога: Воин II, рабочая (согнутая) нога — сторона шага. Спереди, 2,5–3 м. Стопы камера не проверяет. */
    private val warrior2 = PoseShape(
        "warrior2", sided = true,
        listOf(
            atLeast(ANKLE_SPREAD, WORK, 1.0f, "Шире шаг"),
            within(KNEE, WORK, 90f, 120f, "Согни колено", low = "Не так глубоко", high = "Согни колено"),
            atLeast(KNEE, OTHER, 155f, "Задняя нога прямая"),
            atMost(TORSO_TILT, WORK, 15f, "Корпус прямо"),
            within(ARM_RAISE, BOTH, 75f, 105f, "Руки в стороны", low = "Руки выше", high = "Руки ниже"),
            atLeast(ELBOW, BOTH, 150f, "Выпрями руки"),
        ),
        postureHint = STAND_FRONT,
        sideHint = { "Согни ${it.femAcc} ногу" },
    )

    /** Йога: Стол на четвереньках. Сбоку, 1,8–2,5 м. */
    private val table = PoseShape(
        "table", sided = false,
        listOf(
            atMost(TORSO_FLAT, WORK, 15f, "Спина горизонтально"),
            within(HIP_FLEX, WORK, 75f, 110f, "Колени под тазом"),
            within(KNEE, WORK, 75f, 110f, "Колени под тазом"),
            atLeast(ELBOW, WORK, 155f, "Выпрями руки"),
            atMost(WRIST_SHOULDER_DX, WORK, 0.25f, "Кисти под плечами"),
            atMost(KNEE_HIP_DX, WORK, 0.25f, "Колени под тазом"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Вытянутый щенок, мягкий вариант. Сбоку. */
    private val puppy = PoseShape(
        "puppy", sided = false,
        listOf(
            atMost(KNEE_HIP_DX, WORK, 0.3f, "Таз над коленями"),
            within(KNEE, WORK, 70f, 110f, "Таз над коленями"),
            atMost(SHOULDER_OVER_HIP, WORK, -0.15f, "Грудь ниже"),
            atLeast(ARM_RAISE, WORK, 145f, "Руки вперёд"),
            atLeast(ELBOW, WORK, 150f, "Выпрями руки"),
            atLeast(WRIST_AHEAD, WORK, 0.3f, "Кисти дальше вперёд"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Сфинкс, невысокий подъём. Сбоку, лёжа на животе. */
    private val sphinx = PoseShape(
        "sphinx", sided = false,
        listOf(
            atLeast(KNEE, WORK, 160f, "Выпрями ноги"),
            within(TORSO_ELEVATION, WORK, 15f, 40f, "Поднимись на предплечья", low = "Поднимись на предплечья", high = "Не так высоко"),
            within(ELBOW, WORK, 70f, 110f, "Предплечья на пол"),
            atMost(ELBOW_SHOULDER_DX, WORK, 0.3f, "Локти под плечи"),
            atLeast(SHOULDER_OVER_ELBOW, WORK, 0.2f, "Поднимись на предплечья"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Поза ребёнка, руки вперёд. Сбоку: нужны таз, колено, плечо и рабочая рука. */
    private val child = PoseShape(
        "child", sided = false,
        listOf(
            within(HIP_FLEX, WORK, 20f, 65f, "Таз к пяткам"),
            within(KNEE, WORK, 25f, 80f, "Таз к пяткам"),
            atMost(SHOULDER_OVER_HIP, WORK, 0f, "Грудь ниже"),
            atLeast(ELBOW, WORK, 145f, "Выпрями руки"),
            atLeast(WRIST_AHEAD, WORK, 0.3f, "Руки вперёд"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Низкий выпад, рабочая нога — передняя. Сбоку, рабочая сторона ближе. */
    private val lowLunge = PoseShape(
        "low_lunge", sided = true,
        listOf(
            atLeast(ANKLE_SPREAD, WORK, 0.7f, "Шире шаг"),
            atLeast(HIP_OVER_KNEE, OTHER, 0.4f, "Заднее колено на пол"),
            atLeast(KNEE_DROP, WORK, 0.25f, "Заднее колено на пол"),
            within(KNEE, WORK, 90f, 120f, "Переднее колено согнуто", low = "Не так глубоко", high = "Переднее колено согнуто"),
            within(KNEE, OTHER, 80f, 120f, "Заднее колено согнуто"),
            atMost(TORSO_TILT, WORK, 25f, "Корпус выше"),
        ),
        postureHint = LIE_SIDE,
        sideHint = { "Вперёд — ${it.fem} нога" },
    )

    /** Йога: Полушпагат с мягким коленом, рабочая нога — передняя. Сбоку. */
    private val halfSplit = PoseShape(
        "half_split", sided = true,
        listOf(
            within(KNEE, WORK, 150f, 175f, "Передняя нога прямее", low = "Передняя нога прямее", high = "Колено чуть мягче"),
            within(KNEE, OTHER, 75f, 115f, "Заднее колено согнуто"),
            atMost(KNEE_HIP_DX, OTHER, 0.35f, "Таз над задним коленом"),
            atLeast(ANKLE_HIP_DX, WORK, 0.6f, "Переднюю ногу вперёд"),
            within(HIP_FLEX, WORK, 60f, 115f, "Корпус над бедром", low = "Не наклоняйся глубже", high = "Чуть наклонись вперёд"),
        ),
        postureHint = LIE_SIDE,
        sideHint = { "Вперёд — ${it.fem} нога" },
    )

    /** Йога: Невысокий полумост. Сбоку, лёжа на спине. */
    private val halfBridge = PoseShape(
        "half_bridge", sided = false,
        listOf(
            atMost(SHOULDER_OVER_HIP, WORK, -0.15f, "Подними таз"),
            within(HIP_FLEX, WORK, 150f, 175f, "Подними таз", low = "Подними таз", high = "Не прогибайся"),
            within(KNEE, WORK, 70f, 115f, "Стопы ближе к тазу"),
        ),
        postureHint = LIE_SIDE,
    )

    /** Йога: Шавасана — отдых лёжа (без награды). Сбоку: дальнюю ногу сбоку надёжно не видно — по ближней. */
    private val shavasana = PoseShape(
        "shavasana", sided = false,
        listOf(
            atMost(TORSO_FLAT, WORK, 15f, "Ляг на спину"),
            atLeast(KNEE, WORK, 155f, "Вытяни ноги"),
            atMost(ARM_RAISE, WORK, 35f, "Руки вдоль тела"),
            atLeast(ELBOW, WORK, 145f, "Руки вдоль тела"),
        ),
        postureHint = LIE_SIDE,
    )

    internal val holds: Map<String, PoseShape> = listOf(
        sitUpright, kneeToChest, restChair,
        mountain, chair, warrior2, table, puppy, sphinx, child,
        lowLunge, halfSplit, halfBridge, shavasana,
    ).associateBy { it.id }

    // ---------------------------------------------------------------------------------------------
    // Мягкие повторения ЛФК
    // ---------------------------------------------------------------------------------------------

    /** Подъём рук вперёд (сидя или стоя). Сбоку: ближняя рука. Верх — до уровня плеч. */
    private val armRaiseFront = PoseMove(
        "arm_raise_front", sided = false,
        start = listOf(atMost(ARM_RAISE, WORK, 25f, "Опусти руки")),
        peak = listOf(within(ARM_RAISE, WORK, 70f, 100f, "Руки до уровня плеч"), atLeast(ELBOW, WORK, 145f, "Выпрями руки")),
        always = listOf(atMost(TORSO_TILT, WORK, 15f, "Корпус прямо")),
        postureHint = "Повернись боком к телефону",
        amplitudeHint = "Руки до уровня плеч",
    )

    /** Небольшой наклон прямого корпуса сидя. Сбоку. */
    private val seatedLean = PoseMove(
        "seated_lean", sided = false,
        start = listOf(atMost(TORSO_TILT, WORK, 10f, "Сядь ровно")),
        peak = listOf(within(TORSO_TILT, WORK, 20f, 35f, "Наклонись чуть дальше")),
        always = listOf(atMost(HIP_FLEX, WORK, 130f, "Сядь на стул"), within(KNEE, WORK, 70f, 120f, "Стопы на пол")),
        postureHint = "Сядь на стул боком к телефону",
        amplitudeHint = "Наклонись чуть дальше",
    )

    /** Покачивание назад на четвереньках: таз уходит к пяткам и возвращается. Сбоку. */
    private val quadrupedRock = PoseMove(
        "quadruped_rock", sided = false,
        start = listOf(within(HIP_FLEX, WORK, 80f, 110f, "Таз над коленями")),
        peak = listOf(within(HIP_FLEX, WORK, 45f, 70f, "Таз чуть дальше назад")),
        always = listOf(
            atMost(TORSO_FLAT, WORK, 40f, "Встань на четвереньки"),
            atMost(KNEE, WORK, 120f, "Встань на четвереньки"),
            atLeast(ELBOW, WORK, 150f, "Руки прямые"),
        ),
        postureHint = LIE_SIDE,
        amplitudeHint = "Таз чуть дальше назад",
    )

    /** Вытягивание одной руки на четвереньках. Сбоку, рабочая рука ближе. */
    private val quadrupedReach = PoseMove(
        "quadruped_reach", sided = true,
        start = listOf(atMost(WRIST_OVER_SHOULDER, WORK, -0.5f, "Кисть под плечо")),
        peak = listOf(
            atLeast(ELBOW, WORK, 150f, "Выпрями руку"),
            within(WRIST_OVER_SHOULDER, WORK, -0.2f, 0.2f, "Рука на уровне плеча", low = "Рука выше", high = "Рука ниже"),
            atLeast(WRIST_AHEAD, WORK, 0.5f, "Рука вперёд"),
        ),
        always = listOf(atMost(TORSO_FLAT, WORK, 20f, "Спина горизонтально"), atMost(KNEE, WORK, 120f, "Встань на четвереньки")),
        postureHint = LIE_SIDE,
        amplitudeHint = "Вытяни руку вперёд до уровня плеча",
        sideHint = { "Вытягивай ${it.femAcc} руку" },
    )

    /** Мягкий ягодичный мост без веса. Сбоку, лёжа на спине. */
    private val gluteBridge = PoseMove(
        "glute_bridge", sided = false,
        start = listOf(atLeast(SHOULDER_OVER_HIP, WORK, -0.07f, "Опусти таз")),
        peak = listOf(
            atMost(SHOULDER_OVER_HIP, WORK, -0.15f, "Подними таз чуть выше"),
            within(HIP_FLEX, WORK, 150f, 175f, "Подними таз чуть выше", high = "Не прогибайся"),
        ),
        always = listOf(atMost(TORSO_FLAT, WORK, 40f, "Ляг на спину"), within(KNEE, WORK, 55f, 125f, "Согни ноги, стопы на пол")),
        postureHint = LIE_SIDE,
        amplitudeHint = "Подними таз чуть выше",
    )

    /** Сгибание и разгибание локтей. Спереди: обе руки. */
    private val elbowFlex = PoseMove(
        "elbow_flex", sided = false,
        start = listOf(atLeast(ELBOW, BOTH, 150f, "Выпрями руки")),
        peak = listOf(within(ELBOW, BOTH, 60f, 100f, "Согни локти сильнее")),
        always = listOf(atMost(TORSO_TILT, WORK, 15f, "Корпус прямо")),
        postureHint = STAND_FRONT,
        amplitudeHint = "Согни локти сильнее",
    )

    /** Разведение рук до уровня плеч. Спереди: обе руки, запястья расходятся в стороны. */
    private val armAbduction = PoseMove(
        "arm_abduction", sided = false,
        start = listOf(atMost(ARM_RAISE, BOTH, 25f, "Опусти руки")),
        peak = listOf(
            within(ARM_RAISE, BOTH, 70f, 100f, "Руки до уровня плеч"),
            atLeast(ELBOW, BOTH, 145f, "Выпрями руки"),
            atLeast(WRIST_SHOULDER_DX, BOTH, 0.6f, "Руки в стороны"),
        ),
        always = listOf(atMost(TORSO_TILT, WORK, 15f, "Корпус прямо")),
        postureHint = STAND_FRONT,
        amplitudeHint = "Руки в стороны до уровня плеч",
    )

    /** Разгибание колена сидя. Сбоку, рабочая нога ближе; бедро остаётся на сиденье. */
    private val kneeExtension = PoseMove(
        "knee_extension", sided = true,
        start = listOf(within(KNEE, WORK, 80f, 110f, "Согни колено")),
        peak = listOf(atLeast(KNEE, WORK, 150f, "Выпрями ногу чуть больше")),
        always = listOf(within(HIP_FLEX, WORK, 60f, 125f, "Бедро на сиденье"), atMost(TORSO_TILT, WORK, 25f, "Сядь ровно")),
        postureHint = "Сядь на стул боком к телефону",
        amplitudeHint = "Выпрями ногу чуть больше",
        sideHint = { "Разгибай ${it.neut} колено" },
    )

    /** Сгибание ноги лёжа (скольжение пяткой): контакт пятки с полом камера не видит. Сбоку. */
    private val heelSlide = PoseMove(
        "heel_slide", sided = true,
        start = listOf(atLeast(KNEE, WORK, 150f, "Выпрями ногу")),
        peak = listOf(within(KNEE, WORK, 70f, 110f, "Подтяни пятку ближе")),
        always = listOf(atMost(TORSO_FLAT, WORK, 15f, "Ляг на спину")),
        postureHint = LIE_SIDE,
        amplitudeHint = "Подтяни пятку ближе",
        sideHint = { "Сгибай ${it.femAcc} ногу" },
    )

    /** Поочерёдный подъём коленей стоя (держись за опору). Сбоку, рабочая нога ближе. */
    private val kneeRaise = PoseMove(
        "knee_raise", sided = true,
        start = listOf(atLeast(HIP_FLEX, WORK, 155f, "Опусти ногу")),
        peak = listOf(within(HIP_FLEX, WORK, 80f, 115f, "Колено чуть выше")),
        always = listOf(atLeast(KNEE, OTHER, 150f, "Опорная нога прямая"), atMost(TORSO_TILT, WORK, 15f, "Корпус прямо")),
        postureHint = STAND_SIDE,
        amplitudeHint = "Колено чуть выше",
        sideHint = { "Поднимай ${it.neut} колено" },
    )

    /** Отведение прямой ноги в сторону (держись за опору). Спереди. */
    private val legAbduction = PoseMove(
        "leg_abduction", sided = true,
        start = listOf(atMost(LEG_TILT, WORK, 7f, "Поставь ногу")),
        peak = listOf(within(LEG_TILT, WORK, 15f, 25f, "Отведи ногу чуть дальше")),
        always = listOf(
            atLeast(KNEE, WORK, 150f, "Нога прямая"),
            atLeast(KNEE, OTHER, 150f, "Опорная нога прямая"),
            atMost(TORSO_TILT, WORK, 12f, "Корпус прямо"),
        ),
        postureHint = STAND_FRONT,
        amplitudeHint = "Отведи ногу чуть дальше",
        sideHint = { "Отводи ${it.femAcc} ногу" },
    )

    /** Неглубокое приседание. Сбоку. */
    private val shallowSquat = PoseMove(
        "shallow_squat", sided = false,
        start = listOf(atLeast(KNEE, WORK, 160f, "Выпрямись")),
        peak = listOf(within(KNEE, WORK, 135f, 150f, "Чуть глубже")),
        always = listOf(atMost(TORSO_TILT, WORK, 30f, "Корпус прямее")),
        postureHint = STAND_SIDE,
        amplitudeHint = "Чуть глубже",
    )

    internal val moves: Map<String, PoseMove> = listOf(
        armRaiseFront, seatedLean, quadrupedRock, quadrupedReach, gluteBridge,
        elbowFlex, armAbduction, kneeExtension, heelSlide, kneeRaise, legAbduction, shallowSquat,
    ).associateBy { it.id }

    // ---------------------------------------------------------------------------------------------
    // Для экранов и каталога программ
    // ---------------------------------------------------------------------------------------------

    /** Счётчик удержания позы [poseId] на [targetMs]; null — позы нет. [side] — у несимметричной позы. */
    fun holdCounter(poseId: String, side: PoseSide?, targetMs: Long): PoseHoldCounter? =
        holds[poseId]?.let { PoseHoldCounter(it, side, targetMs) }

    /** Счётчик повторений движения [moveId] (не больше [target]); null — движения нет. */
    fun repCounter(moveId: String, side: PoseSide?, target: Int = Int.MAX_VALUE): PoseRepCounter? =
        moves[moveId]?.let { PoseRepCounter(it, side, target) }

    /** Поза или движение несимметричны (нужна сторона); null — такого нет. */
    fun isSided(id: String): Boolean? = holds[id]?.sided ?: moves[id]?.sided

    fun hasHold(id: String): Boolean = id in holds

    fun hasMove(id: String): Boolean = id in moves

    /** Подсказка, когда человека не видно (что сделать, чтобы попасть в кадр). */
    fun postureHint(id: String): String? = holds[id]?.postureHint ?: moves[id]?.postureHint
}
