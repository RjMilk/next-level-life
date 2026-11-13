package com.nextlevellife.app.stats

import com.nextlevellife.app.sleep.SleepMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Заморозка характеристик и граница чистки сырых событий экрана. */
class StatsFreezeTest {

    private fun pts(day: Long) = DayPoints(day, 1, 2, 3, emptyList())

    @Test
    fun freezesOnlySettledUncachedDays() {
        val fresh = (10L..20L).associateWith { pts(it) }
        val frozen = StatsMath.daysToFreeze(fresh, cached = setOf(11L), settledThrough = 13L, sourcesComplete = true)
        assertEquals(setOf(10L, 12L, 13L), frozen.keys)
    }

    @Test
    fun nightsReadErrorFreezesNothing() {
        val fresh = (10L..20L).associateWith { pts(it) }
        assertTrue(StatsMath.daysToFreeze(fresh, cached = emptySet(), settledThrough = 15L, sourcesComplete = false).isEmpty())
    }

    @Test
    fun firstUnfrozenDay() {
        assertEquals(5L, StatsMath.firstUnfrozenDay(5L, 30L, emptySet()))
        assertEquals(8L, StatsMath.firstUnfrozenDay(5L, 30L, setOf(5L, 6L, 7L, 9L)))
        // Дыра в кеше (день не заморожен) — граница на ней, даже если дальше всё заморожено.
        assertEquals(6L, StatsMath.firstUnfrozenDay(5L, 30L, (5L..14L).toSet() - 6L))
        // Всё до сегодня заморожено — следующий день.
        assertEquals(31L, StatsMath.firstUnfrozenDay(5L, 30L, (5L..30L).toSet()))
    }

    @Test
    fun pruneNeverPassesFirstUnfrozenStatsDay() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.of(2026, 11, 13).toEpochDay()
        val now = LocalDate.ofEpochDay(today).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val ninety = now - SleepMath.RAW_RETENTION_DAYS * SleepMath.DAY_MS
        // Ночи все записаны, характеристики заморожены до today − 16: держит только 90 дней.
        assertEquals(ninety, SleepMath.pruneCutoffMs(now, zone, today + 1, today - 15))
        // Характеристики не считались с дня today − 120: события с вечера накануне не чистятся.
        val statsDay = today - 120
        val expected = LocalDate.ofEpochDay(statsDay - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, SleepMath.pruneCutoffMs(now, zone, today + 1, statsDay))
        // Без данных характеристик — как раньше.
        assertEquals(ninety, SleepMath.pruneCutoffMs(now, zone, today + 1, null))
        // Раньше всех — первая незаписанная ночь.
        val night = today - 200
        assertEquals(
            LocalDate.ofEpochDay(night - 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            SleepMath.pruneCutoffMs(now, zone, night, statsDay),
        )
    }
}
