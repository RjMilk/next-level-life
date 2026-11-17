package com.nextlevellife.app.pose

/**
 * Профили счёта для зала. Пороги не копируются: профиль берёт условия уже проверенного профиля
 * ([ExerciseConfigs]) как есть и меняет только id и подсказки — про штангу, а не про гирю.
 * Приседу и становой со штангой подходят профили «squats» и «deadlift» без изменений (подсказки там общие),
 * поэтому отдельных профилей у них нет: упражнение ссылается на них через Exercise.counterId.
 *
 * Жим штанги — обе руки вместе: профиль жима гири, но считается одним автоматом ([PhaseCounter]), а не по
 * автомату на руку ([EitherArmCounter]). Камера сбоку или в пол-оборота видит ближнюю руку, [SideSelector]
 * держится за неё; штанга идёт обеими руками разом, так что одна рука — это и есть повтор.
 */
internal object GymCounters {
    private fun derived(baseId: String, id: String, postureHint: String, startHint: String): ExerciseConfig {
        val base = ExerciseConfigs.byId.getValue(baseId)
        return ExerciseConfig(
            id = id,
            postureHint = postureHint,
            startHint = startHint,
            farHint = base.farHint,
            finishHint = base.finishHint,
            enter = base.enter,
            hold = base.hold,
            rest = base.rest,
            far = base.far,
            farAlt = base.farAlt,
            finish = base.finish,
            depth = base.depth,
            minRepMs = base.minRepMs,
        )
    }

    /** Жим штанги стоя: условия жима гири, обе руки — один повтор. */
    val pressBarbell: ExerciseConfig = derived("press", "press_barbell", "Встань прямо", "Штангу к плечам")

    /** Тяга штанги в наклоне: условия тяги гири. */
    val rowBarbell: ExerciseConfig = derived("row", "row_barbell", "Наклонись, штанга в руках", "Выпрями руки")

    val all: List<ExerciseConfig> = listOf(pressBarbell, rowBarbell) + GymAngularProfiles.all.map { it.config() }

    val byId: Map<String, ExerciseConfig> = all.associateBy { it.id }
}
