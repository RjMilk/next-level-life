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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.SpanStyle
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
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAvailability
import com.nextlevellife.app.sleep.SleepNight
import com.nextlevellife.app.sleep.SleepRepository
import com.nextlevellife.app.stats.Stat
import com.nextlevellife.app.stats.StatsMath
import com.nextlevellife.app.stats.StatsRepository
import com.nextlevellife.app.stats.StatsSummary
import com.nextlevellife.app.stats.StatsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Итог дня с прошлого показа: при возврате на главную карточка «Сегодня» не мигает. */
private var cachedToday: Pair<Long, ProgressMath.DayDigest>? = null

/** Расход за день с прошлого показа — по той же причине. */
private var cachedSpent: Pair<Long, Long>? = null

/** Ночной перерыв с прошлого показа — по той же причине. */
private var cachedNight: Pair<Long, SleepNight?>? = null

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
    onSettings: () -> Unit,
    /** Открыть «Как получить»: [STAT_INFO_LEVEL] или имя характеристики. */
    onStatInfo: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val widthClass = currentWidthClass()
    val health by ActivitySync.state.collectAsState()

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
    val streak = remember(version) { Store.streak() }
    val limitMin = remember(version) { Settings.dailyLimitMin }
    val nightOn = remember(version) { Settings.nightEnabled }
    val nightStart = remember(version) { Settings.nightStartMin }
    val nightEnd = remember(version) { Settings.nightEndMin }
    // Итог дня из дневника и расход за сегодня: читаются в фоне.
    val today = LocalDate.now().toEpochDay()
    val todayDigest by produceState(cachedToday?.takeIf { it.first == today }?.second, version, today) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ProgressMath.digestDay(
                    Diary.setsForDay(today), Diary.runsForDay(today),
                    activity = runCatching { Diary.activityDay(today) }.getOrNull(),
                )
            }.getOrNull()
        }?.also { cachedToday = today to it }
    }
    // Расход — за учётные сутки (доверенное время и учётный пояс), как считает лимит служба блокировки.
    val spendDay = TrustedClock.today()
    // Ночной перерыв перед сегодняшним днём (день пробуждения — учётные сутки). События экрана
    // экраны не будят, поэтому пересчёт ещё и при каждом возврате в приложение (resumeCount).
    val night by produceState(cachedNight?.takeIf { it.first == spendDay }?.second, version, spendDay, resumeCount) {
        value = withContext(Dispatchers.IO) { SleepRepository.night(spendDay) }
            .also { cachedNight = spendDay to it }
    }
    val spentToday by produceState(cachedSpent?.takeIf { it.first == spendDay }?.second, version, spendDay, resumeCount) {
        value = withContext(Dispatchers.IO) { runCatching { Ledger.spentMs(spendDay) }.getOrNull() }
            ?.also { cachedSpent = spendDay to it }
    }
    // Характеристики за всю историю — в фоне; пока считаются, виден прошлый итог.
    val stats by produceState(StatsRepository.cached(), version, resumeCount) {
        value = withContext(Dispatchers.IO) { StatsRepository.load() }
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
        TodayCard(todayDigest, night, health.availability, onProgress, onSettings)
        StatsCard(stats, streak, onStatInfo)
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

/**
 * «Сегодня»: ночной перерыв одной строкой (если подтверждён), итог дня, не больше двух строк тренировок,
 * затем шаги и бег из Health Connect; нажатие открывает вкладку «Прогресс». Без подключения — одна
 * строка-ссылка в настройки.
 */
@Composable
private fun TodayCard(
    digest: ProgressMath.DayDigest?,
    night: SleepNight?,
    health: HealthAvailability?,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть прогресс", onClick = onProgress),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сегодня", style = MaterialTheme.typography.titleMedium)
                ProgressMath.nightTodayLine(night)?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (digest == null || !digest.hasWorkout) {
                    Text(
                        "Тренировок ещё не было",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        digest.summary,
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
                when (health) {
                    HealthAvailability.CONNECTED -> ProgressMath.todayActivityLines(digest?.activity).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HealthAvailability.NOT_CONNECTED, HealthAvailability.UPDATE_REQUIRED -> Text(
                        "Подключи Health Connect, чтобы видеть шаги",
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClickLabel = "Открыть настройки", onClick = onSettings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Ещё не проверяли или на телефоне нет Health Connect — строки нет.
                    else -> Unit
                }
            }
            Chevron()
        }
    }
}

/**
 * Уровень и характеристики — раскрывающаяся карточка (ключ `home_stats`, по умолчанию свёрнута).
 * Свёрнутая: «Уровень 3 · Новичок» и серия, сводка «Сила 2 · Выносливость 1 · Дисциплина 3» и полоса
 * до следующего уровня; предупреждения о штрафе за простой видны всегда. Развёрнутая — ещё три строки
 * «Сила 2 · 7/10 сегодня» с полосами и «Что входит в уровень». Нажатие на заголовок только раскрывает
 * карточку; на характеристику — открывает «Как получить» для неё. [stats] = null — ещё не посчитано: нули.
 */
@Composable
private fun StatsCard(stats: StatsSummary?, streak: Int, onStatInfo: (String) -> Unit) {
    val overall = StatsMath.progress(stats?.overall ?: 0L)
    val level = overall.level
    val today = stats?.todayPoints
    val state = rememberExpandedState("home_stats")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val risks = Stat.entries.mapNotNull { StatsText.riskLine(it, stats?.riskOf(it) ?: 0) }
    Card(Modifier.fillMaxWidth()) {
        ExpandableHeader(state, spacing = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Уровень $level · ${Store.titleFor(level)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "🔥 $streak ${ProgressMath.daysWord(streak)}",
                    style = MaterialTheme.typography.titleSmall.merge(Tabular),
                    maxLines = 1,
                )
            }
            Text(
                ExpandableText.statsSummary { stats?.total(it) ?: 0L },
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = muted,
            )
            LinearProgressIndicator(
                progress = { overall.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(4.dp),
                color = Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        // Риск штрафа — важное предупреждение: виден и в свёрнутой карточке.
        if (risks.isNotEmpty()) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                risks.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        ExpandableBody(state, contentPadding = PaddingValues(bottom = 4.dp), spacing = 0.dp) {
            Stat.entries.forEachIndexed { i, stat ->
                StatLine(
                    stat = stat,
                    total = stats?.total(stat) ?: 0L,
                    today = today?.of(stat) ?: 0,
                    todayWord = i == 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Как получить: ${stat.title}") { onStatInfo(stat.name) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LinkRow("Что входит в уровень") { onStatInfo(STAT_INFO_LEVEL) }
        }
    }
}

/** Строка-ссылка «Что входит в уровень ›» в теле карточки: акцентным цветом, не ниже 48 dp. */
@Composable
internal fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$text ›", style = MaterialTheme.typography.labelLarge, color = Accent)
    }
}

/** «Сила 2 · 7/10 сегодня» (очки дня приглушены) и тонкая полоса до следующего уровня характеристики. */
@Composable
private fun StatLine(
    stat: Stat,
    total: Long,
    today: Int,
    todayWord: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = StatsMath.progress(total)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(StatsText.statLevel(stat, total))
                    withStyle(SpanStyle(color = muted)) {
                        append(" · ${StatsText.ofCap(today)}")
                        if (todayWord) append(" сегодня")
                    }
                },
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.titleMedium, color = muted)
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------- Заработать минуты ----------

private val ComplexCardColor = Color(0xFF2A1F0A)

/** Отступы тела группы на главной: вложенные карточки не прилипают к краю внешней. */
private val GroupBodyPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)

/**
 * «Заработать минуты»: три раскрывающиеся группы, по умолчанию свёрнуты — «Комплексы»
 * (ключ `home_complexes`), «Без веса» (`home_bodyweight`) и «С гирей» (`home_kettlebell`).
 * [extraComplexes] — карточки других комплексов, они идут после Синди; [complexesSummary] — сводка группы.
 */
@Composable
private fun EarnSection(
    version: Int,
    columns: Int,
    onExercise: (String) -> Unit,
    onComplex: (String) -> Unit,
    complexesSummary: String = "Минотавр, Синди",
    extraComplexes: @Composable () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Заработать минуты", style = MaterialTheme.typography.titleMedium)
        ExpandableCard(
            key = "home_complexes",
            title = "Комплексы",
            summary = complexesSummary,
            contentPadding = GroupBodyPadding,
            contentSpacing = 12.dp,
        ) {
            MinotaurCard(version) { onComplex(Minotaur.ID) }
            CindyCard(version) { onComplex(Cindy.ID) }
            extraComplexes()
        }
        ExpandableCard(
            key = "home_bodyweight",
            title = "Без веса",
            summary = ExpandableText.exerciseNames(Exercises.bodyweight),
            contentPadding = GroupBodyPadding,
            contentSpacing = 12.dp,
        ) {
            CardGrid(Exercises.bodyweight, columns) { ex, m -> ExerciseCard(ex, m, onExercise) }
        }
        ExpandableCard(
            key = "home_kettlebell",
            title = "С гирей",
            summary = ExpandableText.kettlebellSummary(Exercises.kettlebell.size),
            contentPadding = GroupBodyPadding,
            contentSpacing = 12.dp,
        ) {
            CardGrid(Exercises.kettlebell, columns) { ex, m -> ExerciseCard(ex, m, onExercise) }
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

/**
 * Карточка упражнения: название, под ним цена — длинное название не зажимается. Лежит внутри
 * группы, поэтому фон на тон светлее внешней карточки.
 */
@Composable
private fun ExerciseCard(ex: Exercise, modifier: Modifier, onExercise: (String) -> Unit) {
    Card(
        modifier.clickable { onExercise(ex.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ex.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    exercisePriceLine(ex),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ex.hold) {
                    // Планку камера видит только сбоку: телефон ставится на пол.
                    Text(
                        "камера сбоку, на полу",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chevron() {
    Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Цена упражнения для карточки: «1 повт = 30 сек»; у удержания — «1 сек = 5 сек». */
internal fun exercisePriceLine(ex: Exercise): String =
    if (ex.hold) {
        "1 сек = ${formatShort(Exercises.holdRewardMs(ex, 1_000L))}"
    } else {
        "1 повт = ${formatShort(Exercises.rewardMs(ex, 1, Weights.DEFAULT))}"
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
