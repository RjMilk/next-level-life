package com.nextlevellife.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.pose.rememberRepSound
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ComplexScreen"

/** Пауза между сигналом «шаг выполнен» и голосовым объявлением следующего: иначе они сливаются. */
private const val ANNOUNCE_DELAY_MS = 700L

/** Синди, завершённая кнопкой за эти последние секунды, засчитывается как пройденная по времени. */
private const val CINDY_TOLERANCE_MS = 5_000L

/** Голосовые отсечки Синди: остаток времени и фраза. Индекс — бит во флагах объявлений. */
private val cindyCallouts = listOf(
    10 * 60_000L to "Осталось 10 минут",
    5 * 60_000L to "Осталось 5 минут",
    60_000L to "Осталась 1 минута",
    10_000L to "10 секунд",
)

internal enum class ComplexKind { MINOTAUR, CINDY }

/** Шаг комплекса: [part] — номер упражнения внутри круга/ступени, он же индекс в [ComplexKind.exercises]. */
internal data class ComplexStep(val round: Int, val part: Int, val exercise: Exercise, val reps: Int)

internal val ComplexKind.exercises: List<Exercise>
    get() = when (this) {
        ComplexKind.MINOTAUR -> Minotaur.exercises
        ComplexKind.CINDY -> Cindy.round.map { it.exercise }
    }

internal fun ComplexKind.step(i: Int): ComplexStep = when (this) {
    ComplexKind.MINOTAUR -> {
        val s = Minotaur.steps[i]
        ComplexStep(s.round, i % Minotaur.exercises.size, s.exercise, s.reps)
    }
    // Синди — бесконечный цикл по кругу, пока не выйдет время.
    ComplexKind.CINDY -> {
        val part = i % Cindy.round.size
        ComplexStep(i / Cindy.round.size + 1, part, Cindy.round[part].exercise, Cindy.round[part].reps)
    }
}

internal fun ComplexKind.nextStep(i: Int): ComplexStep? =
    if (this == ComplexKind.MINOTAUR && i >= Minotaur.steps.lastIndex) null else step(i + 1)

/** Всё, что должно пережить поворот и раскрытие Fold. */
internal class ComplexRunState(val kind: ComplexKind) {
    var weight by mutableIntStateOf(Weights.DEFAULT)
    var startedAt by mutableLongStateOf(0L)
    var stepIndex by mutableIntStateOf(0)
    var stepCount by mutableIntStateOf(0)
    /** Засчитанные повторения по индексу упражнения комплекса. Массив заменяется целиком, чтобы Compose видел изменение. */
    var repsByEx by mutableStateOf(IntArray(kind.exercises.size))
    var skipped by mutableStateOf(false)
    /** Полные круги Синди — только без пропусков: каждый ровно [Cindy.repsPerRound] повторений. */
    var fullRounds by mutableIntStateOf(0)
    var roundSkipped by mutableStateOf(false)
    var calloutFlags by mutableIntStateOf(0)
    var finished by mutableStateOf(false)
    var finishedElapsed by mutableLongStateOf(0L)
    var resultTitle by mutableStateOf("")
    var resultText by mutableStateOf("")
    var confirmSkip by mutableStateOf(false)
    var confirmQuit by mutableStateOf(false)

    val started get() = startedAt != 0L
    val running get() = started && !finished
    val totalCounted get() = repsByEx.sum()

    companion object {
        fun saver(kind: ComplexKind) = listSaver<ComplexRunState, Any>(
            save = {
                listOf(
                    it.weight, it.startedAt, it.stepIndex, it.stepCount, it.repsByEx.copyOf(),
                    it.skipped, it.fullRounds, it.roundSkipped, it.calloutFlags,
                    it.finished, it.finishedElapsed, it.resultTitle, it.resultText,
                    it.confirmSkip, it.confirmQuit,
                )
            },
            restore = { v ->
                ComplexRunState(kind).apply {
                    weight = v[0] as Int
                    startedAt = v[1] as Long
                    stepIndex = v[2] as Int
                    stepCount = v[3] as Int
                    repsByEx = v[4] as IntArray
                    skipped = v[5] as Boolean
                    fullRounds = v[6] as Int
                    roundSkipped = v[7] as Boolean
                    calloutFlags = v[8] as Int
                    finished = v[9] as Boolean
                    finishedElapsed = v[10] as Long
                    resultTitle = v[11] as String
                    resultText = v[12] as String
                    confirmSkip = v[13] as Boolean
                    confirmQuit = v[14] as Boolean
                }
            },
        )
    }
}

@Composable
fun ComplexScreen(complexId: String, onDone: () -> Unit) {
    val kind = if (complexId == Cindy.ID) ComplexKind.CINDY else ComplexKind.MINOTAUR
    val s = rememberSaveable(kind, saver = ComplexRunState.saver(kind)) { ComplexRunState(kind) }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var announceJob by remember { mutableStateOf<Job?>(null) }

    fun announce(text: String, delayMs: Long, interrupt: Boolean = true) {
        announceJob?.cancel()
        announceJob = scope.launch {
            delay(delayMs)
            sound.say(text, interrupt)
        }
    }

    fun stepAnnouncement(i: Int): String {
        val st = kind.step(i)
        val base = "${st.exercise.name}, ${st.reps}"
        if (i == 0 || kind.step(i - 1).round == st.round) return base
        return when (kind) {
            ComplexKind.MINOTAUR -> "Ступень по ${st.reps}. $base"
            ComplexKind.CINDY -> "Круг ${st.round}. $base"
        }
    }

    fun showResult(title: String, text: String) {
        s.resultText = text
        s.resultTitle = title
        // Человек далеко от экрана: итог тоже должен быть слышен.
        announce(title, ANNOUNCE_DELAY_MS, interrupt = false)
    }

    /** Незавершённый комплекс: каждое упражнение пишется обычным подходом по обычной цене. */
    fun finishIncomplete(note: String? = null) {
        val weight = if (kind == ComplexKind.MINOTAUR) s.weight else Weights.DEFAULT
        var earned = 0L
        var xp = 0L
        var total = 0
        var failed = 0
        kind.exercises.forEachIndexed { i, ex ->
            val n = s.repsByEx[i]
            if (n > 0) {
                // Сбой одного подхода не должен отменять запись остальных.
                try {
                    val r = Workouts.recordSet(ex, weight, n, 0L)
                    earned += r.earnedMs
                    xp += r.xp
                    total += n
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось записать подход ${ex.id}", e)
                    failed += n
                }
            }
        }
        if (total == 0 && failed == 0) {
            done()
            return
        }
        val title = if (kind == ComplexKind.MINOTAUR) "Комплекс не завершён" else "Синди не завершена"
        val text = listOfNotNull(
            note,
            "Засчитано $total ${complexRepsWord(total)}: +${formatShort(earned)} экранного времени, +$xp опыта.",
            if (failed > 0) "Не удалось записать $failed ${complexRepsWord(failed)}." else null,
        ).joinToString(" ")
        showResult(title, text)
    }

    fun beginFinish(): Long? {
        if (s.finished) return null
        val elapsed = (SystemClock.elapsedRealtime() - s.startedAt).coerceAtLeast(0L)
        s.finished = true
        s.finishedElapsed = elapsed
        s.confirmSkip = false
        s.confirmQuit = false
        announceJob?.cancel()
        return elapsed
    }

    fun finishMinotaur() {
        val elapsed = beginFinish() ?: return
        if (s.skipped) {
            finishIncomplete()
            return
        }
        val r = Workouts.recordMinotaur(s.weight, elapsed)
        if (r.earnedMs == 0L) {
            finishIncomplete("Быстрее ${formatDuration(Minotaur.MIN_DURATION_MS)} комплекс целиком не засчитывается.")
            return
        }
        val title = if (r.isRecord) "Новый рекорд!" else "Минотавр повержен"
        var text = "Время ${formatDuration(elapsed)}. +${formatShort(r.earnedMs)} экранного времени, +${r.xp} опыта."
        if (r.isRecord && r.previousBestMs != null) {
            text += " На ${formatDuration(r.previousBestMs - elapsed)} быстрее прежнего рекорда."
        }
        showResult(title, text)
    }

    fun finishCindyByTime() {
        if (beginFinish() == null) return
        s.finishedElapsed = Cindy.DURATION_MS
        sound.say("Время!")
        haptics.strong()
        val reps = kind.exercises.mapIndexed { i, ex -> ex to s.repsByEx[i] }.toMap()
        // Полные круги — только без пропусков; всё остальное засчитанное — повторения сверх,
        // чтобы rounds * repsPerRound + extra совпадало с оплаченными повторениями.
        val rounds = s.fullRounds
        val extra = (s.totalCounted - rounds * Cindy.repsPerRound).coerceAtLeast(0)
        val r = Workouts.recordCindy(reps, rounds, extra, Cindy.DURATION_MS)
        if (r.earnedMs == 0L && s.totalCounted == 0) {
            done()
            return
        }
        val title = if (r.isRecord) "Новый рекорд!" else "Синди завершена"
        var text = "$rounds ${complexRoundsWord(rounds)} + $extra ${complexRepsWord(extra)}. " +
            "+${formatShort(r.earnedMs)} экранного времени, +${r.xp} опыта."
        if (r.isRecord) {
            r.previousBestRounds?.let { (pr, pe) -> text += " Прежний рекорд: $pr + $pe." }
        }
        showResult(title, text)
    }

    fun finishEarly() {
        if (kind == ComplexKind.CINDY &&
            SystemClock.elapsedRealtime() - s.startedAt >= Cindy.DURATION_MS - CINDY_TOLERANCE_MS
        ) {
            finishCindyByTime()
            return
        }
        if (beginFinish() == null) return
        finishIncomplete()
    }

    fun advance(delayMs: Long) {
        val st = kind.step(s.stepIndex)
        if (kind.nextStep(s.stepIndex) == null) {
            finishMinotaur()
            return
        }
        if (kind == ComplexKind.CINDY && st.part == Cindy.round.lastIndex) {
            // Круг с пропуском не полный: его повторения уходят в «сверх», а не в счёт кругов.
            if (!s.roundSkipped) s.fullRounds++
            s.roundSkipped = false
        }
        s.stepIndex++
        s.stepCount = 0
        announce(stepAnnouncement(s.stepIndex), delayMs)
    }

    val onRep: () -> Unit = onRep@{
        if (!s.running) return@onRep
        // Часы опрашиваются раз в 200 мс: повторение после 20:00 не должно попасть в результат.
        if (kind == ComplexKind.CINDY &&
            SystemClock.elapsedRealtime() - s.startedAt >= Cindy.DURATION_MS
        ) {
            finishCindyByTime()
            return@onRep
        }
        if (s.confirmSkip || s.confirmQuit) return@onRep
        val st = kind.step(s.stepIndex)
        if (s.stepCount >= st.reps) return@onRep
        s.stepCount++
        s.repsByEx = s.repsByEx.copyOf().also { it[st.part]++ }
        if (s.stepCount >= st.reps) {
            sound.done()
            haptics.strong()
            advance(ANNOUNCE_DELAY_MS)
        }
    }

    // Часы и отсечки Синди. Время идёт и при открытом диалоге: 20 минут — это 20 минут.
    LaunchedEffect(s.running) {
        if (!s.running) return@LaunchedEffect
        while (true) {
            val t = SystemClock.elapsedRealtime()
            now = t
            if (kind == ComplexKind.CINDY) {
                val left = Cindy.DURATION_MS - (t - s.startedAt)
                if (left <= 0L) {
                    finishCindyByTime()
                    break
                }
                var toSay: String? = null
                cindyCallouts.forEachIndexed { i, (threshold, phrase) ->
                    val bit = 1 shl i
                    if (left <= threshold && s.calloutFlags and bit == 0) {
                        s.calloutFlags = s.calloutFlags or bit
                        // После восстановления давно прошедшие отсечки отмечаются молча.
                        toSay = if (threshold - left < 3_000L) phrase else null
                    }
                }
                // Не перебиваем объявление упражнения: отсечка встаёт в очередь.
                toSay?.let { sound.say(it, interrupt = false) }
            }
            delay(200)
        }
    }

    if (s.running) {
        KeepScreenOn()
        BackHandler { s.confirmQuit = true }
    }

    if (!s.started) {
        ComplexPrep(
            kind = kind,
            weight = s.weight,
            onWeight = { s.weight = it },
            onBack = onDone,
            onStart = {
                haptics.tap()
                val t = SystemClock.elapsedRealtime()
                s.stepIndex = 0
                s.stepCount = 0
                s.startedAt = t
                now = t
                announce(stepAnnouncement(0), 300L)
            },
        )
    } else {
        val elapsed = if (s.finished) s.finishedElapsed else (now - s.startedAt).coerceAtLeast(0L)
        ComplexRunContent(
            state = s,
            elapsedMs = elapsed,
            sound = sound,
            cameraActive = s.running && !s.confirmSkip && !s.confirmQuit,
            onRep = onRep,
            onSkip = { s.confirmSkip = true },
            onQuit = { s.confirmQuit = true },
        )
    }

    if (s.confirmSkip && s.running) {
        AlertDialog(
            onDismissRequest = { s.confirmSkip = false },
            confirmButton = {
                TextButton(onClick = {
                    s.confirmSkip = false
                    s.skipped = true
                    s.roundSkipped = true
                    haptics.strong()
                    advance(300L)
                }) { Text("Пропустить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { s.confirmSkip = false }) { Text("Продолжить") } },
            title = { Text("Пропустить упражнение?") },
            text = { Text("Засчитанные повторения сохранятся, но комплекс будет считаться незавершённым.") },
        )
    }

    if (s.confirmQuit && s.running) {
        AlertDialog(
            onDismissRequest = { s.confirmQuit = false },
            confirmButton = {
                TextButton(onClick = { finishEarly() }) { Text("Завершить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { s.confirmQuit = false }) { Text("Продолжить") } },
            title = { Text("Завершить комплекс?") },
            text = { Text("Засчитанные повторения сохранятся.") },
        )
    }

    if (s.finished && s.resultTitle.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text(s.resultTitle) },
            text = { Text(s.resultText) },
        )
    }
}

/** Лучшее время Минотавра: старые рекорды лежат в Store, новые — в дневнике. */
internal fun minotaurBestMs(weight: Int): Long? {
    val fromDiary = runCatching { Diary.bestComplexTimeMs(Minotaur.ID, weight) }.getOrNull()
    val fromStore = Store.minotaurBestMs(weight)
    return listOfNotNull(fromDiary, fromStore).minOrNull()
}

internal fun cindyBestRounds(): Pair<Int, Int>? =
    runCatching { Diary.bestComplexRounds(Cindy.ID) }.getOrNull()

/** Выбор формы по числу: «1 круг», «3 круга», «5 кругов». */
internal fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

internal fun complexRoundsWord(n: Int) = pluralRu(n, "круг", "круга", "кругов")
internal fun complexRepsWord(n: Int) = pluralRu(n, "повторение", "повторения", "повторений")
