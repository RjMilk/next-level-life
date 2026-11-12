package com.nextlevellife.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.block.BlockerService
import com.nextlevellife.app.block.InstalledApps
import com.nextlevellife.app.data.Cindy
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Minotaur
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Итог дня с прошлого показа: при возврате на главную карточка «Сегодня» не мигает. */
private var cachedToday: Pair<Long, ProgressMath.DayDigest>? = null

/** Расход за день с прошлого показа — по той же причине. */
private var cachedSpent: Pair<Long, Long>? = null

/**
 * Главная («Тренировка»). COMPACT — одна колонка; MEDIUM — одна колонка не шире 600 dp, упражнения
 * в два столбца; EXPANDED — две колонки 40/60: слева баланс, «Сегодня», уровень; справа комплексы
 * и упражнения. Прокрутка хранится снаружи веток, поэтому смена ширины её не сбрасывает.
 */
@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    resumeCount: Int,
    onExercise: (String) -> Unit,
    onComplex: (String) -> Unit,
    onProgress: () -> Unit,
) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val widthClass = currentWidthClass()

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
    val limitMin = remember(version) { Settings.dailyLimitMin }
    val nightOn = remember(version) { Settings.nightEnabled }
    val nightStart = remember(version) { Settings.nightStartMin }
    val nightEnd = remember(version) { Settings.nightEndMin }
    // Итог дня из дневника и расход за сегодня: читаются в фоне.
    val today = LocalDate.now().toEpochDay()
    val todayDigest by produceState(cachedToday?.takeIf { it.first == today }?.second, version, today) {
        value = withContext(Dispatchers.IO) {
            runCatching { ProgressMath.digestDay(Diary.setsForDay(today), Diary.runsForDay(today)) }.getOrNull()
        }?.also { cachedToday = today to it }
    }
    val spentToday by produceState(cachedSpent?.takeIf { it.first == today }?.second, version, today, resumeCount) {
        value = withContext(Dispatchers.IO) { runCatching { Ledger.spentMs(today) }.getOrNull() }
            ?.also { cachedSpent = today to it }
    }

    val mainScroll = rememberScrollState()
    val sideScroll = rememberScrollState()
    val pad = widthClass.outerPadding

    val header: @Composable () -> Unit = {
        Text("Next Level Life", style = MaterialTheme.typography.headlineMedium)
    }
    val warnings: @Composable () -> Unit = {
        if (!serviceOn) {
            WarningCard(
                title = "Блокировка выключена",
                text = "Включи службу «Next Level Life: блокировка» в специальных возможностях " +
                    "(Установленные приложения). Без неё приложения не блокируются.",
                button = "Открыть настройки",
            ) {
                context.startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (!batteryOk) {
            WarningCard(
                title = "Samsung может усыпить блокировку",
                text = "Разреши работу без ограничений батареи, иначе служба будет отключаться в фоне.",
                button = "Разрешить",
            ) {
                context.startActivity(
                    Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            }
        }
    }
    val status: @Composable () -> Unit = {
        BalanceCard(
            balanceMs = balance,
            spentMs = spentToday,
            limitMin = limitMin,
            night = if (nightOn) nightStart to nightEnd else null,
            large = widthClass == WidthClass.EXPANDED,
        )
        TodayCard(todayDigest, onProgress)
        LevelCard(xp, streak)
    }
    val earn: @Composable () -> Unit = {
        EarnSection(version, widthClass.gridColumns, onExercise, onComplex)
    }

    if (widthClass == WidthClass.EXPANDED) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(widthClass.gutter),
        ) {
            HomeColumn(Modifier.weight(0.4f), mainScroll, pad) {
                header()
                warnings()
                status()
            }
            HomeColumn(Modifier.weight(0.6f), sideScroll, pad) {
                earn()
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(mainScroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = if (widthClass == WidthClass.MEDIUM) ContentMaxWidth else Dp.Unspecified)
                    .fillMaxWidth()
                    .padding(pad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                warnings()
                status()
                earn()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Колонка широкой раскладки: своя прокрутка, внешние отступы сверху и снизу. */
@Composable
private fun HomeColumn(modifier: Modifier, scroll: ScrollState, pad: Dp, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(scroll)
            .padding(vertical = pad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

// ---------- Баланс ----------

@Composable
private fun BalanceCard(balanceMs: Long, spentMs: Long?, limitMin: Int, night: Pair<Int, Int>?, large: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Баланс экранного времени",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BalanceScore(balanceMs, large)
            Text(
                "Тратится, только пока открыто заблокированное приложение",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (spentMs != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    SettingsMath.spentTodayLine(spentMs, limitMin),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                )
                if (limitMin > 0) {
                    val fraction = SettingsMath.usageFraction(spentMs, limitMin)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (fraction >= 1f) Bad else Accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            if (night != null) {
                Text(
                    SettingsMath.nightLine(night.first, night.second),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Баланс «табло»: цифры JetBrains Mono, единицы («ч», «мин») — Inter 16/24 на той же базовой линии. */
@Composable
private fun BalanceScore(balanceMs: Long, large: Boolean) {
    val parts = ProgressMath.balanceParts(balanceMs)
    val unitSpan = ScoreUnitStyle.toSpanStyle()
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, (number, unit) ->
                append(number)
                withStyle(unitSpan) {
                    // Неразрывный пробел перед единицей, полукруглая шпация между группами.
                    append(" $unit")
                    if (i < parts.lastIndex) append(" ")
                }
            }
        },
        style = if (large) scoreStyle(64, 72) else scoreStyle(56, 64),
        color = if (balanceMs > 0) Accent else MaterialTheme.colorScheme.onSurface,
    )
}

// ---------- Сегодня, уровень ----------

/** «Сегодня»: итог дня и не больше двух строк активности; нажатие открывает вкладку «Прогресс». */
@Composable
private fun TodayCard(digest: ProgressMath.DayDigest?, onProgress: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть прогресс", onClick = onProgress),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сегодня", style = MaterialTheme.typography.titleMedium)
                if (digest == null || digest.isEmpty) {
                    Text(
                        "Тренировок ещё не было",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        ProgressMath.summaryLine(digest.sets, digest.reps, digest.earnedMs),
                        style = MaterialTheme.typography.titleSmall.merge(Tabular),
                        color = Accent,
                    )
                    val (shown, rest) = ProgressMath.truncate(ProgressMath.todayActivities(digest))
                    shown.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ProgressMath.moreLine(rest)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Chevron()
        }
    }
}

@Composable
private fun LevelCard(xp: Long, streak: Int) {
    val level = Store.levelFor(xp)
    val from = Store.xpForLevel(level)
    val to = Store.xpForLevel(level + 1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Уровень $level · ${Store.titleFor(level)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "🔥 $streak ${ProgressMath.daysWord(streak)}",
                    style = MaterialTheme.typography.titleSmall.merge(Tabular),
                )
            }
            LinearProgressIndicator(
                progress = { ((xp - from).toFloat() / (to - from)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "$xp / $to опыта",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Заработать минуты ----------

private val ComplexCardColor = Color(0xFF2A1F0A)

@Composable
private fun EarnSection(version: Int, columns: Int, onExercise: (String) -> Unit, onComplex: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Заработать минуты", style = MaterialTheme.typography.titleMedium)
        MinotaurCard(version) { onComplex(Minotaur.ID) }
        CindyCard(version) { onComplex(Cindy.ID) }

        GroupLabel("Без веса")
        CardGrid(Exercises.bodyweight, columns) { ex, m -> ExerciseCard(ex, m, onExercise) }
        GroupLabel("С гирей", "Цена за 16 кг; 24 кг ×1,2, 32 кг ×1,6")
        CardGrid(Exercises.kettlebell, columns) { ex, m -> ExerciseCard(ex, m, onExercise) }
    }
}

@Composable
private fun GroupLabel(title: String, note: String? = null) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Карточки по [columns] в ряд; в ряду — одной высоты, неполный ряд добивается пустым местом. */
@Composable
private fun <T> CardGrid(items: List<T>, columns: Int, card: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item -> card(item, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MinotaurCard(version: Int, onClick: () -> Unit) {
    val bests = remember(version) { Weights.all.map { it to Store.minotaurBestMs(it) } }
    ComplexCard(
        title = "Минотавр",
        description = "Лесенка 10 → 1, пять упражнений с гирей, ${Minotaur.totalReps} повторений",
        notes = bests.map { (w, best) ->
            "$w кг: +${formatShort(Minotaur.rewardMs(w))}" + (best?.let { " · рекорд ${formatDuration(it)}" } ?: "")
        },
        onClick = onClick,
    )
}

@Composable
private fun CindyCard(version: Int, onClick: () -> Unit) {
    val best = remember(version) { cindyBestRounds() }
    ComplexCard(
        title = "Синди",
        description = "20 минут: 5 подтягиваний, 10 отжиманий, 15 приседаний",
        notes = listOfNotNull(
            "Круг = 20 минут экранного времени",
            best?.let { (r, e) -> "Рекорд: $r ${complexRoundsWord(r)} + $e" },
        ),
        onClick = onClick,
    )
}

@Composable
private fun ComplexCard(title: String, description: String, notes: List<String>, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ComplexCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Accent)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            notes.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            Text(title, style = MaterialTheme.typography.titleSmall, color = Bad)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = onClick, shape = ButtonShape) { Text(button) }
            }
        }
    }
}

/** Карточка упражнения: название, под ним цена — длинное название не зажимается. */
@Composable
private fun ExerciseCard(ex: Exercise, modifier: Modifier, onExercise: (String) -> Unit) {
    Card(modifier.clickable { onExercise(ex.id) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ex.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, Weights.DEFAULT))}",
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Chevron() {
    Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
