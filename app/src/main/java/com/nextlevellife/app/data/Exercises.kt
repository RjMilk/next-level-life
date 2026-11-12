package com.nextlevellife.app.data

/**
 * Одиночное упражнение: сколько секунд экранного времени и опыта даёт одно повторение.
 * [minSecondsPerRep] защищает от «натыкивания»: засчитывается не больше повторений,
 * чем физически можно сделать за прошедшее время.
 */
data class Exercise(
    val id: String,
    val name: String,
    val rewardSecondsPerRep: Int,
    val xpPerRep: Int,
    val minSecondsPerRep: Double,
)

object Exercises {
    val all = listOf(
        Exercise("pushups", "Отжимания", rewardSecondsPerRep = 60, xpPerRep = 2, minSecondsPerRep = 0.8),
        Exercise("squats", "Приседания", rewardSecondsPerRep = 30, xpPerRep = 1, minSecondsPerRep = 0.8),
        Exercise("abs", "Пресс", rewardSecondsPerRep = 30, xpPerRep = 1, minSecondsPerRep = 0.7),
        Exercise("swings", "Махи гирей", rewardSecondsPerRep = 30, xpPerRep = 2, minSecondsPerRep = 0.8),
    )

    fun byId(id: String): Exercise = all.first { it.id == id }
}

/** Комплекс «Минотавр»: лесенка 10 → 1 по пяти упражнениям с гирей, 275 повторений. */
object Minotaur {
    val exercises = listOf(
        "Трастер",
        "Мах гирей",
        "Тяга сумо к подбородку",
        "Тяга к поясу в наклоне",
        "Становая тяга",
    )
    val ladder = (10 downTo 1).toList()
    val totalReps = ladder.sum() * exercises.size

    val weights = listOf(16, 24, 32)

    fun rewardMinutes(weightKg: Int): Int = when (weightKg) {
        32 -> 180
        24 -> 150
        else -> 120
    }

    fun xp(weightKg: Int): Long = totalReps.toLong() * weightKg / 8

    /** Быстрее этого пройти комплекс нереально: награда не начисляется. */
    const val MIN_DURATION_MS = 7 * 60 * 1000L

    data class Step(val round: Int, val reps: Int, val exercise: String)

    val steps: List<Step> = ladder.flatMapIndexed { i, reps ->
        exercises.map { Step(round = i + 1, reps = reps, exercise = it) }
    }
}
