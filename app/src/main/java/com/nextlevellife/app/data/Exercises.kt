package com.nextlevellife.app.data

import kotlin.math.roundToLong

/**
 * Упражнение и его «цена»: сколько секунд экранного времени даёт одно повторение.
 * Для гиревых упражнений цена указана для гири 16 кг и умножается на [Weights.factor].
 * Одно повторение стоит одинаково и отдельно, и внутри Минотавра.
 *
 * [minSecondsPerRep] защищает от «натыкивания»: засчитывается не больше повторений,
 * чем физически можно сделать за прошедшее время.
 */
data class Exercise(
    val id: String,
    val name: String,
    val rewardSecondsPerRep: Int,
    val minSecondsPerRep: Double,
    val kettlebell: Boolean = false,
)

object Weights {
    val all = listOf(16, 24, 32)
    const val DEFAULT = 16

    /** Мягкий рост: тяжёлая гиря — это развитие, а не способ быстро набить минуты. */
    fun factor(weightKg: Int): Double = when (weightKg) {
        32 -> 1.6
        24 -> 1.2
        else -> 1.0
    }
}

object Exercises {
    val pushups = Exercise("pushups", "Отжимания", 30, 0.8)
    val squats = Exercise("squats", "Приседания", 40, 0.8)
    val abs = Exercise("abs", "Пресс", 35, 0.7)

    val thruster = Exercise("thruster", "Трастер", 45, 1.5, kettlebell = true)
    val swing = Exercise("swings", "Мах гирей", 30, 0.8, kettlebell = true)
    val sumoPull = Exercise("sumo_pull", "Тяга сумо к подбородку", 30, 1.0, kettlebell = true)
    val row = Exercise("row", "Тяга к поясу в наклоне", 25, 1.0, kettlebell = true)
    val deadlift = Exercise("deadlift", "Становая тяга", 25, 1.0, kettlebell = true)

    val bodyweight = listOf(pushups, squats, abs)
    val kettlebell = listOf(thruster, swing, sumoPull, row, deadlift)
    val all = bodyweight + kettlebell

    fun byId(id: String): Exercise = all.first { it.id == id }

    /** Награда в миллисекундах за [reps] повторений; вес учитывается только для гиревых. */
    fun rewardMs(ex: Exercise, reps: Int, weightKg: Int): Long {
        val factor = if (ex.kettlebell) Weights.factor(weightKg) else 1.0
        return (reps * ex.rewardSecondsPerRep * factor * 1000).roundToLong()
    }

    /** Опыт: 1 очко за каждые 10 секунд заработанного времени. */
    fun xp(rewardMs: Long): Long = rewardMs / 10_000
}

/** Комплекс «Минотавр»: лесенка 10 → 1 по пяти упражнениям с гирей, 275 повторений. */
object Minotaur {
    val exercises = listOf(
        Exercises.thruster,
        Exercises.swing,
        Exercises.sumoPull,
        Exercises.row,
        Exercises.deadlift,
    )
    val ladder = (10 downTo 1).toList()
    private val repsPerExercise = ladder.sum()
    val totalReps = repsPerExercise * exercises.size

    /**
     * Фиксированная награда за весь комплекс: 2,5 / 3 / 4 часа.
     * Чуть больше суммы тех же повторений по отдельности (2:22 / 2:50 / 3:47) —
     * небольшой бонус за то, что комплекс пройден целиком.
     */
    fun rewardMs(weightKg: Int): Long = when (weightKg) {
        32 -> 240
        24 -> 180
        else -> 150
    } * 60_000L

    /** Сколько дали бы те же повторения, сделанные по отдельности. */
    fun separateRewardMs(weightKg: Int): Long =
        exercises.sumOf { Exercises.rewardMs(it, repsPerExercise, weightKg) }

    fun xp(weightKg: Int): Long = Exercises.xp(rewardMs(weightKg))

    /** Быстрее этого пройти комплекс нереально: награда не начисляется. */
    const val MIN_DURATION_MS = 7 * 60 * 1000L

    data class Step(val round: Int, val reps: Int, val exercise: Exercise)

    val steps: List<Step> = ladder.flatMapIndexed { i, reps ->
        exercises.map { Step(round = i + 1, reps = reps, exercise = it) }
    }
}
