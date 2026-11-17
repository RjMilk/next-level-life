package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.guide.CameraSetup
import com.nextlevellife.app.guide.Guides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Установка камеры для гиревых: то, что реально считается (записи тестировщика), а не «в пол-оборота». */
class CameraSetupTextTest {
    private fun guide(id: String) = Guides.of(id) ?: error("нет гида $id")

    @Test
    fun faceOnExercises() {
        for (id in listOf("thruster", "swings", "sumo_pull")) {
            assertTrue(id, id in CameraSetup.FACE)
            assertEquals(id, CameraSetup.FACE_SETUP, setupLine(id))
            assertEquals(id, CameraSetup.FACE_ANGLE, guide(id).angle)
            assertEquals(id, CameraSetup.FACE_DISTANCE, guide(id).distance)
            assertTrue(id, guide(id).camera.contains("руки над головой"))
        }
        assertTrue(CameraSetup.FACE_SETUP.contains("лицом"))
        assertTrue(CameraSetup.FACE_SETUP.contains("уровне пояса"))
    }

    @Test
    fun hingeExercisesStaySideOn() {
        for (id in listOf("row", "deadlift")) {
            assertTrue(id, setupLine(id).contains("боком"))
            assertEquals(id, "сбоку", guide(id).angle)
        }
    }

    @Test
    fun noUnverifiedHalfTurn() {
        for (id in Minotaur.exercises.map { it.id } + "squats") {
            assertFalse(id, setupLine(id).contains("пол-оборота"))
            assertFalse(id, guide(id).angle.contains("пол-оборота"))
        }
        assertFalse(CameraSetup.MINOTAUR.contains("пол-оборота"))
    }

    @Test
    fun minotaurSetupNamesEveryExercise() {
        val text = CameraSetup.MINOTAUR.lowercase()
        for (word in listOf("трастер", "мах", "тягу сумо", "лицом", "тягу в наклоне", "становую", "боком", "руки над головой")) {
            assertTrue(word, text.contains(word))
        }
        assertEquals(setOf("thruster", "swings", "sumo_pull", "row", "deadlift"), Minotaur.exercises.map { it.id }.toSet())
    }
}
