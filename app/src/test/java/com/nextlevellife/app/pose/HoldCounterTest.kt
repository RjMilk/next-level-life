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

/** Таз чуть провис: угол 166° (вход проходит), отклонение ≈ 0,155 торса. */
internal val PLANK_166 = Key(64f, 258f, 258f, 180f, 90f)

/** Сильный провис: угол в тазу 150°. */
internal val PLANK_150 = Key(48f, 258f, 258f, 180f, 90f)

class HoldCounterTest {

    private val startOver = "Опустись на колени и снова прими планку"
    private val maxReached = "Максимум за подход — 3 минуты"

    private fun plank(): RepCounter = RepCounters.forExercise("plank") ?: error("нет счётчика планки")

    private fun run(tl: Timeline): List<RepState> = plank().feed(tl.frames)

    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.2f с", ms / 1000.0)

    /** Живой вход: человек сначала стоит перед телефоном (не в планке), потом ложится. */
    private fun live(tl: Timeline = Timeline(), standMs: Long = 1_000) = tl.hold(STAND, standMs)

    /** Кадр из готовых положений суставов видимой (левой) стороны; правая — с уверенностью [farConf]. */
    private fun frameOf(t: Long, pos: Map<Limb, Pair<Float, Float>>, farConf: Float = 0.3f): PoseFrame {
        val pts = HashMap<Joint, PosePoint>()
        for ((limb, p) in pos) {
            if (limb == Limb.NOSE) {
                pts[Joint.NOSE] = PosePoint(p.first, p.second, 0.95f)
                continue
            }
            pts[Side.LEFT.joint(limb)] = PosePoint(p.first, p.second, 0.95f)
            pts[Side.RIGHT.joint(limb)] = PosePoint(p.first + 6f, p.second + 4f, farConf)
        }
        return PoseFrame(t, 1080, 1920, pts)
    }

    private fun signals(frame: PoseFrame): Signals {
        val located = BodyLocator.locate(frame, HoldConfigs.byId.getValue("plank").required, emptySet())
        return Signals.measure((located as Located.Found).body)
    }

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
            live(tl)
            val pre = tl.frames.size
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
            val at5 = states[pre + 150]
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
            val states = run(live().hold(key, 30_000))
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
            val tl = live().hold(PLANK, 20_000)
            gap(tl)
            tl.hold(PLANK, 20_000)
            val states = run(tl)
            val last = states.last()
            // 40 с позы минус провал (он длиннее 250 мс и не оплачивается).
            assertEquals(name + ": " + sec(last.heldMs), 39_930.0, last.heldMs.toDouble(), 200.0)
            assertEquals("$name: один отрезок", last.heldMs, last.currentSegmentMs)
        }
    }

    @Test
    fun longDropoutEndsSegmentAndNeedsNewTransition() {
        // Провал 1 с (> 0,7 с): отрезок завершён. Вернулся сразу в планке — это мог быть и снимок:
        // второй отрезок не начинается, пока человек не выйдет из планки и не войдёт снова.
        val states = run(live().hold(PLANK, 20_000).empty(1_000).hold(PLANK, 20_000))
        val last = states.last()
        assertEquals(sec(last.heldMs), 19_970.0, last.heldMs.toDouble(), 100.0)
        assertTrue(!last.inPosition && last.tracking)
        assertEquals(startOver, last.hint)

        // Встал на колени (здесь — встал) и снова лёг — оба отрезка засчитаны.
        val again = run(live().hold(PLANK, 20_000).empty(1_000).hold(STAND, 1_000).hold(PLANK, 20_000)).last()
        assertEquals(sec(again.heldMs), 39_930.0, again.heldMs.toDouble(), 200.0)
        assertEquals(sec(again.currentSegmentMs), 19_960.0, again.currentSegmentMs.toDouble(), 100.0)
    }

    @Test
    fun segmentsShorterThanTenSecondsAreNotCounted() {
        val tl = live().hold(PLANK, 8_000)
        val gapFrom = tl.frames.size
        tl.empty(2_000)
        val gapTo = tl.frames.size
        live(tl).hold(PLANK, 8_000)
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
        val tl2 = live().hold(PLANK, 12_000).empty(2_000)
        live(tl2).hold(PLANK, 5_000)
        val last2 = run(tl2).last()
        assertEquals(sec(last2.heldMs), 12_000.0, last2.heldMs.toDouble(), 300.0)
        assertTrue(last2.pendingMs in 4_000L..5_000L)
        assertEquals(last2.heldMs + last2.pendingMs, last2.shownHeldMs)
    }

    @Test
    fun angleNoiseDoesNotBreakSegment() {
        // Шум ±5° на направлении каждого отрезка: углы в суставах дрожат до ±10°.
        for (seed in 1..10) {
            val tl = live(Timeline(noise = Random(seed * 101), noiseDeg = 5f)).hold(PLANK, 30_000)
            val last = run(tl).last()
            assertTrue("зерно $seed: ${sec(last.heldMs)}", last.heldMs in 29_000L..30_000L)
            assertEquals("зерно $seed: один отрезок", last.heldMs, last.currentSegmentMs)
        }
    }

    @Test
    fun hysteresisKeepsSlightSagButDoesNotEnterWithIt() {
        // С чуть провисшим тазом войти нельзя…
        val enter = run(live().hold(PLANK_SLIGHT_SAG, 15_000))
        assertTrue(enter.none { it.inPosition })
        assertEquals("Таз выше", enter.last().hint)
        assertEquals(0L, enter.last().shownHeldMs)

        // …но если вошёл ровно, лёгкий провис отрезок не рвёт и время идёт.
        val tl = live().hold(PLANK, 2_000).move(PLANK, PLANK_SLIGHT_SAG, 500).hold(PLANK_SLIGHT_SAG, 15_000)
        val last = run(tl).last()
        assertTrue(last.inPosition && last.holding)
        assertEquals(sec(last.heldMs), 17_500.0, last.heldMs.toDouble(), 300.0)

        // Сильный провис после входа: время сразу останавливается, через 0,7 с отрезок завершён.
        val sag = live().hold(PLANK, 12_000)
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
    fun hipAngleIsTheMainCriterion() {
        // 163°, отклонение таза ≈ 0,17: длинные ноги (2 торса) — так отклонение в долях масштаба меньше.
        val hip = Pair(StickFigure.HIP_X, StickFigure.HIP_Y)
        val k = 2.0f / (StickFigure.THIGH + StickFigure.SHIN)
        val pos163 = StickFigure.positions(Key(61f, 258f, 258f, 180f, 90f)).mapValues { (limb, p) ->
            if (limb == Limb.KNEE || limb == Limb.ANKLE) {
                Pair(hip.first + (p.first - hip.first) * k, hip.second + (p.second - hip.second) * k)
            } else {
                p
            }
        }
        val s163 = signals(frameOf(0, pos163))
        assertEquals(163f, s163.hipAngle, 0.2f)
        assertEquals(-0.17f, s163.hipOffLine, 0.01f)

        // Вошёл ровно, потом таз опустился до 163° — удержание продолжается.
        val tl = live().hold(PLANK, 2_000)
        val from = tl.frames.size
        val frames = tl.frames + (0 until 450).map { i -> frameOf(tl.nextTimeMs + (i * Timeline.STEP_MS).toLong(), pos163) }
        val st = plank().feed(frames)
        assertTrue(st.subList(from, st.size).all { it.inPosition && it.holding })
        assertEquals(sec(st.last().heldMs), 17_000.0, st.last().heldMs.toDouble(), 300.0)

        // Провис до 150° — время останавливается, отрезок завершается.
        val sag = run(live().hold(PLANK, 12_000).hold(PLANK_150, 3_000)).last()
        assertTrue(!sag.inPosition && !sag.holding)
        assertEquals("Таз выше", sag.hint)
        assertEquals(sec(sag.heldMs), 12_000.0, sag.heldMs.toDouble(), 100.0)

        // 166° с отклонением 0,155: раньше вход не пускало отклонение (> 0,15), теперь решает угол.
        val s166 = signals(PoseFrame(0, 1080, 1920, StickFigure.points(PLANK_166)))
        assertEquals(166f, s166.hipAngle, 0.2f)
        assertTrue("отклонение ${s166.hipOffLine}", s166.hipOffLine < -0.15f)
        val enter166 = run(live().hold(PLANK_166, 15_000)).last()
        assertTrue(enter166.inPosition && enter166.holding)
        assertEquals(sec(enter166.heldMs), 15_000.0, enter166.heldMs.toDouble(), 300.0)
    }

    @Test
    fun lyingOnTheFloorIsNotPlank() {
        // Контрпример рецензента: лежит на животе, локоть чуть ниже плеча, предплечье по полу.
        val lying = mapOf(
            Limb.SHOULDER to Pair(400f, 500f), Limb.HIP to Pair(300f, 500f), Limb.KNEE to Pair(210f, 500f),
            Limb.ANKLE to Pair(125f, 500f), Limb.ELBOW to Pair(400f, 510f), Limb.WRIST to Pair(455f, 510f),
            Limb.NOSE to Pair(430f, 490f),
        )
        val sig = signals(frameOf(0, lying))
        assertEquals(180f, sig.hipAngle, 0.5f)
        assertEquals(90f, sig.elbow, 1f)
        assertTrue("плечо над локтем ${sig.shoulderOverElbow}", sig.shoulderOverElbow < 0.2f)

        val tl = live()
        val frames = tl.frames + (0 until 900).map { i -> frameOf(tl.nextTimeMs + (i * Timeline.STEP_MS).toLong(), lying) }
        val st = plank().feed(frames)
        assertTrue(st.none { it.inPosition || it.holding || it.shownHeldMs != 0L })
        assertTrue(st.last().tracking)
        assertEquals("Поднимись на предплечья", st.last().hint)
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
            val states = run(live().also { it.frames.addAll(tl.frames.map { f -> f.shifted(5_000) }) })
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
        val last = run(live(Timeline(stepMs = 1000.0 / 12)).hold(PLANK, 30_000)).last()
        assertEquals(sec(last.heldMs), 30_000.0, last.heldMs.toDouble(), 500.0)
    }

    @Test
    fun framesRarerThanQuarterSecondAreNotPaid() {
        // 3 к/с: интервалы 333 мс > 250 мс — непрерывность позы не подтверждается: входа нет, время не идёт.
        val states = run(live(Timeline(stepMs = 1000.0 / 3)).hold(PLANK, 30_000))
        assertTrue(states.none { it.inPosition })
        assertTrue(states.all { it.shownHeldMs == 0L })
    }

    @Test
    fun entryRestartsAfterGap() {
        // Кадр планки, 400 мс кадров нет, ещё 400 мс планки: вместе дольше входа (0,5 с), но вход
        // начинается заново после разрыва, поэтому в позу ещё не вошёл.
        val tl = live().hold(PLANK, 33)
        tl.skip(400)
        tl.hold(PLANK, 400)
        val states = run(tl)
        assertTrue(states.none { it.inPosition })
        // Ещё полсекунды — вошёл; разрыв в отрезок не попал.
        val tl2 = live().hold(PLANK, 33)
        tl2.skip(400)
        tl2.hold(PLANK, 1_000)
        val last2 = run(tl2).last()
        assertTrue(last2.inPosition)
        assertTrue(sec(last2.currentSegmentMs), last2.currentSegmentMs in 900L..1_000L)

        // Пример рецензента: два хороших кадра с паузой в минуту — не удержание.
        val tl3 = live().hold(PLANK, 33)
        tl3.skip(60_000)
        tl3.hold(PLANK, 33)
        assertTrue(run(tl3).none { it.inPosition })
    }

    @Test
    fun timeRollbackDoesNotPayTwice() {
        val c = plank()
        val tl = live().hold(PLANK, 12_000)
        c.feed(tl.frames)
        val before = c.state
        assertTrue(before.holding)
        val last = tl.frames.last()
        // Метки 1000, 1100, 1000, 1100, … — каждые 100 мс «повторяются» сто раз.
        val times = listOf(100L, 200L) + (1..200).map { if (it % 2 == 1) 100L else 200L }
        val states = c.feed(times.map { PoseFrame(last.timeMs + it, last.width, last.height, last.points) })
        val after = c.state
        assertEquals(sec(after.heldMs - before.heldMs), 200L, after.heldMs - before.heldMs)
        assertTrue("отброшенный кадр состояние не меняет", states.drop(2).all { it == states[1] })
        // Кадр с той же меткой тоже отбрасывается.
        assertEquals(after, c.onFrame(PoseFrame(last.timeMs + 200, last.width, last.height, emptyMap())))
    }

    @Test
    fun unknownFramesInShortGapsArePaid() {
        // 12 с ровно, затем 10 с — в каждом втором кадре лодыжка ненадёжна (уверенность 0,3), другой
        // стороны не видно: такие кадры неизвестные, а разрыв между хорошими 67 мс — оплачивается.
        val tl = live().hold(PLANK, 12_000)
        val from = tl.frames.size
        repeat(300) { i ->
            tl.frame(PLANK) { p ->
                if (i % 2 == 0) p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.3f)
            }
        }
        val states = run(tl)
        val last = states.last()
        assertTrue("человек виден", states.subList(from, states.size).all { it.tracking })
        assertEquals(sec(last.heldMs), 22_000.0, last.heldMs.toDouble(), 200.0)
        assertEquals("один отрезок", last.heldMs, last.currentSegmentMs)

        // Неизвестных кадров подряд дольше 250 мс: этот разрыв не оплачивается, но отрезок цел.
        val tl2 = live().hold(PLANK, 12_000)
        repeat(10) { tl2.frame(PLANK) { p -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.3f) } }
        tl2.hold(PLANK, 5_000)
        val last2 = run(tl2).last()
        // Кадры планки занимают 519 интервалов (17 300 мс), из них разрыв 11 интервалов (367 мс) не оплачен.
        assertEquals(sec(last2.heldMs), 16_933.0, last2.heldMs.toDouble(), 100.0)
        assertEquals("один отрезок", last2.heldMs, last2.currentSegmentMs)

        // Лодыжка ненадёжна всё время — дольше 250 мс её нет вовсе: удержания нет.
        val bad = live()
        repeat(450) { bad.frame(PLANK) { p -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.3f) } }
        val badStates = run(bad)
        assertTrue(badStates.all { it.shownHeldMs == 0L })
        assertEquals("Не вижу ноги", badStates.last().hint)
    }

    @Test
    fun badPoseBetweenGoodFramesIsNeverPaid() {
        // Хороший — провис — хороший, разрыв 67 мс: провис не оплачивается (в отличие от неизвестного кадра).
        val tl = live().hold(PLANK, 12_000)
        repeat(150) { tl.hold(PLANK, 33).hold(PLANK_SAG, 33) }
        val last = run(tl).last()
        assertTrue(last.inPosition)
        assertEquals(sec(last.heldMs), 12_000.0, last.heldMs.toDouble(), 100.0)
    }

    @Test
    fun switchesToOtherSideAtOnceWhenCurrentIsUnknown() {
        // Обе стороны видны уверенно (дальняя поднята до 0,9).
        val both: (MutableMap<Joint, PosePoint>) -> Unit = { p ->
            for (j in Joint.entries) p[j]?.let { if (it.confidence < 0.5f) p[j] = it.copy(confidence = 0.9f) }
        }
        fun hide(p: MutableMap<Joint, PosePoint>, side: Side) {
            for (limb in Limb.entries) if (limb != Limb.NOSE) p[side.joint(limb)] = p.getValue(side.joint(limb)).copy(confidence = 0.3f)
        }
        val tl = Timeline()
        repeat(30) { tl.frame(STAND, both) }
        repeat(450) { tl.frame(PLANK, both) }
        val from = tl.frames.size
        repeat(300) { i ->
            tl.frame(PLANK) { p ->
                both(p)
                when (i % 10) {
                    // Правой стороны не видно (выбрана левая — ничего не меняется).
                    9 -> hide(p, Side.RIGHT)
                    // У левой пропала лодыжка → сразу на правую. Правая в прошлом кадре не была видна:
                    // интервал «левая → правая» не оплачивается.
                    0 -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(confidence = 0.2f)
                    // Правая (выбранная) пропала → сразу на левую. Левая и в прошлом кадре была хорошей —
                    // это её собственная пара кадров, интервал оплачивается.
                    4 -> hide(p, Side.RIGHT)
                }
            }
        }
        val states = run(tl)
        // На кадре со сменой стороны поза оценена по другой стороне — время не замирает.
        assertTrue(states.subList(from, states.size).all { it.holding })
        // Не оплачены 29 интервалов по ~33 мс (при i = 10, 20, …, 290; при i = 0 правая была видна кадром раньше).
        val last = states.last()
        assertEquals(sec(last.heldMs), 25_000.0 - 29 * 33.3, last.heldMs.toDouble(), 150.0)
        assertEquals("один отрезок", last.heldMs, last.currentSegmentMs)
    }

    @Test
    fun outOfFrameJointsAreUnknown() {
        // Лодыжка за правым краем кадра (130 % ширины) с высокой уверенностью — не сустав.
        val far = run(live().also { tl ->
            repeat(450) { tl.frame(PLANK) { p -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(x = 1080f * 1.3f) } }
        })
        assertTrue(far.all { it.shownHeldMs == 0L && !it.inPosition })
        assertEquals("Не вижу ноги", far.last().hint)

        // Нечисловые координаты.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val st = run(live().also { tl ->
                repeat(450) { tl.frame(PLANK) { p -> p[Joint.LEFT_ANKLE] = p.getValue(Joint.LEFT_ANKLE).copy(y = bad) } }
            })
            assertTrue("$bad", st.all { it.shownHeldMs == 0L && !it.inPosition })
        }

        // Чуть за краем (−3 % ширины) — в пределах допуска 5 %, засчитывается.
        val ankleX = StickFigure.positions(PLANK).getValue(Limb.ANKLE).first
        val dx = -ankleX - 0.03f * 1080f
        val edge = run(live().also { tl ->
            repeat(450) {
                tl.frame(PLANK) { p -> for ((j, v) in p.toMap()) p[j] = v.copy(x = v.x + dx) }
            }
        }).last()
        assertEquals(sec(edge.heldMs), 15_000.0, edge.heldMs.toDouble(), 300.0)
    }

    @Test
    fun realisticStreamAt12Fps() {
        // Поток «как у ML Kit»: шум, три четверти, выпадающие суставы (12 % кадров), дыхание уверенности.
        // Выпал сустав на выбранной стороне — поза сразу оценивается по другой (она видна и кадром раньше,
        // поэтому интервал не теряется). Было 24–27 с, когда кадр с выпавшим суставом не оплачивался.
        val results = (1..10).map { seed ->
            val stream = RealisticStream(seed * 7919, 12.0).hold(STAND, 1_500).hold(PLANK, 30_000)
            plank().feed(stream.frames).last()
        }
        println("== планка 30 с, реалистичный поток 12 к/с: " + results.joinToString(" ") { sec(it.heldMs) })
        for (r in results) {
            assertTrue(sec(r.heldMs), r.heldMs in 28_000L..30_500L)
            assertEquals("один отрезок", r.heldMs, r.currentSegmentMs)
        }
    }

    @Test
    fun photoOfPlankIsNotPaid() {
        // Снимок правильной планки с первого кадра: перехода «не в планке → в планку» не было.
        for ((name, tl) in listOf(
            "ровный снимок" to Timeline().hold(PLANK, 60_000),
            "снимок с дрожью распознавания" to Timeline(noise = Random(7), noiseDeg = 3f).hold(PLANK, 60_000),
        )) {
            val states = run(tl)
            assertTrue(name, states.all { it.shownHeldMs == 0L && !it.inPosition && !it.holding })
            assertTrue(name, states.last().tracking)
            assertEquals(name, startOver, states.last().hint)
        }
        // Снимок в «реалистичном» потоке: шум, три четверти, выпадающие суставы, дыхание уверенности.
        for (seed in 1..10) {
            val states = plank().feed(RealisticStream(seed * 31, 12.0).hold(PLANK, 60_000).frames)
            assertTrue("зерно $seed", states.all { it.shownHeldMs == 0L && !it.inPosition })
        }

        // Стоял, пропал дольше 1,5 с, появился уже в планке — снова нужен переход.
        assertTrue(run(live().empty(2_000).hold(PLANK, 30_000)).all { it.shownHeldMs == 0L })

        // Стоял, потом 20 с «планка» на 3 к/с (вход не подтверждается), затем нормальный поток:
        // с последнего «не в планке» прошло больше 15 с — входа нет.
        val stale = live()
        repeat(60) {
            stale.frame(PLANK)
            stale.skip(300)
        }
        stale.hold(PLANK, 30_000)
        val st = run(stale)
        assertTrue(st.all { it.shownHeldMs == 0L })
        assertEquals(startOver, st.last().hint)
    }

    @Test
    fun liveTransitionIntoPlankCounts() {
        // Встал перед телефоном → опустился в планку за 1,5 с → 30 с планки.
        val tl = live(standMs = 2_000).move(STAND, PLANK, 1_500).hold(PLANK, 30_000)
        val last = run(tl).last()
        assertTrue(sec(last.heldMs), last.heldMs in 29_500L..31_000L)
        assertEquals(last.heldMs, last.currentSegmentMs)

        // Провис дольше 0,7 с завершает отрезок, но сам и даёт переход: выровнялся — новый отрезок.
        val sag = live().hold(PLANK, 12_000).hold(PLANK_SAG, 1_500).hold(PLANK, 12_000)
        val l2 = run(sag).last()
        assertTrue(l2.inPosition)
        assertEquals(sec(l2.heldMs), 24_000.0, l2.heldMs.toDouble(), 300.0)
    }

    @Test
    fun oneSegmentIsCappedAtThreeMinutes() {
        val states = run(live().hold(PLANK, 240_000))
        val last = states.last()
        assertEquals(180_000L, last.heldMs)
        assertEquals(180_000L, last.currentSegmentMs)
        assertTrue("поза держится", last.inPosition && last.holding)
        assertEquals(maxReached, last.hint)
        assertTrue(states.all { it.heldMs <= 180_000L })
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
        val elbowsForward = run(live().hold(Key(78f, 258f, 258f, 135f, 90f), 3_000)).last()
        assertEquals("Локти под плечи", elbowsForward.hint)
        assertEquals("Ляг в планку боком к телефону", run(Timeline().hold(STAND, 2_000)).last().hint)
    }

    @Test
    fun resetClearsHold() {
        val c = plank()
        assertTrue(c.feed(live().hold(PLANK, 15_000).frames).last().heldMs > 14_000)
        c.reset()
        assertEquals(RepState(), c.state)
        val again = live().hold(PLANK, 12_000).frames.map { it.shifted(60_000) }
        val last = c.feed(again).last()
        assertEquals(sec(last.heldMs), 12_000.0, last.heldMs.toDouble(), 300.0)
        // После сброса метки времени могут начаться заново (новый подход) — кадры не отбрасываются.
        c.reset()
        val fromStart = c.feed(live().hold(PLANK, 12_000).frames).last()
        assertEquals(sec(fromStart.heldMs), 12_000.0, fromStart.heldMs.toDouble(), 300.0)
    }

    @Test
    fun replayHonorsResetsAndSumsSets() {
        // Два подхода в одной записи, между ними сброс (# reset): 15 с и 12 с.
        val tl = live().hold(PLANK, 15_000)
        val resetAt = tl.frames.size
        live(tl).hold(PLANK, 12_000)
        val phone = plank()
        val recorded = tl.frames.mapIndexed { i, f ->
            if (i == resetAt) phone.reset()
            val s = phone.onFrame(f)
            RecordedState("COUNT", 0, s.tracking, s.inPosition, 0f, s.hint, s.atStart, s.heldMs)
        }
        val data = PoseLogData("plank", false, emptyMap(), tl.frames, recorded, resets = setOf(resetAt))

        val resetsAt = ArrayList<Int>()
        val inner = plank()
        val spy = object : RepCounter by inner {
            var seen = 0
            override fun onFrame(frame: PoseFrame) = inner.onFrame(frame).also { seen++ }
            override fun reset() {
                resetsAt += seen
                inner.reset()
            }
        }
        val r = PoseLogReplay.analyze("два подхода", data) { spy }
        assertEquals("сброс перед отмеченным кадром", listOf(resetAt), resetsAt)
        assertEquals(sec(r.replayHeldMs!!), 27_000.0, r.replayHeldMs!!.toDouble(), 500.0)
        assertEquals(sec(r.phoneHeldMs!!), 27_000.0, r.phoneHeldMs!!.toDouble(), 500.0)
        val sets = PoseLogReplay.heldBySets(r.states.map { it.heldMs }, data.resets)
        assertEquals(2, sets.size)
        assertEquals(15_000.0, sets[0].toDouble(), 300.0)
        assertEquals(12_000.0, sets[1].toDouble(), 300.0)
    }

    private fun PoseFrame.shifted(ms: Long) = PoseFrame(timeMs + ms, width, height, points)
}
