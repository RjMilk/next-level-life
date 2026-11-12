package com.nextlevellife.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.FilterChip
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import kotlinx.coroutines.delay
import kotlin.math.floor

/**
 * Честный режим: каждое повторение отмечается касанием большой кнопки.
 * Засчитывается не больше повторений, чем можно физически сделать за прошедшее время.
 */
@Composable
fun ExerciseScreen(exerciseId: String, onDone: () -> Unit) {
    val ex = remember(exerciseId) { Exercises.byId(exerciseId) }
    KeepScreenOn()

    var weight by remember { mutableIntStateOf(Weights.DEFAULT) }
    var count by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    fun add(n: Int) {
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        count = (count + n).coerceAtLeast(0)
    }

    val elapsedMs = if (startedAt == 0L) 0L else now - startedAt

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ex.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(formatDuration(elapsedMs), style = MaterialTheme.typography.titleLarge)
        }
        if (ex.kettlebell) {
            // Вес выбирается до первого повторения, потом фиксируется.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Weights.all.forEach { w ->
                    FilterChip(
                        selected = weight == w,
                        onClick = { weight = w },
                        enabled = count == 0,
                        label = { Text("$w кг") },
                    )
                }
            }
        }
        Text(
            "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, weight))} · " +
                "будет начислено: ${formatShort(Exercises.rewardMs(ex, count, weight))}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Большая зона касания: удобно нажимать носом или рукой между повторениями.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { add(1) },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$count", fontSize = 120.sp, fontWeight = FontWeight.Black, color = Accent)
                Text("Нажимай после каждого повторения", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { add(-1) }, Modifier.weight(1f)) { Text("−1") }
            OutlinedButton(onClick = { add(5) }, Modifier.weight(1f)) { Text("+5") }
            OutlinedButton(onClick = { add(10) }, Modifier.weight(1f)) { Text("+10") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, Modifier.weight(1f)) { Text("Отмена") }
            Button(
                onClick = {
                    val possible = floor(elapsedMs / 1000.0 / ex.minSecondsPerRep).toInt() + 1
                    val credited = minOf(count, possible)
                    val earnedMs = Exercises.rewardMs(ex, credited, weight)
                    val xp = Exercises.xp(earnedMs)
                    val title = if (ex.kettlebell) "${ex.name} $weight кг ×$credited" else "${ex.name} ×$credited"
                    Store.recordWorkout(title, xp, earnedMs)
                    result = buildString {
                        append("Засчитано $credited повт.: +${formatShort(earnedMs)}, +$xp опыта.")
                        if (credited < count) {
                            append("\n\n$count повторений за ${formatDuration(elapsedMs)} сделать нельзя, засчитано столько, сколько реально успеть.")
                        }
                    }
                },
                enabled = count > 0,
                modifier = Modifier.weight(2f),
            ) { Text("Готово") }
        }
    }

    result?.let {
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text("Тренировка записана") },
            text = { Text(it) },
        )
    }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
