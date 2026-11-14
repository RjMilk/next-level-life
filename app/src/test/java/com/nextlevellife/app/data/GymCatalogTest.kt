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
    )

    @Test fun allRequestedEntriesHaveFixedProjectPricesAndNoClassC() {
        assertEquals(25, Exercises.gym.size)
        assertEquals(25, Exercises.gym.map { it.id }.toSet().size)
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

    @Test fun classAReferencesExistingProfilesWithoutCopies() {
        assertEquals("squats", Exercises.barbellFrontSquat.counterId)
        assertEquals("row", Exercises.dumbbellRow.counterId)
        assertEquals("curl", Exercises.dumbbellCurl.counterId)
        assertEquals("goblet_squat", Exercises.dumbbellGobletSquat.counterId)
        assertEquals("pullups", Exercises.assistedPullup.counterId)
    }
}
