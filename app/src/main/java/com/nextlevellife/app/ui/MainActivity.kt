package com.nextlevellife.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Store

/**
 * Простая навигация без библиотек: текущий экран хранится строкой.
 * Верхний уровень (HOME) — две вкладки с нижней панелью; упражнение, комплекс и список
 * приложений открываются поверх, без панели, и возвращают на ту вкладку, с которой ушли.
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
                    // С «Прогресса» «Назад» ведёт на «Тренировку»; с «Тренировки» — выход, как раньше.
                    BackHandler(enabled = screen == HOME && tab == TAB_PROGRESS) { tab = TAB_TRAINING }

                    val showBar = screen == HOME
                    // Нижний край отдаём панели: она сама отступает от жестовой полосы.
                    val contentInsets =
                        if (showBar) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        else WindowInsets.safeDrawing
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .windowInsetsPadding(contentInsets)
                        ) {
                            when {
                                screen == HOME && tab == TAB_PROGRESS ->
                                    tabStates.SaveableStateProvider(TAB_PROGRESS) { ProgressScreen() }
                                screen == HOME -> tabStates.SaveableStateProvider(TAB_TRAINING) {
                                    HomeScreen(
                                        resumeCount = resumeCount,
                                        onExercise = { screen = "$EXERCISE$it" },
                                        onComplex = { screen = "$COMPLEX$it" },
                                        onApps = { screen = APPS },
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
                        if (showBar) BottomTabs(selected = tab, onSelect = { tab = it })
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

@Composable
private fun BottomTabs(selected: String, onSelect: (String) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color(0xFF1A1200),
        selectedTextColor = Accent,
        indicatorColor = Accent,
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    ) {
        NavigationBarItem(
            selected = selected == TAB_TRAINING,
            onClick = { onSelect(TAB_TRAINING) },
            icon = { DumbbellIcon() },
            label = { Text("Тренировка") },
            colors = colors,
        )
        NavigationBarItem(
            selected = selected == TAB_PROGRESS,
            onClick = { onSelect(TAB_PROGRESS) },
            icon = { BarsIcon() },
            label = { Text("Прогресс") },
            colors = colors,
        )
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
