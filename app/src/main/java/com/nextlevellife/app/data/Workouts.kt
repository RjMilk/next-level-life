package com.nextlevellife.app.data

import android.util.Log
import com.nextlevellife.app.stats.StatsRepository
import org.json.JSONObject
import java.util.UUID

/**
 * Итог записи тренировки для экрана результата.
 * [previousBestMs] — прежний рекорд по времени (Минотавр), [previousBestRounds] — по кругам (Синди).
 * [saved] = false — запись не удалась, ничего не начислено (награда в итоге нулевая).
 * [xp] — опыт за минуты: с версии 0.6 не начисляется (0); у повтора старой операции — как был записан.
 * [strengthGain] — на сколько выросли очки силы за сутки после этой записи, [strengthToday] — очки силы
 * за сутки теперь (предварительно, потолок 10); null — не известно (сбой чтения, операция до 0.6).
 */
data class WorkoutResult(
    val earnedMs: Long,
    val xp: Long,
    val isRecord: Boolean = false,
    val previousBestMs: Long? = null,
    val previousBestRounds: Pair<Int, Int>? = null,
    val saved: Boolean = true,
    val strengthGain: Int? = null,
    val strengthToday: Int? = null,
)

/** Один подход для [Workouts.recordSets]. Вес учитывается только для гиревых упражнений. */
data class SetInput(val exercise: Exercise, val weightKg: Int, val reps: Int, val durationMs: Long = 0L)

/**
 * Единая точка записи результатов: награда по ценам, запись в [Diary] и операция в [Ledger]
 * (баланс) — одной транзакцией SQLite. Не записалось — ничего не начислено.
 * С версии 0.6 опыт за минуты не начисляется (delta_xp = 0, xp подходов и прохождений = 0): уровень
 * считается по характеристикам ([com.nextlevellife.app.stats.StatsMath]) из самих записей дневника.
 *
 * [sessionId] делает запись идемпотентной: повторный вызов с тем же идентификатором ничего
 * не начисляет и возвращает тот же результат (ключ `set:<sessionId>` или `complex:<sessionId>`).
 * Без него каждый вызов — новая запись.
 */
object Workouts {
    private const val TAG = "Workouts"
    private val NOTHING = WorkoutResult(0L, 0L)
    private val FAILED = WorkoutResult(0L, 0L, saved = false)

    /** Записать один подход: награда по ценам, баланс и опыт, серия, дневник. */
    @Synchronized
    fun recordSet(exercise: Exercise, weightKg: Int, reps: Int, durationMs: Long, sessionId: String? = null): WorkoutResult =
        recordSets(listOf(SetInput(exercise, weightKg, reps, durationMs)), sessionId)

    /**
     * Несколько подходов (например, незавершённый комплекс) по обычным ценам: все подходы
     * и одно начисление SET_REWARD — одной транзакцией. Подходы без повторений пропускаются.
     */
    @Synchronized
    fun recordSets(sets: List<SetInput>, sessionId: String? = null): WorkoutResult {
        val newSets = sets.filter { it.reps > 0 }.map { s ->
            val earned = Exercises.rewardMs(s.exercise, s.reps, s.weightKg)
            NewSet(
                exerciseId = s.exercise.id,
                exerciseName = s.exercise.name,
                weightKg = if (s.exercise.kettlebell) s.weightKg else null,
                reps = s.reps,
                durationMs = s.durationMs.coerceAtLeast(0L),
                earnedMs = earned,
                xp = 0L, // опыт за минуты не начисляется с 0.6
            )
        }
        if (newSets.isEmpty()) return NOTHING
        return persistSets(newSets, sessionId, "подходы")
    }

    /**
     * Записать подход-удержание (планку): [heldMs] — засчитанное время (отрезки не короче 10 с),
     * [durationMs] — полное время подхода. Награда — [Exercises.holdRewardMs] за целые секунды;
     * засчитанное не больше полного времени. В дневник — reps = 0, held_ms, measurement_type = HOLD.
     * Одна транзакция с журналом баланса, идемпотентно по [sessionId] (тот же ключ `set:<sessionId>`,
     * что у подходов). Нечего засчитывать — ничего не пишется (saved = true, награда 0);
     * сбой — saved = false, ничего не начислено.
     */
    @Synchronized
    fun recordHold(exercise: Exercise, heldMs: Long, durationMs: Long, sessionId: String? = null): WorkoutResult {
        val held = WorkoutMath.creditedHoldMs(heldMs, durationMs)
        if (held <= 0L) return NOTHING
        val earned = Exercises.holdRewardMs(exercise, held)
        val set = NewSet(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            weightKg = null,
            reps = 0,
            durationMs = durationMs.coerceAtLeast(0L),
            earnedMs = earned,
            xp = 0L, // опыт за минуты не начисляется с 0.6
            measurement = Measurement.HOLD,
            heldMs = held,
        )
        return persistSets(listOf(set), sessionId, "удержание", mapOf("held_ms" to held))
    }

    /** Подходы и одно начисление SET_REWARD — одной транзакцией [persist]. */
    private fun persistSets(
        newSets: List<NewSet>, sessionId: String?, what: String, extra: Map<String, Any?> = emptyMap(),
    ): WorkoutResult {
        val earnedMs = newSets.sumOf { it.earnedMs }
        val xp = newSets.sumOf { it.xp }
        val key = LedgerMath.setKey(sessionId ?: UUID.randomUUID().toString())
        return persist(key, what) {
            val now = TrustedClock.nowMs() // доверенное время: перевод часов не меняет сутки начисления
            val day = DiaryMath.dayOf(now)
            val before = strengthOf(day)
            val ids = newSets.map { Diary.addSet(now, it) }
            val result = withStrength(WorkoutResult(earnedMs, xp), before, strengthOf(day))
            credit(
                OpKind.SET_REWARD, key, result, sourceType = "sets", sourceId = ids.joinToString(","), now = now,
                details = mapOf("set_ids" to ids, "exercises" to newSets.map { it.exerciseId }) + extra,
            )
            result
        }
    }

    /** Минотавр пройден целиком за durationMs. Фиксированная награда Minotaur.rewardMs(weightKg); в дневник — прохождение + 5 подходов по 55 повторений. */
    @Synchronized
    fun recordMinotaur(weightKg: Int, durationMs: Long, sessionId: String? = null): WorkoutResult {
        // Быстрее нереально — так же, как на экране: награды нет, в дневник не пишем.
        if (durationMs < Minotaur.MIN_DURATION_MS) return NOTHING
        val earnedMs = Minotaur.rewardMs(weightKg)
        val xp = 0L // опыт за минуты не начисляется с 0.6
        val key = LedgerMath.complexKey(sessionId ?: UUID.randomUUID().toString())
        var fresh = false
        val r = persist(key, "Минотавр") {
            fresh = true
            // Прежний рекорд читаем до записи; старый рекорд из Store тоже учитываем — он мог быть до дневника.
            val previousBest = WorkoutMath.bestOf(
                safely("рекорд Минотавра") { Diary.bestComplexTimeMs(Minotaur.ID, weightKg) },
                Store.minotaurBestMs(weightKg),
            )
            val now = TrustedClock.nowMs() // доверенное время: перевод часов не меняет сутки начисления
            val day = DiaryMath.dayOf(now)
            val before = strengthOf(day)
            val runId = Diary.addRun(
                timeMs = now, complexId = Minotaur.ID, weightKg = weightKg,
                durationMs = durationMs, rounds = null, extraReps = null, totalReps = Minotaur.totalReps,
                earnedMs = earnedMs, xp = xp, completed = true,
                sets = WorkoutMath.minotaurSets(weightKg, earnedMs, xp),
            )
            val result = withStrength(
                WorkoutResult(earnedMs, xp, previousBest == null || durationMs < previousBest, previousBest),
                before, strengthOf(day),
            )
            credit(
                OpKind.COMPLEX_REWARD, key, result, sourceType = "complex_run", sourceId = runId.toString(), now = now,
                details = mapOf("complex_id" to Minotaur.ID, "weight_kg" to weightKg, "duration_ms" to durationMs),
            )
            result
        }
        if (fresh && r.saved) Store.submitMinotaurTime(weightKg, durationMs)
        return r
    }

    /** Синди: repsByExercise — засчитанные повторения каждого упражнения за 20 минут. Награда Cindy.rewardMs(...). isRecord — побит ли лучший результат по кругам. */
    @Synchronized
    fun recordCindy(
        repsByExercise: Map<Exercise, Int>, rounds: Int, extraReps: Int, durationMs: Long,
        sessionId: String? = null,
    ): WorkoutResult {
        val counted = repsByExercise.filterValues { it > 0 }
        val totalReps = counted.values.sum()
        if (totalReps <= 0) return NOTHING
        val earnedMs = Cindy.rewardMs(counted)
        val xp = 0L // опыт за минуты не начисляется с 0.6
        val completed = WorkoutMath.cindyCompleted(durationMs)
        val key = LedgerMath.complexKey(sessionId ?: UUID.randomUUID().toString())
        return persist(key, "Синди") {
            val previousBest = safely("рекорд Синди") { Diary.bestComplexRounds(Cindy.ID) }
            // Прерванная раньше времени Синди не сравнивается с полными.
            val isRecord = completed && DiaryMath.isBetterRounds(rounds to extraReps, previousBest)
            val now = TrustedClock.nowMs() // доверенное время: перевод часов не меняет сутки начисления
            val day = DiaryMath.dayOf(now)
            val before = strengthOf(day)
            val runId = Diary.addRun(
                timeMs = now, complexId = Cindy.ID, weightKg = null,
                durationMs = durationMs.coerceAtLeast(0L), rounds = rounds, extraReps = extraReps,
                totalReps = totalReps, earnedMs = earnedMs, xp = xp, completed = completed,
                sets = WorkoutMath.cindySets(counted, xp),
            )
            val result = withStrength(
                WorkoutResult(earnedMs, xp, isRecord, previousBestRounds = previousBest),
                before, strengthOf(day),
            )
            credit(
                OpKind.COMPLEX_REWARD, key, result, sourceType = "complex_run", sourceId = runId.toString(), now = now,
                details = mapOf("complex_id" to Cindy.ID, "rounds" to rounds, "extra_reps" to extraReps, "duration_ms" to durationMs),
            )
            result
        }
    }

    /**
     * Одна транзакция: если операция с ключом [key] уже есть — возвращает её сохранённый результат,
     * иначе выполняет [block] (дневник + [credit]). После успешной новой записи обновляет серию дней.
     * Любая ошибка — откат и [FAILED].
     */
    private fun persist(key: String, what: String, block: () -> WorkoutResult): WorkoutResult {
        var fresh = false
        return try {
            val r = Diary.write {
                Ledger.opByKey(key)?.let { return@write replay(it) }
                fresh = true
                block()
            }
            if (fresh) Store.markTrained()
            r
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать ($what): ничего не начислено", e)
            FAILED
        }
    }

    /** Начисление внутри транзакции [persist]; в metadata — итог для повторного вызова с тем же ключом. */
    private fun credit(
        kind: OpKind, key: String, result: WorkoutResult, sourceType: String, sourceId: String, now: Long,
        details: Map<String, Any?>,
    ) {
        val meta = LinkedHashMap<String, Any?>(details)
        meta["is_record"] = result.isRecord
        result.previousBestMs?.let { meta["previous_best_ms"] = it }
        result.previousBestRounds?.let { (r, e) ->
            meta["previous_best_rounds"] = r
            meta["previous_best_extra"] = e
        }
        result.strengthGain?.let { meta["strength_gain"] = it }
        result.strengthToday?.let { meta["strength_today"] = it }
        val inserted = Ledger.reward(kind, key, result.earnedMs, result.xp, sourceType, sourceId, now, meta)
        // Ключ проверен в той же транзакции; если всё же занят — откатываем, чтобы не записать дневник без оплаты.
        check(inserted) { "Операция $key уже существует" }
    }

    /** Результат уже записанной операции: награда из самой операции, рекорды — из её metadata. */
    private fun replay(op: BalanceOp): WorkoutResult {
        val meta = runCatching { JSONObject(op.metadataJson) }.getOrNull()
        val prevMs = meta?.takeIf { it.has("previous_best_ms") }?.optLong("previous_best_ms")
        val prevRounds = meta?.takeIf { it.has("previous_best_rounds") }?.let {
            it.optInt("previous_best_rounds") to it.optInt("previous_best_extra")
        }
        return WorkoutResult(
            earnedMs = op.deltaMs,
            xp = op.deltaXp,
            isRecord = meta?.optBoolean("is_record", false) ?: false,
            previousBestMs = prevMs,
            previousBestRounds = prevRounds,
            strengthGain = meta?.takeIf { it.has("strength_gain") }?.optInt("strength_gain"),
            strengthToday = meta?.takeIf { it.has("strength_today") }?.optInt("strength_today"),
        )
    }

    /** Очки силы за сутки [day] по дневнику (внутри транзакции записи); сбой чтения — null, запись не срывается. */
    private fun strengthOf(day: Long): Int? = safely("сила за день") { StatsRepository.strengthOfDay(day) }

    /** Прирост силы за сутки: до записи [before] и после [after]. */
    private fun withStrength(r: WorkoutResult, before: Int?, after: Int?): WorkoutResult =
        if (before == null || after == null) r
        else r.copy(strengthGain = (after - before).coerceAtLeast(0), strengthToday = after)

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

    /**
     * Сколько удержания засчитать в дневник и награду: целые секунды, не больше полного времени
     * подхода [durationMs] (если оно известно, то есть > 0), не меньше нуля.
     */
    fun creditedHoldMs(heldMs: Long, durationMs: Long): Long {
        val capped = if (durationMs > 0L) minOf(heldMs, durationMs) else heldMs
        return (capped.coerceAtLeast(0L) / 1000) * 1000
    }

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
