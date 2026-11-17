package com.nextlevellife.app.guide

/**
 * Показ техники: фигурка-«человечек», ключевые кадры и тексты про камеру. Чистый Kotlin без Android —
 * данные и интерполяция проверяются тестами, рисует их [com.nextlevellife.app.ui.GuideFigure].
 *
 * Координаты — в одной общей квадратной сцене 0…1 (начало слева сверху, y вниз), чтобы фигурка
 * не меняла масштаб между кадрами. Пол — линия [GuideScene.FLOOR]. На экране сцена вписывается
 * по меньшей стороне и стоит по центру, линия пола тянется на всю ширину.
 */

/**
 * Суставы фигурки. Стороны — анатомические (правая рука человека — RIGHT).
 * Вид сбоку: фигурка смотрит вправо, ближняя к зрителю сторона — правая (рисуется ярче и поверх).
 * Вид спереди: фигурка смотрит на зрителя, её правая сторона — слева на экране.
 */
enum class GuideJoint {
    HEAD, NECK, PELVIS,
    R_SHOULDER, L_SHOULDER,
    R_ELBOW, L_ELBOW,
    R_WRIST, L_WRIST,
    R_HIP, L_HIP,
    R_KNEE, L_KNEE,
    R_ANKLE, L_ANKLE,
}

/** Точка сцены. */
data class Pt(val x: Float, val y: Float)

/** Поза: координаты всех суставов [GuideJoint]. */
data class GuidePose(val points: Map<GuideJoint, Pt>) {
    operator fun get(j: GuideJoint): Pt = points.getValue(j)
}

/**
 * Ключевой кадр. [moveMs] — плавный переход В этот кадр из предыдущего (у первого — из последнего,
 * когда показ зациклен), [holdMs] — остановка в нём.
 */
data class GuideFrame(val pose: GuidePose, val moveMs: Int, val holdMs: Int)

/** Ракурс рисунка. */
enum class GuideView { SIDE, FRONT }

/** Где гиря. */
enum class BellGrip {
    /** Между кистями (обе руки держат одну гирю). */
    HANDS,

    /** В правой (на виде сбоку — ближней) кисти. */
    RIGHT_HAND,

    /** Лежит на тазу (ягодичный мост). */
    PELVIS,
}

/** Как висит гиря относительно кисти. */
enum class BellHang {
    /** Корпус под ручкой (держат у груди, над головой, внизу на прямых руках). */
    GRAVITY,

    /** Корпус продолжает предплечье (мах: гирю несёт по дуге). */
    ALONG_FOREARM,
}

data class Bell(val grip: BellGrip, val hang: BellHang = BellHang.GRAVITY)

/**
 * Реквизит в сцене. Предмет на рисунке не значит, что камера его распознаёт у пользователя.
 * [mat] — коврик под фигуркой; [bar] — перекладина на уровне кистей первого кадра (пол тогда не рисуется).
 */
data class GuideProps(
    val bell: Bell? = null,
    val mat: Boolean = false,
    val bar: Boolean = false,
    val chair: Chair? = null,
    val barbell: Barbell? = null,
    val dumbbells: Dumbbells? = null,
    val bench: Bench? = null,
    val cableMachine: CableMachine? = null,
    val legMachine: LegMachine? = null,
    val assistedMachine: AssistedMachine? = null,
    val parallelBars: ParallelBars? = null,
)

/**
 * Параллельные брусья, вид сбоку: жерди идут вдоль взгляда человека, ближняя — от [x0] до [x1],
 * её ось на высоте [y] (кисти лежат на ней сверху), стойки — до пола. Дальняя жердь на той же высоте
 * за телом, темнее. Пол рисуется: брусья стоят на нём, а стопы висят.
 */
data class ParallelBars(val x0: Float, val x1: Float, val y: Float)

/** Открытый тренажёр ног: ось рычага у колена и мягкий валик у лодыжки. */
data class LegMachine(val pivot: Pt, val rollerOffset: Pt = Pt(0f, 0f))

/** Гравитрон: подвижная платформа следует за коленями, трос идёт к противовесу. */
data class AssistedMachine(val towerX: Float = 0.84f, val topY: Float = 0.09f)

/** Опора грифа: кисти для тяг/жимов или плечи для приседа. */
enum class BarbellGrip { HANDS, SHOULDERS, FRONT_RACK }

/**
 * Гриф с блинами. [offset] задаёт положение относительно середины кистей или плеч.
 * [halfLength] — видимая половина грифа с учётом проекции выбранного ракурса.
 */
data class Barbell(
    val grip: BarbellGrip = BarbellGrip.HANDS,
    val offset: Pt = Pt(0f, 0f),
    val halfLength: Float = 0.22f,
    val plateRadius: Float = 0.06f,
)

enum class DumbbellGrip { EACH_HAND, RIGHT_HAND, LEFT_HAND, BOTH_HANDS, GOBLET }

/**
 * Гантели поворачиваются вместе с предплечьем, середина рукояти остаётся в кисти.
 * [DumbbellGrip.GOBLET] — одна вертикальная гантель, обе кисти поддерживают верхний диск.
 */
data class Dumbbells(val grip: DumbbellGrip = DumbbellGrip.EACH_HAND)

/**
 * Мягкая опора скамьи от таза [hip] к голове [head]. Разные y задают наклон.
 * [seatEnd] добавляет отдельное горизонтальное сиденье от [hip] для наклонного/сидячего жима.
 */
data class Bench(val hip: Pt, val head: Pt, val seatEnd: Pt? = null)

enum class CableGrip { HANDS, RIGHT_HAND, LEFT_HAND }

/**
 * Схема блочного тренажёра: стойка [base]–[top], блок [pulley], трос к кистям и сиденье.
 * [cableVia] — неподвижные промежуточные блоки между верхом стойки и рабочим блоком.
 * Низкий [pulley] подходит горизонтальной тяге, высокий — верхней тяге.
 */
data class CableMachine(
    val base: Pt,
    val top: Pt,
    val pulley: Pt,
    val seat: Chair? = null,
    val grip: CableGrip = CableGrip.HANDS,
    val cableVia: List<Pt> = emptyList(),
)

/**
 * Стул (вид сбоку): сиденье от [x0] до [x1] на высоте [seatY], ножки до пола, спинка над концом
 * сиденья — слева ([backLeft]) или справа.
 */
data class Chair(val x0: Float, val x1: Float, val seatY: Float, val backLeft: Boolean = true) {
    companion object {
        /** Высота спинки над сиденьем. */
        const val BACK = 0.2f
    }
}

/** Что подсвечивать акцентным цветом: рабочая часть тела. */
enum class GuideHighlight {
    ARMS,

    /** Только правая (на виде сбоку — ближняя) рука: упражнения одной рукой. */
    WORKING_ARM,
    LEGS,
    TORSO,
}

/**
 * Показ техники одного упражнения.
 *
 * @param view ракурс рисунка (не путать с ракурсом камеры [angle]).
 * @param frames ключевые кадры, не меньше двух; показ идёт по кругу (если [loop]).
 * @param anchor сустав-опора: при переходе между кадрами он движется по прямой, остальное тело —
 *   поворотом костей вокруг него (длины костей сохраняются). Кисти и стопы, стоящие в двух соседних
 *   кадрах на одном месте, при переходе не скользят, пока вторичная опора достижима без растяжения
 *   костей; при конфликте сохраняются длины и основная опора (см. [GuideMath.lerp]).
 * @param distance расстояние до телефона: «2–3 м».
 * @param angle ракурс камеры коротко, со строчной: «сбоку», «спереди».
 * @param camera как поставить телефон и что должно быть в кадре — одна фраза без точки в конце.
 * @param focus главный акцент техники — короткая фраза над анимацией.
 * @param tips 2–4 коротких подсказки: что важно для счёта и техники (без точки в конце).
 * @param mistakes 1–2 случая, когда повтор не засчитается (без точки в конце).
 */
data class ExerciseGuide(
    val exerciseId: String,
    val view: GuideView,
    val frames: List<GuideFrame>,
    val props: GuideProps = GuideProps(),
    val highlight: Set<GuideHighlight> = emptySet(),
    val anchor: GuideJoint = GuideJoint.R_ANKLE,
    val loop: Boolean = true,
    val distance: String,
    val angle: String,
    val camera: String,
    val focus: String,
    val tips: List<String>,
    val mistakes: List<String>,
    /** Заголовок карточки, если [exerciseId] — не упражнение из каталога (позы и движения программ). */
    val title: String? = null,
) {
    /** Строка для карточки: «Камера: 2–3 м · сбоку». */
    val cameraLine: String get() = "Камера: $distance · $angle"
}

/** Размеры сцены и «костей» фигурки (в долях стороны сцены). */
object GuideScene {
    /** Линия пола. */
    const val FLOOR = 0.9f

    /** Высота лодыжки над полом, когда стопа стоит на полу. */
    const val ANKLE_LIFT = 0.025f

    /** Лодыжка стоящего человека. */
    const val ANKLE_Y = FLOOR - ANKLE_LIFT

    const val SHIN = 0.19f
    const val THIGH = 0.2f

    /** Таз → шея. */
    const val TORSO = 0.21f

    /** Плечи ниже шеи на столько (вдоль корпуса). */
    const val SHOULDER_DROP = 0.025f

    /** Шея → центр головы. */
    const val HEAD = 0.07f
    const val HEAD_RADIUS = 0.042f
    const val UPPER_ARM = 0.135f
    const val FOREARM = 0.125f

    /** Вид спереди: половина ширины плеч и таза. */
    const val SHOULDER_HALF = 0.06f
    const val HIP_HALF = 0.035f

    /** Стопа (рисуется от лодыжки). */
    const val FOOT = 0.05f

    /** Гиря: радиус корпуса. */
    const val BELL_RADIUS = 0.04f

    /** Кости фигурки (дерево с корнем в любом суставе): по ним рисуется и интерполируется поза. */
    val BONES: List<Pair<GuideJoint, GuideJoint>> = listOf(
        GuideJoint.HEAD to GuideJoint.NECK,
        GuideJoint.NECK to GuideJoint.PELVIS,
        GuideJoint.NECK to GuideJoint.R_SHOULDER,
        GuideJoint.NECK to GuideJoint.L_SHOULDER,
        GuideJoint.R_SHOULDER to GuideJoint.R_ELBOW,
        GuideJoint.R_ELBOW to GuideJoint.R_WRIST,
        GuideJoint.L_SHOULDER to GuideJoint.L_ELBOW,
        GuideJoint.L_ELBOW to GuideJoint.L_WRIST,
        GuideJoint.PELVIS to GuideJoint.R_HIP,
        GuideJoint.PELVIS to GuideJoint.L_HIP,
        GuideJoint.R_HIP to GuideJoint.R_KNEE,
        GuideJoint.R_KNEE to GuideJoint.R_ANKLE,
        GuideJoint.L_HIP to GuideJoint.L_KNEE,
        GuideJoint.L_KNEE to GuideJoint.L_ANKLE,
    )
}
