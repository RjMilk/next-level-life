package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Трастер, телефон близко: в верхней точке кисти и локти уходят за верхний край кадра
 * (запись тестировщика: счёт терялся, «Встань в кадр целиком»).
 */
class ThrusterEdgeTest {
    private val rack = Key(0f, 180f, 180f, 170f, -20f)
    private val bottom = Key(30f, 95f, 200f, 170f, -20f)
    private val overhead = Key(0f, 180f, 180f, 0f, 0f)
    private val path = listOf(rack, bottom, rack, overhead)

    /** Как ML Kit отдаёт точки за верхним краем. */
    private enum class Out { REMOVED, LOW_CONFIDENCE }

    /**
     * Кадр, обрезанный сверху на уровне [top] (пиксели исходного кадра): голова у самого края,
     * поднятые руки — за краем. Точки выше края пропадают или остаются с уверенностью 0,15.
     */
    private fun crop(frames: List<PoseFrame>, top: Float, out: Out): List<PoseFrame> = frames.map { f ->
        if (f.points.isEmpty()) return@map f
        val pts = HashMap<Joint, PosePoint>()
        for ((j, p) in f.points) {
            val y = p.y - top
            when {
                y >= 0f -> pts[j] = p.copy(y = y)
                out == Out.LOW_CONFIDENCE -> pts[j] = p.copy(y = y, confidence = 0.15f)
            }
        }
        PoseFrame(f.timeMs, f.width, f.height, pts)
    }

    private fun thruster() = RepCounters.forExercise("thruster")!!

    private fun reps(n: Int, noise: Random? = null) =
        Timeline(noise = noise).hold(rack, 1000).reps(path, n, 600).hold(rack, 1000).frames

    /** Голова у верхнего края (нос ≈ 30 px от края), поднятые кисти — на 100 px выше края. */
    private val top = 780f

    @Test
    fun armsAboveTopEdgeStillCount() {
        for (out in Out.entries) {
            val frames = crop(reps(5), top, out)
            // Сам кадр: в верхней точке кисти действительно за краем, а голова видна.
            val up = frames.first { f -> f.points[Joint.LEFT_WRIST].let { it == null || it.confidence < 0.5f } }
            assertTrue(up.points.getValue(Joint.NOSE).y in 0f..60f)
            val states = thruster().feed(frames)
            assertEquals("$out", 5, states.last().count)
            assertTrue("$out: человек терялся", states.all { it.tracking })
        }
    }

    @Test
    fun withoutEdgeHandlingTheTopIsLost() {
        // Контроль: тот же подход тем же профилем без обёртки — верх не виден, повторы не считаются.
        val plain = PhaseCounter(ExerciseConfigs.byId.getValue("thruster"))
        val states = plain.feed(crop(reps(5), top, Out.REMOVED))
        assertEquals(0, states.last().count)
        assertTrue(states.any { !it.tracking })
    }

    @Test
    fun armsAboveTopEdgeWithNoise() {
        for (seed in 1..10) {
            val frames = crop(reps(5, Random(seed)), top, Out.LOW_CONFIDENCE)
            assertEquals("зерно $seed", 5, thruster().feed(frames).last().count)
        }
    }

    @Test
    fun fullyVisibleThrusterUnchanged() {
        assertEquals(5, thruster().feed(reps(5)).last().count)
    }

    @Test
    fun personLeavingFrameDoesNotCount() {
        // Присел, встал — и ушёл из кадра целиком (пустые кадры), вернулся с гирей у груди: повтора нет.
        val tl = Timeline().hold(rack, 1000)
        repeat(3) { tl.move(rack, bottom, 600).move(bottom, rack, 600).empty(2000).hold(rack, 800) }
        tl.hold(rack, 1000)
        assertEquals("ушёл после приседа", 0, thruster().feed(crop(tl.frames, top, Out.REMOVED)).last().count)

        // Руки пошли вверх, кисти у самого края — и человек пропал из кадра целиком, а не только руки.
        val gone = Timeline().hold(rack, 1000)
        repeat(3) {
            gone.move(rack, bottom, 600).move(bottom, rack, 600).move(rack, rack.lerp(overhead, 0.4f), 300)
                .empty(2000).hold(rack, 800)
        }
        val frames = crop(gone.frames, top, Out.REMOVED)
        assertEquals("ушёл с поднятыми руками", 0, thruster().feed(frames).last().count)
    }

    @Test
    fun headOutOfFrameIsNotFilledIn() {
        // Кадр срезан по плечи: головы нет — кисти за краем не достраиваются, повторов нет.
        val frames = crop(reps(3), 845f, Out.REMOVED)
        assertEquals(0, thruster().feed(frames).last().count)
    }

    @Test
    fun rackAtTopEdgeIsNotOverhead() {
        // Гиря у груди у самого края кадра, кисти пропадают (закрыты гирей), рук над головой нет: повторов нет.
        val tl = Timeline().hold(rack, 1000).reps(listOf(rack, bottom), 5, 600).hold(rack, 1000)
        val frames = crop(tl.frames, top, Out.REMOVED).map { f ->
            val pts = f.points.mapValues { (j, p) ->
                if ((j == Joint.LEFT_WRIST || j == Joint.RIGHT_WRIST) && p.y < 95f) p.copy(confidence = 0.2f) else p
            }
            PoseFrame(f.timeMs, f.width, f.height, pts)
        }
        val states = thruster().feed(frames)
        assertEquals(0, states.last().count)
    }

    @Test
    fun weakWristBackAtRackForgetsOverhead() {
        // Трастер с руками за краем, гиря обратно к груди (кисти закрыты гирей, уверенность низкая, но в кадре),
        // затем присед без жима: второго повтора нет — кисти не достраиваются над головой.
        val tl = Timeline().hold(rack, 1000)
            .move(rack, bottom, 600).move(bottom, rack, 600).move(rack, overhead, 600).hold(overhead, 300)
            .move(overhead, rack, 600)
            .move(rack, bottom, 600).move(bottom, rack, 600).hold(rack, 1500)
        val base = tl.frames.first().timeMs
        val lowering = base + 1000 + 600 * 3 + 300
        val frames = crop(tl.frames, top, Out.REMOVED).map { f ->
            if (f.timeMs < lowering) return@map f
            val pts = f.points.mapValues { (j, p) ->
                if (j == Joint.LEFT_WRIST || j == Joint.RIGHT_WRIST) p.copy(confidence = 0.2f) else p
            }
            PoseFrame(f.timeMs, f.width, f.height, pts)
        }
        assertEquals(1, thruster().feed(frames).last().count)
    }

    @Test
    fun resetForgetsArmsThatLeft() {
        val c = thruster()
        c.feed(crop(reps(2), top, Out.REMOVED))
        assertEquals(2, c.state.count)
        c.reset()
        assertEquals(0, c.state.count)
        val again = crop(reps(1), top, Out.REMOVED).map { PoseFrame(it.timeMs + 60_000, it.width, it.height, it.points) }
        assertEquals(1, c.feed(again).last().count)
    }
}
