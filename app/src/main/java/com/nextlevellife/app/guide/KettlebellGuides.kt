package com.nextlevellife.app.guide

import com.nextlevellife.app.guide.GuidePoses.front
import com.nextlevellife.app.guide.GuidePoses.side
import com.nextlevellife.app.guide.GuideScene.ANKLE_Y

/**
 * Гиды упражнений с гирей. Ракурс и расстояние сверены со счётчиками в pose/Counters.kt
 * и с подсказками на камере (setupLine в CameraRepPanel).
 */
object KettlebellGuides : GuidePack {
    private val inHands = Bell(BellGrip.HANDS)

    private val thruster = run {
        val feet = onFloor(0.52f)
        val rack = side(torso = 0f, upperArm = 160f, forearm = 20f, pin = feet)
        ExerciseGuide(
            exerciseId = "thruster",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(rack, 900, 500),
                GuideFrame(
                    side(torso = 25f, thigh = 95f, shin = 200f, upperArm = 165f, forearm = 25f, head = 15f, pin = feet),
                    1000, 300,
                ),
                GuideFrame(rack, 800, 0),
                GuideFrame(side(torso = 0f, upperArm = 4f, forearm = 3f, pin = feet), 600, 600),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.LEGS, GuideHighlight.ARMS),
            distance = "2,5–3 м",
            angle = "сбоку или в пол-оборота",
            camera = "Оставь место над головой для вытянутых рук: в кадре всё тело",
            focus = "Присед, подъём, жим",
            tips = listOf(
                "Гиря у груди, присядь до бёдер почти параллельно полу",
                "Вставая, выжми гирю над головой одним движением",
                "Вверху руки прямые и ноги выпрямлены",
                "Не прогибайся в пояснице",
            ),
            mistakes = listOf("Руки не выпрямились над головой или присед неглубокий"),
        )
    }

    private val swing = run {
        val feet = onFloor(0.5f)
        ExerciseGuide(
            exerciseId = "swings",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(
                    side(torso = 55f, thigh = 150f, shin = 195f, upperArm = 205f, forearm = 210f, head = 45f, pin = feet),
                    1000, 300,
                ),
                GuideFrame(side(torso = 15f, thigh = 175f, shin = 183f, upperArm = 160f, forearm = 150f, pin = feet), 600, 0),
                GuideFrame(side(torso = 0f, upperArm = 95f, forearm = 95f, pin = feet), 500, 500),
            ),
            props = GuideProps(bell = Bell(BellGrip.HANDS, BellHang.ALONG_FOREARM)),
            highlight = setOf(GuideHighlight.TORSO),
            distance = "2,5–3 м",
            angle = "сбоку",
            camera = "Встань боком к телефону: в кадре весь замах и руки перед собой",
            focus = "Движение начинается тазом",
            tips = listOf(
                "В замахе таз уходит назад, корпус наклоняется, гиря между ног",
                "Резко выпрями таз — гиря сама поднимется до уровня груди",
                "Руки только сопровождают гирю",
            ),
            mistakes = listOf("Маленький наклон в замахе или гиря не дошла до груди"),
        )
    }

    private val sumoPull = run {
        val rightFoot = Reach(0.37f, ANKLE_Y, bend = 270f)
        val leftFoot = Reach(0.63f, ANKLE_Y, bend = 90f)
        ExerciseGuide(
            exerciseId = "sumo_pull",
            view = GuideView.FRONT,
            frames = listOf(
                GuideFrame(
                    front(
                        pin = Pin(GuideJoint.PELVIS, 0.5f, 0.6f),
                        rightFoot = rightFoot, leftFoot = leftFoot,
                        rightHand = Reach(0.49f, 0.665f, bend = 270f), leftHand = Reach(0.51f, 0.665f, bend = 90f),
                    ),
                    1100, 500,
                ),
                GuideFrame(
                    front(
                        pin = Pin(GuideJoint.PELVIS, 0.5f, 0.5f),
                        rightFoot = rightFoot, leftFoot = leftFoot,
                        rightHand = Reach(0.485f, 0.3f, bend = 300f), leftHand = Reach(0.515f, 0.3f, bend = 60f),
                    ),
                    1100, 500,
                ),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.ARMS),
            anchor = GuideJoint.R_ANKLE,
            distance = "2–3 м",
            angle = "спереди или в пол-оборота",
            camera = "В кадре обе руки, таз и стопы",
            focus = "Локти идут вверх",
            tips = listOf(
                "Широкая стойка, начни с опущенными руками: гиря ниже таза",
                "Вставая, тяни гирю к подбородку, локти выше кистей",
                "Поднимай только до комфортной высоты, без рывка плечами",
            ),
            mistakes = listOf("Локти ниже кистей или гиря не дошла до уровня плеч"),
        )
    }

    private val row = run {
        val feet = onFloor(0.45f)
        ExerciseGuide(
            exerciseId = "row",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(
                    side(
                        torso = 72f, thigh = 165f, shin = 190f, upperArm = 180f, forearm = 180f,
                        farUpperArm = 200f, farForearm = 200f, head = 78f, pin = feet,
                    ),
                    1100, 500,
                ),
                GuideFrame(
                    side(
                        torso = 72f, thigh = 165f, shin = 190f, upperArm = 265f, forearm = 185f,
                        farUpperArm = 200f, farForearm = 200f, head = 78f, pin = feet,
                    ),
                    900, 500,
                ),
            ),
            props = GuideProps(bell = Bell(BellGrip.RIGHT_HAND)),
            highlight = setOf(GuideHighlight.WORKING_ARM),
            distance = "2–2,5 м",
            angle = "сбоку или в пол-оборота",
            camera = "Рабочая рука ближе к камере, видно всё тело",
            focus = "Сохраняй наклон корпуса",
            tips = listOf(
                "Наклонись вперёд с прямой спиной, свободная рука на колене",
                "Веди локоть назад, гиря идёт к поясу",
                "Внизу полностью выпрями руку",
            ),
            mistakes = listOf("Корпус выпрямился или гиря не дошла до пояса"),
        )
    }

    private val deadlift = run {
        val feet = onFloor(0.48f)
        ExerciseGuide(
            exerciseId = "deadlift",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 0f, upperArm = 180f, forearm = 178f, pin = feet), 1100, 500),
                GuideFrame(
                    side(torso = 75f, thigh = 130f, shin = 195f, upperArm = 180f, forearm = 180f, head = 70f, pin = feet),
                    1100, 400,
                ),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.TORSO),
            distance = "2,5–3 м",
            angle = "сбоку",
            camera = "В кадре плечи, кисти, таз, колени и стопы",
            focus = "Отводи таз назад, затем встань",
            tips = listOf(
                "Наклонись с прямой спиной, гиря до колен или ниже",
                "Вставай, выпрямляя таз и колени одновременно",
                "Вверху корпус вертикально, без отклонения назад",
            ),
            mistakes = listOf("Наклон слишком мал или вверху не выпрямился до конца"),
        )
    }

    private val gobletSquat = run {
        val feet = onFloor(0.52f)
        ExerciseGuide(
            exerciseId = "goblet_squat",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 0f, upperArm = 160f, forearm = 20f, pin = feet), 1100, 600),
                GuideFrame(
                    side(torso = 25f, thigh = 95f, shin = 200f, upperArm = 165f, forearm = 25f, head = 15f, pin = feet),
                    1100, 500,
                ),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.LEGS),
            distance = "2–3 м",
            angle = "сбоку",
            camera = "Телефон на уровне пояса: видно всё тело от головы до стоп",
            focus = "Гиря у груди",
            tips = listOf(
                "Держи гирю за рожки у груди, локти вниз",
                "Опускайся, пока бёдра не станут почти параллельны полу",
                "Вверху полностью выпрями ноги",
            ),
            mistakes = listOf(
                "Руки опущены вдоль тела — такой присед не засчитается",
                "Неглубокий присед",
            ),
        )
    }

    /** Жим одной рукой: рисуется правой (слева на экране), считается любая рука. */
    private val press = run {
        val rack = front(upperArm = -5f, forearm = 175f, leftUpperArm = 8f, leftForearm = 4f)
        ExerciseGuide(
            exerciseId = "press",
            view = GuideView.FRONT,
            frames = listOf(
                GuideFrame(rack, 1100, 600),
                GuideFrame(front(upperArm = 178f, forearm = 180f, leftUpperArm = 8f, leftForearm = 4f), 1000, 500),
            ),
            props = GuideProps(bell = Bell(BellGrip.RIGHT_HAND)),
            highlight = setOf(GuideHighlight.WORKING_ARM),
            distance = "2–3 м",
            angle = "спереди",
            camera = "Видно всё тело и место над головой для вытянутой руки",
            focus = "Без подседа",
            tips = listOf(
                "Гиря у плеча, локоть под кистью",
                "Выжми вверх до прямой руки, ноги и корпус неподвижны",
                "Опусти гирю обратно к плечу — тогда повтор засчитается",
                "Руку можно менять: считаются обе",
            ),
            mistakes = listOf(
                "Рука не выпрямилась над головой",
                "Две руки разом засчитываются как один повтор",
            ),
        )
    }

    private val reverseLunge = run {
        val front = onFloor(0.62f)
        ExerciseGuide(
            exerciseId = "reverse_lunge",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 0f, upperArm = 160f, forearm = 20f, pin = front), 1200, 600),
                GuideFrame(
                    side(
                        torso = 3f, thigh = 95f, shin = 180f, farThigh = 195f, farShin = 275f,
                        upperArm = 162f, forearm = 22f, pin = front,
                    ),
                    1200, 500,
                ),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.LEGS),
            distance = "2,5–3 м",
            angle = "сбоку",
            camera = "Видно всё тело от головы до стоп и место для шага назад",
            focus = "Шаг назад, затем вернись",
            tips = listOf(
                "Гиря у груди, корпус вертикально",
                "Шагни назад и опустись: колени согнуты примерно под прямым углом",
                "Вернись в стойку на прямые ноги",
                "Чередуй ноги: каждый шаг — один повтор",
            ),
            mistakes = listOf("Неглубокий выпад или не вернулся в стойку до конца"),
        )
    }

    private val curl = run {
        val feet = onFloor(0.5f)
        ExerciseGuide(
            exerciseId = "curl",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 0f, upperArm = 175f, forearm = 172f, pin = feet), 1200, 500),
                GuideFrame(side(torso = 0f, upperArm = 178f, forearm = 15f, pin = feet), 1000, 500),
            ),
            props = GuideProps(bell = inHands),
            highlight = setOf(GuideHighlight.ARMS),
            distance = "1,8–2,5 м",
            angle = "сбоку",
            camera = "Видно всё тело, руки не закрыты корпусом",
            focus = "Локти возле корпуса",
            tips = listOf(
                "Возьми гирю двумя руками, руки прямые",
                "Согни руки и подними гирю к груди",
                "Опусти до полностью прямых рук",
            ),
            mistakes = listOf("Раскачивание корпусом или гиря не дошла до груди"),
        )
    }

    /** Мост: плечи и стопы на месте, таз поднимается. Опора — плечо на полу. */
    private val bridge = run {
        val shoulder = Pin(GuideJoint.R_SHOULDER, 0.3f, 0.858f)
        val feet = Reach(0.705f, ANKLE_Y, bend = 0f)
        val hands = Reach(0f, -0.065f, bend = 180f, joint = GuideJoint.PELVIS)
        ExerciseGuide(
            exerciseId = "glute_bridge",
            view = GuideView.SIDE,
            frames = listOf(
                GuideFrame(side(torso = 270f, head = 272f, pin = shoulder, hands = hands, feet = feet), 1100, 600),
                GuideFrame(side(torso = 235f, head = 285f, pin = shoulder, hands = hands, feet = feet), 1000, 600),
            ),
            props = GuideProps(bell = Bell(BellGrip.PELVIS), mat = true),
            highlight = setOf(GuideHighlight.TORSO),
            anchor = GuideJoint.R_SHOULDER,
            distance = "1,8–2,5 м",
            angle = "сбоку",
            camera = "Телефон низко, на полу или подставке: в кадре плечи, таз, колени и стопы",
            focus = "Подними таз без прогиба",
            tips = listOf(
                "Ляг на спину, стопы на полу, гиря на тазу — придерживай её руками",
                "Подними таз до прямой линии плечи — таз — колени",
                "Опустись до пола и начни следующий повтор",
            ),
            mistakes = listOf("Таз поднят невысоко или не опустился до пола"),
        )
    }

    override val guides: List<ExerciseGuide> =
        listOf(thruster, swing, sumoPull, row, deadlift, gobletSquat, press, reverseLunge, curl, bridge)
}
