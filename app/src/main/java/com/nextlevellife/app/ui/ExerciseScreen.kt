package com.nextlevellife.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.pose.CountKind
import com.nextlevellife.app.pose.RepCounters
import com.nextlevellife.app.pose.rememberRepSound
import kotlinx.coroutines.delay
import java.util.UUID

/** Одиночное упражнение: повторения считает только камера, ручного ввода нет. */
@Composable
fun ExerciseScreen(exerciseId: String, onDone: () -> Unit) {
    val ex = remember(exerciseId) { Exercises.byId(exerciseId) }
    // Удержание (планка) считается временем: свой экран без повторений и без reset() по исходной позе.
    if (ex.hold) {
        HoldExerciseScreen(ex, onDone)
        return
    }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    KeepScreenOn()

    // Переживает поворот и раскрытие Fold. Всё объявлено до выбора раскладки,
    // поэтому позиционные ключи не зависят от ширины окна.
    var weight by rememberSaveable { mutableIntStateOf(Weights.DEFAULT) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    val startedAt = rememberSaveable { SystemClock.elapsedRealtime() }
    var showExit by rememberSaveable { mutableStateOf(false) }
    // Итог записи сохраняется, чтобы после поворота на диалоге не записать подход второй раз.
    var recorded by rememberSaveable { mutableStateOf(false) }
    var recordedCount by rememberSaveable { mutableIntStateOf(0) }
    var recordedElapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var recordedEarnedMs by rememberSaveable { mutableLongStateOf(0L) }
    var recordedXp by rememberSaveable { mutableLongStateOf(0L) }
    // Запись не удалась: подход не потерян, его можно записать ещё раз (тем же sessionId — без дубля).
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    // Один подход — один идентификатор: повторный вызов записи с ним ничего не начислит второй раз.
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    // Идёт ли счёт (для цвета счётчика в панели на широком экране); после пересоздания придёт заново.
    var counting by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedMs = if (recorded || saveFailed) recordedElapsedMs else (now - startedAt).coerceAtLeast(0L)

    fun finish() {
        if (recorded || count <= 0) return
        // Повтор после сбоя — с той же длительностью, что и первая попытка.
        val duration = if (saveFailed) recordedElapsedMs else (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val result = Workouts.recordSet(ex, weight, count, duration, sessionId)
        recordedElapsedMs = duration
        showExit = false
        if (!result.saved) {
            saveFailed = true
            return
        }
        saveFailed = false
        recorded = true
        recordedCount = count
        recordedEarnedMs = result.earnedMs
        recordedXp = result.xp
        haptics.strong()
        sound.done()
    }

    fun requestExit() {
        if (count > 0 && !recorded) showExit = true else onDone()
    }

    BackHandler(enabled = !recorded) { requestExit() }

    val widthClass = currentWidthClass()
    // На широком экране счётчик — в панели слева, на камере остаётся только человек.
    val counterInPanel = widthClass == WidthClass.EXPANDED
    val canFinish = count > 0 && !recorded

    val weightChips: @Composable () -> Unit = {
        if (ex.kettlebell) {
            // Вес выбирается до первого повторения: камера его не видит, а цена от него зависит.
            WeightChips(weight, enabled = count == 0 && !recorded) { weight = it }
        }
    }

    val camera: @Composable (Modifier) -> Unit = { modifier ->
        CameraRepPanel(
            exercise = ex,
            count = count,
            target = null,
            sessionKey = Unit,
            // На диалоге выхода счёт на паузе: человек смотрит в экран, а не делает повторения.
            active = !recorded && !showExit && !saveFailed,
            sound = sound,
            frontCamera = frontCamera,
            onFrontCameraChange = { frontCamera = it },
            modifier = modifier,
            showCount = !counterInPanel,
            onCountingChange = { counting = it },
            onRep = { count++ },
        )
    }

    val panelWidth = widthClass.workoutPanelWidth
    if (panelWidth == null) {
        // Узкий экран: всё в одну колонку, камера забирает остаток высоты.
        Column(
            Modifier
                .fillMaxSize()
                .padding(widthClass.outerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExerciseTitle(ex, widthClass)
            ExerciseTimer(elapsedMs, widthClass)
            weightChips()
            RewardInfo(ex, weight, count, stacked = false)
            camera(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            BottomBar(canFinish = canFinish, onCancel = { requestExit() }, onFinish = { finish() })
        }
    } else {
        WorkoutSideLayout(widthClass, panelWidth, camera) {
            if (counterInPanel) {
                ExerciseTitle(ex, widthClass)
                ExerciseTimer(elapsedMs, widthClass)
                FittedRepCounter(
                    count = count,
                    target = null,
                    counting = counting,
                    onCamera = false,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    weightChips()
                    RewardInfo(ex, weight, count, stacked = true)
                }
            } else {
                // Средняя ширина: показания прокручиваются, кнопки закреплены внизу.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExerciseTitle(ex, widthClass)
                    ExerciseTimer(elapsedMs, widthClass)
                    weightChips()
                    RewardInfo(ex, weight, count, stacked = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            StackedButtons(canFinish = canFinish, onCancel = { requestExit() }, onFinish = { finish() })
        }
    }

    if (showExit && !recorded && !saveFailed) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Записать ${repsText(count)}?") },
            text = { Text("Без записи время за них не начислится.") },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Записать") } },
            dismissButton = {
                TextButton(onClick = {
                    showExit = false
                    onDone()
                }) { Text("Выйти без записи", color = Bad) }
                TextButton(onClick = { showExit = false }) { Text("Продолжить") }
            },
        )
    }

    if (saveFailed && !recorded) {
        AlertDialog(
            // Случайным касанием мимо диалога подход не теряется: выбор только кнопками.
            onDismissRequest = {},
            title = { Text("Не удалось записать") },
            text = {
                Text(
                    "Подход (${repsText(count)}) не сохранился, время не начислено. Можно попробовать ещё раз.",
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                )
            },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Повторить") } },
            dismissButton = {
                TextButton(onClick = onDone) { Text("Выйти без записи", color = Bad) }
            },
        )
    }

    if (recorded) {
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text("Тренировка записана") },
            text = {
                Text(
                    "${repsText(recordedCount)}: +${formatShort(recordedEarnedMs)}, +$recordedXp опыта",
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                )
            },
        )
    }
}

/**
 * Удержание позы (планка). Табло — время «м:сс» и «засчитано 1:20»; «Готово» записывает подход через
 * [Workouts.recordHold]. Счётчик создаётся один раз при входе на экран, reset() — только «Заново»
 * и уход с экрана. Засчитанное время, начало подхода и sessionId переживают пересоздание Activity;
 * движок при пересоздании новый, а уже засчитанное прибавляется к его времени ([HoldMath.total]).
 */
@Composable
private fun HoldExerciseScreen(ex: Exercise, onDone: () -> Unit) {
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    KeepScreenOn()

    // Всё объявлено до выбора раскладки: позиционные ключи не зависят от ширины окна.
    var creditedMs by rememberSaveable { mutableLongStateOf(0L) }
    var startedAt by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showExit by rememberSaveable { mutableStateOf(false) }
    var showRestart by rememberSaveable { mutableStateOf(false) }
    var recorded by rememberSaveable { mutableStateOf(false) }
    var recordedHeldMs by rememberSaveable { mutableLongStateOf(0L) }
    var recordedElapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var recordedEarnedMs by rememberSaveable { mutableLongStateOf(0L) }
    var recordedXp by rememberSaveable { mutableLongStateOf(0L) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    var frontCamera by rememberSaveable { mutableStateOf(true) }

    // Счётчик — один на экран. creditedMs на момент создания — засчитанное до пересоздания.
    val tracker = remember(ex.id) {
        RepCounters.forExercise(ex.id)?.takeIf { it.kind == CountKind.HOLD }?.let { HoldTracker(it, creditedMs) }
    }
    DisposableEffect(tracker) { onDispose { tracker?.counter?.reset() } }
    LaunchedEffect(tracker) {
        if (tracker != null) snapshotFlow { tracker.board.creditedMs }.collect { creditedMs = it }
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedMs = if (recorded || saveFailed) recordedElapsedMs else (now - startedAt).coerceAtLeast(0L)

    fun finish() {
        if (recorded) return
        // Повтор после сбоя — с тем же временем, что и первая попытка.
        val held = if (saveFailed) recordedHeldMs else creditedMs
        if (held <= 0L) return
        val duration = if (saveFailed) recordedElapsedMs else (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val result = Workouts.recordHold(ex, held, duration, sessionId)
        recordedHeldMs = held
        recordedElapsedMs = duration
        showExit = false
        if (!result.saved) {
            saveFailed = true
            return
        }
        saveFailed = false
        recorded = true
        recordedEarnedMs = result.earnedMs
        recordedXp = result.xp
        haptics.strong()
        sound.done()
    }

    fun restart() {
        showRestart = false
        tracker?.restart()
        creditedMs = 0L
        startedAt = SystemClock.elapsedRealtime()
    }

    fun requestExit() {
        if (creditedMs > 0L && !recorded) showExit = true else onDone()
    }

    BackHandler(enabled = !recorded) { requestExit() }

    val widthClass = currentWidthClass()
    val scoreInPanel = widthClass == WidthClass.EXPANDED
    val canFinish = creditedMs > 0L && !recorded
    val canRestart = !recorded && !saveFailed
    // Засчитанное есть — сначала спросить: «Заново» его стирает.
    val onRestart: () -> Unit = {
        if (creditedMs > 0L) {
            showRestart = true
        } else {
            restart()
        }
    }

    val camera: @Composable (Modifier) -> Unit = { modifier ->
        CameraHoldPanel(
            exercise = ex,
            tracker = tracker,
            // На диалогах время не считается: человек смотрит в экран, а не стоит в планке.
            active = !recorded && !showExit && !saveFailed && !showRestart,
            sound = sound,
            frontCamera = frontCamera,
            onFrontCameraChange = { frontCamera = it },
            modifier = modifier,
            showScore = !scoreInPanel,
        )
    }

    val panelWidth = widthClass.workoutPanelWidth
    if (panelWidth == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(widthClass.outerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExerciseTitle(ex, widthClass)
            ExerciseTimer(elapsedMs, widthClass)
            HoldRewardInfo(ex, creditedMs, stacked = false)
            camera(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            HoldBottomBar(
                canFinish = canFinish, canRestart = canRestart,
                onCancel = { requestExit() }, onRestart = onRestart, onFinish = { finish() },
            )
        }
    } else {
        WorkoutSideLayout(widthClass, panelWidth, camera) {
            if (scoreInPanel) {
                ExerciseTitle(ex, widthClass)
                ExerciseTimer(elapsedMs, widthClass)
                if (tracker != null) {
                    HoldScore(
                        tracker,
                        onCamera = false,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                HoldRewardInfo(ex, creditedMs, stacked = true)
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExerciseTitle(ex, widthClass)
                    ExerciseTimer(elapsedMs, widthClass)
                    HoldRewardInfo(ex, creditedMs, stacked = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRestart,
                enabled = canRestart,
                shape = ButtonShape,
                contentPadding = TightButtonPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MainButtonHeight),
            ) { Text("Заново", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            Spacer(Modifier.height(12.dp))
            StackedButtons(canFinish = canFinish, onCancel = { requestExit() }, onFinish = { finish() })
        }
    }

    val heldText = ProgressMath.formatHold(creditedMs)
    if (showExit && !recorded && !saveFailed) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Записать $heldText планки?") },
            text = { Text("Без записи время за неё не начислится.") },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Записать") } },
            dismissButton = {
                TextButton(onClick = {
                    showExit = false
                    onDone()
                }) { Text("Выйти без записи", color = Bad) }
                TextButton(onClick = { showExit = false }) { Text("Продолжить") }
            },
        )
    }

    if (showRestart && !recorded && !saveFailed) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            title = { Text("Начать заново?") },
            text = { Text("Засчитанное время ($heldText) пропадёт.", style = MaterialTheme.typography.bodyLarge.merge(Tabular)) },
            confirmButton = { TextButton(onClick = { restart() }) { Text("Заново", color = Bad) } },
            dismissButton = { TextButton(onClick = { showRestart = false }) { Text("Продолжить") } },
        )
    }

    if (saveFailed && !recorded) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Не удалось записать") },
            text = {
                Text(
                    "Подход (${ProgressMath.formatHold(recordedHeldMs)} планки) не сохранился, время не начислено. " +
                        "Можно попробовать ещё раз.",
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                )
            },
            confirmButton = { TextButton(onClick = { finish() }) { Text("Повторить") } },
            dismissButton = {
                TextButton(onClick = onDone) { Text("Выйти без записи", color = Bad) }
            },
        )
    }

    if (recorded) {
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text("Тренировка записана") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        HoldMath.resultLine(recordedHeldMs, recordedEarnedMs),
                        style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                    )
                    Text(
                        "+$recordedXp опыта",
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** Цена секунды удержания и заработанное; в узкой панели — двумя строками. */
@Composable
private fun HoldRewardInfo(ex: Exercise, creditedMs: Long, stacked: Boolean) {
    val one = HoldMath.priceLine(ex)
    val earned = formatShort(Exercises.holdRewardMs(ex, creditedMs))
    val style = MaterialTheme.typography.titleMedium.merge(Tabular)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(one, color = muted, style = style)
            Text("Заработано: $earned", color = muted, style = style)
        }
    } else {
        Text("$one · заработано $earned", color = muted, style = style)
    }
}

/** Кнопки под камерой для удержания (узкий экран): «Отмена», «Заново», «Готово». */
@Composable
private fun HoldBottomBar(
    canFinish: Boolean, canRestart: Boolean,
    onCancel: () -> Unit, onRestart: () -> Unit, onFinish: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onCancel,
            shape = ButtonShape,
            contentPadding = TightButtonPadding,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) { Text("Отмена", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        OutlinedButton(
            onClick = onRestart,
            enabled = canRestart,
            shape = ButtonShape,
            contentPadding = TightButtonPadding,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) { Text("Заново", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = ButtonShape,
            contentPadding = TightButtonPadding,
            modifier = Modifier
                .weight(1.4f)
                .height(64.dp),
        ) { Text("Готово", style = MaterialTheme.typography.titleLarge, maxLines = 1) }
    }
}

/** Название упражнения — отдельной строкой: длинное название не теснит таймер. */
@Composable
private fun ExerciseTitle(ex: Exercise, widthClass: WidthClass) {
    Text(
        ex.name,
        style = if (widthClass == WidthClass.MEDIUM) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.headlineMedium
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ExerciseTimer(elapsedMs: Long, widthClass: WidthClass) {
    ScoreText(
        formatDuration(elapsedMs),
        sizeSp = Scoreboard.exerciseTimerSp(widthClass),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun WeightChips(weight: Int, enabled: Boolean, onWeight: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Weights.all.forEach { w ->
            FilterChip(
                selected = weight == w,
                onClick = { onWeight(w) },
                enabled = enabled,
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

/** Цена повторения и заработанное; в узкой панели — двумя строками. */
@Composable
private fun RewardInfo(ex: Exercise, weight: Int, count: Int, stacked: Boolean) {
    val one = "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, weight))}"
    val earned = formatShort(Exercises.rewardMs(ex, count, weight))
    val style = MaterialTheme.typography.titleMedium.merge(Tabular)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(one, color = muted, style = style)
            Text("Заработано: $earned", color = muted, style = style)
        }
    } else {
        Text("$one · заработано $earned", color = muted, style = style)
    }
}

private val TightButtonPadding = PaddingValues(horizontal = 8.dp)

/** Кнопки в ряд под камерой (узкий экран). */
@Composable
private fun BottomBar(canFinish: Boolean, onCancel: () -> Unit, onFinish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onCancel,
            shape = ButtonShape,
            contentPadding = TightButtonPadding,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) { Text("Отмена", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = ButtonShape,
            contentPadding = TightButtonPadding,
            modifier = Modifier
                .weight(1.6f)
                .height(64.dp),
        ) { Text("Готово", style = MaterialTheme.typography.titleLarge, maxLines = 1) }
    }
}

/** Кнопки столбиком внизу левой панели (широкий экран): «Готово» — нижняя и крупнее. */
@Composable
private fun ColumnScope.StackedButtons(canFinish: Boolean, onCancel: () -> Unit, onFinish: () -> Unit) {
    OutlinedButton(
        onClick = onCancel,
        shape = ButtonShape,
        contentPadding = TightButtonPadding,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MainButtonHeight),
    ) { Text("Отмена", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onFinish,
        enabled = canFinish,
        shape = ButtonShape,
        contentPadding = TightButtonPadding,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) { Text("Готово", style = MaterialTheme.typography.titleLarge, maxLines = 1) }
}

/** «1 повторение», «3 повторения», «5 повторений». */
private fun repsText(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> "повторений"
        mod10 == 1 -> "повторение"
        mod10 in 2..4 -> "повторения"
        else -> "повторений"
    }
    return "$n $word"
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
