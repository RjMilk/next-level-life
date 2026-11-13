package com.nextlevellife.app.pose

import kotlin.math.min

/**
 * Описание упражнения-удержания для [HoldCounter].
 *
 * @param required суставы одной стороны, без которых позу не оценить.
 * @param postureHint подсказка, когда человека не видно целиком или он не в позе вообще.
 * @param fault проверка позы по замеру: null — поза правильная, иначе короткая подсказка,
 *   что исправить. strict = true — строгие пороги входа, false — мягкие пороги удержания (гистерезис).
 *   NaN в признаке — нарушение (все суставы при проверке свежие, NaN значит вырожденную геометрию).
 * @param enterMs столько непрерывно правильных кадров (строгие пороги) нужно для входа в позу.
 * @param paidGapMs интервал между двумя хорошими кадрами оплачивается, только если он не длиннее
 *   (и между ними не было достоверно плохой позы); на входе разрыв длиннее начинает вход заново.
 * @param breakMs без хорошего кадра дольше этого — отрезок удержания завершается.
 * @param minSegmentMs отрезок короче не засчитывается вовсе.
 * @param maxSegmentMs один отрезок оплачивается не больше этого.
 * @param liveWindowMs вход возможен, только если не раньше чем за столько до него человек был виден не в позе.
 * @param liveOutMs «не в позе» засчитывается, если такие кадры идут непрерывно хотя бы столько.
 * @param startOverHint подсказка, когда поза правильная, но живого входа не было.
 * @param maxHint подсказка, когда отрезок упёрся в [maxSegmentMs].
 */
internal class HoldConfig(
    val id: String,
    val required: Set<Limb>,
    val postureHint: String,
    val fault: (s: Sample, strict: Boolean) -> String?,
    val enterMs: Long,
    val paidGapMs: Long,
    val breakMs: Long,
    val minSegmentMs: Long,
    val maxSegmentMs: Long,
    val liveWindowMs: Long,
    val liveOutMs: Long,
    val startOverHint: String,
    val maxHint: String,
)

/**
 * Счётчик удержания позы: время идёт, только пока поза правильная.
 *
 * Разбор суставов общий с [PhaseCounter]: обе стороны тела ([SideSelector]), устаревание суставов,
 * уверенность ≥ [JointTuning.MIN_CONFIDENCE], точки за краем кадра не берутся. Кадр бывает трёх видов:
 * - ХОРОШИЙ — все [HoldConfig.required] суставы выбранной стороны свежие и поза проходит проверку;
 * - ПЛОХОЙ (поза достоверно неправильная) — суставы свежие, проверка не пройдена;
 * - НЕИЗВЕСТНЫЙ — человека нет или нужных свежих суставов не хватает: позу оценить нельзя.
 * Кадр с меткой времени не позже уже обработанной отбрасывается целиком (время не идёт назад).
 *
 * Сторона тела: если текущая сторона в кадре не свежая, а другая свежая целиком — переключаемся сразу.
 * Интервал «последний хороший кадр старой стороны → кадр новой» не оплачивается (стороны не смешиваются),
 * кроме случая, когда новая сторона в том же предыдущем кадре тоже была видна целиком и в правильной
 * позе: тогда оба конца интервала замерены по одной (новой) стороне.
 *
 * Живой вход (защита от фотографии): войти можно, только если не раньше чем за [HoldConfig.liveWindowMs]
 * человек был виден НЕ в позе (плохие кадры подряд не короче [HoldConfig.liveOutMs]) и с тех пор
 * не пропадал дольше [EngineTuning.LOST_RESET_MS]. Вход в удержание этот «допуск» расходует: после
 * завершения отрезка нужен новый переход из «не в позе» (плохие кадры провала его как раз дают,
 * а пропажа человека или неизвестные кадры — нет).
 *
 * Автомат: ВНЕ ПОЗЫ → (хороший кадр по строгим порогам + живой вход) ВХОД → (непрерывно
 * [HoldConfig.enterMs], плохой кадр или разрыв дольше [HoldConfig.paidGapMs] — вход заново) УДЕРЖАНИЕ.
 * Время — сумма интервалов между соседними хорошими кадрами, если интервал не длиннее
 * [HoldConfig.paidGapMs], между ними только неизвестные кадры и сторона не менялась; время входа
 * засчитывается в отрезок задним числом. Плохой кадр сразу останавливает накопление; провал до
 * [HoldConfig.breakMs] отрезок сохраняет (сам провал не оплачивается), дольше — отрезок завершается.
 * Отрезки короче [HoldConfig.minSegmentMs] в итог ([RepState.heldMs]) не входят; один отрезок
 * оплачивается не больше [HoldConfig.maxSegmentMs] (поза держится, но время стоит).
 */
internal class HoldCounter(private val cfg: HoldConfig) : RepCounter {
    private enum class Phase { OUT, ENTERING, HOLDING }

    private enum class Verdict { GOOD, BAD, UNKNOWN }

    override val kind: CountKind get() = CountKind.HOLD

    override var state: RepState = RepState()
        private set

    /** Смена стороны рвёт оплачиваемую пару кадров (кроме пары кадров самой новой стороны, см. [onFrame]). */
    private val sides = SideSelector(cfg.required, lostSwitchMs = 0L) { switched = true }

    /** В этом кадре сменилась сторона. */
    private var switched = false

    private var phase = Phase.OUT
    private var enterSince = 0L

    /** Самая поздняя обработанная метка времени (null — кадров ещё не было). */
    private var lastFrameMs: Long? = null

    /** Время последнего хорошего кадра (null — не было с начала входа). */
    private var lastGoodMs: Long? = null

    /** После последнего хорошего кадра не было плохого кадра и смены стороны: следующий хороший оплачивает интервал. */
    private var pairOpen = false

    /** Оплаченное время: при ВХОДЕ — накопленное за вход, при УДЕРЖАНИИ — текущий отрезок. */
    private var segmentMs = 0L

    /** Сумма завершённых засчитанных отрезков. */
    private var confirmedMs = 0L

    /** Начало текущей серии плохих кадров (null — серии нет). */
    private var outSince: Long? = null

    /** Последний момент, когда человек был виден не в позе достаточно долго (допуск на живой вход). */
    private var liveAt: Long? = null

    private var lastTrackedMs: Long? = null
    private var lastHint = cfg.postureHint

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        val prevFrame = lastFrameMs
        // Время не идёт назад: повтор или откат метки не должен ни платить, ни менять состояние.
        if (prevFrame != null && t <= prevFrame) return state
        lastFrameMs = t
        sides.update(frame)

        val lastTracked = lastTrackedMs
        if (lastTracked != null && t - lastTracked > EngineTuning.LOST_RESET_MS) {
            // Человек пропал надолго — вернуться мог уже кто-то другой (или фотография).
            sides.drop()
            lastTrackedMs = null
            outSince = null
            liveAt = null
        }

        // Провал дольше breakMs — отрезок завершён (до оценки кадра: дальше нужен строгий вход).
        val lastGood = lastGoodMs
        if (phase == Phase.HOLDING && lastGood != null && t - lastGood > cfg.breakMs) closeSegment()

        // Пустой кадр — человека нет совсем: устаревшие суставы его не заменяют.
        switched = false
        val track = if (frame.points.isEmpty()) null else sides.choose(t)
        val s = track?.cur
        val tracking = track != null && s != null
        if (switched) {
            // Пара «старая сторона → новая» не оплачивается: стороны не смешиваем. Но если новая сторона
            // и в предыдущем (последнем хорошем) кадре была видна целиком и в правильной позе — это её
            // собственная пара кадров, и интервал между ними честно подтверждён одной стороной.
            val p = track?.prev
            pairOpen = pairOpen && p != null && p.timeMs == lastGoodMs && p.allFresh(cfg.required) &&
                cfg.fault(p, phase != Phase.HOLDING) == null
        }
        var verdict = Verdict.UNKNOWN
        var hint: String
        if (track == null || s == null) {
            hint = if (frame.points.isEmpty()) {
                cfg.postureHint
            } else {
                BodyLocator.missingHint(sides.closest().missing(cfg.required))
                    .takeUnless { it == BodyLocator.HINT_NOT_IN_FRAME } ?: cfg.postureHint
            }
        } else {
            lastTrackedMs = t
            if (!track.allFresh(cfg.required)) {
                // Часть суставов взята из прошлых кадров: позу не оцениваем (неизвестный кадр).
                hint = lastHint
            } else {
                val fault = cfg.fault(s, phase != Phase.HOLDING)
                verdict = if (fault == null) Verdict.GOOD else Verdict.BAD
                hint = fault ?: ""
            }
        }

        // Живой вход: человек виден не в позе непрерывно хотя бы liveOutMs.
        when (verdict) {
            Verdict.BAD -> {
                val since = outSince ?: t.also { outSince = it }
                if (t - since >= cfg.liveOutMs) liveAt = t
            }
            Verdict.GOOD -> outSince = null
            Verdict.UNKNOWN -> Unit
        }

        val good = verdict == Verdict.GOOD
        when (phase) {
            Phase.OUT -> if (good && !startEntering(t)) hint = cfg.startOverHint

            Phase.ENTERING -> when (verdict) {
                // Вход должен быть непрерывным: достоверно плохой кадр — заново.
                Verdict.BAD -> {
                    phase = Phase.OUT
                    segmentMs = 0L
                }
                Verdict.GOOD -> {
                    val prevGood = lastGoodMs
                    if (prevGood == null || t - prevGood > cfg.paidGapMs) {
                        // Разрыв дольше оплачиваемого — непрерывность не подтверждена, вход заново.
                        phase = Phase.OUT
                        segmentMs = 0L
                        if (!startEntering(t)) hint = cfg.startOverHint
                    } else {
                        pay(t)
                        if (t - enterSince >= cfg.enterMs) {
                            phase = Phase.HOLDING
                            // Допуск израсходован: следующий отрезок — только после нового перехода.
                            liveAt = null
                        }
                    }
                }
                Verdict.UNKNOWN -> Unit
            }

            Phase.HOLDING -> if (good) {
                pay(t)
                if (segmentMs >= cfg.maxSegmentMs) hint = cfg.maxHint
            }
        }
        when (verdict) {
            Verdict.GOOD -> {
                lastGoodMs = t
                pairOpen = true
            }
            Verdict.BAD -> pairOpen = false
            Verdict.UNKNOWN -> Unit
        }
        lastHint = hint

        val holding = phase == Phase.HOLDING
        val counted = holding && segmentMs >= cfg.minSegmentMs
        state = RepState(
            count = 0,
            tracking = tracking,
            inPosition = holding,
            hint = hint,
            depth = 0f,
            atStart = holding,
            heldMs = confirmedMs + if (counted) segmentMs else 0L,
            currentSegmentMs = if (holding) segmentMs else 0L,
            pendingMs = if (holding && !counted) segmentMs else 0L,
            holding = holding && good,
        )
        return state
    }

    /** Начать вход с кадра [t], если был живой переход; false — входа нет (фаза остаётся ВНЕ ПОЗЫ). */
    private fun startEntering(t: Long): Boolean {
        val live = liveAt
        if (live == null || t - live > cfg.liveWindowMs) return false
        phase = Phase.ENTERING
        enterSince = t
        segmentMs = 0L
        return true
    }

    /**
     * Оплатить интервал от предыдущего хорошего кадра: между ними не было плохого кадра и смены
     * стороны ([pairOpen]), разрыв не длиннее paidGapMs. Отрезок не растёт выше maxSegmentMs.
     */
    private fun pay(t: Long) {
        val last = lastGoodMs ?: return
        val dt = t - last
        if (pairOpen && dt <= cfg.paidGapMs) segmentMs = min(segmentMs + dt, cfg.maxSegmentMs)
    }

    private fun closeSegment() {
        if (segmentMs >= cfg.minSegmentMs) confirmedMs += segmentMs
        segmentMs = 0L
        phase = Phase.OUT
        lastGoodMs = null
        pairOpen = false
    }

    override fun reset() {
        phase = Phase.OUT
        enterSince = 0L
        lastFrameMs = null
        lastGoodMs = null
        pairOpen = false
        switched = false
        segmentMs = 0L
        confirmedMs = 0L
        outSince = null
        liveAt = null
        lastTrackedMs = null
        lastHint = cfg.postureHint
        sides.reset()
        state = RepState()
    }
}
