package com.nextlevellife.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.Store
import kotlinx.coroutines.delay

@Composable
fun MinotaurScreen(onDone: () -> Unit) {
    var weight by remember { mutableIntStateOf(16) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var confirmQuit by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }

    val running = startedAt != 0L && result == null

    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    if (running) {
        KeepScreenOn()
        BackHandler { confirmQuit = true }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Минотавр", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Accent)

        if (!running) {
            Text("Лесенка 10 → 1: на каждой ступени по одному подходу пяти упражнений. Всего ${Minotaur.totalReps} повторений.")
            Minotaur.exercises.forEachIndexed { i, name -> Text("${i + 1}. $name") }

            Spacer(Modifier.height(8.dp))
            Text("Вес гири", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Minotaur.weights.forEach { w ->
                    FilterChip(selected = weight == w, onClick = { weight = w }, label = { Text("$w кг") })
                }
            }
            Text("Награда: +${Minotaur.rewardMinutes(weight)} мин, +${Minotaur.xp(weight)} опыта")
            Store.minotaurBestMs(weight)?.let { Text("Твой рекорд с $weight кг: ${formatDuration(it)}", color = Good) }

            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDone, Modifier.weight(1f)) { Text("Назад") }
                Button(
                    onClick = {
                        stepIndex = 0
                        startedAt = SystemClock.elapsedRealtime()
                        now = startedAt
                    },
                    modifier = Modifier.weight(2f),
                ) { Text("Старт") }
            }
        } else {
            val step = Minotaur.steps[stepIndex]
            val next = Minotaur.steps.getOrNull(stepIndex + 1)
            Text(
                formatDuration(now - startedAt),
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            LinearProgressIndicator(
                progress = { stepIndex.toFloat() / Minotaur.steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ступень ${step.round} из ${Minotaur.ladder.size} · $weight кг",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("${step.reps}", fontSize = 120.sp, fontWeight = FontWeight.Black, color = Accent)
                Text(step.exercise, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                next?.let {
                    Spacer(Modifier.height(16.dp))
                    Text("Дальше: ${it.exercise} ×${it.reps}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = {
                    if (stepIndex < Minotaur.steps.lastIndex) {
                        stepIndex++
                    } else {
                        val total = SystemClock.elapsedRealtime() - startedAt
                        result = finishMinotaur(weight, total)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            ) { Text("Готово", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { confirmQuit = true }, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Прервать")
            }
        }
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            confirmButton = { TextButton(onClick = onDone) { Text("Прервать") } },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Продолжить") } },
            title = { Text("Прервать Минотавра?") },
            text = { Text("Незаконченный комплекс не даёт минут.") },
        )
    }

    result?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Отлично") } },
            title = { Text(title) },
            text = { Text(text) },
        )
    }
}

private fun finishMinotaur(weight: Int, totalMs: Long): Pair<String, String> {
    if (totalMs < Minotaur.MIN_DURATION_MS) {
        return "Слишком быстро" to
            "${formatDuration(totalMs)} за ${Minotaur.totalReps} повторений нереально. Награда не начислена."
    }
    val minutes = Minotaur.rewardMinutes(weight)
    val xp = Minotaur.xp(weight)
    val record = Store.submitMinotaurTime(weight, totalMs)
    Store.recordWorkout("Минотавр $weight кг · ${formatDuration(totalMs)}", xp, minutes * 60_000L)
    val title = if (record) "Новый рекорд!" else "Минотавр повержен"
    return title to "Время ${formatDuration(totalMs)}. +$minutes мин экранного времени, +$xp опыта."
}
