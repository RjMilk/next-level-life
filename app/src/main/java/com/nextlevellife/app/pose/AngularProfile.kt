package com.nextlevellife.app.pose

/** Одна выбранная сторона либо синхронное движение обеих сторон одним повторением. */
internal enum class AngularSide { BEST_VISIBLE, LEFT, RIGHT, BOTH }

/** Видимость главных суставов профиля. Остальные суставы не ограничивают ракурс. */
internal data class AngularVisibility(
    val required: Set<Limb> = emptySet(),
    val minConfidence: Float = JointTuning.MIN_CONFIDENCE,
    val requireFresh: Boolean = true,
) {
    init {
        require(minConfidence.isFinite() && minConfidence in 0f..1f)
    }
}

/** Допустимый интервал сигнала для входа в позу и для её удержания во время движения. */
internal data class AngularPosition(
    val signal: Feature,
    val enterMin: Float = Float.NEGATIVE_INFINITY,
    val enterMax: Float = Float.POSITIVE_INFINITY,
    val holdMin: Float = enterMin,
    val holdMax: Float = enterMax,
) {
    init {
        require(!enterMin.isNaN() && !enterMax.isNaN() && enterMin <= enterMax)
        require(!holdMin.isNaN() && !holdMax.isNaN() && holdMin <= holdMax)
    }

    fun accepts(sample: Sample, entering: Boolean): Boolean {
        val value = sample[signal]
        return value.isFinite() && if (entering) value in enterMin..enterMax else value in holdMin..holdMax
    }
}

/**
 * Декларативный профиль полного цикла «исходная точка → дальняя точка → возврат».
 * [signal] задаёт суставной угол или нормированный сигнал [Feature]. Порог [startThreshold]
 * может быть как больше, так и меньше [farThreshold]. [hysteresis] расширяет зону между
 * крайними точками: от исходного порога наружу и от дальнего порога наружу на эту величину.
 * Например, 150 → 100 с гистерезисом 5 требует >155 → <95 → >155.
 * Для углов единица — градус, для высот — доля масштаба тела.
 * Подтверждение соседними кадрами, выдержка позы и сброс при потере человека остаются в [PhaseCounter].
 */
internal data class AngularProfile(
    val id: String,
    val signal: Feature,
    val startThreshold: Float,
    val farThreshold: Float,
    val hysteresis: Float = 0f,
    val side: AngularSide = AngularSide.BEST_VISIBLE,
    val visibility: AngularVisibility = AngularVisibility(),
    val positions: List<AngularPosition> = emptyList(),
    val minCycleMs: Long = 300L,
    val postureHint: String,
    val startHint: String,
    val farHint: String,
    val finishHint: String,
) {
    init {
        require(id.isNotBlank())
        require(startThreshold.isFinite() && farThreshold.isFinite() && startThreshold != farThreshold)
        require(hysteresis.isFinite() && hysteresis >= 0f)
        require(minCycleMs > 0L)
    }

    fun config(): ExerciseConfig {
        val decreasing = startThreshold > farThreshold
        val start = startThreshold + if (decreasing) hysteresis else -hysteresis
        val far = farThreshold + if (decreasing) -hysteresis else hysteresis
        val startCondition = if (decreasing) signal.above(start) else signal.below(start)
        val farCondition = if (decreasing) signal.below(far) else signal.above(far)
        return ExerciseConfig(
            id = id,
            postureHint = postureHint,
            startHint = startHint,
            farHint = farHint,
            finishHint = finishHint,
            enter = { sample -> positions.all { it.accepts(sample, entering = true) } },
            hold = { sample -> positions.all { it.accepts(sample, entering = false) } },
            rest = listOf(startCondition),
            far = listOf(farCondition),
            finish = listOf(startCondition),
            depth = { Geometry.progress(it[signal], start, far) },
            minRepMs = minCycleMs,
            sideMode = side,
            extraRequired = visibility.required + positions.flatMap { it.signal.joints },
            minConfidence = visibility.minConfidence,
            requireFresh = visibility.requireFresh,
            resetOnSideSwitch = true,
        )
    }

    fun counter(): RepCounter = PhaseCounter(config())
}
