package com.nextlevellife.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nextlevellife.app.update.UpdateManager
import com.nextlevellife.app.update.UpdateMath

/**
 * Плашка «Доступна версия X.Y.Z» вверху главной: появляется, когда проверка при запуске (или раньше,
 * из кеша) нашла версию новее. «Обновить» — тот же путь, что в «Настройках» → «Обновление» (скачать,
 * проверить, установщик); «Позже» — скрыть до следующей новой версии. Окон поверх экрана не открывает.
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val ui by UpdateManager.state.collectAsState()
    val available = ui.available
    if (available == null || !ui.showBanner) return
    var canInstall by remember { mutableStateOf(UpdateManager.canInstall(context)) }
    // Вернулись из системных настроек «Установка неизвестных приложений» — перечитать разрешение.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = UpdateManager.canInstall(context) }

    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Доступна версия ${available.version}", style = MaterialTheme.typography.titleSmall, color = Accent)
            when (ui.phase) {
                UpdateManager.Phase.DOWNLOADING -> {
                    val total = ui.totalBytes.coerceAtLeast(1L)
                    Text(
                        "Скачиваю: ${UpdateMath.sizeText(ui.downloadedBytes)} из ${UpdateMath.sizeText(ui.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    )
                    LinearProgressIndicator(
                        progress = { (ui.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BannerButtons {
                        OutlinedButton(onClick = { UpdateManager.cancel() }, shape = ButtonShape) { Text("Отменить") }
                    }
                }
                UpdateManager.Phase.VERIFYING, UpdateManager.Phase.INSTALLING, UpdateManager.Phase.CHECKING -> {
                    Text(
                        if (ui.phase == UpdateManager.Phase.INSTALLING) "Передаю файл установщику…" else "Проверяю…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                UpdateManager.Phase.READY -> when {
                    !canInstall -> {
                        Text(
                            "Чтобы поставить обновление, разреши этому приложению установку приложений.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        BannerButtons {
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                    runCatching { context.startActivity(intent) }
                                },
                                shape = ButtonShape,
                            ) { Text("Разрешить установку") }
                        }
                    }
                    ui.confirmIntent != null -> BannerButtons {
                        Button(onClick = { UpdateManager.confirmFrom(context) }, shape = ButtonShape) { Text("Подтвердить установку") }
                    }
                    else -> BannerButtons {
                        Button(onClick = { UpdateManager.install(context) }, shape = ButtonShape) { Text("Установить") }
                    }
                }
                UpdateManager.Phase.IDLE -> {
                    Text(
                        "${UpdateMath.sizeText(available.sizeBytes)} · подробности — в «Настройках» → «Обновление»",
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BannerButtons {
                        TextButton(onClick = { UpdateManager.hideBanner(context) }) { Text("Позже") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { UpdateManager.downloadAndInstall(context) }, shape = ButtonShape) { Text("Обновить") }
                    }
                }
            }
            ui.warning?.takeIf { ui.phase == UpdateManager.Phase.READY }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Warn)
            }
            ui.message?.takeIf { ui.isError }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Bad)
            }
        }
    }
}

/** Кнопки плашки — справа. */
@Composable
private fun BannerButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        content()
    }
}
