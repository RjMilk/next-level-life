package com.nextlevellife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletopMathTest {
    private fun fold(horizontal: Boolean = true, half: Boolean = true, top: Int = 980, bottom: Int = 980) =
        FoldInfo(horizontal, half, top, bottom)

    @Test
    fun `обычный телефон без сгибов — не столик`() {
        assertNull(TabletopMath.detect(emptyList()))
    }

    @Test
    fun `горизонтальный сгиб наполовину — столик с границами сгиба`() {
        assertEquals(Tabletop(980, 1000), TabletopMath.detect(listOf(fold(top = 980, bottom = 1000))))
    }

    @Test
    fun `раскрытый Fold (FLAT) и вертикальный сгиб («книжка») — не столик`() {
        assertNull(TabletopMath.detect(listOf(fold(half = false))))
        assertNull(TabletopMath.detect(listOf(fold(horizontal = false))))
    }

    @Test
    fun `берётся первый подходящий сгиб, перевёрнутые границы пропускаются`() {
        val bad = fold(top = 500, bottom = 400)
        val good = fold(top = 900, bottom = 900)
        assertEquals(Tabletop(900, 900), TabletopMath.detect(listOf(fold(horizontal = false), bad, good)))
    }

    @Test
    fun `деление по линии сгиба с учётом положения области в окне`() {
        // Область начинается под строкой состояния (100 px), высота 1868, сгиб на 984 px окна.
        val p = TabletopMath.panes(containerTopPx = 100, containerHeightPx = 1868, fold = Tabletop(984, 984), minPanePx = 400)
        assertEquals(TabletopPanes(884, 0, 984), p)
    }

    @Test
    fun `шарнир-полоса не занимается содержимым`() {
        val p = TabletopMath.panes(0, 2000, Tabletop(990, 1010), minPanePx = 400)
        assertEquals(TabletopPanes(990, 20, 990), p)
        assertEquals(2000, p.topPx + p.gapPx + p.bottomPx)
    }

    @Test
    fun `сгиб вне области — пополам`() {
        assertEquals(TabletopPanes(500, 0, 500), TabletopMath.panes(1200, 1000, Tabletop(900, 900), minPanePx = 100))
        assertEquals(TabletopPanes(500, 0, 500), TabletopMath.panes(0, 1000, Tabletop(1000, 1000), minPanePx = 100))
    }

    @Test
    fun `слишком низкая половина поднимается до минимума`() {
        val p = TabletopMath.panes(0, 1000, Tabletop(150, 150), minPanePx = 300)
        assertEquals(TabletopPanes(300, 0, 700), p)
        val q = TabletopMath.panes(0, 1000, Tabletop(900, 900), minPanePx = 300)
        assertEquals(TabletopPanes(700, 0, 300), q)
    }

    @Test
    fun `минимум больше половины области — пополам, без отрицательных высот`() {
        val p = TabletopMath.panes(0, 500, Tabletop(100, 100), minPanePx = 400)
        assertEquals(TabletopPanes(250, 0, 250), p)
        assertEquals(TabletopPanes(0, 0, 0), TabletopMath.panes(0, 0, Tabletop(100, 100), minPanePx = 400))
    }

    @Test
    fun `нижняя половина Fold — три колонки, раскладушки — две`() {
        assertTrue(TabletopMath.wideBoard(760f))
        assertTrue(TabletopMath.wideBoard(TabletopMath.WIDE_BOARD_DP))
        assertFalse(TabletopMath.wideBoard(380f))
    }
}
