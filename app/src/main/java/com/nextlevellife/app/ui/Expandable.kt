package com.nextlevellife.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Store

/** Префикс ключа в SharedPreferences: `ui_expanded:home_stats`. */
private const val EXPANDED_PREFIX = "ui_expanded:"

/** Отступы тела раскрывающейся карточки по умолчанию: по бокам и снизу, сверху — уже отступ заголовка. */
val ExpandableBodyPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)

/**
 * Раскрыт ли раздел. Хранится в SharedPreferences ([Store.prefs]) под ключом `ui_expanded:<key>`
 * и переживает перезапуск. [Store.version] не трогается: раскрытие раздела — не изменение данных,
 * экраны из-за него не пересчитываются.
 */
@Stable
class ExpandedState internal constructor(private val key: String, initial: Boolean) {
    var expanded by mutableStateOf(initial)
        private set

    fun toggle() {
        expanded = !expanded
        // Store может быть не готов (предпросмотр) — тогда состояние живёт только в памяти.
        runCatching { Store.prefs().edit().putBoolean(EXPANDED_PREFIX + key, expanded).apply() }
    }
}

/** Состояние раздела [key]: при первом показе читается из настроек, по умолчанию — [defaultExpanded]. */
@Composable
fun rememberExpandedState(key: String, defaultExpanded: Boolean = false): ExpandedState = remember(key) {
    val saved = runCatching { Store.prefs().getBoolean(EXPANDED_PREFIX + key, defaultExpanded) }
        .getOrDefault(defaultExpanded)
    ExpandedState(key, saved)
}

/**
 * Раскрывающаяся карточка: заголовок ([title], под ним краткая сводка [summary]) и шеврон справа;
 * нажатие на заголовок раскрывает и сворачивает тело. Раскрытие запоминается между запусками
 * (ключ `ui_expanded:<key>`). [contentPadding] и [contentSpacing] — отступы тела и между его строками.
 */
@Composable
fun ExpandableCard(
    key: String,
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    summaryColor: Color? = null,
    contentPadding: PaddingValues = ExpandableBodyPadding,
    contentSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberExpandedState(key, defaultExpanded)
    Card(modifier.fillMaxWidth()) {
        ExpandableHeader(state) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = summaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ExpandableBody(state, contentPadding, contentSpacing, content)
    }
}

/**
 * Заголовок раскрывающегося раздела: строка на всю ширину не ниже 56 dp, слева [content] (столбцом),
 * справа шеврон «›», который поворачивается вниз при раскрытии. Нажатие — только раскрыть или свернуть.
 * Для карточек с нестандартным заголовком (уровень на главной и в «Прогрессе»).
 */
@Composable
fun ExpandableHeader(
    state: ExpandedState,
    modifier: Modifier = Modifier,
    spacing: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = state.expanded
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { stateDescription = if (expanded) "развёрнуто" else "свёрнуто" }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Свернуть" else "Развернуть",
                onClick = state::toggle,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        ExpandChevron(expanded)
    }
}

/** Тело раскрывающегося раздела: выезжает вниз с проявлением и уезжает обратно. */
@Composable
fun ExpandableBody(
    state: ExpandedState,
    contentPadding: PaddingValues = ExpandableBodyPadding,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = state.expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * Шеврон «›», нарисованный линией: в отличие от символа шрифта, он поворачивается ровно вокруг
 * своего центра. Свёрнуто — смотрит вправо, развёрнуто — вниз.
 */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .size(24.dp)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.40f, h * 0.25f)
            lineTo(w * 0.65f, h * 0.50f)
            lineTo(w * 0.40f, h * 0.75f)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
