package com.nextlevellife.app.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Тип события экрана (phone_events.type). STATE — состояние, снятое при подключении службы. */
enum class PhoneEventType { SCREEN_ON, SCREEN_OFF, USER_PRESENT, STATE }

/**
 * Событие экрана телефона. [utcMs] — доверенное время. [interactive] — экран включён
 * (PowerManager.isInteractive), [locked] — экран блокировки показан (KeyguardManager.isKeyguardLocked);
 * null — не известно. [bootCount] — номер загрузки устройства.
 */
data class PhoneEvent(
    val utcMs: Long,
    val type: PhoneEventType,
    val interactive: Boolean? = null,
    val locked: Boolean? = null,
    val bootCount: Int? = null,
    val source: String = "",
)

/** Пропуск наблюдения: служба не работала, события не записывались. */
data class ObservationGap(val startMs: Long, val endMs: Long)

/** Окно ночного режима из настроек, минуты от полуночи: с [startMin] (включительно) до [endMin] (исключительно). */
data class NightMode(val startMin: Int, val endMin: Int)

/** OK — перерыв подтверждён событиями; PARTIAL — обрезан краем окна; UNKNOWN — данных нет или они с пропуском. */
enum class NightStatus { OK, PARTIAL, UNKNOWN }

/**
 * Ночной перерыв в использовании телефона для ночи с днём пробуждения [wakeDay] (учётные сутки).
 * [startMs] — последнее использование перед перерывом, [endMs] — первое после; null — перерыв не найден.
 * [interruptionMs] и [interruptionCount] — короткие ночные обращения, объединённые в перерыв.
 * [phoneFree] — «ночь без телефона». [nightStartMin]/[nightEndMin] — окно ночного режима, действовавшее
 * при расчёте (null — ночной режим выключен). [final] — ночь окончательная (после 12:00 дня пробуждения).
 *
 * Это оценка по экрану этого телефона, а не сон: телефон не отличает сон от чтения книги.
 */
data class SleepNight(
    val wakeDay: Long,
    val status: NightStatus,
    val startMs: Long?,
    val endMs: Long?,
    val interruptionMs: Long,
    val interruptionCount: Int,
    val phoneFree: Boolean,
    val nightStartMin: Int?,
    val nightEndMin: Int?,
    val zoneId: String,
    val ruleVersion: Int = SleepMath.RULE_VERSION,
    val final: Boolean = true,
) {
    /** Длительность перерыва: конец − начало − время коротких обращений; null — перерыва нет. */
    val durationMs: Long?
        get() {
            val s = startMs ?: return null
            val e = endMs ?: return null
            return (e - s - interruptionMs).coerceAtLeast(0L)
        }
}

/**
 * Чистая логика ночного перерыва, без Android: её проверяют юнит-тесты.
 *
 * Использование — экран включён и разблокирован: USER_PRESENT, или SCREEN_ON при снятой блокировке
 * (телефон без экрана блокировки или ещё не успел заблокироваться). Экран, включённый уведомлением
 * поверх экрана блокировки, — не использование. Конец использования — SCREEN_OFF.
 *
 * Алгоритм v1 для дня пробуждения D:
 * 1. Окно поиска — с 20:00 дня D−1 до 12:00 дня D в учётном поясе (не дальше «сейчас»).
 * 2. Время окна делится на использование, отсутствие использования и «неизвестно» (пропуск наблюдения,
 *    нет начального состояния).
 * 3. Соседние интервалы без использования объединяются через короткие обращения: каждое ≤ 2 мин,
 *    вместе ≤ 5 мин на кандидата.
 * 4. Из кандидатов, обе границы которых подтверждены событиями использования внутри окна, выбирается самый
 *    длинный (длительность за вычетом обращений) от 3 до 14 ч; при равенстве — более поздний.
 * 5. Если кандидат, обрезанный краем окна или проходящий через «неизвестно», мог бы оказаться длиннее
 *    выбранного, — PARTIAL (только край окна) или UNKNOWN (пропуск наблюдения). Нет событий в окне — UNKNOWN.
 * 6. «Ночь без телефона» — OK, перерыв ≥ 6 ч и ни одного использования (и ни одного «неизвестно»)
 *    в окне ночного режима этой ночи.
 */
object SleepMath {
    const val RULE_VERSION = 1

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS

    /** Окно поиска: с 20:00 дня D−1 до 12:00 дня D. */
    const val WINDOW_START_MIN = 20 * 60
    const val WINDOW_END_MIN = 12 * 60

    /** Короткое обращение — не дольше 2 минут; все обращения кандидата — не дольше 5 минут. */
    const val MAX_INTERRUPTION_MS = 2 * MINUTE_MS
    const val MAX_TOTAL_INTERRUPTIONS_MS = 5 * MINUTE_MS

    const val MIN_BREAK_MS = 3 * HOUR_MS
    const val MAX_BREAK_MS = 14 * HOUR_MS

    /** «Ночь без телефона» — перерыв не короче 6 часов. */
    const val PHONE_FREE_MIN_MS = 6 * HOUR_MS

    /** Пропуск между последним событием и подключением службы длиннее этого — пропуск наблюдения. */
    const val GAP_THRESHOLD_MS = 2 * MINUTE_MS

    /** Сырые события и пропуски хранятся столько дней; итоги ночей — всегда. */
    const val RAW_RETENTION_DAYS = 90

    // ---------- Окна ----------

    fun windowStartMs(wakeDay: Long, zone: ZoneId): Long = atMinute(wakeDay - 1, WINDOW_START_MIN, zone)

    fun windowEndMs(wakeDay: Long, zone: ZoneId): Long = atMinute(wakeDay, WINDOW_END_MIN, zone)

    /** Ночь окончательная, когда наступило 12:00 дня пробуждения. */
    fun isFinal(wakeDay: Long, nowMs: Long, zone: ZoneId): Boolean = nowMs >= windowEndMs(wakeDay, zone)

    /** Последний день пробуждения, ночь которого уже окончательная на момент [nowMs]. */
    fun lastFinalWakeDay(nowMs: Long, zone: ZoneId): Long {
        val today = dayOf(nowMs, zone)
        return if (isFinal(today, nowMs, zone)) today else today - 1
    }

    /** Первая ночь, окно которой ещё не кончилось к моменту первого события [firstEventMs]. */
    fun firstWakeDay(firstEventMs: Long, zone: ZoneId): Long {
        val day = dayOf(firstEventMs, zone)
        return if (firstEventMs < windowEndMs(day, zone)) day else day + 1
    }

    /**
     * Окно ночного режима, относящееся к ночи с днём пробуждения [wakeDay]: [начало, конец) в мс.
     * Через полночь — с вечера D−1 до утра D; без перехода — днём D, если конец не позже 12:00,
     * или вечером D−1, если начало не раньше 20:00. Начало == конец — ночного режима нет (null).
     */
    fun nightModeInterval(wakeDay: Long, zone: ZoneId, mode: NightMode): Pair<Long, Long>? {
        val s = mode.startMin
        val e = mode.endMin
        return when {
            s == e -> null
            s > e -> atMinute(wakeDay - 1, s, zone) to atMinute(wakeDay, e, zone)
            s >= WINDOW_START_MIN -> atMinute(wakeDay - 1, s, zone) to atMinute(wakeDay - 1, e, zone)
            else -> atMinute(wakeDay, s, zone) to atMinute(wakeDay, e, zone)
        }
    }

    // ---------- Состояния ----------

    enum class Phase { USE, IDLE, UNKNOWN }

    data class Segment(val startMs: Long, val endMs: Long, val phase: Phase) {
        val durationMs: Long get() = endMs - startMs
    }

    /** Состояние после события [e] (предыдущее — [prev]). */
    fun phaseAfter(prev: Phase, e: PhoneEvent): Phase = when (e.type) {
        PhoneEventType.USER_PRESENT -> Phase.USE
        PhoneEventType.SCREEN_OFF -> Phase.IDLE
        // Экран включился без блокировки — пользуются; поверх экрана блокировки (уведомление) — нет.
        PhoneEventType.SCREEN_ON -> if (e.locked == false) Phase.USE else if (prev == Phase.USE) Phase.USE else Phase.IDLE
        PhoneEventType.STATE -> when {
            e.interactive == false -> Phase.IDLE
            e.interactive == true && e.locked == false -> Phase.USE
            e.interactive == true && e.locked == true -> Phase.IDLE
            else -> Phase.UNKNOWN
        }
    }

    /**
     * Шкала состояний на [fromMs, toMs). Начальное состояние — по [seed] и событиям раньше [fromMs]
     * (нет ни одного — «неизвестно»). Время внутри пропусков [gaps] — «неизвестно».
     * Соседние отрезки с одинаковым состоянием сливаются.
     */
    fun timeline(seed: PhoneEvent?, events: List<PhoneEvent>, gaps: List<ObservationGap>, fromMs: Long, toMs: Long): List<Segment> {
        if (toMs <= fromMs) return emptyList()
        val sorted = (listOfNotNull(seed) + events).sortedBy { it.utcMs }
        var phase = Phase.UNKNOWN
        var cur = fromMs
        val raw = ArrayList<Segment>()
        for (e in sorted) {
            if (e.utcMs >= toMs) break
            if (e.utcMs > cur) {
                raw += Segment(cur, e.utcMs, phase)
                cur = e.utcMs
            }
            phase = phaseAfter(phase, e)
        }
        raw += Segment(cur, toMs, phase)
        return merge(overlayGaps(raw, gaps))
    }

    private fun overlayGaps(segments: List<Segment>, gaps: List<ObservationGap>): List<Segment> {
        if (gaps.isEmpty()) return segments
        val out = ArrayList<Segment>()
        for (seg in segments) {
            // Точки разреза: границы пропусков внутри отрезка.
            val cuts = sortedSetOf(seg.startMs, seg.endMs)
            for (g in gaps) {
                if (g.startMs in (seg.startMs + 1) until seg.endMs) cuts += g.startMs
                if (g.endMs in (seg.startMs + 1) until seg.endMs) cuts += g.endMs
            }
            val points = cuts.toList()
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val inGap = gaps.any { it.startMs <= a && it.endMs >= b }
                out += Segment(a, b, if (inGap) Phase.UNKNOWN else seg.phase)
            }
        }
        return out
    }

    private fun merge(segments: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segments) {
            if (s.endMs <= s.startMs) continue
            val last = out.lastOrNull()
            if (last != null && last.phase == s.phase && last.endMs == s.startMs) out[out.lastIndex] = last.copy(endMs = s.endMs)
            else out += s
        }
        return out
    }

    // ---------- Кандидаты ----------

    /**
     * Кандидат на ночной перерыв. [startBounded]/[endBounded] — граница подтверждена событием использования
     * внутри окна; [touchesEdge] — упирается в край окна; [hasUnknown] — проходит через «неизвестно».
     */
    data class Candidate(
        val startMs: Long,
        val endMs: Long,
        val interruptionMs: Long,
        val interruptionCount: Int,
        val startBounded: Boolean,
        val endBounded: Boolean,
        val touchesEdge: Boolean,
        val hasUnknown: Boolean,
    ) {
        val durationMs: Long get() = endMs - startMs - interruptionMs
        val confirmed: Boolean get() = startBounded && endBounded && !hasUnknown
    }

    private sealed interface Block {
        val startMs: Long
        val endMs: Long
    }
    private data class FreeBlock(override val startMs: Long, override val endMs: Long, val hasUnknown: Boolean) : Block
    private data class UseBlock(override val startMs: Long, override val endMs: Long) : Block
    private data class BreakBlock(override val startMs: Long, override val endMs: Long) : Block

    /**
     * Кандидаты по шкале [segments]. [unknownIsFree] = false — «неизвестно» рвёт кандидатов (то, что точно
     * известно); true — считается свободным (верхняя оценка: насколько длинным мог быть перерыв).
     * Для каждого свободного отрезка — самый длинный кандидат, который с него начинается.
     */
    fun candidates(segments: List<Segment>, unknownIsFree: Boolean): List<Candidate> {
        if (segments.isEmpty()) return emptyList()
        val windowStart = segments.first().startMs
        val windowEnd = segments.last().endMs
        val blocks = ArrayList<Block>()
        for (s in segments) {
            val block: Block = when {
                s.phase == Phase.USE -> UseBlock(s.startMs, s.endMs)
                s.phase == Phase.IDLE -> FreeBlock(s.startMs, s.endMs, hasUnknown = false)
                unknownIsFree -> FreeBlock(s.startMs, s.endMs, hasUnknown = true)
                else -> BreakBlock(s.startMs, s.endMs)
            }
            val last = blocks.lastOrNull()
            if (last is FreeBlock && block is FreeBlock && last.endMs == block.startMs) {
                blocks[blocks.lastIndex] = FreeBlock(last.startMs, block.endMs, last.hasUnknown || block.hasUnknown)
            } else {
                blocks += block
            }
        }
        val out = ArrayList<Candidate>()
        for (i in blocks.indices) {
            val first = blocks[i] as? FreeBlock ?: continue
            var j = i
            var total = 0L
            var count = 0
            var unknown = first.hasUnknown
            while (j + 2 < blocks.size) {
                val use = blocks[j + 1] as? UseBlock ?: break
                val next = blocks[j + 2] as? FreeBlock ?: break
                val d = use.endMs - use.startMs
                if (d > MAX_INTERRUPTION_MS || total + d > MAX_TOTAL_INTERRUPTIONS_MS) break
                total += d
                count++
                unknown = unknown || next.hasUnknown
                j += 2
            }
            val last = blocks[j] as FreeBlock
            out += Candidate(
                startMs = first.startMs,
                endMs = last.endMs,
                interruptionMs = total,
                interruptionCount = count,
                startBounded = blocks.getOrNull(i - 1) is UseBlock,
                endBounded = blocks.getOrNull(j + 1) is UseBlock,
                touchesEdge = first.startMs == windowStart || last.endMs == windowEnd,
                hasUnknown = unknown,
            )
        }
        return out
    }

    private val byLengthThenLater = compareBy<Candidate>({ it.durationMs }, { it.startMs })

    // ---------- Ночь ----------

    /**
     * Ночной перерыв для дня пробуждения [wakeDay] в учётном поясе [zone] на момент [nowMs].
     * [seed] — последнее событие раньше всех [events]; [events] — события начиная с начала окна
     * (или раньше — с начала окна ночного режима, если оно раньше). [gaps] — пропуски наблюдения.
     * [nightMode] = null — ночной режим выключен, значка не бывает.
     */
    fun computeNight(
        wakeDay: Long,
        zone: ZoneId,
        seed: PhoneEvent?,
        events: List<PhoneEvent>,
        gaps: List<ObservationGap>,
        nowMs: Long,
        nightMode: NightMode?,
    ): SleepNight {
        val windowStart = windowStartMs(wakeDay, zone)
        val windowEnd = windowEndMs(wakeDay, zone)
        val effectiveEnd = minOf(windowEnd, nowMs)
        val final = nowMs >= windowEnd
        fun result(status: NightStatus, c: Candidate?, phoneFree: Boolean = false) = SleepNight(
            wakeDay = wakeDay,
            status = status,
            startMs = c?.startMs,
            endMs = c?.endMs,
            interruptionMs = c?.interruptionMs ?: 0L,
            interruptionCount = c?.interruptionCount ?: 0,
            phoneFree = phoneFree,
            nightStartMin = nightMode?.startMin,
            nightEndMin = nightMode?.endMin,
            zoneId = zone.id,
            final = final,
        )

        // Границы подтверждаются только событиями внутри окна: без них — «нет данных», а не ноль.
        if (effectiveEnd <= windowStart || events.none { it.utcMs in windowStart until effectiveEnd }) {
            return result(NightStatus.UNKNOWN, null)
        }
        val segments = timeline(seed, events, gaps, windowStart, effectiveEnd)

        val best = candidates(segments, unknownIsFree = false)
            .filter { it.confirmed && it.durationMs in MIN_BREAK_MS..MAX_BREAK_MS }
            .maxWithOrNull(byLengthThenLater)
        // Что могло бы оказаться длиннее, если бы было известно, что за краем окна или в пропуске.
        val threat = candidates(segments, unknownIsFree = true)
            .filter { (it.hasUnknown || it.touchesEdge) && it.durationMs >= MIN_BREAK_MS }
            .filter { best == null || it.durationMs > best.durationMs }
        if (threat.isNotEmpty()) {
            if (threat.any { it.hasUnknown }) return result(NightStatus.UNKNOWN, null)
            return result(NightStatus.PARTIAL, threat.maxWith(byLengthThenLater))
        }
        if (best == null) return result(NightStatus.UNKNOWN, null)

        val phoneFree = nightMode != null && best.durationMs >= PHONE_FREE_MIN_MS &&
            isNightModeClean(wakeDay, zone, nightMode, seed, events, gaps, nowMs)
        return result(NightStatus.OK, best, phoneFree)
    }

    /** В окне ночного режима ни одного использования и ни одного пропуска наблюдения, и окно уже прошло. */
    private fun isNightModeClean(
        wakeDay: Long, zone: ZoneId, mode: NightMode,
        seed: PhoneEvent?, events: List<PhoneEvent>, gaps: List<ObservationGap>, nowMs: Long,
    ): Boolean {
        val (from, to) = nightModeInterval(wakeDay, zone, mode) ?: return false
        if (to > nowMs) return false
        val segments = timeline(seed, events, gaps, from, to)
        return segments.isNotEmpty() && segments.all { it.phase == Phase.IDLE }
    }

    // ---------- Пропуск наблюдения при подключении службы ----------

    /**
     * Пропуск наблюдения при подключении службы в момент [connectMs] (загрузка [connectBoot]):
     * от последнего известного события [last] до подключения, если прошло больше [GAP_THRESHOLD_MS].
     * Исключение — телефон перезагружался, а перед этим экран был выключен: до первой разблокировки
     * после загрузки пользоваться телефоном нельзя, служба подключается сразу после неё.
     * Первое подключение (событий ещё нет) — не пропуск: данные просто начинаются отсюда.
     */
    fun connectGap(last: PhoneEvent?, connectMs: Long, connectBoot: Int?): ObservationGap? {
        if (last == null) return null
        if (connectMs - last.utcMs <= GAP_THRESHOLD_MS) return null
        val rebooted = last.bootCount != null && connectBoot != null && last.bootCount != connectBoot
        if (rebooted && phaseAfter(Phase.UNKNOWN, last) == Phase.IDLE) return null
        return ObservationGap(last.utcMs, connectMs)
    }

    // ---------- Мелочи ----------

    fun dayOf(utcMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDate().toEpochDay()

    private fun atMinute(day: Long, minuteOfDay: Int, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()
}
