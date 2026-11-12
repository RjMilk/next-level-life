package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DiaryMathTest {

    private fun set(id: String, name: String, weight: Int?, reps: Int, time: Long = 0) =
        SetEntry(time, time, 0, id, name, weight, reps, 0, 0, 0, null)

    @Test
    fun totalsGroupByExerciseAndWeightInOrderOfFirstSet() {
        val totals = DiaryMath.totals(
            listOf(
                set("swings", "Мах гирей", 16, 20),
                set("pushups", "Отжимания", null, 10),
                set("swings", "Мах гирей", 24, 15),
                set("swings", "Мах гирей", 16, 25),
                set("pushups", "Отжимания", null, 12),
            )
        )
        assertEquals(
            listOf(
                ExerciseTotal("swings", "Мах гирей", 16, 2, 45),
                ExerciseTotal("pushups", "Отжимания", null, 2, 22),
                ExerciseTotal("swings", "Мах гирей", 24, 1, 15),
            ),
            totals,
        )
    }

    @Test
    fun totalsUseLatestName() {
        val totals = DiaryMath.totals(listOf(set("abs", "Скручивания", null, 10), set("abs", "Пресс", null, 5)))
        assertEquals("Пресс", totals.single().exerciseName)
    }

    @Test
    fun dayOfUsesLocalZone() {
        val zone = ZoneId.of("Europe/Moscow")
        val lateEvening = LocalDateTime.of(2026, 11, 12, 23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), DiaryMath.dayOf(lateEvening, zone))
        // То же мгновение в UTC ещё 12-е, а в Токио уже 13-е.
        assertEquals(LocalDate.of(2026, 11, 13).toEpochDay(), DiaryMath.dayOf(lateEvening, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun betterRounds() {
        assertTrue(DiaryMath.isBetterRounds(1 to 0, null))
        assertTrue(DiaryMath.isBetterRounds(5 to 3, 4 to 29))
        assertTrue(DiaryMath.isBetterRounds(5 to 4, 5 to 3))
        assertFalse(DiaryMath.isBetterRounds(5 to 3, 5 to 3))
        assertFalse(DiaryMath.isBetterRounds(4 to 29, 5 to 0))
    }

    @Test
    fun parsesLegacyBodyweightSet() {
        assertEquals(DiaryMath.LegacySet(Exercises.pushups, null, 20), DiaryMath.parseLegacy("Отжимания ×20"))
    }

    @Test
    fun parsesLegacyKettlebellSet() {
        assertEquals(DiaryMath.LegacySet(Exercises.sumoPull, 24, 12), DiaryMath.parseLegacy("Тяга сумо к подбородку 24 кг ×12"))
        // Старые записи гиревых без веса — с гирей по умолчанию.
        assertEquals(DiaryMath.LegacySet(Exercises.swing, 16, 30), DiaryMath.parseLegacy("Мах гирей ×30"))
    }

    @Test
    fun parsesLegacyMinotaur() {
        assertEquals(DiaryMath.LegacyMinotaur(24, (21 * 60 + 5) * 1000L), DiaryMath.parseLegacy("Минотавр 24 кг · 21:05"))
    }

    @Test
    fun skipsUnknownLegacyTitles() {
        assertNull(DiaryMath.parseLegacy("Бёрпи ×10"))
        assertNull(DiaryMath.parseLegacy("Отжимания ×0"))
        assertNull(DiaryMath.parseLegacy("Что-то странное"))
    }
}
