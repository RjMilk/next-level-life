package com.nextlevellife.app.data

import com.nextlevellife.app.pose.PoseHoldConfigs
import com.nextlevellife.app.pose.PoseSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutinesTest {

    // ---------- Целостность каталога ----------

    @Test
    fun idsAreUniqueAndDoNotClash() {
        val routineIds = Routines.all.map { it.id }
        assertEquals(routineIds.size, routineIds.toSet().size)
        assertEquals(4, routineIds.size)
        val foreign = Exercises.all.map { it.id }.toSet() + setOf(Cindy.ID, Minotaur.ID, "saitama")
        for (r in Routines.all) {
            assertFalse(r.id, r.id in foreign)
            assertTrue(r.id, Routines.isRoutineId(r.id))
            for (e in r.exercises) {
                assertFalse(e.exerciseId, e.exerciseId in foreign)
                assertFalse("id упражнения совпадает с id программы: ${e.exerciseId}", e.exerciseId in routineIds)
                assertTrue(e.exerciseId, Routines.isRoutineExercise(e.exerciseId))
                val prefix = if (r.kind == RoutineKind.LFK) Routines.LFK_PREFIX else Routines.YOGA_PREFIX
                assertTrue(e.exerciseId, e.exerciseId.startsWith(prefix))
            }
        }
        for (ex in Exercises.all) assertFalse(ex.id, Routines.isRoutineExercise(ex.id))
        assertEquals(Routines.lfkBack, Routines.byId("lfk_back_neck"))
    }

    @Test
    fun stepsAreValid() {
        for (r in Routines.all) {
            for (s in r.steps) {
                assertTrue("${r.id}: ${s.title}", s.nominalMs > 0)
                if (s is RoutineExercise) {
                    assertTrue(s.title, s.camera.isNotBlank() && s.distance.isNotBlank())
                    assertTrue(s.title, s.tips.isNotEmpty())
                    assertNotNull(s.title, s.guideId)
                }
                when (s) {
                    is HoldStep -> {
                        assertTrue(s.poseId, PoseHoldConfigs.hasHold(s.poseId))
                        assertEquals(s.poseId, PoseHoldConfigs.isSided(s.poseId), s.side != null)
                        assertNotNull(PoseHoldConfigs.holdCounter(s.poseId, s.side, s.targetMs))
                    }
                    is RepsStep -> {
                        assertTrue(s.moveId, PoseHoldConfigs.hasMove(s.moveId))
                        assertEquals(s.moveId, PoseHoldConfigs.isSided(s.moveId), s.side != null)
                        assertTrue(s.reps > 0)
                    }
                    is RestStep -> Unit
                }
            }
            // Первый шаг — подготовка, последний — упражнение.
            assertTrue(r.id, r.steps.first() is RestStep)
            assertTrue(r.id, r.steps.last() is RoutineExercise)
        }
    }

    @Test
    fun sidedExercisesHaveBothSides() {
        for (r in Routines.all) {
            val sided = r.exercises.filter { it.side != null }.groupBy { it.exerciseId }
            for ((id, steps) in sided) {
                val left = steps.count { it.side == PoseSide.LEFT }
                val right = steps.count { it.side == PoseSide.RIGHT }
                assertEquals("${r.id}/$id", left, right)
                assertTrue("${r.id}/$id", left > 0)
                // Объём сторон одинаковый.
                assertEquals("${r.id}/$id", steps.first { it.side == PoseSide.LEFT }.nominalMs, steps.first { it.side == PoseSide.RIGHT }.nominalMs)
            }
        }
    }

    @Test
    fun durationMatchesDeclared() {
        for (r in Routines.all) {
            assertEquals(r.id, r.declaredMinutes * 60_000.0, r.nominalMs.toDouble(), 15_000.0)
        }
        assertEquals(9, Routines.lfkBack.declaredMinutes)
        assertEquals(9, Routines.lfkJoints.declaredMinutes)
        assertEquals(10, Routines.yogaMorning.declaredMinutes)
        assertEquals(12, Routines.yogaEvening.declaredMinutes)
        // Блоки на карточке: ЛФК — 8 упражнений, утро — 8 поз, вечер — 10 поз (как в каталоге).
        assertEquals(8, Routines.lfkBack.blocks)
        assertEquals(8, Routines.lfkJoints.blocks)
        assertEquals(8, Routines.yogaMorning.blocks)
        assertEquals(10, Routines.yogaEvening.blocks)
    }

    // ---------- Награда ----------

    private fun full(r: Routine): List<RoutineStepResult> = r.steps.mapIndexedNotNull { i, s ->
        when (s) {
            is HoldStep -> RoutineStepResult(i, heldMs = s.targetMs, durationMs = s.targetMs + 2_000)
            is RepsStep -> RoutineStepResult(i, reps = s.reps, durationMs = s.nominalMs)
            is RestStep -> null
        }
    }

    @Test
    fun pricesFromCatalog() {
        val hold40 = HoldStep("yoga_x", "x", "table", 40, distance = "1", camera = "1")
        assertEquals(10_000L, RoutineMath.stepRewardMs(hold40, RoutineStepResult(0, heldMs = 40_000)))
        // Дробная секунда копится (39 с → 9,75 с), сверх цели не платится.
        assertEquals(9_750L, RoutineMath.stepRewardMs(hold40, RoutineStepResult(0, heldMs = 39_900)))
        assertEquals(10_000L, RoutineMath.stepRewardMs(hold40, RoutineStepResult(0, heldMs = 90_000)))
        val reps8 = RepsStep("lfk_x", "x", "elbow_flex", 8, distance = "1", camera = "1")
        assertEquals(16_000L, RoutineMath.stepRewardMs(reps8, RoutineStepResult(0, reps = 8)))
        assertEquals(16_000L, RoutineMath.stepRewardMs(reps8, RoutineStepResult(0, reps = 12)))
        // Без оплаты: отдых в позе.
        val rest = hold40.copy(paid = false)
        assertEquals(0L, RoutineMath.stepRewardMs(rest, RoutineStepResult(0, heldMs = 40_000)))
        assertEquals(0L, RoutineMath.stepRewardMs(RestStep(30), RoutineStepResult(0, heldMs = 30_000)))
    }

    @Test
    fun fullProgramRewards() {
        // Спина и шея: 40 повторений × 2 с + 40 с удержания × 0,25 = 90 с.
        assertEquals(90_000L, RoutineMath.maxRewardMs(Routines.lfkBack))
        // Суставы: 64 повторения × 2 с = 128 с.
        assertEquals(128_000L, RoutineMath.maxRewardMs(Routines.lfkJoints))
        // Утро: 345 с поз × 0,25 = 86,25 → 86 с (округление вниз в итоге).
        assertEquals(86_000L, RoutineMath.maxRewardMs(Routines.yogaMorning))
        // Вечер: 360 с поз (шавасана без награды) × 0,25 = 90 с.
        assertEquals(90_000L, RoutineMath.maxRewardMs(Routines.yogaEvening))
        for (r in Routines.all) {
            val s = RoutineMath.score(r, full(r))
            assertTrue(r.id, s.completed)
            assertTrue(r.id, s.hasWork)
            assertEquals(RoutineMath.maxRewardMs(r), s.rawEarnedMs)
            // Мягче силовых: вся программа — меньше двух с половиной минут (одна минута планки — 5 минут).
            assertTrue(r.id, s.rawEarnedMs < 150_000L)
            // Подходы делят начисленное без остатка.
            val sets = RoutineMath.sets(r, full(r), s.rawEarnedMs)
            assertEquals(r.id, s.rawEarnedMs, sets.sumOf { it.earnedMs })
            assertTrue(sets.all { Routines.isRoutineExercise(it.exerciseId) })
        }
    }

    @Test
    fun completedNeedsEightyPercentOfEveryRequiredStep() {
        val r = Routines.yogaMorning
        val base = full(r)
        // 80 % удержания (36 из 45 с) — шаг выполнен.
        val i = r.steps.indexOfFirst { it is HoldStep }
        val at80 = base.map { if (it.stepIndex == i) it.copy(heldMs = 36_000, skipped = true) else it }
        assertTrue(RoutineMath.score(r, at80).completed)
        val below = base.map { if (it.stepIndex == i) it.copy(heldMs = 35_000) else it }
        assertFalse(RoutineMath.score(r, below).completed)
        // Пропущенный шаг без подтверждения — программа частичная, но заработанное остаётся.
        val missing = base.filter { it.stepIndex != i }
        val s = RoutineMath.score(r, missing)
        assertFalse(s.completed)
        assertEquals(86_000L - 11_000L, s.rawEarnedMs) // 345 − 45 = 300 с → 75 с

        // Отдых без награды (шавасана) на завершённость не влияет.
        val ev = Routines.yogaEvening
        val sv = ev.steps.indexOfLast { it is HoldStep }
        assertTrue(RoutineMath.score(ev, full(ev).filter { it.stepIndex != sv }).completed)

        // Повторения: 7 из 8 — выполнено (87,5 %), 6 из 8 — нет.
        val lfk = Routines.lfkJoints
        val ri = lfk.steps.indexOfFirst { it is RepsStep && it.reps == 8 }
        assertTrue(RoutineMath.score(lfk, full(lfk).map { if (it.stepIndex == ri) it.copy(reps = 7) else it }).completed)
        assertFalse(RoutineMath.score(lfk, full(lfk).map { if (it.stepIndex == ri) it.copy(reps = 6) else it }).completed)

        // Ничего не подтверждено — записывать нечего.
        assertFalse(RoutineMath.score(r, emptyList()).hasWork)
    }

    @Test
    fun dailyCap() {
        assertEquals(90_000L, RoutineMath.capped(90_000L, 0L))
        assertEquals(60_000L, RoutineMath.capped(90_000L, 240_000L))
        assertEquals(0L, RoutineMath.capped(90_000L, 300_000L))
        assertEquals(0L, RoutineMath.capped(90_000L, 400_000L))
        // Четыре полные программы за день упираются в 5 минут.
        var total = 0L
        for (r in Routines.all) total += RoutineMath.capped(RoutineMath.maxRewardMs(r), total)
        assertEquals(RoutineMath.DAILY_CAP_MS, total)
    }

    @Test
    fun setsForDiary() {
        val r = Routines.lfkBack
        val results = full(r)
        val sets = RoutineMath.sets(r, results, 45_000L) // предел срезал половину
        assertEquals(45_000L, sets.sumOf { it.earnedMs })
        val holds = sets.filter { it.measurement == Measurement.HOLD }
        // Спокойное сидение, колено к груди ×2, отдых лёжа — удержания; повторения — 6 шагов.
        assertEquals(4, holds.size)
        assertTrue(holds.all { it.reps == 0 && it.heldMs > 0 })
        assertEquals(6, sets.count { it.measurement == Measurement.REPS })
        assertEquals(40, sets.filter { it.measurement == Measurement.REPS }.sumOf { it.reps })
        // Неоплачиваемые удержания — с нулевой наградой.
        assertEquals(0L, holds.first { it.exerciseId == "lfk_sit_calm" }.earnedMs)
        // Одна сторона — одно имя упражнения (без «левая/правая»).
        assertEquals(setOf("Колено к груди лёжа"), sets.filter { it.exerciseId == "lfk_knee_to_chest" }.map { it.exerciseName }.toSet())
        // Повтор итога одного шага не удваивает награду.
        val doubled = results + results.first()
        assertEquals(RoutineMath.score(r, results).rawEarnedMs, RoutineMath.score(r, doubled).rawEarnedMs)
    }
}
