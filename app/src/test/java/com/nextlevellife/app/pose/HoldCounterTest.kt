package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Планка на предплечьях, вид строго сбоку. Голова — вперёд (+x), тело поднято к плечам на 12°:
 * таз и колени 180°, плечо вертикально вниз к локтю, предплечье по полу вперёд (локоть 90°).
 */
internal val PLANK = Key(78f, 258f, 258f, 180f, 90f)

/** Таз провис: угол в тазу 156°. */
internal val PLANK_SAG = Key(66f, 270f, 270f, 180f, 90f)

/** Таз задран: угол в тазу 154°. */
internal val PLANK_PIKE = Key(92f, 246f, 246f, 180f, 90f)

/** Колени согнуты (≈ 142°), линия плечо–лодыжка ≈ 24° от горизонтали. */
internal val PLANK_BENT_KNEES = Key(78f, 258f, 220f, 180f, 90f)

/** Таз чуть провис: угол 164°, отклонение ≈ 0,18 торса — между порогами входа и удержания. */
internal val PLANK_SLIGHT_SAG = Key(62f, 258f, 258f, 180f, 90f)

class HoldCounterTest {

    private fun plank(): RepCounter = RepCounters.forExercise("plank") ?: error("нет счётчика планки")

    private fun run(tl: Timeline): List<RepState> = plank().feed(tl.frames)

    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.2f с", ms / 1000.0)

    @Test
    fun plankCounterContract() {
        val c = RepCounters.forExercise("plank")
        assertNotNull(c)
        assertEquals(CountKind.HOLD, c!!.kind)
        for (id in SPECS.map { it.id }) assertEquals(id, CountKind.REPS, RepCounters.forExercise(id)!!.kind)
        assertEquals(RepState(), c.state)
    }

    @Test
    fun flatPlankThirtySecondsCountsThirty() {
        for ((name, tl) in listOf(
            "слева" to Timeline(),
            "справа, лицом влево" to Timeline(visible = Side.RIGHT, facingLeft = true),
        )) {
            tl.hold(PLANK, 30_000)
            val states = run(tl)
            val last = states.last()
            assertEquals(name, 30_000.0, last.heldMs.toDouble(), 500.0)
            assertEquals(name, last.heldMs, last.currentSegmentMs)
            assertEquals(name, 0L, last.pendingMs)
            assertEquals(name, last.heldMs, last.shownHeldMs)
            assertEquals(name, 0, last.count)
            assertTrue(name, last.tracking && last.inPosition && last.holding)
            assertEquals(name, "", last.hint)
            // Пока отрезок короче 10 с — время видно на экране, но ещё не засчитано.
            val at5 = states[150]
            assertEquals(name, 0L, at5.heldMs)
            assertTrue(name + ": " + sec(at5.pendingMs), at5.pendingMs in 4_500L..5_100L)
            assertEquals(name, at5.pendingMs, at5.shownHeldMs)
            // Экранное время не убывает на ровной планке.
            assertTrue(name, states.zipWithNext().all { (a, b) -> b.shownHeldMs >= a.shownHeldMs })
        }
    }

    @Test
    fun badPlanksGiveNothing() {
        val cases = listOf(
            Triple("провис таза", PLANK_SAG, "Таз выше"),
            Triple("таз задран", PLANK_PIKE, "Таз ниже"),
            Triple("согнуты колени", PLANK_BENT_KNEES, "Выпрями ноги"),
        )
        for ((name, key, hint) in cases) {
            val states = run(Timeline().hold(key, 30_000))
            val last = states.last()
            assertEquals(name, 0L, last.heldMs)
            assertTrue(name, states.all { it.shownHeldMs == 0L && !it.inPosition && !it.holding })
            assertTrue(name, last.tracking)
            assertEquals(name, hint, last.hint)
        }
    }

    @Test
    fun halfSecondDropoutKeepsOneSegment() {
        // Пустые кадры (человек не найден) и кадры, которых нет вовсе.
        for ((name, gap) in listOf<Pair<String, (Timeline) -> Unit>>(
            "пустые кадры" to { it.empty(500) },
            "нет кадров" to { it.skip(500) },
        )) {
            val tl = Timeline().hold(PLANK, 20_000)
            gap(tl)
            tl.hold(PLANK, 20_000)
            val states = run(tl)
            val last = states.last()
            // 40 с позы минус провал (он не оплачивается) — 2 кадра по ~33 мс.
            assertEquals(name + ": " + sec(last.heldMs), 39_930.0, last.heldMs.toDouble(), 200.0)
            assertEquals("$name: один отрезок", last.heldMs, last.currentSegmentMs)
        }
    }

    @Test
    fun longDropoutSplitsSegments() {
        // Провал 1 с (> 0,7 с) — два отрезка по 20 с, оба засчитаны.
        val states = run(Timeline().hold(PLANK, 20_000).empty(1_000).hold(PLANK, 20_000))
        val last = states.last()
        assertEquals(sec(last.heldMs), 39_930.0, last.heldMs.toDouble(), 200.0)
        assertEquals(sec(last.currentSegmentMs), 19_960.0, last.currentSegmentMs.toDouble(), 100.0)
    }

    @Test
    fun segmentsShorterThanTenSecondsAreNotCounted() {
        val tl = Timeline().hold(PLANK, 8_000)
        val gapFrom = tl.frames.size
        tl.empty(2_000)
        val gapTo = tl.frames.size
        tl.hold(PLANK, 8_000)
        val states = run(tl)
        assertTrue("до пропажи время шло на экране", states[gapFrom - 1].shownHeldMs > 7_000)
        assertEquals(0L, states[gapFrom - 1].heldMs)
        val gap = states.subList(gapFrom, gapTo)
        assertTrue(gap.all { !it.tracking && it.heldMs == 0L })
        assertEquals("после 0,7 с провала отрезок оборвался", 0L, gap.last().shownHeldMs)
        val last = states.last()
        assertEquals("оба отрезка короче 10 с — засчитано 0", 0L, last.heldMs)
        assertTrue(sec(last.pendingMs), last.pendingMs in 7_000L..8_000L)

        // Отрезок, дотянувший до 10 с, остаётся засчитанным, даже если следующий короткий.
        val tl2 = Timeline().hold(PLANK, 12_000).empty(2_000).hold(PLANK, 5_000)
        val last2 = run(tl2).last()
        assertEquals(sec(last2.heldMs), 12_000.0, last2.heldMs.toDouble(), 300.0)
        assertTrue(last2.pendingMs in 4_000L..5_000L)
        assertEquals(last2.heldMs + last2.pendingMs, last2.shownHeldMs)
    }

    @Test
    fun angleNoiseDoesNotBreakSegment() {
        // Шум ±5° на направлении каждого отрезка: углы в суставах дрожат до ±10°.
        for (seed in 1..10) {
            val tl = Timeline(noise = Random(seed * 101), noiseDeg = 5f).hold(PLANK, 30_000)
            val last = run(tl).last()
            assertTrue("зерно $seed: ${sec(last.heldMs)}", last.heldMs in 29_000L..30_000L)
            assertEquals("зерно $seed: один отрезок", last.heldMs, last.currentSegmentMs)
        }
    }

    @Test
    fun hysteresisKeepsSlightSagButDoesNotEnterWithIt() {
        // С чуть провисшим тазом войти нельзя…
        val enter = run(Timeline().hold(PLANK_SLIGHT_SAG, 15_000))
        assertTrue(enter.none { it.inPosition })
        assertEquals("Таз выше", enter.last().hint)
        assertEquals(0L, enter.last().shownHeldMs)

        // …но если вошёл ровно, лёгкий провис отрезок не рвёт и время идёт.
        val tl = Timeline().hold(PLANK, 2_000).move(PLANK, PLANK_SLIGHT_SAG, 500).hold(PLANK_SLIGHT_SAG, 15_000)
        val last = run(tl).last()
        assertTrue(last.inPosition && last.holding)
        assertEquals(sec(last.heldMs), 17_500.0, last.heldMs.toDouble(), 300.0)

        // Сильный провис после входа: время сразу останавливается, через 0,7 с отрезок завершён.
        val sag = Timeline().hold(PLANK, 12_000)
        val sagFrom = sag.frames.size
        sag.hold(PLANK_SAG, 3_000)
        val st = run(sag)
        assertEquals("Таз выше", st[sagFrom].hint)
        assertTrue(st[sagFrom].inPosition && !st[sagFrom].holding)
        assertEquals(st[sagFrom - 1].shownHeldMs, st[sagFrom + 15].shownHeldMs)
        assertTrue(!st.last().inPosition)
        assertEquals(sec(st.last().heldMs), 12_000.0, st.last().heldMs.toDouble(), 100.0)
    }

    @Test
    fun notPlankGivesNoHold() {
        val pushups = SPECS.first { it.id == "pushups" }
        val cases = listOf(
            "стоит, руки вниз" to Timeline().hold(STAND, 15_000),
            "стоит, локти согнуты под плечами" to Timeline().hold(Key(0f, 180f, 180f, 180f, 90f), 15_000),
            "отжимания" to Timeline().hold(pushups.rest, 1_000).reps(pushups.path, 10, pushups.segmentMs),
            "упор на прямых руках" to Timeline().hold(pushups.rest, 15_000),
            "лёжа на животе, руки вдоль тела" to Timeline().hold(Key(90f, 270f, 270f, 270f, 270f), 15_000),
            "лёжа на животе на локтях, таз на полу" to Timeline().hold(Key(60f, 270f, 270f, 180f, 90f), 15_000),
            "лёжа на животе, руки под подбородком" to Timeline().hold(Key(90f, 270f, 270f, 100f, 200f), 15_000),
            "лёжа на спине" to Timeline().hold(Key(270f, 90f, 90f, 90f, 90f), 15_000),
        )
        val failures = cases.mapNotNull { (name, tl) ->
            val states = run(tl)
            when {
                states.any { it.inPosition || it.holding || it.shownHeldMs != 0L } -> "$name: было удержание"
                states.last().hint.isEmpty() -> "$name: нет подсказки"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun twelveFpsCountsFully() {
        val last = run(Timeline(stepMs = 1000.0 / 12).hold(PLANK, 30_000)).last()
        assertEquals(sec(last.heldMs), 30_000.0, last.heldMs.toDouble(), 500.0)
    }

    @Test
    fun framesRarerThanQuarterSecondAreNotPaid() {
        // 3 к/с: интервалы 333 мс > 250 мс — поза видна, но время не идёт.
        val states = run(Timeline(stepMs = 1000.0 / 3).hold(PLANK, 30_000))
        assertTrue("поза распознана", states.last().inPosition)
        assertTrue(states.all { it.shownHeldMs == 0L })
    }

    @Test
    fun staleJointsAreNotPaid() {
        // 12 с ровно, затем 10 с — в каждом втором кадре лодыжка ненадёжна (уверенность 0,3):
        // сустав берётся из прошлого кадра (устарел), такие кадры не оплачиваются,
        // а с ними и интервалы до соседних хороших кадров.
        val tl = Timeline().hold(PLANK, 12_000)
        val from = tl.frames.size
        repeat(300) { i ->
            tl.frame(PLANK) { p ->
                if (i % 2 == 0) p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.3f)
            }
        }
        val states = run(tl)
        val last = states.last()
        assertTrue("человек виден", states.subList(from, states.size).all { it.tracking })
        assertTrue("отрезок не оборвался", last.inPosition)
        assertEquals(sec(last.heldMs), 12_000.0, last.heldMs.toDouble(), 100.0)
        assertEquals(states[from - 1].heldMs, last.heldMs)

        // Лодыжка ненадёжна всё время — дольше 250 мс её нет вовсе: удержания нет.
        val bad = Timeline()
        repeat(450) { bad.frame(PLANK) { p -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.3f) } }
        val badStates = run(bad)
        assertTrue(badStates.all { it.shownHeldMs == 0L })
        assertEquals("Не вижу ноги", badStates.last().hint)
    }

    @Test
    fun realisticStreamAt12Fps() {
        // Поток «как у ML Kit»: шум, три четверти, выпадающие суставы (12 % кадров), дыхание уверенности.
        // Кадр с выпавшим нужным суставом стоит двух интервалов, поэтому засчитывается меньше 30 с.
        val results = (1..10).map { seed ->
            val stream = RealisticStream(seed * 7919, 12.0).hold(PLANK, 30_000)
            plank().feed(stream.frames).last()
        }
        println("== планка 30 с, реалистичный поток 12 к/с: " + results.joinToString(" ") { sec(it.heldMs) })
        for (r in results) {
            assertTrue(sec(r.heldMs), r.heldMs in 22_000L..30_000L)
            assertEquals("один отрезок", r.heldMs, r.currentSegmentMs)
        }
    }

    @Test
    fun hintsWhenNotSeen() {
        val c = plank()
        assertEquals("Ляг в планку боком к телефону", c.onFrame(PoseFrame(1, 1080, 1920, emptyMap())).hint)
        val noLegs = StickFigure.points(PLANK).filterKeys {
            it != Joint.LEFT_ANKLE && it != Joint.RIGHT_ANKLE && it != Joint.LEFT_KNEE && it != Joint.RIGHT_KNEE
        }
        val st = plank().onFrame(PoseFrame(1, 1080, 1920, noLegs))
        assertTrue(!st.tracking)
        assertEquals("Не вижу ноги", st.hint)
        val noArms = StickFigure.points(PLANK).filterKeys { it != Joint.LEFT_WRIST && it != Joint.RIGHT_WRIST }
        assertEquals("Не вижу руки", plank().onFrame(PoseFrame(1, 1080, 1920, noArms)).hint)
        val elbowsForward = run(Timeline().hold(Key(78f, 258f, 258f, 135f, 90f), 3_000)).last()
        assertEquals("Локти под плечи", elbowsForward.hint)
        assertEquals("Ляг в планку боком к телефону", run(Timeline().hold(STAND, 2_000)).last().hint)
    }

    @Test
    fun resetClearsHold() {
        val c = plank()
        assertTrue(c.feed(Timeline().hold(PLANK, 15_000).frames).last().heldMs > 14_000)
        c.reset()
        assertEquals(RepState(), c.state)
        val again = Timeline().hold(PLANK, 12_000).frames.map { PoseFrame(it.timeMs + 60_000, it.width, it.height, it.points) }
        val last = c.feed(again).last()
        assertEquals(sec(last.heldMs), 12_000.0, last.heldMs.toDouble(), 300.0)
    }
}
