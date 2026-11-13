package com.nextlevellife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.pose.BodyLocator
import com.nextlevellife.app.pose.CameraPermissionGate
import com.nextlevellife.app.pose.ExerciseConfigs
import com.nextlevellife.app.pose.Located
import com.nextlevellife.app.pose.PoseCameraView
import com.nextlevellife.app.pose.PoseFrame
import com.nextlevellife.app.pose.PoseLogWriter
import com.nextlevellife.app.pose.RepCounter
import com.nextlevellife.app.pose.RepCounters
import com.nextlevellife.app.pose.RepSound
import com.nextlevellife.app.pose.RepState
import com.nextlevellife.app.pose.Signals

/**
 * Тайминги фаз панели. Ими же пользуется эталон панели в тестах (PanelSim в PoseLogReplayTest),
 * поэтому меняйте только здесь.
 */
internal object PanelTiming {
    /** Сколько держать исходное положение (tracking && atStart), прежде чем начнётся счёт. */
    const val HOLD_MS = 1_000L

    /** Разрыв удержания (кадры без tracking/atStart) не длиннее этого удержание не обнуляет. */
    const val HOLD_GAP_MS = 300L

    /** Человека не видно во время счёта дольше — голосом «Не вижу тебя» (счёт продолжается). */
    const val LOST_HINT_MS = 1_500L

    /** Человека не видно во время счёта дольше — назад к поиску и новому удержанию. */
    const val LOST_SEARCH_MS = 3_000L
}

/** Подсказка в поиске, когда человек в позе упражнения, но посреди движения. */
internal const val HINT_RETURN_TO_START = "Вернись в исходное положение"

/** Подсказка, когда человек пропал из кадра во время счёта. */
internal const val HINT_LOST = "Не вижу тебя"

/** С этой глубины амплитуда считается полной (индикатор зеленеет). */
private const val FULL_DEPTH = 0.98f

internal val Warn = Color(0xFFFFD166)
private val PanelShape = RoundedCornerShape(24.dp)

private enum class Phase {
    /** Человек не найден или не в исходной позе. */
    SEARCH,

    /** В исходной позе, идёт секунда удержания. */
    READY,

    /** Счёт идёт. */
    COUNT,
}

/**
 * Всё, что меняется на каждом кадре. Отдельный объект со своими состояниями:
 * их читает только слой показаний, поэтому превью камеры не перекомпонуется.
 */
private class RepTracker(val counter: RepCounter) {
    var phase by mutableStateOf(Phase.SEARCH)
    var hint by mutableStateOf("")
    var depth by mutableFloatStateOf(0f)
    var tracking by mutableStateOf(false)
    var holdProgress by mutableFloatStateOf(0f)

    // Служебное, на экран не выводится.
    var holdSince = -1L
    var holdBadSince = -1L
    var lostSince = -1L
    var counterSeen = 0
    var announced = 0

    /** Что вернул счётчик на последнем кадре (для записи позы; reset() его не трогает). */
    var lastState = RepState()

    fun toSearch() {
        phase = Phase.SEARCH
        holdSince = -1L
        holdBadSince = -1L
        lostSince = -1L
        holdProgress = 0f
    }
}

/** Фаза панели в записи позы: SEARCH / HOLD / COUNT. */
private val Phase.logName: String
    get() = when (this) {
        Phase.SEARCH -> "SEARCH"
        Phase.READY -> "HOLD"
        Phase.COUNT -> "COUNT"
    }

private const val DIAG_UPDATE_MS = 250L

/**
 * Строка диагностики «18 к/с · локоть 142°»: частота обработанных кадров за последнюю секунду
 * и главный сигнал упражнения (сырой, тот же выбор стороны тела, что у счётчика).
 * Текст меняется не чаще [DIAG_UPDATE_MS] и читается только в [DiagLine].
 */
internal class FrameDiag(exerciseId: String) {
    var text by mutableStateOf("")

    private val config = ExerciseConfigs.byId[exerciseId]
    private val signal: Pair<String, (Signals) -> Float>? = when (exerciseId) {
        "pushups", "pullups", "row" -> "локоть" to { s: Signals -> s.elbow }
        "squats", "thruster" -> "колено" to { s: Signals -> s.knee }
        "abs", "swings", "deadlift" -> "наклон" to { s: Signals -> s.torsoTilt }
        else -> null
    }
    private val times = LongArray(128)
    private var head = 0
    private var size = 0
    private var lastUpdate = Long.MIN_VALUE

    fun onFrame(frame: PoseFrame) {
        val t = frame.timeMs
        times[(head + size) % times.size] = t
        if (size < times.size) size++ else head = (head + 1) % times.size
        while (size > 1 && t - times[head] > 1_000L) {
            head = (head + 1) % times.size
            size--
        }
        if (lastUpdate != Long.MIN_VALUE && t - lastUpdate in 0 until DIAG_UPDATE_MS) return
        lastUpdate = t

        val span = t - times[head]
        val fps = if (size > 1 && span > 0) Math.round((size - 1) * 1000f / span) else 0
        val sig = signal
        text = if (sig == null || config == null) {
            "$fps к/с"
        } else {
            val located = BodyLocator.locate(frame, config.required, config.optional)
            val v = if (located is Located.Found) sig.second(Signals.measure(located.body)) else Float.NaN
            val value = if (v.isNaN()) "—" else "${Math.round(v)}°"
            "$fps к/с · ${sig.first} $value"
        }
    }
}

@Composable
fun CameraRepPanel(
    exercise: Exercise,
    count: Int,              // текущий счёт (хранит родитель)
    target: Int?,            // цель подхода или null
    sessionKey: Any,         // смена ключа = новый подход: счётчик с нуля, снова ждём исходную позу
    active: Boolean,         // false — превью идёт, счёт не ведётся
    sound: RepSound,
    // Выбор камеры хранит родитель (rememberSaveable до выбора раскладки): после раскрытия Fold
    // панель стоит в другом месте дерева, и её собственное сохранённое состояние потерялось бы.
    frontCamera: Boolean,
    onFrontCameraChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showCount: Boolean = true,                     // false — счётчик показывает родитель (широкий экран)
    onCountingChange: ((Boolean) -> Unit)? = null, // идёт ли счёт: для цвета счётчика у родителя
    onRep: () -> Unit,       // засчитано одно повторение
) {
    val front = frontCamera
    val counter = remember(exercise.id, sessionKey) { RepCounters.forExercise(exercise.id) }
    val panel = modifier.clip(PanelShape).background(Color.Black)

    if (counter == null) {
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

    val tracker = remember(counter) { RepTracker(counter) }

    val currentOnCounting by rememberUpdatedState(onCountingChange)
    LaunchedEffect(tracker) {
        snapshotFlow { tracker.phase == Phase.COUNT }.collect { currentOnCounting?.invoke(it) }
    }

    // «Чёрный ящик»: каждый кадр подхода в файл, чтобы проиграть его на компьютере.
    val context = LocalContext.current
    val writer = remember(exercise.id, sessionKey, front) { PoseLogWriter(context, exercise.id, front, target) }
    DisposableEffect(writer) { onDispose { writer.close() } }
    val currentWriter by rememberUpdatedState(writer)
    val diag = remember(exercise.id) { FrameDiag(exercise.id) }

    val currentCount by rememberUpdatedState(count)
    val currentTarget by rememberUpdatedState(target)
    val currentActive by rememberUpdatedState(active)
    val currentSound by rememberUpdatedState(sound)
    val currentOnRep by rememberUpdatedState(onRep)

    // Пауза или другая камера — снова ждём исходную позу.
    LaunchedEffect(tracker, active, front) {
        tracker.toSearch()
        writer.markReset()
    }

    // Один объект на всё время подхода: PoseCameraView не перекомпонуется от новых лямбд.
    val onFrame: (PoseFrame) -> Unit = remember(tracker) {
        { frame ->
            diag.onFrame(frame)
            if (currentActive) {
                handleFrame(
                    tracker = tracker,
                    frame = frame,
                    parentCount = currentCount,
                    target = currentTarget,
                    sound = currentSound,
                    onRep = currentOnRep,
                )
                currentWriter.write(frame, tracker.phase.logName, tracker.lastState)
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
            Readout(
                tracker = tracker,
                diag = diag,
                exerciseId = exercise.id,
                count = count,
                target = target,
                showCount = showCount,
                onSwitchCamera = { onFrontCameraChange(!front) },
            )
        }
    }
}

/** Логика фаз на один кадр. Вызывается в главном потоке. */
private fun handleFrame(
    tracker: RepTracker,
    frame: PoseFrame,
    parentCount: Int,
    target: Int?,
    sound: RepSound,
    onRep: () -> Unit,
) {
    // Цель достигнута: ждём, пока родитель сменит sessionKey, ничего больше не считаем.
    if (tracker.phase == Phase.COUNT && target != null && maxOf(parentCount, tracker.announced) >= target) return

    val state = tracker.counter.onFrame(frame)
    tracker.lastState = state
    tracker.depth = state.depth.coerceIn(0f, 1f)
    tracker.tracking = state.tracking
    tracker.hint = state.hint
    val t = frame.timeMs

    when (tracker.phase) {
        Phase.SEARCH, Phase.READY -> {
            // Удержание — именно исходная точка движения, а не любая поза упражнения:
            // иначе секунда наберётся посреди повторений и reset() сотрёт начатое повторение.
            val atStart = state.tracking && state.atStart
            if (atStart) {
                tracker.holdBadSince = -1L
                if (tracker.holdSince < 0) tracker.holdSince = t
            } else if (tracker.holdSince >= 0) {
                // Одиночные плохие кадры удержание не обнуляют — только разрыв дольше HOLD_GAP_MS.
                if (tracker.holdBadSince < 0) tracker.holdBadSince = t
                if (t - tracker.holdBadSince > PanelTiming.HOLD_GAP_MS) tracker.toSearch()
            }

            if (tracker.holdSince >= 0) {
                val held = t - tracker.holdSince
                tracker.phase = Phase.READY
                tracker.holdProgress = (held.toFloat() / PanelTiming.HOLD_MS).coerceIn(0f, 1f)
                if (atStart && held >= PanelTiming.HOLD_MS) {
                    // Всё, что счётчик насчитал, пока человек устраивался, не считается.
                    tracker.counter.reset()
                    tracker.counterSeen = tracker.counter.state.count
                    tracker.announced = parentCount
                    tracker.lostSince = -1L
                    tracker.phase = Phase.COUNT
                    sound.ready()
                }
            } else if (state.tracking && state.inPosition) {
                // В позе, но посреди движения: счёт начнётся из исходного положения.
                tracker.hint = HINT_RETURN_TO_START
                sound.hint(HINT_RETURN_TO_START)
            } else if (state.tracking && state.hint.isNotEmpty()) {
                // Человек виден, но не в позе — подскажем голосом, он далеко от экрана.
                sound.hint(state.hint)
            }
        }

        Phase.COUNT -> {
            // Короткую потерю переживаем в счёте: фазой повторения распоряжается сам счётчик.
            var lostLong = false
            if (!state.tracking) {
                if (tracker.lostSince < 0) tracker.lostSince = t
                val lost = t - tracker.lostSince
                if (lost > PanelTiming.LOST_SEARCH_MS) {
                    tracker.toSearch()
                    sound.hint(HINT_LOST)
                    return
                }
                lostLong = lost > PanelTiming.LOST_HINT_MS
            } else {
                tracker.lostSince = -1L
            }

            val grown = state.count - tracker.counterSeen
            tracker.counterSeen = state.count
            if (grown > 0) {
                // Родитель обновит count только при перекомпоновке — берём максимум с уже объявленным.
                var n = maxOf(parentCount, tracker.announced)
                repeat(grown) {
                    if (target != null && n >= target) return@repeat
                    n++
                    onRep()
                    sound.rep(n)
                }
                tracker.announced = n
            } else if (lostLong) {
                sound.hint(HINT_LOST)
            } else if (state.hint.isNotEmpty()) {
                sound.hint(state.hint)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Слой показаний: читается с 2 метров
// ---------------------------------------------------------------------------------------------

@Composable
private fun Readout(
    tracker: RepTracker,
    diag: FrameDiag,
    exerciseId: String,
    count: Int,
    target: Int?,
    showCount: Boolean,
    onSwitchCamera: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val phase = tracker.phase

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusPlate(tracker, count, target, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CameraSwitch(onSwitchCamera)
            }
            DiagLine(diag, Modifier.padding(top = 6.dp))

            // Счётчик сам подбирает размер под эту область: 112 / 128 / 160 sp по ширине окна,
            // при нехватке высоты — до 96 sp, потом цель уходит вправо от числа.
            if (showCount) {
                FittedRepCounter(
                    count = count,
                    target = target,
                    counting = phase == Phase.COUNT,
                    onCamera = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Справа место под индикатор амплитуды.
                        .padding(top = 8.dp, bottom = 8.dp, end = 36.dp),
                )
            } else {
                // Счётчик в панели слева: здесь только человек в кадре.
                Spacer(Modifier.weight(1f))
            }

            if (phase == Phase.SEARCH) {
                Text(
                    setupLine(exerciseId),
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

        DepthBar(
            tracker,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(20.dp)
                .fillMaxHeight(0.5f),
        )
    }
}

@Composable
private fun StatusPlate(tracker: RepTracker, count: Int, target: Int?, modifier: Modifier) {
    val hint = tracker.hint
    val (text, color) = when (tracker.phase) {
        Phase.SEARCH -> (hint.ifEmpty { "Встань в исходную позу" }) to Warn
        Phase.READY -> "Держи позу" to Good
        Phase.COUNT -> when {
            target != null && count >= target -> "Готово" to Good
            hint.isNotEmpty() -> hint to Warn
            else -> "Считаю" to Good
        }
    }
    val showHold = tracker.phase == Phase.READY
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CameraScrim)
            .drawBehind {
                // Полоска удержания позы: видно, что секунда идёт и скоро начнётся счёт.
                if (showHold) {
                    val h = 4.dp.toPx()
                    drawRect(
                        Good,
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width * tracker.holdProgress, h),
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Мелкая приглушённая строка диагностики; от новых кадров перекомпонуется только она сама.
 * До первого кадра — заглушка той же высоты, чтобы счётчик под ней не менял размер.
 */
@Composable
internal fun DiagLine(diag: FrameDiag, modifier: Modifier) {
    val text = diag.text.ifEmpty { "— к/с" }
    Text(
        text,
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium.merge(Tabular),
        maxLines = 1,
        modifier = modifier
            .background(CameraScrim, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DepthBar(tracker: RepTracker, modifier: Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.width / 2f)
        drawRoundRect(CameraScrim, cornerRadius = r)
        // Глубину читаем при рисовании: каждый кадр перерисовывает только эту полосу.
        val d = if (tracker.tracking) tracker.depth else 0f
        if (d > 0f) {
            val h = size.height * d
            drawRoundRect(
                if (d >= FULL_DEPTH) Good else Accent,
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
                cornerRadius = r,
            )
        }
        drawRoundRect(Color.White.copy(alpha = 0.35f), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
internal fun CameraSwitch(onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CameraScrim)
            .clickable(onClickLabel = "Переключить камеру", onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Камера", color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

internal fun setupLine(exerciseId: String): String = when (exerciseId) {
    "pushups" -> "Поставь телефон сбоку, в 2 метрах: видно всё тело от головы до стоп"
    "squats" -> "Встань боком или в пол-оборота, видно всё тело"
    "abs" -> "Ляг боком к телефону: видно плечи, таз и колени"
    "pullups" -> "Отодвинь телефон: в кадре турник и всё тело"
    "thruster", "sumo_pull", "row" -> "Встань в пол-оборота к телефону, видно всё тело"
    "swings", "deadlift" -> "Встань боком к телефону, видно всё тело"
    "plank" -> "Поставь телефон на пол сбоку, в 2 метрах. Встань на колени в кадре, потом прими планку"
    else -> "Поставь телефон в 2 метрах: видно всё тело"
}
