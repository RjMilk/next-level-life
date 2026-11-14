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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.nextlevellife.app.data.MealEntryLine
import com.nextlevellife.app.data.MealItem
import com.nextlevellife.app.data.Nutrition
import com.nextlevellife.app.data.NutritionMath
import com.nextlevellife.app.data.ProductVersion
import com.nextlevellife.app.data.Products
import com.nextlevellife.app.data.ProductsMath
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TimeSnapshot
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.media.MealPhotos
import com.nextlevellife.app.media.MealProcessing
import com.nextlevellife.app.pose.CameraPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

private const val TAG = "NutritionScreen"

/** Приём в ленте: есть ли файл фото здесь, снят ли он этим телефоном, его позиции (продукты). */
private data class MealRow(val meal: MealItem, val hasFile: Boolean, val mine: Boolean, val entries: List<MealEntryLine>)

private const val MODE_LIST = "list"
private const val MODE_CAMERA = "camera"
private const val MODE_PROCESSING = "processing"
private const val MODE_CONFIRM = "confirm"

/** Запись подтверждённого приёма (не обработка снимка: у неё своё ожидание итога). */
private const val MODE_SAVING = "saving"
private const val MODE_VIEW = "view"
private const val MODE_PRODUCTS = "products"
private const val MODE_PRODUCT_EDIT = "product_edit"
private const val MODE_PICK = "pick"
private const val MODE_SCAN = "scan"
private const val MODE_PORTION = "portion"

/**
 * «Питание»: съёмка еды камерой приложения, лента приёмов учётных суток (листание днями ‹ ›), окно питания
 * (первый и последний зафиксированный приём). Просмотр фото крупно, удаление с подтверждением. Заметка задаётся
 * только при съёмке. Минут и очков питание не даёт. Фото на второй телефон не передаются.
 * В приёме — продукты ([Products]): из своего списка, по штрихкоду или новой карточкой; сумма ккал и БЖУ у приёма
 * и за день. «Мои продукты» — список, поиск, правка и удаление карточек.
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
    // Карточка продукта: ключ сеанса правки (свои поля формы), продукт (null — новый), штрихкод со сканера,
    // вернуться ли после сохранения к выбору порции для приёма [viewUid].
    var editKey by rememberSaveable { mutableStateOf("") }
    var editProductUid by rememberSaveable { mutableStateOf<String?>(null) }
    var editBarcode by rememberSaveable { mutableStateOf<String?>(null) }
    var editForMeal by rememberSaveable { mutableStateOf(false) }
    // Выбранная версия продукта для позиции приёма [viewUid].
    var pickVersionUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pickMessage by remember { mutableStateOf<String?>(null) }
    // Идёт запись приёма в этом экземпляре экрана; после пересоздания — false, и экран возвращается к ленте.
    var saving by remember { mutableStateOf(false) }

    // Фото удалённых приёмов (в том числе удалённых на втором телефоне) и брошенные снимки — убрать.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { MealPhotos.prune(context) } } }

    val rows by produceState<List<MealRow>?>(null, version, day) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val me = runCatching { Ledger.deviceId() }.getOrNull()
                val entries = runCatching { Products.entriesForDay(day) }
                    .onFailure { Log.e(TAG, "Не удалось прочитать продукты приёмов", it) }.getOrDefault(emptyMap())
                Nutrition.mealsForDay(day).map {
                    MealRow(it, MealPhotos.exists(context, it.photoName), it.author == me, entries[it.uid].orEmpty())
                }
            }.onFailure { Log.e(TAG, "Не удалось прочитать приёмы", it) }.getOrDefault(emptyList())
        }
    }
    val products by produceState<List<ProductVersion>?>(null, version) {
        value = withContext(Dispatchers.IO) {
            runCatching { Products.live() }.onFailure { Log.e(TAG, "Не удалось прочитать продукты", it) }.getOrDefault(emptyList())
        }
    }

    fun openEditor(productUid: String?, barcode: String?, forMeal: Boolean) {
        editKey = UUID.randomUUID().toString()
        editProductUid = productUid
        editBarcode = barcode
        editForMeal = forMeal
        mode = MODE_PRODUCT_EDIT
    }

    fun openPortion(v: ProductVersion) {
        pickVersionUid = v.versionUid
        editKey = UUID.randomUUID().toString()
        mode = MODE_PORTION
    }

    fun openPicker() {
        pickMessage = null
        mode = MODE_PICK
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
            MODE_PROCESSING, MODE_SAVING -> Unit
            MODE_CONFIRM, MODE_CAMERA -> { dropPending(); mode = MODE_LIST }
            MODE_PICK -> mode = MODE_VIEW
            MODE_SCAN, MODE_PORTION -> openPicker()
            // Карточка продукта обрабатывает «Назад» сама (спрашивает, если есть несохранённые правки).
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
                            // Снимок принадлежит тому uid, под которым был создан его файл: поздний снимок отменённой
                            // съёмки не должен попасть под uid новой.
                            val uid = pendingUid
                            if (uid == null || raw.name != MealPhotos.rawFile(context, uid).name) {
                                raw.delete()
                                return@MealCameraView
                            }
                            mode = MODE_PROCESSING
                            // Обработка — вне экрана: поворот её не отменит; экран ждёт итог в MODE_PROCESSING.
                            MealProcessing.start(context, uid, raw)
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
                val uid = pendingUid
                LaunchedEffect(uid) {
                    if (uid == null) { mode = MODE_LIST; return@LaunchedEffect }
                    while (MealProcessing.isRunning(uid)) kotlinx.coroutines.delay(100)
                    val ok = MealProcessing.result(uid) ?: MealPhotos.pendingFile(context, uid).isFile
                    MealProcessing.forget(uid)
                    if (ok) {
                        mode = MODE_CONFIRM
                    } else {
                        dropPending()
                        message = "Не удалось сохранить снимок"
                        mode = MODE_LIST
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Сохраняю снимок…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            MODE_SAVING -> {
                // Экран пересоздан во время записи: запись дошла до конца в фоне, итог — в ленте.
                if (!saving) LaunchedEffect(Unit) { pendingUid = null; note = ""; mode = MODE_LIST }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Сохраняю приём…", style = MaterialTheme.typography.bodyMedium)
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
                            saving = true
                            mode = MODE_SAVING
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
                                saving = false
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
                MealView(
                    row,
                    onClose = { mode = MODE_LIST },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    Nutrition.deleteMeal(row.meal.uid)
                                    row.meal.photoName?.let { MealPhotos.delete(context, listOf(it)) }
                                }.onFailure { Log.e(TAG, "Не удалось удалить приём", it) }
                            }
                            mode = MODE_LIST
                        }
                    },
                    onAddProduct = { openPicker() },
                    onRemoveEntry = { line ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { Products.deleteEntry(line.entry.uid) }
                                .onFailure { Log.e(TAG, "Не удалось убрать продукт из приёма", it) }
                        }
                    },
                )
            }
            MODE_PRODUCTS -> ProductsListView(
                live = products,
                onBack = { mode = MODE_LIST },
                onNew = { openEditor(null, null, forMeal = false) },
                onOpen = { openEditor(it.productUid, null, forMeal = false) },
            )
            MODE_PRODUCT_EDIT -> {
                val live = products
                val back = if (editForMeal) MODE_PICK else MODE_PRODUCTS
                val stored = editProductUid?.let { uid -> live?.firstOrNull { it.productUid == uid } }
                when {
                    live == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    // Продукт удалили на другом телефоне, пока карточка была открыта.
                    editProductUid != null && stored == null -> LaunchedEffect(Unit) { mode = back }
                    else -> ProductEditorView(
                        editKey = editKey,
                        stored = stored,
                        initialBarcode = editBarcode,
                        live = live,
                        onClose = { if (editForMeal) openPicker() else mode = MODE_PRODUCTS },
                        onSaved = { v -> if (editForMeal) openPortion(v) else mode = MODE_PRODUCTS },
                        onDeleted = { mode = MODE_PRODUCTS },
                    )
                }
            }
            MODE_PICK -> ProductPickerView(
                live = products,
                message = pickMessage,
                onCancel = { mode = MODE_VIEW },
                onScan = { pickMessage = null; mode = MODE_SCAN },
                onNew = { openEditor(null, null, forMeal = true) },
                onPick = { openPortion(it) },
            )
            MODE_SCAN -> BarcodeScanView(
                onCancel = { openPicker() },
                onCode = { code ->
                    scope.launch {
                        val found = withContext(Dispatchers.IO) {
                            runCatching { ProductsMath.findByBarcode(Products.live(), code) }
                                .onFailure { Log.e(TAG, "Не удалось найти продукт по штрихкоду", it) }
                        }
                        found.fold(
                            // Знакомый — сразу порция; незнакомый — новая карточка с этим кодом.
                            onSuccess = { v -> if (v != null) openPortion(v) else openEditor(null, code, forMeal = true) },
                            onFailure = { openPicker(); pickMessage = "Не удалось прочитать продукты" },
                        )
                    }
                },
            )
            MODE_PORTION -> {
                val uid = pickVersionUid
                val v by produceState<ProductVersion?>(null, uid) {
                    value = uid?.let { withContext(Dispatchers.IO) { runCatching { Products.version(it) }.getOrNull() } }
                }
                PortionView(
                    key = editKey,
                    version = v,
                    onCancel = { openPicker() },
                    onAdd = { grams ->
                        val meal = viewUid
                        val ver = v
                        if (meal == null || ver == null) {
                            mode = MODE_LIST
                        } else {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { Products.addEntry(meal, ver, grams) }
                                        .onFailure { Log.e(TAG, "Не удалось добавить продукт в приём", it) }.isSuccess
                                }
                                if (ok) {
                                    mode = MODE_VIEW
                                } else {
                                    openPicker()
                                    pickMessage = "Не удалось добавить продукт"
                                }
                            }
                        }
                    },
                )
            }
            else -> MealList(
                day = day,
                today = today,
                rows = rows,
                message = message,
                onBack = onBack,
                onShoot = { startCamera() },
                onProducts = { mode = MODE_PRODUCTS },
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
    onProducts: () -> Unit,
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
        OutlinedButton(
            onClick = onProducts,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Мои продукты") }
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
        DayTotalsCard(rows?.flatMap { it.entries }.orEmpty())
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
                ProductsMath.mealLine(ProductsMath.totals(row.entries))?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * Приём крупно: фото, время, заметка, продукты (сумма, «Добавить продукт», «Убрать»), «Удалить приём»
 * с подтверждением. Прокручивается: фото — 4:3, без файла фото — короткая плашка с подписью.
 */
@Composable
private fun MealView(
    row: MealRow,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onAddProduct: () -> Unit,
    onRemoveEntry: (MealEntryLine) -> Unit,
) {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val meal = row.meal
    val today = TrustedClock.today()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("${dayName(meal.day, today)}, ${NutritionMath.timeText(meal.capturedUtcMs, meal.zoneId)}", "Назад", onClose)
        val bitmap by produceState<Bitmap?>(null, meal.photoName, row.hasFile) {
            val name = meal.photoName
            value = if (name != null && row.hasFile) withContext(Dispatchers.IO) { MealPhotos.full(context, name) } else null
        }
        if (row.hasFile) {
            PhotoBox(bitmap, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        } else {
            PhotoBox(null, Modifier.fillMaxWidth().height(96.dp), placeholder = photoPlaceholder(row))
        }
        meal.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        MealEntriesSection(row.entries, onAdd = onAddProduct, onRemove = onRemoveEntry)
        OutlinedButton(
            onClick = { confirm = true },
            shape = ButtonShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Удалить приём") }
        Spacer(Modifier.height(12.dp))
    }
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
