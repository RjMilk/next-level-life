package com.nextlevellife.app.ui

import com.nextlevellife.app.data.FreeSet
import com.nextlevellife.app.data.FreeSetStatus
import com.nextlevellife.app.data.FreeWorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTextTest {
    private fun set(n: Int, id: String, reps: Int, held: Long = 0L, status: FreeSetStatus = FreeSetStatus.RECORDED, earned: Long = 0L) =
        FreeSet(n, id, reps, held, 0L, 60_000L, status, earned)

    @Test
    fun nowLine() {
        assertEquals("Начни любое упражнение", FreeText.now(null, 0, 0L))
        assertEquals("Сейчас: Приседания · 12", FreeText.now("squats", 12, 0L))
        assertEquals("Сейчас: Планка · 0:45", FreeText.now("plank", 0, 45_000L))
        assertEquals("Начни любое упражнение", FreeText.now("нет_такого", 3, 0L))
    }

    @Test
    fun setLines() {
        assertEquals("Приседания — 12 повт. · +8 мин", FreeText.setLine(set(1, "squats", 12), 480_000L))
        assertEquals("Планка — 1:20 · +6 мин 40 сек", FreeText.setLine(set(2, "plank", 0, 80_000L), 400_000L))
        // Не записанный подход — по обычной цене; записанный — как начислено.
        assertEquals(120_000L, FreeText.setEarned(set(3, "pushups", 4, status = FreeSetStatus.CLOSED)))
        assertEquals(7L, FreeText.setEarned(set(3, "pushups", 4, earned = 7L)))
    }

    @Test
    fun setsViewChangesOnlyWithShownText() {
        // Планка растёт на каждом кадре, а список меняется раз в секунду: в пределах секунды — тот же вид.
        val a = FreeWorkoutLog(listOf(set(1, "squats", 10, earned = 400_000L), set(2, "plank", 0, 12_100L, FreeSetStatus.OPEN)))
        val b = FreeWorkoutLog(listOf(set(1, "squats", 10, earned = 400_000L), set(2, "plank", 0, 12_900L, FreeSetStatus.OPEN)))
        val c = FreeWorkoutLog(listOf(set(1, "squats", 10, earned = 400_000L), set(2, "plank", 0, 13_000L, FreeSetStatus.OPEN)))
        assertEquals(FreeText.setsView(a), FreeText.setsView(b))
        assertTrue(FreeText.setsView(b) != FreeText.setsView(c))
        val v = FreeText.setsView(c)
        assertEquals(listOf("2. Планка — 0:13 · +1 мин 5 сек · идёт", "1. Приседания — 10 повт. · +6 мин 40 сек"), v.rows.map { it.text })
        assertEquals("Заработано: +7 мин 45 сек", v.earned)
    }

    @Test
    fun resultText() {
        val log = FreeWorkoutLog(
            listOf(set(1, "squats", 10, earned = 400_000L), set(2, "pushups", 5, earned = 150_000L)),
        )
        val (title, text) = FreeText.result(log, "+1 силы (сегодня 1/10)")
        assertEquals("Тренировка записана", title)
        assertEquals(
            "Приседания — 10 повт. · +6 мин 40 сек\nОтжимания — 5 повт. · +2 мин 30 сек\nВсего: +9 мин 10 сек\n+1 силы (сегодня 1/10)",
            text,
        )
        assertTrue(FreeText.SETUP.contains("отжимания, приседания, пресс, подтягивания, планка"))
    }
}
