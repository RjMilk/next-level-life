package com.nextlevellife.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.SharedSettings
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.health.ActivityMath
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthConnect
import com.nextlevellife.app.health.HealthDiagnostics
import com.nextlevellife.app.health.HealthDiagnosticsReport
import com.nextlevellife.app.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Локальный отчёт читается и копируется по явному действию пользователя. */
@Composable
internal fun HealthDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<HealthDiagnosticsReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(HealthConnect.permissionContract()) {
        ActivitySync.request(appContext, force = true)
        refresh++
    }

    LaunchedEffect(refresh) {
        loading = true
        report = null
        error = null
        copied = false
        try {
            report = HealthDiagnostics.read(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Не удалось проверить Health Connect — ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = ContentMaxWidth).fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Данные Health Connect", style = MaterialTheme.typography.titleLarge)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium) }
                    report?.let { current ->
                        SelectionContainer {
                            Text(current.reportText, style = MaterialTheme.typography.bodySmall.merge(Tabular))
                        }
                        if (current.canRequestHistory) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val optional = HealthConnect.optionalHistoryPermissions(appContext)
                                            if (optional.isEmpty()) {
                                                refresh++
                                            } else {
                                                permissions.launch(optional)
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = "Не удалось запросить историю — ${e.message ?: e.javaClass.simpleName}"
                                        }
                                    }
                                },
                                enabled = !loading,
                                shape = ButtonShape,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Разрешить чтение давней истории") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val current = report ?: return@OutlinedButton
                        try {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Диагностика Health Connect", current.reportText))
                            copied = true
                        } catch (e: Exception) {
                            error = "Не удалось скопировать отчёт — ${e.message ?: e.javaClass.simpleName}"
                        }
                    },
                    enabled = report != null && !loading,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (copied) "Отчёт скопирован" else "Скопировать отчёт") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Проверить снова") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

private data class ActivitySourceChoice(val source: String?, val pending: Boolean, val canChange: Boolean)

@Composable
internal fun ActivitySourceSelection(version: Int, me: String, peerId: String, peerName: String) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val health by ActivitySync.state.collectAsState()
    val today = TrustedClock.today()
    var sourceError by remember { mutableStateOf<String?>(null) }
    val choice by produceState<ActivitySourceChoice?>(null, version, peerId) {
        try {
            value = withContext(Dispatchers.IO) {
                ActivitySourceChoice(
                    SharedSettings.activitySource(),
                    SharedSettings.activitySourcePending(),
                    SharedSettings.canChangeActivitySource(),
                )
            }
            sourceError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sourceError = "Не удалось узнать источник активности — ${e.message ?: e.javaClass.simpleName}"
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshSteps by remember { mutableStateOf(0) }
    var stepsLoading by remember { mutableStateOf(true) }
    var steps by remember { mutableStateOf<Long?>(null) }
    var stepsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(health.connected, health.lastSyncMs, today, refreshSteps) {
        stepsLoading = true
        stepsError = null
        try {
            steps = HealthConnect.sevenDaySteps(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            steps = null
            stepsError = "Не удалось прочитать шаги этого телефона — ${e.message ?: e.javaClass.simpleName}"
        } finally {
            stepsLoading = false
        }
    }
    val choose: (String) -> Unit = { deviceId ->
        scope.launch {
            saving = true
            error = null
            try {
                withContext(Dispatchers.IO) { Settings.setActivitySource(deviceId) }
                SyncEngine.requestSync("смена источника активности", force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Не удалось выбрать источник — ${e.message ?: e.javaClass.simpleName}"
            } finally {
                saving = false
            }
        }
    }

    Text(
        "Шаги и бег читает",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    listOf(me to "Этот телефон", peerId to "Второй телефон ($peerName)").forEach { (deviceId, label) ->
        val enabled = !saving && choice?.canChange == true
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = choice?.source == deviceId, enabled = enabled, role = Role.RadioButton) { choose(deviceId) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = choice?.source == deviceId, onClick = null, enabled = enabled)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    SettingsText("Выбери телефон, на котором стоит приложение часов (Garmin Connect, Mi Fitness, Zepp, CMF Watch и т.п.)")
    if (choice?.canChange == false) {
        SettingsText("Для смены источника обнови приложение на обоих телефонах и выполни обмен")
    }
    SettingsRow(
        "Health Connect этого телефона за 7 дней",
        if (stepsLoading) "…" else steps?.let(ActivityMath::stepsValue) ?: "нет данных",
        enabled = !stepsLoading,
        onClick = { refreshSteps++ },
    )
    stepsError?.let { SettingsText(it, color = Bad) }
    if (choice?.pending == true) {
        SettingsText("Смена ожидает подтверждения обменом. Новый источник начнёт читать данные после подтверждения", color = Warn)
    } else if (choice?.source == null) {
        SettingsText("Источник выберется при первом обмене")
    }
    error?.let { SettingsText(it, color = Bad) }
    sourceError?.let { SettingsText(it, color = Bad) }
}
