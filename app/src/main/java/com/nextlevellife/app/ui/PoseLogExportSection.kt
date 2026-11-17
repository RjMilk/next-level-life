package com.nextlevellife.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.pose.PoseLogExport
import com.nextlevellife.app.pose.PoseLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Раздел настроек «Для разработчика»: «Отправить записи тренировок» — записи «чёрного ящика»
 * за выбранный период одним zip в системное «Поделиться» ([PoseLogExport]).
 */
@Composable
internal fun PoseLogExportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(PoseLogExport.DEFAULT_DAYS) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // Сколько записей за период и их общий размер (до упаковки).
    val found by produceState<Pair<Int, Long>?>(null, days, busy) {
        value = withContext(Dispatchers.IO) {
            val files = PoseLogExport.select(PoseLogExport.dirs(context), System.currentTimeMillis(), days)
            files.size to files.sumOf { it.length() }
        }
    }

    SettingsSection("Для разработчика") {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Записи тренировок за последние")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = PoseLogExport.PERIOD_DAYS
                options.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = d == days,
                        onClick = { days = d; status = null },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(if (d == 1) "сутки" else "$d дн.") }
                }
            }
            Button(
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        status = try {
                            val packed = withContext(Dispatchers.IO) { PoseLogExport.build(context, days) }
                            if (packed == null) {
                                "За этот период записей нет" to true
                            } else {
                                PoseLogExport.share(context, packed)
                                "Архив: ${PoseLogExport.countText(packed.files)}, ${PoseLogExport.sizeText(packed.bytes)}" to false
                            }
                        } catch (e: Exception) {
                            "Не удалось собрать архив: ${e.message ?: e.javaClass.simpleName}" to true
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && (found?.first ?: 0) > 0,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("Отправить записи тренировок") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        found?.let { (n, bytes) ->
            SettingsText(if (n == 0) "Записей нет" else "${PoseLogExport.countText(n)}, ${PoseLogExport.sizeText(bytes)} до сжатия")
        }
        status?.let { (text, isError) -> SettingsText(text, color = if (isError) Bad else Good) }
        SettingsText(
            "Только точки суставов, без видео. На телефоне хранятся последние ${PoseLogWriter.KEEP} подходов",
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
