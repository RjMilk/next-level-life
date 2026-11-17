package com.nextlevellife.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.FreeSet
import com.nextlevellife.app.data.FreeSetStatus
import com.nextlevellife.app.data.FreeWorkoutLog
import com.nextlevellife.app.data.FreeWorkoutMath
import com.nextlevellife.app.data.SetInput
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.WorkoutResult
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.pose.CameraPermissionGate
import com.nextlevellife.app.pose.FreeCloseReason
import com.nextlevellife.app.pose.FreeEvent
import com.nextlevellife.app.pose.FreeSessionClassifier
import com.nextlevellife.app.pose.FreeState
import com.nextlevellife.app.pose.PoseCameraView
import com.nextlevellife.app.pose.PoseFrame
import com.nextlevellife.app.pose.PoseLogWriter
import com.nextlevellife.app.pose.RepSound
import com.nextlevellife.app.pose.RepState
import com.nextlevellife.app.pose.rememberRepSound
import com.nextlevellife.app.stats.StatsText
import kotlinx.coroutines.delay
import java.util.UUID

private const val TAG = "FreeWorkout"

// ---------------------------------------------------------------------------------------------
// Тексты вольной тренировки (чистая логика, покрыта тестами)
// ---------------------------------------------------------------------------------------------

internal object FreeText {
    const val TITLE = "Вольная тренировка"
    const val CARD_SUBTITLE = "Камера сама определит упражнение"
    const val START = "Начни любое упражнение"
    const val SETUP =
        "Телефон в 2–3 м, в кадре всё тело. Упражнения определяются сами: отжимания, приседания, пресс, подтягивания, планка"

    /** Плашка, пока упражнение не распознано, а человек виден. */
    const val FIND_START = "Замри в исходной позе"
    const val COUNTING = "Считаю"
    const val HOLDING = "Держи планку"

    /** Засчитанное текущим упражнением: «12» или у планки «0:45». */
    fun amount(exerciseId: String, reps: Int, heldMs: Long): String =
        if (Exercises.byIdOrNull(exerciseId)?.hold == true) ProgressMath.formatHold(heldMs) else "$reps"

    /** Крупная строка: «Сейчас: Приседания · 12»; до распознавания — «Начни любое упражнение». */
    fun now(exerciseId: String?, reps: Int, heldMs: Long): String {
        val ex = exerciseId?.let { Exercises.byIdOrNull(it) } ?: return START
        return "Сейчас: ${ex.name} · ${amount(ex.id, reps, heldMs)}"
    }

    /** Что засчитано в подходе: «12 повт.» или «0:45». */
    fun setAmount(s: FreeSet): String = if (s.hold) ProgressMath.formatHold(s.heldMs) else "${s.reps} повт."

    /** Строка подхода: «Приседания — 12 повт. · +8 мин». */
    fun setLine(s: FreeSet, earnedMs: Long): String =
        "${s.exercise?.name ?: s.exerciseId} — ${setAmount(s)} · +${formatShort(earnedMs)}"

    /** Заработано за подход: записанное — как начислено, остальное — по обычной цене. */
    fun setEarned(s: FreeSet): Long = if (s.status == FreeSetStatus.RECORDED) s.earnedMs else FreeWorkoutMath.rewardMs(s)

    /** Итог для диалога: заголовок и текст. */
    fun result(log: FreeWorkoutLog, strength: String?): Pair<String, String> {
        val lines = log.sets.filter { it.status == FreeSetStatus.RECORDED && it.hasWork }.map { setLine(it, it.earnedMs) }
        val text = (lines + "Всего: +${formatShort(log.recordedMs)}" + listOfNotNull(strength?.replaceFirstChar { it.uppercase() }))
            .joinToString("\n")
        return "Тренировка записана" to text
    }
}

// ---------------------------------------------------------------------------------------------
// Состояние экрана
// ---------------------------------------------------------------------------------------------

/** Сохраняемое состояние тренировки: переживает поворот и пересоздание Activity. */
internal class FreeRunState {
    var log by mutableStateOf(FreeWorkoutLog())
    var startedAt by mutableLongStateOf(SystemClock.elapsedRealtime())
    var finished by mutableStateOf(false)
    var finishedElapsed by mutableLongStateOf(0L)
    var resultTitle by mutableStateOf("")
    var resultText by mutableStateOf("")
    var retry by mutableStateOf(false)
    var confirmQuit by mutableStateOf(false)

    /** Сила: суммарный прирост за записи тренировки и последнее «сегодня» (−1 — не известно). */
    var strengthGain by mutableIntStateOf(0)
    var strengthToday by mutableIntStateOf(-1)

    companion object {
        val Saver = listSaver<FreeRunState, Any>(
            save = {
                listOf(
                    it.log.encode(), it.startedAt, it.finished, it.finishedElapsed, it.resultTitle, it.resultText,
                    it.retry, it.confirmQuit, it.strengthGain, it.strengthToday,
                )
            },
            restore = { v ->
                FreeRunState().apply {
                    log = FreeWorkoutLog.decode(v[0] as String)
                    startedAt = v[1] as Long
                    finished = v[2] as Boolean
                    finishedElapsed = v[3] as Long
                    resultTitle = v[4] as String
                    resultText = v[5] as String
                    retry = v[6] as Boolean
                    confirmQuit = v[7] as Boolean
                    strengthGain = v[8] as Int
                    strengthToday = v[9] as Int
                }
            },
        )
    }
}

/** То, что меняется на каждом кадре: читает только слой показаний. */
private class FreeLive {
    var current by mutableStateOf<String?>(null)
    var inPose by mutableStateOf(false)
    var hint by mutableStateOf("")
    var tracking by mutableStateOf(false)
    var holding by mutableStateOf(false)
    var pendingHeldMs by mutableLongStateOf(0L)

    fun update(st: FreeState) {
        current = st.current
        inPose = st.inPose
        hint = st.hint
        tracking = st.tracking
        holding = st.holding
        pendingHeldMs = st.pendingHeldMs
    }
}

// ---------------------------------------------------------------------------------------------
// Экран
// ---------------------------------------------------------------------------------------------

/**
 * Вольная тренировка: камера сама определяет упражнение ([FreeSessionClassifier]) среди упражнений со своим весом
 * ([FreeWorkoutMath.exercises]) и считает повторения (у планки — время). Ручного ввода нет. Каждый подход
 * закрывается сменой упражнения, паузой 20 с или «Закончить» и сразу записывается обычным подходом своего
 * упражнения по обычной цене ([Workouts.recordSets] / [Workouts.recordHold], ключ `free:<сессия>:<номер>`) —
 * повтор записи не платит дважды. Подходы, сессия и начатый подход переживают поворот; классификатор после
 * пересоздания новый и продолжает открытый подход ([FreeWorkoutLog.resume]).
 */
@Composable
fun FreeWorkoutScreen(onDone: () -> Unit) {
    val s = rememberSaveable(saver = FreeRunState.Saver) { FreeRunState() }
    // Одна тренировка — один идентификатор: из него ключи записи подходов.
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    KeepScreenOn()

    // Классификатор — один на экран, выше выбора раскладки: смена ширины и позы Fold его не пересоздают.
    val classifier = remember { FreeSessionClassifier(FreeWorkoutMath.candidates, resume = s.log.resume()) }
    DisposableEffect(classifier) { onDispose { classifier.reset() } }
    val live = remember { FreeLive() }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedMs = if (s.finished) s.finishedElapsed else (now - s.startedAt).coerceAtLeast(0L)

    /** Записать подход [number] (повтор после сбоя — тем же ключом). */
    fun recordSet(number: Int) {
        val set = s.log.sets.firstOrNull { it.number == number } ?: return
        val ex = set.exercise ?: return
        val key = FreeWorkoutMath.setSessionId(sessionId, number)
        val r = try {
            if (ex.hold) {
                Workouts.recordHold(ex, set.heldMs, set.durationMs, key)
            } else {
                Workouts.recordSets(listOf(SetInput(ex, Weights.DEFAULT, set.reps, set.durationMs)), key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать подход", e)
            WorkoutResult(0L, 0L, saved = false)
        }
        s.log = s.log.withResult(number, r.saved, r.earnedMs)
        if (r.saved) {
            r.strengthGain?.let { s.strengthGain += it }
            r.strengthToday?.let { s.strengthToday = it }
        }
    }

    fun recordClosed() {
        s.log.sets.filter { it.status == FreeSetStatus.CLOSED }.forEach { recordSet(it.number) }
    }

    /** Засчитанное камерой: в журнал, голосом, закрытые подходы — сразу в запись. */
    fun onEvents(events: List<FreeEvent>) {
        var log = s.log
        for (e in events) {
            val before = log.open
            log = log.apply(e)
            val open = log.open
            when (e) {
                is FreeEvent.Reps -> open?.let { sound.rep(it.reps) }
                is FreeEvent.Held -> {
                    val prevHeld = before?.takeIf { it.number == open?.number }?.heldMs ?: 0L
                    if (prevHeld == 0L) sound.holdStart()
                    val mark = HoldMath.tenSecondMark(prevHeld, open?.heldMs ?: 0L)
                    if (mark != null) sound.holdSeconds(mark)
                }
                is FreeEvent.Closed -> if (before != null && !s.finished) {
                    if (e.reason == FreeCloseReason.HOLD_END) sound.holdStop() else sound.done()
                }
            }
        }
        s.log = log
        recordClosed()
    }

    fun recordRun() {
        s.log.toRecord.forEach { recordSet(it.number) }
        val failed = s.log.failed
        if (failed > 0) {
            s.retry = true
            s.resultTitle = "Не удалось записать"
            s.resultText = "Не записано подходов: $failed — время за них не начислено. Записанные подходы сохранены: " +
                "+${formatShort(s.log.recordedMs)}. Можно попробовать ещё раз."
            return
        }
        s.retry = false
        val strength = StatsText.strengthResult(s.strengthGain, s.strengthToday.takeIf { it >= 0 })
        val (title, text) = FreeText.result(s.log, strength)
        s.resultTitle = title
        s.resultText = text
    }

    fun finish() {
        if (s.finished) return
        val t = SystemClock.elapsedRealtime()
        s.finished = true
        s.finishedElapsed = (t - s.startedAt).coerceAtLeast(0L)
        // Начатый подход не теряется: классификатор решает спор за последний повтор и закрывает подход.
        onEvents(classifier.finish(t))
        s.log = s.log.closeOpen()
        s.confirmQuit = false
        if (!s.log.hasWork) {
            onDone()
            return
        }
        haptics.strong()
        sound.done()
        recordRun()
    }

    fun requestExit() {
        if (s.log.hasWork) s.confirmQuit = true else onDone()
    }

    BackHandler(enabled = !s.finished) { requestExit() }

    val widthClass = currentWidthClass()
    // Fold «столиком»: камера над сгибом, «Сейчас», подходы и кнопка — под ним.
    val tabletop = rememberTabletop()
    val nowInPanel = tabletop != null || widthClass == WidthClass.EXPANDED
    val active = !s.finished && !s.confirmQuit

    val camera: @Composable (Modifier) -> Unit = { modifier ->
        FreeCameraPanel(
            classifier = classifier,
            live = live,
            log = s.log,
            active = active,
            sound = sound,
            frontCamera = frontCamera,
            onFrontCameraChange = { frontCamera = it },
            showNow = !nowInPanel,
            onEvents = { onEvents(it) },
            modifier = modifier,
        )
    }
    val nowText = FreeText.now(live.current, currentReps(s.log, live.current), currentHeld(s.log, live))

    val panelWidth = widthClass.workoutPanelWidth
    if (tabletop != null) {
        TabletopSplit(tabletop, widthClass, top = camera) {
            TabletopBoard(
                info = { mod ->
                    Column(mod.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FreeHeader(elapsedMs, widthClass)
                        FreeSetsList(s.log, compact = false)
                    }
                },
                score = { mod -> NowLine(nowText, active = live.current != null, onCamera = false, modifier = mod) },
                actions = { FinishButton(enabled = !s.finished, onClick = { finish() }) },
            )
        }
    } else if (panelWidth == null) {
        // Узкий экран: всё в одну колонку, камера забирает остаток высоты.
        Column(
            Modifier
                .fillMaxSize()
                .padding(widthClass.outerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FreeHeader(elapsedMs, widthClass)
            camera(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            FreeSetsList(s.log, compact = true)
            FinishButton(enabled = !s.finished, onClick = { finish() })
        }
    } else {
        WorkoutSideLayout(widthClass, panelWidth, camera) {
            FreeHeader(elapsedMs, widthClass)
            Spacer(Modifier.height(8.dp))
            if (nowInPanel) {
                NowLine(
                    nowText,
                    active = live.current != null,
                    onCamera = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 160.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                FreeSetsList(s.log, compact = false)
            }
            Spacer(Modifier.height(16.dp))
            FinishButton(enabled = !s.finished, onClick = { finish() })
        }
    }

    if (s.confirmQuit && !s.finished) {
        AlertDialog(
            onDismissRequest = { s.confirmQuit = false },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Закончить") } },
            dismissButton = { TextButton(onClick = { s.confirmQuit = false }) { Text("Продолжить") } },
            title = { Text("Закончить тренировку?") },
            text = { Text("Засчитанные камерой подходы уже записаны и оплачены; начатый подход тоже сохранится.") },
        )
    }

    if (s.finished && s.resultTitle.isNotEmpty()) {
        AlertDialog(
            // Случайным касанием мимо диалога итог не теряется: выбор только кнопками.
            onDismissRequest = {},
            confirmButton = {
                if (s.retry) {
                    TextButton(onClick = { recordRun() }) { Text("Повторить") }
                } else {
                    TextButton(onClick = onDone) { Text("Отлично") }
                }
            },
            dismissButton = if (s.retry) {
                { TextButton(onClick = onDone) { Text("Выйти", color = Bad) } }
            } else {
                null
            },
            title = { Text(s.resultTitle) },
            text = { Text(s.resultText, style = MaterialTheme.typography.bodyLarge.merge(Tabular)) },
        )
    }
}

/** Повторы текущего упражнения в открытом подходе (0 — подхода этого упражнения нет). */
private fun currentReps(log: FreeWorkoutLog, current: String?): Int =
    log.open?.takeIf { it.exerciseId == current }?.reps ?: 0

/** Удержание текущего упражнения: засчитанное в открытом подходе плюс ещё не засчитанный отрезок. */
private fun currentHeld(log: FreeWorkoutLog, live: FreeLive): Long =
    (log.open?.takeIf { it.exerciseId == live.current }?.heldMs ?: 0L) + live.pendingHeldMs

@Composable
private fun FreeHeader(elapsedMs: Long, widthClass: WidthClass) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            FreeText.TITLE,
            style = if (widthClass == WidthClass.COMPACT) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ScoreText(
            formatDuration(elapsedMs),
            sizeSp = if (widthClass == WidthClass.EXPANDED) Scoreboard.exerciseTimerSp(widthClass) else 32,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Подходы тренировки и общее заработанное. [compact] — узкий экран под камерой: последние [COMPACT_SETS]
 * подходов (свежий сверху), остальные — числом.
 */
@Composable
private fun FreeSetsList(log: FreeWorkoutLog, compact: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.titleMedium.merge(Tabular)
    val sets = log.sets.filter { it.hasWork }.reversed()
    val shown = if (compact) sets.take(COMPACT_SETS) else sets
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Заработано: +${formatShort(log.earnedMs)}", style = style, color = Accent)
        if (sets.isEmpty()) {
            Text("Подходов пока нет", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        shown.forEach { set ->
            val failed = set.status == FreeSetStatus.FAILED
            Text(
                "${set.number}. " + FreeText.setLine(set, FreeText.setEarned(set)) +
                    if (set.status == FreeSetStatus.OPEN) " · идёт" else if (failed) " · не записан" else "",
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = if (failed) Bad else if (set.status == FreeSetStatus.OPEN) MaterialTheme.colorScheme.onBackground else muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (compact && sets.size > shown.size) {
            Text("и ещё подходов: ${sets.size - shown.size}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

private const val COMPACT_SETS = 2

@Composable
private fun FinishButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) { Text("Закончить", style = MaterialTheme.typography.titleLarge, maxLines = 1) }
}

/**
 * Крупная строка «Сейчас: Приседания · 12» — целиком, без многоточия: размер шрифта подбирается измерением
 * под отведённую область (до 3 строк), от крупного к мелкому. [onCamera] — поверх превью (белый на затемнении).
 */
@Composable
private fun NowLine(text: String, active: Boolean, onCamera: Boolean, modifier: Modifier = Modifier) {
    val t = MaterialTheme.typography
    val styles = listOf(t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val color = when {
        active -> Accent
        onCamera -> Color.White
        else -> MaterialTheme.colorScheme.onBackground
    }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val padH = 16.dp
        val padV = 8.dp
        val maxW = (constraints.maxWidth - with(density) { (padH * 2).roundToPx() })
            .coerceAtLeast(1)
        val maxH = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - with(density) { (padV * 2).roundToPx() })
                .coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        val style: TextStyle = remember(text, maxW, maxH, styles) {
            styles.firstOrNull { st ->
                val r = measurer.measure(text, style = st, maxLines = NOW_MAX_LINES, constraints = Constraints(maxWidth = maxW))
                !r.hasVisualOverflow && r.size.height <= maxH
            } ?: styles.last()
        }
        val base = if (onCamera) Modifier.background(CameraScrim, RoundedCornerShape(24.dp)) else Modifier
        Text(
            text,
            color = color,
            style = style.merge(Tabular),
            textAlign = TextAlign.Center,
            maxLines = NOW_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = base
                .padding(horizontal = padH, vertical = padV)
                .clearAndSetSemantics { contentDescription = text },
        )
    }
}

private const val NOW_MAX_LINES = 3

/**
 * Камера вольной тренировки: превью со скелетом, плашка состояния, крупная строка «Сейчас» ([showNow]) и подсказка
 * ракурса, пока ничего не распознано. Каждый кадр — в классификатор (пока [active]), засчитанное — в [onEvents].
 */
@Composable
private fun FreeCameraPanel(
    classifier: FreeSessionClassifier,
    live: FreeLive,
    log: FreeWorkoutLog,
    active: Boolean,
    sound: RepSound,
    frontCamera: Boolean,
    onFrontCameraChange: (Boolean) -> Unit,
    showNow: Boolean,
    onEvents: (List<FreeEvent>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black)
    val context = LocalContext.current
    // «Чёрный ящик»: кадры тренировки в файл, чтобы настраивать распознавание по записям.
    val writer = remember(frontCamera) { PoseLogWriter(context, "free", frontCamera) }
    DisposableEffect(writer) { onDispose { writer.close() } }
    val diag = remember { FrameDiag("free") }

    val currentActive by rememberUpdatedState(active)
    val currentOnEvents by rememberUpdatedState(onEvents)
    val currentLog by rememberUpdatedState(log)
    val currentWriter by rememberUpdatedState(writer)
    val currentSound by rememberUpdatedState(sound)

    LaunchedEffect(active, frontCamera) { writer.markReset() }

    val onFrame: (PoseFrame) -> Unit = remember(classifier) {
        { frame ->
            diag.onFrame(frame)
            if (currentActive) {
                val before = live.current
                val st = classifier.onFrame(frame)
                live.update(st)
                if (st.events.isNotEmpty()) currentOnEvents(st.events)
                // Первое распознанное упражнение — двойной сигнал «готов» (дальше смену слышно по счёту и «готово»).
                if (before == null && st.current != null) currentSound.ready()
                if (st.events.none { it is FreeEvent.Reps || it is FreeEvent.Held } && st.tracking && st.hint.isNotEmpty()) {
                    currentSound.hint(st.hint)
                }
                val open = currentLog.open?.takeIf { it.exerciseId == st.current }
                currentWriter.write(
                    frame,
                    if (st.current != null) "COUNT" else "SEARCH",
                    RepState(
                        count = open?.reps ?: 0,
                        tracking = st.tracking,
                        inPosition = st.inPose,
                        hint = st.hint,
                        depth = st.depth,
                        heldMs = open?.heldMs ?: 0L,
                        pendingMs = st.pendingHeldMs,
                        holding = st.holding,
                    ),
                )
            }
        }
    }

    CameraPermissionGate(panel) {
        Box(Modifier.fillMaxSize()) {
            PoseCameraView(
                modifier = Modifier.fillMaxSize(),
                frontCamera = frontCamera,
                showSkeleton = true,
                onFrame = onFrame,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    FreeStatusPlate(live, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    CameraSwitch { onFrontCameraChange(!frontCamera) }
                }
                DiagLine(diag, Modifier.padding(top = 6.dp))
                if (showNow) {
                    NowLine(
                        FreeText.now(live.current, currentReps(log, live.current), currentHeld(log, live)),
                        active = live.current != null,
                        onCamera = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (live.current == null) {
                    Text(
                        FreeText.SETUP,
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

/** Плашка состояния над камерой: подсказка (жёлтая) или «Считаю» / «Держи планку» (зелёная). */
@Composable
private fun FreeStatusPlate(live: FreeLive, modifier: Modifier) {
    val hint = live.hint
    val (text, color) = when {
        hint.isNotEmpty() -> hint to Warn
        live.current == null || !live.inPose -> (if (live.tracking) FreeText.FIND_START else FreeText.START) to Warn
        live.holding -> FreeText.HOLDING to Good
        else -> FreeText.COUNTING to Good
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CameraScrim)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        StatusText(text, color)
    }
}

/** Карточка на главной: в начале раздела упражнений, рядом с «Без веса». */
@Composable
internal fun FreeWorkoutHomeCard(onOpen: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Начать вольную тренировку", onClick = onOpen),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(FreeText.TITLE, style = MaterialTheme.typography.titleMedium)
                Text(
                    FreeText.CARD_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Без веса: ${FreeWorkoutMath.exercises.joinToString(", ") { it.name.lowercase() }} — по обычным ценам",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
