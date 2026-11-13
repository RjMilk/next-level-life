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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.health.ActivityMath

@Composable
internal fun RecordsCard(
    minotaurBest: List<Pair<Int, Long?>>,
    cindyBest: Pair<Int, Int>?,
    records: List<ExerciseRecord>,
    expanded: String?,
    history: List<Pair<Long, ExerciseTotal>>?,
    onToggle: (String) -> Unit,
    bestStepsDay: Pair<Long, Long>? = null,
    longestRun: ActivityRun? = null,
    today: Long = 0L,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Рекорды", style = MaterialTheme.typography.titleMedium)

            if (bestStepsDay != null || longestRun != null) {
                SectionLabel("Шаги и бег")
                bestStepsDay?.let { (day, steps) ->
                    RecordRow(
                        "Больше всего шагов · ${ProgressMath.dateLabel(day, today)}",
                        ActivityMath.formatThousands(steps),
                    )
                }
                longestRun?.let { r ->
                    RecordRow(
                        "Самая длинная пробежка · ${ProgressMath.dateLabel(r.day, today)}",
                        ActivityMath.formatKm(r.distanceM),
                    )
                }
            }

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
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(Tabular))
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
            Text(r.exerciseName, style = MaterialTheme.typography.titleSmall)
            Text(
                bestText,
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.totalReps}", style = MaterialTheme.typography.titleMedium.merge(Tabular))
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

/** История упражнения за последние 30 тренировочных дней: «12 ноя · 24 кг» слева, «2 подхода · 35 повт.» справа. */
@Composable
private fun ExerciseHistory(history: List<Pair<Long, ExerciseTotal>>?) {
    Column(
        Modifier.padding(start = 8.dp, end = 28.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            history == null -> Text(
                "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            history.isEmpty() -> Text(
                "Записей нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> history.forEach { (day, t) -> StatRow(ProgressMath.historyCells(day, t)) }
        }
    }
}
