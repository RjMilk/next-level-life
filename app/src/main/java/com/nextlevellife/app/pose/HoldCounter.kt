package com.nextlevellife.app.pose

/**
 * Описание упражнения-удержания для [HoldCounter].
 *
 * @param required суставы одной стороны, без которых позу не оценить.
 * @param postureHint подсказка, когда человека не видно целиком или он не в позе вообще.
 * @param fault проверка позы по замеру: null — поза правильная, иначе короткая подсказка,
 *   что исправить. strict = true — строгие пороги входа, false — мягкие пороги удержания (гистерезис).
 *   NaN в признаке — нарушение (все суставы при проверке свежие, NaN значит вырожденную геометрию).
 * @param enterMs столько непрерывно правильных кадров (строгие пороги) нужно для входа в позу.
 * @param paidGapMs интервал между двумя соседними хорошими кадрами оплачивается, только если он не длиннее.
 * @param breakMs без хорошего кадра дольше этого — отрезок удержания завершается.
 * @param minSegmentMs отрезок короче не засчитывается вовсе.
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
)

/**
 * Счётчик удержания позы: время идёт, только пока поза правильная.
 *
 * Разбор суставов общий с [PhaseCounter]: обе стороны тела ([SideSelector]), устаревание суставов,
 * уверенность ≥ [JointTuning.MIN_CONFIDENCE]. «Хороший» кадр — человек в кадре, все [HoldConfig.required]
 * суставы выбранной стороны свежие (не устаревшие) и поза проходит проверку.
 *
 * Автомат: ВНЕ ПОЗЫ → (хороший кадр по строгим порогам) ВХОД → (непрерывно [HoldConfig.enterMs])
 * УДЕРЖАНИЕ. Время — сумма интервалов между двумя ПОДРЯД идущими хорошими кадрами, если интервал
 * не длиннее [HoldConfig.paidGapMs]; время входа засчитывается в отрезок задним числом. Плохой или
 * пустой кадр сразу останавливает накопление; провал до [HoldConfig.breakMs] отрезок сохраняет
 * (сам провал не оплачивается), дольше — отрезок завершается, и для продолжения нужен новый вход.
 * Отрезки короче [HoldConfig.minSegmentMs] в итог ([RepState.heldMs]) не входят.
 */
internal class HoldCounter(private val cfg: HoldConfig) : RepCounter {
    private enum class Phase { OUT, ENTERING, HOLDING }

    override val kind: CountKind get() = CountKind.HOLD

    override var state: RepState = RepState()
        private set

    private val sides = SideSelector(cfg.required)

    private var phase = Phase.OUT
    private var enterSince = 0L

    /** Время последнего хорошего кадра (null — не было с начала входа). */
    private var lastGoodMs: Long? = null

    /** Предыдущий обработанный кадр был хорошим. */
    private var prevGood = false

    /** Оплаченное время: при ВХОДЕ — накопленное за вход, при УДЕРЖАНИИ — текущий отрезок. */
    private var segmentMs = 0L

    /** Сумма завершённых засчитанных отрезков. */
    private var confirmedMs = 0L

    private var lastTrackedMs: Long? = null
    private var lastHint = cfg.postureHint

    override fun onFrame(frame: PoseFrame): RepState {
        val t = frame.timeMs
        sides.update(frame)

        val lastTracked = lastTrackedMs
        if (lastTracked != null && t - lastTracked > EngineTuning.LOST_RESET_MS) {
            sides.drop()
            lastTrackedMs = null
        }

        // Провал дольше breakMs — отрезок завершён (до оценки кадра: дальше нужен строгий вход).
        val lastGood = lastGoodMs
        if (phase == Phase.HOLDING && lastGood != null && t - lastGood > cfg.breakMs) closeSegment()

        // Пустой кадр — человека нет совсем: устаревшие суставы его не заменяют.
        val track = if (frame.points.isEmpty()) null else sides.choose(t)
        val s = track?.cur
        val tracking = track != null && s != null
        var good = false
        val hint: String
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
                // Часть суставов взята из прошлых кадров: позу не оцениваем и время не платим.
                hint = lastHint
            } else {
                val fault = cfg.fault(s, phase != Phase.HOLDING)
                good = fault == null
                hint = fault ?: ""
            }
        }

        when (phase) {
            Phase.OUT -> if (good) {
                phase = Phase.ENTERING
                enterSince = t
                segmentMs = 0L
            }

            Phase.ENTERING -> if (!good) {
                // Вход должен быть непрерывным: любой плохой кадр — заново.
                phase = Phase.OUT
                segmentMs = 0L
            } else {
                pay(t)
                if (t - enterSince >= cfg.enterMs) phase = Phase.HOLDING
            }

            Phase.HOLDING -> if (good) pay(t)
        }
        prevGood = good
        if (good) lastGoodMs = t
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

    /** Оплатить интервал от предыдущего кадра, если оба кадра хорошие и разрыв не длиннее paidGapMs. */
    private fun pay(t: Long) {
        val last = lastGoodMs ?: return
        val dt = t - last
        if (prevGood && dt > 0 && dt <= cfg.paidGapMs) segmentMs += dt
    }

    private fun closeSegment() {
        if (segmentMs >= cfg.minSegmentMs) confirmedMs += segmentMs
        segmentMs = 0L
        phase = Phase.OUT
        lastGoodMs = null
        prevGood = false
    }

    override fun reset() {
        phase = Phase.OUT
        enterSince = 0L
        lastGoodMs = null
        prevGood = false
        segmentMs = 0L
        confirmedMs = 0L
        lastTrackedMs = null
        lastHint = cfg.postureHint
        sides.reset()
        state = RepState()
    }
}
