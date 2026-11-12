package com.nextlevellife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Столбики прошлых дней: нейтральные, чтобы сегодняшний выделялся. */
private val BarColor = Color(0xFF4B5263)

// ---------- Календарь ----------

@Composable
internal fun CalendarCard(
    month: Int,
    today: Long,
    selected: Long,
    trained: Set<Long>,
    onMonth: (Int) -> Unit,
    onSelect: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowButton("‹", "Предыдущий месяц", enabled = true) { onMonth(month - 1) }
                Text(
                    ProgressMath.monthTitle(month),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                ArrowButton("›", "Следующий месяц", enabled = ProgressMath.canGoNext(month, today)) {
                    onMonth(ProgressMath.nextMonth(month, today))
                }
            }
            Row(Modifier.padding(vertical = 4.dp)) {
                ProgressMath.weekdayHeaders.forEach {
                    Text(
                        it,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ProgressMath.monthGrid(month).chunked(7).forEach { week ->
                Row {
                    week.forEach { day ->
                        val cell = Modifier
                            .weight(1f)
                            .height(48.dp)
                        if (day == null) {
                            Box(cell)
                        } else {
                            DayCell(
                                day = day,
                                isToday = day == today,
                                isSelected = day == selected,
                                isTrained = day in trained,
                                isFuture = day > today,
                                label = ProgressMath.dayTitle(day, today),
                                onClick = { onSelect(day) },
                                modifier = cell,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Long,
    isToday: Boolean,
    isSelected: Boolean,
    isTrained: Boolean,
    isFuture: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .padding(1.dp)
            .clip(shape)
            .then(
                if (isSelected) Modifier
                    .background(Accent.copy(alpha = 0.18f))
                    .border(1.5.dp, Accent, shape)
                else Modifier
            )
            .clickable(enabled = !isFuture, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (isTrained) "$label, была тренировка" else label
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isTrained) Accent else Color.Transparent)
                .then(if (isToday) Modifier.border(2.dp, onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ProgressMath.dayOfMonth(day).toString(),
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                fontWeight = if (isTrained || isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isTrained -> OnAccent
                    isFuture -> onSurface.copy(alpha = 0.3f)
                    else -> onSurface
                },
            )
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.25f),
        )
    }
}

// ---------- Диаграмма за 14 дней ----------

@Composable
internal fun ChartCard(
    days: List<Long>,
    reps: List<Int>,
    earnedMs: Long,
    today: Long,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    val total = reps.sum()
    val fractions = ProgressMath.barFractions(reps)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Последние ${days.size} дней", style = MaterialTheme.typography.titleMedium)
            Text(
                "За ${days.size} дней: $total ${ProgressMath.repsWord(total)}, +${ProgressMath.formatEarned(earnedMs)}",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            ) {
                days.forEachIndexed { i, day ->
                    val isToday = day == today
                    val isSelected = day == selected
                    val count = reps.getOrElse(i) { 0 }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable(role = Role.Button) { onSelect(day) }
                            .semantics {
                                contentDescription =
                                    "${ProgressMath.shortDate(day)}: $count ${ProgressMath.repsWord(count)}"
                                this.selected = isSelected
                            }
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            val f = fractions.getOrElse(i) { 0f }
                            if (f > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.75f)
                                        .fillMaxHeight(f)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(
                                            when {
                                                isToday -> Accent
                                                isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> BarColor
                                            }
                                        ),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(2.dp)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                                )
                            }
                        }
                        Text(
                            ProgressMath.dayOfMonth(day).toString(),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall.merge(Tabular),
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
