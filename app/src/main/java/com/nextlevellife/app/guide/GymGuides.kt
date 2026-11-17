package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuideJoint.*
import com.nextlevellife.app.guide.GuidePoses.front
import com.nextlevellife.app.guide.GuidePoses.side

/**
 * Поддерживаемые исполнения зала из docs/gym-catalog-design.md, разделы 3–5.
 * Рисунок сбоку раскрывает амплитуду; фактический ракурс телефона указан отдельно.
 * Реквизит объясняет технику и не означает распознавание снаряда камерой.
 */
object GymGuides : GuidePack {
    private fun frames(a: GuidePose, b: GuidePose, move: Int = 1250) = listOf(
        GuideFrame(a, move, 600), GuideFrame(b, move, 600),
    )
    private val legHighlight = setOf(GuideHighlight.LEGS)
    private val armHighlight = setOf(GuideHighlight.ARMS)
    private val torsoHighlight = setOf(GuideHighlight.TORSO)
    private fun camera(height: String, joints: String) =
        "Закрепи телефон на высоте $height м: $joints остаются открыты на всей амплитуде"
    private const val OPEN_ARM = "Плечо, локоть или кисть скрыты скамьёй, блином либо рамой — переставь телефон"
    private const val OPEN_LEG = "Таз, колено или лодыжка скрыты рамой либо валиком — переставь телефон"

    private fun squat(id: String, frontRack: Boolean = false, goblet: Boolean = false): ExerciseGuide {
        fun pose(bottom: Boolean): GuidePose {
            val torso = if (bottom) { if (frontRack || goblet) 20f else 35f } else 0f
            val grip = if (goblet) Reach(0.065f, 0.025f, 180f, R_SHOULDER)
                else if (frontRack) null
                else Reach(-0.025f, 0.005f, 180f, R_SHOULDER)
            return side(torso = torso, thigh = if (bottom) 90f else 180f,
                shin = if (bottom) 200f else 180f, head = torso, pin = onFloor(0.56f), hands = grip,
                upperArm = if (frontRack) 90f else 180f, forearm = if (frontRack) 270f else 180f)
        }
        return ExerciseGuide(
            id, GuideView.SIDE, frames(pose(false), pose(true)),
            props = if (goblet) GuideProps(dumbbells = Dumbbells(DumbbellGrip.GOBLET))
                else GuideProps(barbell = Barbell(if (frontRack) BarbellGrip.FRONT_RACK else BarbellGrip.SHOULDERS,
                    offset = if (frontRack) Pt(0.01f, 0f) else Pt(-0.025f, -0.005f), halfLength = 0.18f)),
            highlight = legHighlight, distance = if (goblet) "2,5–3 м" else "2,5–3,5 м", angle = "сбоку",
            camera = camera("0,7–1,0", "Ближние таз, колено и лодыжка"),
            focus = if (goblet) "Гантель у груди, пятки на полу" else if (frontRack) "Локти вперёд, гриф на передних дельтах" else "Гриф на верхе спины, стопы устойчивы",
            tips = listOf(
                if (goblet) "Держи одну гантель вертикально обеими руками за верхний диск"
                else if (frontRack) "Держи гриф на плечах перед шеей, локти направь вперёд"
                else "Положи гриф на мышцы верха спины, держи его руками с двух сторон",
                "Опустись до бёдер примерно параллельно полу, затем выпрями ноги",
                "Колени движутся по направлению носков, спина сохраняет нейтральное положение",
            ),
            mistakes = listOf("Неглубокий присед или неполное выпрямление ног", "Таз, колено или лодыжка закрыты стойкой либо блинами"),
        )
    }

    private val deadlift = ExerciseGuide(
        "bb_deadlift", GuideView.SIDE,
        frames(
            side(torso = 0f, pin = onFloor(0.48f)),
            side(torso = 65f, thigh = 110f, shin = 195f, upperArm = 180f, forearm = 180f,
                head = 65f, pin = onFloor(0.48f)), 1450,
        ), props = GuideProps(barbell = Barbell(halfLength = 0.18f, plateRadius = 0.095f)),
        highlight = torsoHighlight, distance = "3–4 м", angle = "сбоку с небольшим смещением вперёд",
        camera = camera("0,7–1,0", "Плечо, кисть, таз, колено и лодыжка"),
        focus = "Встань, одновременно разгибая таз и колени",
        tips = listOf("Гриф идёт близко к голеням и бёдрам, руки остаются прямыми",
            "Отведи таз назад и согни колени, кисти опускаются к коленям или ниже",
            "Вверху выпрямись без отклонения корпуса назад"),
        mistakes = listOf("Недостаточный наклон внизу или согнутые колени наверху", "Блин постоянно закрывает кисть или колено"),
    )

    private val romanian = ExerciseGuide(
        "bb_romanian_deadlift", GuideView.SIDE,
        frames(
            side(torso = 0f, thigh = 175f, shin = 185f, pin = onFloor(0.52f)),
            side(torso = 75f, thigh = 165f, shin = 185f, head = 75f, pin = onFloor(0.52f)), 1450,
        ), props = GuideProps(barbell = Barbell(halfLength = 0.18f)),
        highlight = torsoHighlight, distance = "2,5–3,5 м", angle = "сбоку",
        camera = camera("0,7–1,0", "Плечо, таз и колено"),
        focus = "Таз назад, колени слегка согнуты",
        tips = listOf("Начни стоя, удерживай гриф близко к ногам на прямых руках",
            "Наклоняйся в тазобедренных суставах, сохраняя небольшой сгиб коленей",
            "Остановись в доступной глубине с нейтральной спиной и выпрямись без прогиба"),
        mistakes = listOf("Колени сильно сгибаются как в приседе", "Таз скрыт блином или наклон слишком мал"),
    )

    private fun benchPress(id: String, incline: Boolean, dumbbells: Boolean): ExerciseGuide {
        val torso = if (incline) 310f else 270f
        val hip = Pin(PELVIS, 0.49f, 0.685f)
        fun pose(top: Boolean): GuidePose {
            val raw = side(torso = torso, thigh = 90f, shin = 180f,
                upperArm = if (top) 12f else if (incline) 115f else 120f,
                forearm = if (top) 12f else if (incline) 25f else 30f, pin = hip)
            // Руки разведены в глубину сцены. Их проекция короче, чем в строгом профиле:
            // одинаковый коэффициент в обоих кадрах сохраняет угол и длины видимых костей.
            return GuidePose(raw.points.mapValues { (joint, point) ->
                val shoulder = when (joint) {
                    R_ELBOW, R_WRIST -> raw[R_SHOULDER]
                    L_ELBOW, L_WRIST -> raw[L_SHOULDER]
                    else -> null
                }
                if (shoulder == null) point else Pt(shoulder.x + (point.x - shoulder.x) * 0.78f,
                    shoulder.y + (point.y - shoulder.y) * 0.78f)
            })
        }
        return ExerciseGuide(
            id, GuideView.SIDE, frames(pose(false), pose(true)),
            props = GuideProps(
                barbell = if (dumbbells) null else Barbell(halfLength = 0.18f, plateRadius = 0.045f),
                dumbbells = if (dumbbells) Dumbbells() else null,
                bench = if (incline) Bench(Pt(0.49f, 0.716f), Pt(0.263f, 0.526f), Pt(0.59f, 0.716f))
                    else Bench(Pt(0.53f, 0.72f), Pt(0.19f, 0.72f)),
            ), highlight = armHighlight, anchor = PELVIS, distance = "2–3 м",
            angle = "переднебоковой со стороны ног, 30–45° к скамье",
            camera = camera(if (incline) "0,9–1,2" else "0,7–1,1", "Ближние плечо, локоть и кисть"),
            focus = if (incline) "Скамья 30–45°, стопы на полу" else "Лопатки на скамье, стопы на полу",
            tips = listOf(
                if (incline) "Установи спинку под 30–45° и сохраняй опору таза и лопаток"
                else "Ляг на скамью, собери лопатки и сохраняй устойчивую опору стоп",
                if (dumbbells) "Опускай обе гантели к груди и выжимай одновременно"
                else "Опускай гриф к груди без отбива, затем выжимай вверх",
                "Предплечья внизу близки к вертикали, локти направлены примерно под 45° к корпусу",
                "Один общий цикл обеих рук — один повтор, ближняя рука полностью открыта",
            ), mistakes = listOf("Руки не выпрямились или локоть недостаточно согнулся", OPEN_ARM),
        )
    }

    private val standingPress = run {
        val rack = front(rightHand = Reach(-0.005f, 0.005f, 180f, R_SHOULDER),
            leftHand = Reach(0.005f, 0.005f, 180f, L_SHOULDER))
        ExerciseGuide("bb_press", GuideView.FRONT,
            frames(rack, front(upperArm = 180f, forearm = 180f)),
            props = GuideProps(barbell = Barbell(halfLength = 0.22f, plateRadius = 0.045f)),
            highlight = armHighlight, distance = "2,5–3,5 м", angle = "спереди или немного сбоку",
            camera = camera("0,9–1,2", "Голова, локти, кисти над головой и колени"),
            focus = "Штанга от плеч до прямых рук над головой",
            tips = listOf("Держи корпус вертикально и колени прямыми, не подседай",
                "Выжми штангу над головой, затем подконтрольно верни к плечам",
                "Смести телефон, если блины закрывают кисти"),
            mistakes = listOf("Руки не выпрямились над головой или штанга не вернулась к плечам", OPEN_ARM))
    }

    private fun row(id: String, single: Boolean): ExerciseGuide {
        val support = Reach(0.64f, 0.58f, 90f)
        fun pose(pulled: Boolean) = side(torso = 72f, thigh = 165f, shin = 190f,
            head = 72f, pin = onFloor(0.43f),
            hands = if (pulled) Reach(0.065f, 0.015f, 270f, PELVIS) else null,
            farHands = if (single) support else if (pulled) Reach(0.065f, 0.015f, 270f, PELVIS) else null)
        return ExerciseGuide(id, GuideView.SIDE, frames(pose(false), pose(true)),
            props = if (single) GuideProps(dumbbells = Dumbbells(DumbbellGrip.RIGHT_HAND),
                bench = Bench(Pt(0.60f, 0.596f), Pt(0.85f, 0.596f)))
            else GuideProps(barbell = Barbell(halfLength = 0.18f)),
            highlight = if (single) setOf(GuideHighlight.WORKING_ARM) else armHighlight,
            distance = if (single) "2–3 м" else "2,5–3,5 м", angle = if (single) "с рабочей стороны" else "сбоку или переднебоковой",
            camera = camera("0,7–1,0", "Ближние плечо, локоть, кисть и таз"),
            focus = "Локоть назад, корпус сохраняет наклон",
            tips = listOf(
                if (single) "Обе стопы на полу, свободной рукой обопрись на скамью, рабочая рука ближе"
                else "Наклонись с нейтральной спиной и слегка согнутыми коленями",
                "Из прямой руки тяни снаряд к поясу, веди локоть назад вдоль корпуса",
                "Не поворачивай плечи и не выпрямляй корпус, затем полностью разогни руку",
            ), mistakes = listOf("Корпус выпрямился или кисть не дошла к поясу", OPEN_ARM))
    }

    private val seatedPress = ExerciseGuide("db_seated_press", GuideView.SIDE,
        frames(
            side(torso = 0f, thigh = 90f, shin = 180f, upperArm = 90f, forearm = 0f, pin = Pin(PELVIS, 0.45f, 0.685f)),
            side(torso = 0f, thigh = 90f, shin = 180f, upperArm = 0f, forearm = 0f, pin = Pin(PELVIS, 0.45f, 0.685f)),
        ), props = GuideProps(dumbbells = Dumbbells(), bench = Bench(Pt(0.42f, 0.716f), Pt(0.42f, 0.43f), Pt(0.56f, 0.716f))),
        highlight = armHighlight, anchor = PELVIS, distance = "2,5–3 м", angle = "спереди или переднебоковой",
        camera = camera("0,9–1,2", "Плечи, локти, кисти над головой и таз"),
        focus = "Жми сидя с устойчивым вертикальным корпусом",
        tips = listOf("Сядь с опорой спины и поставь стопы на пол",
            "Начни с гантелями у плеч, выжми обе руки над головой",
            "Верни гантели к плечам, сохраняя таз на сиденье и не прогибая поясницу"),
        mistakes = listOf("Неполное выпрямление рук или малая амплитуда внизу", OPEN_ARM))

    private val curl = ExerciseGuide("db_curl", GuideView.SIDE,
        frames(
            side(torso = 0f, upperArm = 178f, forearm = 176f, pin = onFloor(0.48f), farUpperArm = 180f, farForearm = 180f),
            side(torso = 0f, upperArm = 178f, forearm = 15f, pin = onFloor(0.48f), farUpperArm = 180f, farForearm = 180f),
        ), props = GuideProps(dumbbells = Dumbbells()),
        highlight = setOf(GuideHighlight.WORKING_ARM), distance = "2–3 м", angle = "сбоку",
        camera = camera("0,8–1,1", "Рабочие плечо, локоть и кисть, а также таз"),
        focus = "Локоть у корпуса, сгибай рабочую руку",
        tips = listOf("Считай отдельный подход каждой рукой, рабочая рука ближе к камере",
            "Не двигая локоть вперёд, подними гантель к плечу",
            "Полностью опусти руку без раскачивания корпуса"),
        mistakes = listOf("Неполное сгибание или разгибание локтя", "Кисть закрыта гантелью или корпус раскачивается"))

    private val lateralRaise = ExerciseGuide("db_lateral_raise", GuideView.FRONT,
        frames(front(upperArm = 8f, forearm = 14f), front(upperArm = 85f, forearm = 75f)),
        props = GuideProps(dumbbells = Dumbbells()), highlight = armHighlight,
        distance = "2,5–3 м", angle = "спереди",
        camera = camera("0,9–1,2", "Таз, плечи, локти и обе кисти по сторонам"),
        focus = "Подними руки в стороны до уровня плеч",
        tips = listOf("Стой вертикально, локти немного согнуты и сохраняют этот угол",
            "Разведи обе руки в стороны до уровня плеч, не пожимая плечами",
            "Подконтрольно опусти руки вдоль тела, не делай мах корпусом"),
        mistakes = listOf("Руки подняты вперёд либо не дошли до уровня плеч", "Наклон корпуса или сильное сгибание локтей"))

    private fun lunge(bulgarian: Boolean): ExerciseGuide {
        val frontFoot = onFloor(0.70f)
        val backFoot = Reach(if (bulgarian) 0.25f else 0.29f, if (bulgarian) 0.635f else 0.85f, 180f)
        fun pose(bottom: Boolean) = side(torso = if (bottom) 8f else 4f,
            thigh = if (bottom) 90f else 145f, shin = if (bottom) 180f else 150f,
            pin = frontFoot, farFeet = backFoot, upperArm = 180f, forearm = 180f)
        return ExerciseGuide(if (bulgarian) "db_bulgarian_split_squat" else "db_lunge",
            GuideView.SIDE, frames(pose(false), pose(true), 1450),
            props = GuideProps(dumbbells = Dumbbells(), bench = if (bulgarian)
                Bench(Pt(0.13f, 0.66f), Pt(0.33f, 0.66f)) else null),
            highlight = legHighlight, distance = "2,5–3,5 м", angle = "сбоку, рабочая нога ближе",
            camera = camera("0,6–0,9", "Передние таз, колено и лодыжка, а также задняя стопа"),
            focus = if (bulgarian) "Опускайся на передней ноге, задняя стопа на скамье" else "Выпад на месте, гантели вдоль тела",
            tips = listOf("Поставь рабочую ногу ближе к телефону и выполняй подход одной ногой",
                if (bulgarian) "Положи подъём задней стопы на скамью, сохраняя основной вес на передней ноге"
                else "Сохраняй разножку на месте, задняя пятка приподнята, обе гантели вдоль тела",
                "Опустись до переднего колена примерно под прямым углом и выпрямись",
                "Колено следует за носком, таз и корпус движутся без разворота"),
            mistakes = listOf("Переднее колено недостаточно согнулось или не выпрямилось", OPEN_LEG))
    }

    private val seat = Chair(0.37f, 0.53f, 0.715f)
    private val seatedHip = Pin(PELVIS, 0.43f, 0.685f)
    private fun seated(upperArm: Float, forearm: Float) = side(torso = 0f, thigh = 90f, shin = 180f,
        upperArm = upperArm, forearm = forearm, pin = seatedHip)

    private val latPulldown = ExerciseGuide("machine_lat_pulldown", GuideView.SIDE,
        frames(seated(15f, 15f), seated(130f, 20f)),
        props = GuideProps(cableMachine = CableMachine(Pt(0.80f, 0.89f), Pt(0.80f, 0.12f), Pt(0.49f, 0.12f), seat)),
        highlight = armHighlight, anchor = PELVIS, distance = "2–3 м", angle = "переднебоковой, 30–45°",
        camera = camera("0,9–1,2", "Плечо, локоть, кисть и таз"),
        focus = "Тяни рукоять к верхней части груди",
        tips = listOf("Сядь устойчиво, корпус почти вертикален, руки полностью вытянуты наверху",
            "Веди локти вниз и опусти рукоять к плечам перед головой",
            "Подконтрольно верни руки наверх без раскачивания и тяги за шею"),
        mistakes = listOf("Локти мало согнулись или руки не выпрямились", OPEN_ARM))

    private val seatedRow = ExerciseGuide("machine_seated_row", GuideView.SIDE,
        frames(seated(90f, 90f), seated(200f, 70f)),
        props = GuideProps(cableMachine = CableMachine(Pt(0.85f, 0.89f), Pt(0.85f, 0.37f), Pt(0.85f, 0.60f), seat)),
        highlight = armHighlight, anchor = PELVIS, distance = "2–3 м", angle = "сбоку",
        camera = camera("0,7–1,0", "Плечо, локоть, кисть и таз"),
        focus = "Тяни к животу без раскачивания корпуса",
        tips = listOf("Сядь с вертикальным корпусом, начни с выпрямленными руками",
            "Веди локти назад и рукоять к животу, не откидывайся",
            "Верни руки вперёд, сохраняя таз и плечи на месте"),
        mistakes = listOf("Корпус раскачивается или рукоять не дошла к животу", OPEN_ARM))

    private val chestPress = ExerciseGuide("machine_chest_press", GuideView.SIDE,
        frames(seated(160f, 40f), seated(85f, 85f)),
        props = GuideProps(cableMachine = CableMachine(Pt(0.20f, 0.89f), Pt(0.20f, 0.30f), Pt(0.20f, 0.54f), seat)),
        highlight = armHighlight, anchor = PELVIS, distance = "2–3 м", angle = "переднебоковой, 30–45°",
        camera = camera("0,9–1,2", "Ближние плечо, локоть и кисть"),
        focus = "Выжми рукояти перед грудью",
        tips = listOf("Настрой сиденье: рукояти на уровне груди, таз и спина опираются на тренажёр",
            "Выжми обе рукояти вперёд, затем подконтрольно согни руки",
            "Используй открытую конструкцию, позволяющую видеть плечо и локоть"),
        mistakes = listOf("Малая амплитуда сгибания либо выпрямления локтя", OPEN_ARM))

    private fun legExercise(id: String, curl: Boolean, lying: Boolean): ExerciseGuide {
        val hip = if (lying) Pin(PELVIS, 0.47f, 0.59f) else seatedHip
        fun pose(flexed: Boolean) = if (lying) side(torso = 90f, thigh = 270f,
            shin = if (flexed) 5f else 270f, upperArm = 160f, forearm = 70f, pin = hip)
        else side(torso = 0f, thigh = 90f, shin = if (flexed) 185f else 95f,
            upperArm = 178f, forearm = 95f, pin = hip)
        return ExerciseGuide(id, GuideView.SIDE,
            frames(pose(!curl), pose(curl)),
            props = GuideProps(
                chair = if (lying) null else seat,
                bench = if (lying) Bench(Pt(0.27f, 0.626f), Pt(0.72f, 0.626f)) else null,
                legMachine = LegMachine(if (lying) Pt(0.27f, 0.59f) else Pt(0.63f, 0.685f)),
            ), highlight = legHighlight, anchor = PELVIS, distance = "2–3 м", angle = "сбоку со свободной стороны",
            camera = camera(if (curl) "0,4–0,8" else "0,5–0,8", "Таз, колено и лодыжка"),
            focus = if (curl) "Согни колени, сохраняя таз на опоре" else "Выпрями колени без рывка",
            tips = listOf(
                if (lying) "Ляг животом на скамью, колени у края, валик над пятками"
                else "Настрой ось тренажёра у колена, спина и таз устойчиво на сиденье",
                if (curl) "Из прямых ног согни колени примерно до прямого угла, затем плавно разогни"
                else "Из согнутых коленей подними валик до почти прямых ног, затем опусти",
                "Не отрывай таз и не ускоряй движение раскачиванием корпуса",
            ), mistakes = listOf("Малая амплитуда колена либо отрыв таза", OPEN_LEG))
    }

    private val assisted = run {
        val rg = Reach(0.38f, 0.105f, 270f)
        val lg = Reach(0.62f, 0.105f, 90f)
        fun pose(top: Boolean) = front(thigh = 3f, shin = 150f,
            pin = Pin(PELVIS, 0.5f, if (top) 0.335f else 0.543f), rightHand = rg, leftHand = lg)
        ExerciseGuide("assisted_pullup", GuideView.FRONT, frames(pose(false), pose(true), 1450),
            props = GuideProps(bar = true, assistedMachine = AssistedMachine()), highlight = armHighlight,
            anchor = R_WRIST, distance = "2,5–3,5 м", angle = "спереди или переднебоковой",
            camera = camera("1,0–1,3", "Нос, плечи, локти и кисти"),
            focus = "Подтягивайся с коленями на платформе",
            tips = listOf("Укажи противовес: больше противовес — легче подтягивание",
                "Начни на прямых руках, подними подбородок к уровню кистей",
                "Опустись до прямых рук без рывков, колени остаются на подвижной платформе"),
            mistakes = listOf("Руки не выпрямились или голова не поднялась к кистям", "Стойки закрывают нос, локти или кисти"))
    }

    private val hyperextension = run {
        val hip = Pin(PELVIS, 0.53f, 0.53f)
        val hands = Reach(0.035f, 0.035f, 180f, R_SHOULDER)
        ExerciseGuide("hyperextension", GuideView.SIDE,
            frames(
                side(torso = 45f, thigh = 225f, shin = 225f, pin = hip, hands = hands),
                side(torso = 115f, thigh = 225f, shin = 225f, pin = hip, hands = hands), 1450,
            ), props = GuideProps(bench = Bench(Pt(0.47f, 0.59f), Pt(0.54f, 0.52f)),
                legMachine = LegMachine(Pt(0.29f, 0.78f), Pt(0f, 0.025f))),
            highlight = torsoHighlight, anchor = PELVIS, distance = "2,5–3 м", angle = "сбоку",
            camera = camera("0,6–0,9", "Плечо, таз и колено"),
            focus = "Разгибай таз до одной линии тела, без прогиба",
            tips = listOf("Настрой подушку под верхнюю часть бёдер, тазобедренный сустав остаётся свободным",
                "Скрести руки на груди и наклони корпус в тазобедренных суставах",
                "Подними корпус до линии плечи — таз — колени, не переразгибай поясницу"),
            mistakes = listOf("Подушка постоянно скрывает таз", "Малый наклон или подъём с прогибом вместо разгибания таза"))
    }

    // Брусья: кисти неподвижны на ближней и дальней жердях (ось жерди чуть ниже запястья), стопы в воздухе.
    private const val BAR_Y = 0.48f
    private const val ARM_STRAIGHT = 0.2595f // чуть короче плеча + предплечья: локоть ≈ 173°, «почти прямые»
    private fun barsProps(x0: Float, x1: Float) = GuideProps(parallelBars = ParallelBars(x0, x1, BAR_Y + 0.012f))
    private const val BARS_CAMERA_HEIGHT = "1,0–1,3"

    /**
     * Отжимания на брусьях. Верх — руки почти прямые, плечо над кистью, корпус наклонён на 12°.
     * Низ — предплечье почти вертикально (локоть чуть позади кисти), плечо на уровне локтя, локоть ≈ 88°,
     * наклон 22°. Колени согнуты, голени скрещены за спиной.
     */
    private val barsDip = run {
        val wx = 0.52f
        val hands = Reach(wx, BAR_Y, 270f)
        fun pose(bottom: Boolean) = side(
            torso = if (bottom) 22f else 12f, thigh = 172f, shin = 272f, farThigh = 176f, farShin = 262f,
            pin = if (bottom) Pin(R_SHOULDER, wx + 0.112f, BAR_Y - 0.142f) else Pin(R_SHOULDER, wx, BAR_Y - ARM_STRAIGHT),
            hands = hands,
        )
        ExerciseGuide("bars_dip", GuideView.SIDE, frames(pose(false), pose(true), 1300),
            props = barsProps(0.2f, 0.84f), highlight = armHighlight, anchor = R_WRIST,
            distance = "2,5–3,5 м", angle = "сбоку",
            camera = "Закрепи телефон сбоку на высоте $BARS_CAMERA_HEIGHT м, на уровне груди: в кадре брусья и всё тело, " +
                "ближние плечо, локоть и кисть не закрыты стойкой",
            focus = "Локти назад, плечи опускаются до уровня локтей",
            tips = listOf(
                "Встань в упор на прямых руках: плечи опущены от ушей, корпус почти вертикален",
                "Согни колени и скрести голени за спиной — так камера видит, что стопы не на полу",
                "Опускайся, отводя локти назад, пока плечо не дойдёт до уровня локтя, затем выжмись до прямых рук",
                "Двигайся ровно, без раскачки и рывков ногами",
            ),
            mistakes = listOf(
                "Неглубоко (плечи выше локтей) или руки не выпрямились наверху",
                "Прямые ноги внизу, стопы на полу или сильный наклон корпуса",
            ),
        )
    }

    /** Подъём ног в упоре: таз и кисти на месте, корпус у вертикали (отклонение 5° назад наверху), ноги почти прямые. */
    private val barsLegRaise = run {
        val wx = 0.40f
        val hands = Reach(wx, BAR_Y, 270f)
        fun pose(top: Boolean) = side(
            torso = if (top) -5f else 0f, thigh = if (top) 85f else 178f, shin = if (top) 88f else 180f,
            pin = Pin(R_SHOULDER, wx, BAR_Y - ARM_STRAIGHT), hands = hands,
        )
        ExerciseGuide("bars_leg_raise", GuideView.SIDE, frames(pose(false), pose(true), 1250),
            props = barsProps(0.16f, 0.8f), highlight = torsoHighlight, anchor = R_WRIST,
            distance = "2,5–3,5 м", angle = "сбоку",
            camera = "Закрепи телефон сбоку на высоте $BARS_CAMERA_HEIGHT м: в кадре брусья и всё тело от кистей до стоп, " +
                "таз и колени не закрыты стойкой",
            focus = "Бёдра до горизонтали, корпус без раскачки",
            tips = listOf(
                "Упор на прямых руках: плечи опущены, корпус вертикален, ноги свободно висят",
                "Подними почти прямые ноги, пока бёдра не станут хотя бы параллельны полу",
                "Опусти ноги до конца под контролем, не раскачивайся и не отклоняйся назад",
            ),
            mistakes = listOf(
                "Бёдра не дошли до горизонтали или ноги не опустились до конца",
                "Сильно согнутые колени или руки, раскачка корпуса",
            ),
        )
    }

    override val guides: List<ExerciseGuide> = listOf(
        squat("bb_squat"), squat("bb_front_squat", frontRack = true), deadlift, romanian,
        benchPress("bb_bench_press", incline = false, dumbbells = false),
        benchPress("bb_incline_press", incline = true, dumbbells = false), standingPress, row("bb_row", false),
        benchPress("db_bench_press", incline = false, dumbbells = true),
        benchPress("db_incline_press", incline = true, dumbbells = true), seatedPress, row("db_row", true),
        curl, lateralRaise, lunge(false), lunge(true), squat("db_goblet_squat", goblet = true),
        latPulldown, seatedRow, chestPress,
        legExercise("machine_leg_extension", curl = false, lying = false),
        legExercise("machine_leg_curl", curl = true, lying = false),
        legExercise("machine_lying_leg_curl", curl = true, lying = true), assisted, hyperextension,
        barsDip, barsLegRaise,
    )
}
