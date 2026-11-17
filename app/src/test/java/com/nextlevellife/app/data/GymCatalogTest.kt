package com.nextlevellife.app.data

import org.junit.Assert.*
import org.junit.Test

class GymCatalogTest {
    private val expectedPrices = mapOf(
        "bb_front_squat" to 45, "bb_romanian_deadlift" to 35, "bb_bench_press" to 30, "bb_incline_press" to 30,
        "db_bench_press" to 30, "db_incline_press" to 30, "db_seated_press" to 30, "db_row" to 25,
        "db_curl" to 15, "db_lateral_raise" to 10, "db_lunge" to 30, "db_bulgarian_split_squat" to 35,
        "db_goblet_squat" to 40, "machine_lat_pulldown" to 25, "machine_seated_row" to 25,
        "machine_chest_press" to 25, "machine_leg_extension" to 15, "machine_leg_curl" to 15,
        "machine_lying_leg_curl" to 15, "assisted_pullup" to 35, "hyperextension" to 20,
        "bars_dip" to 40, "bars_leg_raise" to 25,
    )

    @Test fun allRequestedEntriesHaveFixedProjectPricesAndNoClassC() {
        assertEquals(27, Exercises.gym.size)
        assertEquals(27, Exercises.gym.map { it.id }.toSet().size)
        for ((id, price) in expectedPrices) {
            val ex = Exercises.byId(id)
            assertTrue(id, ex in Exercises.gym && ex !in Exercises.all)
            assertEquals(id, price, ex.rewardSecondsPerRep)
            for (weight in listOf(0, 1, 16, 32, 100, 400)) assertEquals(id, price * 7000L, Exercises.rewardMs(ex, 7, weight))
            assertFalse(id, ex.kettlebell || ex.hold)
        }
        assertFalse(Exercises.gym.any { "leg_press" in it.id || "crossover" in it.id })
    }

    @Test fun gymSpecsDescribeDeclaredWeightAndSensibleAdviceSteps() {
        for (id in expectedPrices.keys) {
            val spec = Exercises.byId(id).gym!!
            val expected = when {
                id.startsWith("bb_") -> Equipment.BARBELL to LoadMode.BARBELL_TOTAL
                id.startsWith("db_") -> Equipment.DUMBBELL to LoadMode.DUMBBELL_EACH
                id == "assisted_pullup" -> Equipment.ASSISTED to LoadMode.COUNTERWEIGHT
                id == "hyperextension" -> Equipment.MACHINE to LoadMode.EXTERNAL_ADDED
                id.startsWith("bars_") -> Equipment.BARS to LoadMode.BELT_ADDED
                else -> Equipment.MACHINE to LoadMode.STACK
            }
            assertEquals(id, expected.first, spec.equipment)
            assertEquals(id, expected.second, spec.loadMode)
            assertTrue(id, spec.stepUpG > 0 && spec.stepUpG % spec.loadMode.stepG == 0)
        }
        assertTrue(Exercises.assistedPullup.gym!!.loadMode.assists)
        assertEquals(0, Exercises.hyperextension.gym!!.loadMode.defaultG)
        assertTrue(GymMath.isValid(Exercises.hyperextension.gym!!.loadMode, 0))
        assertEquals(GymAdvice.Increase(30_000, 27_500), GymMath.advice(Exercises.assistedPullup.gym!!,
            List(3) { GymSet(1L, it.toLong(), 5, 30_000) }))
    }

    @Test fun barsUseBeltWeightFromZeroAndIncreaseByTwoAndAHalfKilos() {
        val mode = LoadMode.BELT_ADDED
        assertEquals(listOf(0, 100_000, 500, 0), listOf(mode.minG, mode.maxG, mode.stepG, mode.defaultG))
        assertFalse(mode.assists)
        assertTrue("на поясе" in mode.title)
        for (ex in listOf(Exercises.barsDip, Exercises.barsLegRaise)) {
            val spec = ex.gym!!
            assertEquals(ex.id, Equipment.BARS, spec.equipment)
            assertEquals(ex.id, LoadMode.BELT_ADDED, spec.loadMode)
            assertEquals(ex.id, 2_500, spec.stepUpG)
            assertEquals(ex.id, ex.id, ex.counterId)
            // Свой вес — по умолчанию 0; три подхода по 5 — совет «+2,5 кг».
            assertEquals(0, GymMath.defaultLoad(mode, emptyList()))
            assertEquals(GymAdvice.Increase(0, 2_500), GymMath.advice(spec, List(3) { GymSet(1L, it.toLong(), 5, 0) }))
            assertEquals(GymAdvice.Increase(10_000, 12_500), GymMath.advice(spec, List(3) { GymSet(1L, it.toLong(), 6, 10_000) }))
            // У предела совет не растёт.
            assertEquals(GymAdvice.Keep(100_000), GymMath.advice(spec, List(3) { GymSet(1L, it.toLong(), 5, 100_000) }))
            // «Лучший» вес — больший.
            val summary = GymMath.summary(spec, listOf(GymSet(1L, 1L, 5, 5_000), GymSet(2L, 2L, 5, 2_500)))
            assertEquals(5_000, summary.bestWorkingG)
            assertEquals(2_500, summary.lastWorkingG)
            // Обмен: вес в пределах и на шаге способа учёта.
            for (ok in listOf(null, 0, 500, 2_500, 100_000)) assertTrue("${ex.id} $ok", GymMath.acceptsLoad(ex.id, ok))
            for (bad in listOf(250, 100_500, 150_000, -500)) assertFalse("${ex.id} $bad", GymMath.acceptsLoad(ex.id, bad))
        }
        assertEquals(40, Exercises.barsDip.rewardSecondsPerRep)
        assertEquals(25, Exercises.barsLegRaise.rewardSecondsPerRep)
        assertEquals(10 * 40_000L, Exercises.rewardMs(Exercises.barsDip, 10, 32))
        assertEquals(10 * 25_000L, Exercises.rewardMs(Exercises.barsLegRaise, 10, 0))
    }

    @Test fun classAReferencesExistingProfilesWithoutCopies() {
        assertEquals("squats", Exercises.barbellFrontSquat.counterId)
        assertEquals("row", Exercises.dumbbellRow.counterId)
        assertEquals("curl", Exercises.dumbbellCurl.counterId)
        assertEquals("goblet_squat", Exercises.dumbbellGobletSquat.counterId)
        assertEquals("pullups", Exercises.assistedPullup.counterId)
    }
}
