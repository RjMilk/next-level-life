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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) { Text("Назад", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = onStart,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(2f)
                        .height(64.dp),
                ) { Text("Старт", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun PrepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Accent)
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
            FilterChip(
                selected = weight == w,
                onClick = { onWeight(w) },
                label = { Text("$w кг", style = MaterialTheme.typography.titleSmall.merge(Tabular)) },
            )
        }
    }
    Text("Награда: +${formatShort(Minotaur.rewardMs(weight))}")
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

/**
 * Раскладка по классу ширины окна: узкий — показания, камера и кнопки в одну колонку;
 * средний — панель 220 dp слева и камера справа; широкий — панель 300 dp с лесенкой/кругами.
 * Fold «столиком» (горизонтальный сгиб наполовину) — камера над сгибом, табло шага и кнопки под ним.
 */
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
    val widthClass = currentWidthClass()
    val pad = widthClass.outerPadding
    // Выбор камеры — до ветвления раскладки: переживает и пересоздание, и смену класса ширины.
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    // Fold «столиком»: камера над сгибом, табло шага под ним. Счёт шага хранит [state] — смена позы его не теряет.
    val tabletop = rememberTabletop()
    // Идёт ли счёт — для цвета счётчика под сгибом; после пересоздания панели придёт заново.
    var counting by remember { mutableStateOf(false) }

    val camera: @Composable (Modifier) -> Unit = { mod ->
        CameraRepPanel(
            exercise = step.exercise,
            count = state.stepCount,
            target = step.reps,
            sessionKey = state.stepIndex,
            active = cameraActive,
            sound = sound,
            frontCamera = frontCamera,
            onFrontCameraChange = { frontCamera = it },
            modifier = mod,
            showCount = tabletop == null,
            onCountingChange = { counting = it },
            onRep = onRep,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerHeight = (maxHeight - pad * 2).value
        val innerWidth = (maxWidth - pad * 2).value
        val panelWidth = widthClass.workoutPanelWidth
        if (tabletop != null) {
            TabletopSplit(tabletop, widthClass, top = camera) {
                TabletopBoard(
                    info = { mod ->
                        BoxWithConstraints(mod) {
                            val infoWidth = maxWidth.value
                            val infoHeight = maxHeight.value
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Readings(
                                    state = state,
                                    elapsedMs = elapsedMs,
                                    widthClass = widthClass,
                                    widthDp = infoWidth,
                                    heightDp = infoHeight,
                                    inPanel = true,
                                )
                            }
                        }
                    },
                    score = { mod ->
                        FittedRepCounter(
                            count = state.stepCount,
                            target = step.reps,
                            counting = counting && state.running,
                            onCamera = false,
                            modifier = mod,
                        )
                    },
                    actions = { stacked ->
                        Controls(enabled = state.running, stacked = stacked, onSkip = onSkip, onQuit = onQuit)
                    },
                )
            }
        } else if (panelWidth == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Readings(
                    state = state,
                    elapsedMs = elapsedMs,
                    widthClass = widthClass,
                    widthDp = innerWidth,
                    heightDp = innerHeight,
                    inPanel = false,
                )
                camera(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Controls(enabled = state.running, stacked = false, onSkip = onSkip, onQuit = onQuit)
            }
        } else {
            WorkoutSideLayout(widthClass, panelWidth, camera) {
                // Показания прокручиваются, если не влезают по высоте (телефон лёжа); кнопки закреплены.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Readings(
                        state = state,
                        elapsedMs = elapsedMs,
                        widthClass = widthClass,
                        widthDp = panelWidth.value,
                        heightDp = innerHeight,
                        inPanel = true,
                    )
                    if (widthClass == WidthClass.EXPANDED) {
                        Spacer(Modifier.height(4.dp))
                        when (kind) {
                            ComplexKind.MINOTAUR -> MinotaurLadder(state)
                            ComplexKind.CINDY -> CindyRounds(state)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Controls(enabled = state.running, stacked = true, onSkip = onSkip, onQuit = onQuit)
            }
        }
    }
}

/** Таймер Синди с округлением вверх: старт показывает 20:00, а 0:00 — только когда время вышло. */
private fun complexTimerText(kind: ComplexKind, elapsedMs: Long): String = when (kind) {
    ComplexKind.MINOTAUR -> formatDuration(elapsedMs)
    ComplexKind.CINDY -> formatDuration((Cindy.DURATION_MS - elapsedMs).coerceAtLeast(0L) + 999L)
}

/** Все ступени Минотавра пройдены целиком. */
private fun minotaurDone(state: ComplexRunState): Boolean {
    val kind = state.kind
    if (kind != ComplexKind.MINOTAUR) return false
    val step = kind.step(state.stepIndex)
    return state.finished && kind.nextStep(state.stepIndex) == null && !state.skipped && state.stepCount >= step.reps
}

@Composable
private fun Readings(
    state: ComplexRunState,
    elapsedMs: Long,
    widthClass: WidthClass,
    widthDp: Float,
    heightDp: Float,
    inPanel: Boolean,
) {
    val kind = state.kind
    val step = kind.step(state.stepIndex)
    val next = kind.nextStep(state.stepIndex)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = LocalDensity.current.fontScale
    val minotaurBest = remember(state.weight, state.finished) {
        if (kind == ComplexKind.MINOTAUR) minotaurBestMs(state.weight) else null
    }
    val cindyBest = remember(state.finished) { if (kind == ComplexKind.CINDY) cindyBestRounds() else null }
    // Мало высоты (разделённый экран) — строки помельче, чтобы камере осталось место.
    val short = heightDp < Scoreboard.SHORT_HEIGHT_DP

    val timerText = complexTimerText(kind, elapsedMs)
    val timerSp = Scoreboard.complexTimerSp(widthClass, widthDp, heightDp, timerText.length, fontScale)
    val record: String?
    val progress: Float
    val stageText: String
    when (kind) {
        ComplexKind.MINOTAUR -> {
            record = minotaurBest?.let { formatDuration(it) }
            progress = state.totalCounted.toFloat() / Minotaur.totalReps
            stageText = "Ступень ${step.round} из ${Minotaur.ladder.size} · ${state.weight} кг"
        }
        ComplexKind.CINDY -> {
            record = cindyBest?.let { (r, e) -> "$r + $e" }
            progress = elapsedMs.toFloat() / Cindy.DURATION_MS
            stageText = "Круг ${step.round}"
        }
    }
    val lastMinute = kind == ComplexKind.CINDY && Cindy.DURATION_MS - elapsedMs <= 60_000L && state.running

    // Таймер на всю ширину, рекорд — под ним.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreText(
            timerText,
            sizeSp = timerSp,
            color = if (lastMinute) Accent else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (record != null) {
            Text(
                "Рекорд $record",
                color = muted,
                style = MaterialTheme.typography.titleSmall.merge(Tabular),
                maxLines = 1,
            )
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

    // Ступень/круг и плашки упражнений внутри неё; в узкой панели — друг под другом.
    val parts = kind.exercises.size
    val pos = if (minotaurDone(state)) parts else step.part
    val stage: @Composable () -> Unit = {
        Text(stageText, color = muted, style = MaterialTheme.typography.bodyMedium.merge(Tabular), maxLines = 1)
    }
    if (inPanel) {
        stage()
        PartBars(parts, pos, Modifier.fillMaxWidth())
    } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            stage()
            PartBars(parts, pos, Modifier.weight(1f))
        }
    }

    // Что делать сейчас: упражнение и цель шага.
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${step.exercise.name} ×${step.reps}",
            modifier = Modifier.fillMaxWidth(),
            style = if (short || widthClass == WidthClass.MEDIUM) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = MaterialTheme.colorScheme.onBackground,
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
            style = if (short) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            maxLines = if (inPanel) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Плашки упражнений внутри ступени/круга: пройденные, текущее, будущие. */
@Composable
private fun PartBars(parts: Int, pos: Int, modifier: Modifier) {
    Row(
        modifier.semantics { contentDescription = "Упражнение ${(pos + 1).coerceAtMost(parts)} из $parts" },
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

private val CellShape = RoundedCornerShape(8.dp)

/** Лесенка Минотавра 10 → 1 (широкий экран): пройденные ступени залиты, текущая обведена. */
@Composable
private fun MinotaurLadder(state: ComplexRunState) {
    val round = state.kind.step(state.stepIndex).round
    val allDone = minotaurDone(state)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = if (allDone) "Все ступени пройдены" else "Ступень $round из ${Minotaur.ladder.size}"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Лесенка", color = muted, style = MaterialTheme.typography.labelLarge)
        Minotaur.ladder.chunked(5).forEachIndexed { row, rungs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rungs.forEachIndexed { j, reps ->
                    val r = row * 5 + j + 1
                    val done = allDone || r < round
                    val current = !allDone && r == round
                    val cell = Modifier
                        .weight(1f)
                        .height(44.dp)
                    val mod = when {
                        done -> cell.background(Accent, CellShape)
                        current -> cell
                            .background(Accent.copy(alpha = 0.2f), CellShape)
                            .border(2.dp, Accent, CellShape)
                        else -> cell.background(MaterialTheme.colorScheme.surfaceVariant, CellShape)
                    }
                    Box(mod, contentAlignment = Alignment.Center) {
                        Text(
                            "$reps",
                            color = when {
                                done -> OnAccent
                                current -> Accent
                                else -> muted
                            },
                            style = MaterialTheme.typography.titleMedium.merge(Tabular),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Круги Синди (широкий экран): число полных кругов и состав текущего круга. */
@Composable
private fun CindyRounds(state: ComplexRunState) {
    val step = state.kind.step(state.stepIndex)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Полных кругов", color = muted, style = MaterialTheme.typography.labelLarge)
        ScoreText("${state.fullRounds}", sizeSp = 48, color = Accent)
        Text("Круг ${step.round}", color = muted, style = MaterialTheme.typography.labelLarge.merge(Tabular))
        Cindy.round.forEachIndexed { i, part ->
            val done = i < step.part
            val current = i == step.part && !state.finished
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = Modifier.size(14.dp)
                Box(
                    when {
                        done -> dot.background(Accent, CellShape)
                        current -> dot.border(2.dp, Accent, CellShape)
                        else -> dot.background(MaterialTheme.colorScheme.surfaceVariant, CellShape)
                    },
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "${part.exercise.name} ×${part.reps}",
                    color = if (current) MaterialTheme.colorScheme.onBackground else muted,
                    style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** «Пропустить упражнение» и «Завершить»: в ряд под камерой или столбиком в панели. */
@Composable
private fun Controls(enabled: Boolean, stacked: Boolean, onSkip: () -> Unit, onQuit: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val label = MaterialTheme.typography.titleMedium
    val skip: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(
            onClick = onSkip,
            enabled = enabled,
            shape = ButtonShape,
            contentPadding = padding,
            modifier = mod.heightIn(min = MainButtonHeight),
        ) { Text("Пропустить упражнение", style = label, textAlign = TextAlign.Center) }
    }
    val quit: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(
            onClick = onQuit,
            enabled = enabled,
            shape = ButtonShape,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad),
            modifier = mod.heightIn(min = MainButtonHeight),
        ) { Text("Завершить", style = label, textAlign = TextAlign.Center) }
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
