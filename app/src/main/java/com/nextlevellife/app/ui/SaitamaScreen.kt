package com.nextlevellife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.MontageBeat
import com.nextlevellife.app.data.Saitama
import com.nextlevellife.app.data.SaitamaDay
import com.nextlevellife.app.data.SaitamaGoal
import com.nextlevellife.app.data.SaitamaMark
import com.nextlevellife.app.data.SaitamaMath
import com.nextlevellife.app.data.SaitamaRepository
import com.nextlevellife.app.data.SaitamaState
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.pose.rememberRepSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Тёмно-янтарный фон карточек комплексов — как на главной. */
internal val SaitamaCardColor = Color(0xFF2A1F0A)

/** Последнее загруженное состояние: при возврате на экран оно видно сразу, пока идёт перечитывание. */
@Volatile private var cachedState: SaitamaState? = null

/**
 * Экран «Сайтамы»: серия и рекорд, прогресс четырёх целей дня с кнопками «Сделать», что осталось,
 * полоса последних 14 дней и «нарезка». При открытии и каждом обновлении данных доплачивает бонус за
 * полные дни ([SaitamaRepository.ensureBonuses]) и показывает «нарезку» новых вех дня.
 * EXPANDED (раскрытый Fold) — две колонки: прогресс слева, серия и 14 дней справа; иначе одна колонка
 * не шире [ContentMaxWidth] по центру.
 *
 * [onExercise] — открыть упражнение по id (после подхода вернуться сюда), [onBack] — на главную.
 */
@Composable
fun SaitamaScreen(resumeCount: Int, onExercise: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val health by ActivitySync.state.collectAsState()
    val widthClass = currentWidthClass()
    val sound = rememberRepSound()

    // Пробежка с часов могла прийти: попросить синхронизацию (само чтение — не чаще раза в 5 минут).
    LaunchedEffect(resumeCount) { ActivitySync.request(context) }

    val today = TrustedClock.today()
    val access = health.access
    val state by produceState(cachedState?.takeIf { it.today == today }, version, today, access, resumeCount) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                SaitamaRepository.ensureBonuses()
                SaitamaRepository.load(access)
            }.getOrNull()
        }?.also { cachedState = it } ?: value
    }

    var montage by remember { mutableStateOf<List<MontageBeat>>(emptyList()) }
    val playing = montage.isNotEmpty()
    // Новые вехи дня: после каждой загрузки (возврат с упражнения, пришла пробежка); пока идёт «нарезка» — ждут её конца.
    LaunchedEffect(state, playing) {
        val s = state ?: return@LaunchedEffect
        if (playing) return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) { SaitamaRepository.takeNewMilestones(s) }
        if (fresh.isNotEmpty()) montage = SaitamaMath.montage(fresh, s.streak)
    }

    val s = state
    val pad = widthClass.outerPadding
    Box(Modifier.fillMaxSize()) {
        if (s == null) {
            Column(Modifier.padding(pad)) { SaitamaHeader() }
        } else {
            val replay = { montage = SaitamaMath.replay(s.day.reached, s.streak) }
            if (widthClass == WidthClass.EXPANDED) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = pad),
                    horizontalArrangement = Arrangement.spacedBy(widthClass.gutter),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = pad),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SaitamaHeader()
                        GoalsCard(s, onExercise)
                        TodayStatus(s)
                        SaitamaNotes()
                        SaitamaButtons(s, replay, onBack)
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = pad),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StreakCard(s)
                        RecentDays(s)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = ContentMaxWidth)
                        .padding(pad),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SaitamaHeader()
                    StreakCard(s)
                    GoalsCard(s, onExercise)
                    TodayStatus(s)
                    RecentDays(s)
                    SaitamaNotes()
                    SaitamaButtons(s, replay, onBack)
                }
            }
        }
        if (playing) SaitamaMontage(montage, sound) { montage = emptyList() }
    }
}

@Composable
private fun SaitamaHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Сайтама", style = MaterialTheme.typography.headlineMedium, color = Accent)
        Text(
            "Каждый день: 100 отжиманий, 100 на пресс, 100 приседаний и 10 км бега",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Серия крупно и рекорд. */
@Composable
private fun StreakCard(s: SaitamaState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = SaitamaCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("🔥 Серия ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline())
                Text("${s.streak}", style = scoreStyle(48, 56), color = Accent, modifier = Modifier.alignByBaseline())
                Text(
                    " ${SaitamaMath.daysWord(s.streak)}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                "рекорд ${s.record}",
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!s.day.full) {
                Text(
                    "Сегодняшний день ещё не пропуск: серия считается до вчера",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Четыре цели дня. */
@Composable
private fun GoalsCard(s: SaitamaState, onExercise: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SaitamaGoal.entries.forEachIndexed { i, goal ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                GoalRow(goal, s.day, s.runReason, onExercise)
            }
        }
    }
}

@Composable
private fun GoalRow(goal: SaitamaGoal, day: SaitamaDay, runReason: String?, onExercise: (String) -> Unit) {
    val done = day.done(goal)
    val value = SaitamaMath.valueText(day, goal)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, style = scoreStyle(20, 28), color = if (done) Good else MaterialTheme.colorScheme.onSurface)
            } else {
                Text("Бег не подтверждён", style = MaterialTheme.typography.titleSmall, color = Accent)
            }
        }
        if (value != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { SaitamaMath.fraction(day, goal) },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (done) Good else Accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                val exerciseId = goal.exerciseId
                if (done) {
                    Spacer(Modifier.width(12.dp))
                    Text("✓", style = MaterialTheme.typography.titleMedium, color = Good)
                } else if (exerciseId != null) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onExercise(exerciseId) },
                        shape = ButtonShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) { Text("Сделать") }
                }
            }
        } else if (runReason != null) {
            Text(runReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (goal == SaitamaGoal.RUN && !done) {
            Text(
                "Засчитывается тренировка «Бег» с часов через Health Connect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Сегодня осталось: …» или «День выполнен ✓ +10 мин». */
@Composable
private fun TodayStatus(s: SaitamaState) {
    if (s.day.full) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "День выполнен ✓" + if (s.bonusPaidToday) " +${formatShort(Saitama.BONUS_MS)}" else "",
                style = MaterialTheme.typography.titleMedium,
                color = Good,
            )
            if (!s.bonusPaidToday) {
                Text(
                    "+${formatShort(Saitama.BONUS_MS)} начислятся при следующей проверке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        SaitamaMath.remainingText(s.day)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge.merge(Tabular))
        }
    }
}

/** Последние 14 дней: две строки по 7, цвет — полный / неполный / без данных о беге. */
@Composable
private fun RecentDays(s: SaitamaState) {
    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Последние ${Saitama.CHECK_DAYS} дней", style = MaterialTheme.typography.titleSmall)
            s.marks.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { (day, mark) -> DayCell(day, mark, Modifier.weight(1f)) }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(SaitamaMark.FULL, "полный")
                LegendItem(SaitamaMark.PARTIAL, "неполный")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(SaitamaMark.NO_RUN_DATA, "нет данных о беге")
                LegendItem(SaitamaMark.TODAY, "сегодня")
            }
        }
    }
}

@Composable
private fun DayCell(day: Long, mark: SaitamaMark, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val (bg, fg) = markColors(mark)
    var m = modifier
        .aspectRatio(1f)
        .clip(shape)
        .background(bg)
    m = when (mark) {
        SaitamaMark.TODAY -> m.border(2.dp, Accent, shape)
        SaitamaMark.NO_RUN_DATA -> m.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), shape)
        else -> m
    }
    Box(m, contentAlignment = Alignment.Center) {
        Text(
            "${LocalDate.ofEpochDay(day).dayOfMonth}",
            style = MaterialTheme.typography.labelLarge.merge(Tabular).copy(
                fontWeight = if (mark == SaitamaMark.FULL) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = fg,
        )
    }
}

@Composable
private fun markColors(mark: SaitamaMark): Pair<Color, Color> {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return when (mark) {
        SaitamaMark.FULL -> Accent to OnAccent
        SaitamaMark.PARTIAL -> MaterialTheme.colorScheme.surfaceVariant to variant
        SaitamaMark.NO_RUN_DATA -> Color.Transparent to variant
        SaitamaMark.TODAY -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        SaitamaMark.BEFORE_START -> Color.Transparent to variant.copy(alpha = 0.35f)
    }
}

@Composable
private fun LegendItem(mark: SaitamaMark, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(3.dp)
        val (bg, _) = markColors(mark)
        var m = Modifier
            .size(12.dp)
            .clip(shape)
            .background(bg)
        m = when (mark) {
            SaitamaMark.TODAY -> m.border(2.dp, Accent, shape)
            SaitamaMark.NO_RUN_DATA -> m.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), shape)
            else -> m
        }
        Box(m)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SaitamaNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Note("Сотни набираются любыми подходами за сутки — отдельными упражнениями и в Синди. Планка не считается, лишнее на завтра не переносится.")
        Note("Повторения и бег оплачиваются как обычно, за полный день — ещё +${formatShort(Saitama.BONUS_MS)}. Полный день засчитывается силе как завершённый комплекс.")
        Note("Серия — полные дни подряд. Пропущенный день обнуляет серию, сегодняшний незаконченный — нет.")
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.SaitamaButtons(s: SaitamaState, onReplay: () -> Unit, onBack: () -> Unit) {
    val canReplay = s.day.reached.isNotEmpty()
    OutlinedButton(
        onClick = onReplay,
        enabled = canReplay,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text("Показать нарезку") }
    if (!canReplay) {
        Text(
            "Нарезка появится после первой сотни",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
    Button(
        onClick = onBack,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text("Готово") }
}
