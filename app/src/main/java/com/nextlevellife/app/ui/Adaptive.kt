package com.nextlevellife.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Класс ширины окна: S25 — COMPACT (~360 dp), развёрнутый Z Fold — EXPANDED (~840–900 dp).
 * Считается по всей доступной ширине окна, до вычета навигации (нижней панели или боковой рейки).
 */
enum class WidthClass {
    /** < 600 dp: одна колонка, нижняя панель вкладок. */
    COMPACT,

    /** 600–839 dp: одна колонка не шире [ContentMaxWidth], боковая рейка. */
    MEDIUM,

    /** ≥ 840 dp: две колонки, боковая рейка. */
    EXPANDED;

    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f

        /** Чистая функция: класс по ширине окна в dp. */
        fun of(widthDp: Float): WidthClass = when {
            widthDp < MEDIUM_MIN_DP -> COMPACT
            widthDp < EXPANDED_MIN_DP -> MEDIUM
            else -> EXPANDED
        }

        fun of(width: Dp): WidthClass = of(width.value)
    }
}

/** Боковая рейка вместо нижней панели — с 600 dp. */
val WidthClass.useRail: Boolean get() = this != WidthClass.COMPACT

/** Внешний отступ экрана: 16 dp, на широком — 24 dp. */
val WidthClass.outerPadding: Dp get() = if (this == WidthClass.EXPANDED) 24.dp else 16.dp

/** Промежуток между колонками — такой же, как внешний отступ. */
val WidthClass.gutter: Dp get() = outerPadding

/** Сколько столбцов в сетке карточек упражнений. */
val WidthClass.gridColumns: Int get() = if (this == WidthClass.COMPACT) 1 else 2

/** Наибольшая ширина одной колонки содержимого (MEDIUM, «Настройки», «Прогресс» на узком). */
val ContentMaxWidth: Dp = 600.dp

/** Ширина боковой рейки навигации. */
val RailWidth: Dp = 80.dp

/** Ширина левой колонки «Прогресса» на широком экране (уровень и календарь). */
val ProgressSideWidth: Dp = 320.dp

/** Текущий класс ширины окна; задаётся в [ProvideWidthClass] в корне MainActivity. */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/** Класс ширины окна для экранов внутри [ProvideWidthClass]. */
@Composable
fun currentWidthClass(): WidthClass = LocalWidthClass.current

/**
 * Меряет доступную ширину через [BoxWithConstraints], кладёт класс в [LocalWidthClass]
 * и передаёт его содержимому. Ставить в корень окна — до навигации и отступов под неё.
 */
@Composable
fun ProvideWidthClass(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(WidthClass) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val widthClass = WidthClass.of(maxWidth)
        CompositionLocalProvider(LocalWidthClass provides widthClass) {
            content(widthClass)
        }
    }
}
