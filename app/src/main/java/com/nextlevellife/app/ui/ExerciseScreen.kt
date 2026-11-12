package com.nextlevellife.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.data.Workouts
import com.nextlevellife.app.pose.rememberRepSound
import kotlinx.coroutines.delay
import java.util.UUID

private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** Одиночное упражнение: повторения считает только камера, ручного ввода нет. */
@Composable
fun ExerciseScreen(exerciseId: String, onDone: () -> Unit) {
    val ex = remember(exerciseId) { Exercises.byId(exerciseId) }
    val haptics = rememberHaptics()
    val sound = rememberRepSound()
    KeepScreenOn()

    // Переживает поворот и раскрытие Fold.
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
    var recordedSaved by rememberSaveable { mutableStateOf(true) }
    // Один подход — один идентификатор: повторный вызов записи с ним ничего не начислит второй раз.
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedMs = if (recorded) recordedElapsedMs else (now - startedAt).coerceAtLeast(0L)

    fun finish() {
        if (recorded || count <= 0) return
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val result = Workouts.recordSet(ex, weight, count, duration, sessionId)
        recorded = true
        recordedCount = count
        recordedElapsedMs = duration
        recordedEarnedMs = result.earnedMs
        recordedXp = result.xp
        recordedSaved = result.saved
        showExit = false
        haptics.strong()
        sound.done()
    }

    fun requestExit() {
        if (count > 0 && !recorded) showExit = true else onDone()
    }

    BackHandler(enabled = !recorded) { requestExit() }

    val info: @Composable () -> Unit = {
        Header(ex, elapsedMs)
        if (ex.kettlebell) {
            // Вес выбирается до первого повторения: камера его не видит, а цена от него зависит.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Weights.all.forEach { w ->
                    FilterChip(
                        selected = weight == w,
                        onClick = { weight = w },
                        enabled = count == 0 && !recorded,
                        label = { Text("$w кг", fontSize = 16.sp) },
                    )
                }
            }
        }
        Text(
            "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, weight))} · " +
                "заработано ${formatShort(Exercises.rewardMs(ex, count, weight))}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium.merge(Tnum),
        )
    }

    val camera: @Composable (Modifier) -> Unit = { modifier ->
        CameraRepPanel(
            exercise = ex,
            count = count,
            target = null,
            sessionKey = Unit,
            // На диалоге выхода счёт на паузе: человек смотрит в экран, а не делает повторения.
            active = !recorded && !showExit,
            sound = sound,
            modifier = modifier,
            onRep = { count++ },
        )
    }

    val buttons: @Composable () -> Unit = {
        BottomBar(
            canFinish = count > 0 && !recorded,
            onCancel = { requestExit() },
            onFinish = { finish() },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.width(360.dp).fillMaxHeight()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { info() }
                    Spacer(Modifier.height(16.dp))
                    buttons()
                }
                camera(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                info()
                camera(Modifier.fillMaxWidth().weight(1f))
                buttons()
            }
        }
    }

    if (showExit && !recorded) {
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

    if (recorded) {
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text(if (recordedSaved) "Тренировка записана" else "Не удалось записать") },
            text = {
                Text(
                    if (recordedSaved) "${repsText(recordedCount)}: +${formatShort(recordedEarnedMs)}, +$recordedXp опыта"
                    else "Подход не сохранился, время не начислено.",
                    style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                )
            },
        )
    }
}

@Composable
private fun Header(ex: Exercise, elapsedMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ex.name,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(elapsedMs),
            style = MaterialTheme.typography.headlineMedium.merge(Tnum),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BottomBar(canFinish: Boolean, onCancel: () -> Unit, onFinish: () -> Unit) {
    val tight = PaddingValues(horizontal = 8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onCancel,
            contentPadding = tight,
            modifier = Modifier.weight(1f).height(64.dp),
        ) { Text("Отмена", fontSize = 18.sp, maxLines = 1) }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            contentPadding = tight,
            modifier = Modifier.weight(1.6f).height(64.dp),
        ) { Text("Готово", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
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
