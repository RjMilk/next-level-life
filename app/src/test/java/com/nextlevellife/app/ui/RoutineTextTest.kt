package com.nextlevellife.app.ui

import com.nextlevellife.app.data.HoldStep
import com.nextlevellife.app.data.RepsStep
import com.nextlevellife.app.data.RestStep
import com.nextlevellife.app.data.RoutineStepResult
import com.nextlevellife.app.data.Routines
import com.nextlevellife.app.pose.PoseSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTextTest {
    private val warriorL = HoldStep("yoga_warrior2", "Воин II", "warrior2", 45, PoseSide.LEFT, distance = "2,5–3 м", camera = "Спереди")
    private val warriorR = warriorL.copy(side = PoseSide.RIGHT)

    @Test
    fun announcements() {
        assertEquals("Воин II, левая сторона. 45 секунд", RoutineText.announce(warriorL, null))
        val reps = RepsStep("lfk_x", "Подъём рук вперёд сидя", "arm_raise_front", 8, distance = "2–3 м", camera = "Сбоку")
        assertEquals("Подъём рук вперёд сидя, 8 раз", RoutineText.announce(reps, null))
        assertEquals("Отдых. Следующее: Воин II, правая", RoutineText.announce(RestStep(15), warriorR))
        assertEquals("Готово. Смени сторону", RoutineText.afterDone(warriorL, warriorR))
        assertEquals("Готово. Смени сторону", RoutineText.afterDone(warriorL, RestStep(15, "Смена стороны")))
        assertEquals("Готово", RoutineText.afterDone(warriorR, RestStep(75, "Переход")))
    }

    @Test
    fun boardAndSummary() {
        assertEquals("0:23 / 0:45", RoutineText.board(warriorL, 23_400, 0, 0))
        assertEquals("3 / 8", RoutineText.board(RepsStep("lfk_x", "x", "elbow_flex", 8, distance = "", camera = ""), 0, 3, 0))
        assertEquals("0:12", RoutineText.board(RestStep(30), 0, 0, 11_200))
        assertEquals("ЛФК · 9 мин · 8 упражнений", RoutineText.summary(Routines.lfkBack))
        assertEquals("Йога · 12 мин · 10 поз", RoutineText.summary(Routines.yogaEvening))
    }

    @Test
    fun resultMentionsCapAndPartial() {
        val r = Routines.yogaMorning
        val i = r.steps.indexOfFirst { it is HoldStep }
        val (title, text) = RoutineText.result(r, listOf(RoutineStepResult(i, heldMs = 45_000)), 0L)
        assertEquals("Программа не завершена", title)
        assertTrue(text, text.contains("0:45 в позах"))
        assertTrue(text, text.contains("Дневной предел"))
    }
}
