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
        positions = positions, minCycleMs = minMs, extremeConfirmMs = 150L, side = side, farChecks = farChecks,
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

    val all = listOf(romanianDeadlift, barbellBench, barbellIncline, dumbbellBench, dumbbellIncline,
        seatedPress, lateralRaise, lunge, bulgarianSplitSquat, latPulldown, seatedRow, chestPress,
        legExtension, seatedLegCurl, lyingLegCurl, hyperextension)
    val byId = all.associateBy { it.id }
}
