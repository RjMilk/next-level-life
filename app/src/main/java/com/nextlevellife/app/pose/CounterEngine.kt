package com.nextlevellife.app.pose

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Общие для всех упражнений настройки автомата. */
internal object EngineTuning {
    /**
     * Защита от одиночного выброса: порог засчитывается, если он пройден на свежем кадре,
     * а соседний по времени кадр (предыдущий или следующий) не дальше этого допуска от порога.
     */
    const val PAIR_ANGLE_TOLERANCE = 15f

    /** То же для высот (доли торса). */
    const val PAIR_RATIO_TOLERANCE = 0.15f

    /** Соседние кадры дальше этого по времени парой не считаются. */
    const val PAIR_MAX_GAP_MS = 400L

    /** Каждое условие крайней точки «защёлкивается» на столько; точка достигнута, когда защёлкнуты все. */
    const val LATCH_MS = 300L

    /** Постоянная времени сглаживания глубины для индикатора (на пороги не влияет). */
    const val DEPTH_TAU_MS = 80f

    /** Нарушение позы дольше этого (непрерывно) — человек вышел из упражнения. */
    const val POSTURE_GRACE_MS = 700L

    /** Человека нет в кадре дольше — фаза сбрасывается в исходную, счёт сохраняется. */
    const val LOST_RESET_MS = 1500L

    /** Сторона тела меняется, если другая заметно увереннее (на столько в среднем на сустав)… */
    const val SIDE_SWITCH_MARGIN = 0.15f

    /** …непрерывно не меньше этого времени… */
    const val SIDE_SWITCH_MS = 500L

    /** …или если текущая сторона потеряна (главные суставы не свежие) дольше этого. */
    const val SIDE_LOST_MS = 300L

    /** Подсказка «Ниже/Выше»: попытка зашла хотя бы на эту глубину… */
    const val HINT_MIN_PEAK = 0.35f

    /** …и откатилась назад на столько, так и не дойдя до полной амплитуды. */
    const val HINT_TURN_BACK = 0.2f

    /** Глубина, ниже которой попытка считается законченной (пик для подсказки сбрасывается). */
    const val HINT_RESET_DEPTH = 0.05f
}

/** Пороговое условие на один признак: [feature] выше или ниже [threshold]. */
internal class Cond(val feature: Feature, private val above: Boolean, val threshold: Float) {
    private val tolerance =
        if (feature.isAngle) EngineTuning.PAIR_ANGLE_TOLERANCE else EngineTuning.PAIR_RATIO_TOLERANCE

    fun holds(v: Float): Boolean = if (above) v > threshold else v < threshold

    private fun near(v: Float): Boolean = if (above) v > threshold - tolerance else v < threshold + tolerance

    /**
     * Порог пройден на несглаженном сигнале и подтверждён соседним кадром: один из двух
     * последних замеров (свежий) проходит порог, другой — в пределах допуска.
     */
    fun confirmed(prev: Sample?, cur: Sample): Boolean {
        if (prev == null || cur.timeMs - prev.timeMs > EngineTuning.PAIR_MAX_GAP_MS) return false
        val a = prev[feature]
        val b = cur[feature]
        return (cur.isFresh(feature) && holds(b) && near(a)) ||
            (prev.isFresh(feature) && holds(a) && near(b))
    }

    override fun toString() = "$feature ${if (above) ">" else "<"} $threshold"
}

internal fun Feature.above(threshold: Float) = Cond(this, true, threshold)
internal fun Feature.below(threshold: Float) = Cond(this, false, threshold)

/**
 * Описание упражнения для общего автомата [PhaseCounter].
 *
 * Повторение — путь «исходное положение → [far] (полная амплитуда) → [finish]».
 * Засчитывается в момент [finish]. Для большинства упражнений [finish] — возврат в исходное
 * положение; у трастера, маха и становой — верхняя точка после нижней.
 *
 * @param enter строгая проверка позы — только для входа в упражнение.
 * @param hold мягкая проверка позы во время движения; NaN (сустав не виден) нарушением не считается.
 * @param rest исходное положение, в котором автомат «взводится» перед первым повторением.
 *   null — взводиться не нужно (мах и становая начинаются с любого положения);
 *   зоной покоя тогда считается [finish].
 * @param depth 0..1 — путь от исходной точки к порогу полной амплитуды (для индикатора).
 * @param minRepMs полный цикл (от выхода из зоны покоя до [finish]) короче — шум.
 */
internal class ExerciseConfig(
    val id: String,
    val postureHint: String,
    val startHint: String = "",
    val farHint: String,
    val finishHint: String,
    val enter: (Sample) -> Boolean,
    val hold: (Sample) -> Boolean,
    val rest: List<Cond>?,
    val far: List<Cond>,
    val finish: List<Cond>,
    val depth: (Sample) -> Float,
    val minRepMs: Long = 300L,
    val sideMode: AngularSide = AngularSide.BEST_VISIBLE,
    val extraRequired: Set<Limb> = emptySet(),
    val minConfidence: Float = JointTuning.MIN_CONFIDENCE,
    val requireFresh: Boolean = false,
    val resetOnSideSwitch: Boolean = false,
    val extremeConfirmMs: Long = 0L,
    val preferValidEntrySide: Boolean = false,
) {
    /** Главные суставы: те, что нужны условиям переходов. Без них человека «не видно». */
    val required: Set<Limb> = (far + finish + rest.orEmpty()).flatMap { it.feature.joints }.toSet() + extraRequired

    /** Остальные суставы (поза, масштаб) — используются, если видны. */
    val optional: Set<Limb> = Limb.entries.toSet() - required

    /** Зона покоя: где начинается повторение. */
    val restZone: List<Cond> = rest ?: finish
}

/** Защёлки условий: момент последнего подтверждения каждого условия. */
private class Latches(private val conds: List<Cond>, private val confirmMs: Long = 0L) {
    private val at = LongArray(conds.size) { NEVER }
    private val since = LongArray(conds.size) { NEVER }
    private var previousUpdate = NEVER

    fun update(tracks: List<SideTrack>, now: Long) {
        if (confirmMs > 0L && previousUpdate != NEVER && now - previousUpdate > JointTuning.STALE_MS) clear()
        previousUpdate = now
        conds.forEachIndexed { i, c ->
            val confirmed = tracks.all { track -> track.cur?.let { c.confirmed(track.prev, it) } == true }
            if (confirmMs == 0L) {
                if (confirmed) at[i] = now
            } else if (confirmed && tracks.all { track -> track.cur?.let { it.isFresh(c.feature) && c.holds(it[c.feature]) } == true }) {
                if (since[i] == NEVER) since[i] = now
                if (now - since[i] >= confirmMs) at[i] = now
            } else {
                since[i] = NEVER
            }
        }
    }

    /** Все условия подтверждены не раньше [since] и не дальше [EngineTuning.LATCH_MS] назад. */
    fun all(now: Long, since: Long): Boolean {
        val from = max(now - EngineTuning.LATCH_MS, since)
        return at.all { it != NEVER && it >= from }
    }

    fun clear() {
        at.fill(NEVER)
        since.fill(NEVER)
        previousUpdate = NEVER
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/** Один конечный автомат на все упражнения; отличается только [ExerciseConfig]. */
internal class PhaseCounter(private val cfg: ExerciseConfig) : RepCounter {
    private enum class Phase { NEED_START, WAIT_FAR, WAIT_RETURN }

    override var state: RepState = RepState()
        private set

    /** Фильтры другой стороны не смешиваем: при смене стороны защёлки и сглаживание начинаются заново. */
    private val sides = SideSelector(cfg.required, minConfidence = cfg.minConfidence) {
        restLatch.clear()
        farLatch.clear()
        finishLatch.clear()
        depthEma = Float.NaN
        if (cfg.resetOnSideSwitch) resetMotion(Latches.NEVER)
    }

    private var count = 0
    private var phase = initialPhase()
    private var phaseSince = Latches.NEVER
    private var lastTrackedMs: Long? = null

    private var engaged = false
    private var violationSince: Long? = null

    private val restLatch = Latches(cfg.restZone, cfg.extremeConfirmMs)
    private val farLatch = Latches(cfg.far, cfg.extremeConfirmMs)
    private val finishLatch = Latches(cfg.finish, cfg.extremeConfirmMs)

    /** Последний кадр в зоне покоя и момент выхода из неё (начало движения). */
    private var lastRestMs: Long? = null
    private var moveStartMs: Long? = null
    private var cycleStartMs = 0L

    private var depthEma = Float.NaN
    private var depthAt = 0L
    private var peakDepth = 0f
    private var troughDepth = 1f
    /** Самая глубокая точка текущего повторения после прохождения порога. */
    private var returnPeak = 0f
    private var moveHint = ""

    // Отметка «не засчитано» ([RepState.missed]) — отдельно от подсказок: подсказка «Выше» бывает и сразу
    // после засчитанного повтора, пока сглаженная глубина ещё спадает, а сигнал там был бы ложным.
    /** В фазе возврата половинка уже отмечена: одна отметка на фазу. */
    private var returnMissed = false

    /** Глубина спадает (после засчитанного повтора или незасчитанной попытки): новой попытки ещё нет. */
    private var missFalling = true
    private var missTrough = Float.MAX_VALUE
    private var missPeak = 0f

    /**
     * Попытка в фазе «к крайней точке»: начинается подъёмом глубины на [EngineTuning.HINT_TURN_BACK]
     * от впадины и не засчитана, если откатилась с пика (не ниже [EngineTuning.HINT_MIN_PEAK]) на столько же,
     * не дойдя до крайней точки. true — на этом кадре попытка не засчитана.
     */
    private fun trackFarMiss(depth: Float): Boolean {
        if (missFalling) {
            missTrough = min(missTrough, depth)
            if (depth >= missTrough + EngineTuning.HINT_TURN_BACK) {
                missFalling = false
                missPeak = depth
            }
            return false
        }
        missPeak = max(missPeak, depth)
        if (missPeak >= EngineTuning.HINT_MIN_PEAK && depth <= missPeak - EngineTuning.HINT_TURN_BACK) {
            missFalling = true
            missTrough = depth
            return true
        }
        return false
    }

    private fun resetMiss() {
        returnMissed = false
        missFalling = true
        missTrough = Float.MAX_VALUE
        missPeak = 0f
    }

    private fun initialPhase() = if (cfg.rest != null) Phase.NEED_START else Phase.WAIT_FAR

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        sides.update(frame)

        val last = lastTrackedMs
        val resetGap = if (cfg.extremeConfirmMs > 0L) JointTuning.STALE_MS else EngineTuning.LOST_RESET_MS
        if (last != null && t - last > resetGap) {
            resetMotion(t)
            engaged = false
            sides.drop()
            lastTrackedMs = null
        }

        // Пустой кадр — человека нет совсем: устаревшие суставы его не заменяют
        // (они только для кратко пропавших отдельных суставов).
        val tracks = if (frame.points.isEmpty()) emptyList() else selectTracks(t)
        val samples = tracks.mapNotNull { it.cur }
        if (samples.isEmpty()) {
            if (cfg.extremeConfirmMs > 0L) {
                restLatch.clear()
                farLatch.clear()
                finishLatch.clear()
            }
            val closest = sides.closest()
            val hint = if (frame.points.isEmpty()) {
                BodyLocator.HINT_NOT_IN_FRAME
            } else {
                BodyLocator.missingHint(closest.missing(cfg.required))
            }
            return emit(tracking = false, inPosition = false, hint = hint, depth = 0f, atStart = false)
        }
        lastTrackedMs = t

        // Поза: строго на входе, дальше держится, пока нарушение не длится дольше POSTURE_GRACE_MS.
        if (!engaged) {
            if (samples.all(cfg.enter)) {
                engaged = true
                violationSince = null
            }
        } else if (samples.all(cfg.hold)) {
            violationSince = null
        } else {
            val since = violationSince ?: t.also { violationSince = it }
            val graceMs = if (cfg.extremeConfirmMs > 0L) JointTuning.STALE_MS else EngineTuning.POSTURE_GRACE_MS
            if (t - since > graceMs) {
                engaged = false
                resetMotion(t)
            }
        }
        if (!engaged) {
            return emit(tracking = true, inPosition = false, hint = cfg.postureHint, depth = 0f, atStart = false)
        }

        // Новые строгие AngularProfile не подтверждают крайние точки во время нарушения позы.
        // Старые профили сохраняют прежний допуск POSTURE_GRACE_MS без изменения поведения.
        if (cfg.requireFresh && !samples.all(cfg.hold)) {
            restLatch.clear()
            farLatch.clear()
            finishLatch.clear()
            return emit(tracking = true, inPosition = false, hint = cfg.postureHint, depth = 0f, atStart = false)
        }

        val depth = smoothDepth(t, samples.minOf(cfg.depth))
        restLatch.update(tracks, t)
        farLatch.update(tracks, t)
        finishLatch.update(tracks, t)
        val inRest = samples.all { s -> cfg.restZone.all { it.holds(s[it.feature]) } }
        // Отметка «не засчитано» для звука ([RepState.missed]); счёт и подсказки она не меняет.
        var missed = false

        when (phase) {
            Phase.NEED_START -> if (restLatch.all(t, Latches.NEVER)) enterPhase(Phase.WAIT_FAR, t)

            Phase.WAIT_FAR -> {
                if (inRest) {
                    lastRestMs = t
                    moveStartMs = null
                } else if (moveStartMs == null) {
                    // Выход из зоны покоя где-то между последним кадром покоя и этим кадром.
                    moveStartMs = lastRestMs?.let { (it + t) / 2 } ?: t
                }
                if (farLatch.all(t, phaseSince)) {
                    cycleStartMs = moveStartMs ?: phaseSince
                    enterPhase(Phase.WAIT_RETURN, t)
                    troughDepth = depth
                    returnPeak = depth
                } else {
                    peakDepth = max(peakDepth, depth)
                    if (peakDepth >= EngineTuning.HINT_MIN_PEAK &&
                        depth <= peakDepth - EngineTuning.HINT_TURN_BACK
                    ) {
                        moveHint = cfg.farHint
                    }
                    if (depth <= EngineTuning.HINT_RESET_DEPTH) peakDepth = 0f
                    missed = trackFarMiss(depth)
                }
            }

            Phase.WAIT_RETURN -> {
                if (depth > returnPeak) {
                    // Человек всё ещё опускается: порог пройден раньше нижней точки, это не возврат.
                    returnPeak = depth
                    troughDepth = depth
                } else {
                    troughDepth = min(troughDepth, depth)
                }
                if (finishLatch.all(t, phaseSince)) {
                    // Слишком короткий цикл — шум: не засчитываем и ждём новое повторение.
                    if (t - cycleStartMs >= cfg.minRepMs) count++
                    enterPhase(Phase.WAIT_FAR, t)
                    lastRestMs = t
                } else if (troughDepth <= returnPeak - EngineTuning.HINT_TURN_BACK &&
                    depth >= troughDepth + EngineTuning.HINT_TURN_BACK
                ) {
                    moveHint = cfg.finishHint
                    // Поднялся не до конца и снова пошёл вниз: половинка «не засчитана» — один раз за фазу.
                    if (!returnMissed) {
                        returnMissed = true
                        missed = true
                    }
                }
            }
        }

        val hint = if (phase == Phase.NEED_START) cfg.startHint else moveHint
        val atStart = inRest && phase != Phase.WAIT_RETURN
        return emit(tracking = true, inPosition = true, hint = hint, depth = depth, atStart = atStart, missed = missed)
    }

    private fun selectTracks(t: Long): List<SideTrack> {
        val selected = when (cfg.sideMode) {
            AngularSide.BEST_VISIBLE -> listOfNotNull(
                if (cfg.preferValidEntrySide) sides.choosePositioned(t, if (engaged) cfg.hold else cfg.enter) else sides.choose(t),
            )
            AngularSide.LEFT -> listOf(sides.tracks.first { it.side == Side.LEFT })
            AngularSide.RIGHT -> listOf(sides.tracks.first { it.side == Side.RIGHT })
            AngularSide.BOTH -> sides.tracks
        }
        return selected.takeIf { tracks ->
            tracks.all { it.available(cfg.required) && (!cfg.requireFresh || it.allFresh(cfg.required)) }
        }.orEmpty()
    }

    private fun smoothDepth(t: Long, raw: Float): Float {
        if (raw.isNaN()) return if (depthEma.isNaN()) 0f else depthEma
        val r = raw.coerceIn(0f, 1f)
        depthEma = if (depthEma.isNaN()) {
            r
        } else {
            val alpha = 1f - exp(-(t - depthAt).coerceAtLeast(0L) / EngineTuning.DEPTH_TAU_MS)
            depthEma + alpha * (r - depthEma)
        }
        depthAt = t
        return depthEma
    }

    private fun enterPhase(p: Phase, t: Long) {
        phase = p
        phaseSince = t
        peakDepth = 0f
        moveHint = ""
        resetMiss()
        if (p == Phase.WAIT_FAR) {
            lastRestMs = t
            moveStartMs = null
        }
    }

    override fun reset() {
        count = 0
        resetMotion(Latches.NEVER)
        engaged = false
        lastTrackedMs = null
        sides.reset()
        state = RepState()
    }

    private fun resetMotion(t: Long) {
        phase = initialPhase()
        phaseSince = t
        violationSince = null
        restLatch.clear()
        farLatch.clear()
        finishLatch.clear()
        lastRestMs = null
        moveStartMs = null
        peakDepth = 0f
        troughDepth = 1f
        returnPeak = 0f
        moveHint = ""
        resetMiss()
        depthEma = Float.NaN
    }

    private fun emit(
        tracking: Boolean,
        inPosition: Boolean,
        hint: String,
        depth: Float,
        atStart: Boolean,
        missed: Boolean = false,
    ): RepState {
        state = RepState(count, tracking, inPosition, hint, depth, atStart, missed = missed)
        return state
    }
}
