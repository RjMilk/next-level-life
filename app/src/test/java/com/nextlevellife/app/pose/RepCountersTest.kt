package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Синтетические подходы для всех девяти упражнений.
 * [path] — замкнутый путь одного повторения, path[0] — положение между повторениями.
 * [wrong] — то же движение, но не в позе упражнения.
 */
internal class Spec(
    val id: String,
    val path: List<Key>,
    val segmentMs: Long,
    val wrong: List<Key>,
    val farHint: String,
) {
    val rest get() = path[0]

    /** Тот же путь, но каждая поза — на полпути от исходной. */
    val half get() = path.map { rest.lerp(it, 0.5f) }

    override fun toString() = id
}

internal val STAND = Key(0f, 180f, 180f, 180f, 180f)
internal val CURL = Key(0f, 180f, 180f, 180f, 30f)

/** Гиря у груди: запястья на уровне плеч перед грудью. */
internal val GOBLET = Key(0f, 180f, 180f, 160f, 20f)

/** Мост внизу: лёжа на спине, колени согнуты, руки к тазу. */
internal val BRIDGE_DOWN = Key(270f, 40f, 150f, 95f, 70f)

internal val SPECS = listOf(
    Spec(
        "pushups",
        path = listOf(Key(75f, 255f, 255f, 180f, 180f), Key(85f, 265f, 265f, 255f, 160f)),
        segmentMs = 700,
        wrong = listOf(STAND, CURL),
        farHint = "Ниже",
    ),
    Spec(
        "squats",
        path = listOf(Key(0f, 180f, 180f, 90f, 90f), Key(40f, 95f, 200f, 90f, 90f)),
        segmentMs = 900,
        wrong = listOf(Key(0f, 180f, 180f, 90f, 90f), Key(40f, 95f, 200f, 90f, 90f)).map { it.rotated(90f) },
        farHint = "Ниже",
    ),
    Spec(
        "abs",
        path = listOf(Key(270f, 45f, 145f, 0f, 0f), Key(345f, 45f, 145f, 0f, 0f)),
        segmentMs = 800,
        wrong = listOf(STAND, Key(60f, 180f, 180f, 180f, 180f)),
        farHint = "Выше",
    ),
    Spec(
        "pullups",
        path = listOf(Key(0f, 180f, 180f, 0f, 0f), Key(0f, 180f, 180f, 130f, -15f)),
        segmentMs = 900,
        wrong = listOf(STAND, CURL),
        farHint = "Выше",
    ),
    Spec(
        "thruster",
        path = listOf(
            Key(0f, 180f, 180f, 170f, -20f), // гиря у груди, стоит
            Key(30f, 95f, 200f, 170f, -20f), // присед
            Key(0f, 180f, 180f, 170f, -20f), // встал
            Key(0f, 180f, 180f, 0f, 0f), // руки над головой
        ),
        segmentMs = 600,
        wrong = listOf(
            Key(0f, 180f, 180f, 170f, -20f),
            Key(30f, 95f, 200f, 170f, -20f),
            Key(0f, 180f, 180f, 170f, -20f),
            Key(0f, 180f, 180f, 0f, 0f),
        ).map { it.rotated(90f) },
        farHint = "Ниже",
    ),
    Spec(
        "swings",
        path = listOf(Key(0f, 180f, 180f, 90f, 90f), Key(55f, 165f, 185f, 200f, 210f)),
        segmentMs = 700,
        wrong = listOf(Key(0f, 180f, 180f, 90f, 90f), Key(55f, 165f, 185f, 200f, 210f)).map { it.rotated(90f) },
        farHint = "Ниже",
    ),
    Spec(
        "sumo_pull",
        path = listOf(Key(30f, 130f, 190f, 180f, 180f), Key(0f, 180f, 180f, 70f, 235f)),
        segmentMs = 800,
        wrong = listOf(Key(30f, 130f, 190f, 180f, 180f), Key(0f, 180f, 180f, 70f, 235f)).map { it.rotated(90f) },
        farHint = "Выше",
    ),
    Spec(
        "row",
        path = listOf(Key(60f, 165f, 185f, 180f, 180f), Key(60f, 165f, 185f, 280f, 180f)),
        segmentMs = 700,
        wrong = listOf(STAND, Key(0f, 180f, 180f, 280f, 180f)),
        farHint = "Выше",
    ),
    Spec(
        "deadlift",
        path = listOf(Key(0f, 180f, 180f, 180f, 180f), Key(70f, 150f, 195f, 180f, 180f)),
        segmentMs = 900,
        wrong = listOf(Key(0f, 180f, 180f, 180f, 180f), Key(70f, 150f, 195f, 180f, 180f)).map { it.rotated(90f) },
        farHint = "Ниже",
    ),
    Spec(
        "goblet_squat",
        path = listOf(GOBLET, Key(25f, 95f, 200f, 165f, 25f)),
        segmentMs = 900,
        // Тот же присед, но руки опущены вдоль тела — это не присед с гирей у груди.
        wrong = listOf(STAND, Key(40f, 95f, 200f, 180f, 180f)),
        farHint = "Ниже",
    ),
    Spec(
        "reverse_lunge",
        // Видимая нога отходит назад: колено ≈ 90°, голень горизонтально.
        path = listOf(GOBLET, Key(3f, 190f, 280f, 162f, 22f)),
        segmentMs = 900,
        wrong = listOf(STAND, Key(3f, 190f, 280f, 180f, 180f)),
        farHint = "Ниже",
    ),
    Spec(
        "curl",
        path = listOf(Key(0f, 180f, 180f, 175f, 172f), Key(0f, 180f, 180f, 178f, 15f)),
        segmentMs = 900,
        wrong = listOf(Key(0f, 180f, 180f, 175f, 172f), Key(0f, 180f, 180f, 178f, 15f)).map { it.rotated(90f) },
        farHint = "Выше",
    ),
    Spec(
        "glute_bridge",
        path = listOf(BRIDGE_DOWN, Key(235f, 55f, 175f, 95f, 70f)),
        segmentMs = 900,
        wrong = listOf(STAND, CURL),
        farHint = "Выше",
    ),
)

class RepCountersTest {

    private fun counter(id: String): RepCounter =
        RepCounters.forExercise(id) ?: error("нет счётчика для $id")

    /** Прогнать проверку для всех упражнений и показать все провалы разом. */
    private fun forAll(check: (Spec) -> String?) {
        val failures = SPECS.mapNotNull { spec -> check(spec)?.let { "${spec.id}: $it" } }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun allRepExercisesHaveCounters() {
        val ids = listOf(
            "pushups", "squats", "abs", "pullups", "thruster", "swings", "sumo_pull", "row", "deadlift",
            "goblet_squat", "reverse_lunge", "curl", "glute_bridge",
        )
        for (id in ids) assertNotNull(id, RepCounters.forExercise(id))
        // Жим одной рукой снимается спереди — его проверяет PressCounterTest.
        assertEquals(ids.toSet(), SPECS.map { it.id }.toSet())
        assertTrue(RepCounters.forExercise("press") is EitherArmCounter)
        // У каждого упражнения на повторения из каталога есть счётчик.
        for (ex in com.nextlevellife.app.data.Exercises.all) assertNotNull(ex.id, RepCounters.forExercise(ex.id))
        assertNull(RepCounters.forExercise("minotaur"))
        assertNull(RepCounters.forExercise(""))
    }

    @Test
    fun fiveFullRepsCountFive() = forAll { spec ->
        val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 5, spec.segmentMs).hold(spec.rest, 1000)
        val states = counter(spec.id).feed(tl.frames)
        val last = states.last()
        when {
            last.count != 5 -> "count=${last.count}, ожидалось 5"
            !last.tracking || !last.inPosition -> "tracking=${last.tracking} inPosition=${last.inPosition}"
            !last.atStart -> "в исходном положении в конце atStart=false"
            states.none { it.inPosition && !it.atStart } -> "atStart не сбрасывался во время движения"
            states.maxOf { it.depth } < 0.99f -> "depth не дошёл до 1: ${states.maxOf { it.depth }}"
            else -> null
        }
    }

    @Test
    fun halfAmplitudeRepsDoNotCount() = forAll { spec ->
        val tl = Timeline().hold(spec.rest, 1000).reps(spec.half, 5, spec.segmentMs).hold(spec.rest, 1000)
        val states = counter(spec.id).feed(tl.frames)
        when {
            states.last().count != 0 -> "count=${states.last().count}, ожидалось 0"
            states.none { it.hint == spec.farHint } -> "не было подсказки «${spec.farHint}»: ${states.map { it.hint }.toSet()}"
            else -> null
        }
    }

    @Test
    fun notInPositionDoesNotCount() = forAll { spec ->
        val tl = Timeline().hold(spec.wrong[0], 1000).reps(spec.wrong, 5, spec.segmentMs).hold(spec.wrong[0], 1000)
        val states = counter(spec.id).feed(tl.frames)
        when {
            states.last().count != 0 -> "count=${states.last().count}, ожидалось 0"
            states.any { it.inPosition } -> "inPosition был true"
            states.any { !it.tracking } -> "человек терялся"
            states.last().hint.isEmpty() -> "нет подсказки про исходное положение"
            else -> null
        }
    }

    @Test
    fun angleNoiseGivesNoExtraReps() = forAll { spec ->
        // Шум ±3° на направлении КАЖДОГО отрезка (угол в суставе дрожит до ±6°), 10 разных зёрен.
        (1..10).firstNotNullOfOrNull { seed ->
            val rnd = Random(spec.id.hashCode() * 31 + seed)
            val reps = Timeline(noise = rnd).hold(spec.rest, 1000)
                .reps(spec.path, 5, spec.segmentMs).hold(spec.rest, 1000)
            val half = Timeline(noise = rnd).hold(spec.rest, 1000)
                .reps(spec.half, 5, spec.segmentMs).hold(spec.rest, 1000)
            val still = Timeline(noise = rnd).hold(spec.rest, 10_000)
            val repsCount = counter(spec.id).feed(reps.frames).last().count
            val halfCount = counter(spec.id).feed(half.frames).last().count
            val stillCount = counter(spec.id).feed(still.frames).last().count
            when {
                repsCount != 5 -> "зерно $seed: с шумом count=$repsCount, ожидалось 5"
                halfCount != 0 -> "зерно $seed: полуамплитуда с шумом count=$halfCount"
                stillCount != 0 -> "зерно $seed: стоя на месте с шумом count=$stillCount"
                else -> null
            }
        }
    }

    @Test
    fun personLostForTwoSecondsKeepsCount() = forAll { spec ->
        val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 3, spec.segmentMs)
        val beforeGap = tl.frames.size
        tl.empty(2000)
        val afterGap = tl.frames.size
        tl.hold(spec.rest, 500).reps(spec.path, 2, spec.segmentMs).hold(spec.rest, 1000)
        val states = counter(spec.id).feed(tl.frames)
        val gap = states.subList(beforeGap, afterGap)
        when {
            states[beforeGap - 1].count != 3 -> "до пропажи count=${states[beforeGap - 1].count}"
            gap.any { it.tracking || it.count != 3 } -> "во время пропажи: ${gap.last()}"
            states.last().count != 5 -> "в конце count=${states.last().count}, ожидалось 5"
            else -> null
        }
    }

    @Test
    fun personLostMidRepDoesNotCountHalfRep() {
        // Ушёл в нижней точке приседа, вернулся через 2 с уже стоя — это не повторение.
        val top = SPECS.first { it.id == "squats" }.path[0]
        val bottom = SPECS.first { it.id == "squats" }.path[1]
        val tl = Timeline().hold(top, 1000).move(top, bottom, 900).hold(bottom, 300).empty(2000).hold(top, 1000)
        assertEquals(0, counter("squats").feed(tl.frames).last().count)
    }

    @Test
    fun emptyFrameIsNotTracking() {
        for (spec in SPECS) {
            val st = counter(spec.id).onFrame(PoseFrame(1, 1080, 1920, emptyMap()))
            assertFalse(spec.id, st.tracking)
            assertFalse(spec.id, st.inPosition)
            assertEquals(spec.id, "Встань в кадр целиком", st.hint)
            assertEquals(spec.id, 0, st.count)
        }
    }

    @Test
    fun missingLegsHint() {
        val points = StickFigure.points(STAND).filterKeys {
            it != Joint.LEFT_ANKLE && it != Joint.RIGHT_ANKLE && it != Joint.LEFT_KNEE && it != Joint.RIGHT_KNEE
        }
        val st = counter("squats").onFrame(PoseFrame(1, 1080, 1920, points))
        assertFalse(st.tracking)
        assertEquals("Не вижу ноги", st.hint)

        val noArms = StickFigure.points(STAND).filterKeys { it != Joint.LEFT_WRIST && it != Joint.RIGHT_WRIST }
        assertEquals("Не вижу руки", counter("pushups").onFrame(PoseFrame(1, 1080, 1920, noArms)).hint)
    }

    @Test
    fun resetClearsCount() {
        val spec = SPECS.first { it.id == "squats" }
        val c = counter(spec.id)
        val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 3, spec.segmentMs).hold(spec.rest, 500)
        assertEquals(3, c.feed(tl.frames).last().count)
        c.reset()
        assertEquals(0, c.state.count)
        assertFalse(c.state.tracking)
        // После сброса счётчик снова работает с нуля.
        val tl2 = Timeline().hold(spec.rest, 1000).reps(spec.path, 2, spec.segmentMs).hold(spec.rest, 500)
        assertEquals(2, c.feed(tl2.frames.map { PoseFrame(it.timeMs + 60_000, it.width, it.height, it.points) }).last().count)
    }

    @Test
    fun tooFastRepsDoNotCount() {
        val spec = SPECS.first { it.id == "squats" }
        val top = spec.path[0]
        val bottom = spec.path[1]
        // Рывки по 3 кадра (≈0,2 с на «повторение»): сглаженный угол проходит оба порога,
        // но повторение короче 400 мс.
        val fast = Timeline().hold(top, 1000)
        repeat(10) { fast.hold(bottom, 100).hold(top, 100) }
        fast.hold(top, 1000)
        assertEquals(0, counter(spec.id).feed(fast.frames).last().count)

        // Контроль: те же рывки, но по 0,5 с — засчитываются.
        val slow = Timeline().hold(top, 1000)
        repeat(4) { slow.hold(bottom, 500).hold(top, 500) }
        assertEquals(4, counter(spec.id).feed(slow.frames).last().count)
    }

    @Test
    fun otherSideAndFacingLeftWorks() {
        val spec = SPECS.first { it.id == "squats" }
        val tl = Timeline(visible = Side.RIGHT, facingLeft = true)
            .hold(spec.rest, 1000).reps(spec.path, 5, spec.segmentMs).hold(spec.rest, 1000)
        assertEquals(5, counter(spec.id).feed(tl.frames).last().count)
    }

    @Test
    fun thrusterNeedsArmsOverhead() {
        // Полный присед, но руки только до уровня лба (локти у плеч) — не засчитывается.
        val rack = Key(0f, 180f, 180f, 170f, -20f)
        val bottom = Key(30f, 95f, 200f, 170f, -20f)
        val halfPress = Key(0f, 180f, 180f, 90f, 0f)
        val tl = Timeline().hold(rack, 1000).reps(listOf(rack, bottom, rack, halfPress), 5, 600).hold(rack, 1000)
        val states = counter("thruster").feed(tl.frames)
        assertEquals(0, states.last().count)
        assertTrue(states.any { it.hint == "До конца" })
    }

    @Test
    fun singleOutlierFrameDoesNotCount() {
        // Один кадр «в нижней точке» посреди стояния — выброс ML Kit, а не присед.
        val spec = SPECS.first { it.id == "squats" }
        val tl = Timeline().hold(spec.rest, 1000)
        repeat(5) { tl.frame(spec.path[1]); tl.hold(spec.rest, 1000) }
        assertEquals(0, counter(spec.id).feed(tl.frames).last().count)
    }

    @Test
    fun briefPostureViolationAtBottomStillCounts() {
        // В нижней точке поза на 0,4 с «ломается» (плечи ниже бёдер) — повторение всё равно засчитывается.
        val top = Key(0f, 180f, 180f, 90f, 90f)
        val bottom = Key(40f, 95f, 200f, 90f, 90f)
        val broken = bottom.copy(torso = 120f)
        val tl = Timeline().hold(top, 1000)
        repeat(3) { tl.move(top, bottom, 600).hold(broken, 400).move(bottom, top, 600) }
        tl.hold(top, 1000)
        val states = counter("squats").feed(tl.frames)
        assertEquals(3, states.last().count)
        assertTrue("поза должна удерживаться", states.drop(2).all { it.inPosition })
    }

    @Test
    fun thrusterConditionsLatchAcrossFrames() {
        // Руки над головой и выпрямленные колени — не в одном кадре, а с разницей ~0,1 с.
        val rack = Key(0f, 180f, 180f, 170f, -20f)
        val bottom = Key(30f, 95f, 200f, 170f, -20f)
        val armsUpKneesBent = Key(0f, 165f, 195f, 0f, 0f) // колено 150°, руки над головой
        // колено 180°, руки уже опущены к подбородку (запястья ниже носа — далеко за допуском пары)
        val kneesStraightArmsLower = Key(0f, 180f, 180f, 120f, 30f)
        val tl = Timeline().hold(rack, 1000)
        repeat(3) {
            tl.move(rack, bottom, 600).move(bottom, armsUpKneesBent, 600).hold(armsUpKneesBent, 100)
                .hold(kneesStraightArmsLower, 300).move(kneesStraightArmsLower, rack, 400)
        }
        tl.hold(rack, 1000)
        assertEquals(3, counter("thruster").feed(tl.frames).last().count)
    }

    @Test
    fun hintsAreEmptyDuringGoodReps() {
        val spec = SPECS.first { it.id == "pushups" }
        val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 3, spec.segmentMs).hold(spec.rest, 1000)
        val states = counter(spec.id).feed(tl.frames)
        assertTrue(states.drop(5).all { it.hint.isEmpty() })
    }

    @Test
    fun deepRepsGiveNoFinishHint() {
        // Глубокое повторение: порог пройден задолго до нижней точки (локоть ~50°, как на записи с телефона).
        // Продолжение вниз после порога — не «недожал», подсказки быть не должно.
        val top = Key(75f, 255f, 255f, 180f, 180f)
        val deep = Key(85f, 265f, 265f, 280f, 150f)
        val tl = Timeline().hold(top, 1000)
        repeat(5) { tl.move(top, deep, 900).move(deep, top, 900) }
        tl.hold(top, 1000)
        val states = counter("pushups").feed(tl.frames)
        assertEquals(5, states.last().count)
        assertTrue("ложная подсказка: ${states.map { it.hint }.toSet()}", states.none { it.hint == "До конца" })
    }

    @Test
    fun risingHalfwayThenGoingBackDownGivesFinishHint() {
        // Поднялся наполовину и снова пошёл вниз, не выпрямив руки, — подсказка «До конца» нужна.
        val top = Key(75f, 255f, 255f, 180f, 180f)
        val deep = Key(85f, 265f, 265f, 280f, 150f)
        val half = top.lerp(deep, 0.45f)
        val tl = Timeline().hold(top, 1000)
            .move(top, deep, 900).move(deep, half, 700).move(half, deep, 700).move(deep, top, 900)
            .hold(top, 1000)
        val states = counter("pushups").feed(tl.frames)
        assertEquals(1, states.last().count)
        assertTrue("нет подсказки: ${states.map { it.hint }.toSet()}", states.any { it.hint == "До конца" })
    }
}
