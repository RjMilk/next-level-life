package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.GymMath
import com.nextlevellife.app.data.GymSummary

/**
 * Зал: карточка упражнения на главной, выбор веса перед подходом и блок «Зал» в «Прогрессе».
 * Повторения считает только камера; вес заявляется человеком и на минуты и очки не влияет.
 */

/** Кнопки изменения веса: −2,5, −0,5, +0,5, +2,5 кг. */
private val LoadSteps = listOf(-2_500, -500, 500, 2_500)

/**
 * Карточка упражнения зала: название, цена за повтор, последний рабочий вес и совет на следующий раз.
 * [summary] — null, пока история читается.
 */
@Composable
internal fun GymExerciseCard(ex: Exercise, summary: GymSummary?, modifier: Modifier, onExercise: (String) -> Unit) {
    Card(
        modifier.clickable { onExercise(ex.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ex.name, style = MaterialTheme.typography.titleSmall)
            Text(
                exercisePriceLine(ex),
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                GymMath.workingLine(summary),
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary?.advice?.let {
                Text(GymMath.adviceText(it), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = Accent)
            }
        }
    }
}

/**
 * Вес подхода зала. До первого повторения ([enabled]) — крупное число и кнопки −2,5/−0,5/+0,5/+2,5 кг,
 * совет на сегодня и мелкая строка, что вес не влияет на минуты; после — одна строка с весом, чтобы камере
 * осталось больше места.
 */
@Composable
internal fun GymLoadPicker(ex: Exercise, loadG: Int, summary: GymSummary?, enabled: Boolean, onLoad: (Int) -> Unit) {
    val spec = ex.gym ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!enabled) {
        Text(
            "Вес ${GymMath.formatKg(loadG)} · на минуты не влияет",
            style = MaterialTheme.typography.titleSmall.merge(Tabular),
            color = muted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                GymMath.formatKg(loadG),
                style = MaterialTheme.typography.headlineSmall.merge(Tabular),
            )
            summary?.advice?.let {
                Text(
                    GymMath.adviceText(it),
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    color = Accent,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadSteps.forEach { delta ->
                val next = GymMath.adjust(spec.loadMode, loadG, delta)
                OutlinedButton(
                    onClick = { onLoad(next) },
                    enabled = next != loadG,
                    shape = ButtonShape,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        GymMath.formatDelta(delta).removeSuffix(" кг"),
                        style = MaterialTheme.typography.titleSmall.merge(Tabular),
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            "${spec.loadMode.title.replaceFirstChar { it.uppercaseChar() }}. ${GymMath.LOAD_NOTE}",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

/**
 * «Зал» в «Прогрессе» — раскрывающаяся карточка (ключ `progress_gym`, по умолчанию свёрнута): по каждому
 * упражнению с рабочими подходами — последний и лучший рабочий вес. Без истории карточки нет.
 */
@Composable
internal fun GymProgressCard(exercises: List<Exercise>, summaries: Map<String, GymSummary>) {
    val rows = exercises.mapNotNull { ex -> summaries[ex.id]?.takeIf { it.lastWorkingG != null }?.let { ex to it } }
    if (rows.isEmpty()) return
    ExpandableCard(
        key = "progress_gym",
        title = "Зал",
        summary = "Рабочий вес: подходы от ${GymMath.WORK_REPS} повторений",
        contentSpacing = 2.dp,
    ) {
        rows.forEachIndexed { i, (ex, s) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ex.name, style = MaterialTheme.typography.titleSmall)
                    s.advice?.let {
                        Text(
                            GymMath.adviceText(it),
                            style = MaterialTheme.typography.bodySmall.merge(Tabular),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(GymMath.formatKg(s.lastWorkingG!!), style = MaterialTheme.typography.titleMedium.merge(Tabular))
                    Text(
                        "лучший ${s.bestWorkingG?.let { GymMath.formatKg(it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
