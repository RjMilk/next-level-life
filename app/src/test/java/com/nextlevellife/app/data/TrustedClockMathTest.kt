package com.nextlevellife.app.data

import com.nextlevellife.app.block.BlockReason
import com.nextlevellife.app.block.BlockRules
import com.nextlevellife.app.block.BlockSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TrustedClockMathTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val hour = 60 * 60_000L
    private val day48 = 48 * hour
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = moscow) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private val settings = BlockSettings(dailyLimitMin = 120, nightEnabled = true, nightStartMin = 23 * 60, nightEndMin = 7 * 60)

    // ---------- Доверенное время в пределах загрузки ----------

    @Test
    fun trustedTimeFollowsMonotonicClockWithinBoot() {
        val anchor = ClockAnchor(bootCount = 7, anchorUtcMs = ms(2026, 11, 13, 12, 0), anchorElapsedMs = 50_000)
        assertEquals(anchor.anchorUtcMs, TrustedClockMath.trustedNow(anchor, 50_000))
        assertEquals(anchor.anchorUtcMs + hour, TrustedClockMath.trustedNow(anchor, 50_000 + hour))
    }

    @Test
    fun processRestartInSameBootKeepsAnchorWhateverSystemClockSays() {
        val saved = ClockAnchor(7, ms(2026, 11, 13, 12, 0), 50_000)
        val elapsed = 50_000 + 2 * hour
        val realNow = saved.anchorUtcMs + 2 * hour
        for (shift in listOf(0L, day48, -day48)) {
            val a = TrustedClockMath.resolveAnchor(saved, lastTrustedMs = realNow, bootCount = 7, elapsedNowMs = elapsed,
                systemNowMs = realNow + shift, networkNowMs = null)
            assertEquals(saved, a)
            assertEquals(realNow, TrustedClockMath.trustedNow(a, elapsed))
        }
    }

    @Test
    fun systemClockShiftDoesNotChangeAccountingDayOrNight() {
        // 13 ноября, 22:30 по Москве: не ночь, расход за сегодня 120 минут — лимит исчерпан.
        val saved = ClockAnchor(3, ms(2026, 11, 13, 22, 0), 1_000)
        val elapsed = 1_000 + 30 * 60_000L
        val spentByDay = mapOf(day(2026, 11, 13) to 120 * 60_000L)
        for (shift in listOf(day48, -day48, 24 * hour - 10 * 60_000L)) {
            val a = TrustedClockMath.resolveAnchor(saved, null, 3, elapsed, systemNowMs = ms(2026, 11, 13, 22, 30) + shift, networkNowMs = null)
            val snap = TimeSnapshot(TrustedClockMath.trustedNow(a, elapsed), moscow)
            assertEquals(day(2026, 11, 13), snap.day)
            assertFalse(BlockRules.isNight(snap, settings))
            val d = BlockRules.evaluate(snap, balanceMs = 10 * hour, spentTodayMs = spentByDay[snap.day] ?: 0L, s = settings)
            assertEquals(BlockReason.LIMIT, d.reason)
        }
        // Ночью перевод часов «на день вперёд, на полдень» ночь не снимает.
        val nightAnchor = ClockAnchor(3, ms(2026, 11, 13, 23, 30), 1_000)
        val a = TrustedClockMath.resolveAnchor(nightAnchor, null, 3, 2_000, ms(2026, 11, 14, 12, 0), null)
        val snap = TimeSnapshot(TrustedClockMath.trustedNow(a, 2_000), moscow)
        assertTrue(BlockRules.isNight(snap, settings))
        assertEquals(BlockReason.NIGHT, BlockRules.evaluate(snap, 10 * hour, 0L, settings).reason)
    }

    @Test
    fun snapshotDayAndMinuteAreInAccountingZone() {
        val t = ms(2026, 11, 13, 22, 30) // 22:30 Москва = 04:30 14 ноября в Токио
        val m = TimeSnapshot(t, moscow)
        val k = TimeSnapshot(t, tokyo)
        assertEquals(day(2026, 11, 13), m.day)
        assertEquals(22 * 60 + 30, m.minuteOfDay)
        assertEquals(day(2026, 11, 14), k.day)
        assertEquals(4 * 60 + 30, k.minuteOfDay)
    }

    // ---------- Новая загрузка ----------

    @Test
    fun newBootWithoutNetworkNeverGoesBackFromLastTrusted() {
        val saved = ClockAnchor(3, ms(2026, 11, 13, 12, 0), 900_000)
        val last = ms(2026, 11, 13, 18, 0)
        // Номер загрузки сменился, часы переведены на 2 суток назад.
        val a = TrustedClockMath.resolveAnchor(saved, last, bootCount = 4, elapsedNowMs = 10_000,
            systemNowMs = last - day48, networkNowMs = null)
        assertEquals(ClockAnchor(4, last, 10_000), a)
        // Системное время впереди last_trusted — берётся оно.
        val b = TrustedClockMath.resolveAnchor(saved, last, 4, 10_000, last + hour, null)
        assertEquals(last + hour, b.anchorUtcMs)
    }

    @Test
    fun newBootPrefersNetworkTime() {
        val saved = ClockAnchor(3, ms(2026, 11, 13, 12, 0), 900_000)
        val last = ms(2026, 11, 15, 12, 0) // last_trusted «убежал» вперёд после ручного перевода
        val network = ms(2026, 11, 13, 13, 0)
        val a = TrustedClockMath.resolveAnchor(saved, last, 4, 5_000, systemNowMs = last + day48, networkNowMs = network)
        assertEquals(network, a.anchorUtcMs)
        assertEquals(5_000L, a.anchorElapsedMs)
    }

    @Test
    fun monotonicClockGoingBackMeansNewBootEvenWithSameOrUnknownBootCount() {
        val saved = ClockAnchor(null, ms(2026, 11, 13, 12, 0), 900_000)
        assertFalse(TrustedClockMath.isSameBoot(saved, null, 10_000))
        assertTrue(TrustedClockMath.isSameBoot(saved, null, 1_000_000))
        assertTrue(TrustedClockMath.isSameBoot(saved, 5, 1_000_000))
        assertFalse(TrustedClockMath.isSameBoot(saved.copy(bootCount = 4), 5, 1_000_000))
        // Номер загрузки появился — сохраняется в якоре, сам якорь прежний.
        val a = TrustedClockMath.resolveAnchor(saved, null, 5, 1_000_000, 0L, null)
        assertEquals(saved.copy(bootCount = 5), a)
    }

    @Test
    fun firstRunWithoutHistoryUsesSystemTime() {
        val a = TrustedClockMath.resolveAnchor(null, null, 1, 3_000, 1_700_000_000_000, null)
        assertEquals(ClockAnchor(1, 1_700_000_000_000, 3_000), a)
    }

    @Test
    fun skewAndPersistThresholds() {
        assertFalse(TrustedClockMath.isSkewed(1_000_000, 1_000_000 + 120_000))
        assertTrue(TrustedClockMath.isSkewed(1_000_000, 1_000_000 + 120_001))
        assertTrue(TrustedClockMath.isSkewed(1_000_000, 1_000_000 - day48))
        assertTrue(TrustedClockMath.shouldPersist(null, 5))
        assertFalse(TrustedClockMath.shouldPersist(10_000, 69_999))
        assertTrue(TrustedClockMath.shouldPersist(10_000, 70_000))
    }

    // ---------- Учётный пояс ----------

    @Test
    fun pendingZoneAppliesAfter24HoursNotBefore() {
        val since = ms(2026, 11, 13, 12, 0)
        val state = ZoneState("Europe/Moscow", PendingZone("Asia/Tokyo", since))
        assertEquals(state, TrustedClockMath.effectiveZone(state, since))
        assertEquals(state, TrustedClockMath.effectiveZone(state, since + 24 * hour - 1))
        assertEquals(ZoneState("Asia/Tokyo", null), TrustedClockMath.effectiveZone(state, since + 24 * hour))
        assertEquals(since + 24 * hour, state.pending!!.effectiveAtMs)
        // Без отложенной смены ничего не меняется.
        val plain = ZoneState("Europe/Moscow", null)
        assertEquals(plain, TrustedClockMath.effectiveZone(plain, since + day48))
    }

    @Test
    fun requestZoneStartsCountdownOnceAndCancelsOnSameZone() {
        val t0 = 1_000_000L
        val s0 = ZoneState("Europe/Moscow", null)
        val s1 = TrustedClockMath.requestZone(s0, "Asia/Tokyo", t0)
        assertEquals(PendingZone("Asia/Tokyo", t0), s1.pending)
        // Повторный запрос того же пояса отсчёт не сбрасывает.
        assertEquals(s1, TrustedClockMath.requestZone(s1, "Asia/Tokyo", t0 + hour))
        // Другой пояс — отсчёт заново.
        assertEquals(PendingZone("Europe/London", t0 + hour), TrustedClockMath.requestZone(s1, "Europe/London", t0 + hour).pending)
        // Запрос на учётный пояс — отмена.
        assertEquals(s0, TrustedClockMath.requestZone(s1, "Europe/Moscow", t0 + hour))
    }

    @Test
    fun switchOfferOnlyWhenPhoneZoneDiffers() {
        val s = ZoneState("Europe/Moscow", null)
        assertNull(TrustedClockMath.zoneSwitchOffer(s, "Europe/Moscow"))
        assertEquals("Asia/Tokyo", TrustedClockMath.zoneSwitchOffer(s, "Asia/Tokyo"))
        val pending = s.copy(pending = PendingZone("Asia/Tokyo", 0))
        assertNull(TrustedClockMath.zoneSwitchOffer(pending, "Asia/Tokyo"))
        assertEquals("Europe/London", TrustedClockMath.zoneSwitchOffer(pending, "Europe/London"))
    }

    @Test
    fun resumeClockIsShownOnPhoneClock() {
        val now = TimeSnapshot(ms(2026, 11, 13, 23, 30), moscow)
        assertEquals("07:00", TrustedClockMath.nextLocalClock(7 * 60, now, moscow))
        assertEquals("00:00", TrustedClockMath.nextLocalClock(0, now, moscow))
        // Телефон в Токио (+6 ч к Москве): 07:00 Москвы — 13:00 по часам телефона.
        assertEquals("13:00", TrustedClockMath.nextLocalClock(7 * 60, now, tokyo))
        assertEquals("06:00", TrustedClockMath.nextLocalClock(0, now, tokyo))
    }

    // ---------- Восстановление из копии ----------

    @Test
    fun restoreKeepsLocalAnchorAndTakesZoneFromBackup() {
        val local = mapOf(
            TrustedClock.KEY_BOOT_COUNT to "12", TrustedClock.KEY_ANCHOR_UTC to "1000",
            TrustedClock.KEY_ANCHOR_ELAPSED to "50", TrustedClock.KEY_LAST_TRUSTED to "2000",
            TrustedClock.KEY_ZONE to "Europe/Moscow",
        )
        val backup = mapOf(
            TrustedClock.KEY_BOOT_COUNT to "3", TrustedClock.KEY_ANCHOR_UTC to "9", TrustedClock.KEY_ZONE to "Asia/Tokyo",
            TrustedClock.KEY_PENDING_ZONE to "Europe/London", TrustedClock.KEY_PENDING_SINCE to "5",
        )
        val changes = TrustedClockMath.restoredMeta(local, backup)
        assertEquals("12", changes[TrustedClock.KEY_BOOT_COUNT])
        assertEquals("1000", changes[TrustedClock.KEY_ANCHOR_UTC])
        assertEquals("50", changes[TrustedClock.KEY_ANCHOR_ELAPSED])
        assertEquals("2000", changes[TrustedClock.KEY_LAST_TRUSTED])
        // Пояс из копии не трогается.
        assertFalse(changes.containsKey(TrustedClock.KEY_ZONE))
        // Отложенная смена удаляется.
        assertTrue(changes.containsKey(TrustedClock.KEY_PENDING_ZONE) && changes[TrustedClock.KEY_PENDING_ZONE] == null)
        assertTrue(changes.containsKey(TrustedClock.KEY_PENDING_SINCE) && changes[TrustedClock.KEY_PENDING_SINCE] == null)
        // В старой копии пояса нет — остаётся здешний.
        assertEquals("Europe/Moscow", TrustedClockMath.restoredMeta(local, emptyMap())[TrustedClock.KEY_ZONE])
    }

    // ---------- Разбиение списания через полночь ----------

    @Test
    fun spendAcrossMidnightIsSplitIntoTwoDays() {
        val end = ms(2026, 11, 14, 0, 0) + 1_500 // 00:00:01.5
        val parts = LedgerMath.splitByDay(end, 4_000, moscow)
        assertEquals(2, parts.size)
        assertEquals(DayShare(day(2026, 11, 13), 2_500, ms(2026, 11, 14, 0, 0) - 1), parts[0])
        assertEquals(DayShare(day(2026, 11, 14), 1_500, end), parts[1])
        assertEquals(4_000L, parts.sumOf { it.ms })
    }

    @Test
    fun spendWithinOneDayIsOnePart() {
        val end = ms(2026, 11, 13, 15, 0)
        assertEquals(listOf(DayShare(day(2026, 11, 13), 2_000, end)), LedgerMath.splitByDay(end, 2_000, moscow))
        assertTrue(LedgerMath.splitByDay(end, 0, moscow).isEmpty())
        // Конец ровно в полночь — всё в прошлых сутках.
        val midnight = ms(2026, 11, 14, 0, 0)
        assertEquals(listOf(DayShare(day(2026, 11, 13), 2_000, midnight - 1)), LedgerMath.splitByDay(midnight, 2_000, moscow))
    }

    @Test
    fun longDebtSpansSeveralDaysAndSumsUp() {
        val end = ms(2026, 11, 15, 1, 0)
        val parts = LedgerMath.splitByDay(end, 26 * hour, moscow)
        assertEquals(listOf(day(2026, 11, 13), day(2026, 11, 14), day(2026, 11, 15)), parts.map { it.day })
        assertEquals(listOf(hour, 24 * hour, hour), parts.map { it.ms })
    }

    @Test
    fun splitPartsGoToSeparateUsageRows() {
        val end = ms(2026, 11, 14, 0, 0) + 1_000
        var cursor: UsageCursor? = UsageCursor("s", "app", 0, day(2026, 11, 13), 10_000)
        var extended = 0L
        val rows = ArrayList<Pair<Long, Long>>()
        for (share in LedgerMath.splitByDay(end, 3_000, moscow)) {
            val p = LedgerMath.planUsage(cursor, "s", "app", share.day, share.ms)
            extended += p.addToOpen
            p.newRows.forEach { rows += share.day to it }
            cursor = p.next
        }
        // Вчерашняя часть дописана в открытую вчерашнюю строку, сегодняшняя — новая строка нового дня.
        assertEquals(2_000L, extended)
        assertEquals(listOf(day(2026, 11, 14) to 1_000L), rows)
        assertEquals(day(2026, 11, 14), cursor!!.day)
        assertEquals(1, cursor.index)
    }

    // ---------- Миграция block_events ----------

    @Test
    fun blockEventsMigrationIsAppended() {
        assertEquals(4, Diary.SCHEMA_VERSION)
        val m = Diary.MIGRATIONS[3]
        val table = m.first { it.contains("CREATE TABLE block_events") }
        for (col in listOf("id INTEGER PRIMARY KEY", "utc_ms", "accounting_day", "package_name", "reason", "\"trigger\"")) {
            assertTrue("нет столбца $col", table.contains(col))
        }
    }
}
