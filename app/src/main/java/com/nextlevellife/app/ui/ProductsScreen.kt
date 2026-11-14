package com.nextlevellife.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.MealEntryLine
import com.nextlevellife.app.data.ProductField
import com.nextlevellife.app.data.ProductVersion
import com.nextlevellife.app.data.Products
import com.nextlevellife.app.data.ProductsMath
import com.nextlevellife.app.pose.CameraPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "ProductsScreen"

/** Неразрывный пробел: «150 г» не разрывается на узком экране. */
private const val NBSP = ' '

@Composable
private fun ProductHeader(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQuery(it.replace('\n', ' ').take(ProductsMath.NAME_MAX)) },
        label = { Text("Поиск по названию или штрихкоду") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** «Штрихкод 4006381333931 · порция 150 г»; ничего нет — null. */
private fun productExtra(v: ProductVersion): String? {
    val parts = ArrayList<String>()
    v.barcode?.let { parts += "Штрихкод $it" }
    v.portionG?.let { parts += "порция $it${NBSP}г" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun ProductCard(v: ProductVersion, clickLabel: String, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(v.name, style = MaterialTheme.typography.titleMedium)
                Text(ProductsMath.per100Text(v), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
                productExtra(v)?.let { Text(it, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted) }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = muted)
        }
    }
}

/** Список продуктов с поиском; [live] null — ещё загружается. */
@Composable
private fun ProductList(live: List<ProductVersion>?, query: String, empty: String, clickLabel: String, onOpen: (ProductVersion) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        live == null -> CircularProgressIndicator(Modifier.padding(16.dp))
        live.isEmpty() -> Text(empty, style = MaterialTheme.typography.bodyMedium, color = muted)
        else -> {
            val found = ProductsMath.search(live, query)
            if (found.isEmpty()) {
                Text("Ничего не нашлось", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            found.forEach { v -> ProductCard(v, clickLabel) { onOpen(v) } }
        }
    }
}

/** «Мои продукты»: свои карточки, поиск, «Новый продукт»; нажатие — правка. */
@Composable
internal fun ProductsListView(
    live: List<ProductVersion>?,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (ProductVersion) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductHeader("Мои продукты", "Готово", onBack)
        Button(
            onClick = onNew,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Новый продукт", style = MaterialTheme.typography.titleMedium) }
        Text(
            "Карточки вводишь сам: название, штрихкод и пищевая ценность с упаковки. Продукты общие для обоих " +
                "телефонов. Питание минут и очков не даёт.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!live.isNullOrEmpty()) SearchField(query) { query = it }
        ProductList(
            live = live,
            query = query,
            empty = "Своих продуктов пока нет. Добавь вручную или отсканируй штрихкод, когда добавляешь продукт в приём.",
            clickLabel = "Изменить продукт",
            onOpen = onOpen,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** Выбор продукта для приёма: сканер, «Новый продукт», поиск и список. */
@Composable
internal fun ProductPickerView(
    live: List<ProductVersion>?,
    message: String?,
    onCancel: () -> Unit,
    onScan: () -> Unit,
    onNew: () -> Unit,
    onPick: (ProductVersion) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductHeader("Добавить продукт", "Отмена", onCancel)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onScan,
                shape = ButtonShape,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text("Сканировать", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = onNew,
                shape = ButtonShape,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text("Новый продукт", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (!live.isNullOrEmpty()) SearchField(query) { query = it }
        ProductList(
            live = live,
            query = query,
            empty = "Своих продуктов пока нет: отсканируй штрихкод или заведи продукт вручную.",
            clickLabel = "Выбрать продукт",
            onOpen = onPick,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** Сканер штрихкода: камера с проверкой разрешения; [onCode] — проверенный код (один раз). */
@Composable
internal fun ColumnScope.BarcodeScanView(onCancel: () -> Unit, onCode: (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    ProductHeader("Штрихкод", "Отмена", onCancel)
    CameraPermissionGate(Modifier.fillMaxWidth().weight(1f)) {
        BarcodeScannerView(
            modifier = Modifier.fillMaxSize(),
            onBarcode = onCode,
            onError = { error = it },
        )
    }
    Text(
        error ?: "Наведи камеру на штрихкод на упаковке, поперёк рамки. Подходят EAN-13, EAN-8, UPC-A и UPC-E.",
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Карточка продукта: название, штрихкод, на 100 г — ккал и БЖУ, порция по умолчанию. Ошибки ввода — у полей,
 * «Сохранить» неактивна, пока они есть. [stored] — правка (null — новый продукт, [initialBarcode] — код со сканера).
 * Сохранение и удаление — здесь; [onSaved] — сохранённая (или прежняя, если не менялась) версия.
 */
@Composable
internal fun ProductEditorView(
    editKey: String,
    stored: ProductVersion?,
    initialBarcode: String?,
    live: List<ProductVersion>,
    onClose: () -> Unit,
    onSaved: (ProductVersion) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initial = remember(editKey) {
        ProductsMath.formFields(stored).toMutableList().also { if (stored == null && initialBarcode != null) it[1] = initialBarcode }
    }
    var name by rememberSaveable(editKey) { mutableStateOf(initial[0]) }
    var barcode by rememberSaveable(editKey) { mutableStateOf(initial[1]) }
    var kcal by rememberSaveable(editKey) { mutableStateOf(initial[2]) }
    var protein by rememberSaveable(editKey) { mutableStateOf(initial[3]) }
    var fat by rememberSaveable(editKey) { mutableStateOf(initial[4]) }
    var carbs by rememberSaveable(editKey) { mutableStateOf(initial[5]) }
    var portion by rememberSaveable(editKey) { mutableStateOf(initial[6]) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // uid новой версии — на весь сеанс правки и переживает поворот: запись, начатая до поворота (её корутина
    // отменилась, а транзакция могла дойти до конца), и повторное «Сохранить» дают одну версию, а не две.
    val saveUid by rememberSaveable(editKey) { mutableStateOf(UUID.randomUUID().toString()) }

    // Пересоздание экрана посреди записи: версия уже записана — туда же, куда после успешного сохранения.
    LaunchedEffect(editKey, saveUid) {
        val done = withContext(Dispatchers.IO) { runCatching { Products.version(saveUid) }.getOrNull() }
        if (done != null) onSaved(done)
    }

    val fields = listOf(name, barcode, kcal, protein, fat, carbs, portion)
    val form = ProductsMath.parseForm(name, barcode, kcal, protein, fat, carbs, portion) { code ->
        ProductsMath.barcodeOwner(live, code, stored?.productUid)
    }
    val dirty = fields != initial
    // Пустое название у новой карточки — не ошибка, пока его не начали вводить: «Сохранить» и так неактивна.
    fun shown(f: ProductField): String? = form.errors[f]?.takeUnless { f == ProductField.NAME && name.isEmpty() }

    fun close() {
        if (dirty && !busy) confirmExit = true else if (!busy) onClose()
    }

    fun save() {
        if (!form.ok || busy) return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { Products.save(stored?.productUid, form, versionUid = saveUid) }
                    .onFailure { Log.e(TAG, "Не удалось сохранить продукт", it) }
            }
            busy = false
            saved.fold(
                onSuccess = { v -> if (v != null) onSaved(v) else error = "Продукт удалён на другом телефоне" },
                onFailure = { error = "Не удалось сохранить продукт" },
            )
        }
    }

    BackHandler { close() }
    LaunchedEffect(name, barcode, kcal, protein, fat, carbs, portion) { error = null }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (stored == null) "Новый продукт" else "Продукт", style = MaterialTheme.typography.headlineMedium, color = Accent)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(ProductsMath.NAME_MAX) },
                label = { Text("Название") },
                placeholder = { Text("Например, Гречка ядрица") },
                singleLine = true,
                isError = shown(ProductField.NAME) != null,
                supportingText = { Text(shown(ProductField.NAME) ?: "${name.trim().length} из ${ProductsMath.NAME_MAX}") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = barcode,
                onValueChange = { barcode = it.filter { c -> c.isDigit() || c == ' ' || c == '-' }.take(20) },
                label = { Text("Штрихкод (необязательно)") },
                singleLine = true,
                isError = shown(ProductField.BARCODE) != null,
                supportingText = { Text(shown(ProductField.BARCODE) ?: "8, 12, 13 или 14 цифр под штрихами") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("На 100${NBSP}г (необязательно)", style = MaterialTheme.typography.titleSmall)
            NumberField(
                kcal, { kcal = it.take(4) }, "Ккал", shown(ProductField.KCAL) ?: "От 0 до ${ProductsMath.KCAL_MAX}",
                KeyboardType.Number, Modifier.fillMaxWidth(), shown(ProductField.KCAL) != null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(protein, { protein = it.take(5) }, "Белки, г", null, KeyboardType.Decimal, Modifier.weight(1f), shown(ProductField.PROTEIN) != null)
                NumberField(fat, { fat = it.take(5) }, "Жиры, г", null, KeyboardType.Decimal, Modifier.weight(1f), shown(ProductField.FAT) != null)
                NumberField(carbs, { carbs = it.take(5) }, "Углев., г", null, KeyboardType.Decimal, Modifier.weight(1f), shown(ProductField.CARBS) != null)
            }
            // Ошибки трёх узких полей — одной строкой под ними: в поле шириной в треть экрана текст не помещается.
            val macroErrors = listOfNotNull(
                shown(ProductField.PROTEIN)?.let { "Белки: $it" },
                shown(ProductField.FAT)?.let { "Жиры: $it" },
                shown(ProductField.CARBS)?.let { "Углеводы: $it" },
                shown(ProductField.MACROS),
            )
            Text(
                if (macroErrors.isEmpty()) "С упаковки, например 12,5. Можно оставить пустым." else macroErrors.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = if (macroErrors.isEmpty()) muted else MaterialTheme.colorScheme.error,
            )
            NumberField(
                portion, { portion = it.take(5) }, "Порция по умолчанию, г", shown(ProductField.PORTION) ?: "Подставится при добавлении в приём",
                KeyboardType.Number, Modifier.fillMaxWidth(), shown(ProductField.PORTION) != null,
            )
            if (stored != null) {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Удалить продукт", color = Bad)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error != null) Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = ::close,
                    shape = ButtonShape,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("Назад", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = ::save,
                    enabled = form.ok && !busy,
                    shape = ButtonShape,
                    modifier = Modifier.weight(2f).heightIn(min = 56.dp),
                ) { Text("Сохранить", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Выйти", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } },
            title = { Text("Выйти без сохранения?") },
            text = { Text("Изменения в карточке не сохранятся.") },
        )
    }
    if (confirmDelete && stored != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { Products.delete(stored.productUid) }
                                .onFailure { Log.e(TAG, "Не удалось удалить продукт", it) }.isSuccess
                        }
                        busy = false
                        if (ok) onDeleted() else error = "Не удалось удалить продукт"
                    }
                }) { Text("Удалить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            title = { Text("Удалить продукт «${stored.name}»?") },
            text = { Text("Он пропадёт из списка на обоих телефонах. В прошлых приёмах останется подписанным.") },
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    supporting: String?,
    type: KeyboardType,
    modifier: Modifier,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter { c -> c.isDigit() || c == ',' || c == '.' }) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        textStyle = MaterialTheme.typography.bodyLarge.merge(Tabular),
        modifier = modifier,
    )
}

/**
 * Порция выбранного продукта: вес подставлен из порции по умолчанию, можно поменять или стереть (пусто — порция
 * по умолчанию, а без неё — «без веса»). [version] null — ещё загружается.
 * [onAdd] — вес и uid позиции: uid задан на весь сеанс [key] и переживает поворот, запись по нему идемпотентна.
 * [onAlreadyAdded] — экран пересоздан, а позиция с этим uid уже записана (запись дошла до конца до поворота).
 */
@Composable
internal fun PortionView(
    key: String,
    version: ProductVersion?,
    onCancel: () -> Unit,
    onAdd: (grams: Int?, entryUid: String) -> Unit,
    onAlreadyAdded: () -> Unit,
) {
    val entryUid by rememberSaveable(key) { mutableStateOf(UUID.randomUUID().toString()) }
    LaunchedEffect(key, entryUid) {
        val done = withContext(Dispatchers.IO) { runCatching { Products.entry(entryUid) }.getOrNull() }
        if (done != null) onAlreadyAdded()
    }
    if (version == null) {
        ProductHeader("Порция", "Отмена", onCancel)
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    var grams by rememberSaveable(key) { mutableStateOf(version.portionG?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val (value, error) = ProductsMath.parseGrams(grams)
    val effective = value ?: version.portionG
    val preview = ProductsMath.entryText(
        MealEntryLine(com.nextlevellife.app.data.MealEntry("", "", "", version.versionUid, effective, 0L), version),
    )
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductHeader(version.name, "Отмена", onCancel)
        Text(ProductsMath.per100Text(version), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberField(
            grams, { grams = it.filter(Char::isDigit).take(5) }, "Вес порции, г",
            error ?: if (version.portionG != null) "Пусто — порция по умолчанию, ${version.portionG}${NBSP}г" else "Пусто — без веса",
            KeyboardType.Number, Modifier.fillMaxWidth(), error != null,
        )
        Text(preview, style = MaterialTheme.typography.titleLarge.merge(Tabular), color = Accent)
        Button(
            onClick = { busy = true; onAdd(value, entryUid) },
            enabled = error == null && !busy,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Добавить в приём", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Позиции приёма: сумма, список с «Убрать» (с подтверждением), «Добавить продукт». */
@Composable
internal fun MealEntriesSection(entries: List<MealEntryLine>, onAdd: () -> Unit, onRemove: (MealEntryLine) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Продукты", style = MaterialTheme.typography.labelLarge, color = muted)
            if (entries.isEmpty()) {
                Text("Пока не добавлены. Ккал и БЖУ считаются по продуктам с весом.", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                val t = ProductsMath.totals(entries)
                ProductsMath.kcalText(t)?.let { Text(it, style = MaterialTheme.typography.titleLarge.merge(Tabular), color = Accent) }
                ProductsMath.macrosText(t)?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tabular)) }
                entries.forEach { line ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                ProductsMath.entryName(line),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (line.product == null) muted else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(ProductsMath.entryText(line), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
                        }
                        TextButton(onClick = { confirm = line.entry.uid }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Убрать", color = Bad)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onAdd,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Добавить продукт") }
        }
    }
    val target = entries.firstOrNull { it.entry.uid == confirm }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Убрать «${ProductsMath.entryName(target)}»?") },
            text = { Text("Продукт исчезнет из этого приёма на обоих телефонах.") },
            confirmButton = {
                TextButton(onClick = { confirm = null; onRemove(target) }) { Text("Убрать", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

/** Сумма за день по всем позициям приёмов; позиций нет — ничего. */
@Composable
internal fun DayTotalsCard(entries: List<MealEntryLine>) {
    if (entries.isEmpty()) return
    val t = ProductsMath.totals(entries)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("За день по продуктам", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProductsMath.kcalText(t)?.let { Text(it, style = MaterialTheme.typography.titleLarge.merge(Tabular), color = Accent) }
            ProductsMath.macrosText(t)?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tabular)) }
        }
    }
}
