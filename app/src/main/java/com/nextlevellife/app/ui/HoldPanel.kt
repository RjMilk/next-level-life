package com.nextlevellife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.pose.CameraPermissionGate
import com.nextlevellife.app.pose.CounterThresholds
import com.nextlevellife.app.pose.HoldSpeech
import com.nextlevellife.app.pose.PoseCameraView
import com.nextlevellife.app.pose.PoseFrame
import com.nextlevellife.app.pose.PoseLogWriter
import com.nextlevellife.app.pose.RepCounter
import com.nextlevellife.app.pose.RepSound
import com.nextlevellife.app.pose.RepState

// ---------------------------------------------------------------------------------------------
// Удержание позы (планка): чистая логика табло и голоса — проверяется тестами
// ---------------------------------------------------------------------------------------------

internal object HoldMath {
    /** Отрезок короче не засчитывается (как в движке планки). */
    val MIN_SEGMENT_MS: Long = CounterThresholds.Plank.MIN_SEGMENT_MS

    /** Потолок одной непрерывной серии (как в движке планки). */
    val MAX_SEGMENT_MS: Long = CounterThresholds.Plank.MAX_SEGMENT_MS

    /** Голос называет секунды текущего отрезка с этим шагом. */
    const val ANNOUNCE_STEP_MS = 10_000L

    /** Сколько держится подсказка «Меньше 10 секунд — не засчитано». */
    const val SHORT_NOTICE_MS = 3_000L

    const val CAPTION_PENDING = "засчитается с 10 секунд"
    const val STATUS_HOLD = "Держи планку"
    const val STATUS_BACK = "Вернись в планку"
    const val STATUS_LIE_DOWN = "Ляг боком к телефону"

    /**
     * Всего засчитано за подход: [baseMs] — засчитанное до пересоздания экрана (движок после
     * пересоздания новый и начинает с нуля), [engineHeldMs] — засчитанное новым движком.
     */
    fun total(baseMs: Long, engineHeldMs: Long): Long = baseMs.coerceAtLeast(0L) + engineHeldMs.coerceAtLeast(0L)

    /**
     * Табло по целым секундам (меняется раз в секунду — экран не перерисовывается на каждом кадре).
     * [shownMs] — что на табло (засчитанное + текущий ещё не засчитанный отрезок), [creditedMs] — засчитанное,
     * [pending] — идёт отрезок короче 10 с, [live] — время идёт прямо сейчас.
     */
    data class Board(val shownMs: Long, val creditedMs: Long, val pending: Boolean, val live: Boolean) {
        val tone: HoldTone
            get() = when {
                pending -> HoldTone.PENDING
                live -> HoldTone.LIVE
                else -> HoldTone.IDLE
            }
    }

    private fun wholeSeconds(ms: Long): Long = (ms.coerceAtLeast(0L) / 1000) * 1000

    fun board(baseMs: Long, s: RepState): Board = Board(
        shownMs = wholeSeconds(total(baseMs, s.shownHeldMs)),
        creditedMs = wholeSeconds(total(baseMs, s.heldMs)),
        pending = s.pendingMs > 0L,
        live = s.holding && s.currentSegmentMs < MAX_SEGMENT_MS,
    )

    /**
     * Подпись под табло: сразу после короткого обрыва — «Меньше 10 секунд — не засчитано»;
     * пока отрезок короче 10 с — «засчитается с 10 секунд»; иначе — «засчитано 1:20».
     */
    fun caption(board: Board, shortNotice: Boolean): String = when {
        shortNotice -> HoldSpeech.SHORT
        board.pending -> CAPTION_PENDING
        else -> "засчитано ${ProgressMath.formatHold(board.creditedMs)}"
    }

    /** Событие отрезка между двумя состояниями движка. */
    enum class SegmentEvent {
        /** Вошёл в удержание — «Старт». */
        START,

        /** Отрезок закончился и засчитан (был не короче 10 с) — «Стоп». */
        STOP,

        /** Отрезок оборвался раньше 10 с — «Стоп» и «Меньше 10 секунд — не засчитано». */
        STOP_SHORT,
    }

    fun segmentEvent(prev: RepState, cur: RepState): SegmentEvent? = when {
        !prev.inPosition && cur.inPosition -> SegmentEvent.START
        prev.inPosition && !cur.inPosition -> if (prev.pendingMs > 0L) SegmentEvent.STOP_SHORT else SegmentEvent.STOP
        else -> null
    }

    /** Отрезок перешёл через очередные 10 с: сколько секунд назвать голосом (10, 20, …); иначе null. */
    fun tenSecondMark(prevSegmentMs: Long, curSegmentMs: Long): Int? {
        if (curSegmentMs < ANNOUNCE_STEP_MS) return null
        val cur = curSegmentMs / ANNOUNCE_STEP_MS
        if (cur <= prevSegmentMs.coerceAtLeast(0L) / ANNOUNCE_STEP_MS) return null
        return (cur * (ANNOUNCE_STEP_MS / 1000)).toInt()
    }

    /** Отрезок только что упёрся в потолок (3 минуты). */
    fun maxReached(prevSegmentMs: Long, curSegmentMs: Long): Boolean =
        prevSegmentMs < MAX_SEGMENT_MS && curSegmentMs >= MAX_SEGMENT_MS

    /** Строка статуса над камерой и хорошая ли она (зелёная) или подсказка (жёлтая). */
    fun status(s: RepState): Pair<String, Boolean> = when {
        s.inPosition && s.currentSegmentMs >= MAX_SEGMENT_MS -> HoldSpeech.MAX to false
        s.inPosition && s.holding -> STATUS_HOLD to true
        s.inPosition -> s.hint.ifEmpty { STATUS_BACK } to false
        else -> s.hint.ifEmpty { STATUS_LIE_DOWN } to false
    }

    /** Итог записанного подхода: «1:20 засчитано · +6 мин 40 сек». */
    fun resultLine(heldMs: Long, earnedMs: Long): String =
        "${ProgressMath.formatHold(heldMs)} засчитано · +${formatShort(earnedMs)}"

    /** Цена и заработанное: «1 сек = 5 сек» и «6 мин 40 сек». */
    fun priceLine(ex: Exercise): String = "1 сек = ${formatShort(Exercises.holdRewardMs(ex, 1_000L))}"
}

// ---------------------------------------------------------------------------------------------
// Состояние удержания на экране
// ---------------------------------------------------------------------------------------------

/**
 * Счётчик удержания и то, что видно на экране. Создаётся один раз на экран упражнения (выше выбора
 * раскладки — смена ширины окна его не пересоздаёт). [baseMs] — засчитанное до пересоздания Activity.
 * reset() счётчика — только в [restart] (кнопка «Заново») и при уходе с экрана: если сбросить его,
 * когда человек уже лежит в планке, время не пойдёт, пока он не встанет.
 */
internal class HoldTracker(val counter: RepCounter, baseMs: Long) {
    var baseMs: Long = baseMs
        private set

    var board by mutableStateOf(HoldMath.board(baseMs, RepState()))
        private set
    var status by mutableStateOf(HoldMath.status(RepState()))
        private set
    var shortNotice by mutableStateOf(false)
        private set
    var inPosition by mutableStateOf(false)
        private set

    /** Растёт при каждом «Заново»: панель отмечает разрыв в записи позы. */
    var restarts by mutableIntStateOf(0)
        private set

    /** Последнее состояние движка (для событий и записи позы). */
    var last = RepState()
        private set

    private var shortAt = Long.MIN_VALUE

    val caption: String get() = HoldMath.caption(board, shortNotice)

    /** Обработать кадр: движок, табло, голос. Вызывается в главном потоке. */
    fun onFrame(frame: PoseFrame, sound: RepSound) {
        val prev = last
        val s = counter.onFrame(frame)
        last = s
        val t = frame.timeMs

        when (HoldMath.segmentEvent(prev, s)) {
            HoldMath.SegmentEvent.START -> sound.holdStart()
            HoldMath.SegmentEvent.STOP -> sound.holdStop()
            HoldMath.SegmentEvent.STOP_SHORT -> {
                sound.holdStop()
                sound.say(HoldSpeech.SHORT, interrupt = false)
                shortAt = t
            }
            null -> Unit
        }
        val mark = if (s.inPosition) HoldMath.tenSecondMark(prev.currentSegmentMs, s.currentSegmentMs) else null
        if (mark != null) sound.holdSeconds(mark)
        if (s.inPosition && HoldMath.maxReached(prev.currentSegmentMs, s.currentSegmentMs)) {
            sound.holdMax()
        } else if (s.hint.isNotEmpty() && s.currentSegmentMs < HoldMath.MAX_SEGMENT_MS && mark == null) {
            // Подсказки движка — голосом, с тем же ограничением частоты, что у повторений.
            sound.hint(s.hint)
        }

        board = HoldMath.board(baseMs, s)
        status = HoldMath.status(s)
        inPosition = s.inPosition
        tick(t)
    }

    /** Погасить подсказку о коротком отрезке по времени кадра (кадры идут и на паузе). */
    fun tick(timeMs: Long) {
        val show = shortAt != Long.MIN_VALUE && timeMs - shortAt in 0 until HoldMath.SHORT_NOTICE_MS
        if (show != shortNotice) shortNotice = show
    }

    /** «Заново»: подход с нуля, засчитанное (и до пересоздания тоже) пропадает. */
    fun restart() {
        counter.reset()
        baseMs = 0L
        last = RepState()
        shortAt = Long.MIN_VALUE
        shortNotice = false
        board = HoldMath.board(0L, RepState())
        status = HoldMath.status(RepState())
        inPosition = false
        restarts++
    }
}

// ---------------------------------------------------------------------------------------------
// Панель камеры в режиме удержания
// ---------------------------------------------------------------------------------------------

/**
 * Камера для удержания позы. В отличие от [CameraRepPanel] здесь нет «поиска → секунды в исходной
 * позе → reset()»: движок сам требует перехода в планку и сам решает, когда идёт время.
 * [tracker] = null — камера это упражнение считать не умеет.
 */
@Composable
internal fun CameraHoldPanel(
    exercise: Exercise,
    tracker: HoldTracker?,
    active: Boolean,          // false — превью идёт, время не считается (диалог, записано)
    sound: RepSound,
    frontCamera: Boolean,
    onFrontCameraChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showScore: Boolean = true, // false — табло показывает родитель (широкий экран)
) {
    val panel = modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black)
    if (tracker == null) {
        Box(panel.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Камера пока не умеет считать это упражнение",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val front = frontCamera

    // «Чёрный ящик»: каждый кадр подхода в файл (формат v3 с heldMs), как у повторений.
    val context = LocalContext.current
    val writer = remember(exercise.id, tracker, front) { PoseLogWriter(context, exercise.id, front) }
    DisposableEffect(writer) { onDispose { writer.close() } }
    val currentWriter by rememberUpdatedState(writer)
    val diag = remember(exercise.id) { FrameDiag(exercise.id) }

    val currentActive by rememberUpdatedState(active)
    val currentSound by rememberUpdatedState(sound)

    // Пауза или «Заново» — разрыв в записи позы (до первого кадра ничего не пишется).
    LaunchedEffect(writer, active, tracker.restarts) { writer.markReset() }

    val onFrame: (PoseFrame) -> Unit = remember(tracker) {
        { frame ->
            diag.onFrame(frame)
            if (currentActive) {
                tracker.onFrame(frame, currentSound)
                currentWriter.write(frame, if (tracker.last.inPosition) "COUNT" else "SEARCH", tracker.last)
            } else {
                tracker.tick(frame.timeMs)
            }
        }
    }

    CameraPermissionGate(panel) {
        Box(Modifier.fillMaxSize()) {
            PoseCameraView(
                modifier = Modifier.fillMaxSize(),
                frontCamera = front,
                showSkeleton = true,
                onFrame = onFrame,
            )
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    HoldStatusPlate(tracker, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    CameraSwitch { onFrontCameraChange(!front) }
                }
                DiagLine(diag, Modifier.padding(top = 6.dp))
                if (showScore) {
                    HoldScore(
                        tracker,
                        onCamera = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (!tracker.inPosition && tracker.board.shownMs == 0L) {
                    Text(
                        setupLine(exercise.id),
                        color = Color.White,
                        style = InstructionStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CameraScrim, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Табло удержания по состоянию [tracker]: на камере или в панели слева. */
@Composable
internal fun HoldScore(tracker: HoldTracker, onCamera: Boolean, modifier: Modifier = Modifier) {
    val board = tracker.board
    FittedHoldTimer(
        time = ProgressMath.formatHold(board.shownMs),
        caption = tracker.caption,
        tone = board.tone,
        captionWarn = tracker.shortNotice,
        onCamera = onCamera,
        modifier = modifier,
    )
}

@Composable
private fun HoldStatusPlate(tracker: HoldTracker, modifier: Modifier) {
    val (text, good) = tracker.status
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CameraScrim)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Подсказка целиком: не помещается крупно — шрифт меньше, без многоточия.
        StatusText(text, if (good) Good else Warn)
    }
}
