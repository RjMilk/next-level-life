package com.nextlevellife.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nextlevellife.app.block.AppEntry
import com.nextlevellife.app.block.InstalledApps
import com.nextlevellife.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val blocked = remember(version) { Store.blockedPackages() }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.Default) { InstalledApps.launchable(context) }
    }

    val widthClass = currentWidthClass()
    // Список не растягивается на весь развёрнутый Fold: колонка до 600 dp по центру.
    Column(
        Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(widthClass.outerPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Блокируемые приложения",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Готово") }
        }
        Text(
            "Включённые открываются только за заработанные минуты. По умолчанию закрыты соцсети, видео и браузеры, всё остальное работает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val filtered = list
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                // Заблокированные сверху, чтобы сразу видеть, что закрыто.
                .sortedByDescending { it.packageName in blocked }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app, app.packageName in blocked) { Store.setBlocked(app.packageName, it) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, blocked: Boolean, onToggle: (Boolean) -> Unit) {
    val icon = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (blocked) "Заблокировано" else "Доступно всегда",
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = blocked, onCheckedChange = onToggle)
    }
}
