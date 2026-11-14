package com.nextlevellife.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// «Режим столика»: складной телефон полусложен, сгиб горизонтальный — камера сверху, табло снизу
// ---------------------------------------------------------------------------------------------

/**
 * Телефон стоит «столиком»: горизонтальный сгиб в состоянии HALF_OPENED.
 * [foldTopPx]..[foldBottomPx] — сгиб в координатах окна по вертикали (у Fold это линия нулевой толщины,
 * у телефонов с видимым шарниром — полоса, на которой ничего не рисуется).
 */
data class Tabletop(val foldTopPx: Int, val foldBottomPx: Int)

/** Сгиб из WindowManager, сведённый к тому, что нужно раскладке: без Android, для тестов. */
internal data class FoldInfo(
    val horizontal: Boolean,
    val halfOpened: Boolean,
    val topPx: Int,
    val bottomPx: Int,
)

/** Высоты верхней половины, сгиба и нижней половины в пикселях; в сумме — высота области. */
internal data class TabletopPanes(val topPx: Int, val gapPx: Int, val bottomPx: Int)

/** Чистая логика режима столика (покрыта тестами). */
internal object TabletopMath {
    /** Меньше этой высоты половина не делается: при странных границах сгиба — ровно пополам. */
    const val MIN_PANE_DP = 160f

    /** С этой ширины нижняя половина — три колонки (сведения · табло · кнопки), уже — табло и сведения над кнопками. */
    const val WIDE_BOARD_DP = 560f

    /** Ширина колонки кнопок в широкой нижней половине. */
    const val ACTIONS_WIDTH_DP = 220f

    /** Столик — первый горизонтальный сгиб в состоянии HALF_OPENED; иначе null (обычный телефон, Fold раскрыт или в книжке). */
    fun detect(folds: List<FoldInfo>): Tabletop? = folds
        .firstOrNull { it.horizontal && it.halfOpened && it.bottomPx >= it.topPx }
        ?.let { Tabletop(it.topPx, it.bottomPx) }

    /**
     * Делит область высотой [containerHeightPx], верх которой в окне на [containerTopPx], по линии сгиба.
     * Сгиб вне области (или впритык к краю) — пополам. Каждая половина не ниже [minPanePx]
     * (если область вообще позволяет), сгиб между ними — пустой.
     */
    fun panes(containerTopPx: Int, containerHeightPx: Int, fold: Tabletop, minPanePx: Int): TabletopPanes {
        if (containerHeightPx <= 0) return TabletopPanes(0, 0, 0)
        var gap = (fold.foldBottomPx - fold.foldTopPx).coerceAtLeast(0)
        var top = fold.foldTopPx - containerTopPx
        if (top <= 0 || top + gap >= containerHeightPx) {
            top = containerHeightPx / 2
            gap = 0
        }
        val free = containerHeightPx - gap
        val minPane = minPanePx.coerceAtLeast(0).coerceAtMost(free / 2)
        top = top.coerceIn(minPane, free - minPane)
        return TabletopPanes(top, gap, free - top)
    }

    /** Нижняя половина достаточно широкая для трёх колонок. */
    fun wideBoard(widthDp: Float): Boolean = widthDp >= WIDE_BOARD_DP
}

/** Activity, к которой привязан [Context] (Compose отдаёт обёртки). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Поза складного телефона для текущего окна: [Tabletop], если он стоит «столиком», иначе null.
 * На обычных телефонах и у раскрытого/сложенного Fold — всегда null, раскладки не меняются.
 * Ставить выше выбора раскладки, рядом с остальным состоянием экрана.
 */
@Composable
fun rememberTabletop(): Tabletop? {
    val context = LocalContext.current
    val flow: Flow<Tabletop?> = remember(context) {
        val activity = context.findActivity()
        if (activity == null) {
            flowOf(null)
        } else {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .map { info ->
                    TabletopMath.detect(
                        info.displayFeatures.filterIsInstance<FoldingFeature>().map { f ->
                            FoldInfo(
                                horizontal = f.orientation == FoldingFeature.Orientation.HORIZONTAL,
                                halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
                                topPx = f.bounds.top,
                                bottomPx = f.bounds.bottom,
                            )
                        },
                    )
                }
                .distinctUntilChanged()
        }
    }
    val tabletop by flow.collectAsState(initial = null)
    return tabletop
}

/**
 * Раскладка «столиком»: [top] — над линией сгиба (камера), [bottom] — под ней (табло и кнопки).
 * Граница — реальная линия сгиба: своё положение в окне область узнаёт после первой раскладки
 * (до этого считается, что она начинается с верха окна). Отступы от краёв — как у остальных экранов.
 */
@Composable
internal fun TabletopSplit(
    tabletop: Tabletop,
    widthClass: WidthClass,
    modifier: Modifier = Modifier,
    top: @Composable (Modifier) -> Unit,
    bottom: @Composable () -> Unit,
) {
    var containerTop by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val pad = widthClass.outerPadding
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { containerTop = it.positionInWindow().y.roundToInt() },
    ) {
        val panes = TabletopMath.panes(
            containerTopPx = containerTop,
            containerHeightPx = constraints.maxHeight,
            fold = tabletop,
            minPanePx = with(density) { TabletopMath.MIN_PANE_DP.dp.roundToPx() },
        )
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { panes.topPx.toDp() })
                    .padding(start = pad, end = pad, top = pad, bottom = 8.dp),
            ) {
                top(Modifier.fillMaxSize())
            }
            if (panes.gapPx > 0) Spacer(Modifier.height(with(density) { panes.gapPx.toDp() }))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { panes.bottomPx.toDp() })
                    .padding(start = pad, end = pad, top = 8.dp, bottom = pad),
            ) {
                bottom()
            }
        }
    }
}

/**
 * Нижняя половина столика. Широкая: слева [info] (прокручивается), посередине крупное [score], справа
 * [actions] столбиком. Узкая (раскладушка): [info] и [score] рядом, кнопки в ряд под ними.
 * [score] получает модификатор, задающий его область, — счётчик сам подбирает размер под неё.
 */
@Composable
internal fun TabletopBoard(
    info: @Composable (Modifier) -> Unit,
    score: @Composable (Modifier) -> Unit,
    actions: @Composable (stacked: Boolean) -> Unit,
    gutter: Dp = 16.dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (TabletopMath.wideBoard(maxWidth.value)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                info(Modifier.weight(1f).fillMaxHeight())
                score(Modifier.weight(1.2f).fillMaxHeight())
                Column(
                    Modifier
                        .width(TabletopMath.ACTIONS_WIDTH_DP.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    actions(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gutter),
                ) {
                    info(Modifier.weight(1f).fillMaxHeight())
                    score(Modifier.weight(1f).fillMaxHeight())
                }
                actions(false)
            }
        }
    }
}
