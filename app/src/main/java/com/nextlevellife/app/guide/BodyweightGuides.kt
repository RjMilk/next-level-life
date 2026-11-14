package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuidePoses.front
import com.nextlevellife.app.guide.GuidePoses.side

/**
 * Гиды упражнений без веса. Ракурс и расстояние сверены со счётчиками в pose/Counters.kt:
 * какие суставы и углы им нужны, и с подсказками на камере (setupLine в CameraRepPanel).
 */
object BodyweightGuides : GuidePack {

    /** Отжимания: носки на месте, кисти на месте; корпус опускается целиком. */
    private val pushups = run {
        val ankle = Pin(GuideJoint.R_ANKLE, 0.18f, 0.865f)
        val hands = Reach(0.70f, 0.88f, bend = 270f)
        ExerciseGuide(
            exerciseId = "pushups",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 65f, thigh = 245f, shin = 245f, head = 70f, pin = ankle, hands = hands), 1000, 600),
                GuideFrame(side(torso = 82f, thigh = 262f, shin = 262f, head = 86f, pin = ankle, hands = hands), 1100, 500),
            ),
            props = GuideProps(mat = true),
            highlight = setOf(GuideHighlight.ARMS),
            distance = "1,8–2,5 м",
            angle = "сбоку",
            camera = "Телефон сбоку на низкой подставке: в кадре плечи, локти, таз и стопы",
            focus = "Корпус движется целиком",
            tips = listOf(
                "Опустись, пока локти не согнутся сильнее прямого угла",
                "Вернись на полностью выпрямленные руки",
                "Корпус — одна прямая от плеч до стоп",
            ),
            mistakes = listOf("Полуповтор: локти мало согнулись или руки не выпрямились до конца"),
        )
    }

    private val squats = run {
        val feet = onFloor(0.55f)
        ExerciseGuide(
            exerciseId = "squats",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 0f, upperArm = 176f, forearm = 172f, pin = feet), 1100, 600),
                GuideFrame(
                    side(torso = 40f, thigh = 95f, shin = 200f, upperArm = 88f, forearm = 88f, head = 25f, pin = feet),
                    1100, 500,
                ),
            ),
            highlight = setOf(GuideHighlight.LEGS),
            distance = "2–3 м",
            angle = "сбоку или в пол-оборота",
            camera = "Телефон на уровне пояса: видно всё тело от головы до стоп",
            focus = "Колени сгибаются, затем полностью выпрямляются",
            tips = listOf(
                "Опускайся, пока бёдра не станут почти параллельны полу",
                "Вверху полностью выпрями ноги",
                "Пятки на полу, руки можно вытянуть вперёд",
            ),
            mistakes = listOf("Неглубокий присед — повтор не засчитается"),
        )
    }

    private val abs = run {
        val feet = onFloor(0.75f)
        ExerciseGuide(
            exerciseId = "abs",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(
                    side(torso = 270f, thigh = 40f, shin = 150f, upperArm = 65f, forearm = 290f, head = 276f, pin = feet),
                    1000, 600,
                ),
                GuideFrame(
                    side(torso = 335f, thigh = 40f, shin = 150f, upperArm = 130f, forearm = 355f, head = 345f, pin = feet),
                    1000, 500,
                ),
            ),
            props = GuideProps(mat = true),
            highlight = setOf(GuideHighlight.TORSO),
            distance = "1,8–2,5 м",
            angle = "сбоку",
            camera = "Телефон сбоку на полу или низкой подставке: в кадре плечи, таз, колени и стопы",
            focus = "Подними корпус и плавно опустись",
            tips = listOf(
                "Ноги согнуты, стопы на полу, руки скрещены на груди",
                "Поднимай корпус больше чем наполовину — к коленям",
                "Опускайся до конца, лопатки касаются пола",
            ),
            mistakes = listOf("Подъём одними плечами слишком мал — повтор не засчитается"),
        )
    }

    /** Подтягивания спереди: кисти на перекладине неподвижны (опора — правая кисть). */
    private val pullups = run {
        val rightGrip = Reach(0.38f, 0.105f, bend = 270f)
        val leftGrip = Reach(0.62f, 0.105f, bend = 90f)
        ExerciseGuide(
            exerciseId = "pullups",
            view = GuideView.FRONT,
            frames = listOf(
                GuideFrame(
                    front(thigh = 3f, shin = 1f, pin = Pin(GuideJoint.PELVIS, 0.5f, 0.54f), rightHand = rightGrip, leftHand = leftGrip),
                    1300, 600,
                ),
                GuideFrame(
                    front(thigh = 3f, shin = 1f, pin = Pin(GuideJoint.PELVIS, 0.5f, 0.335f), rightHand = rightGrip, leftHand = leftGrip),
                    1100, 500,
                ),
            ),
            props = GuideProps(bar = true),
            highlight = setOf(GuideHighlight.ARMS),
            anchor = GuideJoint.R_WRIST,
            distance = "2,5–3 м",
            angle = "спереди",
            camera = "Телефон спереди, ниже перекладины: видны кисти, голова, локти и таз",
            focus = "Начни на прямых руках",
            tips = listOf(
                "Каждый повтор — из виса на прямых руках",
                "Поднимайся, пока подбородок не окажется у перекладины",
                "Опускайся подконтрольно, без рывков",
                "Камера следит за локтями и головой относительно кистей, перекладину она не видит",
            ),
            mistakes = listOf("Руки внизу не выпрямились или подбородок не дошёл до кистей"),
        )
    }

    /** Планка: вход с колен, удержание, выход на колени. Опора — локоть на полу. */
    private val plank = run {
        val elbow = Pin(GuideJoint.R_ELBOW, 0.68f, 0.88f)
        ExerciseGuide(
            exerciseId = "plank",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(
                    side(torso = 112f, thigh = 180f, shin = 270f, upperArm = 180f, forearm = 90f, head = 100f, pin = elbow),
                    1000, 900,
                ),
                GuideFrame(
                    side(torso = 77f, thigh = 257f, shin = 257f, upperArm = 180f, forearm = 90f, head = 80f, pin = elbow),
                    1100, 2600,
                ),
            ),
            props = GuideProps(mat = true),
            highlight = setOf(GuideHighlight.TORSO),
            anchor = GuideJoint.R_ELBOW,
            distance = "1,8–2,5 м",
            angle = "строго сбоку",
            camera = "Телефон на полу сбоку: видны плечи, локти, таз, колени и стопы",
            focus = "Таз на линии тела",
            tips = listOf(
                "Начни на коленях в кадре, затем прими планку — так камера увидит вход в позу",
                "Локти под плечами, предплечья на полу",
                "Тело — прямая от плеч до пяток, дыши свободно",
            ),
            mistakes = listOf(
                "Таз провис или задран — время не идёт",
                "Отрезок короче 10 секунд не засчитывается",
            ),
        )
    }

    override val guides: List<ExerciseGuide> = listOf(pushups, squats, abs, pullups, plank)
}
