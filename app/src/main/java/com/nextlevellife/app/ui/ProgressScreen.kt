package com.nextlevellife.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.ExerciseRecord
import com.nextlevellife.app.data.ExerciseTotal
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Табличные цифры: числа в столбик не прыгают по ширине. */
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

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
    val month = (savedMonth ?: currentMonth).coerceAtMost(currentMonth)
    val selected = (savedDay ?: today).coerceAtMost(today)

    val overview by produceState(ProgressCache.overview, version, today) {
        value = withContext(Dispatchers.IO) { loadOverview(today) }.also { ProgressCache.overview = it }
    }
    val trained by produceState(ProgressCache.trained?.takeIf { it.first == month }?.second ?: emptySet(), version, month) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Diary.trainedDays(ProgressMath.firstDayOfMonth(month), ProgressMath.lastDayOfMonth(month))
            }.getOrDefault(emptySet())
        }.also { ProgressCache.trained = month to it }
    }
    val dayData by produceState(ProgressCache.day, version, selected) {
        value = withContext(Dispatchers.IO) {
            val sets = runCatching { Diary.setsForDay(selected) }.getOrDefault(emptyList<SetEntry>())
            val runs = runCatching { Diary.runsForDay(selected) }.getOrDefault(emptyList<ComplexRun>())
            DayData(selected, ProgressMath.digestDay(sets, runs))
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Прогресс", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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

            val o = overview
            when {
                o == null -> Unit // грузится: обычно доли секунды
                !o.hasEntries -> EmptyDiaryCard()
                else -> {
                    DayCard(selected, today, dayData, Modifier.bringIntoViewRequester(dayRequester))
                    ChartCard(
                        days = o.chartDays,
                        reps = o.chartReps,
                        earnedMs = o.chartEarnedMs,
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
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
    )
}

@Composable
private fun LevelCard(xp: Long, streak: Int) {
    val level = Store.levelFor(xp)
    val (left, progress) = ProgressMath.levelProgress(xp, Store.xpForLevel(level), Store.xpForLevel(level + 1))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Уровень $level", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Accent)
                    Text(Store.titleFor(level), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(ProgressMath.streakLabel(streak), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
            Text(
                ProgressMath.dayTitle(selected, today),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val digest = data?.takeIf { it.day == selected }?.digest ?: return@Column
            if (digest.isEmpty) {
                Text("В этот день тренировок не было", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                ProgressMath.summaryLine(digest.sets, digest.reps, digest.earnedMs),
                style = MaterialTheme.typography.titleSmall.merge(Tabular),
                fontWeight = FontWeight.Bold,
                color = Accent,
            )
            if (digest.runs.isNotEmpty()) {
                SectionLabel("Комплексы")
                digest.runs.forEach { r ->
                    Text(
                        ProgressMath.complexLine(r),
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = if (r.completed) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (digest.totals.isNotEmpty()) {
                SectionLabel("По упражнениям")
                digest.totals.forEach { t ->
                    Text(ProgressMath.totalLine(t), style = MaterialTheme.typography.bodyMedium.merge(Tabular))
                }
            }
            if (digest.looseSets.isNotEmpty()) {
                SectionLabel("Подходы")
                digest.looseSets.forEach { s ->
                    Text(ProgressMath.setLine(s), style = MaterialTheme.typography.bodyMedium.merge(Tabular))
                }
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
