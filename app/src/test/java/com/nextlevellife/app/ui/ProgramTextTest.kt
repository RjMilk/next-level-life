package com.nextlevellife.app.ui

import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.ProgramSummary
import com.nextlevellife.app.data.Programs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тексты экрана домашней программы и строки прохождений в «Прогрессе». */
class ProgramTextTest {
    private val b = Programs.beginner

    @Test
    fun setLinesAndSchemes() {
        assertEquals("3×10", ProgramText.scheme(b.steps[0]))
        assertEquals("3×20 с", ProgramText.scheme(b.steps[3]))
        assertEquals("Подход 2 из 3 · цель 10", ProgramText.setLine(b.steps[0], 1))
        assertEquals("Подход 1 из 3 · цель 0:20", ProgramText.setLine(b.steps[3], 0))
        assertEquals("4 упражнения · 12 подходов · ≈20 мин", ProgramText.summary(b))
        assertEquals("4 программы", programsSummary())
    }

    @Test
    fun voice() {
        assertEquals("Приседания. Подход 1 из 3, 10 раз", ProgramText.announceSet(b, 0))
        assertEquals("Подход 2 из 3, 10 раз", ProgramText.announceSet(b, 1))
        assertEquals("Отжимания. Подход 1 из 3, 6 раз", ProgramText.announceSet(b, 3))
        assertEquals("Планка. Подход 1 из 3, 20 секунд", ProgramText.announceSet(b, 9))
        assertEquals("Отдых. Следующее: Отжимания, подход 1 из 3", ProgramText.announceRest(b, 3))
        assertEquals("Следующее: Приседания · подход 2 из 3", ProgramText.nextLine(b, 1))
        assertEquals(null, ProgramText.nextLine(b, b.totalSets))
        assertEquals("0:45", ProgramText.restBoard(44_100L))
        assertEquals("0:00", ProgramText.restBoard(-5L))
    }

    @Test
    fun cameraHintFromGuide() {
        val hint = ProgramText.cameraHint("pushups")
        assertNotNull(hint)
        assertTrue(hint!!, hint.startsWith("Камера: "))
        Programs.all.flatMap { it.steps }.forEach { assertNotNull(it.exercise.id, ProgramText.cameraHint(it.exercise.id)) }
    }

    @Test
    fun resultText() {
        val full = ProgramSummary(12, 12, 12, 78, 60_000L, 47 * 60_000L, completed = true)
        val (t1, x1) = ProgramText.result(b, full, "+3 силы (сегодня 5 из 10)")
        assertEquals("Программа пройдена", t1)
        assertTrue(x1, x1.startsWith("Подходов: 12 из 12 · 78 повторений · 1:00 в планке. +47 мин экранного времени."))
        assertTrue(x1, x1.contains("+3 силы"))
        val part = ProgramSummary(5, 3, 12, 40, 0L, 20 * 60_000L, completed = false)
        val (t2, x2) = ProgramText.result(b, part, null)
        assertEquals("Программа не завершена", t2)
        assertTrue(x2, x2.endsWith("Цель не достигнута в 9 подходах."))
    }

    @Test
    fun progressLinesForProgramRuns() {
        fun run(id: String, weight: Int?, completed: Boolean) = ComplexRun(
            id = 1, timeMs = 0, day = 0, complexId = id, weightKg = weight, durationMs = 21 * 60_000L + 5_000L,
            rounds = null, extraReps = null, totalReps = 96, earnedMs = 0, xp = 0, completed = completed,
        )
        assertEquals("Новичок, всё тело, ≈20 мин — 21:05", ProgressMath.complexLine(run("home_beginner", null, true)))
        assertEquals(
            "Гиря — силовая 24 кг — 21:05 · не завершён",
            ProgressMath.complexLine(run("home_kb_strength", 24, false)),
        )
        assertEquals(
            ProgressMath.Cells("Гиря — силовая 24 кг", "21:05"),
            ProgressMath.complexCells(run("home_kb_strength", 24, true)),
        )
    }
}
