package com.nextlevellife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.pose.RepSound

// ---------- Подготовка ----------

@Composable
internal fun ComplexPrep(
    kind: ComplexKind,
    weight: Int,
    onWeight: (Int) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (kind) {
                    ComplexKind.MINOTAUR -> MinotaurPrep(weight, onWeight)
                    ComplexKind.CINDY -> CindyPrep()
                }
                Text(
                    "Счёт идёт сам. Встань в исходную позу — после сигнала начинай.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) { Text("Назад") }
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .weight(2f)
                        .height(64.dp),
                ) { Text("Старт", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ColumnScope.PrepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        color = Accent,
    )
}

@Composable
private fun SetupHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ColumnScope.MinotaurPrep(weight: Int, onWeight: (Int) -> Unit) {
    val best = remember(weight) { minotaurBestMs(weight) }
    PrepTitle("Минотавр")
    Text(
        "Лесенка 10 → 1: на каждой ступени по одному подходу пяти упражнений. " +
            "Всего ${Minotaur.totalReps} повторений.",
    )
    Minotaur.exercises.forEachIndexed { i, ex -> Text("${i + 1}. ${ex.name}") }

    Spacer(Modifier.height(4.dp))
    Text("Вес гири", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Weights.all.forEach { w ->
            FilterChip(selected = weight == w, onClick = { onWeight(w) }, label = { Text("$w кг") })
        }
    }
    Text("Награда: +${formatShort(Minotaur.rewardMs(weight))}, +${Minotaur.xp(weight)} опыта")
    Text(
        "Те же ${Minotaur.totalReps} повторений по отдельности дали бы " +
            "${formatShort(Minotaur.separateRewardMs(weight))}: разница — бонус за комплекс целиком.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    best?.let { Text("Твой рекорд с $weight кг: ${formatDuration(it)}", color = Good) }
    SetupHint("Поставь телефон в 2 метрах, встань в пол-оборота: видно всё тело от головы до стоп.")
}

@Composable
private fun ColumnScope.CindyPrep() {
    val best = remember { cindyBestRounds() }
    PrepTitle("Синди")
    Text("20 минут, как можно больше кругов.")
    Text("Круг:", style = MaterialTheme.typography.titleMedium)
    Cindy.round.forEachIndexed { i, part -> Text("${i + 1}. ${part.exercise.name} ×${part.reps}") }
    Text("Полный круг = 20 минут экранного времени")
    best?.let { (r, e) ->
        Text("Твой рекорд: $r ${complexRoundsWord(r)} + $e", color = Good)
    }
    SetupHint("Поставь телефон по диагонали, чтобы в кадр попали и турник, и место на полу.")
}

// ---------- Выполнение ----------

/** Размеры показаний: при нехватке высоты уменьшаются шрифты, а не камера. */
private class RunSizes(val timer: TextUnit, val name: TextUnit, val gap: Dp, val compact: Boolean = false)

private fun sizesFor(height: Dp): RunSizes = when {
    height >= 760.dp -> RunSizes(timer = 72.sp, name = 32.sp, gap = 10.dp)
    height >= 640.dp -> RunSizes(timer = 64.sp, name = 30.sp, gap = 8.dp)
    height >= 540.dp -> RunSizes(timer = 56.sp, name = 28.sp, gap = 6.dp)
    else -> RunSizes(timer = 44.sp, name = 22.sp, gap = 4.dp, compact = true)
}

@Composable
internal fun ComplexRunContent(
    state: ComplexRunState,
    elapsedMs: Long,
    sound: RepSound,
    cameraActive: Boolean,
    onRep: () -> Unit,
    onSkip: () -> Unit,
    onQuit: () -> Unit,
) {
    val kind = state.kind
    val step = kind.step(state.stepIndex)

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight
        val camera: @Composable (Modifier) -> Unit = { mod ->
            CameraRepPanel(
                exercise = step.exercise,
                count = state.stepCount,
                target = step.reps,
                sessionKey = state.stepIndex,
                active = cameraActive,
                sound = sound,
                modifier = mod,
                onRep = onRep,
            )
        }
        if (twoPane) {
            // Слева только показания с прокруткой: камеру они не теснят, шрифты можно крупнее.
            val sizes = if (maxHeight >= 560.dp) sizesFor(760.dp) else sizesFor(540.dp)
            val leftWidth = min(360.dp, maxWidth * 0.5f)
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier
                        .width(leftWidth)
                        .fillMaxHeight(),
                ) {
                    // Слева мало высоты (телефон лёжа): показания прокручиваются, кнопки закреплены.
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(sizes.gap),
                    ) {
                        Readings(state, elapsedMs, sizes)
                    }
                    Spacer(Modifier.height(8.dp))
                    Controls(enabled = state.running, onSkip = onSkip, onQuit = onQuit)
                }
                camera(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            val sizes = sizesFor(maxHeight)
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(sizes.gap),
            ) {
                Readings(state, elapsedMs, sizes)
                camera(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Controls(enabled = state.running, onSkip = onSkip, onQuit = onQuit)
            }
        }
    }
}

@Composable
private fun Readings(state: ComplexRunState, elapsedMs: Long, sizes: RunSizes) {
    val kind = state.kind
    val step = kind.step(state.stepIndex)
    val next = kind.nextStep(state.stepIndex)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val minotaurBest = remember(state.weight, state.finished) {
        if (kind == ComplexKind.MINOTAUR) minotaurBestMs(state.weight) else null
    }
    val cindyBest = remember(state.finished) { if (kind == ComplexKind.CINDY) cindyBestRounds() else null }

    val timerText: String
    val record: String?
    val progress: Float
    val stageText: String
    when (kind) {
        ComplexKind.MINOTAUR -> {
            timerText = formatDuration(elapsedMs)
            record = minotaurBest?.let { formatDuration(it) }
            progress = state.totalCounted.toFloat() / Minotaur.totalReps
            stageText = "Ступень ${step.round} из ${Minotaur.ladder.size} · ${state.weight} кг"
        }
        ComplexKind.CINDY -> {
            // Округление вверх: старт показывает 20:00, а 0:00 — только когда время вышло.
            val left = (Cindy.DURATION_MS - elapsedMs).coerceAtLeast(0L)
            timerText = formatDuration(left + 999L)
            record = cindyBest?.let { (r, e) -> "$r + $e" }
            progress = elapsedMs.toFloat() / Cindy.DURATION_MS
            stageText = "Круг ${step.round}"
        }
    }
    val lastMinute = kind == ComplexKind.CINDY && Cindy.DURATION_MS - elapsedMs <= 60_000L && state.running

    // Таймер и рекорд.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timerText,
            maxLines = 1,
            style = TextStyle(
                fontSize = sizes.timer,
                lineHeight = sizes.timer,
                fontWeight = FontWeight.Black,
                fontFeatureSettings = "tnum",
                color = if (lastMinute) Accent else MaterialTheme.colorScheme.onBackground,
            ),
        )
        if (record != null) {
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Рекорд", color = muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    record,
                    color = muted,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
    }

    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = Accent,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    // Ступень/круг и плашки упражнений внутри неё.
    val parts = kind.exercises.size
    val minotaurDone = kind == ComplexKind.MINOTAUR && state.finished && next == null && !state.skipped &&
        state.stepCount >= step.reps
    val pos = if (minotaurDone) parts else step.part
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stageText,
            color = muted,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Row(
            Modifier
                .weight(1f)
                .semantics { contentDescription = "Упражнение ${(pos + 1).coerceAtMost(parts)} из $parts" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shape = RoundedCornerShape(4.dp)
            repeat(parts) { i ->
                val base = Modifier
                    .weight(1f)
                    .height(14.dp)
                val mod = when {
                    i < pos -> base.background(Accent, shape)
                    i == pos -> base
                        .background(Accent.copy(alpha = 0.3f), shape)
                        .border(2.dp, Accent, shape)
                    else -> base.background(MaterialTheme.colorScheme.surfaceVariant, shape)
                }
                Box(mod)
            }
        }
    }

    // Что делать сейчас.
    Column(Modifier.fillMaxWidth()) {
        Text(
            step.exercise.name,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                fontSize = sizes.name,
                lineHeight = sizes.name * 1.15f,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                state.finished -> if (kind == ComplexKind.CINDY) "Время вышло" else "Комплекс окончен"
                next == null -> "Последний подход"
                else -> "Дальше: ${next.exercise.name} ×${next.reps}"
            },
            color = muted,
            style = if (sizes.compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Controls(enabled: Boolean, onSkip: () -> Unit, onQuit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        OutlinedButton(
            onClick = onSkip,
            enabled = enabled,
            contentPadding = padding,
            modifier = Modifier
                .weight(3f)
                .heightIn(min = 48.dp),
        ) { Text("Пропустить упражнение", textAlign = TextAlign.Center) }
        OutlinedButton(
            onClick = onQuit,
            enabled = enabled,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad),
            modifier = Modifier
                .weight(2f)
                .heightIn(min = 48.dp),
        ) { Text("Завершить", textAlign = TextAlign.Center) }
    }
}
