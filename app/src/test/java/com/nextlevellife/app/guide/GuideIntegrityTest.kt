package com.nextlevellife.app.guide

import com.nextlevellife.app.data.Exercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Целостность всех гидов: и встроенных, и добавленных другими наборами через [Guides]. */
class GuideIntegrityTest {
    private val all = Guides.all()

    /** Прогнать проверку по всем гидам и показать все провалы разом. */
    private fun forAll(check: (ExerciseGuide) -> List<String>) {
        val failures = all.flatMap { g -> check(g).map { "${g.exerciseId}: $it" } }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun everyExerciseHasGuide() {
        for (ex in Exercises.all) assertNotNull(ex.id, Guides.of(ex.id))
        assertNull(Guides.of("нет такого"))
        assertEquals("id гидов не повторяются", all.size, all.map { it.exerciseId }.toSet().size)
        // Встроенные наборы не перекрывают друг друга одинаковыми id (иначе один гид молча заменит другой).
        val builtIn = listOf(BodyweightGuides, KettlebellGuides, RoutineGuides).flatMap { p -> p.guides.map { it.exerciseId } }
        assertEquals("повторы id: ${builtIn.groupBy { it }.filter { it.value.size > 1 }.keys}", builtIn.size, builtIn.toSet().size)
    }

    @Test
    fun everyRoutineStepHasGuide() {
        val ids = com.nextlevellife.app.data.Routines.all.flatMap { r -> r.exercises.mapNotNull { it.guideId } }.toSet()
        assertTrue("в программах нет шагов с гидом", ids.size >= 20)
        val missing = ids.filter { Guides.of(it) == null }
        assertTrue("нет гида для $missing", missing.isEmpty())
        // Расстояние и ракурс гида не противоречат шагу.
        val conflicts = com.nextlevellife.app.data.Routines.all.flatMap { it.exercises }.mapNotNull { step ->
            val g = Guides.of(step.guideId ?: return@mapNotNull null)!!
            val sameAngle = step.camera.lowercase().startsWith(g.angle)
            if (g.distance != step.distance || !sameAngle) "${step.guideId}: «${step.cameraLine}» ≠ «${g.cameraLine}»" else null
        }
        assertTrue(conflicts.joinToString("\n", prefix = "\n"), conflicts.isEmpty())
        // У поз программ — свой заголовок.
        for (id in ids) assertTrue(id, !Guides.of(id)!!.title.isNullOrBlank())
    }

    @Test
    fun framesHaveAllJointsInsideScene() = forAll { g ->
        buildList {
            if (g.frames.size < 2) add("кадров меньше двух")
            g.frames.forEachIndexed { i, f ->
                val missing = GuideJoint.entries - f.pose.points.keys
                if (missing.isNotEmpty()) add("кадр $i: нет $missing")
                for ((j, p) in f.pose.points) {
                    if (!(p.x in 0f..1f && p.y in 0f..1f)) add("кадр $i: $j вне сцены ($p)")
                }
                if (f.moveMs <= 0) add("кадр $i: переход ${f.moveMs}")
                if (f.holdMs < 0) add("кадр $i: остановка ${f.holdMs}")
            }
            val cycle = GuideMath.cycleMs(g)
            if (cycle !in 2_000L..9_000L) add("круг $cycle мс")
            // Голова выше пола и не уходит под него в переходах.
            for (t in 0 until cycle step 50) {
                val head = GuideMath.poseAt(g, t)[GuideJoint.HEAD]
                if (head.y + GuideScene.HEAD_RADIUS > GuideScene.FLOOR + 0.01f) {
                    add("голова под полом в $t мс")
                    break
                }
            }
        }
    }

    @Test
    fun bonesKeepLengthAcrossFrames() = forAll { g ->
        buildList {
            val first = g.frames[0].pose
            for (f in g.frames) for ((a, b) in GuideScene.BONES) {
                val l0 = kotlin.math.hypot(first[a].x - first[b].x, first[a].y - first[b].y)
                val l = kotlin.math.hypot(f.pose[a].x - f.pose[b].x, f.pose[a].y - f.pose[b].y)
                if (kotlin.math.abs(l - l0) > 0.002f) add("кость $a-$b: $l0 → $l")
            }
        }
    }

    @Test
    fun textsAreFilledAndConsistent() = forAll { g ->
        buildList {
            val texts = listOf(g.distance, g.angle, g.camera, g.focus) + g.tips + g.mistakes
            if (texts.any { it.isBlank() }) add("пустой текст")
            if (g.tips.size !in 2..4) add("подсказок ${g.tips.size}")
            if (g.mistakes.size !in 1..2) add("ошибок ${g.mistakes.size}")
            for (t in listOf(g.camera, g.focus) + g.tips + g.mistakes) {
                if (t.trimEnd().endsWith(".")) add("точка в конце: «$t»")
                if (t != t.trim()) add("пробелы по краям: «$t»")
                if (!t.first().isUpperCase()) add("со строчной: «$t»")
            }
            if (!g.distance.endsWith(" м")) add("расстояние: «${g.distance}»")
            if (g.angle.first().isUpperCase()) add("ракурс с прописной: «${g.angle}»")
            if (!g.cameraLine.startsWith("Камера: ${g.distance} · ")) add("строка камеры: ${g.cameraLine}")
        }
    }

    @Test
    fun registerAddsAndReplaces() {
        val base = Guides.of("squats")!!
        val extra = base.copy(exerciseId = "test_extra_pose")
        Guides.register(extra)
        assertEquals(extra, Guides.of("test_extra_pose"))
        assertTrue(Guides.all().any { it.exerciseId == "test_extra_pose" })
    }
}
