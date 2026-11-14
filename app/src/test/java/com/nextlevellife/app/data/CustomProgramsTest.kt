package com.nextlevellife.app.data

import com.nextlevellife.app.pose.RepCounters
import com.nextlevellife.app.ui.ProgramEditorText
import com.nextlevellife.app.ui.ProgressMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Свои домашние программы ([CustomProgramMath]): хранение, ограничения, награда как у готовых. */
class CustomProgramsTest {
    private val m = CustomProgramMath

    private fun program(id: String, title: String, vararg steps: ProgramStep) =
        Program(id, title, m.SUBTITLE, steps.toList())

    private val sample = program(
        "home_c_abc12345", "Утро «дома» \\ \"кавычки\"\nи перевод",
        ProgramStep(Exercises.squats, 3, 12, 45),
        ProgramStep(Exercises.plank, 2, 40, 60),
        ProgramStep(Exercises.press, 4, 8, 90),
        ProgramStep(Exercises.squats, 1, 20, 15),
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val other = program("home_c_zz9900aa", "Вечер", ProgramStep(Exercises.swing, 5, 25, 30))
        val text = m.encode(listOf(sample, other))
        assertEquals(listOf(sample, other), m.decode(text))
        // Повторное кодирование даёт ту же строку.
        assertEquals(text, m.encode(m.decode(text)))
        assertEquals(emptyList<Program>(), m.decode(m.encode(emptyList())))
    }

    @Test
    fun brokenTextGivesEmptyList() {
        listOf(null, "", "  ", "{", "[]", "{\"programs\":5}", "nonsense", "{\"programs\":[1,\"x\",null]}")
            .forEach { assertEquals(it.toString(), emptyList<Program>(), m.decode(it)) }
    }

    @Test
    fun unknownExerciseStepDroppedEmptyProgramDropped() {
        val text = """
            {"v":1,"programs":[
              {"id":"home_c_aaaaaaaa","title":"Смешанная","steps":[
                {"ex":"jumping_jacks","sets":3,"target":10,"rest":60},
                {"ex":"pushups","sets":2,"target":8,"rest":45},
                {"sets":2,"target":8,"rest":45}
              ]},
              {"id":"home_c_bbbbbbbb","title":"Пустая","steps":[{"ex":"burpee","sets":3,"target":10,"rest":60}]},
              {"id":"home_c_cccccccc","title":"Без шагов","steps":[]},
              {"id":"home_c_dddddddd","title":"Нет списка"}
            ]}
        """.trimIndent()
        val list = m.decode(text)
        assertEquals(listOf("home_c_aaaaaaaa"), list.map { it.id })
        assertEquals(listOf(ProgramStep(Exercises.pushups, 2, 8, 45)), list[0].steps)
    }

    @Test
    fun valuesClampedOnLoad() {
        val text = """
            {"programs":[{"id":"home_c_aaaaaaaa","title":"   ","steps":[
              {"ex":"squats","sets":99,"target":500,"rest":5},
              {"ex":"pushups","sets":0,"target":-3,"rest":1000},
              {"ex":"plank","sets":2,"target":3,"rest":50},
              {"ex":"plank","sets":2,"target":999,"rest":"x"},
              {"ex":"plank","sets":2.7,"target":42,"rest":100},
              {"ex":"abs"}
            ]}]}
        """.trimIndent()
        val steps = m.decode(text).single().steps
        assertEquals(ProgramStep(Exercises.squats, 6, 50, 15), steps[0])
        assertEquals(ProgramStep(Exercises.pushups, 1, 1, 180), steps[1])
        assertEquals(ProgramStep(Exercises.plank, 2, 10, 45), steps[2])
        assertEquals(ProgramStep(Exercises.plank, 2, 180, 60), steps[3])
        // Удержание — с шагом 5 с, отдых — с шагом 15 с.
        assertEquals(ProgramStep(Exercises.plank, 2, 40, 105), steps[4])
        assertEquals(ProgramStep(Exercises.abs, 3, 10, 60), steps[5])
        // Пустое название при загрузке — «Своя программа».
        assertEquals("Своя программа", m.decode(text).single().title)
    }

    @Test
    fun loadLimits() {
        val many = (0 until 15).map { i ->
            program("home_c_" + "a".repeat(7) + ('a' + i), "П$i", *Array(14) { ProgramStep(Exercises.abs, 3, 10, 60) })
        }
        val list = m.decode(m.encode(many))
        assertEquals(m.PROGRAMS_MAX, list.size)
        list.forEach { assertEquals(m.STEPS_MAX, it.steps.size) }
        // Повтор id и чужие id отбрасываются; длинное название обрезается.
        val dup = m.decode(
            m.encode(
                listOf(
                    program("home_c_aaaaaaaa", "x".repeat(60), ProgramStep(Exercises.abs, 3, 10, 60)),
                    program("home_c_aaaaaaaa", "Второй", ProgramStep(Exercises.abs, 3, 10, 60)),
                    program("home_beginner", "Чужой", ProgramStep(Exercises.abs, 3, 10, 60)),
                    program("home_c_ABCDEFGH", "Заглавные", ProgramStep(Exercises.abs, 3, 10, 60)),
                )
            )
        )
        assertEquals(listOf("home_c_aaaaaaaa"), dup.map { it.id })
        assertEquals(m.TITLE_MAX, dup[0].title.length)
    }

    @Test
    fun stepButtonsClamp() {
        val squat = m.defaultStep(Exercises.squats)
        assertEquals(ProgramStep(Exercises.squats, 3, 10, 60), squat)
        assertEquals(4, m.withSets(squat, 1).sets)
        assertEquals(6, m.withSets(squat.copy(sets = 6), 1).sets)
        assertEquals(1, m.withSets(squat.copy(sets = 1), -1).sets)
        assertEquals(11, m.withTarget(squat, 1).target)
        assertEquals(50, m.withTarget(squat.copy(target = 50), 1).target)
        assertEquals(75, m.withRest(squat, 1).restSeconds)
        assertEquals(180, m.withRest(squat.copy(restSeconds = 180), 1).restSeconds)
        assertEquals(15, m.withRest(squat.copy(restSeconds = 15), -1).restSeconds)
        assertFalse(m.canChange(squat.copy(sets = 6), StepField.SETS, 1))
        assertTrue(m.canChange(squat, StepField.SETS, 1))
        assertFalse(m.canChange(squat.copy(restSeconds = 15), StepField.REST, -1))

        val plank = m.defaultStep(Exercises.plank)
        assertEquals(30, plank.target)
        assertEquals(35, m.withTarget(plank, 1).target)
        assertEquals(10, m.withTarget(plank.copy(target = 10), -1).target)
        assertEquals(180, m.withTarget(plank.copy(target = 180), 1).target)
        assertFalse(m.canChange(plank.copy(target = 180), StepField.TARGET, 1))

        // Смена упражнения: тот же вид счёта — цель остаётся, другой — цель по умолчанию.
        val toPushups = m.withExercise(squat.copy(target = 25, sets = 5, restSeconds = 90), Exercises.pushups)
        assertEquals(ProgramStep(Exercises.pushups, 5, 25, 90), toPushups)
        assertEquals(ProgramStep(Exercises.plank, 5, 30, 90), m.withExercise(toPushups, Exercises.plank))
        assertEquals(ProgramStep(Exercises.swing, 3, 10, 60), m.withExercise(plank, Exercises.swing))
    }

    @Test
    fun validation() {
        assertEquals("Добавь название", m.problem("", 1))
        assertEquals("Добавь название", m.problem("   ", 1))
        assertNull(m.problem("  А  ", 1))
        assertNull(m.problem("x".repeat(40), 1))
        assertEquals("Название — не длиннее 40 символов", m.problem("x".repeat(41), 1))
        assertNull(m.problem(" " + "x".repeat(40) + " ", 1))
        assertEquals("Добавь хотя бы одно упражнение", m.problem("Утро", 0))
        assertNull(m.problem("Утро", 10))
        assertEquals("Не больше 10 упражнений", m.problem("Утро", 11))
        // Лимит программ: новую нельзя при 10, правка существующей — можно.
        assertTrue(m.canCreate(9))
        assertFalse(m.canCreate(10))
        assertNull(m.problem("Утро", 1, isNew = true, count = 9))
        assertEquals("Не больше 10 программ", m.problem("Утро", 1, isNew = true, count = 10))
        assertNull(m.problem("Утро", 1, isNew = false, count = 10))

        val built = m.build("home_c_abcdefgh", "  Утро  ", listOf(ProgramStep(Exercises.squats, 9, 0, 61)))
        assertEquals("Утро", built.title)
        assertEquals(ProgramStep(Exercises.squats, 6, 1, 60), built.steps.single())
        assertTrue(m.isValid(built))
        assertFalse(m.isValid(m.newProgram("home_c_abcdefgh")))
    }

    @Test
    fun newProgramDefaults() {
        val p = m.newProgram()
        assertEquals("", p.title)
        assertEquals(listOf(ProgramStep(Exercises.squats, 3, 10, 60)), p.steps)
    }

    @Test
    fun idsHavePrefixAndCountAsPrograms() {
        val random = Random(7)
        val ids = (0 until 200).map { m.newId(random = random) }
        ids.forEach { id ->
            assertTrue(id, id.startsWith("home_c_"))
            assertTrue(id, Regex("home_c_[a-z0-9]{8}").matches(id))
            assertTrue(m.isValidId(id))
            assertTrue(Programs.isProgramId(id))
            assertTrue(m.isCustomId(id))
        }
        // Занятый id не выдаётся.
        val taken = ids.toSet()
        repeat(50) { assertFalse(m.newId(taken, random) in taken) }
        // Готовые программы — не свои.
        Programs.all.forEach { assertFalse(it.id, m.isCustomId(it.id)) }
        // Без открытых настроек (юнит-тест) своих программ нет, поиск не падает.
        assertNull(Programs.byId("home_c_abcdefgh"))
        assertEquals(Programs.beginner, Programs.byId("home_beginner"))
    }

    @Test
    fun rewardSameAsReadyProgram() {
        Programs.all.forEach { ready ->
            val custom = m.build(m.newId(), ready.title, ready.steps)
            // Упражнения, подходы и цели те же; отдых прижимается к шагу 15 с («Гиря — выносливость»: 40 → 45),
            // на награду он не влияет.
            assertEquals(ready.steps.map { it.copy(restSeconds = 0) }, custom.steps.map { it.copy(restSeconds = 0) })
            assertEquals(ready.id, ProgramMath.maxRewardMs(ready), ProgramMath.maxRewardMs(custom))
            Weights.all.forEach { w -> assertEquals(ProgramMath.maxRewardMs(ready, w), ProgramMath.maxRewardMs(custom, w)) }
            if (ready.steps == custom.steps) assertEquals(ProgramMath.estimateMinutes(ready), ProgramMath.estimateMinutes(custom))
            assertEquals(ready.kettlebell, custom.kettlebell)
        }
    }

    @Test
    fun onlyCameraCountedExercises() {
        assertEquals(Exercises.all.toSet(), m.exercises.toSet())
        m.exercises.forEach { assertNotNull(it.id, RepCounters.forExercise(it.id)) }
        m.exercises.forEach { assertEquals(it, m.exerciseById(it.id)) }
        assertNull(m.exerciseById("burpee"))
    }

    @Test
    fun hugeInputGivesEmptyList() {
        // 100 000 шагов: строка в мегабайты — больше предела, сразу пустой список, без разбора.
        val step = "{\"ex\":\"squats\",\"sets\":3,\"target\":10,\"rest\":60}"
        val huge = "{\"programs\":[{\"id\":\"home_c_aaaaaaaa\",\"title\":\"Много\",\"steps\":[" +
            List(100_000) { step }.joinToString(",") + "]}]}"
        assertTrue(huge.length > m.MAX_TEXT_LENGTH)
        assertEquals(emptyList<Program>(), m.decode(huge))
        // Огромное название — тоже.
        val longTitle = "{\"programs\":[{\"id\":\"home_c_aaaaaaaa\",\"title\":\"" + "x".repeat(1_000_000) +
            "\",\"steps\":[$step]}]}"
        assertEquals(emptyList<Program>(), m.decode(longTitle))
    }

    @Test
    fun manyStepsWithinSizeLimitStopAtTen() {
        // 3000 коротких шагов укладываются в 64 КиБ: берутся первые 10 подходящих.
        val text = "{\"programs\":[{\"id\":\"home_c_aaaaaaaa\",\"title\":\"Много\",\"steps\":[" +
            List(3_000) { "{\"ex\":\"abs\"}" }.joinToString(",") + "]}]}"
        assertTrue(text.length <= m.MAX_TEXT_LENGTH)
        val p = m.decode(text).single()
        assertEquals(m.STEPS_MAX, p.steps.size)
        // Значений больше предела парсера — испорчено, пустой список.
        val nodes = "{\"programs\":[" + List(MiniJson.MAX_NODES + 10) { "0" }.joinToString(",") + "]}"
        assertTrue(nodes.length <= m.MAX_TEXT_LENGTH)
        assertEquals(emptyList<Program>(), m.decode(nodes))
    }

    @Test
    fun deepNestingNoCrash() {
        // 30 000 вложенных массивов: без ограничения глубины — переполнение стека.
        val deep = "[".repeat(30_000) + "]".repeat(30_000)
        assertEquals(emptyList<Program>(), m.decode(deep))
        val deepInside = "{\"programs\":[{\"id\":\"home_c_aaaaaaaa\",\"title\":\"x\",\"steps\":" +
            "[".repeat(20) + "]".repeat(20) + "}]}"
        assertEquals(emptyList<Program>(), m.decode(deepInside))
        // Обычная глубина своих программ (5) проходит.
        assertEquals(listOf(sample), m.decode(m.encode(listOf(sample))))
    }

    @Test
    fun snapshotOfOneProgram() {
        assertEquals(sample, m.decodeOne(m.encodeOne(sample)))
        assertNull(m.decodeOne("испорчено"))
    }

    @Test
    fun editorTexts() {
        val nb = ' '
        // «Новичок»: 3090 с награды, ≈20 мин.
        assertEquals("≈20${nb}мин · до +51${nb}мин${nb}30${nb}сек экранного времени", ProgramEditorText.estimate(Programs.beginner))
        assertTrue(ProgramEditorText.estimate(Programs.kettlebellStrength)!!.endsWith(" с гирей 16${nb}кг"))
        assertNull(ProgramEditorText.estimate(m.build("", "Пусто", emptyList())))
        assertEquals("Секунд", ProgramEditorText.targetLabel(m.defaultStep(Exercises.plank)))
        assertEquals("Повторений", ProgramEditorText.targetLabel(m.defaultStep(Exercises.squats)))
        assertEquals("Удалить программу «Утро»?", ProgramEditorText.deleteTitle("Утро"))
    }

    @Test
    fun deletedCustomProgramInProgress() {
        val run = ComplexRun(
            id = 1, timeMs = 0, day = 0, complexId = "home_c_abcdefgh", weightKg = null, durationMs = 21 * 60_000L + 5_000L,
            rounds = null, extraReps = null, totalReps = 96, earnedMs = 0, xp = 0, completed = true,
        )
        assertEquals("Своя программа — 21:05", ProgressMath.complexLine(run))
        assertEquals("Своя программа 24 кг — 21:05 · не завершён", ProgressMath.complexLine(run.copy(weightKg = 24, completed = false)))
    }
}
