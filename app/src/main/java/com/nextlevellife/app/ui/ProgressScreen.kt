package com.nextlevellife.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.health.ActivityMath
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
)

/** День вместе с номером: пока грузится новый, на экране не перепутать даты. */
private data class DayData(val day: Long, val digest: ProgressMath.DayDigest)

/**
 * Последние загруженные данные. Вкладка при каждом открытии создаётся заново; с кешем она
 * сразу рисуется целиком, без мигания и без сброса восстановленной прокрутки, а свежие
 * данные подменяют кеш через долю секунды. Пишется и читается только из главного потока.
 */
private object ProgressCache {
    var overview: Overview? = null
    var trained: Pair<Int, Set<Long>>? = null
    var day: DayData? = null
    var history: Pair<String, List<Pair<Long, ExerciseTotal>>>? = null
}

@Composable
fun ProgressScreen(modifier: Modifier = Modifier) {
    val version by Store.version.collectAsState()
    val today = LocalDate.now().toEpochDay()
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
    val dayData by produceState(ProgressCache.day, version, selected) {
        value = withContext(Dispatchers.IO) {
            val sets = runCatching { Diary.setsForDay(selected) }.getOrDefault(emptyList<SetEntry>())
            val runs = runCatching { Diary.runsForDay(selected) }.getOrDefault(emptyList<ComplexRun>())
            val activity = runCatching { Diary.activityDay(selected) }.getOrNull()
            val activityRuns = runCatching { Diary.activityRunsForDay(selected) }.getOrDefault(emptyList())
            DayData(selected, ProgressMath.digestDay(sets, runs, activity, activityRuns))
        }.also { ProgressCache.day = it }
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
        val xp = overview?.xp ?: Store.xp()
        LevelCard(xp, overview?.streak ?: Store.streak())
        CalendarCard(
            month = month,
            today = today,
            selected = selected,
            trained = trained,
            onMonth = { savedMonth = it },
            onSelect = selectDay,
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
                DayCard(selected, today, dayData, Modifier.bringIntoViewRequester(dayRequester))
                ChartCard(
                    days = o.chartDays,
                    reps = o.chartReps,
                    steps = o.chartSteps,
                    runMeters = o.chartRunMeters,
                    earnedMs = o.chartEarnedMs,
                    metric = chartMetric,
                    showMetrics = o.hasActivityData || health.connected,
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
    return Overview(
        xp = Store.xp(),
        streak = Store.streak(),
        hasEntries = runCatching { Diary.hasEntries() }.getOrDefault(false),
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

@Composable
private fun LevelCard(xp: Long, streak: Int) {
    val level = Store.levelFor(xp)
    val (left, progress) = ProgressMath.levelProgress(xp, Store.xpForLevel(level), Store.xpForLevel(level + 1))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Уровень $level", style = MaterialTheme.typography.headlineMedium, color = Accent)
                    Text(
                        Store.titleFor(level),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(ProgressMath.streakLabel(streak), style = MaterialTheme.typography.titleSmall.merge(Tabular))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "До следующего уровня $left опыта",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun DayCard(selected: Long, today: Long, data: DayData?, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ProgressMath.dayTitle(selected, today), style = MaterialTheme.typography.titleMedium)
            val digest = data?.takeIf { it.day == selected }?.digest ?: return@Column
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
                    modifier = Modifier.padding(top = if (activity != null) 6.dp else 0.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (activity != null) SectionLabel("Тренировка")
            Text(
                // Неразрывный пробел перед единицами: на узком экране «мин» не остаётся одно на строке.
                ProgressMath.summaryLine(digest.sets, digest.reps, digest.earnedMs)
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
