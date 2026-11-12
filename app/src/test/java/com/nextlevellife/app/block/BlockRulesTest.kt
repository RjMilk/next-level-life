package com.nextlevellife.app.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class BlockRulesTest {

    private val defaults = BlockSettings(dailyLimitMin = 120, nightEnabled = true, nightStartMin = 23 * 60, nightEndMin = 7 * 60)
    private fun at(h: Int, m: Int) = h * 60 + m
    private val min = 60_000L

    // ---------- Ночное окно ----------

    @Test
    fun nightAcrossMidnight() {
        assertTrue(BlockRules.isNight(at(23, 30), defaults))
        assertTrue(BlockRules.isNight(at(0, 0), defaults))
        assertTrue(BlockRules.isNight(at(3, 15), defaults))
        assertFalse(BlockRules.isNight(at(12, 0), defaults))
    }

    @Test
    fun nightStartInclusiveEndExclusive() {
        assertTrue(BlockRules.isNight(at(23, 0), defaults))
        assertFalse(BlockRules.isNight(at(22, 59), defaults))
        assertTrue(BlockRules.isNight(at(6, 59), defaults))
        assertFalse(BlockRules.isNight(at(7, 0), defaults))
    }

    @Test
    fun nightWithinOneDay() {
        val s = defaults.copy(nightStartMin = at(1, 0), nightEndMin = at(5, 0))
        assertFalse(BlockRules.isNight(at(0, 59), s))
        assertTrue(BlockRules.isNight(at(1, 0), s))
        assertTrue(BlockRules.isNight(at(4, 59), s))
        assertFalse(BlockRules.isNight(at(5, 0), s))
        assertFalse(BlockRules.isNight(at(23, 30), s))
    }

    @Test
    fun nightStartEqualsEndMeansNoNight() {
        val s = defaults.copy(nightStartMin = at(22, 0), nightEndMin = at(22, 0))
        for (m in 0 until BlockRules.MINUTES_PER_DAY) assertFalse(BlockRules.isNight(m, s))
    }

    @Test
    fun nightDisabled() {
        val s = defaults.copy(nightEnabled = false)
        assertFalse(BlockRules.isNight(at(2, 0), s))
        assertNull(BlockRules.evaluate(at(2, 0), 10 * min, 0L, s).reason)
    }

    @Test
    fun nightBlocksAnyBalance() {
        val d = BlockRules.evaluate(at(23, 0), 100 * 60 * min, 0L, defaults)
        assertEquals(BlockReason.NIGHT, d.reason)
        assertEquals(0L, d.availableMs)
    }

    // ---------- Лимит ----------

    @Test
    fun limitExactlyExhausted() {
        val d = BlockRules.evaluate(at(12, 0), 60 * min, 120 * min, defaults)
        assertEquals(BlockReason.LIMIT, d.reason)
        assertEquals(0L, d.availableMs)
    }

    @Test
    fun limitOneMsLeft() {
        val d = BlockRules.evaluate(at(12, 0), 60 * min, 120 * min - 1, defaults)
        assertNull(d.reason)
        assertEquals(1L, d.availableMs)
    }

    @Test
    fun zeroLimitMeansNoLimit() {
        val s = defaults.copy(dailyLimitMin = 0)
        assertNull(BlockRules.limitLeftMs(10_000 * min, s))
        val d = BlockRules.evaluate(at(12, 0), 300 * min, 10_000 * min, s)
        assertNull(d.reason)
        assertEquals(300 * min, d.availableMs)
    }

    @Test
    fun limitLeftNeverNegative() {
        assertEquals(0L, BlockRules.limitLeftMs(500 * min, defaults))
        assertEquals(120 * min, BlockRules.limitLeftMs(-5L, defaults))
    }

    // ---------- Приоритет и доступное время ----------

    @Test
    fun priorityNightThenLimitThenBalance() {
        // Всё сразу: ночь, лимит исчерпан, баланса нет.
        assertEquals(BlockReason.NIGHT, BlockRules.evaluate(at(23, 30), 0L, 200 * min, defaults).reason)
        // Днём: лимит важнее пустого баланса.
        assertEquals(BlockReason.LIMIT, BlockRules.evaluate(at(12, 0), 0L, 200 * min, defaults).reason)
        // Лимит не исчерпан, баланса нет.
        assertEquals(BlockReason.BALANCE, BlockRules.evaluate(at(12, 0), 0L, 10 * min, defaults).reason)
    }

    @Test
    fun availableIsMinOfBalanceAndLimitLeft() {
        // Баланс 5 ч, по лимиту осталось 30 мин.
        assertEquals(30 * min, BlockRules.evaluate(at(12, 0), 300 * min, 90 * min, defaults).availableMs)
        // Баланс 10 мин, по лимиту осталось 2 ч.
        assertEquals(10 * min, BlockRules.evaluate(at(12, 0), 10 * min, 0L, defaults).availableMs)
    }

    @Test
    fun negativeBalanceIsBalanceBlock() {
        assertEquals(BlockReason.BALANCE, BlockRules.evaluate(at(12, 0), -5L, 0L, defaults).reason)
    }

    @Test
    fun evaluateByLocalTime() {
        assertEquals(BlockReason.NIGHT, BlockRules.evaluate(LocalTime.of(23, 0, 0), 60 * min, 0L, defaults).reason)
        assertNull(BlockRules.evaluate(LocalTime.of(22, 59, 59), 60 * min, 0L, defaults).reason)
        assertEquals(at(22, 59), BlockRules.minuteOfDay(LocalTime.of(22, 59, 59)))
    }

    @Test
    fun formatsClock() {
        assertEquals("07:00", BlockRules.formatClock(420))
        assertEquals("23:05", BlockRules.formatClock(at(23, 5)))
        assertEquals("00:00", BlockRules.formatClock(0))
    }

    @Test
    fun ranges() {
        assertTrue(0 in BlockRules.LIMIT_RANGE && 1440 in BlockRules.LIMIT_RANGE && 1441 !in BlockRules.LIMIT_RANGE)
        assertTrue(1439 in BlockRules.MINUTE_RANGE && 1440 !in BlockRules.MINUTE_RANGE && -1 !in BlockRules.MINUTE_RANGE)
    }
}
