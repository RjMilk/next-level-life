package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal

@Composable
internal fun RecordsCard(
    minotaurBest: List<Pair<Int, Long?>>,
    cindyBest: Pair<Int, Int>?,
    records: List<ExerciseRecord>,
    expanded: String?,
    history: List<Pair<Long, ExerciseTotal>>?,
    onToggle: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Рекорды", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            SectionLabel("Минотавр — лучшее время")
            minotaurBest.forEach { (weight, ms) ->
                RecordRow("$weight кг", ms?.let { formatDuration(it) } ?: "—")
            }

            SectionLabel("Синди — больше всего кругов")
            RecordRow("Лучший результат", cindyBest?.let { (r, e) -> ProgressMath.cindyResult(r, e) } ?: "—")

            if (records.isNotEmpty()) {
                SectionLabel("Упражнения")
                records.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    val isOpen = expanded == r.exerciseId
                    ExerciseRecordRow(r, isOpen) { onToggle(r.exerciseId) }
                    if (isOpen) ExerciseHistory(history)
                }
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(Tabular), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExerciseRecordRow(r: ExerciseRecord, isOpen: Boolean, onClick: () -> Unit) {
    val best = r.bestReps
    val bestText = when {
        best == null -> "Лучший подход: —"
        r.bestWeightKg != null -> "Лучший подход: $best повт. · ${r.bestWeightKg} кг"
        else -> "Лучший подход: $best повт."
    }
    val totalWord = ProgressMath.repsWord((r.totalReps % 100).toInt())
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (isOpen) "Свернуть историю" else "Показать историю",
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.exerciseName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                bestText,
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.totalReps}", style = MaterialTheme.typography.titleMedium.merge(Tabular), fontWeight = FontWeight.Bold)
            Text(
                "$totalWord всего",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (isOpen) "▴" else "▾",
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** История упражнения за последние 30 тренировочных дней: «12 ноя — 2 подхода, 35 повт.». */
@Composable
private fun ExerciseHistory(history: List<Pair<Long, ExerciseTotal>>?) {
    Column(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            history == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.isEmpty() -> Text("Записей нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> history.forEach { (day, t) ->
                val weight = t.weightKg?.let { " · $it кг" } ?: ""
                Text(
                    "${ProgressMath.shortDate(day)}$weight — ${t.sets} ${ProgressMath.setsWord(t.sets)}, ${t.reps} повт.",
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                )
            }
        }
    }
}
