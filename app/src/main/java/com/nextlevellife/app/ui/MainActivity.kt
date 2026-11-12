package com.nextlevellife.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Store

/**
 * Простая навигация без библиотек: текущий экран хранится строкой.
 * Верхний уровень (HOME) — три вкладки: на узком окне нижняя панель, с 600 dp — боковая рейка.
 * Упражнение, комплекс и список приложений открываются поверх, без навигации, и возвращают
 * на ту вкладку, с которой ушли.
 */
class MainActivity : ComponentActivity() {

    /** Увеличивается в onResume, чтобы главный экран перепроверил разрешения. */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent {
            NextLevelTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by rememberSaveable { mutableStateOf(HOME) }
                    var tab by rememberSaveable { mutableStateOf(TAB_TRAINING) }
                    // Состояние вкладок (прокрутка, выбранный день) сохраняется, пока вкладка скрыта.
                    val tabStates = rememberSaveableStateHolder()
                    val goHome = { screen = HOME }
                    if (screen != HOME) BackHandler(onBack = goHome)
                    // С «Прогресса» и «Настроек» «Назад» ведёт на «Тренировку»; с «Тренировки» — выход.
                    BackHandler(enabled = screen == HOME && tab != TAB_TRAINING) { tab = TAB_TRAINING }

                    // Ширина меряется по всему окну, до вычета навигации.
                    ProvideWidthClass(Modifier.fillMaxSize()) { widthClass ->
                        val showNav = screen == HOME
                        val rail = showNav && widthClass.useRail
                        val bottomBar = showNav && !rail
                        val contentInsets = when {
                            // Нижний край отдаём панели: она сама отступает от жестовой полосы.
                            bottomBar -> WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                            // Начальный край отдаём рейке.
                            rail -> WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                            else -> WindowInsets.safeDrawing
                        }
                        // Рейка и панель — условные соседи; сам контейнер содержимого всегда на одном месте
                        // дерева, так что при смене ширины (раскрыли Fold) экраны не пересоздаются.
                        Row(Modifier.fillMaxSize()) {
                            if (rail) SideRail(selected = tab, onSelect = { tab = it })
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .windowInsetsPadding(contentInsets)
                                ) {
                                    when {
                                        screen == HOME && tab == TAB_PROGRESS ->
                                            tabStates.SaveableStateProvider(TAB_PROGRESS) { ProgressScreen() }
                                        screen == HOME && tab == TAB_SETTINGS ->
                                            tabStates.SaveableStateProvider(TAB_SETTINGS) {
                                                SettingsScreen(onApps = { screen = APPS })
                                            }
                                        screen == HOME -> tabStates.SaveableStateProvider(TAB_TRAINING) {
                                            HomeScreen(
                                                resumeCount = resumeCount,
                                                onExercise = { screen = "$EXERCISE$it" },
                                                onComplex = { screen = "$COMPLEX$it" },
                                                onProgress = { tab = TAB_PROGRESS },
                                            )
                                        }
                                        screen == APPS -> AppsScreen(onBack = goHome)
                                        screen.startsWith(COMPLEX) -> ComplexScreen(
                                            complexId = screen.removePrefix(COMPLEX),
                                            onDone = goHome,
                                        )
                                        screen.startsWith(EXERCISE) -> ExerciseScreen(
                                            exerciseId = screen.removePrefix(EXERCISE),
                                            onDone = goHome,
                                        )
                                    }
                                }
                                if (bottomBar) BottomTabs(selected = tab, onSelect = { tab = it })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    private companion object {
        const val HOME = "home"
        const val APPS = "apps"
        const val COMPLEX = "complex:"
        const val EXERCISE = "exercise:"
    }
}

private const val TAB_TRAINING = "training"
private const val TAB_PROGRESS = "progress"
private const val TAB_SETTINGS = "settings"

/** Вкладка верхнего уровня: ключ, подпись, значок. */
private class TabSpec(val key: String, val label: String, val icon: @Composable () -> Unit)

private val tabs = listOf(
    TabSpec(TAB_TRAINING, "Тренировка") { DumbbellIcon() },
    TabSpec(TAB_PROGRESS, "Прогресс") { BarsIcon() },
    TabSpec(TAB_SETTINGS, "Настройки") { GearIcon() },
)

@Composable
private fun BottomTabs(selected: String, onSelect: (String) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = OnAccent,
        selectedTextColor = Accent,
        indicatorColor = Accent,
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    ) {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = selected == t.key,
                onClick = { onSelect(t.key) },
                icon = t.icon,
                label = { Text(t.label, maxLines = 1) },
                colors = colors,
            )
        }
    }
}

/** Боковая рейка для окна от 600 dp: вкладки по центру высоты, чтобы до них дотягивался большой палец. */
@Composable
private fun SideRail(selected: String, onSelect: (String) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = OnAccent,
        selectedTextColor = Accent,
        indicatorColor = Accent,
    )
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .width(RailWidth),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
    ) {
        Spacer(Modifier.weight(1f))
        tabs.forEach { t ->
            NavigationRailItem(
                selected = selected == t.key,
                onClick = { onSelect(t.key) },
                icon = t.icon,
                label = { Text(t.label, maxLines = 1) },
                colors = colors,
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Гантель: иконок в подключённых библиотеках нет, рисуем сами. */
@Composable
private fun DumbbellIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val r = CornerRadius(1.5f * u)
        drawRect(color, Offset(6f * u, 11f * u), Size(12f * u, 2f * u))
        drawRoundRect(color, Offset(3f * u, 6f * u), Size(4f * u, 12f * u), r)
        drawRoundRect(color, Offset(17f * u, 6f * u), Size(4f * u, 12f * u), r)
        drawRoundRect(color, Offset(1f * u, 9f * u), Size(2f * u, 6f * u), r)
        drawRoundRect(color, Offset(21f * u, 9f * u), Size(2f * u, 6f * u), r)
    }
}

/** Три столбика — значок прогресса. */
@Composable
private fun BarsIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val r = CornerRadius(1f * u)
        drawRoundRect(color, Offset(3f * u, 13f * u), Size(4.5f * u, 8f * u), r)
        drawRoundRect(color, Offset(9.75f * u, 8f * u), Size(4.5f * u, 13f * u), r)
        drawRoundRect(color, Offset(16.5f * u, 3f * u), Size(4.5f * u, 18f * u), r)
    }
}

/** Шестерёнка — значок настроек: кольцо и восемь зубцов. */
@Composable
private fun GearIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        // Кольцо: от радиуса 3,5 до 7,5 — в центре остаётся отверстие.
        drawCircle(color, radius = 5.5f * u, center = center, style = Stroke(width = 4f * u))
        repeat(8) { i ->
            rotate(degrees = i * 45f, pivot = center) {
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - 1.75f * u, center.y - 10.5f * u),
                    size = Size(3.5f * u, 4f * u),
                    cornerRadius = CornerRadius(0.8f * u),
                )
            }
        }
    }
}
