package com.nextlevellife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Размеры «табло» на экранах упражнения и комплекса (чистая логика, покрыта тестами)
// ---------------------------------------------------------------------------------------------

/** Где стоит цель («из 20») относительно числа повторений. */
internal enum class TargetPlacement {
    /** Отдельной строкой под числом — обычный вид. */
    BELOW,

    /** Столбиком справа от числа — когда даже 96 sp с целью снизу не влезают по высоте. */
    BESIDE,
}

/** Размер счётчика повторений: шрифт табло и межстрочный в sp, место цели. */
internal data class CounterSize(val sizeSp: Int, val lineSp: Int, val placement: TargetPlacement)

internal object Scoreboard {
    /** Меньше этого счётчик не уменьшается: дальше меняется расположение цели. */
    const val MIN_COUNT_SP = 96

    /** Шаг уменьшения шрифтов табло. */
    const val STEP_SP = 8

    /** Ширина цифры JetBrains Mono — 600/1000 em (моноширинный, все цифры одинаковые). */
    const val DIGIT_EM = 0.6f

    /** Строка цели — headlineSmall 24/32. */
    const val TARGET_LINE_SP = 32

    /** Цель столбиком справа («из» / «100») вместе с зазором, при масштабе шрифта 1. */
    const val TARGET_BESIDE_DP = 72f

    /** Поля плашки счётчика: по горизонтали и по вертикали с каждой стороны. */
    const val BLOCK_PAD_H_DP = 20f
    const val BLOCK_PAD_V_DP = 4f

    /** Нижняя граница таймера комплекса, если не влезает по ширине. */
    const val MIN_TIMER_SP = 40

    /** Ниже этой высоты области таймер комплекса меньше (разделённый экран, телефон лёжа). */
    const val SHORT_HEIGHT_DP = 560f

    /** Межстрочный табло: на 8 sp больше размера (112/120, 128/136, 160/168, 96/104). */
    fun lineFor(sizeSp: Int): Int = sizeSp + 8

    /** Наибольший счётчик повторений для класса ширины окна. */
    fun maxCountSp(widthClass: WidthClass): Int = when (widthClass) {
        WidthClass.COMPACT -> 112
        WidthClass.MEDIUM -> 128
        WidthClass.EXPANDED -> 160
    }

    /** Число знаков: не меньше двух, чтобы счёт 9 → 10 не менял размер табло. */
    fun digitsOf(n: Int): Int = maxOf(2, n.coerceAtLeast(0).toString().length)

    /** Размеры от [maxSp] вниз шагом [STEP_SP], последним — ровно [minSp]. */
    private fun ladder(maxSp: Int, minSp: Int): List<Int> {
        if (maxSp <= minSp) return listOf(minSp)
        val sizes = (maxSp downTo minSp + 1 step STEP_SP).toMutableList()
        sizes += minSp
        return sizes
    }

    /**
     * Размер счётчика повторений для области [widthDp] × [heightDp] (вся область, в которой плашка
     * стоит по центру). Порядок: самый крупный размер класса ширины с целью снизу; не влезает —
     * уменьшаем до [MIN_COUNT_SP]; не влезает и он — цель столбиком справа (снова от крупного).
     * Если не помещается ничего, остаётся [MIN_COUNT_SP]: число не обрезается и не переносится,
     * в худшем случае выходит за край области.
     */
    fun counterSize(
        widthClass: WidthClass,
        widthDp: Float,
        heightDp: Float,
        count: Int,
        target: Int?,
        fontScale: Float = 1f,
    ): CounterSize {
        val digits = maxOf(digitsOf(count), target?.let { digitsOf(it) } ?: 0)
        val sizes = ladder(maxCountSp(widthClass), MIN_COUNT_SP)

        fun numberWidth(sp: Int) = digits * DIGIT_EM * sp * fontScale
        fun fitsBelow(sp: Int): Boolean {
            val w = numberWidth(sp) + 2 * BLOCK_PAD_H_DP
            val targetLine = if (target != null) TARGET_LINE_SP else 0
            val h = (lineFor(sp) + targetLine) * fontScale + 2 * BLOCK_PAD_V_DP
            return w <= widthDp && h <= heightDp
        }
        fun besideWidth(sp: Int) = numberWidth(sp) + TARGET_BESIDE_DP * fontScale + 2 * BLOCK_PAD_H_DP
        fun fitsBeside(sp: Int): Boolean {
            val h = lineFor(sp) * fontScale + 2 * BLOCK_PAD_V_DP
            return besideWidth(sp) <= widthDp && h <= heightDp
        }

        sizes.firstOrNull { fitsBelow(it) }?.let { return CounterSize(it, lineFor(it), TargetPlacement.BELOW) }
        if (target != null) {
            sizes.firstOrNull { fitsBeside(it) }?.let { return CounterSize(it, lineFor(it), TargetPlacement.BESIDE) }
        }
        // Не влезает ничего. Мешает высота — цель сбоку (ниже столбик); мешает ширина — цель снизу.
        val placement = if (target != null && besideWidth(MIN_COUNT_SP) <= widthDp) {
            TargetPlacement.BESIDE
        } else {
            TargetPlacement.BELOW
        }
        return CounterSize(MIN_COUNT_SP, lineFor(MIN_COUNT_SP), placement)
    }

    /** Наибольший размер от [maxSp] до [minSp], при котором [chars] знаков табло влезают в [widthDp]. */
    fun fitSp(maxSp: Int, minSp: Int, widthDp: Float, chars: Int, fontScale: Float = 1f): Int =
        ladder(maxSp, minSp).firstOrNull { chars * DIGIT_EM * it * fontScale <= widthDp } ?: minSp

    /**
     * Таймер комплекса: 72 sp, на широком экране 96 sp; при высоте области меньше [SHORT_HEIGHT_DP] —
     * не больше 56 sp. Уменьшается, если [chars] знаков не влезают в [widthDp] (панель 220 dp).
     */
    fun complexTimerSp(widthClass: WidthClass, widthDp: Float, heightDp: Float, chars: Int, fontScale: Float = 1f): Int {
        var max = if (widthClass == WidthClass.EXPANDED) 96 else 72
        if (heightDp < SHORT_HEIGHT_DP) max = minOf(max, 56)
        return fitSp(max, MIN_TIMER_SP, widthDp, maxOf(chars, 5), fontScale)
    }

    /** Нижняя граница табло удержания: «12:30» в узкой панели не должно вылезать за край. */
    const val MIN_HOLD_SP = 56

    /** Подпись под табло удержания («засчитано 1:20») — titleLarge 22/28. */
    const val HOLD_CAPTION_LINE_SP = 28

    /**
     * Размер табло удержания «м:сс» для области [widthDp] × [heightDp]: самый крупный размер класса
     * ширины (как у счётчика повторений), не меньше [MIN_HOLD_SP]; под числом — строка подписи.
     * [chars] — длина строки времени (не меньше 4, чтобы 0:59 → 1:00 не меняло размер).
     */
    fun holdSp(widthClass: WidthClass, widthDp: Float, heightDp: Float, chars: Int, fontScale: Float = 1f): Int {
        val n = maxOf(4, chars)
        return ladder(maxCountSp(widthClass), MIN_HOLD_SP).firstOrNull { sp ->
            val w = n * DIGIT_EM * sp * fontScale + 2 * BLOCK_PAD_H_DP
            val h = (lineFor(sp) + HOLD_CAPTION_LINE_SP) * fontScale + 2 * BLOCK_PAD_V_DP
            w <= widthDp && h <= heightDp
        } ?: MIN_HOLD_SP
    }

    /** Таймер в шапке одиночного упражнения. */
    fun exerciseTimerSp(widthClass: WidthClass): Int = if (widthClass == WidthClass.EXPANDED) 56 else 40

    /** Ширина левой панели в широкой раскладке; null — вертикальная раскладка (узкий экран). */
    fun panelWidthDp(widthClass: WidthClass): Float? = when (widthClass) {
        WidthClass.COMPACT -> null
        WidthClass.MEDIUM -> 220f
        WidthClass.EXPANDED -> 300f
    }
}

/** Ширина левой панели экранов упражнения и комплекса; null — всё в одну колонку. */
internal val WidthClass.workoutPanelWidth: Dp?
    get() = Scoreboard.panelWidthDp(this)?.dp

/** Высота основных кнопок на экранах упражнения и комплекса. */
internal val MainButtonHeight: Dp = 56.dp

// ---------------------------------------------------------------------------------------------
// Общие элементы экранов упражнения и комплекса
// ---------------------------------------------------------------------------------------------

/** Затемнение под показаниями поверх камеры. */
internal val CameraScrim = Color.Black.copy(alpha = 0.55f)

/** Строка-инструкция поверх камеры — 20/28: её читают с двух метров. */
internal val InstructionStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp)

/** Число табло одной строкой: не переносится и не обрезается. */
@Composable
internal fun ScoreText(
    text: String,
    sizeSp: Int,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        style = scoreStyle(sizeSp, Scoreboard.lineFor(sizeSp)),
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

/**
 * Счётчик повторений с целью, сам подбирает размер под отведённую область ([modifier] задаёт её
 * размер). [onCamera] — поверх превью (белый текст на затемнении), иначе — на фоне панели.
 */
@Composable
internal fun FittedRepCounter(
    count: Int,
    target: Int?,
    counting: Boolean,
    onCamera: Boolean,
    modifier: Modifier = Modifier,
) {
    val widthClass = currentWidthClass()
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = Scoreboard.counterSize(
            widthClass = widthClass,
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            count = count,
            target = target,
            fontScale = fontScale,
        )
        RepCounterBlock(count, target, counting, size, onCamera)
    }
}

@Composable
private fun RepCounterBlock(count: Int, target: Int?, counting: Boolean, size: CounterSize, onCamera: Boolean) {
    val idle = if (onCamera) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    val numberColor = if (counting) Accent else idle
    val targetColor = if (onCamera) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    val targetStyle = MaterialTheme.typography.headlineSmall.merge(Tabular)
    val blockShape = RoundedCornerShape(24.dp)
    val base = if (onCamera) Modifier.background(CameraScrim, blockShape) else Modifier

    Box(
        base
            .padding(horizontal = Scoreboard.BLOCK_PAD_H_DP.dp, vertical = Scoreboard.BLOCK_PAD_V_DP.dp)
            .clearAndSetSemantics {
                contentDescription = if (target != null) "$count из $target" else "$count"
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            target == null -> ScoreText("$count", size.sizeSp, numberColor)
            size.placement == TargetPlacement.BELOW -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreText("$count", size.sizeSp, numberColor)
                Text("из $target", color = targetColor, style = targetStyle, maxLines = 1, softWrap = false)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreText("$count", size.sizeSp, numberColor)
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text("из", color = targetColor, style = targetStyle, maxLines = 1)
                    Text("$target", color = targetColor, style = targetStyle, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Как выглядит табло удержания. */
internal enum class HoldTone {
    /** Время идёт и засчитывается — акцентный цвет. */
    LIVE,

    /** Идёт отрезок короче 10 с: время видно, но приглушено. */
    PENDING,

    /** Удержания сейчас нет. */
    IDLE,
}

/**
 * Табло удержания: время «м:сс» шрифтом табло и подпись под ним («засчитано 1:20»,
 * «засчитается с 10 секунд»). Само подбирает размер под область [modifier], как [FittedRepCounter].
 * [captionWarn] — подпись-предупреждение («Меньше 10 секунд — не засчитано»).
 */
@Composable
internal fun FittedHoldTimer(
    time: String,
    caption: String,
    tone: HoldTone,
    captionWarn: Boolean,
    onCamera: Boolean,
    modifier: Modifier = Modifier,
) {
    val widthClass = currentWidthClass()
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val sp = Scoreboard.holdSp(widthClass, maxWidth.value, maxHeight.value, time.length, fontScale)
        val idle = if (onCamera) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
        val timeColor = when (tone) {
            HoldTone.LIVE -> Accent
            HoldTone.PENDING -> Accent.copy(alpha = 0.45f)
            HoldTone.IDLE -> idle
        }
        val captionColor = when {
            captionWarn -> Warn
            onCamera -> Color.White.copy(alpha = 0.85f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val base = if (onCamera) Modifier.background(CameraScrim, RoundedCornerShape(24.dp)) else Modifier
        Column(
            base
                .padding(horizontal = Scoreboard.BLOCK_PAD_H_DP.dp, vertical = Scoreboard.BLOCK_PAD_V_DP.dp)
                .clearAndSetSemantics { contentDescription = "Планка $time, $caption" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScoreText(time, sp, timeColor)
            Text(
                caption,
                color = captionColor,
                style = MaterialTheme.typography.titleLarge.merge(Tabular),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * Широкая раскладка: слева панель показаний и кнопок шириной [panelWidth], справа камера на всю
 * оставшуюся ширину и высоту. Превью само заполняет свою область (FILL_CENTER в PoseCameraView,
 * скелет рисуется тем же преобразованием), поэтому контейнер камеры пропорций не навязывает.
 */
@Composable
internal fun WorkoutSideLayout(
    widthClass: WidthClass,
    panelWidth: Dp,
    camera: @Composable (Modifier) -> Unit,
    panel: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(widthClass.outerPadding),
        horizontalArrangement = Arrangement.spacedBy(widthClass.gutter),
    ) {
        Column(
            Modifier
                .width(panelWidth)
                .fillMaxHeight(),
            content = panel,
        )
        camera(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}
