package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Routine
import com.nextlevellife.app.data.RoutineMath
import com.nextlevellife.app.data.Routines

/**
 * Карточки программ ЛФК и йоги для главной — в стиле карточек комплексов (тёмно-янтарный фон, заголовок
 * акцентом): «ЛФК · 9 мин · 8 упражнений», «Награда до +1 мин 30 сек». [onOpen] получает id программы
 * (навигация «complex:<id>» → [RoutineScreen]). [showHeader] — подпись группы «Мягкая подвижность»
 * с пометкой про ЛФК (каталог, раздел 2); выключить, если заголовок группы рисует сам экран.
 */
@Composable
fun RoutineCards(onOpen: (String) -> Unit, showHeader: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeader) {
            Column {
                Text(Routines.GROUP_TITLE, style = MaterialTheme.typography.titleMedium)
                Text(
                    Routines.GROUP_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Routines.all.forEach { RoutineCard(it) { onOpen(it.id) } }
    }
}

@Composable
private fun RoutineCard(routine: Routine, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RoutineCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(routine.title, style = MaterialTheme.typography.titleLarge, color = Accent)
            Text(RoutineText.summary(routine), style = MaterialTheme.typography.bodyMedium.merge(Tabular))
            Text(
                "Награда до +${formatShort(RoutineMath.maxRewardMs(routine))}",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
