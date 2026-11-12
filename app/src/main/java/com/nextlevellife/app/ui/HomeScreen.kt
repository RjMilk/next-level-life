package com.nextlevellife.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.block.BlockerService
import com.nextlevellife.app.block.InstalledApps
import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Итог дня с прошлого показа: при возврате на главную карточка «Сегодня» не мигает. */
private var cachedToday: Pair<Long, ProgressMath.DayDigest>? = null

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    resumeCount: Int,
    onExercise: (String) -> Unit,
    onComplex: (String) -> Unit,
    onApps: () -> Unit,
    onProgress: () -> Unit,
) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()

    // Первый запуск: сразу заполняем список блокировки набором по умолчанию.
    LaunchedEffect(Unit) {
        if (!Store.isConfigured()) {
            val apps = withContext(Dispatchers.Default) { InstalledApps.launchable(context) }
            Store.setBlockedPackages(InstalledApps.defaultBlocked(apps))
        }
    }

    val serviceOn = remember(resumeCount) { BlockerService.isEnabled(context) }
    val batteryOk = remember(resumeCount) {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    // version участвует в чтении, чтобы экран обновлялся при изменении данных.
    val balance = remember(version) { Store.balanceMs() }
    val xp = remember(version) { Store.xp() }
    val streak = remember(version) { Store.streak() }
    val blockedCount = remember(version) { Store.blockedPackages().size }
    // Итог дня из дневника: читается в фоне, обновляется после каждого записанного подхода.
    val today = LocalDate.now().toEpochDay()
    val todayDigest by produceState(cachedToday?.takeIf { it.first == today }?.second, version, today) {
        value = withContext(Dispatchers.IO) {
            runCatching { ProgressMath.digestDay(Diary.setsForDay(today), Diary.runsForDay(today)) }.getOrNull()
        }?.also { cachedToday = today to it }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Next Level Life", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (!serviceOn) {
            WarningCard(
                title = "Блокировка выключена",
                text = "Включи службу «Next Level Life: блокировка» в специальных возможностях " +
                    "(Установленные приложения). Без неё приложения не блокируются.",
                button = "Открыть настройки",
            ) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (!batteryOk) {
            WarningCard(
                title = "Samsung может усыпить блокировку",
                text = "Разреши работу без ограничений батареи, иначе служба будет отключаться в фоне.",
                button = "Разрешить",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            }
        }

        // Баланс
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Баланс экранного времени", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    BlockerService.formatMinutes(balance),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = if (balance > 0) Accent else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Тратится, только пока открыто заблокированное приложение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TodayCard(todayDigest, onProgress)

        // Уровень и серия
        val level = Store.levelFor(xp)
        val from = Store.xpForLevel(level)
        val to = Store.xpForLevel(level + 1)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Уровень $level · ${Store.titleFor(level)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("🔥 $streak ${daysWord(streak)}", style = MaterialTheme.typography.titleMedium)
                }
                LinearProgressIndicator(
                    progress = { ((xp - from).toFloat() / (to - from)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "$xp / $to опыта",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Заработать минуты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Минотавр
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { onComplex(Minotaur.ID) },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F0A)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Минотавр", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Accent)
                Text("Лесенка 10 → 1, пять упражнений с гирей, ${Minotaur.totalReps} повторений")
                Spacer(Modifier.height(6.dp))
                Weights.all.forEach { w ->
                    val best = Store.minotaurBestMs(w)
                    Text(
                        "$w кг: +${formatShort(Minotaur.rewardMs(w))}" +
                            (best?.let { " · рекорд ${formatDuration(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Синди
        val cindyBest = remember(version) { cindyBestRounds() }
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { onComplex(Cindy.ID) },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F0A)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Синди", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Accent)
                Text("20 минут: 5 подтягиваний, 10 отжиманий, 15 приседаний")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Круг = 20 минут экранного времени",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cindyBest?.let { (r, e) ->
                    Text(
                        "Рекорд: $r ${complexRoundsWord(r)} + $e",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Без веса", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Exercises.bodyweight.forEach { ex -> ExerciseCard(ex, onExercise) }
        Text("С гирей (цена за 16 кг; 24 кг ×1,2, 32 кг ×1,6)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Exercises.kettlebell.forEach { ex -> ExerciseCard(ex, onExercise) }

        Card(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onApps),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Блокируемые приложения", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Заблокировано: $blockedCount",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** «Сегодня»: итог дня и строки по упражнениям; нажатие открывает вкладку «Прогресс». */
@Composable
private fun TodayCard(digest: ProgressMath.DayDigest?, onProgress: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть прогресс", onClick = onProgress),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сегодня", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (digest == null || digest.isEmpty) {
                    Text("Сегодня тренировок ещё не было", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        ProgressMath.summaryLine(digest.sets, digest.reps, digest.earnedMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Accent,
                    )
                    digest.totals.forEach { t ->
                        Text(ProgressMath.totalRepsLine(t), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("›", fontSize = 28.sp)
        }
    }
}

@Composable
private fun WarningCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1717)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Bad)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row {
                Spacer(Modifier.width(0.dp).weight(1f))
                Button(onClick = onClick) { Text(button) }
            }
        }
    }
}

@Composable
private fun ExerciseCard(ex: Exercise, onExercise: (String) -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onExercise(ex.id) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ex.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, Weights.DEFAULT))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «2 ч 22 мин», «1 мин 30 сек», «45 сек». */
fun formatShort(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> if (m > 0) "$h ч $m мин" else "$h ч"
        m > 0 -> if (s > 0) "$m мин $s сек" else "$m мин"
        else -> "$s сек"
    }
}

private fun daysWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
