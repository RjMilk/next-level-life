package com.nextlevellife.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.CustomProgramMath
import com.nextlevellife.app.data.CustomPrograms
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Program
import com.nextlevellife.app.data.ProgramMath
import com.nextlevellife.app.data.ProgramStep
import com.nextlevellife.app.data.StepField
import com.nextlevellife.app.data.Weights

// ---------------------------------------------------------------------------------------------
// Тексты редактора (без Compose): проверяются тестами
// ---------------------------------------------------------------------------------------------

internal object ProgramEditorText {
    /** Неразрывный пробел: «12 мин» не разрывается на узком экране. */
    private const val NBSP = ' '

    /** «≈12 мин · до +15 мин экранного времени» (у гиревых — «… с гирей 16 кг»); без шагов — null. */
    fun estimate(p: Program): String? {
        if (p.steps.isEmpty()) return null
        val minutes = "≈${ProgramMath.estimateMinutes(p)}${NBSP}мин"
        val reward = formatShort(ProgramMath.maxRewardMs(p)).replace(' ', NBSP)
        val kb = if (p.kettlebell) " с гирей ${Weights.DEFAULT}${NBSP}кг" else ""
        return "$minutes · до +$reward экранного времени$kb"
    }

    /** Подпись цели: «Повторений», у удержания — «Секунд». */
    fun targetLabel(step: ProgramStep): String = if (step.hold) "Секунд" else "Повторений"

    /** «Удалить программу «Утро»?». */
    fun deleteTitle(title: String): String = "Удалить программу «$title»?"
}

/** Шаги редактора в сохраняемом состоянии: id упражнения, подходы, цель, отдых. */
private val StepsSaver = listSaver<List<ProgramStep>, Any>(
    save = { steps -> steps.flatMap { listOf(it.exercise.id, it.sets, it.target, it.restSeconds) } },
    restore = { v ->
        v.chunked(4).mapNotNull { c ->
            CustomProgramMath.exerciseById(c[0] as String)?.let { ProgramStep(it, c[1] as Int, c[2] as Int, c[3] as Int) }
        }
    },
)

/** Выбор упражнения закрыт. */
private const val PICK_NONE = -2

/** Выбор упражнения для нового шага. */
private const val PICK_ADD = -1

/**
 * Редактор своей домашней программы ([CustomPrograms]): название, шаги (упражнение каталога, подходы,
 * цель, отдых; вверх/вниз/удалить), «Добавить упражнение», внизу — длительность и награда по ценам
 * готовых программ ([ProgramMath]). «Сохранить» неактивна, пока программа невалидна, рядом — причина.
 * [programId] — своя программа или null (новая: без названия, «Приседания» 3×10, отдых 60 с).
 * [onClose] — назад без сохранения, [onSaved] — сохранена (id), [onDeleted] — удалена.
 */
@Composable
fun ProgramEditorScreen(
    programId: String?,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val stored = remember(programId) { programId?.let { CustomPrograms.byId(it) } }
    val isNew = stored == null
    val initial = remember(stored) { stored ?: CustomProgramMath.newProgram() }
    val count = remember { CustomPrograms.count() }

    var title by rememberSaveable(programId) { mutableStateOf(initial.title) }
    var steps by rememberSaveable(programId, stateSaver = StepsSaver) { mutableStateOf(initial.steps) }
    var pickFor by rememberSaveable { mutableIntStateOf(PICK_NONE) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableIntStateOf(0) }

    val dirty = title != initial.title || steps != initial.steps
    val problem = CustomProgramMath.problem(title, steps.size, isNew, count)
    val preview = CustomProgramMath.build(stored?.id ?: "", title, steps)

    fun close() {
        if (dirty) confirmExit = true else onClose()
    }

    fun save() {
        if (problem != null) return
        val saved = CustomPrograms.save(preview)
        if (saved == null) error = "Не удалось сохранить программу" else onSaved(saved.id)
    }

    BackHandler { close() }
    // Сообщение о сбое записи — до следующей правки.
    LaunchedEffect(title, steps) { error = null }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val widthClass = currentWidthClass()
    val scroll = rememberScrollState()
    // Новый шаг — внизу списка: прокрутить к нему.
    LaunchedEffect(added) { if (added > 0) scroll.animateScrollTo(scroll.maxValue) }

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
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (isNew) "Новая программа" else "Своя программа",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Accent,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.replace('\n', ' ').take(CustomProgramMath.TITLE_MAX) },
                    label = { Text("Название") },
                    placeholder = { Text("Например, Утро дома") },
                    singleLine = true,
                    supportingText = { Text("${title.trim().length} из ${CustomProgramMath.TITLE_MAX}") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Каждый подход засчитывает камера и оплачивает по обычной цене упражнения, как в готовых " +
                        "программах. Бонуса за прохождение нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                steps.forEachIndexed { i, step ->
                    StepCard(
                        index = i,
                        count = steps.size,
                        step = step,
                        onChange = { changed -> steps = steps.toMutableList().also { it[i] = changed } },
                        onPick = { pickFor = i },
                        onMove = { delta ->
                            val j = i + delta
                            if (j in steps.indices) {
                                steps = steps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
                            }
                        },
                        onDelete = { steps = steps.toMutableList().also { it.removeAt(i) } },
                    )
                }
                val canAdd = steps.size < CustomProgramMath.STEPS_MAX
                OutlinedButton(
                    onClick = { pickFor = PICK_ADD },
                    enabled = canAdd,
                    shape = ButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(
                        if (canAdd) "+ Добавить упражнение" else "Не больше ${CustomProgramMath.STEPS_MAX} упражнений",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (!isNew) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Удалить программу", color = Bad) }
                }
                Text(
                    "Длительность примерная: перестановка телефона и потеря распознавания её увеличивают.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Итог и кнопки — закреплены внизу.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgramEditorText.estimate(preview)?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall.merge(Tabular))
                }
                val reason = error ?: problem
                if (reason != null) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = ::close,
                        shape = ButtonShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) { Text("Назад", style = MaterialTheme.typography.titleMedium) }
                    Button(
                        onClick = ::save,
                        enabled = problem == null,
                        shape = ButtonShape,
                        modifier = Modifier
                            .weight(2f)
                            .heightIn(min = 56.dp),
                    ) { Text("Сохранить", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    if (pickFor != PICK_NONE) {
        val current = steps.getOrNull(pickFor)?.exercise
        ExercisePicker(
            selected = current,
            onPick = { ex ->
                if (pickFor == PICK_ADD) {
                    if (steps.size < CustomProgramMath.STEPS_MAX) {
                        val rest = steps.lastOrNull()?.restSeconds ?: CustomProgramMath.DEFAULT_REST
                        steps = steps + CustomProgramMath.defaultStep(ex, rest)
                        added++
                    }
                } else {
                    val i = pickFor
                    if (i in steps.indices) steps = steps.toMutableList().also { it[i] = CustomProgramMath.withExercise(it[i], ex) }
                }
                pickFor = PICK_NONE
            },
            onDismiss = { pickFor = PICK_NONE },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onClose() }) { Text("Выйти", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } },
            title = { Text("Выйти без сохранения?") },
            text = { Text("Изменения в программе не сохранятся.") },
        )
    }

    if (confirmDelete && stored != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (CustomPrograms.delete(stored.id)) onDeleted() else error = "Не удалось удалить программу"
                }) { Text("Удалить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            title = { Text(ProgramEditorText.deleteTitle(stored.title)) },
            text = { Text("История прохождений останется.") },
        )
    }
}

/** Шаг: упражнение (нажатие — выбор), подходы, цель, отдых; кнопки вверх/вниз/удалить. */
@Composable
private fun StepCard(
    index: Int,
    count: Int,
    step: ProgramStep,
    onChange: (ProgramStep) -> Unit,
    onPick: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = RoutineCardColor)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Упражнение ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                GlyphButton("↑", "Поднять упражнение ${index + 1} выше", enabled = index > 0) { onMove(-1) }
                GlyphButton("↓", "Опустить упражнение ${index + 1} ниже", enabled = index < count - 1) { onMove(1) }
                GlyphButton("✕", "Удалить упражнение ${index + 1}", enabled = true, color = Bad, onClick = onDelete)
            }
            OutlinedButton(
                onClick = onPick,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "Упражнение: ${step.exercise.name}. Выбрать другое" },
            ) {
                Text(
                    step.exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = MaterialTheme.typography.titleMedium, color = muted)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Широкая карточка (Fold, планшет) — три счётчика в ряд, узкая — друг под другом.
                val stacked = maxWidth >= 480.dp
                val fields: List<Triple<String, String, StepField>> = listOf(
                    Triple("Подходов", step.sets.toString(), StepField.SETS),
                    Triple(ProgramEditorText.targetLabel(step), step.target.toString(), StepField.TARGET),
                    Triple("Отдых, с", step.restSeconds.toString(), StepField.REST),
                )
                val stepper: @Composable (Triple<String, String, StepField>, Modifier) -> Unit = { (label, value, field), mod ->
                    Stepper(
                        label = label,
                        value = value,
                        canMinus = CustomProgramMath.canChange(step, field, -1),
                        canPlus = CustomProgramMath.canChange(step, field, 1),
                        onDelta = { d ->
                            onChange(
                                when (field) {
                                    StepField.SETS -> CustomProgramMath.withSets(step, d)
                                    StepField.TARGET -> CustomProgramMath.withTarget(step, d)
                                    StepField.REST -> CustomProgramMath.withRest(step, d)
                                }
                            )
                        },
                        stacked = stacked,
                        modifier = mod,
                    )
                }
                if (stacked) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fields.forEach { stepper(it, Modifier.weight(1f)) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        fields.forEach { stepper(it, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

/** Счётчик «подпись − значение +»; [stacked] — подпись над кнопками (для ряда из трёх). */
@Composable
private fun Stepper(
    label: String,
    value: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onDelta: (Int) -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton("−", "$label: меньше", canMinus) { onDelta(-1) }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.merge(Tabular),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 52.dp),
            )
            RoundButton("+", "$label: больше", canPlus) { onDelta(1) }
        }
    }
    if (stacked) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            controls()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            controls()
        }
    }
}

@Composable
private fun RoundButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) Accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = if (enabled) Accent else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun GlyphButton(glyph: String, description: String, enabled: Boolean, color: Color = Accent, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = color),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}

/** Выбор упражнения из каталога: группы «Свой вес» и «Гиря»; у удержания — пометка «секунды». */
@Composable
private fun ExercisePicker(selected: Exercise?, onPick: (Exercise) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Упражнение") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickerGroup("Свой вес", CustomProgramMath.bodyweight, selected, onPick)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PickerGroup("Гиря", CustomProgramMath.kettlebell, selected, onPick)
            }
        },
    )
}

@Composable
private fun PickerGroup(title: String, items: List<Exercise>, selected: Exercise?, onPick: (Exercise) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(title, style = MaterialTheme.typography.titleSmall, color = muted, modifier = Modifier.padding(vertical = 4.dp))
    items.forEach { ex ->
        val isSelected = ex == selected
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = { onPick(ex) })
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ex.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Accent else MaterialTheme.colorScheme.onSurface,
                )
                if (ex.hold) Text("удержание, цель в секундах", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (isSelected) Text("✓", style = MaterialTheme.typography.titleMedium, color = Accent)
        }
    }
}
