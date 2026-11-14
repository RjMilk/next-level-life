package com.nextlevellife.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import com.nextlevellife.app.health.ActivitySync
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.CustomProgramMath
import com.nextlevellife.app.data.Programs
import com.nextlevellife.app.data.Routines
import com.nextlevellife.app.data.Saitama
import com.nextlevellife.app.data.SaitamaRepository
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Простая навигация без библиотек: текущий экран хранится строкой.
 * Верхний уровень (HOME) — три вкладки: на узком окне нижняя панель, с 600 dp — боковая рейка.
 * Упражнение, комплекс и список приложений открываются поверх, без навигации, и возвращают
 * на ту вкладку, с которой ушли.
 */
class MainActivity : ComponentActivity() {

    /** Увеличивается в onResume, чтобы главный экран перепроверил разрешения. */
    private var resumeCount by mutableIntStateOf(0)

    /** Открыто ли пояснение о разрешениях Health Connect (его просит сам Health Connect). */
    private var showRationale by mutableStateOf(false)

    /** Activity запущена только ради пояснения: «Понятно» закрывает её и возвращает в Health Connect. */
    private var rationaleOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        // Служба блокировки может быть выключена — транспорт и обмен поднимаем и отсюда (повтор безопасен).
        com.nextlevellife.app.sync.SyncBoot.ensure(this)
        // Новая версия на GitHub: проверка не чаще раза в сутки (показывается в «Настройках» → «Обновление»).
        // Чтение кеша и разбор списка релизов — не на главном потоке.
        val appCtx = applicationContext
        kotlin.concurrent.thread(name = "nll-update-check") { runCatching { com.nextlevellife.app.update.UpdateManager.autoCheckIfDue(appCtx) } }
        enableEdgeToEdge()
        if (savedInstanceState == null && isRationaleIntent(intent)) {
            showRationale = true
            rationaleOnly = true
        }
        setContent {
            NextLevelTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by rememberSaveable { mutableStateOf(HOME) }
                    var tab by rememberSaveable { mutableStateOf(TAB_TRAINING) }
                    // Пояснение — поверх всего; экран и вкладка под ним сохраняются.
                    if (showRationale) {
                        BackHandler(onBack = ::closeRationale)
                        HealthRationaleScreen(onClose = ::closeRationale)
                        return@Surface
                    }
                    // Возврат в приложение и смена вкладки: шаги и пробежки из Health Connect
                    // (проверка доступа; само чтение — не чаще раза в 5 минут).
                    val onTabs = screen == HOME
                    LaunchedEffect(resumeCount, onTabs, tab) {
                        if (onTabs && resumeCount > 0) ActivitySync.request(this@MainActivity)
                    }
                    // «Сайтама»: доплатить +10 минут за полные дни — при открытии приложения, после записи подхода
                    // и после синхронизации активности (всё это меняет Store.version). Повтор ничего не платит;
                    // пауза гасит серию изменений подряд.
                    val version by Store.version.collectAsState()
                    LaunchedEffect(version, resumeCount) {
                        delay(SAITAMA_CHECK_DELAY_MS)
                        withContext(Dispatchers.IO) { SaitamaRepository.ensureBonuses() }
                    }
                    // Состояние вкладок (прокрутка, выбранный день) сохраняется, пока вкладка скрыта.
                    val tabStates = rememberSaveableStateHolder()
                    val goHome = { screen = HOME }
                    // Упражнение, открытое с «Сайтамы», возвращает на неё; остальное — на главную.
                    if (screen != HOME) BackHandler { screen = parentOf(screen) }
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
                                            tabStates.SaveableStateProvider(TAB_PROGRESS) {
                                                ProgressScreen(onStatInfo = { screen = "$STAT_INFO$it" })
                                            }
                                        screen == HOME && tab == TAB_SETTINGS ->
                                            tabStates.SaveableStateProvider(TAB_SETTINGS) {
                                                SettingsScreen(onApps = { screen = APPS }, onPairing = { screen = PAIRING })
                                            }
                                        screen == HOME -> tabStates.SaveableStateProvider(TAB_TRAINING) {
                                            HomeScreen(
                                                resumeCount = resumeCount,
                                                onExercise = { screen = "$EXERCISE$it" },
                                                onComplex = { screen = "$COMPLEX$it" },
                                                onProgress = { tab = TAB_PROGRESS },
                                                onSettings = { tab = TAB_SETTINGS },
                                                onStatInfo = { screen = "$STAT_INFO$it" },
                                                onNutrition = { screen = NUTRITION },
                                                onCards = { screen = CARDS },
                                                onEditProgram = { screen = EDIT_PROGRAM + (it ?: "") },
                                            )
                                        }
                                        screen.startsWith(EDIT_PROGRAM) -> {
                                            val id = screen.removePrefix(EDIT_PROGRAM).removeSuffix(FROM_PREP).ifEmpty { null }
                                            ProgramEditorScreen(
                                                programId = id,
                                                onClose = { screen = parentOf(screen) },
                                                // Из подготовки к старту — обратно в неё (уже с новым составом).
                                                onSaved = { saved -> screen = if (screen.endsWith(FROM_PREP)) "$COMPLEX$saved" else HOME },
                                                onDeleted = goHome,
                                            )
                                        }
                                        screen == NUTRITION -> NutritionScreen(onBack = goHome)
                                        screen == CARDS -> CardsScreen(onBack = goHome)
                                        screen == APPS -> AppsScreen(onBack = goHome)
                                        screen == PAIRING -> PairingScreen(onBack = goHome)
                                        screen.startsWith(STAT_INFO) -> StatInfoScreen(
                                            topic = screen.removePrefix(STAT_INFO),
                                            resumeCount = resumeCount,
                                            onBack = goHome,
                                        )
                                        screen == SAITAMA -> SaitamaScreen(
                                            resumeCount = resumeCount,
                                            onExercise = { screen = "$EXERCISE$it$FROM_SAITAMA" },
                                            onBack = goHome,
                                        )
                                        screen.startsWith(COMPLEX) && Programs.isProgramId(screen.removePrefix(COMPLEX)) -> {
                                            val id = screen.removePrefix(COMPLEX)
                                            ProgramScreen(
                                                programId = id,
                                                onDone = goHome,
                                                onEdit = if (CustomProgramMath.isCustomId(id)) {
                                                    { screen = "$EDIT_PROGRAM$id$FROM_PREP" }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                        screen.startsWith(COMPLEX) && Routines.isRoutineId(screen.removePrefix(COMPLEX)) -> {
                                            var guideFor by rememberSaveable { mutableStateOf<String?>(null) }
                                            RoutineScreen(
                                                routineId = screen.removePrefix(COMPLEX),
                                                onDone = goHome,
                                                onShowGuide = { guideFor = it },
                                            )
                                            guideFor?.let { id ->
                                                GuideDialog(exerciseId = id, onClose = { guideFor = null })
                                            }
                                        }
                                        screen.startsWith(COMPLEX) -> ComplexScreen(
                                            complexId = screen.removePrefix(COMPLEX),
                                            onDone = goHome,
                                        )
                                        screen.startsWith(EXERCISE) -> ExerciseScreen(
                                            exerciseId = screen.removePrefix(EXERCISE).removeSuffix(FROM_SAITAMA),
                                            onDone = { screen = parentOf(screen) },
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
        // Открыли приложение — второй телефон, возможно, рядом: обмен (без транспорта и пары — ничего).
        SyncEngine.requestSync("приложение")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Приложение уже открыто: пояснение показываем поверх, «Понятно» возвращает к экранам.
        if (isRationaleIntent(intent)) showRationale = true
    }

    private fun closeRationale() {
        showRationale = false
        if (rationaleOnly) {
            rationaleOnly = false
            finish()
        }
    }

    private fun isRationaleIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_SHOW_RATIONALE || intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE

    /**
     * Куда возвращаться с экрана [screen]: упражнение с «Сайтамы» — на неё, редактор своей программы,
     * открытый из подготовки к старту, — в неё; остальное — на главную.
     */
    private fun parentOf(screen: String): String = when {
        screen.startsWith(EXERCISE) && screen.endsWith(FROM_SAITAMA) -> SAITAMA
        screen.startsWith(EDIT_PROGRAM) && screen.endsWith(FROM_PREP) ->
            COMPLEX + screen.removePrefix(EDIT_PROGRAM).removeSuffix(FROM_PREP)
        else -> HOME
    }

    private companion object {
        const val HOME = "home"
        const val APPS = "apps"
        const val NUTRITION = "nutrition"
        const val CARDS = "cards"
        const val COMPLEX = "complex:"
        const val EXERCISE = "exercise:"
        const val STAT_INFO = "stat:"
        /** Сопряжение со вторым телефоном (из «Настроек»). */
        const val PAIRING = "pairing"

        /** Экран «Сайтамы»: главная открывает его как комплекс — onComplex("saitama"). */
        const val SAITAMA = COMPLEX + Saitama.ID

        /** Суффикс упражнения, открытого с «Сайтамы»: «exercise:pushups@saitama» — после подхода назад на неё. */
        const val FROM_SAITAMA = "@" + Saitama.ID

        /** Редактор своей программы: «program_edit:» — новая, «program_edit:<id>» — правка. */
        const val EDIT_PROGRAM = "program_edit:"

        /** Суффикс редактора, открытого из подготовки к старту программы: после него — обратно в неё. */
        const val FROM_PREP = "@prep"

        /** Пауза перед доплатой бонуса «Сайтамы» после изменения данных. */
        const val SAITAMA_CHECK_DELAY_MS = 1_500L

        /** Health Connect (Android 13 и старше) просит показать, зачем приложению разрешения. */
        const val ACTION_SHOW_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}

/** Пояснение о разрешениях Health Connect: что читается, зачем и куда (никуда). */
@Composable
private fun HealthRationaleScreen(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ContentMaxWidth)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Шаги и пробежки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Next Level Life читает из Health Connect только три вида данных:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(Modifier.fillMaxWidth(), shape = CardShape) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• Шаги — сколько прошёл за день", style = MaterialTheme.typography.bodyMedium)
                Text("• Дистанция — сколько пробежал", style = MaterialTheme.typography.bodyMedium)
                Text("• Тренировки — чтобы найти пробежки и не оплачивать их шаги второй раз", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Зачем: показать шаги и пробежки на экранах «Тренировка» и «Прогресс» и, если включено " +
                "в настройках, начислить за них экранное время.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Данные остаются на этом телефоне и никуда не отправляются. Приложение ничего не пишет " +
                "в Health Connect и не читает его в фоне. Доступ можно отозвать в Health Connect в любой момент.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onClose,
            shape = ButtonShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text("Понятно") }
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
