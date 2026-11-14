package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymMathTest {
    private val nbsp = ' '
    private val barbell = LoadMode.BARBELL_TOTAL
    private val squatSpec = Exercises.barbellSquat.gym!!
    private val pressSpec = Exercises.barbellPress.gym!!

    private fun s(day: Long, reps: Int, loadG: Int?, timeMs: Long = day * 86_400_000L + reps) = GymSet(day, timeMs, reps, loadG)

    // ---------- Упражнения и цены ----------

    @Test
    fun fourBarbellExercisesWithReusedProfiles() {
        assertEquals(listOf("bb_squat", "bb_deadlift", "bb_press", "bb_row"), Exercises.gym.map { it.id })
        assertEquals(
            listOf("Присед со штангой", "Становая тяга со штангой", "Жим штанги стоя", "Тяга штанги в наклоне"),
            Exercises.gym.map { it.name },
        )
        assertEquals(listOf("squats", "deadlift", "press_barbell", "row_barbell"), Exercises.gym.map { it.counterId })
        for (ex in Exercises.gym) {
            assertTrue(ex.isGym)
            assertFalse(ex.kettlebell)
            assertFalse(ex.hold)
            assertEquals(Equipment.BARBELL, ex.gym!!.equipment)
            assertEquals(LoadMode.BARBELL_TOTAL, ex.gym!!.loadMode)
            assertEquals(ex, Exercises.byId(ex.id))
            // Зал — не в домашних: в свои программы и комплексы не попадает.
            assertFalse(ex in Exercises.all)
            assertTrue(ex in Exercises.catalog)
        }
        // У домашних профиль счёта — их id, зала нет.
        for (ex in Exercises.all) {
            assertEquals(ex.id, ex.counterId)
            assertFalse(ex.isGym)
        }
        assertNull(Exercises.byIdOrNull("нет такого"))
        // Шаг роста: присед и становая +5 кг, жим и тяга +2,5 кг.
        assertEquals(listOf(5_000, 5_000, 2_500, 2_500), Exercises.gym.map { it.gym!!.stepUpG })
        // Минимум секунд на повтор — как у гиревых собратьев.
        assertEquals(Exercises.gobletSquat.minSecondsPerRep, Exercises.barbellSquat.minSecondsPerRep, 0.0)
        assertEquals(Exercises.deadlift.minSecondsPerRep, Exercises.barbellDeadlift.minSecondsPerRep, 0.0)
        assertEquals(Exercises.press.minSecondsPerRep, Exercises.barbellPress.minSecondsPerRep, 0.0)
        assertEquals(Exercises.row.minSecondsPerRep, Exercises.barbellRow.minSecondsPerRep, 0.0)
    }

    @Test
    fun pricesAreFixedPerRepWithoutWeightFactor() {
        assertEquals(listOf(40, 35, 30, 30), Exercises.gym.map { it.rewardSecondsPerRep })
        for (ex in Exercises.gym) {
            val base = Exercises.rewardMs(ex, 10, Weights.DEFAULT)
            assertEquals(ex.rewardSecondsPerRep * 10_000L, base)
            // Вес гири не влияет на цену зала (множителя нет), заявленный вес штанги в цену не входит вовсе.
            for (w in Weights.all + listOf(0, 100, 400)) assertEquals(base, Exercises.rewardMs(ex, 10, w))
        }
        assertEquals(40_000L, Exercises.rewardMs(Exercises.barbellSquat, 1, 32))
    }

    @Test
    fun setLoadOnlyForGymAndNormalized() {
        assertEquals(60_000, WorkoutMath.setLoad(Exercises.barbellSquat, 60_000))
        assertEquals(60_500, WorkoutMath.setLoad(Exercises.barbellSquat, 60_400))
        assertEquals(20_000, WorkoutMath.setLoad(Exercises.barbellSquat, 5_000))
        assertEquals(400_000, WorkoutMath.setLoad(Exercises.barbellSquat, 1_000_000))
        assertNull(WorkoutMath.setLoad(Exercises.barbellSquat, null))
        assertNull(WorkoutMath.setLoad(Exercises.pushups, 60_000))
        assertNull(WorkoutMath.setLoad(Exercises.press, 16_000))
    }

    // ---------- Вес: пределы и шаг ----------

    @Test
    fun loadModesLimitsAndSteps() {
        assertEquals(listOf(20_000, 400_000, 500, 20_000), barbell.let { listOf(it.minG, it.maxG, it.stepG, it.defaultG) })
        assertEquals(listOf(1_000, 80_000, 500), LoadMode.DUMBBELL_EACH.let { listOf(it.minG, it.maxG, it.stepG) })
        assertEquals(listOf(1_000, 300_000, 500), LoadMode.STACK.let { listOf(it.minG, it.maxG, it.stepG) })
        assertEquals(listOf(0, 150_000, 500), LoadMode.COUNTERWEIGHT.let { listOf(it.minG, it.maxG, it.stepG) })
        assertTrue(LoadMode.COUNTERWEIGHT.assists)
        assertTrue(LoadMode.entries.filter { it != LoadMode.COUNTERWEIGHT }.none { it.assists })
        for (m in LoadMode.entries) {
            assertTrue(m.name, GymMath.isValid(m, m.defaultG))
            // Пределы любого способа укладываются в общие пределы обмена.
            assertTrue(m.minG >= 0 && m.maxG <= GymMath.MAX_LOAD_G && m.stepG % GymMath.LOAD_STEP_G == 0)
        }
    }

    @Test
    fun normalizeRoundsToStepAndClamps() {
        assertEquals(20_000, GymMath.normalize(barbell, 0))
        assertEquals(20_000, GymMath.normalize(barbell, -5_000))
        assertEquals(20_000, GymMath.normalize(barbell, 20_249))
        assertEquals(20_500, GymMath.normalize(barbell, 20_250))
        assertEquals(62_500, GymMath.normalize(barbell, 62_500))
        assertEquals(400_000, GymMath.normalize(barbell, 400_000))
        assertEquals(400_000, GymMath.normalize(barbell, 400_400))
        assertEquals(400_000, GymMath.normalize(barbell, Int.MAX_VALUE))
        assertEquals(1_000, GymMath.normalize(LoadMode.DUMBBELL_EACH, 0))
        assertEquals(80_000, GymMath.normalize(LoadMode.DUMBBELL_EACH, 90_000))
        assertEquals(0, GymMath.normalize(LoadMode.COUNTERWEIGHT, -1))
        assertEquals(150_000, GymMath.normalize(LoadMode.COUNTERWEIGHT, 151_000))

        assertTrue(GymMath.isValid(barbell, 20_000))
        assertTrue(GymMath.isValid(barbell, 400_000))
        assertTrue(GymMath.isValid(barbell, 62_500))
        assertFalse(GymMath.isValid(barbell, 19_500))
        assertFalse(GymMath.isValid(barbell, 400_500))
        assertFalse(GymMath.isValid(barbell, 62_250))
        assertTrue(GymMath.isValid(LoadMode.COUNTERWEIGHT, 0))
    }

    @Test
    fun adjustButtonsStayInLimits() {
        assertEquals(22_500, GymMath.adjust(barbell, 20_000, 2_500))
        assertEquals(20_500, GymMath.adjust(barbell, 20_000, 500))
        assertEquals(20_000, GymMath.adjust(barbell, 20_000, -2_500))
        assertEquals(20_000, GymMath.adjust(barbell, 20_500, -2_500))
        assertEquals(400_000, GymMath.adjust(barbell, 399_000, 2_500))
        assertEquals(59_500, GymMath.adjust(barbell, 60_000, -500))
    }

    @Test
    fun formatting() {
        assertEquals("20${nbsp}кг", GymMath.formatKg(20_000))
        assertEquals("62,5${nbsp}кг", GymMath.formatKg(62_500))
        assertEquals("0${nbsp}кг", GymMath.formatKg(0))
        assertEquals("400${nbsp}кг", GymMath.formatKg(400_000))
        assertEquals("+2,5${nbsp}кг", GymMath.formatDelta(2_500))
        assertEquals("−0,5${nbsp}кг", GymMath.formatDelta(-500))
        assertEquals("Попробуй 65${nbsp}кг", GymMath.adviceText(GymAdvice.Increase(60_000, 65_000)))
        assertEquals("Оставь 60${nbsp}кг", GymMath.adviceText(GymAdvice.Keep(60_000)))
        assertTrue("минуты" in GymMath.LOAD_NOTE && "очки" in GymMath.LOAD_NOTE)
    }

    // ---------- История и рекомендации ----------

    @Test
    fun historyIsBestWorkingLoadPerDay() {
        val sets = listOf(
            s(10, 5, 60_000), s(10, 5, 65_000), s(10, 3, 80_000), // 80 кг на 3 — не рабочий
            s(12, 4, 70_000), // день без рабочих подходов — в истории его нет
            s(14, 6, 62_500), s(14, 8, null), // без веса — не в истории
        )
        assertEquals(listOf(GymDay(10, 65_000), GymDay(14, 62_500)), GymMath.history(barbell, sets))
        assertEquals(emptyList<GymDay>(), GymMath.history(barbell, emptyList()))
        // У противовеса рабочий вес дня — наименьший.
        val assisted = listOf(s(1, 5, 30_000), s(1, 6, 25_000), s(1, 2, 10_000))
        assertEquals(listOf(GymDay(1, 25_000)), GymMath.history(LoadMode.COUNTERWEIGHT, assisted))
    }

    @Test
    fun threeSetsOfFiveWithSameLoadMeanIncrease() {
        val last = listOf(s(20, 5, 60_000), s(20, 5, 60_000), s(20, 6, 60_000), s(20, 3, 50_000))
        assertEquals(GymAdvice.Increase(60_000, 65_000), GymMath.advice(squatSpec, last))
        assertEquals("Попробуй 65${nbsp}кг", GymMath.adviceText(GymMath.advice(squatSpec, last)!!))
        // Жим и тяга — +2,5 кг.
        assertEquals(GymAdvice.Increase(60_000, 62_500), GymMath.advice(pressSpec, last))
        assertEquals(GymAdvice.Increase(60_000, 62_500), GymMath.advice(Exercises.barbellRow.gym!!, last))
        assertEquals(GymAdvice.Increase(60_000, 65_000), GymMath.advice(Exercises.barbellDeadlift.gym!!, last))
    }

    @Test
    fun otherwiseKeep() {
        // Два подхода по 5 — мало.
        assertEquals(GymAdvice.Keep(60_000), GymMath.advice(squatSpec, listOf(s(20, 5, 60_000), s(20, 5, 60_000))))
        // Три подхода, но лучший вес только в одном: W — лучший, его оставить.
        val mixed = listOf(s(20, 5, 55_000), s(20, 5, 55_000), s(20, 5, 60_000))
        assertEquals(GymAdvice.Keep(60_000), GymMath.advice(squatSpec, mixed))
        // Третий подход на 4 повторения — не рабочий.
        assertEquals(GymAdvice.Keep(60_000), GymMath.advice(squatSpec, listOf(s(20, 5, 60_000), s(20, 5, 60_000), s(20, 4, 60_000))))
        // Подходы разных дней не складываются: решает только последняя тренировка.
        val split = listOf(s(18, 5, 60_000), s(18, 5, 60_000), s(20, 5, 60_000))
        assertEquals(GymAdvice.Keep(60_000), GymMath.advice(squatSpec, split))
        val olderGood = listOf(s(18, 5, 60_000), s(18, 5, 60_000), s(18, 5, 60_000), s(20, 5, 62_500))
        assertEquals(GymAdvice.Keep(62_500), GymMath.advice(squatSpec, olderGood))
    }

    @Test
    fun lastWorkoutWithoutWorkingSetsKeepsPreviousLoad() {
        val sets = listOf(s(18, 5, 60_000), s(20, 3, 70_000), s(20, 2, 70_000))
        assertEquals(GymAdvice.Keep(60_000), GymMath.advice(squatSpec, sets))
        // Рабочих подходов не было никогда — совета нет.
        assertNull(GymMath.advice(squatSpec, listOf(s(20, 3, 70_000))))
        assertNull(GymMath.advice(squatSpec, emptyList()))
        // Подходы без веса не делают день «последней тренировкой».
        assertEquals(
            GymAdvice.Increase(60_000, 65_000),
            GymMath.advice(squatSpec, listOf(s(18, 5, 60_000), s(18, 5, 60_000), s(18, 5, 60_000), s(20, 8, null))),
        )
    }

    @Test
    fun increaseStopsAtLimitAndCounterweightGoesDown() {
        val top = listOf(s(1, 5, 400_000), s(1, 5, 400_000), s(1, 5, 400_000))
        assertEquals(GymAdvice.Keep(400_000), GymMath.advice(squatSpec, top))
        val near = listOf(s(1, 5, 397_500), s(1, 5, 397_500), s(1, 5, 397_500))
        assertEquals(GymAdvice.Increase(397_500, 400_000), GymMath.advice(squatSpec, near))
        // Гравитрон: прогресс — меньше противовеса; у нуля — остаться.
        val assist = GymSpec(Equipment.ASSISTED, LoadMode.COUNTERWEIGHT, stepUpG = 5_000)
        val g = listOf(s(1, 5, 30_000), s(1, 6, 30_000), s(1, 5, 30_000))
        assertEquals(GymAdvice.Increase(30_000, 25_000), GymMath.advice(assist, g))
        val zero = listOf(s(1, 5, 0), s(1, 5, 0), s(1, 5, 0))
        assertEquals(GymAdvice.Keep(0), GymMath.advice(assist, zero))
    }

    @Test
    fun defaultLoadIsLastDeclaredOrEmptyBar() {
        assertEquals(20_000, GymMath.defaultLoad(barbell, emptyList()))
        assertEquals(20_000, GymMath.defaultLoad(barbell, listOf(s(1, 5, null))))
        // Последний по времени, даже если в нём меньше пяти повторений.
        val sets = listOf(s(1, 5, 60_000, timeMs = 100), s(2, 2, 72_500, timeMs = 300), s(2, 5, 70_000, timeMs = 200))
        assertEquals(72_500, GymMath.defaultLoad(barbell, sets))
        assertEquals(30_000, GymMath.defaultLoad(LoadMode.COUNTERWEIGHT, emptyList()))
    }

    @Test
    fun summaryForCardAndProgress() {
        val sets = listOf(
            s(10, 5, 80_000), s(10, 5, 80_000),
            s(12, 5, 75_000), s(12, 5, 75_000), s(12, 5, 75_000),
        )
        val sum = GymMath.summary(squatSpec, sets)
        assertEquals(75_000, sum.lastWorkingG)
        assertEquals(80_000, sum.bestWorkingG)
        assertEquals(GymAdvice.Increase(75_000, 80_000), sum.advice)
        assertEquals(75_000, sum.defaultLoadG)
        assertEquals(listOf(GymDay(10, 80_000), GymDay(12, 75_000)), sum.history)
        assertEquals("Рабочий вес 75${nbsp}кг", GymMath.workingLine(sum))
        val empty = GymMath.summary(squatSpec, emptyList())
        assertNull(empty.lastWorkingG)
        assertNull(empty.bestWorkingG)
        assertNull(empty.advice)
        assertEquals(20_000, empty.defaultLoadG)
        assertEquals("Рабочего веса ещё нет", GymMath.workingLine(empty))
        assertEquals("Рабочего веса ещё нет", GymMath.workingLine(null))
    }

    // ---------- Схема ----------

    @Test
    fun migration12AddsLoadColumnOnly() {
        assertTrue(Diary.SCHEMA_VERSION >= 12)
        assertEquals(listOf("ALTER TABLE sets ADD COLUMN load_g INTEGER"), Diary.MIGRATIONS[11])
    }
}
