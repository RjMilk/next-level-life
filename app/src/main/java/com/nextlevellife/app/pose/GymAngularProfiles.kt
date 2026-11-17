package com.nextlevellife.app.pose

/** Конкретные исполнения класса Б из docs/gym-catalog-design.md, разделы 3–5.
 * Диапазоны уточняют проектные интервалы; класс А и старые штанговые профили не меняются.
 * Одна выбранная рабочая сторона = один цикл. Разведения требуют обе стороны синхронно.
 */
internal object GymAngularProfiles {
    private fun posture(signal: Feature, min: Float, max: Float, holdMin: Float = min, holdMax: Float = max) =
        AngularPosition(signal, min, max, holdMin, holdMax)

    private val upright = posture(Feature.TORSO_TILT, 0f, 20f, 0f, 25f)
    private val seated = posture(Feature.HIP_FLEXION, 65f, 120f, 55f, 130f)
    private val bench = posture(Feature.TORSO_TILT, 75f, 105f, 70f, 110f)
    private val incline = posture(Feature.TORSO_TILT, 40f, 65f, 35f, 70f)
    private val standing = posture(Feature.HIP_OVER_ANKLE, 0.7f, 2.2f, 0.35f, 2.2f)

    private fun profile(
        id: String, signal: Feature, start: Float, far: Float,
        positions: List<AngularPosition>, minMs: Long = 2_000L,
        side: AngularSide = AngularSide.BEST_VISIBLE,
        startHint: String, farHint: String, postureHint: String,
        farChecks: List<Cond> = emptyList(),
    ) = AngularProfile(
        id = id, signal = signal, startThreshold = start, farThreshold = far,
        positions = positions, minCycleMs = minMs, side = side, farChecks = farChecks,
        postureHint = postureHint, startHint = startHint, farHint = farHint, finishHint = "Вернись в исходное положение",
    )

    private fun benchPress(id: String, inclined: Boolean) = profile(
        id, Feature.ELBOW, if (inclined) 105f else 100f, 155f,
        listOf(if (inclined) incline else bench),
        startHint = "Опусти снаряд к груди", farHint = "Выпрями руки",
        postureHint = "Ляг на скамью: ближняя рука полностью открыта",
        farChecks = listOf(Feature.WRIST_OVER_SHOULDER.above(0.15f)),
    )

    val romanianDeadlift = profile(
        "bb_romanian_deadlift", Feature.HIP_FLEXION, 165f, 115f,
        listOf(posture(Feature.KNEE, 145f, 180f, 140f, 180f), standing), 2_500L,
        startHint = "Выпрямись", farHint = "Таз назад",
        postureHint = "Встань боком: плечо, таз и колено открыты",
    )
    val barbellBench = benchPress("bb_bench_press", false)
    val barbellIncline = benchPress("bb_incline_press", true)
    val dumbbellBench = benchPress("db_bench_press", false)
    val dumbbellIncline = benchPress("db_incline_press", true)
    val seatedPress = profile(
        "db_seated_press", Feature.ELBOW, 110f, 150f, listOf(upright, seated),
        startHint = "Гантели к плечам", farHint = "Кисти выше головы",
        postureHint = "Сядь прямо, плечи и таз видны",
        farChecks = listOf(Feature.WRIST_OVER_NOSE.above(0.35f)),
    )
    val lateralRaise = profile(
        "db_lateral_raise", Feature.SHOULDER_ANGLE, 25f, 70f,
        listOf(upright, standing, posture(Feature.ELBOW, 145f, 180f, 140f, 180f)),
        side = AngularSide.BOTH, startHint = "Опусти руки", farHint = "Разведи руки до плеч",
        postureHint = "Встань лицом: обе руки и ноги в кадре",
        farChecks = listOf(Feature.SHOULDER_ANGLE.below(105f), Feature.WRIST_SPREAD.above(1.5f)),
    )
    val lunge = profile(
        "db_lunge", Feature.KNEE, 155f, 115f,
        listOf(upright, standing, posture(Feature.FOOT_SPREAD, 0.65f, 2.5f, 0.55f, 2.7f)), 2_500L,
        startHint = "Выпрями рабочую ногу в разножке", farHint = "Опустись в выпад",
        postureHint = "Рабочая нога ближе, обе стопы видны и разнесены",
    )
    val bulgarianSplitSquat = profile(
        "db_bulgarian_split_squat", Feature.KNEE, 155f, 105f,
        listOf(upright, standing, posture(Feature.FOOT_SPREAD, 0.65f, 2.5f, 0.55f, 2.7f),
            posture(Feature.REAR_ANKLE_RAISE, 0.25f, 1.4f, 0.2f, 1.5f)), 2_500L,
        startHint = "Выпрями переднюю ногу", farHint = "Опустись на передней ноге",
        postureHint = "Передняя нога ближе, задняя стопа на скамье и видна",
    )
    val latPulldown = profile(
        "machine_lat_pulldown", Feature.ELBOW, 150f, 100f, listOf(upright, seated),
        startHint = "Выпрями руки вверх", farHint = "Тяни рукоять к плечам",
        postureHint = "Сядь прямо, плечо, локоть и кисть открыты",
        farChecks = listOf(Feature.WRIST_OVER_SHOULDER.below(0.35f), Feature.WRIST_OVER_SHOULDER.above(-0.35f)),
    )
    val seatedRow = profile(
        "machine_seated_row", Feature.ELBOW, 150f, 100f, listOf(upright, seated),
        startHint = "Выпрями руки", farHint = "Тяни к поясу",
        postureHint = "Сиди прямо без раскачивания, ближняя рука открыта",
    )
    val chestPress = profile(
        "machine_chest_press", Feature.ELBOW, 105f, 150f, listOf(upright, seated),
        startHint = "Рукояти к груди", farHint = "Выпрями руки",
        postureHint = "Сиди у спинки, ближний локоть не закрыт рычагом",
    )
    val legExtension = profile(
        "machine_leg_extension", Feature.KNEE, 105f, 155f, listOf(upright, seated),
        startHint = "Согни ноги", farHint = "Разогни ноги",
        postureHint = "Сядь боком, таз, колено и лодыжка открыты",
    )
    val seatedLegCurl = profile(
        "machine_leg_curl", Feature.KNEE, 150f, 90f, listOf(upright, seated),
        startHint = "Выпрями ноги", farHint = "Согни ноги",
        postureHint = "Сиди у спинки, таз и колено не закрыты кожухом",
    )
    val lyingLegCurl = profile(
        "machine_lying_leg_curl", Feature.KNEE, 150f, 90f,
        listOf(bench, posture(Feature.HIP_FLEXION, 155f, 180f, 145f, 180f)),
        startHint = "Выпрями ноги", farHint = "Согни ноги к ягодицам",
        postureHint = "Ляг на живот боком к телефону, таз и колено открыты",
    )
    val hyperextension = profile(
        "hyperextension", Feature.HIP_FLEXION, 165f, 110f,
        listOf(posture(Feature.KNEE, 150f, 180f, 145f, 180f)), 2_500L,
        startHint = "Выстрой корпус и бёдра в линию", farHint = "Наклонись от тазобедренного сустава",
        postureHint = "Встань на станок боком, подушка не закрывает таз",
    )

    // Брусья, камера сбоку. Пола камера не видит, поэтому «ноги не на полу» задаётся позой:
    // корпус почти вертикален, таз разогнут (не сидит и не приседает), кисти у таза, а предплечье
    // идёт от локтя вниз к жерди (упор, а не сгибание рук стоя).
    /** Другая лодыжка (если видна) — на той же высоте: обе ноги в воздухе, а не одна на полу. */
    private val feetTogether = AngularPosition(Feature.REAR_ANKLE_RAISE, -0.5f, 0.5f, -0.6f, 0.6f, optional = true)
    private val supportHands = listOf(
        posture(Feature.WRIST_OVER_HIP, -0.5f, 0.7f, -0.6f, 0.8f),
        posture(Feature.ELBOW_OVER_WRIST, 0.3f, 2f, 0.25f, 2f),
    )

    /**
     * Отжимания на брусьях: локоть > 155° → < 95° и плечи опустились к локтям. Колени согнуты, голени
     * за спиной: таз выше лодыжки не больше 1,25 торса (стоя и с прямыми висящими ногами ≈ 1,75) — так
     * брусья не путаются со сгибанием рук стоя. Вертикальный корпус отличает их от отжиманий от пола.
     */
    val barsDip = profile(
        "bars_dip", Feature.ELBOW, 155f, 95f,
        listOf(
            posture(Feature.TORSO_TILT, 0f, 30f, 0f, 40f),
            posture(Feature.HIP_FLEXION, 130f, 180f, 120f, 180f),
            posture(Feature.HIP_OVER_ANKLE, -0.5f, 1.25f, -0.6f, 1.35f),
            feetTogether,
        ) + supportHands, 1_500L,
        startHint = "Выпрями руки в упоре", farHint = "Опустись, плечи к локтям",
        postureHint = "Упор на брусьях боком, колени согнуты",
        farChecks = listOf(Feature.SHOULDER_OVER_ELBOW.below(0.3f)),
    )

    /**
     * Подъём ног в упоре на брусьях: угол плечо–таз–колено > 160° → < 105° (бёдра до горизонтали).
     * Руки прямые, корпус у вертикали, ноги почти прямые: так подъём не путается с приседом (там колено
     * < 110°), а махи ногой стоя отсекает вторая лодыжка, оставшаяся на полу (если она видна).
     */
    val barsLegRaise = profile(
        "bars_leg_raise", Feature.HIP_FLEXION, 160f, 105f,
        listOf(
            posture(Feature.TORSO_TILT, 0f, 20f, 0f, 30f),
            posture(Feature.ELBOW, 150f, 180f, 140f, 180f),
            posture(Feature.KNEE, 140f, 180f, 125f, 180f),
            feetTogether,
        ) + supportHands.take(1), 1_500L,
        startHint = "Опусти ноги", farHint = "Подними бёдра до горизонтали",
        postureHint = "Упор на прямых руках боком к телефону",
    )

    val all = listOf(romanianDeadlift, barbellBench, barbellIncline, dumbbellBench, dumbbellIncline,
        seatedPress, lateralRaise, lunge, bulgarianSplitSquat, latPulldown, seatedRow, chestPress,
        legExtension, seatedLegCurl, lyingLegCurl, hyperextension, barsDip, barsLegRaise)
    val byId = all.associateBy { it.id }
}
