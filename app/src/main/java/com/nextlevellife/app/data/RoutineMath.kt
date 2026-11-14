package com.nextlevellife.app.data

/**
 * Итог одного шага программы: [stepIndex] — индекс в [Routine.steps], [heldMs] — подтверждённое камерой
 * удержание, [reps] — засчитанные повторения, [durationMs] — сколько шаг шёл по часам.
 * [skipped] — шаг пропущен кнопкой (подтверждённое до пропуска всё равно оплачивается).
 */
data class RoutineStepResult(
    val stepIndex: Int,
    val heldMs: Long = 0L,
    val reps: Int = 0,
    val durationMs: Long = 0L,
    val skipped: Boolean = false,
)

/**
 * Подсчёт программы: [rawEarnedMs] — награда по ценам до дневного предела (целые секунды),
 * [completed] — все обязательные шаги выполнены, [totalReps]/[heldMs] — подтверждено камерой,
 * [hasWork] — есть что записывать (хоть одно повторение или секунда удержания).
 */
data class RoutineScore(
    val rawEarnedMs: Long,
    val completed: Boolean,
    val totalReps: Int,
    val heldMs: Long,
    val hasWork: Boolean,
)

/**
 * Цены программ ЛФК и йоги (каталог, раздел 5): низкая награда за мягкую активность, чтобы программы
 * не стали самым лёгким способом набить минуты. Платится только подтверждённое камерой — повторения
 * (не больше назначенных в шаге) и удержание (не больше целевого времени шага). Подготовка, отдых,
 * переходы, спокойное сидение и шавасана — 0. Бонуса за завершение нет: завершение — отметка в истории.
 * Чистая логика, без Android: проверяется тестами.
 */
object RoutineMath {
    /** Повторение ЛФК: 2 с экранного времени (для сравнения: отжимание — 30 с). */
    const val REP_REWARD_MS = 2_000L

    /**
     * Удержание позы: 0,25 с за подтверждённую секунду — целым отношением [HOLD_REWARD_NUM]/[HOLD_REWARD_DEN]
     * (у планки — 5 с за секунду). 40 с позы = 10 с, 6 минут удержаний = 90 с.
     */
    const val HOLD_REWARD_NUM = 1L
    const val HOLD_REWARD_DEN = 4L

    /** Общий дневной предел награды за ЛФК и йогу: 5 минут экранного времени. */
    const val DAILY_CAP_MS = 5 * 60_000L

    /** Шаг выполнен, если подтверждено не меньше стольких процентов цели (удержание — времени, повторения — числа). */
    const val DONE_PERCENT = 80

    /** Засчитанное удержание шага: целые секунды, не больше цели. */
    fun creditedHoldMs(step: HoldStep, heldMs: Long): Long =
        (heldMs.coerceIn(0L, step.targetMs) / 1000) * 1000

    /** Засчитанные повторения шага: не больше назначенных. */
    fun creditedReps(step: RepsStep, reps: Int): Int = reps.coerceIn(0, step.reps)

    /** Награда шага в мс (может быть дробной секундой: округление — только в итоге, [rawEarnedMs]). */
    fun stepRewardMs(step: RoutineStep, r: RoutineStepResult?): Long = when {
        r == null -> 0L
        step is HoldStep -> if (step.paid) creditedHoldMs(step, r.heldMs) * HOLD_REWARD_NUM / HOLD_REWARD_DEN else 0L
        step is RepsStep -> creditedReps(step, r.reps) * REP_REWARD_MS
        else -> 0L
    }

    /** Обязательный шаг: оплачиваемое упражнение (подготовка, отдых в позе и шавасана — нет). */
    fun isRequired(step: RoutineStep): Boolean = when (step) {
        is HoldStep -> step.paid
        is RepsStep -> true
        is RestStep -> false
    }

    /** Шаг выполнен: подтверждено ≥ [DONE_PERCENT] % цели (даже если потом нажат «Пропустить»). */
    fun stepDone(step: RoutineStep, r: RoutineStepResult?): Boolean {
        if (r == null) return false
        return when (step) {
            is HoldStep -> creditedHoldMs(step, r.heldMs) * 100 >= step.targetMs * DONE_PERCENT
            is RepsStep -> creditedReps(step, r.reps) * 100 >= step.reps * DONE_PERCENT
            is RestStep -> true
        }
    }

    private fun byStep(results: List<RoutineStepResult>): Map<Int, RoutineStepResult> =
        results.groupBy { it.stepIndex }.mapValues { (_, list) ->
            // Повтор одного шага (после пересоздания экрана) — берём лучший итог, не сумму.
            list.maxWith(compareBy<RoutineStepResult>({ it.heldMs }, { it.reps }))
        }

    /** Награда, завершённость и подтверждённое по итогам шагов. */
    fun score(routine: Routine, results: List<RoutineStepResult>): RoutineScore {
        val map = byStep(results)
        var ms = 0L
        var reps = 0
        var held = 0L
        var done = true
        routine.steps.forEachIndexed { i, step ->
            val r = map[i]
            ms += stepRewardMs(step, r)
            if (step is RepsStep && r != null) reps += creditedReps(step, r.reps)
            if (step is HoldStep && r != null) held += creditedHoldMs(step, r.heldMs)
            if (isRequired(step) && !stepDone(step, r)) done = false
        }
        return RoutineScore(
            rawEarnedMs = (ms / 1000) * 1000,
            completed = done,
            totalReps = reps,
            heldMs = held,
            hasWork = reps > 0 || held > 0L,
        )
    }

    /** Награда за программу, пройденную целиком (для карточки «до +N»). */
    fun maxRewardMs(routine: Routine): Long = score(
        routine,
        routine.steps.mapIndexedNotNull { i, s ->
            when (s) {
                is HoldStep -> RoutineStepResult(i, heldMs = s.targetMs)
                is RepsStep -> RoutineStepResult(i, reps = s.reps)
                is RestStep -> null
            }
        },
    ).rawEarnedMs

    /** Начислить с учётом дневного предела: за сегодня программами уже начислено [alreadyTodayMs]. */
    fun capped(rawMs: Long, alreadyTodayMs: Long): Long =
        rawMs.coerceAtLeast(0L).coerceAtMost((DAILY_CAP_MS - alreadyTodayMs.coerceAtLeast(0L)).coerceAtLeast(0L))

    /**
     * Подходы прохождения для дневника: по одному на упражнение-шаг с подтверждённым временем или повторениями
     * (удержание — HOLD с held_ms, повторения — REPS). [earnedMs] (уже с пределом) делится между ними
     * пропорционально наградам шагов, чтобы сумма подходов совпадала с начисленным.
     */
    fun sets(routine: Routine, results: List<RoutineStepResult>, earnedMs: Long): List<NewSet> {
        val map = byStep(results)
        val items = routine.steps.mapIndexedNotNull { i, step ->
            val r = map[i] ?: return@mapIndexedNotNull null
            when (step) {
                is HoldStep -> creditedHoldMs(step, r.heldMs).takeIf { it > 0L }?.let { Triple(step, r, it) }
                is RepsStep -> creditedReps(step, r.reps).takeIf { it > 0 }?.let { Triple(step, r, it.toLong()) }
                is RestStep -> null
            }
        }
        val weights = items.map { (step, r, _) -> stepRewardMs(step, r) }
        val earned = if (weights.sum() > 0L) WorkoutMath.split(earnedMs, weights) else items.map { 0L }
        return items.mapIndexed { k, (step, r, amount) ->
            // Название без стороны: обе стороны — одно упражнение в итогах дня.
            val name = step.name
            when (step) {
                is HoldStep -> NewSet(
                    exerciseId = step.exerciseId, exerciseName = name, weightKg = null, reps = 0,
                    durationMs = r.durationMs.coerceAtLeast(0L), earnedMs = earned[k], xp = 0L,
                    measurement = Measurement.HOLD, heldMs = amount,
                )
                else -> NewSet(
                    exerciseId = step.exerciseId, exerciseName = name, weightKg = null, reps = amount.toInt(),
                    durationMs = r.durationMs.coerceAtLeast(0L), earnedMs = earned[k], xp = 0L,
                )
            }
        }
    }
}
