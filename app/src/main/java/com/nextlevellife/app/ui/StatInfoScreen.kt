package com.nextlevellife.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.stats.Stat
import com.nextlevellife.app.stats.StatsGuide
import com.nextlevellife.app.stats.StatsMath
import com.nextlevellife.app.stats.StatsRepository
import com.nextlevellife.app.stats.StatsSummary
import com.nextlevellife.app.stats.StatsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Тема экрана «Как получить»: общий уровень. Остальные темы — имена [Stat]. */
const val STAT_INFO_LEVEL = "level"

/**
 * «Как получить»: для характеристики — сколько у тебя сейчас, очки за сегодня с источниками,
 * правила начисления, ограничения и пример; для уровня — из чего он складывается и лестница званий.
 * С экрана уровня можно перейти к характеристике; «Назад» оттуда возвращает к уровню.
 */
@Composable
fun StatInfoScreen(topic: String, resumeCount: Int, onBack: () -> Unit) {
    var current by rememberSaveable(topic) { mutableStateOf(topic) }
    val back = { if (current != topic) current = topic else onBack() }
    BackHandler(onBack = back)

    val version by Store.version.collectAsState()
    // Пересчёт и при возвращении в приложение, и после смены учётных суток: события экрана версию не меняют.
    val today = TrustedClock.today()
    val stats by produceState(StatsRepository.cached(), version, resumeCount, today) {
        value = withContext(Dispatchers.IO) { StatsRepository.load() }
    }
    val stat = Stat.entries.firstOrNull { it.name == current }
    val scroll = rememberScrollState()
    val widthClass = currentWidthClass()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(widthClass.outerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stat?.title ?: "Уровень",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = back) { Text(if (current != topic) "Назад" else "Готово") }
        }
        if (stat != null) {
            StatInfo(stat, stats)
        } else {
            LevelInfo(stats, onStat = { current = it.name })
        }
    }
}

@Composable
private fun StatInfo(stat: Stat, stats: StatsSummary?) {
    val guide = StatsGuide.forStat(stat, Settings.dailyLimitMin)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(guide.subtitle, style = MaterialTheme.typography.bodyLarge, color = muted)

    // Сейчас: уровень характеристики, очки и сегодняшние источники.
    val total = stats?.total(stat) ?: 0L
    val p = StatsMath.progress(total)
    val today = stats?.todayPoints
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    StatsText.statLevel(stat, total),
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                    color = Accent,
                    modifier = Modifier.weight(1f),
                )
                Text(StatsText.pointsLabel(total), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = muted)
            }
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(StatsText.toNextLevel(p.left), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
            Text(
                "Сегодня ${StatsText.ofCap(today?.of(stat) ?: 0)}",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.titleSmall.merge(Tabular),
            )
            val sources = today?.sourcesOf(stat).orEmpty()
            if (sources.isEmpty()) {
                Text("Пока без очков", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                sources.forEach { Text(StatsText.sourceLine(it), style = MaterialTheme.typography.bodySmall.merge(Tabular)) }
            }
            StatsText.riskLine(stat, stats?.riskOf(stat) ?: 0)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    Text("Как получить", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            guide.rules.forEachIndexed { i, rule ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.what, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            rule.reward,
                            style = MaterialTheme.typography.titleSmall.merge(Tabular),
                            color = Accent,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    if (rule.note != null) {
                        Text(rule.note, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
    guide.limits.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = muted) }

    Text("Если забросить", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Card(Modifier.fillMaxWidth()) {
        Text(guide.penalty, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Пример", style = MaterialTheme.typography.labelLarge, color = muted)
            Text(guide.example, style = MaterialTheme.typography.bodyLarge.merge(Tabular))
        }
    }
}

@Composable
private fun LevelInfo(stats: StatsSummary?, onStat: (Stat) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val overall = StatsMath.progress(stats?.overall ?: 0L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Уровень ${overall.level} · ${Store.titleFor(overall.level)}",
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                    color = Accent,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(StatsText.pointsLabel(overall.points), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = muted)
            }
            LinearProgressIndicator(
                progress = { overall.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(StatsText.toNextLevel(overall.left), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
        }
    }

    // Из чего сложилось: три характеристики, каждая открывает своё описание.
    Text("Из чего складывается", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Stat.entries.forEachIndexed { i, stat ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = "Как получить: ${stat.title}") { onStat(stat) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(StatsText.statLevel(stat, stats?.total(stat) ?: 0L), style = MaterialTheme.typography.bodyLarge.merge(Tabular))
                        Text(
                            StatsGuide.forStat(stat, Settings.dailyLimitMin).subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                    Text(
                        StatsText.pointsLabel(stats?.total(stat) ?: 0L),
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = muted,
                    )
                    Text("  ›", style = MaterialTheme.typography.titleMedium, color = muted)
                }
            }
        }
    }

    StatsGuide.levelFacts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }

    Text("Звания по уровням", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsGuide.ladder().forEach { step ->
                val reached = overall.level >= step.level
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${step.level} · ${step.title}",
                        style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                        color = if (reached) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (step.points == 0L) "с начала" else StatsText.pointsLabel(step.points),
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Text(StatsGuide.ladderNote, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = muted)
}
