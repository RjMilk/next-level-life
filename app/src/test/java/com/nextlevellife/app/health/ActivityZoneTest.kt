package com.nextlevellife.app.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Учётный пояс в шагах и пробежках: ключи v2 и неизменный день записанной пробежки. */
class ActivityZoneTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val min = 60_000L
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = moscow) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun rewardKeysAreV2AndDifferFromV1() {
        val d = day(2026, 11, 13)
        assertEquals("steps:v2:$d:${30 * min}", ActivityMath.rewardKey(d, 30 * min))
        assertTrue(ActivityMath.rewardKey(d, 30 * min) != "steps:$d:${30 * min}")
    }

    @Test
    fun zoneChangeDoesNotMoveRecordedRun() {
        // Пробежка 23:30–23:55 по Москве 13 ноября: в Токио это уже 14 ноября.
        val run = RunSession("r1", ms(2026, 11, 13, 23, 30), ms(2026, 11, 13, 23, 55), "garmin", 56, distanceM = 5_000)
        val d13 = day(2026, 11, 13)
        val d14 = day(2026, 11, 14)
        assertEquals(d14, ActivityMath.dayOf(run.startMs, tokyo))

        // Записанная пробежка остаётся в своём дне и после смены учётного пояса на Токио.
        val known = mapOf("r1" to d13)
        val days = ActivityMath.assignRunDays(listOf(run), known, tokyo)
        assertEquals(d13, days["r1"])
        val totals = ActivityMath.buildDays(listOf(d13, d14), emptyMap(), emptyMap(), listOf(run), tokyo, days)
        assertEquals(5_000L, totals.first { it.day == d13 }.runDistanceM)
        assertEquals(0L, totals.first { it.day == d14 }.runDistanceM)
        assertEquals(0, totals.first { it.day == d14 }.runsCount)

        // Новая пробежка — день начала в учётном поясе.
        assertEquals(d14, ActivityMath.assignRunDays(listOf(run), emptyMap(), tokyo)["r1"])
        assertEquals(d13, ActivityMath.assignRunDays(listOf(run), emptyMap(), moscow)["r1"])
    }

    @Test
    fun zoneChangeDoesNotPayStepsTwice() {
        val d13 = day(2026, 11, 13)
        val d14 = day(2026, 11, 14)
        val since = d13 - 10
        // По Москве 13 ноября: 18:00–21:00 — 6 000 шагов, оплачены (30 мин) в сутках 13 ноября.
        val paid13 = ActivityMath.due(6_000, 0, 0).totalMs
        assertEquals(30 * min, ActivityMath.rewardDelta(paid13, 0, true, d13, since))
        // В 21:00 по Москве применена смена на Токио: это уже 03:00 14 ноября, и те же шаги
        // (18:00–21:00 Москвы = 00:00–03:00 Токио) попадают в токийские сутки 14 ноября с rewarded_ms = 0.
        val changeAt = ms(2026, 11, 13, 21, 0)
        val frozen = com.nextlevellife.app.data.TrustedClockMath.stepsFrozenThrough(null, changeAt, tokyo)
        assertEquals(d14, frozen)
        val due14 = ActivityMath.due(6_000 + 4_000, 0, 0).totalMs
        // Без заморозки шаги оплатились бы второй раз…
        assertEquals(due14, ActivityMath.rewardDelta(due14, 0, true, d14, since))
        // …а с ней сутки по день смены только показываются.
        assertEquals(0L, ActivityMath.rewardDelta(due14, 0, true, d14, since, frozenThroughDay = frozen))
        assertEquals(0L, ActivityMath.rewardDelta(paid13 + 10 * min, paid13, true, d13, since, frozenThroughDay = frozen))
        // Следующие сутки оплачиваются как обычно.
        assertEquals(10 * min, ActivityMath.rewardDelta(10 * min, 0, true, d14 + 1, since, frozenThroughDay = frozen))
    }

    @Test
    fun dayBoundariesFollowAccountingZone() {
        // Сутки 14 ноября в Токио начинаются в 18:00 13 ноября по Москве.
        assertEquals(ms(2026, 11, 13, 18, 0), ActivityMath.dayStartMs(day(2026, 11, 14), tokyo))
        val pieces = ActivityMath.dayPieces(ms(2026, 11, 13, 17, 30), ms(2026, 11, 13, 18, 30), tokyo)
        assertEquals(listOf(day(2026, 11, 13), day(2026, 11, 14)), pieces.map { it.day })
    }
}
