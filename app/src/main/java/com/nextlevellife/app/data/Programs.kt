package com.nextlevellife.app.data

import kotlin.math.roundToInt

/**
 * Домашние программы (docs/next-stages-design.md, раздел 1): готовые тренировки из обычных упражнений
 * каталога — подходы с целью и отдыхом между ними. Это «комплексы» по месту в приложении: прохождение
 * пишется в complex_runs (complex_id = [Program.id], префикс [Programs.PREFIX]), а каждый подход —
 * сразу после выполнения обычным подходом упражнения ([Workouts.recordSets] / [Workouts.recordHold])
 * по обычным ценам. Подходы идут в силу и «Сайтаму» как любые другие; прохождение бонуса комплекса
 * и времени выносливости не даёт (StatsMath, правила версии 6). Бонуса минут за завершение нет.
 * Всё засчитанное подтверждает камера.
 */

/**
 * Шаг программы: упражнение [exercise], [sets] подходов с целью [target] (повторения; у удержания —
 * секунды) и отдыхом [restSeconds] после каждого подхода (после последнего подхода программы отдыха нет).
 */
data class ProgramStep(
    val exercise: Exercise,
    val sets: Int,
    val target: Int,
    val restSeconds: Int,
) {
    val hold: Boolean get() = exercise.hold

    /** Цель удержания в мс (только для [hold]). */
    val targetMs: Long get() = target * 1000L

    val restMs: Long get() = restSeconds * 1000L
}

/** Место подхода в программе: шаг [stepIndex] и подход [setIndex] (оба с нуля). */
data class ProgramSlot(val stepIndex: Int, val setIndex: Int)

/** Готовая программа: [title] — название на карточке, [subtitle] — коротко о ней. */
data class Program(
    val id: String,
    val title: String,
    val subtitle: String,
    val steps: List<ProgramStep>,
) {
    /** Есть гиревые упражнения: перед стартом выбирается гиря. */
    val kettlebell: Boolean get() = steps.any { it.exercise.kettlebell }

    /** Все подходы по порядку. */
    val slots: List<ProgramSlot> =
        steps.flatMapIndexed { i, step -> (0 until step.sets).map { ProgramSlot(i, it) } }

    val totalSets: Int get() = slots.size

    fun step(slot: ProgramSlot): ProgramStep = steps[slot.stepIndex]
}

/**
 * Итог одного подхода: засчитанные камерой повторения [reps] или удержание [heldMs], начислено [earnedMs].
 * Подход без работы (пропущенный) в итоги не входит.
 */
data class ProgramSetResult(
    val stepIndex: Int,
    val setIndex: Int,
    val reps: Int = 0,
    val heldMs: Long = 0L,
    val earnedMs: Long = 0L,
) {
    val hasWork: Boolean get() = reps > 0 || heldMs > 0L
}

/**
 * Итог прохождения: [setsDone] — подходов с засчитанной работой, [goalSets] — из них с достигнутой целью,
 * [completed] — цель достигнута во всех подходах программы.
 */
data class ProgramSummary(
    val setsDone: Int,
    val goalSets: Int,
    val totalSets: Int,
    val totalReps: Int,
    val heldMs: Long,
    val earnedMs: Long,
    val completed: Boolean,
) {
    val hasWork: Boolean get() = setsDone > 0
}

object Programs {
    /** Префикс id программ: по нему прохождения исключаются из бонуса комплекса (StatsMath). */
    const val PREFIX = "home_"

    /** Группа на главной. */
    const val GROUP_TITLE = "Домашние тренировки"

    val beginner = Program(
        id = "home_beginner",
        title = "Новичок, всё тело, ≈20 мин",
        subtitle = "Приседания, отжимания, пресс и планка — по три подхода",
        steps = listOf(
            ProgramStep(Exercises.squats, sets = 3, target = 10, restSeconds = 60),
            ProgramStep(Exercises.pushups, sets = 3, target = 6, restSeconds = 60),
            ProgramStep(Exercises.abs, sets = 3, target = 10, restSeconds = 60),
            ProgramStep(Exercises.plank, sets = 3, target = 20, restSeconds = 60),
        ),
    )

    val legsCore = Program(
        id = "home_legs_core",
        title = "Ноги и кор",
        subtitle = "Приседания, пресс и планка",
        steps = listOf(
            ProgramStep(Exercises.squats, sets = 3, target = 12, restSeconds = 45),
            ProgramStep(Exercises.abs, sets = 3, target = 10, restSeconds = 45),
            ProgramStep(Exercises.plank, sets = 3, target = 30, restSeconds = 45),
        ),
    )

    val kettlebellStrength = Program(
        id = "home_kb_strength",
        title = "Гиря — силовая",
        subtitle = "Присед у груди, становая тяга, жим и тяга к поясу",
        steps = listOf(
            ProgramStep(Exercises.gobletSquat, sets = 3, target = 10, restSeconds = 75),
            ProgramStep(Exercises.deadlift, sets = 3, target = 10, restSeconds = 75),
            ProgramStep(Exercises.press, sets = 3, target = 10, restSeconds = 75),
            ProgramStep(Exercises.row, sets = 3, target = 10, restSeconds = 75),
        ),
    )

    val kettlebellEndurance = Program(
        id = "home_kb_endurance",
        title = "Гиря — выносливость",
        subtitle = "Махи, трастер и присед у груди с коротким отдыхом",
        steps = listOf(
            ProgramStep(Exercises.swing, sets = 4, target = 20, restSeconds = 40),
            ProgramStep(Exercises.thruster, sets = 3, target = 10, restSeconds = 40),
            ProgramStep(Exercises.gobletSquat, sets = 3, target = 12, restSeconds = 40),
        ),
    )

    val all: List<Program> = listOf(beginner, legsCore, kettlebellStrength, kettlebellEndurance)

    /** Готовая или своя программа ([CustomPrograms], id с префиксом [CustomProgramMath.PREFIX]); null — нет такой. */
    fun byId(id: String): Program? = all.firstOrNull { it.id == id } ?: CustomPrograms.byId(id)

    /** Прохождение домашней программы (complex_runs.complex_id), включая свои программы. */
    fun isProgramId(complexId: String): Boolean = complexId.startsWith(PREFIX)
}

/** Чистая логика программ: длительность, награды, итоги, ключи записи. Без Android — проверяется тестами. */
object ProgramMath {
    /** Темп одного повторения для оценки длительности. */
    const val REP_MS = 3_000L

    /** Встать в исходное положение перед подходом. */
    const val SET_SETUP_MS = 10_000L

    /** Переставить телефон и встать по-новому перед следующим упражнением. */
    const val CHANGE_MS = 30_000L

    /** Поставить телефон перед первым подходом. */
    const val START_MS = 30_000L

    /** sessionId подхода для [Workouts]: ключ журнала `set:program:<session>:<step>:<set>` — повтор не платит дважды. */
    fun setSessionId(session: String, stepIndex: Int, setIndex: Int): String = "program:$session:$stepIndex:$setIndex"

    /** sessionId прохождения: ключ журнала `complex:program:<session>`. */
    fun runSessionId(session: String): String = "program:$session"

    /** Отдых после подхода [slotIndex]; после последнего подхода программы — 0. */
    fun restAfterMs(program: Program, slotIndex: Int): Long =
        if (slotIndex >= program.slots.lastIndex) 0L else program.step(program.slots[slotIndex]).restMs

    /** Следующий подход — уже другое упражнение (нужна подсказка про ракурс). */
    fun changesExercise(program: Program, slotIndex: Int): Boolean {
        val next = program.slots.getOrNull(slotIndex + 1) ?: return false
        return next.stepIndex != program.slots[slotIndex].stepIndex
    }

    /** Время работы одного подхода по плану. */
    fun workMs(step: ProgramStep): Long = if (step.hold) step.targetMs else step.target * REP_MS

    /**
     * Примерная длительность: работа подходов, подготовка к каждому подходу, отдых между подходами,
     * перестановка телефона при смене упражнения и в начале. Потеря распознавания её увеличивает.
     */
    fun estimateMs(program: Program): Long {
        var total = START_MS
        program.slots.forEachIndexed { i, slot ->
            total += SET_SETUP_MS + workMs(program.step(slot)) + restAfterMs(program, i)
            if (changesExercise(program, i)) total += CHANGE_MS
        }
        return total
    }

    /** Длительность в минутах для карточки («≈20 мин»). */
    fun estimateMinutes(program: Program): Int = (estimateMs(program) / 60_000.0).roundToInt().coerceAtLeast(1)

    /**
     * Награда за подход по обычным ценам: повторения — [Exercises.rewardMs] (гиря — с множителем веса),
     * удержание — [Exercises.holdRewardMs] за целые секунды.
     */
    fun setRewardMs(step: ProgramStep, reps: Int, heldMs: Long, weightKg: Int): Long =
        if (step.hold) Exercises.holdRewardMs(step.exercise, heldMs) else Exercises.rewardMs(step.exercise, reps.coerceAtLeast(0), weightKg)

    /** Награда за программу, если все подходы выполнены ровно до цели. */
    fun maxRewardMs(program: Program, weightKg: Int = Weights.DEFAULT): Long =
        program.slots.sumOf { slot ->
            val step = program.step(slot)
            setRewardMs(step, step.target, step.targetMs, weightKg)
        }

    /** Сколько засчитывать в подход: не больше цели (лишнее сверх цели не набирается — счёт останавливается). */
    fun cappedReps(step: ProgramStep, reps: Int): Int = reps.coerceIn(0, step.target)

    fun cappedHeldMs(step: ProgramStep, heldMs: Long): Long = heldMs.coerceIn(0L, step.targetMs)

    /** Цель подхода достигнута. */
    fun reached(step: ProgramStep, reps: Int, heldMs: Long): Boolean =
        if (step.hold) heldMs >= step.targetMs else reps >= step.target

    /** Итог прохождения по записанным подходам [results] (подходы без работы не считаются). */
    fun summary(program: Program, results: List<ProgramSetResult>): ProgramSummary {
        val done = results.filter { it.hasWork && it.stepIndex in program.steps.indices }
            .distinctBy { it.stepIndex to it.setIndex }
        val reachedSlots = done.filter { reached(program.steps[it.stepIndex], it.reps, it.heldMs) }
            .map { ProgramSlot(it.stepIndex, it.setIndex) }
            .toSet()
        return ProgramSummary(
            setsDone = done.size,
            goalSets = reachedSlots.size,
            totalSets = program.totalSets,
            totalReps = done.sumOf { it.reps },
            heldMs = done.sumOf { it.heldMs },
            earnedMs = done.sumOf { it.earnedMs },
            completed = program.slots.all { it in reachedSlots },
        )
    }
}
