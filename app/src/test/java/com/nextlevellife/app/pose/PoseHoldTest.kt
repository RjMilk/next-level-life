package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Обе стороны тела независимо. Направления отрезков в градусах по экрану: 0 — вверх, 90 — вправо (+x),
 * 180 — вниз, 270 — влево. torso: таз→плечо, thigh: таз→колено, shin: колено→лодыжка,
 * upperArm: плечо→локоть, forearm: локоть→запястье.
 */
internal data class Half(
    val torso: Float,
    val thigh: Float,
    val shin: Float,
    val upperArm: Float,
    val forearm: Float,
) {
    fun lerp(o: Half, k: Float) = Half(
        torso + (o.torso - torso) * k,
        thigh + (o.thigh - thigh) * k,
        shin + (o.shin - shin) * k,
        upperArm + (o.upperArm - upperArm) * k,
        forearm + (o.forearm - forearm) * k,
    )
}

/** Поза двух сторон. [halfWidth] — полуширина таза и плеч в торсах (0 — вид сбоку, стороны совпадают). */
internal data class Body2(
    val left: Half,
    val right: Half,
    val halfWidth: Float = 0.3f,
    val leftConf: Float = 0.95f,
    val rightConf: Float = 0.95f,
) {
    fun lerp(o: Body2, k: Float) = copy(left = left.lerp(o.left, k), right = right.lerp(o.right, k))

    fun points(): MutableMap<Joint, PosePoint> {
        val unit = 150f
        fun dir(deg: Float, len: Float): Pair<Float, Float> {
            val r = Math.toRadians(deg.toDouble())
            return Pair(sin(r).toFloat() * len * unit, -cos(r).toFloat() * len * unit)
        }
        fun add(p: Pair<Float, Float>, d: Pair<Float, Float>) = Pair(p.first + d.first, p.second + d.second)
        val out = HashMap<Joint, PosePoint>()
        var shoulders = 0f to 0f
        for (side in PoseSide.entries) {
            val h = if (side == PoseSide.LEFT) left else right
            val c = if (side == PoseSide.LEFT) leftConf else rightConf
            val sx = if (side == PoseSide.LEFT) 1f else -1f
            val hip = Pair(540f + sx * halfWidth * unit, 1100f)
            val shoulder = add(hip, dir(h.torso, 1f))
            val elbow = add(shoulder, dir(h.upperArm, 0.6f))
            val wrist = add(elbow, dir(h.forearm, 0.55f))
            val knee = add(hip, dir(h.thigh, 0.9f))
            val ankle = add(knee, dir(h.shin, 0.85f))
            for ((limb, p) in listOf(
                Limb.SHOULDER to shoulder, Limb.ELBOW to elbow, Limb.WRIST to wrist,
                Limb.HIP to hip, Limb.KNEE to knee, Limb.ANKLE to ankle,
            )) out[side.joint(limb)!!] = PosePoint(p.first, p.second, c)
            shoulders = Pair(shoulders.first + shoulder.first / 2f, shoulders.second + shoulder.second / 2f)
        }
        val torsoDir = left.torso
        val nose = add(add(shoulders, dir(torsoDir, 0.25f)), dir(torsoDir + 90f, 0.12f))
        out[Joint.NOSE] = PosePoint(nose.first, nose.second, 0.95f)
        return out
    }
}

/** Кадры 30 к/с с плавным переходом между позами. */
internal class Clip {
    val frames = ArrayList<PoseFrame>()
    private var t = 100_000.0
    private val step = 1000.0 / 30

    private fun n(ms: Long) = (ms / step).roundToInt().coerceAtLeast(1)

    fun frame(b: Body2?, edit: ((MutableMap<Joint, PosePoint>) -> Unit)? = null) {
        val p = b?.points()?.also { edit?.invoke(it) } ?: emptyMap()
        frames += PoseFrame(t.toLong(), 1080, 1920, p)
        t += step
    }

    fun hold(b: Body2, ms: Long, edit: ((MutableMap<Joint, PosePoint>) -> Unit)? = null) = apply {
        repeat(n(ms)) { frame(b, edit) }
    }

    fun empty(ms: Long) = apply { repeat(n(ms)) { frame(null) } }

    fun skip(ms: Long) = apply { t += ms }

    fun move(a: Body2, b: Body2, ms: Long) = apply {
        val k = n(ms)
        for (i in 1..k) frame(a.lerp(b, ((1 - cos(Math.PI * i / k)) / 2).toFloat()))
    }
}

// ---------- Позы для проверок ----------

private val STAND_HALF = Half(0f, 180f, 180f, 180f, 180f)

/** Стоит лицом к камере, руки вдоль тела. */
private val FRONT_STAND = Body2(STAND_HALF, STAND_HALF)

/** Гора: обе руки вверх. */
private val MOUNTAIN = Body2(Half(0f, 180f, 180f, 0f, 0f), Half(0f, 180f, 180f, 0f, 0f))

/** Гора с согнутыми локтями (≈ 130°) — не та поза. */
private val MOUNTAIN_BENT = Body2(Half(0f, 180f, 180f, 0f, 50f), Half(0f, 180f, 180f, 0f, -50f))

/** Воин II, согнута левая нога (колено 100°), руки в стороны. Левая сторона тела — справа на экране (+x). */
private fun warriorLeft(knee: Float = 100f) =
    Body2(Half(0f, knee, 180f, 90f, 90f), Half(0f, 225f, 225f, 270f, 270f))

/** Воин II, согнута правая нога. */
private val WARRIOR_RIGHT = Body2(Half(0f, 135f, 135f, 90f, 90f), Half(0f, 260f, 180f, 270f, 270f))

/** Широкая стойка, ноги прямые, руки в стороны — между сторонами Воина. */
private val WIDE_STAND = Body2(Half(0f, 135f, 135f, 90f, 90f), Half(0f, 225f, 225f, 270f, 270f))

/** Стол на четвереньках, вид сбоку (дальняя сторона почти не видна). */
private val TABLE = Body2(
    Half(90f, 180f, 270f, 180f, 180f), Half(90f, 180f, 270f, 180f, 180f),
    halfWidth = 0.02f, rightConf = 0.3f,
)

/** Сидит на пятках с прямой спиной — не Стол. */
private val KNEEL_UPRIGHT = TABLE.copy(left = Half(0f, 90f, 270f, 180f, 180f), right = Half(0f, 90f, 270f, 180f, 180f))

class PoseHoldTest {

    private fun mountain(target: Long = 60_000) = PoseHoldConfigs.holdCounter("mountain", null, target)!!
    private fun warrior(side: PoseSide, target: Long = 60_000) = PoseHoldConfigs.holdCounter("warrior2", side, target)!!

    private fun live(c: Clip = Clip(), b: Body2 = FRONT_STAND, ms: Long = 1_000) = c.hold(b, ms)

    @Test
    fun configsAreComplete() {
        for (id in PoseHoldConfigs.holds.keys) assertNotNull(id, PoseHoldConfigs.holdCounter(id, PoseSide.LEFT, 10_000))
        for (id in PoseHoldConfigs.moves.keys) assertNotNull(id, PoseHoldConfigs.repCounter(id, PoseSide.LEFT))
        assertNull(PoseHoldConfigs.holdCounter("plank", null, 10_000))
        assertEquals(true, PoseHoldConfigs.isSided("warrior2"))
        assertEquals(false, PoseHoldConfigs.isSided("mountain"))
        assertNull(PoseHoldConfigs.isSided("nope"))
        // Диапазон удержания всегда содержит диапазон входа (гистерезис, а не сужение).
        val all = PoseHoldConfigs.holds.values.flatMap { it.conds } + PoseHoldConfigs.moves.values.flatMap { it.allConds }
        for (c in all) {
            assertTrue("$c", c.hold.start <= c.enter.start && c.hold.endInclusive >= c.enter.endInclusive)
            assertTrue("$c", c.hint.isNotBlank())
        }
    }

    @Test
    fun poseCountsOnlyInsideRange() {
        val c = Clip()
        live(c).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 20_000)
        val s = mountain().feed(c.frames).last()
        assertEquals(20_000.0, s.heldMs.toDouble(), 1_000.0)
        assertTrue(s.holding)

        // Локти согнуты — поза неправильная: время не идёт, подсказка про руки.
        val bent = Clip()
        live(bent).move(FRONT_STAND, MOUNTAIN_BENT, 600).hold(MOUNTAIN_BENT, 20_000)
        val b = mountain()
        val last = b.feed(bent.frames).last()
        assertEquals(0L, last.heldMs)
        assertEquals(0L, last.shownHeldMs)
        assertEquals("Выпрями руки", last.hint)
        assertEquals(PoseVerdict.BAD, b.info.verdict)

        // Стол: сидит на пятках с прямой спиной — не стол.
        val k = Clip().hold(TABLE.copy(left = STAND_HALF), 1_000).hold(KNEEL_UPRIGHT, 15_000)
        assertEquals(0L, PoseHoldConfigs.holdCounter("table", null, 60_000)!!.feed(k.frames).last().heldMs)
        val t = Clip().hold(KNEEL_UPRIGHT, 1_000).move(KNEEL_UPRIGHT, TABLE, 800).hold(TABLE, 15_000)
        assertEquals(15_000.0, PoseHoldConfigs.holdCounter("table", null, 60_000)!!.feed(t.frames).last().heldMs.toDouble(), 1_000.0)
    }

    @Test
    fun entryRequiresVisibleTransition() {
        // Человек (или фотография) с первого кадра в позе: время не идёт.
        val photo = Clip().hold(MOUNTAIN, 20_000)
        val c = mountain()
        val s = c.feed(photo.frames).last()
        assertEquals(0L, s.heldMs)
        assertFalse(s.inPosition)
        assertEquals(PoseHints.START_OVER, s.hint)

        // Вышел из позы, вошёл снова — теперь считается.
        val again = Clip().skip(20_100).hold(FRONT_STAND, 1_000).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 12_000)
        val after = c.feed(again.frames).last()
        assertEquals(12_000.0, after.heldMs.toDouble(), 1_000.0)

        // Кадр «не в позе» короче 400 мс — не переход (дрожь порога), вход не разрешён.
        val flicker = Clip().hold(MOUNTAIN, 2_000).hold(FRONT_STAND, 200).hold(MOUNTAIN, 12_000)
        assertEquals(0L, mountain().feed(flicker.frames).last().heldMs)
    }

    @Test
    fun shortLossOfPointsKeepsHold() {
        val c = Clip()
        live(c).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 8_000)
        // 400 мс запястья не видны (уверенность 0,1) — кадры неизвестные, отрезок не рвётся.
        c.hold(MOUNTAIN, 400) { p ->
            p[Joint.LEFT_WRIST] = p.getValue(Joint.LEFT_WRIST).copy(confidence = 0.1f)
        }
        // и ещё 300 мс камера не присылает кадров.
        c.hold(MOUNTAIN, 4_000).skip(300).hold(MOUNTAIN, 4_000)
        val s = mountain().feed(c.frames).last()
        assertTrue(s.inPosition)
        // Всего в позе ≈ 16,7 с, разрывы (≈ 0,7 с) не оплачены.
        assertEquals(16_000.0, s.heldMs.toDouble(), 1_000.0)
        assertEquals(s.heldMs, s.currentSegmentMs)
    }

    @Test
    fun longLossBreaksHold() {
        // 6 с в позе (меньше 10 с), потом 1,2 с не видно — отрезок пропал, без нового входа время не идёт.
        val c = Clip()
        live(c).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 6_000).empty(1_200).hold(MOUNTAIN, 10_000)
        val counter = mountain()
        val states = counter.feed(c.frames)
        assertTrue(states.any { it.pendingMs > 0 })
        val s = states.last()
        assertEquals(0L, s.heldMs)
        assertFalse(s.inPosition)

        // 12 с засчитаны, затем человек пропал на 2 с: засчитанное остаётся, новое — только после нового входа.
        val d = Clip()
        live(d).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 12_000).empty(2_000).hold(MOUNTAIN, 5_000)
        val s2 = mountain().feed(d.frames).last()
        assertEquals(12_000.0, s2.heldMs.toDouble(), 1_000.0)
        assertFalse(s2.inPosition)
        assertEquals(PoseHints.START_OVER, s2.hint)

        // Плохая поза дольше 700 мс тоже завершает отрезок.
        val e = Clip()
        live(e).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 12_000).hold(MOUNTAIN_BENT, 1_500).hold(MOUNTAIN, 5_000)
        val s3 = mountain().feed(e.frames).last()
        // Сгибание локтей — видимый выход из позы: новый вход разрешён, новый отрезок идёт (пока < 10 с).
        assertEquals(12_000.0, s3.heldMs.toDouble(), 1_000.0)
        assertTrue(s3.pendingMs > 3_000)
    }

    @Test
    fun hysteresisKeepsHoldNearThreshold() {
        // Вход с коленом 110°, дальше колено гуляет 118…123° (вход до 120°, удержание до 125°): отрезок не рвётся.
        val c = Clip()
        live(c, WIDE_STAND).move(WIDE_STAND, warriorLeft(110f), 600).hold(warriorLeft(110f), 2_000)
        repeat(30) { i -> c.hold(warriorLeft(if (i % 2 == 0) 118f else 123f), 400) }
        val s = warrior(PoseSide.LEFT).feed(c.frames).last()
        assertTrue(s.holding)
        assertEquals(14_000.0, s.heldMs.toDouble(), 1_000.0)

        // А вход прямо с 123° не разрешён (строгий порог).
        val d = Clip()
        live(d, WIDE_STAND).move(WIDE_STAND, warriorLeft(123f), 600).hold(warriorLeft(123f), 12_000)
        val w = warrior(PoseSide.LEFT)
        assertEquals(0L, w.feed(d.frames).last().heldMs)
        assertEquals("Согни колено", w.state.hint)
    }

    @Test
    fun sideIsDetected() {
        // Шаг «левая нога», человек согнул правую: не засчитывается, подсказка о стороне.
        val c = Clip()
        live(c, WIDE_STAND).move(WIDE_STAND, WARRIOR_RIGHT, 600).hold(WARRIOR_RIGHT, 12_000)
        val w = warrior(PoseSide.LEFT)
        val s = w.feed(c.frames).last()
        assertEquals(0L, s.heldMs)
        assertEquals(PoseSide.RIGHT, w.info.observedSide)
        assertEquals("Согни левую ногу", s.hint)

        // Тот же человек делает левую — сторона определена, время идёт.
        c.move(WARRIOR_RIGHT, WIDE_STAND, 600).hold(WIDE_STAND, 600).move(WIDE_STAND, warriorLeft(), 600).hold(warriorLeft(), 12_000)
        val w2 = warrior(PoseSide.LEFT)
        val s2 = w2.feed(c.frames).last()
        assertEquals(PoseSide.LEFT, w2.info.observedSide)
        assertEquals(12_000.0, s2.heldMs.toDouble(), 1_000.0)

        // Шаг «правая нога»: левая поза — не засчитывается.
        val d = Clip()
        live(d, WIDE_STAND).move(WIDE_STAND, warriorLeft(), 600).hold(warriorLeft(), 12_000)
        val w3 = warrior(PoseSide.RIGHT)
        assertEquals(0L, w3.feed(d.frames).last().heldMs)
        assertEquals(PoseSide.LEFT, w3.info.observedSide)
    }

    @Test
    fun sideSwitchNeedsOwnEntry() {
        val w = warrior(PoseSide.LEFT, target = 60_000)
        val c = Clip()
        live(c, WIDE_STAND).move(WIDE_STAND, warriorLeft(), 600).hold(warriorLeft(), 15_000)
        assertEquals(15_000.0, w.feed(c.frames).last().heldMs.toDouble(), 1_000.0)

        w.switchSide(PoseSide.RIGHT)
        assertEquals(PoseSide.RIGHT, w.side)
        assertEquals(0L, w.state.heldMs)
        // Стоит в левой позе дальше — для правой это «не в позе»; прежний таймер не продолжается.
        val d = Clip().skip(16_700).hold(warriorLeft(), 3_000)
        assertEquals(0L, w.feed(d.frames).last().heldMs)
        // Переходит в правую — новая сторона копит своё время.
        d.move(warriorLeft(), WARRIOR_RIGHT, 1_000).hold(WARRIOR_RIGHT, 12_000)
        val s = w.feed(d.frames.drop(90)).last()
        assertEquals(12_000.0, s.heldMs.toDouble(), 1_200.0)
        assertEquals(PoseSide.RIGHT, w.info.observedSide)
    }

    @Test
    fun targetStopsTime() {
        val c = Clip()
        live(c).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 25_000)
        val m = mountain(target = 20_000)
        val states = m.feed(c.frames)
        val s = states.last()
        assertEquals(20_000L, s.heldMs)
        assertTrue(m.info.done)
        assertFalse(s.holding)
        // Ровно до цели, не больше, и цель достигнута до конца клипа.
        assertTrue(states.indexOfFirst { it.heldMs >= 20_000L } < states.size - 100)

        // Добирающий отрезок короче 10 с засчитывается: 15 с, разрыв, ещё 5 с — цель 20 с набрана.
        val d = Clip()
        live(d).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 15_000).move(MOUNTAIN, FRONT_STAND, 600)
            .hold(FRONT_STAND, 800).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 7_000)
        val m2 = mountain(target = 20_000)
        assertEquals(20_000L, m2.feed(d.frames).last().heldMs)
        assertTrue(m2.info.done)
    }

    @Test
    fun observeGrantsLiveEntryWithoutPaying() {
        // Отдых перед шагом: человек виден стоя (не в позе) — допуск на вход есть, но время не идёт.
        val rest = Clip().hold(FRONT_STAND, 1_500).move(FRONT_STAND, MOUNTAIN, 600).hold(MOUNTAIN, 2_000)
        val m = mountain()
        rest.frames.forEach { m.observe(it) }
        assertEquals(0L, m.state.heldMs)
        assertFalse(m.state.inPosition)
        // Шаг начался: человек уже в позе после видимого перехода — время идёт без нового выхода.
        val step = Clip().skip(4_200).hold(MOUNTAIN, 12_000)
        assertEquals(12_000.0, m.feed(step.frames).last().heldMs.toDouble(), 1_000.0)
    }

    @Test
    fun sideViewUsesVisibleSide() {
        // Стол лицом влево: рабочая сторона — та, что видна (правая), дальняя не мешает.
        val flipped = TABLE.copy(
            left = TABLE.left.copy(torso = 270f, shin = 90f), right = TABLE.right.copy(torso = 270f, shin = 90f),
            leftConf = 0.3f, rightConf = 0.95f,
        )
        val kneel = flipped.copy(left = Half(0f, 270f, 90f, 180f, 180f), right = Half(0f, 270f, 90f, 180f, 180f))
        val c = Clip().hold(kneel, 1_000).move(kneel, flipped, 800).hold(flipped, 12_000)
        assertEquals(12_000.0, PoseHoldConfigs.holdCounter("table", null, 60_000)!!.feed(c.frames).last().heldMs.toDouble(), 1_000.0)
    }

    // ---------- Повторения ----------

    private val ARMS_DOWN = FRONT_STAND
    private val ARMS_SIDE = Body2(Half(0f, 180f, 180f, 90f, 90f), Half(0f, 180f, 180f, 270f, 270f))
    private val ARMS_HALF = Body2(Half(0f, 180f, 180f, 130f, 130f), Half(0f, 180f, 180f, 230f, 230f))

    private fun abduction() = PoseHoldConfigs.repCounter("arm_abduction", null, 8)!!

    @Test
    fun slowRepsCount() {
        val c = Clip().hold(ARMS_DOWN, 2_000)
        repeat(3) { c.move(ARMS_DOWN, ARMS_SIDE, 2_500).hold(ARMS_SIDE, 500).move(ARMS_SIDE, ARMS_DOWN, 2_500).hold(ARMS_DOWN, 800) }
        val s = abduction().feed(c.frames).last()
        assertEquals(3, s.count)
        assertTrue(s.atStart)
    }

    @Test
    fun fastRepsAreLimited() {
        val c = Clip().hold(ARMS_DOWN, 1_000)
        repeat(8) { c.move(ARMS_DOWN, ARMS_SIDE, 700).move(ARMS_SIDE, ARMS_DOWN, 700) }
        val states = abduction().feed(c.frames)
        // 8 циклов за ≈ 12 с: засчитано не больше одного за 5 с.
        assertTrue("${states.last().count}", states.last().count in 1..2)
        assertTrue(states.any { it.hint == PoseHints.SLOWER })
    }

    @Test
    fun incompleteRepIsNotCounted() {
        val c = Clip().hold(ARMS_DOWN, 1_000)
        repeat(3) { c.move(ARMS_DOWN, ARMS_HALF, 2_500).move(ARMS_HALF, ARMS_DOWN, 2_500).hold(ARMS_DOWN, 800) }
        val states = abduction().feed(c.frames)
        assertEquals(0, states.last().count)
        assertTrue(states.any { it.hint == "Руки в стороны до уровня плеч" })
    }

    @Test
    fun repTargetCapsCount() {
        val c = Clip().hold(ARMS_DOWN, 2_000)
        repeat(4) { c.move(ARMS_DOWN, ARMS_SIDE, 2_500).hold(ARMS_SIDE, 500).move(ARMS_SIDE, ARMS_DOWN, 2_500).hold(ARMS_DOWN, 800) }
        assertEquals(2, PoseHoldConfigs.repCounter("arm_abduction", null, 2)!!.feed(c.frames).last().count)
    }

    /** Вид сбоку, обе ноги видны. Подъём колена: бедро вперёд (90° — горизонтально). */
    private fun side(left: Half, right: Half) = Body2(left, right, halfWidth = 0.05f)
    private val UP = Half(0f, 180f, 180f, 180f, 180f)
    private val KNEE_UP = Half(0f, 95f, 180f, 180f, 180f)

    @Test
    fun sidedRepsNeedWorkingSide() {
        val stand = side(UP, UP)
        val leftUp = side(KNEE_UP, UP)
        val rightUp = side(UP, KNEE_UP)

        val c = Clip().hold(stand, 1_000)
        repeat(2) { c.move(stand, rightUp, 2_000).hold(rightUp, 500).move(rightUp, stand, 2_000).hold(stand, 1_000) }
        val left = PoseHoldConfigs.repCounter("knee_raise", PoseSide.LEFT)!!
        val states = left.feed(c.frames)
        assertEquals(0, states.last().count)
        assertTrue(states.any { it.hint == "Поднимай левое колено" })

        val d = Clip().hold(stand, 2_000)
        repeat(2) { d.move(stand, leftUp, 2_000).hold(leftUp, 500).move(leftUp, stand, 2_000).hold(stand, 1_000) }
        val left2 = PoseHoldConfigs.repCounter("knee_raise", PoseSide.LEFT)!!
        assertEquals(2, left2.feed(d.frames).last().count)
        assertEquals(0, PoseHoldConfigs.repCounter("knee_raise", PoseSide.RIGHT)!!.feed(d.frames).last().count)
    }

    @Test
    fun hintWhenNobodyOrPartlyVisible() {
        val m = mountain()
        assertEquals(PoseHints.NOT_SEEN, m.feed(Clip().empty(500).frames).last().hint)
        val legsHidden = Clip().hold(MOUNTAIN, 500) { p ->
            for (j in listOf(Joint.LEFT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_KNEE, Joint.RIGHT_ANKLE)) p.remove(j)
        }
        val s = mountain().feed(legsHidden.frames).last()
        assertFalse(s.tracking)
        assertEquals(PoseHints.NO_LEGS, s.hint)
    }
}
