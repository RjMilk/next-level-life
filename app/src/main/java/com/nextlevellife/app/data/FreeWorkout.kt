package com.nextlevellife.app.data

import com.nextlevellife.app.pose.FreeCandidate
import com.nextlevellife.app.pose.FreeEvent
import com.nextlevellife.app.pose.FreeResume

/**
 * Вольная тренировка: камера сама определяет упражнение ([com.nextlevellife.app.pose.FreeSessionClassifier]).
 * Снаряд камера не видит, поэтому здесь только упражнения со своим весом ([Exercises.bodyweight]): присед без гири
 * не должен оплачиваться как гиревой. Каждый подход — обычный подход своего упражнения по обычной цене
 * ([Workouts.recordSets] / [Workouts.recordHold]) с ключом [setSessionId]: повтор записи не платит дважды,
 * а сила считается по обычным правилам (полный подход — от 10 повторений в одном подходе).
 */
object FreeWorkoutMath {
    /**
     * Упражнения вольной тренировки. Порядок — приоритет распознавания по исходной позе (пока ничего
     * не засчитано): сначала самые «узнаваемые» позы. Планка — удержание на предплечьях, отжимания — упор
     * на прямых руках, подтягивания — руки над головой, приседания — стойка, пресс — лёжа на спине.
     */
    val exercises: List<Exercise> = listOf(
        Exercises.plank, Exercises.pushups, Exercises.pullups, Exercises.squats, Exercises.abs,
    )

    /** Кандидаты классификатора: цена — секунды за повторение (у планки — за секунду). */
    val candidates: List<FreeCandidate> = exercises.map { FreeCandidate(it.id, it.rewardSecondsPerRep) }

    /** Ключ записи подхода номер [number] (с 1) тренировки [session]. */
    fun setSessionId(session: String, number: Int): String = "free:$session:$number"

    /** Награда подхода по обычным ценам (для показа; начисляет [Workouts]). */
    fun rewardMs(set: FreeSet): Long {
        val ex = Exercises.byIdOrNull(set.exerciseId) ?: return 0L
        return if (ex.hold) {
            Exercises.holdRewardMs(ex, WorkoutMath.creditedHoldMs(set.heldMs, set.durationMs))
        } else {
            Exercises.rewardMs(ex, set.reps, Weights.DEFAULT)
        }
    }
}

/** Состояние подхода вольной тренировки. */
enum class FreeSetStatus {
    /** Идёт: камера ещё может добавить повторения. */
    OPEN,

    /** Закрыт, ждёт записи. */
    CLOSED,

    /** Записан и оплачен. */
    RECORDED,

    /** Запись не удалась: повторяется тем же ключом (без двойной оплаты). */
    FAILED,
}

/**
 * Подход вольной тренировки: [number] — номер в тренировке (с 1, из него ключ записи), [reps] или [heldMs] —
 * засчитанное камерой, [startMs]..[endMs] — время подхода (часы кадров), [earnedMs] — начислено при записи.
 */
data class FreeSet(
    val number: Int,
    val exerciseId: String,
    val reps: Int,
    val heldMs: Long,
    val startMs: Long,
    val endMs: Long,
    val status: FreeSetStatus,
    val earnedMs: Long = 0L,
) {
    val exercise: Exercise? get() = Exercises.byIdOrNull(exerciseId)

    /** Удержание (планка): засчитывается временем. */
    val hold: Boolean get() = exercise?.hold == true

    /** Длительность подхода: не меньше засчитанного удержания. */
    val durationMs: Long get() = maxOf(endMs - startMs, heldMs).coerceAtLeast(0L)

    val hasWork: Boolean get() = reps > 0 || heldMs > 0L
}

/**
 * Подходы вольной тренировки — чистая логика без Android. События классификатора ([apply]) открывают подход
 * при первом засчитанном, добавляют к нему и закрывают его; другое упражнение закрывает открытый подход
 * само (на случай, если классификатор пересоздан и о подходе не знает). Неизменяемый: каждый шаг — новый журнал.
 */
data class FreeWorkoutLog(val sets: List<FreeSet> = emptyList()) {
    /** Открытый подход (последний, если он ещё идёт). */
    val open: FreeSet? get() = sets.lastOrNull()?.takeIf { it.status == FreeSetStatus.OPEN }

    /** Подходы с засчитанной работой, которые надо записать (закрытые и неудачные). */
    val toRecord: List<FreeSet> get() = sets.filter { it.status == FreeSetStatus.CLOSED || it.status == FreeSetStatus.FAILED }

    /** Начислено за записанные подходы. */
    val recordedMs: Long get() = sets.filter { it.status == FreeSetStatus.RECORDED }.sumOf { it.earnedMs }

    /** Заработано за тренировку: записанное плюс ещё не записанное по обычным ценам. */
    val earnedMs: Long
        get() = sets.sumOf { if (it.status == FreeSetStatus.RECORDED) it.earnedMs else FreeWorkoutMath.rewardMs(it) }

    val failed: Int get() = sets.count { it.status == FreeSetStatus.FAILED }

    val hasWork: Boolean get() = sets.any { it.hasWork }

    /** Чем продолжить классификатор после пересоздания экрана. */
    fun resume(): FreeResume? = open?.let { FreeResume(it.exerciseId, it.endMs) }

    fun apply(event: FreeEvent): FreeWorkoutLog = when (event) {
        is FreeEvent.Reps -> add(event.exerciseId, event.n, 0L, event.startMs, event.atMs)
        is FreeEvent.Held -> add(event.exerciseId, 0, event.ms, event.startMs, event.atMs)
        is FreeEvent.Closed -> closeOpen()
    }

    fun applyAll(events: List<FreeEvent>): FreeWorkoutLog = events.fold(this) { log, e -> log.apply(e) }

    /** Закрыть открытый подход (если есть). */
    fun closeOpen(): FreeWorkoutLog {
        val o = open ?: return this
        return replace(o.copy(status = FreeSetStatus.CLOSED))
    }

    /** Итог записи подхода [number]. */
    fun withResult(number: Int, saved: Boolean, earnedMs: Long): FreeWorkoutLog {
        val s = sets.firstOrNull { it.number == number } ?: return this
        return replace(
            if (saved) s.copy(status = FreeSetStatus.RECORDED, earnedMs = earnedMs) else s.copy(status = FreeSetStatus.FAILED),
        )
    }

    private fun add(exerciseId: String, reps: Int, heldMs: Long, startMs: Long, atMs: Long): FreeWorkoutLog {
        var log = this
        val o = log.open
        if (o != null && o.exerciseId != exerciseId) log = log.closeOpen()
        val cur = log.open
        return if (cur == null) {
            log.copy(
                sets = log.sets + FreeSet(
                    // Номер не повторяется никогда: из него ключ записи (защита от двойной оплаты).
                    number = (log.sets.maxOfOrNull { it.number } ?: 0) + 1,
                    exerciseId = exerciseId,
                    reps = reps,
                    heldMs = heldMs,
                    startMs = minOf(startMs, atMs),
                    endMs = atMs,
                    status = FreeSetStatus.OPEN,
                ),
            )
        } else {
            log.replace(cur.copy(reps = cur.reps + reps, heldMs = cur.heldMs + heldMs, endMs = maxOf(cur.endMs, atMs)))
        }
    }

    private fun replace(s: FreeSet): FreeWorkoutLog = copy(sets = sets.map { if (it.number == s.number) s else it })

    /** Строка для сохранения состояния экрана (поворот, пересоздание). */
    fun encode(): String = sets.joinToString(";") {
        listOf(it.number, it.exerciseId, it.reps, it.heldMs, it.startMs, it.endMs, it.status.name, it.earnedMs).joinToString(",")
    }

    companion object {
        /** Обратно из [encode]; испорченные записи и неизвестные упражнения пропускаются. */
        fun decode(s: String): FreeWorkoutLog {
            if (s.isEmpty()) return FreeWorkoutLog()
            val sets = s.split(';').mapNotNull { row ->
                val p = row.split(',')
                if (p.size != 8) return@mapNotNull null
                runCatching {
                    FreeSet(
                        number = p[0].toInt(),
                        exerciseId = p[1],
                        reps = p[2].toInt(),
                        heldMs = p[3].toLong(),
                        startMs = p[4].toLong(),
                        endMs = p[5].toLong(),
                        status = FreeSetStatus.valueOf(p[6]),
                        earnedMs = p[7].toLong(),
                    )
                }.getOrNull()?.takeIf { Exercises.byIdOrNull(it.exerciseId) != null }
            }
            return FreeWorkoutLog(sets)
        }
    }
}
