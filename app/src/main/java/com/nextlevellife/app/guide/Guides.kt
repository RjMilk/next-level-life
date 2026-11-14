package com.nextlevellife.app.guide

/** Набор гидов из одного файла (упражнения без веса, с гирей, позы ЛФК, йога…). */
interface GuidePack {
    val guides: List<ExerciseGuide>
}

/**
 * Реестр показов техники: [of] по id упражнения (или позы комплекса).
 *
 * Как добавить гиды из нового файла, не трогая существующие наборы:
 * 1. Создать в пакете guide свой файл, например `YogaGuides.kt`:
 *    ```
 *    object YogaGuides : GuidePack {
 *        override val guides = listOf(
 *            ExerciseGuide(
 *                exerciseId = "yoga_mountain", view = GuideView.FRONT,
 *                frames = listOf(GuideFrame(GuidePoses.front(), moveMs = 900, holdMs = 2500), …),
 *                distance = "2,5–3 м", angle = "спереди", camera = "…", focus = "…",
 *                tips = listOf("…", "…"), mistakes = listOf("…"),
 *            ),
 *        )
 *    }
 *    ```
 * 2. Подключить набор одной строкой в [packs] (единственная правка общего кода) — либо, если этот
 *    файл трогать нельзя, вызвать `Guides.register(YogaGuides.guides)` из своего кода до показа
 *    (например, в init объекта комплекса). Повторная регистрация того же id заменяет гид.
 * 3. Тест целостности (GuideIntegrityTest) проверяет все гиды из [all]: полный набор суставов,
 *    координаты в 0…1, длительности, тексты без точки в конце.
 *
 * Позы удобно строить через [GuidePoses.side] / [GuidePoses.front]: углы костей вместо точек.
 */
object Guides {
    /** Встроенные наборы. Новый набор — новый файл и одна строка здесь. */
    private val packs: List<GuidePack> = listOf(BodyweightGuides, KettlebellGuides)

    private val registered = LinkedHashMap<String, ExerciseGuide>()

    /** Добавить или заменить гиды во время работы (для наборов, не перечисленных в [packs]). */
    @Synchronized
    fun register(guides: List<ExerciseGuide>) {
        for (g in guides) registered[g.exerciseId] = g
    }

    fun register(guide: ExerciseGuide) = register(listOf(guide))

    /** Все гиды: встроенные наборы, затем зарегистрированные (они заменяют встроенные с тем же id). */
    @Synchronized
    fun all(): List<ExerciseGuide> {
        val byId = LinkedHashMap<String, ExerciseGuide>()
        for (pack in packs) for (g in pack.guides) byId[g.exerciseId] = g
        byId.putAll(registered)
        return byId.values.toList()
    }

    /** Гид упражнения [exerciseId] или null, если его нет. */
    @Synchronized
    fun of(exerciseId: String): ExerciseGuide? =
        registered[exerciseId] ?: packs.firstNotNullOfOrNull { pack -> pack.guides.firstOrNull { it.exerciseId == exerciseId } }
}
