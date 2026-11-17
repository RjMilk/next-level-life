package com.nextlevellife.app.pose

/**
 * Кандидат вольной тренировки: [id] — упражнение (оно же профиль счёта [RepCounters.forExercise]),
 * [price] — цена повторения (или секунды удержания) в секундах: в споре двух счётчиков за один повтор
 * при прочих равных побеждает более дешёвое упражнение — защита от накрутки.
 */
class FreeCandidate(val id: String, val price: Int) {
    override fun toString() = id
}

/** Тайминги вольной тренировки. Пороги самих счётчиков здесь не меняются — только выбор между ними. */
internal object FreeTuning {
    /** Столько непрерывно в исходной позе — кандидат «готов» (устойчивая поза, а не один кадр). */
    const val READY_MS = 700L

    /** Одиночные кадры не в исходной позе готовность не обнуляют — только разрыв дольше (как у панели подхода). */
    const val READY_GAP_MS = 300L

    /** Вне позы упражнения дольше — для повторов снова нужна исходная поза (как сброс счётчика при пропаже). */
    const val ARM_LOSS_MS = EngineTuning.LOST_RESET_MS

    /** Повторы разных счётчиков ближе по времени — это один физический повтор: засчитывается одному. */
    const val CONFLICT_MS = 600L

    /** Текущее упражнение вне своей позы столько — можно сменить его по исходной позе другого (гистерезис). */
    const val SWITCH_OUT_MS = 700L

    /** Без повторов (и без роста удержания) столько — подход закрывается. */
    const val PAUSE_CLOSE_MS = 20_000L
}

/** Почему закрылся подход. */
enum class FreeCloseReason {
    /** Распознано другое упражнение. */
    CHANGE,

    /** Пауза без повторов не меньше [FreeTuning.PAUSE_CLOSE_MS]. */
    PAUSE,

    /** Отрезок удержания (планки) закончился: один отрезок — один подход, не дольше потолка счётчика. */
    HOLD_END,

    /** «Закончить». */
    FINISH,
}

/** Что засчитано на кадре. [startMs] — начало подхода (для длительности), [atMs] — время кадра. */
sealed class FreeEvent {
    abstract val atMs: Long

    /** [n] повторений упражнения [exerciseId]. */
    data class Reps(val exerciseId: String, val n: Int, val startMs: Long, override val atMs: Long) : FreeEvent()

    /** Ещё [ms] засчитанного удержания упражнения [exerciseId]. */
    data class Held(val exerciseId: String, val ms: Long, val startMs: Long, override val atMs: Long) : FreeEvent()

    /** Текущий подход закрыт (если он был). */
    data class Closed(val reason: FreeCloseReason, override val atMs: Long) : FreeEvent()
}

/**
 * Что классификатор знает после кадра.
 * [current] — распознанное упражнение (null — ещё ничего не распознано), [inPose] — человек в его позе; [setReps] / [setHeldMs] — засчитанное
 * в открытом подходе этим классификатором; [pendingHeldMs] — удержание текущего отрезка, ещё не дотянувшее
 * до минимума (у планки 10 с); [tracking] — человек виден хоть одному счётчику; [hint] — подсказка текущего
 * упражнения (или общая, пока ничего не распознано); [depth] — глубина текущего повтора; [holding] — время
 * удержания идёт сейчас; [events] — засчитанное на этом кадре, по порядку.
 */
data class FreeState(
    val current: String? = null,
    val inPose: Boolean = false,
    val setReps: Int = 0,
    val setHeldMs: Long = 0L,
    val pendingHeldMs: Long = 0L,
    val tracking: Boolean = false,
    val hint: String = "",
    val depth: Float = 0f,
    val holding: Boolean = false,
    val events: List<FreeEvent> = emptyList(),
)

/**
 * Открытый подход до пересоздания экрана: [exerciseId] — его упражнение, [lastCreditMs] — время последнего
 * засчитанного (на тех же часах, что и кадры). Новый классификатор продолжает его: пауза отсчитывается
 * от [lastCreditMs], а смена упражнения закрывает его, как обычно.
 */
data class FreeResume(val exerciseId: String, val lastCreditMs: Long)

/**
 * Вольная тренировка: камера сама определяет упражнение. Тот же кадр получают все кандидатные счётчики
 * (по экземпляру на упражнение, пороги — их собственные), а классификатор решает, чьё это упражнение.
 *
 * Правила:
 * - Кандидат «готов», если устойчиво ([FreeTuning.READY_MS]) в исходной позе; готовый хоть раз — «взведён»
 *   и остаётся таким, пока не выйдет из позы упражнения дольше [FreeTuning.ARM_LOSS_MS]. Повторы и удержание
 *   засчитываются только взведённому кандидату: без исходной позы ничего не считается.
 * - Один физический повтор — одному упражнению. Повторы разных счётчиков в пределах [FreeTuning.CONFLICT_MS] —
 *   спор: побеждает текущее упражнение, иначе более дешёвое ([winner]). Пока решается спор (другой взведённый
 *   счётчик посреди своего движения), повтор ждёт не дольше [FreeTuning.CONFLICT_MS].
 * - Текущее упражнение: до первого засчитанного — готовый кандидат с наивысшим приоритетом (порядок списка).
 *   Когда подход открыт, сменить упражнение может только устойчивая смена позы: другой кандидат готов, а текущее
 *   упражнение вне своей позы не меньше [FreeTuning.SWITCH_OUT_MS], — либо засчитанный повтор (удержание)
 *   другого взведённого кандидата. Одиночные кадры и дрожание упражнение не переключают.
 * - При смене упражнения с открытым подходом подход закрывается, а незавершённые циклы остальных счётчиков
 *   повторений сбрасываются (старые полуциклы не дозасчитываются). Счётчик удержания не сбрасывается:
 *   его время засчитывается только взведённому, а сброс сжёг бы «живой вход».
 * - Подход закрывается при смене упражнения, паузе без засчитанного ≥ [FreeTuning.PAUSE_CLOSE_MS], конце отрезка
 *   удержания (у планки отрезок не длиннее потолка счётчика — 3 минуты) и в [finish].
 *
 * Чистый Kotlin, кадры — из главного потока (как у [RepCounter]).
 */
class FreeSessionClassifier(
    candidates: List<FreeCandidate>,
    resume: FreeResume? = null,
    counterOf: (String) -> RepCounter? = RepCounters::forExercise,
) {
    private class Track(val cand: FreeCandidate, val counter: RepCounter, val order: Int) {
        val hold = counter.kind == CountKind.HOLD
        var state = RepState()
        var seenCount = 0
        var seenHeld = 0L
        var readySince = NEVER
        var readyBadSince = NEVER
        var armed = false
        var armedAt = NEVER
        var outSince = NEVER
        var wasHolding = false

        /**
         * В исходной позе прямо сейчас и уже дольше [FreeTuning.READY_MS] (с допуском на одиночные кадры).
         * Строго дольше: столько же длится допуск счётчика на нарушение позы, и поза, из которой человек
         * уже вышел, на его последнем кадре допуска «готовой» не считается.
         */
        fun ready(t: Long) = readySince != NEVER && readyBadSince == NEVER && t - readySince > FreeTuning.READY_MS

        /** В позе упражнения (счётчик ведёт его). */
        val inPose get() = state.tracking && state.inPosition

        /** Счётчик повторений посреди движения: его повтор может прийти чуть позже. */
        val midCycle get() = !hold && state.tracking && state.inPosition && !state.atStart

        override fun toString() = cand.id
    }

    private class Claim(val track: Track, val n: Int, val atMs: Long)

    private val tracks: List<Track> = candidates.mapIndexedNotNull { i, c -> counterOf(c.id)?.let { Track(c, it, i) } }

    /** Распознанное упражнение. */
    var current: String? = resume?.exerciseId?.takeIf { id -> tracks.any { it.cand.id == id } }
        private set

    /** Открыт подход: с последнего закрытия что-то засчитано. */
    private var setOpen = current != null
    private var setReps = 0
    private var setHeld = 0L
    private var setStartMs = NEVER
    private var lastCreditMs = if (current != null) resume?.lastCreditMs ?: NEVER else NEVER

    /** Последняя граница подходов (смена упражнения или закрытие): раньше неё подход не начинается. */
    private var boundaryMs = NEVER

    private val pending = ArrayList<Claim>()
    private var pendingSince = NEVER

    /** Последний решённый спор: повторы других счётчиков ближе к нему — тот же физический повтор. */
    private var resolvedAt = NEVER
    private var resolvedTrack: Track? = null

    var state: FreeState = FreeState(current = current)
        private set

    /** Упражнения, которые классификатор действительно считает (у кандидата есть счётчик). */
    val candidateIds: List<String> get() = tracks.map { it.cand.id }

    fun onFrame(frame: PoseFrame): FreeState {
        val t = frame.timeMs
        val events = ArrayList<FreeEvent>(0)
        val held = ArrayList<Pair<Track, Long>>(0)
        val holdEnded = ArrayList<Track>(0)

        for (tr in tracks) {
            val st = tr.counter.onFrame(frame)
            tr.state = st
            updateReadiness(tr, st, t)
            if (tr.hold) {
                val d = st.heldMs - tr.seenHeld
                tr.seenHeld = st.heldMs
                if (d > 0L && tr.armed) held += tr to d
                if (tr.wasHolding && !st.inPosition) holdEnded += tr
                tr.wasHolding = st.inPosition
            } else {
                val grown = st.count - tr.seenCount
                tr.seenCount = st.count
                if (grown > 0 && tr.armed && !sameRepAsResolved(tr, t)) {
                    if (pending.isEmpty()) pendingSince = t
                    pending += Claim(tr, grown, t)
                }
            }
        }

        if (pending.isNotEmpty()) {
            val claimers = pending.map { it.track }.toSet()
            val othersBusy = tracks.any { it !in claimers && it.armed && it.midCycle }
            if (!othersBusy || t - pendingSince >= FreeTuning.CONFLICT_MS) resolve(t, events)
        }

        for ((tr, d) in held) {
            if (tr.cand.id != current) {
                // Удержание взведённой планки — устойчивая поза: другое упражнение кончилось.
                pending.clear()
                pendingSince = NEVER
                switchTo(tr, t, events, resetOthers = true)
            }
            credit(tr, t, events, reps = 0, heldMs = d)
        }

        if (setOpen && holdEnded.any { it.cand.id == current }) closeSet(FreeCloseReason.HOLD_END, t, events)

        if (pending.isEmpty()) switchByPose(t, events)

        if (setOpen && lastCreditMs != NEVER && t - lastCreditMs >= FreeTuning.PAUSE_CLOSE_MS) {
            closeSet(FreeCloseReason.PAUSE, t, events)
        }

        state = makeState(events)
        return state
    }

    /** «Закончить»: решить отложенный спор и закрыть подход. События — как у [onFrame]. */
    fun finish(timeMs: Long): List<FreeEvent> {
        val events = ArrayList<FreeEvent>()
        if (pending.isNotEmpty()) resolve(timeMs, events)
        if (setOpen) closeSet(FreeCloseReason.FINISH, timeMs, events)
        state = makeState(events)
        return events
    }

    /** Сбросить все счётчики (уход с экрана). */
    fun reset() {
        tracks.forEach { it.counter.reset() }
    }

    private fun updateReadiness(tr: Track, st: RepState, t: Long) {
        if (st.tracking && st.atStart) {
            tr.readyBadSince = NEVER
            if (tr.readySince == NEVER) tr.readySince = t
        } else if (tr.readySince != NEVER) {
            if (tr.readyBadSince == NEVER) tr.readyBadSince = t
            if (t - tr.readyBadSince > FreeTuning.READY_GAP_MS) {
                tr.readySince = NEVER
                tr.readyBadSince = NEVER
            }
        }
        if (!tr.armed && tr.ready(t)) {
            tr.armed = true
            tr.armedAt = tr.readySince
        }
        if (st.tracking && st.inPosition) {
            tr.outSince = NEVER
        } else {
            if (tr.outSince == NEVER) tr.outSince = t
            if (t - tr.outSince > FreeTuning.ARM_LOSS_MS) tr.armed = false
        }
    }

    /** Повтор другого счётчика рядом с уже решённым спором — тот же физический повтор. */
    private fun sameRepAsResolved(tr: Track, t: Long): Boolean =
        resolvedAt != NEVER && resolvedTrack !== tr && t - resolvedAt <= FreeTuning.CONFLICT_MS

    private fun resolve(t: Long, events: MutableList<FreeEvent>) {
        val claimers = pending.map { it.track }.distinct().sortedBy { it.order }
        val win = claimers.first { it.cand === winner(claimers.map { c -> c.cand }, current) }
        val n = pending.filter { it.track === win }.sumOf { it.n }
        pending.clear()
        pendingSince = NEVER
        resolvedAt = t
        resolvedTrack = win
        if (win.cand.id != current) switchTo(win, t, events, resetOthers = true)
        credit(win, t, events, reps = n, heldMs = 0L)
    }

    private fun credit(tr: Track, t: Long, events: MutableList<FreeEvent>, reps: Int, heldMs: Long) {
        if (!setOpen || setStartMs == NEVER) {
            var start = if (tr.armedAt != NEVER) tr.armedAt else t
            if (boundaryMs != NEVER) start = maxOf(start, boundaryMs)
            // Удержание засчитывается задним числом: подход не короче засчитанного времени.
            if (heldMs > 0L) start = minOf(start, t - heldMs)
            setStartMs = minOf(start, t)
        }
        setOpen = true
        lastCreditMs = t
        if (reps > 0) {
            setReps += reps
            events += FreeEvent.Reps(tr.cand.id, reps, setStartMs, t)
        }
        if (heldMs > 0L) {
            setHeld += heldMs
            events += FreeEvent.Held(tr.cand.id, heldMs, setStartMs, t)
        }
    }

    /** Смена упражнения по устойчивой позе (без засчитанного повтора). */
    private fun switchByPose(t: Long, events: MutableList<FreeEvent>) {
        val best = tracks.filter { it.ready(t) && it.cand.id != current }.minByOrNull { it.order } ?: return
        if (!setOpen) {
            // До первого засчитанного текущее — лишь догадка: смена ничего не закрывает и счётчики не трогает.
            // Пока текущее в своей позе (например, посреди первого повтора), его меняет только более приоритетное.
            val cur = tracks.firstOrNull { it.cand.id == current }
            if (cur == null || !cur.inPose || best.order < cur.order) switchTo(best, t, events, resetOthers = false)
            return
        }
        val cur = tracks.firstOrNull { it.cand.id == current }
        if (cur == null || (cur.outSince != NEVER && t - cur.outSince >= FreeTuning.SWITCH_OUT_MS)) {
            switchTo(best, t, events, resetOthers = true)
        }
    }

    private fun switchTo(tr: Track, t: Long, events: MutableList<FreeEvent>, resetOthers: Boolean) {
        if (setOpen) closeSet(FreeCloseReason.CHANGE, t, events)
        current = tr.cand.id
        boundaryMs = t
        if (resetOthers) {
            for (o in tracks) {
                if (o === tr || o.hold) continue
                o.counter.reset()
                o.seenCount = 0
            }
        }
    }

    private fun closeSet(reason: FreeCloseReason, t: Long, events: MutableList<FreeEvent>) {
        events += FreeEvent.Closed(reason, t)
        setOpen = false
        setReps = 0
        setHeld = 0L
        setStartMs = NEVER
        boundaryMs = t
    }

    private fun makeState(events: List<FreeEvent>): FreeState {
        val cur = tracks.firstOrNull { it.cand.id == current }
        val tracking = tracks.any { it.state.tracking }
        val inPose = cur != null && cur.inPose
        val st = cur?.state
        val hint = when {
            // Подсказки упражнения («Ниже», «Таз выше») — только пока человек в его позе: вне её он,
            // может быть, уже переходит к другому упражнению, и «Встань прямо» было бы неверно.
            inPose -> st?.hint.orEmpty()
            // Никого не видно: подсказка о кадре от счётчика повторений (у планки она про позу).
            !tracking -> tracks.firstOrNull { !it.hold }?.state?.hint.orEmpty()
            else -> ""
        }
        return FreeState(
            current = current,
            inPose = inPose,
            setReps = setReps,
            setHeldMs = setHeld,
            pendingHeldMs = if (cur != null && cur.hold) cur.state.pendingMs else 0L,
            tracking = tracking,
            hint = hint,
            depth = if (st != null && st.tracking) st.depth.coerceIn(0f, 1f) else 0f,
            holding = cur != null && cur.hold && cur.state.holding,
            events = events,
        )
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /**
         * Кому засчитать спорный повтор: текущему упражнению, иначе более дешёвому, при равной цене —
         * раньше в списке кандидатов. [claimers] — в порядке списка кандидатов.
         */
        fun winner(claimers: List<FreeCandidate>, current: String?): FreeCandidate =
            claimers.firstOrNull { it.id == current } ?: claimers.minBy { it.price }
    }
}
