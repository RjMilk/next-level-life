package com.nextlevellife.app.data

/**
 * Зал: упражнения со снарядами. Телефон стоит на стойке или штативе, повторения засчитывает только камера —
 * ручного ввода повторений нет. Перед подходом человек заявляет вес; камера его не проверяет, поэтому вес
 * не влияет ни на минуты, ни на очки характеристик — он только для истории и рекомендаций.
 *
 * Вес хранится в граммах (sets.load_g) — так подходит любому снаряду. Что именно означает число, задаёт
 * [LoadMode]: общий вес штанги, вес одной гантели, вес стека тренажёра или противовес гравитрона.
 */

/** Вид снаряда. */
enum class Equipment(val title: String) {
    BARBELL("штанга"),
    DUMBBELL("гантели"),
    MACHINE("тренажёр"),

    /** Свой вес с помощью (гравитрон): противовес облегчает упражнение. */
    ASSISTED("свой вес с помощью"),
}

/**
 * Способ учёта веса: пределы и шаг в граммах, вес по умолчанию для первого подхода.
 * [assists] — вес помогает, а не мешает (противовес гравитрона): прогресс — это меньший вес.
 */
enum class LoadMode(
    val minG: Int,
    val maxG: Int,
    val stepG: Int,
    val defaultG: Int,
    val assists: Boolean,
    /** Что означает число — мелкой строкой перед подходом. */
    val title: String,
) {
    /** Общий вес штанги с блинами; по умолчанию — пустой гриф. */
    BARBELL_TOTAL(20_000, 400_000, 500, 20_000, false, "общий вес штанги с блинами"),

    /** Вес одной гантели. */
    DUMBBELL_EACH(1_000, 80_000, 500, 10_000, false, "вес одной гантели"),

    /** Вес (отметка) стека тренажёра. */
    STACK(1_000, 300_000, 500, 20_000, false, "вес на стеке тренажёра"),

    /** Противовес гравитрона: чем меньше, тем тяжелее. */
    COUNTERWEIGHT(0, 150_000, 500, 30_000, true, "противовес — помогает, а не мешает"),
}

/**
 * Упражнение зала: снаряд, способ учёта веса и шаг роста рабочего веса для рекомендации
 * («Попробуй W + [stepUpG]»; у противовеса — W − [stepUpG]).
 */
data class GymSpec(
    val equipment: Equipment,
    val loadMode: LoadMode,
    val stepUpG: Int,
)

/** Подход упражнения зала для истории: сутки, время, засчитанные камерой повторения и заявленный вес (может не быть). */
data class GymSet(val day: Long, val timeMs: Long, val reps: Int, val loadG: Int?)

/** Рабочий вес дня: лучший вес подхода, где камера засчитала не меньше [GymMath.WORK_REPS] повторений. */
data class GymDay(val day: Long, val loadG: Int)

/** Рекомендация на следующий раз. */
sealed interface GymAdvice {
    /** Рабочий вес последней тренировки. */
    val loadG: Int

    /** Три подхода по 5 с одним весом сделаны — пора добавить. */
    data class Increase(override val loadG: Int, val nextG: Int) : GymAdvice

    /** Остаться на этом весе. */
    data class Keep(override val loadG: Int) : GymAdvice
}

/** Сводка по упражнению зала: для карточки на главной, экрана подхода и «Прогресса». */
data class GymSummary(
    /** Вес по умолчанию перед подходом: последний заявленный, иначе вес режима по умолчанию. */
    val defaultLoadG: Int,
    /** Рабочий вес последнего дня с рабочими подходами; null — их ещё не было. */
    val lastWorkingG: Int?,
    /** Лучший рабочий вес за всю историю (у противовеса — наименьший). */
    val bestWorkingG: Int?,
    val advice: GymAdvice?,
    val history: List<GymDay>,
)

/** Чистая логика зала, без Android: её проверяют юнит-тесты. */
object GymMath {
    /** Подход рабочий, если камера засчитала хотя бы столько повторений. */
    const val WORK_REPS = 5

    /** Столько рабочих подходов с одним весом — и рекомендуется прибавить. */
    const val SETS_TO_GROW = 3

    /** Общие пределы веса подхода в обмене между телефонами (граммы) и шаг: любые способы учёта. */
    const val MAX_LOAD_G = 400_000
    const val LOAD_STEP_G = 500

    /** Вес в пределах способа учёта, округлённый до ближайшего шага (половина шага — вверх). */
    fun normalize(mode: LoadMode, loadG: Int): Int {
        val clamped = loadG.coerceIn(mode.minG, mode.maxG)
        val steps = (clamped - mode.minG + mode.stepG / 2) / mode.stepG
        return (mode.minG + steps * mode.stepG).coerceAtMost(mode.maxG)
    }

    /** Вес подходит способу учёта как есть: в пределах и кратен шагу (от нижнего предела). */
    fun isValid(mode: LoadMode, loadG: Int): Boolean =
        loadG in mode.minG..mode.maxG && (loadG - mode.minG) % mode.stepG == 0

    /** Изменить вес на [deltaG] кнопкой −/+: результат в пределах и на шаге. */
    fun adjust(mode: LoadMode, loadG: Int, deltaG: Int): Int = normalize(mode, loadG + deltaG)

    /**
     * Правило приёма веса подхода от второго телефона: null — всегда; иначе 0..[MAX_LOAD_G], кратно [LOAD_STEP_G],
     * и упражнение не из известных «не зала» (домашние, ЛФК и йога). Неизвестный этой версии id принимается:
     * это может быть упражнение зала из более новой версии, и отказ потерял бы подход навсегда.
     */
    fun acceptsLoad(exerciseId: String, loadG: Int?): Boolean {
        if (loadG == null) return true
        if (loadG !in 0..MAX_LOAD_G || loadG % LOAD_STEP_G != 0) return false
        val known = Exercises.byIdOrNull(exerciseId)
        if (known != null) return known.isGym
        return !Routines.isRoutineExercise(exerciseId)
    }

    /** Вес лучше: больше; у противовеса — меньше. */
    private fun better(mode: LoadMode, a: Int, b: Int): Int = if (mode.assists) minOf(a, b) else maxOf(a, b)

    private fun bestOf(mode: LoadMode, loads: List<Int>): Int? = loads.reduceOrNull { a, b -> better(mode, a, b) }

    /** Рабочие подходы: камера засчитала не меньше [WORK_REPS], вес заявлен. */
    private fun working(sets: List<GymSet>): List<GymSet> = sets.filter { it.reps >= WORK_REPS && it.loadG != null }

    /** История по дням (по возрастанию): рабочий вес каждого дня с рабочими подходами. */
    fun history(mode: LoadMode, sets: List<GymSet>): List<GymDay> =
        working(sets).groupBy { it.day }.toSortedMap().map { (day, group) ->
            GymDay(day, bestOf(mode, group.map { it.loadG!! })!!)
        }

    /** Вес по умолчанию перед подходом: последний заявленный (по времени), иначе [LoadMode.defaultG]. */
    fun defaultLoad(mode: LoadMode, sets: List<GymSet>): Int =
        sets.filter { it.loadG != null }.maxByOrNull { it.timeMs }?.loadG?.let { normalize(mode, it) } ?: mode.defaultG

    /**
     * Рекомендация на следующий раз. Последняя тренировка — последний день, когда упражнение делали с весом.
     * Если в тот день было не меньше [SETS_TO_GROW] рабочих подходов с одним и тем же лучшим весом W — прибавить
     * [GymSpec.stepUpG] (у противовеса — убавить; у предела — остаться). Иначе — «Оставь W», где W — лучший рабочий
     * вес того дня, а если рабочих подходов в тот день не было — рабочий вес прошлых дней. Истории нет — null.
     */
    fun advice(spec: GymSpec, sets: List<GymSet>): GymAdvice? {
        val mode = spec.loadMode
        val withLoad = sets.filter { it.loadG != null && it.reps > 0 }
        val lastDay = withLoad.maxOfOrNull { it.day } ?: return null
        val dayWork = working(withLoad.filter { it.day == lastDay })
        val w = bestOf(mode, dayWork.map { it.loadG!! })
            ?: return history(mode, withLoad.filter { it.day < lastDay }).lastOrNull()?.let { GymAdvice.Keep(it.loadG) }
        val sameWeight = dayWork.count { it.loadG == w }
        if (sameWeight < SETS_TO_GROW) return GymAdvice.Keep(w)
        val next = normalize(mode, if (mode.assists) w - spec.stepUpG else w + spec.stepUpG)
        return if (next == w) GymAdvice.Keep(w) else GymAdvice.Increase(w, next)
    }

    fun summary(spec: GymSpec, sets: List<GymSet>): GymSummary {
        val history = history(spec.loadMode, sets)
        return GymSummary(
            defaultLoadG = defaultLoad(spec.loadMode, sets),
            lastWorkingG = history.lastOrNull()?.loadG,
            bestWorkingG = bestOf(spec.loadMode, history.map { it.loadG }),
            advice = advice(spec, sets),
            history = history,
        )
    }

    /** «60 кг», «62,5 кг» — с неразрывным пробелом. */
    fun formatKg(loadG: Int): String {
        val whole = loadG / 1000
        val rest = loadG % 1000
        val number = if (rest == 0) "$whole" else "$whole," + "%03d".format(rest).trimEnd('0')
        return "$number${NBSP}кг"
    }

    /** «+2,5 кг», «−0,5 кг» — подпись кнопки изменения веса. */
    fun formatDelta(deltaG: Int): String = (if (deltaG < 0) "−" else "+") + formatKg(kotlin.math.abs(deltaG))

    /** Текст рекомендации: «Попробуй 65 кг», «Оставь 60 кг». */
    fun adviceText(advice: GymAdvice): String = when (advice) {
        is GymAdvice.Increase -> "Попробуй ${formatKg(advice.nextG)}"
        is GymAdvice.Keep -> "Оставь ${formatKg(advice.loadG)}"
    }

    /** Строка рабочего веса для карточки: «Рабочий вес 60 кг» или «Рабочего веса ещё нет». */
    fun workingLine(summary: GymSummary?): String =
        summary?.lastWorkingG?.let { "Рабочий вес ${formatKg(it)}" } ?: "Рабочего веса ещё нет"

    /** Мелкая строка у выбора веса: вес не влияет на награду. */
    const val LOAD_NOTE = "Вес не влияет на минуты и очки — камера его не видит. Он нужен для истории и советов."

    private const val NBSP = ' '
}
