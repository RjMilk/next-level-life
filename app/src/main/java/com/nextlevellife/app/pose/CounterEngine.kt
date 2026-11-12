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
) {
    /** Главные суставы: те, что нужны условиям переходов. Без них человека «не видно». */
    val required: Set<Limb> = (far + finish + rest.orEmpty()).flatMap { it.feature.joints }.toSet()

    /** Остальные суставы (поза, масштаб) — используются, если видны. */
    val optional: Set<Limb> = Limb.entries.toSet() - required

    /** Зона покоя: где начинается повторение. */
    val restZone: List<Cond> = rest ?: finish
}

/** Защёлки условий: момент последнего подтверждения каждого условия. */
private class Latches(private val conds: List<Cond>) {
    private val at = LongArray(conds.size) { NEVER }

    fun update(prev: Sample?, cur: Sample) {
        conds.forEachIndexed { i, c -> if (c.confirmed(prev, cur)) at[i] = cur.timeMs }
    }

    /** Все условия подтверждены не раньше [since] и не дальше [EngineTuning.LATCH_MS] назад. */
    fun all(now: Long, since: Long): Boolean {
        val from = max(now - EngineTuning.LATCH_MS, since)
        return at.all { it != NEVER && it >= from }
    }

    fun clear() = at.fill(NEVER)

    companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/** Один конечный автомат на все упражнения; отличается только [ExerciseConfig]. */
internal class PhaseCounter(private val cfg: ExerciseConfig) : RepCounter {
    private enum class Phase { NEED_START, WAIT_FAR, WAIT_RETURN }

    override var state: RepState = RepState()
        private set

    private val tracks = Side.entries.map { SideTrack(it) }
    private var active: SideTrack? = null
    private var challengerSince: Long? = null
    private var activeStaleSince: Long? = null

    private var count = 0
    private var phase = initialPhase()
    private var phaseSince = Latches.NEVER
    private var lastTrackedMs: Long? = null

    private var engaged = false
    private var violationSince: Long? = null

    private val restLatch = Latches(cfg.restZone)
    private val farLatch = Latches(cfg.far)
    private val finishLatch = Latches(cfg.finish)

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

    private fun initialPhase() = if (cfg.rest != null) Phase.NEED_START else Phase.WAIT_FAR

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        for (tr in tracks) tr.update(frame)

        val last = lastTrackedMs
        if (last != null && t - last > EngineTuning.LOST_RESET_MS) {
            resetMotion(t)
            engaged = false
            active = null
            lastTrackedMs = null
        }

        // Пустой кадр — человека нет совсем: устаревшие суставы его не заменяют
        // (они только для кратко пропавших отдельных суставов).
        val track = if (frame.points.isEmpty()) null else chooseSide(t)
        val s = track?.cur
        if (track == null || s == null) {
            val closest = tracks.maxBy { it.score(cfg.required) }
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
            if (cfg.enter(s)) {
                engaged = true
                violationSince = null
            }
        } else if (cfg.hold(s)) {
            violationSince = null
        } else {
            val since = violationSince ?: t.also { violationSince = it }
            if (t - since > EngineTuning.POSTURE_GRACE_MS) {
                engaged = false
                resetMotion(t)
            }
        }
        if (!engaged) {
            return emit(tracking = true, inPosition = false, hint = cfg.postureHint, depth = 0f, atStart = false)
        }

        val depth = smoothDepth(t, cfg.depth(s))
        val prev = track.prev
        restLatch.update(prev, s)
        farLatch.update(prev, s)
        finishLatch.update(prev, s)
        val inRest = cfg.restZone.all { it.holds(s[it.feature]) }

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
                }
            }
        }

        val hint = if (phase == Phase.NEED_START) cfg.startHint else moveHint
        val atStart = inRest && phase != Phase.WAIT_RETURN
        return emit(tracking = true, inPosition = true, hint = hint, depth = depth, atStart = atStart)
    }

    /**
     * «Липкая» сторона тела: меняется, только если другая сторона заметно увереннее
     * непрерывно [EngineTuning.SIDE_SWITCH_MS] или текущая потеряна дольше [EngineTuning.SIDE_LOST_MS].
     */
    private fun chooseSide(t: Long): SideTrack? {
        val need = cfg.required
        val cur = active
        if (cur != null) {
            val other = tracks.first { it !== cur }
            if (cur.allFresh(need)) {
                activeStaleSince = null
            } else if (activeStaleSince == null) {
                activeStaleSince = t
            }
            val staleSince = activeStaleSince
            val otherOk = other.allFresh(need)
            if (staleSince != null && t - staleSince > EngineTuning.SIDE_LOST_MS && otherOk) {
                switchTo(other)
            } else if (otherOk && other.score(need) > cur.score(need) + EngineTuning.SIDE_SWITCH_MARGIN * need.size) {
                val since = challengerSince ?: t.also { challengerSince = it }
                if (t - since >= EngineTuning.SIDE_SWITCH_MS) switchTo(other)
            } else {
                challengerSince = null
            }
        }
        if (active == null) {
            tracks.filter { it.allFresh(need) }.maxByOrNull { it.score(need) }?.let { switchTo(it) }
        }
        return active?.takeIf { it.available(need) }
    }

    private fun switchTo(track: SideTrack) {
        active = track
        challengerSince = null
        activeStaleSince = null
        // Фильтры другой стороны не смешиваем: защёлки и сглаживание начинаются заново.
        restLatch.clear()
        farLatch.clear()
        finishLatch.clear()
        depthEma = Float.NaN
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
        if (p == Phase.WAIT_FAR) {
            lastRestMs = t
            moveStartMs = null
        }
    }

    override fun reset() {
        count = 0
        resetMotion(Latches.NEVER)
        engaged = false
        active = null
        lastTrackedMs = null
        tracks.forEach { it.reset() }
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
        depthEma = Float.NaN
    }

    private fun emit(tracking: Boolean, inPosition: Boolean, hint: String, depth: Float, atStart: Boolean): RepState {
        state = RepState(count, tracking, inPosition, hint, depth, atStart)
        return state
    }
}
