package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.L_ANKLE
import com.nextlevellife.app.guide.GuideJoint.L_KNEE
import com.nextlevellife.app.guide.GuideJoint.PELVIS
import com.nextlevellife.app.guide.GuideJoint.R_ANKLE
import com.nextlevellife.app.guide.GuideJoint.R_KNEE
import com.nextlevellife.app.guide.GuideJoint.R_SHOULDER
import com.nextlevellife.app.guide.GuidePoses.front
import com.nextlevellife.app.guide.GuidePoses.side
import com.nextlevellife.app.guide.GuideScene.ANKLE_Y

/**
 * Показы поз и движений программ ЛФК и йоги (data/Routines.kt): ключ — guideId шага
 * (у удержаний — poseId, у повторений — moveId). Удержание — два кадра: безопасный вход и сама поза
 * с долгой остановкой; повторения — исходное и крайнее положение. Несимметричные позы нарисованы
 * для ближней (правой) стороны; другая сторона — зеркально, это сказано в подсказках шага.
 * Расстояние и ракурс — те же, что у шагов в Routines; условия поз — в pose/PoseHoldConfigs.
 */
object RoutineGuides : GuidePack {

    // ---------- Камера (как в Routines) ----------
    private const val SIDE_HIGH = "Сбоку, телефон на высоте 50–90 см"
    private const val FRONT_HIGH = "Спереди, телефон на высоте 50–90 см"
    private const val SIDE_FLOOR = "Сбоку, телефон 20–40 см от пола"
    private const val NEAR_2_3 = "2–3 м"
    private const val NEAR_25_3 = "2,5–3 м"
    private const val FLOOR_DIST = "1,8–2,5 м"

    private const val NO_PAIN = "Без боли: при неприятных ощущениях уменьши амплитуду или остановись"
    private const val SMOOTH = "Двигайся плавно, дыши свободно"

    // ---------- Опоры поз ----------

    /** Сидя на стуле: таз на сиденье, бедро горизонтально. */
    private val SEAT = Pin(PELVIS, 0.38f, 0.685f)
    private val CHAIR = Chair(0.25f, 0.45f, 0.715f, backLeft = true)

    /** Лёжа на спине: плечо на полу, голова слева. */
    private val LIE = Pin(R_SHOULDER, 0.25f, 0.858f)

    /** На четвереньках: колено на полу. */
    private val KNEE = Pin(R_KNEE, 0.38f, 0.875f)
    private val HANDS_UNDER = Reach(0.557f, 0.88f, bend = 0f)

    private fun sit(
        torso: Float = 0f, upperArm: Float = 170f, forearm: Float = 110f, shin: Float = 180f,
    ) = side(torso = torso, thigh = 90f, shin = shin, farShin = 180f, upperArm = upperArm, forearm = forearm, pin = SEAT)

    private fun lie(
        torso: Float = 270f, thigh: Float = 40f, shin: Float = 150f, farThigh: Float = thigh, farShin: Float = shin,
        upperArm: Float = 90f, forearm: Float = 90f, head: Float = 272f, hands: Reach? = null, feet: Reach? = null,
    ) = side(
        torso = torso, thigh = thigh, shin = shin, farThigh = farThigh, farShin = farShin,
        upperArm = upperArm, forearm = forearm, head = head, pin = LIE, hands = hands, feet = feet,
    )

    private val table = side(torso = 73f, thigh = 180f, shin = 270f, head = 80f, pin = KNEE, hands = HANDS_UNDER)
    private val rockBack = side(torso = 70f, thigh = 145f, shin = 270f, head = 78f, pin = KNEE, hands = HANDS_UNDER)
    private val stand = side(torso = 0f, upperArm = 178f, forearm = 176f, pin = onFloor(0.5f))

    /** Ноги согнуты, стопы на полу в одной точке: мост и полумост. */
    private val bridgeFeet = Reach(0.659f, ANKLE_Y, bend = 0f)

    // ---------- Сборка гида ----------

    private fun hold(
        id: String, title: String, view: GuideView, entry: GuidePose, pose: GuidePose,
        distance: String, camera: String, focus: String, tips: List<String>, mistakes: List<String>,
        props: GuideProps = GuideProps(mat = camera.startsWith(SIDE_FLOOR)),
        highlight: Set<GuideHighlight> = emptySet(), anchor: GuideJoint = R_ANKLE,
    ) = ExerciseGuide(
        exerciseId = id, view = view,
        frames = listOf(GuideFrame(entry, 1400, 900), GuideFrame(pose, 1600, 4000)),
        props = props, highlight = highlight, anchor = anchor,
        distance = distance, angle = angleOf(camera), camera = camera, focus = focus,
        tips = tips, mistakes = mistakes, title = title,
    )

    private fun reps(
        id: String, title: String, view: GuideView, start: GuidePose, end: GuidePose,
        distance: String, camera: String, focus: String, tips: List<String>, mistakes: List<String>,
        props: GuideProps = GuideProps(mat = camera.startsWith(SIDE_FLOOR)),
        highlight: Set<GuideHighlight> = emptySet(), anchor: GuideJoint = R_ANKLE,
    ) = ExerciseGuide(
        exerciseId = id, view = view,
        frames = listOf(GuideFrame(start, 1600, 700), GuideFrame(end, 1600, 900)),
        props = props, highlight = highlight, anchor = anchor,
        distance = distance, angle = angleOf(camera), camera = camera, focus = focus,
        tips = tips, mistakes = mistakes, title = title,
    )

    private fun angleOf(camera: String) = if (camera.startsWith("Спереди")) "спереди" else "сбоку"

    // ---------- ЛФК ----------

    private val lfk = listOf(
        hold(
            "sit_upright", "Спокойное сидение", GuideView.SIDE,
            entry = sit(torso = 15f), pose = sit(),
            distance = NEAR_2_3, camera = SIDE_HIGH, focus = "Спина прямая, стопы на полу",
            tips = listOf("Сядь боком к телефону, плечи над тазом", "Колени примерно под прямым углом", "Дыши спокойно"),
            mistakes = listOf("Сутулость или наклон вперёд — поза не подтверждается"),
            props = GuideProps(chair = CHAIR), highlight = setOf(GuideHighlight.TORSO), anchor = PELVIS,
        ),
        reps(
            "arm_raise_front", "Подъём рук вперёд", GuideView.SIDE,
            start = sit(upperArm = 180f, forearm = 180f), end = sit(upperArm = 90f, forearm = 90f),
            distance = NEAR_2_3, camera = SIDE_HIGH, focus = "Руки до уровня плеч",
            tips = listOf("Сидя или стоя: руки прямые, корпус неподвижен", "Подними до уровня плеч и плавно опусти", NO_PAIN),
            mistakes = listOf("Руки ниже плеч или согнуты в локтях — повтор не засчитан"),
            props = GuideProps(chair = CHAIR), highlight = setOf(GuideHighlight.ARMS), anchor = PELVIS,
        ),
        reps(
            "seated_lean", "Наклон прямого корпуса сидя", GuideView.SIDE,
            start = sit(upperArm = 155f, forearm = 20f), end = sit(torso = 28f, upperArm = 183f, forearm = 48f),
            distance = NEAR_2_3, camera = SIDE_HIGH, focus = "Небольшой наклон, спина прямая",
            tips = listOf("Руки скрещены на груди, наклон от таза", "Таз остаётся на стуле", SMOOTH),
            mistakes = listOf("Наклон слишком мал или спина округлилась"),
            props = GuideProps(chair = CHAIR), highlight = setOf(GuideHighlight.TORSO), anchor = PELVIS,
        ),
        reps(
            "quadruped_rock", "Покачивание назад на четвереньках", GuideView.SIDE,
            start = table, end = rockBack,
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Таз мягко назад к пяткам",
            tips = listOf("Кисти под плечами, колени под тазом", "Кисти и колени остаются на месте", NO_PAIN),
            mistakes = listOf("Таз почти не сдвинулся назад"),
            highlight = setOf(GuideHighlight.TORSO), anchor = R_KNEE,
        ),
        reps(
            "quadruped_reach", "Вытягивание руки на четвереньках", GuideView.SIDE,
            start = table,
            end = side(
                torso = 73f, thigh = 180f, shin = 270f, upperArm = 88f, forearm = 90f, head = 80f,
                pin = KNEE, hands = null, farHands = HANDS_UNDER,
            ),
            distance = FLOOR_DIST, camera = "$SIDE_FLOOR, рабочая рука ближе", focus = "Рука вперёд до уровня плеча",
            tips = listOf("Рабочая рука ближе к телефону", "Спина не прогибается, корпус горизонтально", SMOOTH),
            mistakes = listOf("Рука ниже плеча или согнута — повтор не засчитан"),
            props = GuideProps(mat = true), highlight = setOf(GuideHighlight.WORKING_ARM), anchor = R_KNEE,
        ),
        reps(
            "soft_bridge", "Мягкий ягодичный мост", GuideView.SIDE,
            start = lie(feet = bridgeFeet), end = lie(torso = 248f, head = 280f, feet = bridgeFeet),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Подними таз без прогиба",
            tips = listOf("Лёжа на спине, стопы на полу, руки вдоль тела", "Таз вверх невысоко и плавно вниз", "Не задерживай дыхание"),
            mistakes = listOf("Таз почти не поднялся или не опустился до пола"),
            highlight = setOf(GuideHighlight.TORSO), anchor = R_SHOULDER,
        ),
        hold(
            "knee_to_chest", "Колено к груди лёжа", GuideView.SIDE,
            entry = lie(),
            pose = lie(thigh = 325f, shin = 75f, farThigh = 40f, farShin = 150f, hands = Reach(0f, 0f, bend = 0f, joint = R_KNEE)),
            distance = FLOOR_DIST, camera = "$SIDE_FLOOR, рабочая нога ближе", focus = "Колено к груди мягко",
            tips = listOf("Рабочая нога ближе к телефону", "Подтяни колено руками, другая нога согнута", "Без рывков и боли"),
            mistakes = listOf("Колено далеко от груди или нога закрыта от камеры"),
            highlight = setOf(GuideHighlight.LEGS), anchor = R_SHOULDER,
        ),
        hold(
            "rest_chair", "Отдых лёжа, голени на стуле", GuideView.SIDE,
            entry = lie(), pose = lie(thigh = 0f, shin = 90f),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Голени на сиденье, дыши спокойно",
            tips = listOf("Стул устойчивый, голени лежат на сиденье", "Колени примерно под прямым углом", "Расслабься, время не оплачивается"),
            mistakes = listOf("Ноги не на стуле или корпус приподнят"),
            props = GuideProps(mat = true, chair = Chair(0.47f, 0.66f, 0.69f, backLeft = false)), anchor = R_SHOULDER,
        ),
        reps(
            "elbow_flex", "Сгибание и разгибание локтей", GuideView.FRONT,
            start = front(), end = front(upperArm = 5f, forearm = 100f),
            distance = NEAR_25_3, camera = FRONT_HIGH, focus = "Плечи неподвижны",
            tips = listOf("Локти у корпуса, сгибай и выпрямляй руки", "Внизу руки полностью прямые", SMOOTH),
            mistakes = listOf("Руки не выпрямились до конца или почти не согнулись"),
            highlight = setOf(GuideHighlight.ARMS), anchor = PELVIS,
        ),
        reps(
            "arm_abduction", "Разведение рук до уровня плеч", GuideView.FRONT,
            start = front(), end = front(upperArm = 90f, forearm = 90f),
            distance = NEAR_25_3, camera = FRONT_HIGH, focus = "Руки в стороны до уровня плеч",
            tips = listOf("Руки прямые, поднимай обе одновременно", "Не выше плеч, плечи не поднимай к ушам", NO_PAIN),
            mistakes = listOf("Руки не дошли до уровня плеч или согнуты"),
            highlight = setOf(GuideHighlight.ARMS), anchor = PELVIS,
        ),
        reps(
            "leg_abduction", "Отведение прямой ноги в сторону", GuideView.FRONT,
            start = front(), end = front(thigh = 20f, shin = 20f, leftThigh = 4f, leftShin = 2f),
            distance = NEAR_25_3, camera = FRONT_HIGH, focus = "Небольшая амплитуда, без махов",
            tips = listOf("Держись за спинку стула сбоку", "Нога прямая, корпус не наклоняй", SMOOTH),
            mistakes = listOf("Нога согнута или корпус сильно наклонён"),
            highlight = setOf(GuideHighlight.LEGS), anchor = PELVIS,
        ),
        reps(
            "knee_raise", "Подъём колена стоя", GuideView.SIDE,
            start = side(torso = 0f, upperArm = 178f, forearm = 176f, pin = Pin(L_ANKLE, 0.5f, ANKLE_Y)),
            end = side(torso = 0f, thigh = 90f, shin = 180f, farThigh = 180f, farShin = 180f, upperArm = 178f, forearm = 176f, pin = Pin(L_ANKLE, 0.5f, ANKLE_Y)),
            distance = NEAR_2_3, camera = "$SIDE_HIGH, рабочая нога ближе", focus = "Колено до комфортной высоты",
            tips = listOf("Держись за опору", "Корпус прямо, опорная нога прямая", SMOOTH),
            mistakes = listOf("Колено поднято слишком низко или корпус наклонён"),
            highlight = setOf(GuideHighlight.LEGS), anchor = L_ANKLE,
        ),
        reps(
            "shallow_squat", "Неглубокое приседание", GuideView.SIDE,
            start = stand,
            end = side(torso = 15f, thigh = 155f, shin = 200f, upperArm = 120f, forearm = 110f, pin = onFloor(0.5f)),
            distance = NEAR_2_3, camera = SIDE_HIGH, focus = "Чуть согни колени и выпрямись",
            tips = listOf("Пятки на полу, корпус почти прямо", "Не приседай глубоко", NO_PAIN),
            mistakes = listOf("Колени почти не согнулись или не выпрямились"),
            highlight = setOf(GuideHighlight.LEGS),
        ),
        reps(
            "knee_extension", "Разгибание колена сидя", GuideView.SIDE,
            start = sit(), end = sit(shin = 95f),
            distance = NEAR_2_3, camera = "$SIDE_HIGH, рабочая нога ближе", focus = "Бедро на сиденье, выпрями ногу",
            tips = listOf("Рабочая нога ближе к телефону", "Выпрямляй плавно, без рывка в конце", "Опускай до прямого угла в колене"),
            mistakes = listOf("Нога не выпрямилась или бедро оторвалось от сиденья"),
            props = GuideProps(chair = CHAIR), highlight = setOf(GuideHighlight.LEGS), anchor = PELVIS,
        ),
        reps(
            "heel_slide", "Сгибание ноги лёжа", GuideView.SIDE,
            start = lie(thigh = 90f, shin = 90f), end = lie(thigh = 40f, shin = 150f, farThigh = 90f, farShin = 90f),
            distance = FLOOR_DIST, camera = "$SIDE_FLOOR, рабочая нога ближе", focus = "Пятка скользит к тазу",
            tips = listOf("Рабочая нога ближе к телефону", "Поясница спокойно на полу", SMOOTH),
            mistakes = listOf("Колено почти не согнулось или нога не выпрямилась"),
            highlight = setOf(GuideHighlight.LEGS), anchor = R_SHOULDER,
        ),
    )

    // ---------- Йога ----------

    private val warriorFeet = Pair(Reach(0.225f, ANKLE_Y, bend = 270f), Reach(0.773f, ANKLE_Y, bend = 45f))

    private val yoga = listOf(
        hold(
            "mountain", "Гора с поднятыми руками", GuideView.FRONT,
            entry = front(), pose = front(upperArm = 172f, forearm = 178f),
            distance = NEAR_25_3, camera = FRONT_HIGH, focus = "Руки вверх, ноги прямые",
            tips = listOf("Стопы вместе, ноги прямые", "Руки вверх, плечи не поднимай к ушам", "Дыши спокойно"),
            mistakes = listOf("Руки согнуты или ниже головы — время не идёт"),
            highlight = setOf(GuideHighlight.ARMS), anchor = PELVIS,
        ),
        hold(
            "chair", "Стул, неглубокий вариант", GuideView.SIDE,
            entry = stand,
            pose = side(torso = 25f, thigh = 140f, shin = 190f, upperArm = 105f, forearm = 105f, head = 20f, pin = onFloor(0.5f)),
            distance = NEAR_2_3, camera = SIDE_HIGH, focus = "Таз назад, руки вперёд",
            tips = listOf("Колени согнуты неглубоко, пятки на полу", "Руки прямые вперёд, спина ровная", NO_PAIN),
            mistakes = listOf("Слишком глубоко или ноги почти прямые — поза не подтверждается"),
            highlight = setOf(GuideHighlight.LEGS),
        ),
        hold(
            "warrior2", "Воин II", GuideView.FRONT,
            entry = front(pin = Pin(PELVIS, 0.55f, 0.617f), rightFoot = warriorFeet.first, leftFoot = warriorFeet.second),
            pose = front(
                upperArm = 90f, forearm = 90f, pin = Pin(PELVIS, 0.55f, 0.625f),
                rightFoot = warriorFeet.first, leftFoot = warriorFeet.second,
            ),
            distance = NEAR_25_3, camera = FRONT_HIGH, focus = "Переднее колено согнуто, руки в стороны",
            tips = listOf("Широкий шаг, задняя нога прямая", "Колено над стопой, на другую сторону — зеркально", "Не углубляй позу ради таймера"),
            mistakes = listOf("Переднее колено почти прямое или руки опущены"),
            highlight = setOf(GuideHighlight.LEGS, GuideHighlight.ARMS),
        ),
        hold(
            "table", "Стол на четвереньках", GuideView.SIDE,
            entry = rockBack, pose = table,
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Кисти под плечами, колени под тазом",
            tips = listOf("Руки прямые, спина ровная", "Взгляд в пол, шея продолжает спину", "Дыши спокойно"),
            mistakes = listOf("Таз или плечи сильно сдвинуты — поза не подтверждается"),
            highlight = setOf(GuideHighlight.TORSO), anchor = R_KNEE,
        ),
        hold(
            "puppy", "Вытянутый щенок", GuideView.SIDE,
            entry = table,
            pose = side(torso = 128f, thigh = 180f, shin = 270f, head = 115f, pin = KNEE, hands = Reach(0.86f, 0.88f, bend = 0f)),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Таз над коленями, руки вперёд",
            tips = listOf("Из стола пройди руками вперёд", "Грудь мягко вниз, без боли в плечах", "Таз остаётся над коленями"),
            mistakes = listOf("Таз ушёл к пяткам — это уже поза ребёнка"),
            highlight = setOf(GuideHighlight.ARMS), anchor = R_KNEE,
        ),
        hold(
            "sphinx", "Сфинкс", GuideView.SIDE,
            entry = side(torso = 88f, thigh = 268f, shin = 270f, upperArm = 92f, forearm = 90f, head = 90f, pin = Pin(PELVIS, 0.45f, 0.855f)),
            pose = side(torso = 60f, thigh = 268f, shin = 270f, upperArm = 160f, forearm = 90f, head = 70f, pin = Pin(PELVIS, 0.45f, 0.855f)),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Локти под плечами",
            tips = listOf("Лёжа на животе, ноги прямые", "Подъём невысокий, предплечья на полу", "Не прогибай поясницу сильнее"),
            mistakes = listOf("Локти далеко впереди плеч или корпус не поднят"),
            highlight = setOf(GuideHighlight.TORSO), anchor = PELVIS,
        ),
        hold(
            "child", "Поза ребёнка", GuideView.SIDE,
            entry = side(torso = 73f, thigh = 180f, shin = 270f, head = 80f, pin = Pin(R_KNEE, 0.55f, 0.875f), hands = Reach(0.727f, 0.88f, bend = 0f)),
            pose = side(torso = 100f, thigh = 115f, shin = 270f, head = 100f, pin = Pin(R_KNEE, 0.55f, 0.875f), hands = Reach(0.86f, 0.88f, bend = 0f)),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Таз к пяткам, руки вперёд",
            tips = listOf("Из стола опусти таз к пяткам", "Лоб к коврику, руки вытянуты вперёд", "Дыши спокойно"),
            mistakes = listOf("Колени или рука не видны камере — время не идёт"),
            highlight = setOf(GuideHighlight.TORSO), anchor = R_KNEE,
        ),
        hold(
            "low_lunge", "Низкий выпад", GuideView.SIDE,
            entry = side(
                torso = 0f, thigh = 110f, shin = 180f, farThigh = 195f, farShin = 255f, pin = onFloor(0.62f),
                hands = Reach(-0.07f, -0.015f, bend = 90f, joint = R_KNEE),
            ),
            pose = side(
                torso = 5f, thigh = 92f, shin = 180f, farThigh = 190f, farShin = 270f, head = 5f, pin = onFloor(0.62f),
                hands = Reach(-0.07f, -0.015f, bend = 90f, joint = R_KNEE),
            ),
            distance = FLOOR_DIST, camera = "$SIDE_FLOOR, рабочая сторона ближе", focus = "Заднее колено на коврике",
            tips = listOf("Передняя нога — ближе к телефону, колено над стопой", "Руки на переднем бедре, корпус прямо", "Не дави коленом вперёд через боль"),
            mistakes = listOf("Заднее колено не опущено или ноги стоят слишком близко"),
            highlight = setOf(GuideHighlight.LEGS),
        ),
        hold(
            "half_split", "Полушпагат с мягким коленом", GuideView.SIDE,
            entry = side(
                torso = 10f, thigh = 95f, shin = 180f, farThigh = 180f, farShin = 270f, pin = Pin(L_KNEE, 0.38f, 0.875f),
                hands = Reach(-0.06f, -0.015f, bend = 90f, joint = R_KNEE),
            ),
            pose = side(
                torso = 62f, thigh = 115f, shin = 130f, farThigh = 180f, farShin = 270f, head = 70f,
                pin = Pin(L_KNEE, 0.38f, 0.875f), hands = Reach(0.6f, 0.86f, bend = 0f),
            ),
            distance = FLOOR_DIST, camera = "$SIDE_FLOOR, рабочая сторона ближе", focus = "Колено передней ноги мягкое",
            tips = listOf("Таз над задним коленом, передняя нога вперёд", "Можно опереться руками на блоки", "Глубину наклона ради зачёта не увеличивай"),
            mistakes = listOf("Передняя нога сильно согнута или таз ушёл вперёд"),
            highlight = setOf(GuideHighlight.LEGS), anchor = L_KNEE,
        ),
        hold(
            "half_bridge", "Невысокий полумост", GuideView.SIDE,
            entry = lie(feet = bridgeFeet), pose = lie(torso = 248f, head = 280f, feet = bridgeFeet),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Таз вверх, плечи и стопы на полу",
            tips = listOf("Стопы на полу, руки вдоль тела", "Подъём невысокий, без прогиба в пояснице", "Дыши спокойно"),
            mistakes = listOf("Таз опустился — время не идёт"),
            highlight = setOf(GuideHighlight.TORSO), anchor = R_SHOULDER,
        ),
        hold(
            "shavasana", "Шавасана — отдых лёжа", GuideView.SIDE,
            entry = lie(), pose = lie(thigh = 90f, shin = 90f, upperArm = 98f, forearm = 94f),
            distance = FLOOR_DIST, camera = SIDE_FLOOR, focus = "Ноги вытянуты, руки вдоль тела",
            tips = listOf("Ляг на спину, ноги прямые", "Расслабься и дыши спокойно", "Время не оплачивается"),
            mistakes = listOf("Колени согнуты или корпус приподнят"),
            anchor = R_SHOULDER,
        ),
    )

    override val guides: List<ExerciseGuide> = lfk + yoga
}
