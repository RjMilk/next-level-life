package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Nutrition
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.health.ActivityMath
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAvailability
import com.nextlevellife.app.sleep.NightStatus
import com.nextlevellife.app.sleep.SleepNight
import com.nextlevellife.app.sleep.SleepRepository
import com.nextlevellife.app.stats.DayPoints
import com.nextlevellife.app.stats.Stat
import com.nextlevellife.app.stats.StatsMath
import com.nextlevellife.app.stats.StatsRepository
import com.nextlevellife.app.stats.StatsSummary
import com.nextlevellife.app.stats.StatsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Сколько дней показывает диаграмма. */
private const val CHART_DAYS = 14

/** Всё для разделов, которые не зависят от выбранного дня и месяца. */
private data class Overview(
    val xp: Long,
    val streak: Int,
    val hasEntries: Boolean,
    val chartDays: List<Long>,
    val chartReps: List<Int>,
    val chartEarnedMs: Long,
    val minotaurBest: List<Pair<Int, Long?>>,
    val cindyBest: Pair<Int, Int>?,
    val records: List<ExerciseRecord>,
    /** Шаги по дням диаграммы (null — данных нет) и метры бега. */
    val chartSteps: List<Long?> = emptyList(),
    val chartRunMeters: List<Long> = emptyList(),
    /** За дни диаграммы есть хоть какие-то данные Health Connect. */
    val hasActivityData: Boolean = false,
    val bestStepsDay: Pair<Long, Long>? = null,
    val longestRun: ActivityRun? = null,
    /** Ночной перерыв по дням диаграммы (мс; null — нет подтверждённых данных) и есть ли хоть какие-то ночи. */
    val chartBreakMs: List<Long?> = emptyList(),
    val chartBreakProvisional: Boolean = false,
    val hasNightData: Boolean = false,
)

/** День вместе с номером: пока грузится новый, на экране не перепутать даты. */
private data class DayData(val day: Long, val digest: ProgressMath.DayDigest, val nutrition: String? = null)

/**
 * Последние загруженные данные. Вкладка при каждом открытии создаётся заново; с кешем она
 * сразу рисуется целиком, без мигания и без сброса восстановленной прокрутки, а свежие
 * данные подменяют кеш через долю секунды. Пишется и читается только из главного потока.
 */
private object ProgressCache {
    var overview: Overview? = null
    var trained: Pair<Int, Set<Long>>? = null
    var phoneFree: Pair<Int, Set<Long>>? = null
    var day: DayData? = null
    var history: Pair<String, List<Pair<Long, ExerciseTotal>>>? = null
}

@Composable
fun ProgressScreen(modifier: Modifier = Modifier, onStatInfo: (String) -> Unit = {}) {
    val version by Store.version.collectAsState()
    // Учётные сутки (доверенное время и учётный пояс) — как на главной и у ночей.
    val today = TrustedClock.today()
    val currentMonth = ProgressMath.monthIndexOfDay(today)

    // null — «текущий месяц» и «сегодня»: после полуночи они сдвигаются сами.
    var savedMonth by rememberSaveable { mutableStateOf<Int?>(null) }
    var savedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var chartMetricName by rememberSaveable { mutableStateOf(ProgressMath.ChartMetric.REPS.name) }
    val chartMetric = ProgressMath.ChartMetric.entries.firstOrNull { it.name == chartMetricName } ?: ProgressMath.ChartMetric.REPS
    val month = (savedMonth ?: currentMonth).coerceAtMost(currentMonth)
    val selected = (savedDay ?: today).coerceAtMost(today)
    val health by ActivitySync.state.collectAsState()
    val connectHealth = rememberHealthConnectAction()

    val overview by produceState(ProgressCache.overview, version, today) {
        value = withContext(Dispatchers.IO) { loadOverview(today) }.also { ProgressCache.overview = it }
    }
    val trained by produceState(ProgressCache.trained?.takeIf { it.first == month }?.second ?: emptySet(), version, month) {
        value = withContext(Dispatchers.IO) {
            val from = ProgressMath.firstDayOfMonth(month)
            val to = ProgressMath.lastDayOfMonth(month)
            // Пробежка делает день тренировочным, обычные шаги — нет.
            runCatching { Diary.trainedDays(from, to) }.getOrDefault(emptySet()) +
                runCatching { Diary.runDays(from, to) }.getOrDefault(emptySet())
        }.also { ProgressCache.trained = month to it }
    }
    // Ночи без телефона за месяц — точки в календаре.
    val phoneFree by produceState(ProgressCache.phoneFree?.takeIf { it.first == month }?.second ?: emptySet(), version, month) {
        value = withContext(Dispatchers.IO) {
            SleepRepository.nights(ProgressMath.firstDayOfMonth(month), ProgressMath.lastDayOfMonth(month))
                .values.filter { it.phoneFree }.mapTo(HashSet()) { it.wakeDay }
        }.also { ProgressCache.phoneFree = month to it }
    }
    val dayData by produceState(ProgressCache.day, version, selected) {
        value = withContext(Dispatchers.IO) {
            val sets = runCatching { Diary.setsForDay(selected) }.getOrDefault(emptyList<SetEntry>())
            val runs = runCatching { Diary.runsForDay(selected) }.getOrDefault(emptyList<ComplexRun>())
            val activity = runCatching { Diary.activityDay(selected) }.getOrNull()
            val activityRuns = runCatching { Diary.activityRunsForDay(selected) }.getOrDefault(emptyList())
            val night = SleepRepository.night(selected)
            val nutrition = runCatching { Nutrition.dayLine(selected) }.getOrNull()
            DayData(selected, ProgressMath.digestDay(sets, runs, activity, activityRuns, night), nutrition)
        }.also { ProgressCache.day = it }
    }
    // Характеристики за всю историю и очки по дням; пока считаются, виден прошлый итог.
    val stats by produceState(StatsRepository.cached(), version, today) {
        value = withContext(Dispatchers.IO) { StatsRepository.load() }
    }
    val history by produceState(ProgressCache.history, version, expanded) {
        val id = expanded
        value = if (id == null) null else withContext(Dispatchers.IO) {
            id to runCatching { Diary.exerciseHistory(id, 30) }.getOrDefault(emptyList())
        }.also { ProgressCache.history = it }
    }

    val scope = rememberCoroutineScope()
    val dayRequester = remember { BringIntoViewRequester() }
    val selectDay: (Long) -> Unit = { day ->
        savedDay = day
        savedMonth = ProgressMath.monthIndexOfDay(day)
    }

    val widthClass = currentWidthClass()
    val pad = widthClass.outerPadding
    // Прокрутки живут снаружи веток раскладки: при раскрытии Fold они не сбрасываются.
    // mainScroll — колонка с карточкой дня: к ней прокручивает нажатие на столбик диаграммы.
    val mainScroll = rememberScrollState()
    val sideScroll = rememberScrollState()

    val header: @Composable () -> Unit = {
        Text("Прогресс", style = MaterialTheme.typography.headlineMedium)
    }
    val levelAndCalendar: @Composable () -> Unit = {
        CharacterCard(stats, overview?.streak ?: Store.streak(), legacyXp = overview?.xp ?: Store.xp(), onStatInfo = onStatInfo)
        CalendarCard(
            month = month,
            today = today,
            selected = selected,
            trained = trained,
            onMonth = { savedMonth = it },
            onSelect = selectDay,
            phoneFree = phoneFree,
        )
    }
    val details: @Composable () -> Unit = {
        val o = overview
        val availability = health.availability
        if (availability == HealthAvailability.NOT_CONNECTED || availability == HealthAvailability.UPDATE_REQUIRED) {
            ConnectHealthCard { connectHealth(health.access) }
        }
        when {
            o == null -> Unit // грузится: обычно доли секунды
            !o.hasEntries -> EmptyDiaryCard()
            else -> {
                DayCard(selected, today, dayData, Modifier.bringIntoViewRequester(dayRequester), stats?.days?.get(selected))
                ChartCard(
                    days = o.chartDays,
                    reps = o.chartReps,
                    steps = o.chartSteps,
                    runMeters = o.chartRunMeters,
                    breakMs = o.chartBreakMs,
                    breakProvisional = o.chartBreakProvisional,
                    earnedMs = o.chartEarnedMs,
                    metric = chartMetric,
                    metrics = buildList {
                        add(ProgressMath.ChartMetric.REPS)
                        if (o.hasActivityData || health.connected) {
                            add(ProgressMath.ChartMetric.STEPS)
                            add(ProgressMath.ChartMetric.RUN_KM)
                        }
                        if (o.hasNightData) add(ProgressMath.ChartMetric.BREAK_H)
                    },
                    onMetric = { chartMetricName = it.name },
                    today = today,
                    selected = selected,
                    onSelect = { day ->
                        selectDay(day)
                        scope.launch { dayRequester.bringIntoView() }
                    },
                )
                RecordsCard(
                    minotaurBest = o.minotaurBest,
                    cindyBest = o.cindyBest,
                    records = o.records,
                    expanded = expanded,
                    history = history?.takeIf { it.first == expanded }?.second,
                    onToggle = { id -> expanded = if (expanded == id) null else id },
                    bestStepsDay = o.bestStepsDay,
                    longestRun = o.longestRun,
                    today = today,
                )
            }
        }
    }

    if (widthClass == WidthClass.EXPANDED) {
        // Слева 320 dp — уровень и календарь, справа — день, диаграмма и рекорды; колонки прокручиваются независимо.
        Row(
            modifier
                .fillMaxSize()
                .padding(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(widthClass.gutter),
        ) {
            Column(
                Modifier
                    .width(ProgressSideWidth)
                    .fillMaxHeight()
                    .verticalScroll(sideScroll)
                    .padding(vertical = pad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                levelAndCalendar()
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(mainScroll)
                    .padding(vertical = pad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                details()
            }
        }
    } else {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(mainScroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(pad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                levelAndCalendar()
                details()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun loadOverview(today: Long): Overview {
    val days = ProgressMath.lastDays(today, CHART_DAYS)
    val summaries = runCatching { Diary.daySummaries(days.first(), days.last()) }.getOrDefault(emptyList())
    val byDay = summaries.associateBy { it.day }
    val exerciseOrder = Exercises.all.map { it.id }
    val records = runCatching { Diary.exerciseRecords() }.getOrDefault(emptyList())
        .sortedWith(
            compareBy<ExerciseRecord> { exerciseOrder.indexOf(it.exerciseId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.exerciseName }
        )
    val activity = runCatching { Diary.activityDays(days.first(), days.last()) }.getOrDefault(emptyList())
        .associateBy { it.day }
    val (bestSteps, longestRun) = runCatching { Diary.activityRecords() }.getOrDefault(null to null)
    // Ночи по дням пробуждения; сегодняшняя до 12:00 — предварительная (в базе её ещё нет).
    val nights = SleepRepository.nights(days.first(), days.last())
    val hasNight = nights.values.any { it.status != NightStatus.UNKNOWN }
    return Overview(
        xp = Store.xp(),
        streak = Store.streak(),
        hasEntries = runCatching { Diary.hasEntries() }.getOrDefault(false) || hasNight,
        chartDays = days,
        chartReps = days.map { byDay[it]?.reps ?: 0 },
        chartEarnedMs = summaries.sumOf { it.earnedMs },
        minotaurBest = Weights.all.map { w -> w to minotaurBestMs(w) },
        cindyBest = cindyBestRounds(),
        records = records,
        chartSteps = days.map { activity[it]?.steps },
        chartRunMeters = days.map { activity[it]?.runDistanceM ?: 0L },
        hasActivityData = activity.values.any { it.steps != null || it.runsCount > 0 },
        bestStepsDay = bestSteps,
        longestRun = longestRun,
        chartBreakMs = days.map { ProgressMath.breakChartValue(nights[it]) },
        chartBreakProvisional = ProgressMath.breakChartProvisional(days.map { nights[it] }),
        hasNightData = hasNight,
    )
}

/** Без разрешения Health Connect: короткое приглашение с кнопкой. */
@Composable
private fun ConnectHealthCard(onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Подключи Health Connect, чтобы видеть шаги и пробежки",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = onConnect, shape = ButtonShape) { Text("Подключить") }
            }
        }
    }
}

/**
 * «Характеристики» — раскрывающаяся карточка (ключ `progress_character`, по умолчанию свёрнута).
 * Свёрнутая: общий уровень (сумма очков трёх характеристик) со званием, серия, полоса и «До следующего
 * уровня». Развёрнутая — ещё Сила, Выносливость и Дисциплина (уровень, очки за всё время, полоса),
 * «Что входит в уровень» и мелкие примечания, в том числе опыт до обновления 0.6 (за минуты), если он был.
 * Нажатие на заголовок только раскрывает карточку; на характеристику — открывает «Как получить» ([onStatInfo]).
 */
@Composable
private fun CharacterCard(stats: StatsSummary?, streak: Int, legacyXp: Long, onStatInfo: (String) -> Unit) {
    val overall = StatsMath.progress(stats?.overall ?: 0L)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val state = rememberExpandedState("progress_character")
    Card(Modifier.fillMaxWidth()) {
        ExpandableHeader(state, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Серия — под уровнем, а не справа: на узком экране справа она прятала номер уровня.
                Column(Modifier.weight(1f)) {
                    Text(
                        "Уровень ${overall.level}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Accent,
                        maxLines = 1,
                    )
                    Text(
                        "${Store.titleFor(overall.level)} · ${ProgressMath.streakLabel(streak)}",
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { overall.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(StatsText.toNextLevel(overall.left), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
        }
        ExpandableBody(state, contentPadding = PaddingValues(bottom = 12.dp), spacing = 4.dp) {
            Stat.entries.forEach { stat ->
                val total = stats?.total(stat) ?: 0L
                val p = StatsMath.progress(total)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Как получить: ${stat.title}") { onStatInfo(stat.name) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            StatsText.statLevel(stat, total),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall.merge(Tabular),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            StatsText.pointsLabel(total),
                            style = MaterialTheme.typography.bodySmall.merge(Tabular),
                            color = muted,
                            maxLines = 1,
                        )
                        Text("  ›", style = MaterialTheme.typography.titleMedium, color = muted)
                    }
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = Accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            LinkRow("Что входит в уровень") { onStatInfo(STAT_INFO_LEVEL) }
            Text(
                "Очки привычек: до ${StatsMath.DAY_CAP} в день на каждую. Нажми, чтобы узнать, как их получить",
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            if (legacyXp > 0L) {
                Text(
                    StatsText.legacyXpLine(legacyXp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = muted,
                )
            }
        }
    }
}

/**
 * Очки выбранного дня: «Сила +7 · Выносливость +3 · Дисциплина +4»; нажатие раскрывает источники
 * («70 повторений → 7», «Ночь без телефона → 4»). Сегодня — «предварительно»: дисциплина за сутки
 * начисляется после их окончания. Пустой прошлый день не показывается.
 */
@Composable
private fun DayStatsSection(points: DayPoints) {
    // Прошлый день может быть «предварительным» только из-за сбоя чтения ночей — пустым его не показываем.
    val isToday = points.day == TrustedClock.today()
    if (points.total == 0 && points.sources.isEmpty() && !(points.provisional && isToday)) return
    var open by rememberSaveable(points.day) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val canOpen = points.sources.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (canOpen) {
                    Modifier.clickable(onClickLabel = if (open) "Скрыть источники очков" else "Показать источники очков") { open = !open }
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(StatsText.dayLine(points), style = MaterialTheme.typography.titleSmall.merge(Tabular), color = Accent)
        if (points.provisional) {
            Text(
                if (isToday) "предварительно · дисциплина за сутки — после их окончания"
                else "предварительно · данные о ночи ещё не прочитаны",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (canOpen) {
            Text(
                if (open) "Скрыть источники ▴" else "Откуда очки ▾",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
            )
        }
        if (open) {
            Stat.entries.forEach { stat ->
                val sources = points.sourcesOf(stat)
                if (sources.isEmpty()) return@forEach
                Text(
                    stat.title,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
                sources.forEach { s ->
                    Text(StatsText.sourceLine(s), style = MaterialTheme.typography.bodySmall.merge(Tabular))
                }
            }
        }
    }
}

@Composable
private fun EmptyDiaryCard() {
    Card(Modifier.fillMaxWidth()) {
        Text(
            "Здесь появится дневник: сделай первый подход на вкладке «Тренировка»",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DayCard(selected: Long, today: Long, data: DayData?, modifier: Modifier = Modifier, points: DayPoints? = null) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ProgressMath.dayTitle(selected, today), style = MaterialTheme.typography.titleMedium)
            // Очки характеристик за день — сразу под датой.
            if (points != null && points.day == selected) DayStatsSection(points)
            val dayData = data?.takeIf { it.day == selected } ?: return@Column
            val digest = dayData.digest
            // Питание: «Питание: 3 приёма · окно 08:40 – 20:15 · 11 ч 35 мин» — если приёмы были.
            dayData.nutrition?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Ночной перерыв перед этим днём — первым: день начинается с пробуждения.
            val night = digest.night
            if (night != null) NightSection(night)
            // Шаги и пробежки — перед подходами.
            val activity = digest.activity
            if (activity != null || digest.activityRuns.isNotEmpty()) {
                SectionLabel("Шаги и бег")
                StatRow(ProgressMath.stepsCells(activity?.steps))
                digest.activityRuns.forEach { r -> StatRow(ProgressMath.activityRunCells(r)) }
                activity?.rewardedMs?.let { ActivityMath.rewardLine(it) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = Accent)
                }
            }
            if (!digest.hasWorkout) {
                Text(
                    if (digest.hasActivity) "Подходов не было" else "В этот день тренировок не было",
                    modifier = Modifier.padding(top = if (activity != null || night != null) 6.dp else 0.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (activity != null || night != null) SectionLabel("Тренировка")
            Text(
                // Неразрывный пробел перед единицами: на узком экране «мин» не остаётся одно на строке.
                digest.summary
                    .replace(" мин", " мин").replace(" ч", " ч"),
                style = MaterialTheme.typography.titleSmall.merge(Tabular),
                color = Accent,
            )
            if (digest.runs.isNotEmpty()) {
                SectionLabel("Комплексы")
                digest.runs.forEach { r -> StatRow(ProgressMath.complexCells(r), dim = !r.completed) }
            }
            if (digest.totals.isNotEmpty()) {
                SectionLabel("По упражнениям")
                digest.totals.forEach { t -> StatRow(ProgressMath.totalCells(t)) }
            }
            if (digest.looseSets.isNotEmpty()) {
                SectionLabel("Подходы")
                digest.looseSets.forEach { s -> StatRow(ProgressMath.setCells(s)) }
            }
        }
    }
}

/**
 * «Ночной перерыв»: «7 ч 35 мин · 23:20 → 07:00», под ним мелко короткие обращения и «по этому телефону»,
 * значок «Ночь без телефона». Это оценка по экрану телефона, не сон — так и подписано.
 */
@Composable
private fun NightSection(night: SleepNight) {
    SectionLabel("Ночной перерыв")
    val (main, note) = ProgressMath.nightCells(night)
    Text(
        main,
        style = MaterialTheme.typography.titleSmall.merge(Tabular),
        color = if (night.status == NightStatus.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        note,
        style = MaterialTheme.typography.bodySmall.merge(Tabular),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (night.phoneFree) {
        Text(
            "🌙 Ночь без телефона",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Строка таблицы: название слева (переносится, если длинное), показатели справа табличными
 * цифрами — столбец чисел не прыгает. [dim] — приглушить (незавершённый комплекс).
 */
@Composable
internal fun StatRow(cells: ProgressMath.Cells, dim: Boolean = false) {
    val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            cells.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            color = color,
        )
        Text(
            cells.value,
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
