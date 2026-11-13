package com.nextlevellife.app.block

import com.nextlevellife.app.data.UsageDebt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendMeterTest {

    /** Как служба: смена экрана сначала закрывает интервал по прежнему состоянию, потом меняет его. */
    private fun SpendMeter.screen(nowMs: Long, on: Boolean): Long = take(nowMs).also { setScreen(on) }

    @Test
    fun screenOffBeforeTickKeepsViewedTime() {
        val m = SpendMeter(screenOn = true)
        m.restart(0)
        // Смотрел 1,5 с, потом выключил экран — эти 1,5 с списываются при выключении.
        assertEquals(1_500L, m.screen(1_500, on = false))
        // Тик через 0,5 с после выключения ничего не списывает.
        assertEquals(0L, m.take(2_000))
    }

    @Test
    fun screenOnBeforeDelayedTickDoesNotChargeOffTime() {
        val m = SpendMeter(screenOn = true)
        m.restart(0)
        assertEquals(1_000L, m.screen(1_000, on = false))
        // Экран выключен 10 минут, тик задержался; включение закрывает выключенный интервал без списания.
        assertEquals(0L, m.screen(601_000, on = true))
        // Задержавшийся тик через 2 с после включения списывает только 2 с.
        assertEquals(2_000L, m.take(603_000))
    }

    @Test
    fun repeatedOnEventsDoNotDoubleCharge() {
        val m = SpendMeter(screenOn = false)
        m.restart(0)
        assertEquals(0L, m.screen(5_000, on = true)) // SCREEN_ON
        assertEquals(1_000L, m.screen(6_000, on = true)) // USER_PRESENT: экран уже был включён
        assertEquals(1_000L, m.take(7_000))
    }

    @Test
    fun restartDropsTimeBeforeSession() {
        val m = SpendMeter()
        m.restart(0)
        m.restart(10_000)
        assertEquals(500L, m.take(10_500))
        // Время назад (не бывает у монотонных часов) — ноль, не отрицательное.
        assertEquals(0L, m.take(10_000))
    }

    @Test
    fun debtsRoundTrip() {
        val debts = listOf(
            UsageDebt(1_000L, 3_000L, "com.example.app", "0b6c-uuid"),
            UsageDebt(-5L, 7_000_000_000_000L, "pkg.with\"quote\\slash", "s\u0001"),
        )
        val text = UsageDebtCodec.encode(debts)
        assertEquals(debts, UsageDebtCodec.decode(text))
        assertEquals(emptyList<UsageDebt>(), UsageDebtCodec.decode(UsageDebtCodec.encode(emptyList())))
    }

    @Test
    fun debtsJsonShape() {
        assertEquals(
            "[{\"start\":1,\"end\":2,\"pkg\":\"a\",\"session\":\"s\"}]",
            UsageDebtCodec.encode(listOf(UsageDebt(1, 2, "a", "s"))),
        )
        // Пробелы и другой порядок полей тоже читаются.
        assertEquals(
            listOf(UsageDebt(1, 2, "a", "s")),
            UsageDebtCodec.decode(" [ { \"pkg\" : \"a\", \"session\":\"s\", \"end\":2, \"start\":1 } ] "),
        )
    }

    @Test
    fun brokenDebtsAreNull() {
        assertNull(UsageDebtCodec.decode(""))
        assertNull(UsageDebtCodec.decode("[{\"start\":1}]"))
        assertNull(UsageDebtCodec.decode("[{\"start\":1,\"end\":2,\"pkg\":\"a\",\"session\":\"s\"}"))
        assertNull(UsageDebtCodec.decode("[{\"start\":x,\"end\":2,\"pkg\":\"a\",\"session\":\"s\"}]"))
        assertNull(UsageDebtCodec.decode("[] мусор"))
    }
}
