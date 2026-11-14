package com.nextlevellife.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.MealItem
import com.nextlevellife.app.data.Nutrition
import com.nextlevellife.app.data.NutritionMath
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TimeSnapshot
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.media.MealPhotos
import com.nextlevellife.app.pose.CameraPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

private const val TAG = "NutritionScreen"

/** Приём в ленте: есть ли файл фото здесь и снят ли он этим телефоном. */
private data class MealRow(val meal: MealItem, val hasFile: Boolean, val mine: Boolean)

private const val MODE_LIST = "list"
private const val MODE_CAMERA = "camera"
private const val MODE_PROCESSING = "processing"
private const val MODE_CONFIRM = "confirm"
private const val MODE_VIEW = "view"

/**
 * «Питание»: съёмка еды камерой приложения, лента приёмов учётных суток (листание днями ‹ ›), окно питания
 * (первый и последний зафиксированный приём). Просмотр фото крупно, удаление с подтверждением. Заметка задаётся
 * только при съёмке. Минут и очков питание не даёт. Фото на второй телефон не передаются.
 */
@Composable
fun NutritionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    val today = TrustedClock.today()
    var day by rememberSaveable { mutableStateOf(today) }
    var mode by rememberSaveable { mutableStateOf(MODE_LIST) }
    // Снимок до сохранения: uid (имя файла) и момент нажатия на спуск (доверенное время и учётный пояс).
    var pendingUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUtc by rememberSaveable { mutableStateOf(0L) }
    var pendingZone by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var viewUid by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Фото удалённых приёмов (в том числе удалённых на втором телефоне) и брошенные снимки — убрать.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { MealPhotos.prune(context) } } }

    val rows by produceState<List<MealRow>?>(null, version, day) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val me = runCatching { Ledger.deviceId() }.getOrNull()
                Nutrition.mealsForDay(day).map { MealRow(it, MealPhotos.exists(context, it.photoName), it.author == me) }
            }.onFailure { Log.e(TAG, "Не удалось прочитать приёмы", it) }.getOrDefault(emptyList())
        }
    }

    fun dropPending() {
        pendingUid?.let { uid -> runCatching { MealPhotos.pendingFile(context, uid).delete() } }
        pendingUid = null
        note = ""
    }

    fun startCamera() {
        dropPending()
        message = null
        pendingUid = UUID.randomUUID().toString()
        mode = MODE_CAMERA
    }

    BackHandler {
        when (mode) {
            MODE_LIST -> onBack()
            MODE_PROCESSING -> Unit
            MODE_CONFIRM, MODE_CAMERA -> { dropPending(); mode = MODE_LIST }
            else -> mode = MODE_LIST
        }
    }

    val widthClass = currentWidthClass()
    Column(
        Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(widthClass.outerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (mode) {
            MODE_CAMERA -> {
                Header("Снять еду", "Отмена") { dropPending(); mode = MODE_LIST }
                CameraPermissionGate(Modifier.fillMaxWidth().weight(1f)) {
                    MealCameraView(
                        modifier = Modifier.fillMaxSize(),
                        rawFile = {
                            val uid = pendingUid ?: UUID.randomUUID().toString().also { pendingUid = it }
                            MealPhotos.rawFile(context, uid)
                        },
                        onShutter = {
                            val t = TrustedClock.snapshot()
                            pendingUtc = t.utcMs
                            pendingZone = t.zone.id
                        },
                        onCaptured = { raw ->
                            val uid = pendingUid ?: return@MealCameraView
                            mode = MODE_PROCESSING
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { MealPhotos.process(raw, MealPhotos.pendingFile(context, uid)) }
                                        .onFailure { Log.e(TAG, "Не удалось обработать снимок", it) }
                                        .also { raw.delete() }
                                        .isSuccess
                                }
                                if (ok) {
                                    mode = MODE_CONFIRM
                                } else {
                                    dropPending()
                                    message = "Не удалось сохранить снимок"
                                    mode = MODE_LIST
                                }
                            }
                        },
                        onError = { message = it },
                    )
                }
                Text(
                    message ?: "Время приёма поставится по моменту снимка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MODE_PROCESSING -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Сохраняю снимок…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            MODE_CONFIRM -> {
                val uid = pendingUid
                if (uid == null) {
                    LaunchedEffect(Unit) { mode = MODE_LIST }
                    return@Column
                }
                val time = TimeSnapshot(pendingUtc, runCatching { ZoneId.of(pendingZone) }.getOrDefault(TrustedClock.zone()))
                Header("Приём в ${NutritionMath.timeText(time.utcMs, time.zone.id)}", "Отмена") { dropPending(); mode = MODE_LIST }
                val bitmap by produceState<Bitmap?>(null, uid) {
                    value = withContext(Dispatchers.IO) { MealPhotos.pendingBitmap(context, uid, 1200) }
                }
                PhotoBox(bitmap, Modifier.fillMaxWidth().weight(1f))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(NutritionMath.MAX_NOTE_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Заметка (необязательно)") },
                    supportingText = { Text("Потом её не изменить · ${note.length}/${NutritionMath.MAX_NOTE_CHARS}") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { startCamera() },
                        shape = ButtonShape,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text("Переснять") }
                    Button(
                        onClick = {
                            val n = note
                            mode = MODE_PROCESSING
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val hasPhoto = MealPhotos.commit(context, uid)
                                        Nutrition.addMeal(uid, time, hasPhoto, n)
                                    }.onFailure { Log.e(TAG, "Не удалось записать приём", it) }.isSuccess
                                }
                                if (ok) {
                                    pendingUid = null
                                    note = ""
                                    day = time.day
                                    message = null
                                } else {
                                    runCatching { MealPhotos.delete(context, listOf(NutritionMath.photoNameFor(uid))) }
                                    dropPending()
                                    message = "Не удалось записать приём"
                                }
                                mode = MODE_LIST
                            }
                        },
                        shape = ButtonShape,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text("Сохранить") }
                }
            }
            MODE_VIEW -> {
                val row = rows?.firstOrNull { it.meal.uid == viewUid }
                if (row == null) {
                    // Приём удалён (здесь или на втором телефоне) — назад к ленте.
                    if (rows != null) LaunchedEffect(Unit) { mode = MODE_LIST }
                    return@Column
                }
                MealView(row, onClose = { mode = MODE_LIST }, onDelete = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                Nutrition.deleteMeal(row.meal.uid)
                                row.meal.photoName?.let { MealPhotos.delete(context, listOf(it)) }
                            }.onFailure { Log.e(TAG, "Не удалось удалить приём", it) }
                        }
                        mode = MODE_LIST
                    }
                })
            }
            else -> MealList(
                day = day,
                today = today,
                rows = rows,
                message = message,
                onBack = onBack,
                onShoot = { startCamera() },
                onDay = { day = it.coerceAtMost(today) },
                onOpen = { viewUid = it; mode = MODE_VIEW },
            )
        }
    }
}

@Composable
private fun Header(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

private fun dayName(day: Long, today: Long): String = when (day) {
    today -> "Сегодня"
    today - 1 -> "Вчера"
    else -> ProgressMath.dayTitle(day, today)
}

@Composable
private fun MealList(
    day: Long,
    today: Long,
    rows: List<MealRow>?,
    message: String?,
    onBack: () -> Unit,
    onShoot: () -> Unit,
    onDay: (Long) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Питание", "Готово", onBack)
        Button(
            onClick = onShoot,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) { Text("Снять еду", style = MaterialTheme.typography.titleMedium) }
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        // Листание дней: ‹ раньше, › позже (не дальше сегодняшнего).
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDay(day - 1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("‹", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                dayName(day, today),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onDay(day + 1) }, enabled = day < today, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        val meals = rows?.map { it.meal }.orEmpty()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Окно питания", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        rows == null -> "…"
                        else -> NutritionMath.windowText(meals) ?: "Приёмов не было"
                    },
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                    color = if (meals.isNotEmpty()) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "По зафиксированным приёмам: первый и последний снимок за сутки. Пропущенный снимок сокращает окно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rows?.forEach { row -> MealCard(row, onClick = { onOpen(row.meal.uid) }) }
        Spacer(Modifier.heightIn(min = 12.dp))
    }
}

/** Подпись вместо фото: снимок на втором телефоне, потерян или не делался. */
private fun photoPlaceholder(row: MealRow): String = when {
    row.meal.photoName == null -> "Без фото"
    row.mine -> "Фото не сохранилось"
    else -> "Фото на другом телефоне"
}

@Composable
private fun MealCard(row: MealRow, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, row.meal.photoName, row.hasFile) {
        val name = row.meal.photoName
        value = if (name != null && row.hasFile) withContext(Dispatchers.IO) { MealPhotos.thumbnail(context, name, 256) } else null
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть приём", onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val b = thumb
                if (b != null) {
                    Image(b.asImageBitmap(), contentDescription = "Фото приёма", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (!row.hasFile) {
                    Text(
                        photoPlaceholder(row),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    NutritionMath.timeText(row.meal.capturedUtcMs, row.meal.zoneId),
                    style = MaterialTheme.typography.titleMedium.merge(Tabular),
                )
                row.meal.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoBox(bitmap: Bitmap?, modifier: Modifier, placeholder: String? = null) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = "Фото приёма", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            placeholder != null -> Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator()
        }
    }
}

/** Приём крупно: фото, время, заметка, «Удалить» с подтверждением. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MealView(row: MealRow, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val meal = row.meal
    val today = TrustedClock.today()
    Header("${dayName(meal.day, today)}, ${NutritionMath.timeText(meal.capturedUtcMs, meal.zoneId)}", "Назад", onClose)
    val bitmap by produceState<Bitmap?>(null, meal.photoName, row.hasFile) {
        val name = meal.photoName
        value = if (name != null && row.hasFile) withContext(Dispatchers.IO) { MealPhotos.full(context, name) } else null
    }
    PhotoBox(bitmap, Modifier.fillMaxWidth().weight(1f), placeholder = if (row.hasFile) null else photoPlaceholder(row))
    meal.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    OutlinedButton(
        onClick = { confirm = true },
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("Удалить") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить приём?") },
            text = { Text("Приём исчезнет из дневника на обоих телефонах, фото будет удалено.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

/** Компактная карточка «Питание» на главной: сегодня N приёмов и окно; нажатие — экран питания. */
@Composable
internal fun NutritionHomeCard(onOpen: () -> Unit) {
    val version by Store.version.collectAsState()
    val today = TrustedClock.today()
    val line by produceState<String?>(null, version, today) {
        value = withContext(Dispatchers.IO) {
            runCatching { NutritionMath.homeLine(Nutrition.mealsForDay(today)) }.getOrNull()
        }
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть питание", onClick = onOpen),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Питание", style = MaterialTheme.typography.titleMedium)
                Text(
                    line ?: " ",
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
