package com.nextlevellife.app.data

import com.nextlevellife.app.pose.PoseSide

/**
 * Программы мягкой подвижности — ЛФК и йога (docs/exercise-catalog.md, разделы 2, 3 и 5).
 * Это «комплексы» по месту в приложении: прохождение пишется в complex_runs (complex_id = [Routine.id]),
 * шаги — подходами (sets) с exercise_id `lfk_…` / `yoga_…`. Награда мягче силовых ([RoutineMath]),
 * в силу, выносливость и «Сайтаму» программы не идут (см. StatsMath).
 * Всё засчитанное подтверждает камера: удержание — время в правильной позе, повторения — полный цикл.
 */
enum class RoutineKind(val title: String) {
    LFK("ЛФК"),
    YOGA("Йога"),
}

/** Шаг программы: упражнение (удержание или повторения) или отдых/переход без оплаты. */
sealed class RoutineStep {
    /** Название шага для экрана и голоса («Воин II · левая»). */
    abstract val title: String

    /** Сколько шаг занимает по плану, мс (повторения — по [RepsStep.secondsPerRep]). */
    abstract val nominalMs: Long
}

/**
 * Упражнение программы. [exerciseId] — id подхода в дневнике (одинаков для обеих сторон), [name] — без стороны,
 * [side] — рабочая сторона несимметричного упражнения (null — симметричное).
 * Подсказки для показа техники: [guideId] — ключ анимации «гида» (подключается после слияния),
 * [camera] — ракурс и высота телефона, [distance] — расстояние, [tips] — главный ориентир и запрет.
 */
sealed class RoutineExercise : RoutineStep() {
    abstract val exerciseId: String
    abstract val name: String
    abstract val side: PoseSide?
    abstract val guideId: String?
    abstract val distance: String
    abstract val camera: String
    abstract val tips: List<String>

    override val title: String get() = side?.let { "$name · ${it.fem}" } ?: name

    /** Строка про камеру: «Сбоку, телефон 20–40 см от пола · 1,8–2,5 м». */
    val cameraLine: String get() = "$camera · $distance"
}

/**
 * Удержание позы [poseId] (см. PoseHoldConfigs) на [seconds]. [paid] = false — подготовка или отдых
 * в позе (спокойное сидение, отдых лёжа, шавасана): камера подтверждает положение, награды нет,
 * на завершённость программы шаг не влияет.
 */
data class HoldStep(
    override val exerciseId: String,
    override val name: String,
    val poseId: String,
    val seconds: Int,
    override val side: PoseSide? = null,
    val paid: Boolean = true,
    override val guideId: String? = poseId,
    override val distance: String,
    override val camera: String,
    override val tips: List<String> = emptyList(),
) : RoutineExercise() {
    val targetMs: Long get() = seconds * 1000L
    override val nominalMs: Long get() = targetMs
}

/** Повторения движения [moveId] (см. PoseHoldConfigs), [reps] штук, в ритме [secondsPerRep]. */
data class RepsStep(
    override val exerciseId: String,
    override val name: String,
    val moveId: String,
    val reps: Int,
    override val side: PoseSide? = null,
    val secondsPerRep: Int = 6,
    override val guideId: String? = moveId,
    override val distance: String,
    override val camera: String,
    override val tips: List<String> = emptyList(),
) : RoutineExercise() {
    override val nominalMs: Long get() = reps * secondsPerRep * 1000L
}

/** Отдых или переход (смена стороны, перестановка телефона): таймер, без оплаты. [note] — что сделать. */
data class RestStep(
    val seconds: Int,
    override val title: String = "Отдых",
    val note: String = "",
) : RoutineStep() {
    override val nominalMs: Long get() = seconds * 1000L
}

/**
 * Программа: [declaredMinutes] — заявленная длительность (каталог: 9, 10, 12 минут); сумма шагов по плану
 * совпадает с ней. Остановки распознавания могут растянуть занятие.
 */
data class Routine(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: RoutineKind,
    val declaredMinutes: Int,
    val steps: List<RoutineStep>,
) {
    val exercises: List<RoutineExercise> get() = steps.filterIsInstance<RoutineExercise>()

    val nominalMs: Long get() = steps.sumOf { it.nominalMs }

    /**
     * Сколько упражнений показывать на карточке: у ЛФК — разных упражнений (блоков каталога, обе стороны —
     * один блок), у йоги — поз (каждая сторона — отдельная поза, как в каталоге).
     */
    val blocks: Int
        get() = if (kind == RoutineKind.LFK) exercises.map { it.exerciseId }.distinct().size else exercises.size

    /** Шаг [i] и следующий упражнение после него (для «Следующее: …»). */
    fun nextExercise(i: Int): RoutineExercise? = steps.drop(i + 1).filterIsInstance<RoutineExercise>().firstOrNull()
}

object Routines {
    const val LFK_PREFIX = "lfk_"
    const val YOGA_PREFIX = "yoga_"

    /** Группа на главной (каталог, раздел 2). */
    const val GROUP_TITLE = "Мягкая подвижность"
    const val GROUP_NOTE = "ЛФК — после согласования при восстановлении"

    /** Текст перед стартом ЛФК (каталог, «Безопасность и текст предупреждения»). */
    const val LFK_WARNING = "Двигайтесь медленно, в комфортной амплитуде, без задержки дыхания. " +
        "Камера проверяет положение крупных суставов, но не оценивает боль и состояние позвоночника. " +
        "При новой или усиливающейся боли, онемении, слабости или головокружении остановитесь. " +
        "После травмы, операции или при назначенной реабилитации согласуйте упражнения со специалистом."

    /** Короткая пометка: это не лечение. */
    const val NOT_MEDICAL = "Не медицинская программа и не лечение конкретного заболевания."

    /** Перед йогой. */
    const val YOGA_NOTE = "Входи в позу плавно и не углубляй её ради таймера. При боли выйди из позы."

    // ---------- Камера ----------

    private const val SEAT_SIDE = "Сбоку, телефон на высоте 50–90 см"
    private const val STAND_SIDE = "Сбоку, телефон на высоте 50–90 см"
    private const val STAND_FRONT = "Спереди, телефон на высоте 50–90 см"
    private const val FLOOR_SIDE = "Сбоку, телефон 20–40 см от пола"
    private const val NEAR_2_3 = "2–3 м"
    private const val NEAR_25_3 = "2,5–3 м"
    private const val FLOOR_DIST = "1,8–2,5 м"

    private fun near(camera: String, side: PoseSide, what: String) = "$camera, ${side.fem} $what ближе"

    // ---------- ЛФК «Спина и шея» ----------

    /**
     * 8 блоков по минуте + минута подготовки и переходов = 9 минут. Оплачиваемых поворотов и наклонов головы
     * нет: шейное движение камера надёжно не видит.
     */
    val lfkBack = Routine(
        id = "lfk_back_neck",
        title = "Спина и шея",
        subtitle = "Мягкая подвижность спины сидя, на четвереньках и лёжа",
        kind = RoutineKind.LFK,
        declaredMinutes = 9,
        steps = listOf(
            RestStep(30, "Подготовка", "Поставь стул боком к телефону, телефон — на высоте 50–90 см в 2–3 м."),
            HoldStep(
                "lfk_sit_calm", "Спокойное сидение", "sit_upright", 40, paid = false,
                distance = NEAR_2_3, camera = SEAT_SIDE,
                tips = listOf("Стопы на полу, спина прямая, дыши спокойно", "Подготовка: время не оплачивается"),
            ),
            RestStep(20),
            RepsStep(
                "lfk_arm_raise_front", "Подъём рук вперёд сидя", "arm_raise_front", 8,
                distance = NEAR_2_3, camera = SEAT_SIDE,
                tips = listOf("Руки до уровня плеч", "Не поднимай через боль"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_seated_lean", "Наклон прямого корпуса сидя", "seated_lean", 8,
                distance = NEAR_2_3, camera = SEAT_SIDE,
                tips = listOf("Небольшой наклон, спина прямая", "Таз остаётся на стуле"),
            ),
            RestStep(42, "Переход", "Телефон — на пол или низкую подставку (20–40 см), коврик боком к нему в 1,8–2,5 м."),
            RepsStep(
                "lfk_quadruped_rock", "Покачивание назад на четвереньках", "quadruped_rock", 8,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Таз мягко назад к пяткам и обратно", "Кисти и колени остаются на месте"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_quadruped_reach", "Вытягивание руки на четвереньках", "quadruped_reach", 4, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "рука"),
                tips = listOf("Рука вперёд до уровня плеча", "Спина не прогибается"),
            ),
            RestStep(10, "Смена стороны", "Развернись: правая рука ближе к телефону."),
            RepsStep(
                "lfk_quadruped_reach", "Вытягивание руки на четвереньках", "quadruped_reach", 4, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "рука"),
                tips = listOf("Рука вперёд до уровня плеча", "Спина не прогибается"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_glute_bridge", "Мягкий ягодичный мост", "glute_bridge", 8,
                // Свой показ: id «glute_bridge» занят мостом с гирей (у него гиря и другие подсказки).
                guideId = "soft_bridge",
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Подними таз без прогиба", "Не задерживай дыхание"),
            ),
            RestStep(12),
            HoldStep(
                "lfk_knee_to_chest", "Колено к груди лёжа", "knee_to_chest", 20, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "нога"),
                tips = listOf("Подтяни колено руками мягко", "Без рывков и боли"),
            ),
            RestStep(10, "Смена стороны", "Ляг головой в другую сторону: правая нога ближе к телефону."),
            HoldStep(
                "lfk_knee_to_chest", "Колено к груди лёжа", "knee_to_chest", 20, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "нога"),
                tips = listOf("Подтяни колено руками мягко", "Без рывков и боли"),
            ),
            RestStep(20, "Отдых", "Поставь устойчивый стул: голени лягут на сиденье."),
            HoldStep(
                "lfk_rest_chair", "Отдых лёжа, голени на стуле", "rest_chair", 40, paid = false,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Голени на устойчивом стуле, дыши спокойно", "Завершение: время не оплачивается"),
            ),
        ),
    )

    // ---------- ЛФК «Суставы: плечи, таз, колени» ----------

    /**
     * Блоки каталога, сгруппированные по ракурсу (чтобы телефон переставлять один раз): спереди стоя —
     * локти, разведение рук, отведение ноги; сбоку стоя — подъём рук, подъём коленей, неглубокий присед;
     * сидя — разгибание колена; лёжа — сгибание ноги (скольжение пяткой).
     */
    val lfkJoints = Routine(
        id = "lfk_joints",
        title = "Суставы: плечи, таз, колени",
        subtitle = "Подвижность плеч, тазобедренных суставов и коленей",
        kind = RoutineKind.LFK,
        declaredMinutes = 9,
        steps = listOf(
            RestStep(20, "Подготовка", "Телефон на высоте 50–90 см. Встань лицом к нему в 2,5–3 м, рядом поставь стул для опоры."),
            RepsStep(
                "lfk_elbow_flex", "Сгибание и разгибание локтей", "elbow_flex", 8,
                distance = NEAR_25_3, camera = STAND_FRONT,
                tips = listOf("Плечи неподвижны, локти у корпуса", "Без рывков"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_arm_abduction", "Разведение рук до уровня плеч", "arm_abduction", 8,
                distance = NEAR_25_3, camera = STAND_FRONT,
                tips = listOf("Руки в стороны до уровня плеч", "Не поднимай через боль"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_leg_abduction", "Отведение прямой ноги в сторону", "leg_abduction", 4, side = PoseSide.LEFT,
                distance = NEAR_25_3, camera = STAND_FRONT,
                tips = listOf("Держись за спинку стула, нога прямая", "Небольшая амплитуда, без махов"),
            ),
            RestStep(6, "Смена стороны", "Теперь правая нога."),
            RepsStep(
                "lfk_leg_abduction", "Отведение прямой ноги в сторону", "leg_abduction", 4, side = PoseSide.RIGHT,
                distance = NEAR_25_3, camera = STAND_FRONT,
                tips = listOf("Держись за спинку стула, нога прямая", "Небольшая амплитуда, без махов"),
            ),
            RestStep(12, "Переход", "Повернись боком к телефону."),
            RepsStep(
                "lfk_arm_raise_front", "Подъём рук вперёд", "arm_raise_front", 8,
                distance = NEAR_2_3, camera = STAND_SIDE,
                tips = listOf("Руки до уровня плеч", "Поясница не прогибается"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_knee_raise", "Подъём колена стоя", "knee_raise", 4, side = PoseSide.LEFT,
                distance = NEAR_2_3, camera = near(STAND_SIDE, PoseSide.LEFT, "нога"),
                tips = listOf("Держись за опору", "Корпус прямо, колено — до комфортной высоты"),
            ),
            RestStep(6, "Смена стороны", "Развернись: правая нога ближе к телефону."),
            RepsStep(
                "lfk_knee_raise", "Подъём колена стоя", "knee_raise", 4, side = PoseSide.RIGHT,
                distance = NEAR_2_3, camera = near(STAND_SIDE, PoseSide.RIGHT, "нога"),
                tips = listOf("Держись за опору", "Корпус прямо, колено — до комфортной высоты"),
            ),
            RestStep(12),
            RepsStep(
                "lfk_shallow_squat", "Неглубокое приседание", "shallow_squat", 8,
                distance = NEAR_2_3, camera = STAND_SIDE,
                tips = listOf("Чуть согни колени и выпрямись", "Не приседай глубоко"),
            ),
            RestStep(12, "Переход", "Сядь на стул боком к телефону."),
            RepsStep(
                "lfk_knee_extension", "Разгибание колена сидя", "knee_extension", 4, side = PoseSide.LEFT,
                distance = NEAR_2_3, camera = near(SEAT_SIDE, PoseSide.LEFT, "нога"),
                tips = listOf("Бедро на сиденье, выпрями ногу", "Без рывка в конце"),
            ),
            RestStep(6, "Смена стороны", "Развернись: правая нога ближе к телефону."),
            RepsStep(
                "lfk_knee_extension", "Разгибание колена сидя", "knee_extension", 4, side = PoseSide.RIGHT,
                distance = NEAR_2_3, camera = near(SEAT_SIDE, PoseSide.RIGHT, "нога"),
                tips = listOf("Бедро на сиденье, выпрями ногу", "Без рывка в конце"),
            ),
            RestStep(40, "Переход", "Телефон — на пол или низкую подставку (20–40 см). Ляг на спину боком к нему, левая нога ближе."),
            RepsStep(
                "lfk_heel_slide", "Сгибание ноги лёжа", "heel_slide", 4, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "нога"),
                tips = listOf("Пятка скользит к тазу и обратно", "Поясница спокойно на полу"),
            ),
            RestStep(6, "Смена стороны", "Ляг головой в другую сторону: правая нога ближе к телефону."),
            RepsStep(
                "lfk_heel_slide", "Сгибание ноги лёжа", "heel_slide", 4, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "нога"),
                tips = listOf("Пятка скользит к тазу и обратно", "Поясница спокойно на полу"),
            ),
        ),
    )

    // ---------- Йога «Утро, 10 минут» ----------

    private val warriorTips = listOf(
        "Переднее колено согнуто, руки в стороны. Разверни стопу передней ноги — камера стопы не проверяет",
        "Не углубляй позу ради таймера",
    )

    /** 8 минутных блоков (удержание + выход и отдых) и 2 минуты подготовки, переходов и одной перестановки телефона. */
    val yogaMorning = Routine(
        id = "yoga_morning",
        title = "Утро, 10 минут",
        subtitle = "Мягкое пробуждение: стоя, затем на коврике",
        kind = RoutineKind.YOGA,
        declaredMinutes = 10,
        steps = listOf(
            RestStep(45, "Подготовка", "Телефон на высоте 50–90 см. Встань лицом к нему в 2,5–3 м."),
            HoldStep(
                "yoga_mountain", "Гора с поднятыми руками", "mountain", 45,
                distance = NEAR_25_3, camera = STAND_FRONT,
                tips = listOf("Руки вверх, ноги прямые", "Плечи не поднимай к ушам"),
            ),
            RestStep(15, "Отдых", "Повернись боком к телефону."),
            HoldStep(
                "yoga_chair", "Стул, неглубокий вариант", "chair", 30,
                distance = NEAR_2_3, camera = STAND_SIDE,
                tips = listOf("Таз назад, руки вперёд", "Колени не уводи глубоко"),
            ),
            RestStep(45, "Отдых", "Встань лицом к телефону, широкий шаг в стороны."),
            HoldStep(
                "yoga_warrior2", "Воин II", "warrior2", 45, side = PoseSide.LEFT,
                distance = NEAR_25_3, camera = STAND_FRONT, tips = warriorTips,
            ),
            RestStep(15, "Смена стороны", "Выпрями ногу, теперь согни правую."),
            HoldStep(
                "yoga_warrior2", "Воин II", "warrior2", 45, side = PoseSide.RIGHT,
                distance = NEAR_25_3, camera = STAND_FRONT, tips = warriorTips,
            ),
            RestStep(75, "Переход", "Телефон — на пол или низкую подставку (20–40 см), коврик боком к нему в 1,8–2,5 м."),
            HoldStep(
                "yoga_table", "Стол на четвереньках", "table", 45,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Кисти под плечами, колени под тазом", "Спина ровная"),
            ),
            RestStep(20),
            HoldStep(
                "yoga_puppy", "Вытянутый щенок", "puppy", 45,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Таз над коленями, руки вперёд", "Грудь мягко вниз, без боли в плечах"),
            ),
            RestStep(20),
            HoldStep(
                "yoga_sphinx", "Сфинкс", "sphinx", 45,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Локти под плечами, подъём невысокий", "Не прогибай поясницу сильнее"),
            ),
            RestStep(20),
            HoldStep(
                "yoga_child", "Поза ребёнка", "child", 45,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Таз к пяткам, руки вперёд", "Дыши спокойно"),
            ),
        ),
    )

    // ---------- Йога «Вечер, растяжка, 12 минут» ----------

    /** 10 минутных блоков и 2 минуты подготовки и переходов; каждая сторона — отдельная поза. Всё на полу. */
    val yogaEvening = Routine(
        id = "yoga_evening",
        title = "Вечер, растяжка, 12 минут",
        subtitle = "Спокойная растяжка на коврике перед сном",
        kind = RoutineKind.YOGA,
        declaredMinutes = 12,
        steps = listOf(
            RestStep(45, "Подготовка", "Телефон — на пол или низкую подставку (20–40 см), коврик боком к нему в 1,8–2,5 м."),
            HoldStep(
                "yoga_table", "Стол на четвереньках", "table", 40,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Кисти под плечами, колени под тазом", "Спина ровная"),
            ),
            RestStep(30),
            HoldStep(
                "yoga_child", "Поза ребёнка", "child", 40,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Таз к пяткам, руки вперёд", "Если колени не видно — сдвинься в кадре"),
            ),
            RestStep(30, "Отдых", "Левая сторона ближе к телефону."),
            HoldStep(
                "yoga_low_lunge", "Низкий выпад", "low_lunge", 40, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "сторона"),
                tips = listOf("Левая нога впереди, заднее колено на коврике, руки на бедре", "Не дави коленом вперёд через боль"),
            ),
            RestStep(30, "Смена стороны", "Развернись: правая сторона ближе к телефону."),
            HoldStep(
                "yoga_low_lunge", "Низкий выпад", "low_lunge", 40, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "сторона"),
                tips = listOf("Правая нога впереди, заднее колено на коврике, руки на бедре", "Не дави коленом вперёд через боль"),
            ),
            RestStep(30, "Отдых", "Развернись: левая сторона ближе к телефону."),
            HoldStep(
                "yoga_half_split", "Полушпагат с мягким коленом", "half_split", 40, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "сторона"),
                tips = listOf("Левая нога вперёд, колено мягкое; можно опереться на блоки", "Глубину наклона ради зачёта не увеличивай"),
            ),
            RestStep(30, "Смена стороны", "Развернись: правая сторона ближе к телефону."),
            HoldStep(
                "yoga_half_split", "Полушпагат с мягким коленом", "half_split", 40, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "сторона"),
                tips = listOf("Правая нога вперёд, колено мягкое; можно опереться на блоки", "Глубину наклона ради зачёта не увеличивай"),
            ),
            RestStep(30, "Отдых", "Ляг на спину боком к телефону, стопы на полу."),
            HoldStep(
                "yoga_half_bridge", "Невысокий полумост", "half_bridge", 40,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Таз вверх, плечи и стопы на полу", "Без прогиба в пояснице"),
            ),
            RestStep(30, "Отдых", "Лёжа на спине, левая нога ближе к телефону."),
            HoldStep(
                "yoga_knee_to_chest", "Колено к груди лёжа", "knee_to_chest", 40, side = PoseSide.LEFT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.LEFT, "нога"),
                tips = listOf("Левое колено к груди, другая нога согнута", "Тяни мягко"),
            ),
            RestStep(30, "Смена стороны", "Ляг головой в другую сторону: правая нога ближе к телефону."),
            HoldStep(
                "yoga_knee_to_chest", "Колено к груди лёжа", "knee_to_chest", 40, side = PoseSide.RIGHT,
                distance = FLOOR_DIST, camera = near(FLOOR_SIDE, PoseSide.RIGHT, "нога"),
                tips = listOf("Правое колено к груди, другая нога согнута", "Тяни мягко"),
            ),
            RestStep(30),
            HoldStep(
                "yoga_shavasana", "Шавасана — отдых лёжа", "shavasana", 45, paid = false,
                distance = FLOOR_DIST, camera = FLOOR_SIDE,
                tips = listOf("Ноги вытянуты, руки вдоль тела, дыши спокойно", "Время не оплачивается"),
            ),
        ),
    )

    val all: List<Routine> = listOf(lfkBack, lfkJoints, yogaMorning, yogaEvening)

    val ids: Set<String> = all.map { it.id }.toSet()

    fun byId(id: String): Routine? = all.firstOrNull { it.id == id }

    /** Прохождение программы (complex_runs.complex_id). */
    fun isRoutineId(complexId: String): Boolean = complexId in ids

    /** Подход программы ЛФК/йоги (sets.exercise_id): в силу и «Сайтаму» не идёт. */
    fun isRoutineExercise(exerciseId: String): Boolean =
        exerciseId.startsWith(LFK_PREFIX) || exerciseId.startsWith(YOGA_PREFIX)
}
