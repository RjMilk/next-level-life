package com.nextlevellife.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextlevellife.app.data.Backup
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.RestoreResult
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/**
 * Вкладка «Настройки»: прокручиваемая колонка не шире [ContentMaxWidth] по центру.
 * Каждый раздел — отдельная функция; новый раздел вставляется строкой в список ниже.
 */
@Composable
fun SettingsScreen(onApps: () -> Unit, modifier: Modifier = Modifier) {
    val widthClass = currentWidthClass()
    val version by Store.version.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(widthClass.outerPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            ScreenTimeSection(version)
            NightModeSection(version)
            // Здесь — раздел «Шаги» (Health Connect): StepsSection(version), между ночным режимом и блокировкой.
            BlockingSection(version, onApps)
            DataSection(version)
            AboutSection()
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------- Общие строительные блоки разделов ----------

/** Раздел: заголовок titleMedium и карточка. Содержимое само задаёт отступы — см. [SettingsRow], [SettingsText]. */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(), shape = CardShape) {
            Column(
                Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

/** Строка настройки во всю ширину карточки: подпись слева, значение справа, «›», если нажимается. */
@Composable
internal fun SettingsRow(
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.merge(Tabular),
                color = (if (onClick != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
            )
        }
        if (onClick != null) {
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** Пояснение или вторичные данные внутри карточки раздела: bodySmall с отступом 16 dp. */
@Composable
internal fun SettingsText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall.merge(Tabular),
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Подпись с переключателем во всю ширину; нажатие на строку тоже переключает. */
@Composable
internal fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- 1. Экранное время ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTimeSection(version: Int) {
    val limit = remember(version) { Settings.dailyLimitMin }
    val today = LocalDate.now().toEpochDay()
    val spent by produceState<Long?>(null, version, today) {
        value = withContext(Dispatchers.IO) { runCatching { Ledger.spentMs(today) }.getOrNull() }
    }
    SettingsSection("Экранное время") {
        Text(
            "Дневной лимит расхода",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        val choices = SettingsMath.LIMIT_CHOICES
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            choices.forEachIndexed { i, minutes ->
                SegmentedButton(
                    selected = limit == minutes,
                    onClick = { runCatching { Settings.setDailyLimitMin(minutes) } },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    icon = {},
                ) { Text("$minutes мин", maxLines = 1) }
            }
        }
        if (limit !in choices) {
            SettingsText(if (limit <= 0) "Сейчас: без лимита" else "Сейчас: $limit мин")
        }
        SettingsText("Ограничивает расход. Заработанный баланс сохраняется")
        SettingsText(
            spent?.let { SettingsMath.usedTodayLine(it, limit) } ?: "Сегодня использовано: …",
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------- 2. Ночной режим ----------

private const val EDIT_START = "start"
private const val EDIT_END = "end"

@Composable
private fun NightModeSection(version: Int) {
    val enabled = remember(version) { Settings.nightEnabled }
    val start = remember(version) { Settings.nightStartMin }
    val end = remember(version) { Settings.nightEndMin }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsSection("Ночной режим") {
        SettingsSwitchRow("Ночной режим", enabled) { Settings.setNightEnabled(it) }
        SettingsText("Блокируемые приложения недоступны при любом балансе")
        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow("Начало", SettingsMath.clock(start), enabled = enabled) { editing = EDIT_START }
        SettingsRow("Окончание", SettingsMath.clock(end), enabled = enabled) { editing = EDIT_END }
    }

    when (editing) {
        EDIT_START -> NightTimeDialog(
            title = "Начало ночного режима",
            initialMin = start,
            check = { SettingsMath.nightProblem(it, end) },
            onDismiss = { editing = null },
            onSave = {
                runCatching { Settings.setNightStartMin(it) }
                editing = null
            },
        )
        EDIT_END -> NightTimeDialog(
            title = "Окончание ночного режима",
            initialMin = end,
            check = { SettingsMath.nightProblem(start, it) },
            onDismiss = { editing = null },
            onSave = {
                runCatching { Settings.setNightEndMin(it) }
                editing = null
            },
        )
    }
}

/** Выбор времени Material 3 в 24-часовом формате; [check] возвращает пояснение, если сохранить нельзя. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightTimeDialog(
    title: String,
    initialMin: Int,
    check: (Int) -> String?,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMin / 60,
        initialMinute = initialMin % 60,
        is24Hour = true,
    )
    val picked = SettingsMath.minuteOfDay(state.hour, state.minute)
    val problem = if (picked == null) "Неверное время" else check(picked)
    // Свой диалог на Surface: AlertDialog меряет содержимое по внутренним размерам, а TimePicker это не любит.
    // Ширину задаём сами: циферблат 256 dp + поля 2 × 20 dp должны влезть и на узком S25.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                TimePicker(state = state)
                Text(
                    problem ?: " ",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Bad,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    TextButton(onClick = { picked?.let(onSave) }, enabled = problem == null) { Text("Сохранить") }
                }
            }
        }
    }
}

// ---------- 3. Блокировка ----------

@Composable
private fun BlockingSection(version: Int, onApps: () -> Unit) {
    val count = remember(version) { Store.blockedPackages().size }
    SettingsSection("Блокировка") {
        SettingsRow("Блокируемые приложения", "$count", onClick = onApps)
        SettingsText(
            "Открываются только за заработанные минуты",
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

// ---------- 4. Данные ----------

/** Итог последней операции с копией: текст и ошибка ли это. */
private data class BackupStatus(val text: String, val isError: Boolean)

/**
 * Копирование и восстановление идут в собственной области корутин, а не в области экрана:
 * переключение вкладки или поворот (раскрытие Fold) не обрывают их на середине.
 */
private object BackupRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow<BackupStatus?>(null)

    fun export(context: Context, uri: Uri) = launchJob("Не удалось создать копию") {
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("файл недоступен для записи")
            out.use { Backup.export(context, it) }
        }
        val now = System.currentTimeMillis()
        Settings.setLastBackupMs(now)
        BackupStatus("Копия сохранена: ${SettingsMath.dateTime(now, now)}", isError = false)
    }

    fun restore(context: Context, uri: Uri) = launchJob("Не удалось восстановить") {
        val result = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("файл недоступен для чтения")
            input.use { Backup.restore(context, it) }
        }
        when (result) {
            is RestoreResult.Ok -> {
                val now = System.currentTimeMillis()
                val from = result.createdAtMs.takeIf { it > 0 }?.let { " от ${SettingsMath.dateTime(it, now)}" } ?: ""
                BackupStatus("Данные восстановлены из копии$from", isError = false)
            }
            is RestoreResult.Error -> BackupStatus("Не удалось восстановить: ${result.reason}", isError = true)
        }
    }

    private fun launchJob(failPrefix: String, block: suspend () -> BackupStatus) {
        if (busy.value) return
        busy.value = true
        status.value = null
        scope.launch {
            status.value = try {
                block()
            } catch (e: Exception) {
                BackupStatus("$failPrefix: ${e.message ?: e.javaClass.simpleName}", isError = true)
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
private fun DataSection(version: Int) {
    val appContext = LocalContext.current.applicationContext
    val busy by BackupRunner.busy.collectAsState()
    val status by BackupRunner.status.collectAsState()
    val last = remember(version) { Settings.lastBackupMs }
    var pendingRestore by rememberSaveable { mutableStateOf<Uri?>(null) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) BackupRunner.export(appContext, uri)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }

    SettingsSection("Данные") {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { createLauncher.launch(SettingsMath.backupFileName(LocalDate.now())) },
                enabled = !busy,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("Создать резервную копию") }
            OutlinedButton(
                onClick = { openLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !busy,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("Восстановить из файла") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        status?.let { s ->
            SettingsText(s.text, color = if (s.isError) Bad else Good)
        }
        SettingsText(
            SettingsMath.lastBackupLine(last, System.currentTimeMillis()),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Восстановить из копии?") },
            text = {
                Text(
                    "Текущие данные будут заменены: баланс, дневник, рекорды и настройки. Отменить это нельзя.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    BackupRunner.restore(appContext, uri)
                }) { Text("Заменить", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Отмена") }
            },
        )
    }
}

// ---------- 5. О приложении ----------

private val LICENSE_FILES = listOf(
    "Inter" to "licenses/OFL-Inter.txt",
    "JetBrains Mono" to "licenses/OFL-JetBrainsMono.txt",
)

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    SettingsSection("О приложении") {
        SettingsRow("Версия", version)
        SettingsRow("Шрифты: Inter и JetBrains Mono (лицензия OFL)", onClick = { showLicenses = true })
    }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            LICENSE_FILES.joinToString("\n\n") { (name, path) ->
                val body = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                    .getOrDefault("Текст лицензии не найден")
                "$name\n\n${body.trim()}"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лицензии шрифтов") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text ?: "…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Версия из PackageManager: BuildConfig в проекте не подключён. */
private fun appVersion(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: "—"
