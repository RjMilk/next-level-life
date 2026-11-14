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
import com.nextlevellife.app.data.Program
import com.nextlevellife.app.data.ProgramMath
import com.nextlevellife.app.data.Programs

/** «4 программы» — сводка свёрнутой группы «Домашние тренировки» на главной. */
internal fun programsSummary(count: Int = Programs.all.size): String =
    "$count ${pluralRu(count, "программа", "программы", "программ")}"

/**
 * Карточки домашних программ для главной — в стиле карточек комплексов: название акцентом,
 * «4 упражнения · 12 подходов · ≈20 мин», состав и «Награда до +…» (за 16 кг у гиревых).
 * [onOpen] получает id программы (навигация «complex:<id>» → [ProgramScreen]).
 */
@Composable
fun ProgramCards(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Programs.all.forEach { ProgramCard(it) { onOpen(it.id) } }
    }
}

@Composable
private fun ProgramCard(program: Program, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RoutineCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(program.title, style = MaterialTheme.typography.titleLarge, color = Accent)
            Text(ProgramText.summary(program), style = MaterialTheme.typography.bodyMedium.merge(Tabular))
            Text(
                program.steps.joinToString(", ") { "${it.exercise.name} ${ProgramText.scheme(it)}" },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            val reward = "Награда до +${formatShort(ProgramMath.maxRewardMs(program))}" +
                if (program.kettlebell) " с гирей 16 кг" else ""
            Text(reward, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
        }
    }
}
