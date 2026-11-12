package com.nextlevellife.app.data

import android.util.Log

/**
 * Итог записи тренировки для экрана результата.
 * [previousBestMs] — прежний рекорд по времени (Минотавр), [previousBestRounds] — по кругам (Синди).
 */
data class WorkoutResult(
    val earnedMs: Long,
    val xp: Long,
    val isRecord: Boolean = false,
    val previousBestMs: Long? = null,
    val previousBestRounds: Pair<Int, Int>? = null,
)

/**
 * Единая точка записи результатов: награда по ценам, баланс и опыт в [Store], подробности в [Diary].
 * Баланс начисляется, даже если дневник не записался: упражнение-то сделано.
 */
object Workouts {
    private const val TAG = "Workouts"
    private val NOTHING = WorkoutResult(0L, 0L)

    /** Записать один подход: считает награду по ценам, пополняет баланс и опыт, обновляет серию, пишет в дневник. */
    @Synchronized
    fun recordSet(exercise: Exercise, weightKg: Int, reps: Int, durationMs: Long): WorkoutResult {
        if (reps <= 0) return NOTHING
        val earnedMs = Exercises.rewardMs(exercise, reps, weightKg)
        val xp = Exercises.xp(earnedMs)
        val set = NewSet(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            weightKg = if (exercise.kettlebell) weightKg else null,
            reps = reps,
            durationMs = durationMs.coerceAtLeast(0L),
            earnedMs = earnedMs,
            xp = xp,
        )
        safely("подход ${exercise.id}") { Diary.addSet(System.currentTimeMillis(), set) }
        Store.credit(earnedMs, xp)
        return WorkoutResult(earnedMs, xp)
    }

    /** Минотавр пройден целиком за durationMs. Фиксированная награда Minotaur.rewardMs(weightKg); в дневник — прохождение + 5 подходов по 55 повторений. */
    @Synchronized
    fun recordMinotaur(weightKg: Int, durationMs: Long): WorkoutResult {
        // Быстрее нереально — так же, как на экране: награды нет, в дневник не пишем.
        if (durationMs < Minotaur.MIN_DURATION_MS) return NOTHING
        val earnedMs = Minotaur.rewardMs(weightKg)
        val xp = Minotaur.xp(weightKg)
        // Прежний рекорд читаем до записи; старый рекорд из Store тоже учитываем — он мог быть до дневника.
        val previousBest = WorkoutMath.bestOf(
            safely("рекорд Минотавра") { Diary.bestComplexTimeMs(Minotaur.ID, weightKg) },
            Store.minotaurBestMs(weightKg),
        )
        val isRecord = previousBest == null || durationMs < previousBest
        safely("Минотавр") {
            Diary.addRun(
                timeMs = System.currentTimeMillis(), complexId = Minotaur.ID, weightKg = weightKg,
                durationMs = durationMs, rounds = null, extraReps = null, totalReps = Minotaur.totalReps,
                earnedMs = earnedMs, xp = xp, completed = true,
                sets = WorkoutMath.minotaurSets(weightKg, earnedMs, xp),
            )
        }
        Store.submitMinotaurTime(weightKg, durationMs)
        Store.credit(earnedMs, xp)
        return WorkoutResult(earnedMs, xp, isRecord, previousBest)
    }

    /** Синди: repsByExercise — засчитанные повторения каждого упражнения за 20 минут. Награда Cindy.rewardMs(...). isRecord — побит ли лучший результат по кругам. */
    @Synchronized
    fun recordCindy(repsByExercise: Map<Exercise, Int>, rounds: Int, extraReps: Int, durationMs: Long): WorkoutResult {
        val counted = repsByExercise.filterValues { it > 0 }
        val totalReps = counted.values.sum()
        if (totalReps <= 0) return NOTHING
        val earnedMs = Cindy.rewardMs(counted)
        val xp = Exercises.xp(earnedMs)
        val completed = WorkoutMath.cindyCompleted(durationMs)
        val previousBest = safely("рекорд Синди") { Diary.bestComplexRounds(Cindy.ID) }
        // Прерванная раньше времени Синди не сравнивается с полными.
        val isRecord = completed && DiaryMath.isBetterRounds(rounds to extraReps, previousBest)
        safely("Синди") {
            Diary.addRun(
                timeMs = System.currentTimeMillis(), complexId = Cindy.ID, weightKg = null,
                durationMs = durationMs.coerceAtLeast(0L), rounds = rounds, extraReps = extraReps,
                totalReps = totalReps, earnedMs = earnedMs, xp = xp, completed = completed,
                sets = WorkoutMath.cindySets(counted, xp),
            )
        }
        Store.credit(earnedMs, xp)
        return WorkoutResult(earnedMs, xp, isRecord, previousBestRounds = previousBest)
    }

    private inline fun <T> safely(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Дневник: не удалось ($what)", e)
            null
        }
}

/** Чистая логика наград и рекордов, без Android: её проверяют юнит-тесты. */
internal object WorkoutMath {
    /** Синди с таймером, остановленным чуть раньше 20:00, всё равно считается полной. */
    const val CINDY_TOLERANCE_MS = 5_000L

    fun cindyCompleted(durationMs: Long): Boolean = durationMs >= Cindy.DURATION_MS - CINDY_TOLERANCE_MS

    /** Серия дней после тренировки сегодня; [lastDay] — день прошлой тренировки. */
    fun nextStreak(lastDay: Long, today: Long, current: Int): Int = when (lastDay) {
        today -> current.coerceAtLeast(1)
        today - 1 -> current + 1
        else -> 1
    }

    fun bestOf(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * Делит [total] пропорционально [weights] так, чтобы сумма частей была ровно [total]
     * (остаток от округления уходит частям с наибольшей дробной долей).
     */
    fun split(total: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val sum = weights.sumOf { it.coerceAtLeast(0L) }
        val w = if (sum > 0) weights.map { it.coerceAtLeast(0L) } else weights.map { 1L }
        val wSum = if (sum > 0) sum else weights.size.toLong()
        val parts = w.map { total * it / wSum }.toMutableList()
        var left = total - parts.sum()
        val order = w.indices.sortedWith(compareByDescending<Int> { (total * w[it]) % wSum }.thenBy { it })
        var i = 0
        while (left > 0) {
            parts[order[i % order.size]]++
            left--
            i++
        }
        return parts
    }

    /**
     * Подходы Минотавра для дневника: по одному на упражнение (10+9+…+1 = 55 повторений).
     * Фиксированная награда комплекса делится между ними по ценам упражнений,
     * чтобы сумма по дню совпадала с начисленным.
     */
    fun minotaurSets(weightKg: Int, earnedMs: Long, xp: Long): List<NewSet> {
        val reps = Minotaur.ladder.sum()
        val prices = Minotaur.exercises.map { Exercises.rewardMs(it, reps, weightKg) }
        val earned = split(earnedMs, prices)
        val xps = split(xp, prices)
        return Minotaur.exercises.mapIndexed { i, ex ->
            // Время отдельных упражнений внутри комплекса не меряется: оно есть только у прохождения.
            NewSet(ex.id, ex.name, weightKg, reps, 0L, earned[i], xps[i])
        }
    }

    /** Подходы Синди: по одному на упражнение со всеми засчитанными повторениями, в порядке круга. */
    fun cindySets(repsByExercise: Map<Exercise, Int>, xp: Long): List<NewSet> {
        val roundOrder = Cindy.round.map { it.exercise }
        val exercises = (roundOrder + repsByExercise.keys).distinct().filter { (repsByExercise[it] ?: 0) > 0 }
        val earned = exercises.map { Exercises.rewardMs(it, repsByExercise.getValue(it), Weights.DEFAULT) }
        val xps = split(xp, earned)
        return exercises.mapIndexed { i, ex ->
            val weight = if (ex.kettlebell) Weights.DEFAULT else null
            NewSet(ex.id, ex.name, weight, repsByExercise.getValue(ex), 0L, earned[i], xps[i])
        }
    }
}
