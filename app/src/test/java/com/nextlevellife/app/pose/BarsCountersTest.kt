package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Верх отжиманий на брусьях, вид сбоку: руки прямые, корпус наклонён на 12°, колени согнуты, голени за спиной. */
internal val BARS_DIP_TOP = Key(12f, 172f, 272f, 180f, 180f)

/** Низ: плечо отведено назад до уровня локтя, предплечье почти вертикально (локоть 80°), наклон 22°. */
internal val BARS_DIP_BOTTOM = Key(22f, 172f, 272f, 270f, 170f)

/** Упор на прямых руках, ноги висят. */
internal val BARS_HANG = Key(0f, 180f, 180f, 180f, 180f)

/** Бёдра чуть выше горизонтали (угол плечо–таз–колено 90°), ноги почти прямые, корпус отклонён на 5° назад. */
internal val BARS_LEGS_UP = Key(-5f, 85f, 88f, 180f, 180f)

/**
 * Брусья на синтетических кадрах (реальные координаты скелета, 30 кадров/с): полный повтор, неполная амплитуда,
 * потеря человека, короткий пик и взаимная непутаница с отжиманиями от пола, приседом и подтягиваниями.
 */
class BarsCountersTest {
    private fun counter(id: String) = RepCounters.forExercise(id) ?: error("нет счётчика $id")

    private fun count(id: String, tl: Timeline): Int {
        val c = counter(id)
        c.feed(tl.frames)
        return c.state.count
    }

    private fun reps(a: Key, b: Key, n: Int = 3, segmentMs: Long = 1_400L) =
        Timeline().hold(a, 1_000).reps(listOf(a, b), n, segmentMs).hold(a, 1_000)

    /** Движение с правкой суставов каждого кадра (например, вторая лодыжка видна). */
    private fun Timeline.moveEdited(from: Key, to: Key, ms: Long, edit: (Key, MutableMap<Joint, PosePoint>) -> Unit) = apply {
        val n = (ms / Timeline.STEP_MS).toInt()
        for (i in 1..n) {
            val k = from.lerp(to, ((1 - cos(Math.PI * i / n)) / 2).toFloat())
            frame(k) { edit(k, it) }
        }
    }

    private fun Timeline.holdEdited(key: Key, ms: Long, edit: (Key, MutableMap<Joint, PosePoint>) -> Unit) = apply {
        repeat((ms / Timeline.STEP_MS).toInt()) { frame(key) { edit(key, it) } }
    }

    private fun repsEdited(a: Key, b: Key, n: Int, edit: (Key, MutableMap<Joint, PosePoint>) -> Unit) =
        Timeline().holdEdited(a, 1_000, edit).apply {
            repeat(n) { moveEdited(a, b, 1_400, edit).moveEdited(b, a, 1_400, edit) }
        }.holdEdited(a, 1_000, edit)

    /** Дальняя лодыжка (правая при видимой левой стороне) видна уверенно в точке [at] (пиксели). */
    private fun farAnkleAt(points: MutableMap<Joint, PosePoint>, at: Pair<Float, Float>) {
        points[Joint.RIGHT_ANKLE] = PosePoint(at.first, at.second, 0.9f)
    }

    private fun nearAnkle(k: Key) = StickFigure.positions(k).getValue(Limb.ANKLE)

    /** Лодыжка стоящего человека: под тазом на длину ноги. */
    private val floorAnkle = Pair(StickFigure.HIP_X, StickFigure.HIP_Y + (StickFigure.THIGH + StickFigure.SHIN) * StickFigure.UNIT)

    @Test fun fullDipsAndLegRaisesCount() {
        val dips = counter("bars_dip")
        val states = dips.feed(reps(BARS_DIP_TOP, BARS_DIP_BOTTOM).frames)
        assertEquals(3, states.last().count)
        assertTrue(states.last().inPosition && states.last().atStart)
        assertEquals(3, count("bars_leg_raise", reps(BARS_HANG, BARS_LEGS_UP)))
        // С другой видимой стороной и с человеком, смотрящим влево, — так же.
        assertEquals(2, count("bars_dip", Timeline(visible = Side.RIGHT, facingLeft = true)
            .hold(BARS_DIP_TOP, 1_000).reps(listOf(BARS_DIP_TOP, BARS_DIP_BOTTOM), 2, 1_400).hold(BARS_DIP_TOP, 1_000)))
    }

    @Test fun visibleFarFootDoesNotBreakRealRepetitions() {
        // Голени скрещены: дальняя лодыжка рядом с ближней. Ноги вместе при подъёме: дальняя лодыжка поднимается с ближней.
        val beside: (Key, MutableMap<Joint, PosePoint>) -> Unit = { k, p ->
            val a = nearAnkle(k)
            farAnkleAt(p, Pair(a.first + 12f, a.second + 8f))
        }
        val dips = counter("bars_dip")
        dips.feed(repsEdited(BARS_DIP_TOP, BARS_DIP_BOTTOM, 3, beside).frames)
        assertEquals(3, dips.state.count)
        val raise = counter("bars_leg_raise")
        raise.feed(repsEdited(BARS_HANG, BARS_LEGS_UP, 3, beside).frames)
        assertEquals(3, raise.state.count)
    }

    @Test fun incompleteAmplitudeDoesNotCount() {
        // Локоть ≈ 120° (до 95° не дошёл), бёдра ≈ 126° (до горизонтали не дошли).
        assertEquals(0, count("bars_dip", reps(BARS_DIP_TOP, BARS_DIP_TOP.lerp(BARS_DIP_BOTTOM, 0.6f))))
        assertEquals(0, count("bars_leg_raise", reps(BARS_HANG, BARS_HANG.lerp(BARS_LEGS_UP, 0.6f))))
        // Наверху руки не выпрямлены (локоть ≈ 140° < 155°): повторы после первого не считаются.
        val bentTop = BARS_DIP_TOP.lerp(BARS_DIP_BOTTOM, 0.4f)
        assertEquals(0, count("bars_dip", reps(bentTop, BARS_DIP_BOTTOM)))
    }

    @Test fun losingPersonDiscardsUnfinishedCycle() {
        for ((id, path) in listOf("bars_dip" to (BARS_DIP_TOP to BARS_DIP_BOTTOM), "bars_leg_raise" to (BARS_HANG to BARS_LEGS_UP))) {
            val (a, b) = path
            val c = counter(id)
            // Дошёл до дальней точки и пропал из кадра на 2 с — недоделанный повтор не засчитывается.
            c.feed(Timeline().hold(a, 1_000).move(a, b, 1_400).hold(b, 400).empty(2_000).hold(a, 1_000).frames)
            assertEquals(id, 0, c.state.count)
            assertFalse(id, c.state.tracking && c.state.depth > 0.5f)
            // Пропуск кадров на 0,45 с (дольше устаревания сустава) тоже сбрасывает строгий профиль.
            c.reset()
            c.feed(Timeline().hold(a, 1_000).move(a, b, 1_400).hold(b, 400).skip(450).hold(a, 1_000).frames)
            assertEquals(id, 0, c.state.count)
            // После возвращения в кадр следующий полный повтор считается.
            c.reset()
            c.feed(Timeline().hold(a, 1_000).move(a, b, 1_400).hold(b, 400).empty(2_000)
                .hold(a, 1_000).reps(listOf(a, b), 1, 1_400).hold(a, 1_000).frames)
            assertEquals(id, 1, c.state.count)
        }
    }

    @Test fun briefExtremeShorterThan150MsDoesNotCount() {
        for ((id, path) in listOf("bars_dip" to (BARS_DIP_TOP to BARS_DIP_BOTTOM), "bars_leg_raise" to (BARS_HANG to BARS_LEGS_UP))) {
            val (a, b) = path
            val middle = a.lerp(b, 0.6f)
            fun peak(ms: Long) = Timeline().hold(a, 1_000).move(a, middle, 1_400).hold(b, ms)
                .hold(middle, 300).move(middle, a, 1_400).hold(a, 1_000)
            assertEquals("$id: 100 мс", 0, count(id, peak(100)))
            assertEquals("$id: 133 мс", 0, count(id, peak(133)))
            // Контроль: та же траектория с удержанием 300 мс засчитывается.
            assertEquals("$id: 300 мс", 1, count(id, peak(300)))
        }
    }

    @Test fun existingProfilesDoNotCountBars() {
        // Голени за спиной и вариант с прямыми висящими ногами: ни отжимания от пола, ни присед, ни подтягивания.
        val straightTop = Key(12f, 178f, 180f, 180f, 180f)
        val straightBottom = Key(22f, 178f, 180f, 270f, 170f)
        // Сильный наклон и голени назад-вниз.
        val leanTop = Key(15f, 175f, 262f, 180f, 180f)
        val leanBottom = Key(30f, 175f, 262f, 270f, 170f)
        val paths = listOf(
            "брусья" to reps(BARS_DIP_TOP, BARS_DIP_BOTTOM),
            "брусья, прямые ноги" to reps(straightTop, straightBottom),
            "брусья, наклон" to reps(leanTop, leanBottom),
            "подъём ног" to reps(BARS_HANG, BARS_LEGS_UP),
        )
        for ((name, tl) in paths) for (id in listOf("pushups", "squats", "pullups")) {
            assertEquals("$id: $name", 0, count(id, tl))
        }
        // Сами брусья: подъём ног не засчитывает отжимания и наоборот.
        assertEquals(0, count("bars_dip", reps(BARS_HANG, BARS_LEGS_UP)))
        assertEquals(0, count("bars_leg_raise", reps(BARS_DIP_TOP, BARS_DIP_BOTTOM)))
    }

    @Test fun barsDoNotCountFloorPushupsSquatsOrPullups() {
        for (spec in SPECS.filter { it.id in setOf("pushups", "squats", "pullups") }) {
            val tl = Timeline().hold(spec.path[0], 1_000).reps(spec.path, 3, 1_400).hold(spec.path[0], 1_000)
            // Контроль: свой профиль на этих кадрах считает.
            assertEquals(spec.id, 3, count(spec.id, tl))
            for (id in listOf("bars_dip", "bars_leg_raise")) assertEquals("$id: ${spec.id}", 0, count(id, tl))
        }
    }

    @Test fun standingImitationsAreNotBars() {
        // Стоя: сгибание рук и «брусья в воздухе» (локти назад, предплечья вниз) — ноги прямые на полу.
        val armsBack = Key(0f, 180f, 180f, 270f, 170f)
        assertEquals(0, count("bars_dip", reps(STAND, CURL)))
        assertEquals(0, count("bars_dip", reps(STAND, armsBack)))
        // Обратные отжимания от скамьи: ноги вытянуты вперёд (таз согнут) — не брусья.
        assertEquals(0, count("bars_dip", reps(Key(5f, 90f, 175f, 180f, 180f), Key(15f, 90f, 175f, 270f, 170f))))
        // Мах прямой ногой стоя: вторая стопа видна на полу — не подъём ног в упоре.
        val c = counter("bars_leg_raise")
        c.feed(repsEdited(BARS_HANG, BARS_LEGS_UP, 3) { _, p -> farAnkleAt(p, floorAnkle) }.frames)
        assertEquals(0, c.state.count)
        // Подъём согнутых коленей (колено ≈ 95°) в этом упражнении не засчитывается: ноги должны быть почти прямыми.
        assertEquals(0, count("bars_leg_raise", reps(BARS_HANG, Key(-5f, 85f, 180f, 180f, 180f))))
        // Присед с руками вдоль тела — тоже не подъём ног.
        assertEquals(0, count("bars_leg_raise", reps(STAND, Key(25f, 95f, 200f, 180f, 180f))))
    }
}
