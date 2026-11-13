package com.nextlevellife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutLayoutTest {

    private fun size(
        wc: WidthClass,
        w: Float,
        h: Float,
        count: Int = 7,
        target: Int? = 20,
        fontScale: Float = 1f,
    ) = Scoreboard.counterSize(wc, w, h, count, target, fontScale)

    // ---------- Счётчик повторений ----------

    @Test
    fun fullSizePerWidthClassWhenThereIsRoom() {
        assertEquals(CounterSize(112, 120, TargetPlacement.BELOW), size(WidthClass.COMPACT, 300f, 400f))
        assertEquals(CounterSize(128, 136, TargetPlacement.BELOW), size(WidthClass.MEDIUM, 400f, 400f))
        assertEquals(CounterSize(160, 168, TargetPlacement.BELOW), size(WidthClass.EXPANDED, 500f, 600f))
    }

    @Test
    fun lineHeightIsSizePlusEight() {
        assertEquals(120, Scoreboard.lineFor(112))
        assertEquals(136, Scoreboard.lineFor(128))
        assertEquals(168, Scoreboard.lineFor(160))
        assertEquals(104, Scoreboard.lineFor(96))
    }

    @Test
    fun shrinksByHeightDownTo96WithTargetBelow() {
        // 112 с целью снизу: 120 + 32 + 8 = 160 dp.
        // На 150 dp: 104 → 112 + 32 + 8 = 152 > 150; 96 → 104 + 32 + 8 = 144 ≤ 150.
        assertEquals(CounterSize(96, 104, TargetPlacement.BELOW), size(WidthClass.COMPACT, 300f, 150f))
        // 104: 152 ≤ 155.
        assertEquals(CounterSize(104, 112, TargetPlacement.BELOW), size(WidthClass.COMPACT, 300f, 155f))
        // Ровно впритык — ещё помещается.
        assertEquals(CounterSize(112, 120, TargetPlacement.BELOW), size(WidthClass.COMPACT, 300f, 160f))
        // EXPANDED 160 → 168 + 40 = 208; на 200 dp — 152 (160 + 40 = 200).
        assertEquals(CounterSize(152, 160, TargetPlacement.BELOW), size(WidthClass.EXPANDED, 500f, 200f))
    }

    @Test
    fun targetMovesBesideWhen96DoesNotFitBelow() {
        // 96 снизу требует 144 dp; на 130 — цель справа, число снова как можно крупнее: 112 → 120 + 8 = 128 ≤ 130.
        assertEquals(CounterSize(112, 120, TargetPlacement.BESIDE), size(WidthClass.COMPACT, 300f, 130f))
        // На 115 — 104 (112 + 8 = 120 > 115) → 96 (104 + 8 = 112 ≤ 115).
        assertEquals(CounterSize(96, 104, TargetPlacement.BESIDE), size(WidthClass.COMPACT, 300f, 115f))
    }

    @Test
    fun neverBelow96EvenWhenNothingFits() {
        val tiny = size(WidthClass.COMPACT, 300f, 60f)
        assertEquals(96, tiny.sizeSp)
        assertEquals(TargetPlacement.BESIDE, tiny.placement)
        // Без цели — всегда снизу (цели нет), но не меньше 96.
        val noTarget = size(WidthClass.EXPANDED, 300f, 60f, target = null)
        assertEquals(CounterSize(96, 104, TargetPlacement.BELOW), noTarget)
        // Слишком узко и для цели сбоку — цель снизу.
        val narrow = size(WidthClass.COMPACT, 150f, 60f)
        assertEquals(CounterSize(96, 104, TargetPlacement.BELOW), narrow)
    }

    @Test
    fun withoutTargetNeedsOnlyNumberLine() {
        // 112 без цели: 120 + 8 = 128.
        assertEquals(CounterSize(112, 120, TargetPlacement.BELOW), size(WidthClass.COMPACT, 300f, 128f, target = null))
    }

    @Test
    fun shrinksByWidthForThreeDigits() {
        // Панель 300 dp на широком: две цифры 160 sp = 192 + 40 ≤ 300; три цифры — 288 + 40 > 300.
        assertEquals(160, size(WidthClass.EXPANDED, 300f, 600f, count = 42, target = null).sizeSp)
        // 3 × 0.6 × 144 = 259.2 + 40 = 299.2 ≤ 300; 152 → 273.6 + 40 > 300.
        assertEquals(144, size(WidthClass.EXPANDED, 300f, 600f, count = 120, target = null).sizeSp)
        // Цель трёхзначная — размер считается по ней с самого начала подхода.
        assertEquals(144, size(WidthClass.EXPANDED, 300f, 600f, count = 0, target = 100).sizeSp)
    }

    @Test
    fun countChangeFrom9To10KeepsSize() {
        val nine = size(WidthClass.COMPACT, 200f, 400f, count = 9, target = null)
        val ten = size(WidthClass.COMPACT, 200f, 400f, count = 10, target = null)
        assertEquals(nine, ten)
    }

    @Test
    fun fontScaleIsAccountedFor() {
        // При шрифте ×1.3 112 sp с целью требует (120 + 32) × 1.3 + 8 = 205.6 dp.
        assertEquals(112, size(WidthClass.COMPACT, 400f, 210f, fontScale = 1.3f).sizeSp)
        assertEquals(104, size(WidthClass.COMPACT, 400f, 200f, fontScale = 1.3f).sizeSp)
    }

    @Test
    fun phoneWidthFitsTwoDigitsAtFullSize() {
        // S25: 360 dp − отступы 32 − поля панели 24 − полоса глубины 36 ≈ 268 dp.
        assertEquals(112, size(WidthClass.COMPACT, 268f, 400f, count = 17, target = 20).sizeSp)
        assertEquals(112, size(WidthClass.COMPACT, 268f, 400f, count = 100, target = null).sizeSp)
    }

    // ---------- Таймеры ----------

    @Test
    fun complexTimerSizes() {
        assertEquals(72, Scoreboard.complexTimerSp(WidthClass.COMPACT, 328f, 700f, 5))
        assertEquals(96, Scoreboard.complexTimerSp(WidthClass.EXPANDED, 300f, 750f, 5))
        // Панель 220 dp: 5 × 0.6 × 72 = 216 — помещается.
        assertEquals(72, Scoreboard.complexTimerSp(WidthClass.MEDIUM, 220f, 700f, 5))
        // «100:00» в панели 220 dp — меньше: 6 × 0.6 × 56 = 201.6.
        assertEquals(56, Scoreboard.complexTimerSp(WidthClass.MEDIUM, 220f, 700f, 6))
        // Мало высоты — не больше 56.
        assertEquals(56, Scoreboard.complexTimerSp(WidthClass.COMPACT, 328f, 500f, 5))
        assertEquals(56, Scoreboard.complexTimerSp(WidthClass.EXPANDED, 300f, 500f, 5))
        // Короткий текст считается как пять знаков.
        assertEquals(72, Scoreboard.complexTimerSp(WidthClass.MEDIUM, 220f, 700f, 4))
    }

    @Test
    fun fitSpRespectsMinimum() {
        assertEquals(40, Scoreboard.fitSp(72, 40, 50f, 5))
        assertEquals(72, Scoreboard.fitSp(72, 40, 1000f, 5))
        // Нижняя граница входит в ряд, даже если не кратна шагу.
        assertEquals(42, Scoreboard.fitSp(74, 42, 5 * 0.6f * 42, 5))
    }

    @Test
    fun exerciseTimerSizes() {
        assertEquals(40, Scoreboard.exerciseTimerSp(WidthClass.COMPACT))
        assertEquals(40, Scoreboard.exerciseTimerSp(WidthClass.MEDIUM))
        assertEquals(56, Scoreboard.exerciseTimerSp(WidthClass.EXPANDED))
    }

    // ---------- Раскладка ----------

    @Test
    fun panelWidths() {
        assertNull(WidthClass.COMPACT.workoutPanelWidth)
        assertEquals(220f, WidthClass.MEDIUM.workoutPanelWidth!!.value)
        assertEquals(300f, WidthClass.EXPANDED.workoutPanelWidth!!.value)
        assertTrue(MainButtonHeight.value >= 56f)
    }
}
