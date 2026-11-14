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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.CustomProgramMath
import com.nextlevellife.app.data.Program
import com.nextlevellife.app.data.ProgramMath
import com.nextlevellife.app.data.ProgramSetResult
import com.nextlevellife.app.data.ProgramStep
import com.nextlevellife.app.data.ProgramSummary
import com.nextlevellife.app.data.Programs
import com.nextlevellife.app.data.SetInput
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.WorkoutResult
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.guide.Guides
import com.nextlevellife.app.pose.CountKind
import com.nextlevellife.app.pose.RepCounters
import com.nextlevellife.app.pose.RepSound
import com.nextlevellife.app.pose.rememberRepSound
import com.nextlevellife.app.stats.StatsText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min

private const val TAG = "ProgramScreen"

/** Пауза между сигналом «подход выполнен» и голосом дальше: иначе они сливаются. */
private const val PROGRAM_ANNOUNCE_DELAY_MS = 700L

// ---------------------------------------------------------------------------------------------
// Тексты экрана программы (без Compose): проверяются тестами
// ---------------------------------------------------------------------------------------------

internal object ProgramText {
    /** «3×10», у удержания — «3×20 с». */
    fun scheme(step: ProgramStep): String = "${step.sets}×${step.target}" + if (step.hold) " с" else ""

    /** «цель 10», у удержания — «цель 0:20». */
    fun goal(step: ProgramStep): String =
        "цель " + if (step.hold) ProgressMath.formatHold(step.targetMs) else step.target.toString()

    /** «Подход 2 из 3 · цель 10». */
    fun setLine(step: ProgramStep, setIndex: Int): String = "Подход ${setIndex + 1} из ${step.sets} · ${goal(step)}"

    /** «4 упражнения · 12 подходов · ≈20 мин». */
    fun summary(p: Program): String {
        val ex = p.steps.size
        val sets = p.totalSets
        return "$ex ${pluralRu(ex, "упражнение", "упражнения", "упражнений")} · " +
            "$sets ${pluralRu(sets, "подход", "подхода", "подходов")} · ≈${ProgramMath.estimateMinutes(p)} мин"
    }

    /** «10 раз», «20 секунд». */
    private fun amount(step: ProgramStep): String =
        if (step.hold) "${step.target} ${pluralRu(step.target, "секунда", "секунды", "секунд")}"
        else "${step.target} ${pluralRu(step.target, "раз", "раза", "раз")}"

    /**
     * Голос в начале подхода [slotIndex]: первый подход упражнения — с названием («Приседания. Подход 1 из 3,
     * 10 раз»), следующие — без («Подход 2 из 3, 10 раз»).
     */
    fun announceSet(p: Program, slotIndex: Int): String {
        val slot = p.slots[slotIndex]
        val step = p.step(slot)
        val set = "Подход ${slot.setIndex + 1} из ${step.sets}, ${amount(step)}"
        return if (slot.setIndex == 0) "${step.exercise.name}. $set" else set
    }

    /** Голос в начале отдыха перед подходом [nextSlotIndex]: «Отдых. Следующее: Отжимания, подход 1 из 3». */
    fun announceRest(p: Program, nextSlotIndex: Int): String {
        val slot = p.slots.getOrNull(nextSlotIndex) ?: return "Отдых"
        val step = p.step(slot)
        return "Отдых. Следующее: ${step.exercise.name}, подход ${slot.setIndex + 1} из ${step.sets}"
    }

    /** «Следующее: Отжимания · подход 1 из 3» — под таймером отдыха. */
    fun nextLine(p: Program, nextSlotIndex: Int): String? {
        val slot = p.slots.getOrNull(nextSlotIndex) ?: return null
        val step = p.step(slot)
        return "Следующее: ${step.exercise.name} · подход ${slot.setIndex + 1} из ${step.sets}"
    }

    /**
     * Подсказка про ракурс из гида упражнения: «Камера: 1,8–2,5 м · сбоку. Телефон сбоку на низкой подставке…».
     * null — гида нет.
     */
    fun cameraHint(exerciseId: String): String? =
        Guides.of(exerciseId)?.let { "${it.cameraLine}. ${it.camera}" }

    /** Табло отдыха: «0:45» (округление вверх — «0:00» не висит секунду). */
    fun restBoard(leftMs: Long): String = ProgressMath.formatHold(leftMs.coerceAtLeast(0L) + 999L)

    /** Итог прохождения: заголовок и текст. [strength] — строка про силу или null. */
    fun result(p: Program, s: ProgramSummary, strength: String?): Pair<String, String> {
        val title = if (s.completed) "Программа пройдена" else "Программа не завершена"
        val done = listOfNotNull(
            "Подходов: ${s.setsDone} из ${s.totalSets}",
            s.totalReps.takeIf { it > 0 }?.let { "$it ${complexRepsWord(it)}" },
            s.heldMs.takeIf { it > 0L }?.let { "${ProgressMath.formatHold(it)} в планке" },
        ).joinToString(" · ")
        val parts = ArrayList<String>()
        parts += "$done."
        parts += "+${formatShort(s.earnedMs)} экранного времени."
        strength?.let { parts += it.replaceFirstChar { c -> c.uppercase() } + "." }
        if (!s.completed) {
            val missed = s.totalSets - s.goalSets
            parts += "Цель не достигнута в $missed ${pluralRu(missed, "подходе", "подходах", "подходах")}."
        }
        return title to parts.joinToString(" ")
    }
}

// ---------------------------------------------------------------------------------------------
// Состояние прохождения: переживает поворот и раскрытие Fold
// ---------------------------------------------------------------------------------------------

/** Подход ещё не делался. */
private const val SET_NONE = 0

/** Подход записан и оплачен. */
private const val SET_RECORDED = 1

/** Запись не удалась: повторяется при завершении (тем же ключом — без двойной оплаты). */
private const val SET_FAILED = 2

/** Пропущен без засчитанной работы. */
private const val SET_SKIPPED = 3

internal class ProgramRunState(val program: Program) {
    private val n = program.slots.size
    var weight by mutableIntStateOf(Weights.DEFAULT)
    var startedAt by mutableLongStateOf(0L)

    /** Текущий подход (индекс в [Program.slots]); во время отдыха — следующий. */
    var slot by mutableIntStateOf(0)
    var resting by mutableStateOf(false)

    /** Начало текущего подхода или отдыха (elapsedRealtime). */
    var phaseStartedAt by mutableLongStateOf(0L)
    var restMs by mutableLongStateOf(0L)

    /** Засчитанное в текущем подходе: повторения и удержание. */
    var count by mutableIntStateOf(0)
    var liveHeldMs by mutableLongStateOf(0L)

    /** По подходам; массивы меняются на месте, экран узнаёт об этом по [revision]. */
    var reps = IntArray(n)
    var held = LongArray(n)
    var earned = LongArray(n)
    var durations = LongArray(n)
    var status = IntArray(n)
    var revision by mutableIntStateOf(0)

    /** Сила: суммарный прирост за записи программы и последнее «сегодня» (−1 — не известно). */
    var strengthGain by mutableIntStateOf(0)
    var strengthToday by mutableIntStateOf(-1)

    var finished by mutableStateOf(false)
    var finishedElapsed by mutableLongStateOf(0L)
    var resultTitle by mutableStateOf("")
    var resultText by mutableStateOf("")
    var retry by mutableStateOf(false)
    var confirmQuit by mutableStateOf(false)

    /** Открыт показ техники этого упражнения ("" — закрыт): счёт на паузе. */
    var guideFor by mutableStateOf("")

    val started get() = startedAt != 0L
    val running get() = started && !finished
    val step: ProgramStep get() = program.step(program.slots[slot])
    val setIndex: Int get() = program.slots[slot].setIndex

    /** Записанные подходы для итога. */
    fun results(): List<ProgramSetResult> = program.slots.indices.mapNotNull { i ->
        if (status[i] != SET_RECORDED) return@mapNotNull null
        val s = program.slots[i]
        ProgramSetResult(s.stepIndex, s.setIndex, reps[i], held[i], earned[i])
    }

    val earnedTotal: Long get() = earned.indices.sumOf { if (status[it] == SET_RECORDED) earned[it] else 0L }

    companion object {
        fun saver(program: Program) = listSaver<ProgramRunState, Any>(
            save = {
                listOf(
                    it.weight, it.startedAt, it.slot, it.resting, it.phaseStartedAt, it.restMs, it.count, it.liveHeldMs,
                    it.reps.copyOf(), it.held.copyOf(), it.earned.copyOf(), it.durations.copyOf(), it.status.copyOf(),
                    it.strengthGain, it.strengthToday, it.finished, it.finishedElapsed, it.resultTitle, it.resultText,
                    it.retry, it.confirmQuit, it.guideFor,
                )
            },
            restore = { v ->
                ProgramRunState(program).apply {
                    weight = v[0] as Int
                    startedAt = v[1] as Long
                    slot = v[2] as Int
                    resting = v[3] as Boolean
                    phaseStartedAt = v[4] as Long
                    restMs = v[5] as Long
                    count = v[6] as Int
                    liveHeldMs = v[7] as Long
                    // Под число подходов программы: состав мог измениться (защита; свою программу
                    // экран восстанавливает по снимку состава, так что обычно размеры совпадают).
                    val n = program.slots.size
                    reps = (v[8] as IntArray).copyOf(n)
                    held = (v[9] as LongArray).copyOf(n)
                    earned = (v[10] as LongArray).copyOf(n)
                    durations = (v[11] as LongArray).copyOf(n)
                    status = (v[12] as IntArray).copyOf(n)
                    strengthGain = v[13] as Int
                    strengthToday = v[14] as Int
                    finished = v[15] as Boolean
                    finishedElapsed = v[16] as Long
                    resultTitle = v[17] as String
                    resultText = v[18] as String
                    retry = v[19] as Boolean
                    confirmQuit = v[20] as Boolean
                    guideFor = v[21] as String
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Экран
// ---------------------------------------------------------------------------------------------

/**
 * Домашняя программа ([Programs]): перед стартом — состав, длительность, гиря; затем подход за подходом —
 * камера со скелетом и счётчиком (повторения — [CameraRepPanel], планка — [CameraHoldPanel]). Цель набрана —
 * подход сразу записывается обычным подходом упражнения ([Workouts.recordSets] / [Workouts.recordHold], ключ
 * `program:<session>:<step>:<set>`), затем отдых с голосом. «Закончить подход» — записать сделанное и отдыхать,
 * «Пропустить подход» — дальше без отдыха (засчитанное записывается), «Закончить» — итог и прохождение
 * в complex_runs ([Workouts.recordProgram]) без бонуса. Пока открыт гид или идёт отдых, счёт не ведётся.
 * [onEdit] — у своей программы: «Изменить» перед стартом открывает редактор.
 *
 * Своя программа может измениться или исчезнуть, пока экран пересоздаётся (правка, восстановление копии),
 * поэтому при старте её состав запоминается снимком ([CustomProgramMath.encodeOne]) в сохраняемом состоянии,
 * и начатое прохождение доводится и записывается по снимку, даже если программы в каталоге уже нет.
 */
@Composable
fun ProgramScreen(programId: String, onDone: () -> Unit, onEdit: (() -> Unit)? = null) {
    // Снимок состава своей программы с момента старта ("" — не начата или готовая программа).
    var snapshot by rememberSaveable(programId) { mutableStateOf("") }
    val program = remember(programId, snapshot) {
        snapshot.takeIf { it.isNotEmpty() }?.let { CustomProgramMath.decodeOne(it) } ?: Programs.byId(programId)
    }
    if (program == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val s = rememberSaveable(programId, saver = ProgramRunState.saver(program)) { ProgramRunState(program) }
    // Одно прохождение — один идентификатор: ключи подходов и прохождения строятся из него.
    val sessionId = rememberSaveable(programId) { UUID.randomUUID().toString() }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var announceJob by remember { mutableStateOf<Job?>(null) }

    fun announce(text: String, delayMs: Long, interrupt: Boolean = true) {
        announceJob?.cancel()
        announceJob = scope.launch {
            delay(delayMs)
            sound.say(text, interrupt)
        }
    }

    /** Записать подход [i] (повтор после сбоя — тем же ключом). */
    fun recordSlot(i: Int): Boolean {
        val slot = program.slots[i]
        val step = program.step(slot)
        val key = ProgramMath.setSessionId(sessionId, slot.stepIndex, slot.setIndex)
        val r = try {
            if (step.hold) {
                Workouts.recordHold(step.exercise, s.held[i], s.durations[i], key)
            } else {
                Workouts.recordSets(listOf(SetInput(step.exercise, s.weight, s.reps[i], s.durations[i])), key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать подход программы", e)
            WorkoutResult(0L, 0L, saved = false)
        }
        if (r.saved) {
            s.earned[i] = r.earnedMs
            s.status[i] = SET_RECORDED
            r.strengthGain?.let { s.strengthGain += it }
            r.strengthToday?.let { s.strengthToday = it }
        } else {
            s.status[i] = SET_FAILED
        }
        s.revision++
        return r.saved
    }

    /** Закрыть текущий подход: засчитанное (не больше цели) записывается сразу. */
    fun closeSet() {
        val i = s.slot
        val step = s.step
        s.durations[i] = (SystemClock.elapsedRealtime() - s.phaseStartedAt).coerceAtLeast(0L)
        s.reps[i] = if (step.hold) 0 else ProgramMath.cappedReps(step, s.count)
        s.held[i] = if (step.hold) ProgramMath.cappedHeldMs(step, s.liveHeldMs) else 0L
        if (s.reps[i] > 0 || s.held[i] > 0L) {
            recordSlot(i)
        } else {
            s.status[i] = SET_SKIPPED
            s.revision++
        }
    }

    /** Итог: ещё раз записать несохранённые подходы, затем прохождение. */
    fun recordRun() {
        program.slots.indices.filter { s.status[it] == SET_FAILED }.forEach { recordSlot(it) }
        val failed = s.status.count { it == SET_FAILED }
        val summary = ProgramMath.summary(program, s.results())
        val r = try {
            Workouts.recordProgram(program, summary, s.weight, s.finishedElapsed, ProgramMath.runSessionId(sessionId))
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать прохождение программы", e)
            WorkoutResult(0L, 0L, saved = false)
        }
        if (failed > 0 || !r.saved) {
            s.retry = true
            s.resultTitle = "Не удалось записать"
            s.resultText = if (failed > 0) {
                "Не записано подходов: $failed — время за них не начислено. Записанные подходы сохранены: " +
                    "+${formatShort(summary.earnedMs)}. Можно попробовать ещё раз."
            } else {
                "Подходы записаны (+${formatShort(summary.earnedMs)}), но прохождение не сохранилось. " +
                    "Можно попробовать ещё раз."
            }
            return
        }
        s.retry = false
        val strength = StatsText.strengthResult(s.strengthGain, s.strengthToday.takeIf { it >= 0 })
        val (title, text) = ProgramText.result(program, summary, strength)
        s.resultTitle = title
        s.resultText = text
        announce(title, PROGRAM_ANNOUNCE_DELAY_MS, interrupt = false)
    }

    fun finish() {
        if (s.finished) return
        val t = SystemClock.elapsedRealtime()
        // Начатый подход с засчитанной работой не теряется.
        if (!s.resting && s.slot in program.slots.indices && s.status[s.slot] == SET_NONE && (s.count > 0 || s.liveHeldMs > 0L)) {
            closeSet()
        }
        s.finished = true
        s.finishedElapsed = (t - s.startedAt).coerceAtLeast(0L)
        s.confirmQuit = false
        s.guideFor = ""
        announceJob?.cancel()
        if (s.status.none { it == SET_RECORDED || it == SET_FAILED }) {
            onDone()
            return
        }
        haptics.strong()
        sound.done()
        recordRun()
    }

    /** К следующему подходу: с отдыхом ([withRest]) или сразу. Последний подход — итог. */
    fun goNext(withRest: Boolean, afterGoal: Boolean) {
        val i = s.slot
        if (i >= program.slots.lastIndex) {
            finish()
            return
        }
        val t = SystemClock.elapsedRealtime()
        s.slot = i + 1
        s.count = 0
        s.liveHeldMs = 0L
        s.phaseStartedAt = t
        now = t
        val rest = if (withRest) ProgramMath.restAfterMs(program, i) else 0L
        val lead = if (afterGoal) "Готово. " else ""
        if (rest > 0L) {
            s.resting = true
            s.restMs = rest
            announce(lead + ProgramText.announceRest(program, i + 1), PROGRAM_ANNOUNCE_DELAY_MS)
        } else {
            s.resting = false
            announce(lead + ProgramText.announceSet(program, i + 1), PROGRAM_ANNOUNCE_DELAY_MS)
        }
    }

    /** Цель подхода набрана (или «Закончить подход»): записать и на отдых. */
    fun completeSet(goal: Boolean) {
        if (!s.running || s.resting || s.status[s.slot] != SET_NONE) return
        closeSet()
        haptics.strong()
        sound.done()
        goNext(withRest = true, afterGoal = goal)
    }

    fun skipSet() {
        if (!s.running || s.resting) return
        if (s.status[s.slot] == SET_NONE) closeSet()
        haptics.tap()
        goNext(withRest = false, afterGoal = false)
    }

    fun endRest() {
        if (!s.running || !s.resting) return
        val t = SystemClock.elapsedRealtime()
        s.resting = false
        s.phaseStartedAt = t
        now = t
        announce("Начали", 0L)
    }

    val onRep: () -> Unit = onRep@{
        if (!s.running || s.resting || s.confirmQuit || s.guideFor.isNotEmpty()) return@onRep
        val step = s.step
        if (s.count >= step.target) return@onRep
        s.count++
        if (s.count >= step.target) completeSet(goal = true)
    }
    val onHeld by rememberUpdatedState<(Long) -> Unit> { ms ->
        if (s.running && !s.resting && s.step.hold) {
            s.liveHeldMs = ms
            if (ms >= s.step.targetMs) completeSet(goal = true)
        }
    }

    // Часы: отдых идёт по времени; отсчёт «три, два, один» в конце. На диалоге и в гиде отдых не кончается сам.
    LaunchedEffect(s.running) {
        if (!s.running) return@LaunchedEffect
        var spoken = -1L
        while (true) {
            val t = SystemClock.elapsedRealtime()
            now = t
            if (s.resting && !s.confirmQuit && s.guideFor.isEmpty()) {
                val left = s.restMs - (t - s.phaseStartedAt)
                if (left <= 0L) {
                    spoken = -1L
                    endRest()
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

    // Защита: подход вне состава программы (не должно случаться — своя программа идёт по снимку) —
    // не падать, а закончить прохождение и записать итог по уже записанным подходам.
    val slotOk = s.slot in program.slots.indices
    if (s.started && !slotOk) {
        LaunchedEffect(Unit) {
            Log.e(TAG, "Подход ${s.slot} вне программы ${program.id} (${program.slots.size} подходов)")
            finish()
        }
    }

    if (!s.started) {
        ProgramPrep(program, weight = s.weight, onWeight = { s.weight = it }, onBack = onDone, onEdit = onEdit, onStart = {
            haptics.tap()
            // Своя программа: дальше прохождение идёт по этому составу, что бы ни случилось с каталогом.
            if (CustomProgramMath.isCustomId(program.id)) snapshot = CustomProgramMath.encodeOne(program)
            val t = SystemClock.elapsedRealtime()
            s.slot = 0
            s.startedAt = t
            s.phaseStartedAt = t
            now = t
            announce(ProgramText.announceSet(program, 0), 300L)
        })
    } else if (!slotOk) {
        Box(Modifier.fillMaxSize())
    } else {
        ProgramRun(
            s = s,
            nowMs = now,
            sound = sound,
            cameraActive = s.running && !s.resting && !s.confirmQuit && s.guideFor.isEmpty(),
            onRep = onRep,
            onHeld = { onHeld(it) },
            onFinishSet = { completeSet(goal = false) },
            onSkipSet = { skipSet() },
            onSkipRest = { endRest() },
            onQuit = { s.confirmQuit = true },
            onGuide = { s.guideFor = it },
        )
    }

    if (s.guideFor.isNotEmpty() && s.running) {
        GuideDialog(s.guideFor, onClose = { s.guideFor = "" })
    }

    if (s.confirmQuit && s.running) {
        AlertDialog(
            onDismissRequest = { s.confirmQuit = false },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Закончить", color = Bad) } },
            dismissButton = { TextButton(onClick = { s.confirmQuit = false }) { Text("Продолжить") } },
            title = { Text("Закончить программу?") },
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
                    TextButton(onClick = onDone) { Text("Готово") }
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

// ---------- Подготовка ----------

@Composable
private fun ProgramPrep(
    program: Program,
    weight: Int,
    onWeight: (Int) -> Unit,
    onBack: () -> Unit,
    onEdit: (() -> Unit)?,
    onStart: () -> Unit,
) {
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
                if (onEdit == null) {
                    Text(program.title, style = MaterialTheme.typography.headlineMedium, color = Accent)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            program.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Accent,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onEdit) { Text("Изменить", style = MaterialTheme.typography.titleMedium) }
                    }
                }
                Text(
                    ProgramText.summary(program) + " · до +${formatShort(ProgramMath.maxRewardMs(program, weight))}",
                    style = MaterialTheme.typography.titleSmall.merge(Tabular),
                    color = muted,
                )
                Text(program.subtitle, style = MaterialTheme.typography.bodyLarge)
                if (program.kettlebell) {
                    Text("Вес гири", style = MaterialTheme.typography.titleMedium)
                    ProgramWeightChips(weight, onWeight)
                    Text(
                        "Камера гирю не видит: цена повторений — по выбранному весу, как в отдельных упражнениях.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                Card(colors = CardDefaults.cardColors(containerColor = RoutineCardColor)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Каждый подход засчитывает камера и сразу оплачивает по обычной цене упражнения. " +
                                "Цель набрана — подход записан, начинается отдых. Бонуса за прохождение нет.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text("Состав", style = MaterialTheme.typography.titleMedium)
                program.steps.forEachIndexed { i, step ->
                    Column {
                        Text(
                            "${i + 1}. ${step.exercise.name} — ${ProgramText.scheme(step)} · отдых ${step.restSeconds} с",
                            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                        )
                        ProgramText.cameraHint(step.exercise.id)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                Text(
                    "Длительность примерная: перестановка телефона и потеря распознавания её увеличивают.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
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

@Composable
private fun ProgramWeightChips(weight: Int, onWeight: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Weights.all.forEach { w ->
            FilterChip(
                selected = weight == w,
                onClick = { onWeight(w) },
                label = {
                    Text(
                        "$w кг",
                        style = MaterialTheme.typography.titleSmall.merge(Tabular),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------- Выполнение ----------

@Composable
private fun ProgramRun(
    s: ProgramRunState,
    nowMs: Long,
    sound: RepSound,
    cameraActive: Boolean,
    onRep: () -> Unit,
    onHeld: (Long) -> Unit,
    onFinishSet: () -> Unit,
    onSkipSet: () -> Unit,
    onSkipRest: () -> Unit,
    onQuit: () -> Unit,
    onGuide: (String) -> Unit,
) {
    val widthClass = currentWidthClass()
    // Выбор камеры и признак счёта — до ветвления раскладки: переживают смену класса ширины и пересоздание.
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    var counting by remember { mutableStateOf(false) }
    val step = s.step
    val restLeft = if (s.resting) s.restMs - (nowMs - s.phaseStartedAt) else 0L

    // Счётчик удержания — один на подход, выше раскладки: смена позы Fold его не пересоздаёт.
    // Засчитанное до пересоздания Activity ([ProgramRunState.liveHeldMs]) прибавляется к новому движку.
    val tracker = remember(s.slot) {
        if (!step.hold) null
        else RepCounters.forExercise(step.exercise.id)?.takeIf { it.kind == CountKind.HOLD }?.let { HoldTracker(it, s.liveHeldMs) }
    }
    DisposableEffect(tracker) { onDispose { tracker?.counter?.reset() } }
    val currentOnHeld by rememberUpdatedState(onHeld)
    LaunchedEffect(tracker) {
        if (tracker != null) snapshotFlow { tracker.board.creditedMs }.collect { currentOnHeld(it) }
    }

    val tabletop = rememberTabletop()
    val scoreInPanel = tabletop != null || widthClass == WidthClass.EXPANDED

    val camera: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            if (step.hold) {
                CameraHoldPanel(
                    exercise = step.exercise,
                    tracker = tracker,
                    active = cameraActive,
                    sound = sound,
                    frontCamera = frontCamera,
                    onFrontCameraChange = { frontCamera = it },
                    modifier = Modifier.fillMaxSize(),
                    showScore = !scoreInPanel && !s.resting,
                )
            } else {
                CameraRepPanel(
                    exercise = step.exercise,
                    count = s.count,
                    target = step.target,
                    sessionKey = s.slot,
                    active = cameraActive,
                    sound = sound,
                    frontCamera = frontCamera,
                    onFrontCameraChange = { frontCamera = it },
                    modifier = Modifier.fillMaxSize(),
                    showCount = !scoreInPanel && !s.resting,
                    onCountingChange = { counting = it },
                    onRep = onRep,
                )
            }
            // Отдых — таймер поверх камеры (на узком экране); превью остаётся, чтобы поставить телефон.
            if (s.resting && !scoreInPanel) {
                RestBoard(
                    s, restLeft, onCamera = true,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }

    val score: @Composable (Modifier) -> Unit = { mod ->
        when {
            s.resting -> Box(mod, contentAlignment = Alignment.Center) {
                RestBoard(s, restLeft, onCamera = false, modifier = Modifier.fillMaxWidth())
            }
            tracker != null -> HoldScore(tracker, onCamera = false, modifier = mod)
            step.hold -> Spacer(mod)
            else -> FittedRepCounter(count = s.count, target = step.target, counting = counting, onCamera = false, modifier = mod)
        }
    }

    val panelWidth = widthClass.workoutPanelWidth
    if (tabletop != null) {
        TabletopSplit(tabletop, widthClass, top = camera) {
            TabletopBoard(
                info = { mod ->
                    Column(mod.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SetHeader(s, onGuide, compact = true)
                    }
                },
                score = score,
                actions = { stacked ->
                    RunControls(s, stacked, onFinishSet = onFinishSet, onSkipSet = onSkipSet, onSkipRest = onSkipRest, onQuit = onQuit)
                },
            )
        }
    } else if (panelWidth == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(widthClass.outerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetHeader(s, onGuide, compact = true)
            camera(Modifier.weight(1f).fillMaxWidth())
            RunControls(s, stacked = false, onFinishSet = onFinishSet, onSkipSet = onSkipSet, onSkipRest = onSkipRest, onQuit = onQuit)
        }
    } else {
        // Fold и планшет: слева подход и табло, кнопки закреплены внизу панели.
        WorkoutSideLayout(widthClass, panelWidth, camera) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SetHeader(s, onGuide, compact = false)
                if (scoreInPanel) score(Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
            Spacer(Modifier.height(12.dp))
            RunControls(s, stacked = true, onFinishSet = onFinishSet, onSkipSet = onSkipSet, onSkipRest = onSkipRest, onQuit = onQuit)
        }
    }
}

/**
 * Заголовок: программа и прогресс по подходам, упражнение, «Подход 2 из 3 · цель 10», заработано.
 * Перед новым упражнением (первый подход или отдых перед ним) — ракурс камеры из гида. ▶ — показ техники.
 */
@Composable
private fun SetHeader(s: ProgramRunState, onGuide: (String) -> Unit, compact: Boolean) {
    val program = s.program
    val step = s.step
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Suppress("UNUSED_VARIABLE") val rev = s.revision // перерисовать после записи подхода
    val doneSets = s.slot
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${program.title} · заработано +${formatShort(s.earnedTotal)}",
                color = muted,
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (Guides.of(step.exercise.id) != null) GuideButton(onClick = { onGuide(step.exercise.id) })
        }
        LinearProgressIndicator(
            progress = { (doneSets.toFloat() / program.totalSets).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            if (s.resting) "Отдых" else step.exercise.name + if (step.exercise.kettlebell) " · ${s.weight} кг" else "",
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val lines = ArrayList<String>()
        if (s.resting) {
            ProgramText.nextLine(program, s.slot)?.let { lines += it }
        } else {
            lines += ProgramText.setLine(step, s.setIndex)
        }
        // Ракурс — перед новым упражнением: на отдыхе перед ним и в его первом подходе.
        if (s.setIndex == 0) ProgramText.cameraHint(step.exercise.id)?.let { lines += it }
        Text(
            lines.joinToString("\n"),
            color = muted,
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            maxLines = if (compact) 4 else 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RunControls(
    s: ProgramRunState,
    stacked: Boolean,
    onFinishSet: () -> Unit,
    onSkipSet: () -> Unit,
    onSkipRest: () -> Unit,
    onQuit: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val label = MaterialTheme.typography.titleMedium
    val hasWork = s.count > 0 || s.liveHeldMs > 0L
    val main: @Composable (Modifier) -> Unit = { mod ->
        when {
            s.resting -> OutlinedButton(
                onClick = onSkipRest, enabled = s.running, shape = ButtonShape, contentPadding = padding,
                modifier = mod.heightIn(min = MainButtonHeight),
            ) { Text("Пропустить отдых", style = label, textAlign = TextAlign.Center) }
            hasWork -> Button(
                onClick = onFinishSet, enabled = s.running, shape = ButtonShape, contentPadding = padding,
                modifier = mod.heightIn(min = MainButtonHeight),
            ) { Text("Закончить подход", style = label, textAlign = TextAlign.Center) }
            else -> OutlinedButton(
                onClick = onSkipSet, enabled = s.running, shape = ButtonShape, contentPadding = padding,
                modifier = mod.heightIn(min = MainButtonHeight),
            ) { Text("Пропустить подход", style = label, textAlign = TextAlign.Center) }
        }
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
            main(Modifier.fillMaxWidth())
            quit(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            main(Modifier.weight(3f))
            quit(Modifier.weight(2f))
        }
    }
}

/** Таймер отдыха: «0:45», полоса, «Следующее: …». */
@Composable
private fun RestBoard(s: ProgramRunState, leftMs: Long, onCamera: Boolean, modifier: Modifier = Modifier) {
    val text = ProgramText.restBoard(leftMs)
    val progress = if (s.restMs > 0L) (1f - leftMs.toFloat() / s.restMs).coerceIn(0f, 1f) else 1f
    val fg = if (onCamera) Color.White else MaterialTheme.colorScheme.onBackground
    val muted = if (onCamera) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = LocalDensity.current.fontScale
    val base = if (onCamera) Modifier.background(CameraScrim, RoundedCornerShape(24.dp)) else Modifier
    Column(
        modifier.then(base).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Отдых", color = muted, style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Моноширинные цифры ≈ 0,62 высоты шрифта в ширину.
            val sp = min(maxWidth.value / (text.length * 0.62f) / fontScale, 96f).toInt().coerceAtLeast(28)
            ScoreText(text, sizeSp = sp, color = fg, textAlign = TextAlign.Center)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = if (onCamera) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        )
        ProgramText.nextLine(s.program, s.slot)?.let {
            Text(it, color = muted, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}
