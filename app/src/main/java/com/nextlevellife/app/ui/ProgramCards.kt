package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.CustomProgramMath
import com.nextlevellife.app.data.Program
import com.nextlevellife.app.data.ProgramMath
import com.nextlevellife.app.data.Programs

/** «4 программы» — сводка свёрнутой группы «Домашние тренировки» на главной (готовые и свои). */
internal fun programsSummary(count: Int = Programs.all.size): String =
    "$count ${pluralRu(count, "программа", "программы", "программ")}"

/**
 * Карточки домашних программ для главной — в стиле карточек комплексов: название акцентом,
 * «4 упражнения · 12 подходов · ≈20 мин», состав и «Награда до +…» (за 16 кг у гиревых).
 * После готовых — свои программы [custom] (с кнопкой «Изменить») и карточка «Своя программа +»;
 * при 10 своих она неактивна. [onOpen] получает id программы (навигация «complex:<id>» → [ProgramScreen]),
 * [onEdit] — id своей программы или null для новой (→ [ProgramEditorScreen]).
 */
@Composable
fun ProgramCards(custom: List<Program>, onOpen: (String) -> Unit, onEdit: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Programs.all.forEach { ProgramCard(it, onClick = { onOpen(it.id) }) }
        custom.forEach { ProgramCard(it, onClick = { onOpen(it.id) }, onEdit = { onEdit(it.id) }) }
        NewProgramCard(enabled = CustomProgramMath.canCreate(custom.size), onClick = { onEdit(null) })
    }
}

@Composable
private fun ProgramCard(program: Program, onClick: () -> Unit, onEdit: (() -> Unit)? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RoutineCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onEdit == null) {
                Text(program.title, style = MaterialTheme.typography.titleLarge, color = Accent)
            } else {
                // Своя программа: «Изменить» справа от названия, название переносится.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        program.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Accent,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onEdit) { Text("Изменить") }
                }
            }
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

/** «Своя программа +» — открывает редактор; при 10 своих неактивна с подписью «Не больше 10 программ». */
@Composable
private fun NewProgramCard(enabled: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Своя программа +",
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) Accent else muted,
            )
            Text(
                if (enabled) "Собери программу из упражнений каталога" else "Не больше ${CustomProgramMath.PROGRAMS_MAX} программ",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}
