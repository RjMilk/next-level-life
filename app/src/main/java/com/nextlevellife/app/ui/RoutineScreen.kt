package com.nextlevellife.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.HoldStep
import com.nextlevellife.app.data.RepsStep
import com.nextlevellife.app.data.RestStep
import com.nextlevellife.app.data.Routine
import com.nextlevellife.app.data.RoutineExercise
import com.nextlevellife.app.data.RoutineKind
import com.nextlevellife.app.data.RoutineMath
import com.nextlevellife.app.data.RoutineStep
import com.nextlevellife.app.data.RoutineStepResult
import com.nextlevellife.app.data.Routines
import com.nextlevellife.app.data.WorkoutResult
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.pose.CameraPermissionGate
import com.nextlevellife.app.pose.PoseCameraView
import com.nextlevellife.app.pose.PoseFrame
import com.nextlevellife.app.pose.PoseHints
import com.nextlevellife.app.pose.PoseHoldConfigs
import com.nextlevellife.app.pose.PoseHoldCounter
import com.nextlevellife.app.pose.PoseLogWriter
import com.nextlevellife.app.pose.PoseRepCounter
import com.nextlevellife.app.pose.RepCounter
import com.nextlevellife.app.pose.RepSound
import com.nextlevellife.app.pose.RepState
import com.nextlevellife.app.pose.rememberRepSound
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min

private const val TAG = "RoutineScreen"

/** Пауза между сигналом «шаг выполнен» и голосом следующего шага: иначе они сливаются. */
private const val ROUTINE_ANNOUNCE_DELAY_MS = 700L

// ---------------------------------------------------------------------------------------------
// Чистая логика экрана программы (без Compose)
// ---------------------------------------------------------------------------------------------

internal object RoutineText {
    /** «Воин II, левая сторона. 45 секунд», «Подъём рук вперёд сидя, 8 раз», «Отдых. Следующее: Стул». */
    fun announce(step: RoutineStep, next: RoutineExercise?): String = when (step) {
        is HoldStep -> listOfNotNull(
            step.name + (step.side?.let { ", ${it.fem} сторона" } ?: ""),
            "${step.seconds} ${pluralRu(step.seconds, "секунда", "секунды", "секунд")}",
        ).joinToString(". ")
        is RepsStep -> step.name + (step.side?.let { ", ${it.fem} сторона" } ?: "") +
            ", ${step.reps} ${pluralRu(step.reps, "раз", "раза", "раз")}"
        is RestStep -> listOfNotNull(step.title, next?.let { "Следующее: ${it.title.replace(" · ", ", ")}" })
            .joinToString(". ")
    }

    /** Что сказать после выполненного шага [step], если дальше [after]. */
    fun afterDone(step: RoutineStep, after: RoutineStep?): String = when {
        after == null -> "Готово"
        step is RoutineExercise && after is RoutineExercise && after.exerciseId == step.exerciseId &&
            after.side != step.side -> "Готово. Смени сторону"
        after is RestStep && after.title == "Смена стороны" -> "Готово. Смени сторону"
        else -> "Готово"
    }

    /** Табло шага: «0:23 / 0:30», «3 / 8», отдых — «0:12». */
    fun board(step: RoutineStep, heldMs: Long, reps: Int, restLeftMs: Long): String = when (step) {
        is HoldStep -> "${ProgressMath.formatHold(heldMs)} / ${ProgressMath.formatHold(step.targetMs)}"
        is RepsStep -> "$reps / ${step.reps}"
        is RestStep -> ProgressMath.formatHold(restLeftMs.coerceAtLeast(0L) + 999L)
    }

    /** «9 мин · 8 упражнений», «10 мин · 8 поз». */
    fun summary(r: Routine): String {
        val n = r.blocks
        val what = if (r.kind == RoutineKind.LFK) pluralRu(n, "упражнение", "упражнения", "упражнений") else pluralRu(n, "поза", "позы", "поз")
        return "${r.kind.title} · ${r.declaredMinutes} мин · $n $what"
    }

    /** Итог: подтверждено, начислено, предел, завершённость. */
    fun result(r: Routine, results: List<RoutineStepResult>, earnedMs: Long): Pair<String, String> {
        val score = RoutineMath.score(r, results)
        val title = if (score.completed) "Программа пройдена" else "Программа не завершена"
        val parts = ArrayList<String>()
        val confirmed = listOfNotNull(
            score.heldMs.takeIf { it > 0L }?.let { "${ProgressMath.formatHold(it)} в позах" },
            score.totalReps.takeIf { it > 0 }?.let { "$it ${complexRepsWord(it)}" },
        )
        parts += if (confirmed.isEmpty()) "Камера ничего не подтвердила." else "Подтверждено камерой: ${confirmed.joinToString(" и ")}."
        parts += "+${formatShort(earnedMs)} экранного времени."
        if (earnedMs < score.rawEarnedMs) parts += "Дневной предел за ЛФК и йогу — ${formatShort(RoutineMath.DAILY_CAP_MS)}: начислено не всё."
        if (!score.completed) parts += "Часть шагов пропущена или подтверждена меньше чем на ${RoutineMath.DONE_PERCENT} %."
        return title to parts.joinToString(" ")
    }
}

// ---------------------------------------------------------------------------------------------
// Состояние прохождения: переживает поворот и раскрытие Fold
// ---------------------------------------------------------------------------------------------

internal class RoutineRunState(val routine: Routine) {
    private val n = routine.steps.size
    var startedAt by mutableLongStateOf(0L)
    var stepIndex by mutableIntStateOf(0)
    var stepStartedAt by mutableLongStateOf(0L)

    /** Подтверждённое по шагам (массивы меняются на месте: на экран идёт через [RoutineController]). */
    var held = LongArray(n)
    var reps = IntArray(n)
    var durations = LongArray(n)
    var skipped = BooleanArray(n)
    var finished by mutableStateOf(false)
    var finishedElapsed by mutableLongStateOf(0L)
    var resultTitle by mutableStateOf("")
    var resultText by mutableStateOf("")
    var retry by mutableStateOf(false)
    var confirmSkip by mutableStateOf(false)
    var confirmQuit by mutableStateOf(false)

    val started get() = startedAt != 0L
    val running get() = started && !finished
    val step: RoutineStep get() = routine.steps[stepIndex]

    fun results(): List<RoutineStepResult> = routine.steps.indices.mapNotNull { i ->
        if (routine.steps[i] is RestStep || (held[i] <= 0L && reps[i] <= 0 && !skipped[i])) null
        else RoutineStepResult(i, held[i], reps[i], durations[i], skipped[i])
    }

    companion object {
        fun saver(routine: Routine) = listSaver<RoutineRunState, Any>(
            save = {
                listOf(
                    it.startedAt, it.stepIndex, it.stepStartedAt, it.held.copyOf(), it.reps.copyOf(),
                    it.durations.copyOf(), it.skipped.copyOf(), it.finished, it.finishedElapsed,
                    it.resultTitle, it.resultText, it.retry, it.confirmSkip, it.confirmQuit,
                )
            },
            restore = { v ->
                RoutineRunState(routine).apply {
                    startedAt = v[0] as Long
                    stepIndex = v[1] as Int
                    stepStartedAt = v[2] as Long
                    held = v[3] as LongArray
                    reps = v[4] as IntArray
                    durations = v[5] as LongArray
                    skipped = v[6] as BooleanArray
                    finished = v[7] as Boolean
                    finishedElapsed = v[8] as Long
                    resultTitle = v[9] as String
                    resultText = v[10] as String
                    retry = v[11] as Boolean
                    confirmSkip = v[12] as Boolean
                    confirmQuit = v[13] as Boolean
                }
            },
        )
    }
}

/**
 * Счётчики шагов и то, что видно на экране. Живёт, пока экран на месте (после пересоздания Activity —
 * новый: засчитанное до него берётся из [RoutineRunState], счётчик добирает только остаток цели).
 * Кадры приходят в главном потоке.
 */
internal class RoutineController(private val s: RoutineRunState) {
    private val routine = s.routine
    private val counters = HashMap<Int, RepCounter>()
    private val base = HashMap<Int, Long>()

    var status by mutableStateOf("" to false)
        private set
    var heldMs by mutableLongStateOf(0L)
        private set
    var reps by mutableIntStateOf(0)
        private set
    var live by mutableStateOf(false)
        private set
    var tracking by mutableStateOf(false)
        private set
    var lastState = RepState()
        private set
    private var spokenSecond = -1L

    /** Счётчик шага [i] (создаётся при первом обращении); null у отдыха и неизвестной позы. */
    fun counterFor(i: Int): RepCounter? {
        counters[i]?.let { return it }
        val c: RepCounter = when (val step = routine.steps[i]) {
            is HoldStep -> {
                base[i] = s.held[i]
                PoseHoldConfigs.holdCounter(step.poseId, step.side, (step.targetMs - s.held[i]).coerceAtLeast(1_000L))
            }
            is RepsStep -> {
                base[i] = s.reps[i].toLong()
                PoseHoldConfigs.repCounter(step.moveId, step.side, (step.reps - s.reps[i]).coerceAtLeast(1))
            }
            is RestStep -> null
        } ?: return null
        counters[i] = c
        return c
    }

    /** Новый шаг: табло из сохранённого. */
    fun enterStep(i: Int) {
        heldMs = s.held[i]
        reps = s.reps[i]
        live = false
        spokenSecond = -1L
        lastState = RepState()
        status = when (val st = routine.steps[i]) {
            is RestStep -> st.title to true
            else -> PoseHints.NOT_SEEN to false
        }
    }

    /**
     * Кадр текущего шага. [onStepDone] — цель шага набрана. Отдых: кадры идут счётчику следующей позы
     * в режиме наблюдения (допуск на живой вход), время не платится.
     */
    fun onFrame(frame: PoseFrame, sound: RepSound, haptics: Haptics, onStepDone: () -> Unit) {
        val i = s.stepIndex
        when (val step = routine.steps[i]) {
            is RestStep -> {
                val j = (i + 1 until routine.steps.size).firstOrNull { routine.steps[it] !is RestStep }
                (j?.let { counterFor(it) } as? PoseHoldCounter)?.observe(frame)
            }
            is HoldStep -> {
                val c = counterFor(i) as? PoseHoldCounter ?: return
                val prev = lastState
                val st = c.onFrame(frame)
                lastState = st
                val total = (base[i] ?: 0L) + st.shownHeldMs
                s.held[i] = (base[i] ?: 0L) + st.heldMs
                heldMs = total
                live = st.holding
                tracking = st.tracking
                if (!prev.inPosition && st.inPosition) {
                    sound.ready()
                    sound.say("Держи")
                } else if (prev.inPosition && !st.inPosition && !c.info.done) {
                    sound.holdStop()
                }
                if (st.inPosition) {
                    val left = step.targetMs - total
                    val sec = total / 1000
                    if (left in 1..3_000) {
                        val n = (left + 999) / 1000
                        if (n != spokenSecond) {
                            spokenSecond = n
                            sound.say(listOf("", "один", "два", "три")[n.toInt()])
                        }
                    } else if (sec > 0 && sec % 10 == 0L && prev.shownHeldMs / 1000 != st.shownHeldMs / 1000) {
                        sound.holdSeconds(sec.toInt())
                    }
                } else if (st.hint.isNotEmpty()) {
                    sound.hint(st.hint)
                }
                status = when {
                    c.info.done -> "Готово" to true
                    st.holding -> "Держи" to true
                    c.info.entering -> "Хорошо, замри" to true
                    !st.tracking && st.hint.isEmpty() -> PoseHints.NOT_SEEN to false
                    st.hint.isNotEmpty() -> st.hint to false
                    else -> "Прими позу" to false
                }
                if (c.info.done) {
                    s.held[i] = step.targetMs
                    heldMs = step.targetMs
                    haptics.strong()
                    onStepDone()
                }
            }
            is RepsStep -> {
                val c = counterFor(i) as? PoseRepCounter ?: return
                val prev = lastState
                val st = c.onFrame(frame)
                lastState = st
                val total = ((base[i] ?: 0L) + st.count).toInt().coerceAtMost(step.reps)
                tracking = st.tracking
                if (st.count > prev.count) {
                    s.reps[i] = total
                    reps = total
                    haptics.tap()
                    sound.rep(total)
                } else if (st.hint.isNotEmpty()) {
                    sound.hint(st.hint)
                }
                live = st.inPosition
                status = when {
                    total >= step.reps -> "Готово" to true
                    st.hint.isNotEmpty() -> st.hint to false
                    st.inPosition -> "Считаю" to true
                    !st.tracking -> PoseHints.NOT_SEEN to false
                    else -> "Прими исходное положение" to false
                }
                if (total >= step.reps) onStepDone()
            }
        }
    }

    fun reset() {
        counters.values.forEach { it.reset() }
        counters.clear()
    }
}

// ---------------------------------------------------------------------------------------------
// Экран
// ---------------------------------------------------------------------------------------------

/**
 * Программа ЛФК или йоги ([Routines]): описание и шаги перед стартом, затем шаг за шагом — камера со скелетом,
 * табло «0:23 / 0:30» или «3 / 8», голос; цель набрана — следующий шаг сам. «Пропустить шаг» — шаг не засчитан
 * (подтверждённое до пропуска оплачивается), «Закончить» — записать сделанное по ценам [RoutineMath].
 * [onShowGuide] — кнопка ▶ «Как выполнять» (ключ анимации шага); показ техники подключается отдельно.
 */
@Composable
fun RoutineScreen(routineId: String, onDone: () -> Unit, onShowGuide: (String) -> Unit = {}) {
    val routine = remember(routineId) { Routines.byId(routineId) }
    if (routine == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val s = rememberSaveable(routineId, saver = RoutineRunState.saver(routine)) { RoutineRunState(routine) }
    // Одно прохождение — один идентификатор записи: повторный вызов ничего не начислит второй раз.
    val sessionId = rememberSaveable(routineId) { UUID.randomUUID().toString() }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    val scope = rememberCoroutineScope()
    val controller = remember(s) { RoutineController(s) }
    DisposableEffect(controller) { onDispose { controller.reset() } }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var announceJob by remember { mutableStateOf<Job?>(null) }

    fun announce(text: String, delayMs: Long, interrupt: Boolean = true) {
        announceJob?.cancel()
        announceJob = scope.launch {
            delay(delayMs)
            sound.say(text, interrupt)
        }
    }

    fun record() {
        val results = s.results()
        val r = try {
            Workouts.recordRoutine(routine, results, s.finishedElapsed, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать программу", e)
            WorkoutResult(0L, 0L, saved = false)
        }
        if (!r.saved) {
            s.retry = true
            s.resultTitle = "Не удалось записать"
            s.resultText = "Программа не сохранилась, время не начислено. Можно попробовать ещё раз."
            return
        }
        s.retry = false
        val (title, text) = RoutineText.result(routine, results, r.earnedMs)
        s.resultTitle = title
        s.resultText = text
        announce(title, ROUTINE_ANNOUNCE_DELAY_MS, interrupt = false)
    }

    fun finish() {
        if (s.finished) return
        val t = SystemClock.elapsedRealtime()
        s.durations[s.stepIndex] = (t - s.stepStartedAt).coerceAtLeast(0L)
        s.finished = true
        s.finishedElapsed = (t - s.startedAt).coerceAtLeast(0L)
        s.confirmSkip = false
        s.confirmQuit = false
        announceJob?.cancel()
        if (s.results().none { it.heldMs > 0L || it.reps > 0 }) {
            onDone()
            return
        }
        haptics.strong()
        sound.done()
        record()
    }

    fun advance(skipped: Boolean) {
        if (!s.running) return
        val i = s.stepIndex
        val t = SystemClock.elapsedRealtime()
        s.durations[i] = (t - s.stepStartedAt).coerceAtLeast(0L)
        if (skipped) s.skipped[i] = true
        if (i >= routine.steps.lastIndex) {
            finish()
            return
        }
        val step = routine.steps[i]
        val nextStep = routine.steps[i + 1]
        s.stepIndex = i + 1
        s.stepStartedAt = t
        now = t
        controller.enterStep(i + 1)
        val lead = if (step is RoutineExercise && !skipped) {
            sound.done()
            RoutineText.afterDone(step, nextStep) + ". "
        } else {
            ""
        }
        announce(lead + RoutineText.announce(nextStep, routine.nextExercise(i + 1)), ROUTINE_ANNOUNCE_DELAY_MS)
    }

    val onStepDone by rememberUpdatedState { advance(skipped = false) }

    // Часы: отдых идёт по времени; отсчёт «три, два, один» в конце отдыха.
    LaunchedEffect(s.running) {
        if (!s.running) return@LaunchedEffect
        var spoken = -1L
        while (true) {
            val t = SystemClock.elapsedRealtime()
            now = t
            val step = s.step
            if (step is RestStep && !s.confirmQuit && !s.confirmSkip) {
                val left = step.nominalMs - (t - s.stepStartedAt)
                if (left <= 0L) {
                    advance(skipped = false)
                } else if (left <= 3_000L) {
                    val n = (left + 999) / 1000
                    if (n != spoken) {
                        spoken = n
                        sound.say(listOf("", "один", "два", "три")[n.toInt()], interrupt = false)
                    }
                }
            }
            delay(200)
        }
    }

    if (s.running) {
        KeepScreenOn()
        BackHandler { s.confirmQuit = true }
    }

    if (!s.started) {
        RoutinePrep(routine, onBack = onDone, onStart = {
            haptics.tap()
            val t = SystemClock.elapsedRealtime()
            s.stepIndex = 0
            s.startedAt = t
            s.stepStartedAt = t
            now = t
            controller.enterStep(0)
            announce(RoutineText.announce(routine.steps[0], routine.nextExercise(0)), 300L)
        })
    } else {
        RoutineRun(
            s = s,
            controller = controller,
            nowMs = now,
            sound = sound,
            haptics = haptics,
            cameraActive = s.running && !s.confirmSkip && !s.confirmQuit,
            onStepDone = { onStepDone() },
            onSkip = {
                if (s.step is RestStep) advance(skipped = false) else s.confirmSkip = true
            },
            onQuit = { s.confirmQuit = true },
            onShowGuide = onShowGuide,
        )
    }

    if (s.confirmSkip && s.running) {
        val step = s.step
        val done = RoutineMath.stepDone(step, RoutineStepResult(s.stepIndex, s.held[s.stepIndex], s.reps[s.stepIndex]))
        AlertDialog(
            onDismissRequest = { s.confirmSkip = false },
            confirmButton = {
                TextButton(onClick = {
                    s.confirmSkip = false
                    haptics.strong()
                    advance(skipped = true)
                }) { Text("Пропустить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { s.confirmSkip = false }) { Text("Продолжить") } },
            title = { Text("Пропустить шаг?") },
            text = {
                Text(
                    if (done) "Шаг уже засчитан: подтверждено больше ${RoutineMath.DONE_PERCENT} %."
                    else "Шаг не будет засчитан, программа — не завершена. Подтверждённое камерой время сохранится.",
                )
            },
        )
    }

    if (s.confirmQuit && s.running) {
        AlertDialog(
            onDismissRequest = { s.confirmQuit = false },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Закончить", color = Bad) } },
            dismissButton = { TextButton(onClick = { s.confirmQuit = false }) { Text("Продолжить") } },
            title = { Text("Закончить программу?") },
            text = { Text("Подтверждённое камерой сохранится и будет оплачено по ценам программы.") },
        )
    }

    if (s.finished && s.resultTitle.isNotEmpty()) {
        AlertDialog(
            // Случайным касанием мимо диалога итог не теряется: выбор только кнопками.
            onDismissRequest = {},
            confirmButton = {
                if (s.retry) {
                    TextButton(onClick = { record() }) { Text("Повторить") }
                } else {
                    TextButton(onClick = onDone) { Text("Готово") }
                }
            },
            dismissButton = if (s.retry) {
                { TextButton(onClick = onDone) { Text("Выйти без записи", color = Bad) } }
            } else {
                null
            },
            title = { Text(s.resultTitle) },
            text = { Text(s.resultText, style = MaterialTheme.typography.bodyLarge.merge(Tabular)) },
        )
    }
}

// ---------- Подготовка ----------

@Composable
private fun RoutinePrep(routine: Routine, onBack: () -> Unit, onStart: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val widthClass = currentWidthClass()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(widthClass.outerPadding),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(routine.title, style = MaterialTheme.typography.headlineMedium, color = Accent)
                Text(
                    RoutineText.summary(routine) + " · до +${formatShort(RoutineMath.maxRewardMs(routine))}",
                    style = MaterialTheme.typography.titleSmall.merge(Tabular),
                    color = muted,
                )
                Text(routine.subtitle, style = MaterialTheme.typography.bodyLarge)
                Card(colors = CardDefaults.cardColors(containerColor = RoutineCardColor)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (routine.kind == RoutineKind.LFK) {
                            Text("Безопасность", style = MaterialTheme.typography.titleMedium, color = Warn)
                            Text(Routines.LFK_WARNING, style = MaterialTheme.typography.bodyMedium)
                            Text(Routines.NOT_MEDICAL, style = MaterialTheme.typography.bodySmall, color = muted)
                        } else {
                            Text(Routines.YOGA_NOTE, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "Время и повторения засчитывает камера: поза — пока ты в ней, повторение — полный цикл. " +
                        "Отдых и переходы не оплачиваются. Цена: поза — ¼ секунды за секунду, повторение — " +
                        "${formatShort(RoutineMath.REP_REWARD_MS)}; за день — не больше ${formatShort(RoutineMath.DAILY_CAP_MS)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Text("Шаги", style = MaterialTheme.typography.titleMedium)
                var n = 0
                routine.steps.forEach { step ->
                    when (step) {
                        is RestStep -> Text(
                            "${step.title} · ${step.seconds} с" + if (step.note.isNotEmpty()) " — ${step.note}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        is RoutineExercise -> {
                            n++
                            Column {
                                Text("$n. ${step.title} — ${stepAmount(step)}", style = MaterialTheme.typography.bodyLarge.merge(Tabular))
                                Text(step.cameraLine, style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, shape = ButtonShape, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text("Назад", style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onStart, shape = ButtonShape, modifier = Modifier.weight(2f).height(64.dp)) {
                    Text("Начать", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** «45 с», «45 с, без награды», «8 раз». */
private fun stepAmount(step: RoutineExercise): String = when (step) {
    is HoldStep -> "${step.seconds} с" + if (!step.paid) ", без награды" else ""
    is RepsStep -> "${step.reps} ${pluralRu(step.reps, "раз", "раза", "раз")}"
}

// ---------- Выполнение ----------

@Composable
private fun RoutineRun(
    s: RoutineRunState,
    controller: RoutineController,
    nowMs: Long,
    sound: RepSound,
    haptics: Haptics,
    cameraActive: Boolean,
    onStepDone: () -> Unit,
    onSkip: () -> Unit,
    onQuit: () -> Unit,
    onShowGuide: (String) -> Unit,
) {
    val widthClass = currentWidthClass()
    val pad = widthClass.outerPadding
    // Выбор камеры — до ветвления раскладки: переживает и пересоздание, и смену класса ширины.
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    val step = s.step
    val restLeft = if (step is RestStep) step.nominalMs - (nowMs - s.stepStartedAt) else 0L
    val scoreInPanel = widthClass == WidthClass.EXPANDED

    val camera: @Composable (Modifier) -> Unit = { mod ->
        RoutineCamera(
            s = s, controller = controller, active = cameraActive, sound = sound, haptics = haptics,
            frontCamera = frontCamera, onFrontCameraChange = { frontCamera = it },
            showScore = !scoreInPanel, restLeftMs = restLeft, onStepDone = onStepDone, modifier = mod,
        )
    }

    val panelWidth = widthClass.workoutPanelWidth
    if (panelWidth == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepHeader(s, onShowGuide, compact = true)
            camera(Modifier.weight(1f).fillMaxWidth())
            RunControls(s, stacked = false, onSkip = onSkip, onQuit = onQuit)
        }
    } else {
        // Fold и планшет: слева шаг и табло рядом с камерой, кнопки закреплены внизу панели.
        WorkoutSideLayout(widthClass, panelWidth, camera) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepHeader(s, onShowGuide, compact = false)
                if (scoreInPanel) {
                    StepScore(s, controller, restLeft, onCamera = false, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            RunControls(s, stacked = true, onSkip = onSkip, onQuit = onQuit)
        }
    }
}

@Composable
private fun StepHeader(s: RoutineRunState, onShowGuide: (String) -> Unit, compact: Boolean) {
    val routine = s.routine
    val step = s.step
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val exerciseNo = routine.steps.take(s.stepIndex + 1).count { it is RoutineExercise }
    val total = routine.exercises.size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${routine.title} · шаг ${exerciseNo.coerceAtLeast(1)} из $total",
                color = muted,
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val guide = (step as? RoutineExercise)?.guideId ?: routine.nextExercise(s.stepIndex)?.guideId
            // ▶ — только если у шага есть показ техники.
            if (guide != null && com.nextlevellife.app.guide.Guides.of(guide) != null) {
                TextButton(onClick = { onShowGuide(guide) }) { Text("▶ Как выполнять") }
            }
        }
        LinearProgressIndicator(
            progress = { (s.stepIndex.toFloat() / routine.steps.size).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            step.title,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = when (step) {
            is RestStep -> listOfNotNull(
                step.note.ifEmpty { null },
                routine.nextExercise(s.stepIndex)?.let { "Следующее: ${it.title} · ${it.cameraLine}" },
            ).joinToString("\n")
            is RoutineExercise -> listOfNotNull(step.tips.firstOrNull(), step.cameraLine).joinToString("\n")
        }
        if (sub.isNotEmpty()) {
            Text(sub, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 3 else 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RunControls(s: RoutineRunState, stacked: Boolean, onSkip: () -> Unit, onQuit: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val label = MaterialTheme.typography.titleMedium
    val skipText = if (s.step is RestStep) "Дальше" else "Пропустить шаг"
    val skip: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(
            onClick = onSkip, enabled = s.running, shape = ButtonShape, contentPadding = padding,
            modifier = mod.heightIn(min = MainButtonHeight),
        ) { Text(skipText, style = label, textAlign = TextAlign.Center) }
    }
    val quit: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(
            onClick = onQuit, enabled = s.running, shape = ButtonShape, contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad),
            modifier = mod.heightIn(min = MainButtonHeight),
        ) { Text("Закончить", style = label, textAlign = TextAlign.Center) }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            skip(Modifier.fillMaxWidth())
            quit(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            skip(Modifier.weight(3f))
            quit(Modifier.weight(2f))
        }
    }
}

/** Табло шага и полоса прогресса: «0:23 / 0:30», «3 / 8», отдых — обратный отсчёт. */
@Composable
private fun StepScore(
    s: RoutineRunState,
    controller: RoutineController,
    restLeftMs: Long,
    onCamera: Boolean,
    modifier: Modifier = Modifier,
) {
    val step = s.step
    val text = RoutineText.board(step, controller.heldMs, controller.reps, restLeftMs)
    val progress = when (step) {
        is HoldStep -> controller.heldMs.toFloat() / step.targetMs
        is RepsStep -> controller.reps.toFloat() / step.reps
        is RestStep -> 1f - restLeftMs.toFloat() / step.nominalMs
    }.coerceIn(0f, 1f)
    val idle = if (onCamera) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val color = when {
        step is RestStep -> if (onCamera) Color.White else MaterialTheme.colorScheme.onBackground
        controller.live -> Accent
        else -> idle
    }
    val fontScale = LocalDensity.current.fontScale
    val base = if (onCamera) Modifier.background(CameraScrim, RoundedCornerShape(24.dp)) else Modifier
    Column(
        modifier.then(base).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Моноширинные цифры ≈ 0,62 высоты шрифта в ширину.
            val sp = min(maxWidth.value / (text.length * 0.62f) / fontScale, 96f).toInt().coerceAtLeast(28)
            ScoreText(text, sizeSp = sp, color = color, textAlign = TextAlign.Center)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (step is RestStep) MaterialTheme.colorScheme.secondary else Accent,
            trackColor = if (onCamera) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        )
        if (step is RestStep) {
            Text(step.title, color = idle, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoutineCamera(
    s: RoutineRunState,
    controller: RoutineController,
    active: Boolean,
    sound: RepSound,
    haptics: Haptics,
    frontCamera: Boolean,
    onFrontCameraChange: (Boolean) -> Unit,
    showScore: Boolean,
    restLeftMs: Long,
    onStepDone: () -> Unit,
    modifier: Modifier,
) {
    val panel = modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black)
    val step = s.step
    val index = s.stepIndex
    // «Чёрный ящик»: кадры шага-упражнения в файл (exercise_id шага), чтобы настраивать пороги по записям.
    val context = LocalContext.current
    val logId = (step as? RoutineExercise)?.exerciseId
    val writer = remember(index, frontCamera, logId) { logId?.let { PoseLogWriter(context, it, frontCamera) } }
    DisposableEffect(writer) { onDispose { writer?.close() } }
    LaunchedEffect(writer, active) { writer?.markReset() }

    val currentActive by rememberUpdatedState(active)
    val currentSound by rememberUpdatedState(sound)
    val currentWriter by rememberUpdatedState(writer)
    val currentOnStepDone by rememberUpdatedState(onStepDone)
    val onFrame: (PoseFrame) -> Unit = remember(controller) {
        { frame ->
            if (currentActive) {
                controller.onFrame(frame, currentSound, haptics) { currentOnStepDone() }
                val st = controller.lastState
                currentWriter?.write(frame, if (st.inPosition) "COUNT" else "SEARCH", st)
            }
        }
    }

    CameraPermissionGate(panel) {
        Box(Modifier.fillMaxSize()) {
            PoseCameraView(modifier = Modifier.fillMaxSize(), frontCamera = frontCamera, showSkeleton = true, onFrame = onFrame)
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    val (text, good) = controller.status
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CameraScrim)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        StatusText(text.ifEmpty { step.title }, if (good) Good else Warn)
                    }
                    Spacer(Modifier.width(8.dp))
                    CameraSwitch { onFrontCameraChange(!frontCamera) }
                }
                if (showScore) {
                    Spacer(Modifier.weight(1f))
                    StepScore(s, controller, restLeftMs, onCamera = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // Пока человека не видно или идёт отдых — где поставить телефон.
                val setup = when (step) {
                    is RoutineExercise -> step.cameraLine.takeIf { !controller.tracking }
                    is RestStep -> s.routine.nextExercise(index)?.let { "Дальше: ${it.cameraLine}" }
                }
                if (setup != null) {
                    Text(
                        setup,
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

/** Фон карточек программ и плашки предупреждения — как у карточек комплексов. */
internal val RoutineCardColor: Color get() = SaitamaCardColor
