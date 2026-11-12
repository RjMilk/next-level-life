package com.nextlevellife.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.R

val Accent = Color(0xFFF5A524)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFF87171)

/** Тёмный текст на акцентной заливке (как onPrimary в теме). */
val OnAccent = Color(0xFF1A1200)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF232731),
    onSurfaceVariant = Color(0xFFA3A9B6),
    error = Bad,
)

// ---------- Шрифты ----------

/** Основной шрифт интерфейса: Inter 400 / 600 / 700 (лицензия OFL, файлы в res/font). */
val InterFont: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** «Табло»: JetBrains Mono 700 — крупные числа (баланс, таймер, счёт повторений). */
val ScoreFont: FontFamily = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

/**
 * Стиль «табло»: JetBrains Mono 700, табличные цифры, трекинг 0.
 * Например, баланс на главной — `scoreStyle(56, 64)`, на широком экране — `scoreStyle(64, 72)`.
 */
fun scoreStyle(sizeSp: Int, lineSp: Int): TextStyle = TextStyle(
    fontFamily = ScoreFont,
    fontWeight = FontWeight.Bold,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum",
)

/** Единицы рядом с цифрами табло («ч», «мин»): Inter 16/24. */
val ScoreUnitStyle: TextStyle = TextStyle(
    fontFamily = InterFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
)

/** Табличные цифры для любого стиля шкалы: `style.merge(Tabular)` — числа в столбик не прыгают. */
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

private fun inter(weight: Int, size: Int, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = InterFont,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** Шкала типографики на Inter: вес; размер / межстрочный (sp); трекинг (sp). */
val AppTypography = Typography(
    displayLarge = inter(700, 56, 64, -0.5f),
    displayMedium = inter(700, 44, 52, -0.5f),
    displaySmall = inter(700, 36, 44),
    headlineLarge = inter(700, 32, 40),
    headlineMedium = inter(700, 28, 36),
    headlineSmall = inter(600, 24, 32),
    titleLarge = inter(600, 22, 28),
    titleMedium = inter(600, 18, 24),
    titleSmall = inter(600, 16, 22),
    bodyLarge = inter(400, 18, 26),
    bodyMedium = inter(400, 16, 24),
    bodySmall = inter(400, 14, 20),
    labelLarge = inter(600, 14, 20, 0.2f),
    labelMedium = inter(600, 12, 16, 0.2f),
    labelSmall = inter(600, 11, 16, 0.2f),
)

// ---------- Формы ----------

/** Панели и карточки — радиус 12 dp. */
val CardShape = RoundedCornerShape(12.dp)

/** Кнопки — радиус 16 dp (передавать в `shape =` у Button / OutlinedButton). */
val ButtonShape = RoundedCornerShape(16.dp)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = CardShape,
    large = RoundedCornerShape(16.dp),
)

@Composable
fun NextLevelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
