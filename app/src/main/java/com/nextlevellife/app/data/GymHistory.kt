package com.nextlevellife.app.data

/** Сводки упражнений зала из дневника (чтение базы — не в главном потоке). */
object GymHistory {
    /** Сводка по каждому упражнению зала; сбой чтения — пустая история (вес по умолчанию, без совета). */
    fun summaries(exercises: List<Exercise> = Exercises.gym): Map<String, GymSummary> {
        val sets = runCatching { Diary.gymSets(exercises.map { it.id }) }.getOrDefault(emptyMap())
        return exercises.mapNotNull { ex -> ex.gym?.let { spec -> ex.id to GymMath.summary(spec, sets[ex.id].orEmpty()) } }.toMap()
    }

    fun summary(exercise: Exercise): GymSummary? = summaries(listOf(exercise))[exercise.id]
}
