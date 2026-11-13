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
        val saved = ClockAnchor(7, ms(2026, 11, 13, 12, 0), 50_000, AnchorSource.NETWORK)
        val elapsed = 50_000 + 2 * hour
        val realNow = saved.anchorUtcMs + 2 * hour
        for (shift in listOf(0L, day48, -day48)) {
            val a = TrustedClockMath.resolveAnchor(SavedClock(saved, realNow - 60_000, elapsed - 60_000), bootCount = 7,
                elapsedNowMs = elapsed, systemNowMs = realNow + shift, networkNowMs = null)
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
            val a = TrustedClockMath.resolveAnchor(SavedClock(saved, null, null), 3, elapsed,
                systemNowMs = ms(2026, 11, 13, 22, 30) + shift, networkNowMs = null)
            val snap = TimeSnapshot(TrustedClockMath.trustedNow(a, elapsed), moscow)
            assertEquals(day(2026, 11, 13), snap.day)
            assertFalse(BlockRules.isNight(snap, settings))
            val d = BlockRules.evaluate(snap, balanceMs = 10 * hour, spentTodayMs = spentByDay[snap.day] ?: 0L, s = settings)
            assertEquals(BlockReason.LIMIT, d.reason)
        }
        // Ночью перевод часов «на день вперёд, на полдень» ночь не снимает.
        val nightAnchor = ClockAnchor(3, ms(2026, 11, 13, 23, 30), 1_000)
        val a = TrustedClockMath.resolveAnchor(SavedClock(nightAnchor, null, null), 3, 2_000, ms(2026, 11, 14, 12, 0), null)
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
    fun newBootWithoutNetworkIsConservativeWhateverSystemClockSays() {
        val saved = ClockAnchor(3, ms(2026, 11, 13, 12, 0), 900_000, AnchorSource.NETWORK)
        val last = ms(2026, 11, 13, 18, 0)
        // Номер загрузки сменился, сети нет: часы назад на 2 суток, как есть или вперёд на 2 суток — всё равно
        // якорь = last_trusted + elapsedRealtime на момент запуска (время выключенного телефона теряется).
        for (system in listOf(last - day48, last + hour, last + day48)) {
            val a = TrustedClockMath.resolveAnchor(SavedClock(saved, last, 900_000 + 6 * hour), bootCount = 4,
                elapsedNowMs = 10_000, systemNowMs = system, networkNowMs = null)
            assertEquals(ClockAnchor(4, last + 10_000, 10_000, AnchorSource.CONSERVATIVE), a)
        }
    }

    @Test
    fun airplaneModeRebootWithClockForwardDoesNotOpenNightOrLimit() {
        // 23:30 13 ноября по Москве, ночь. Владелец переводит часы на 12:00 следующего дня, включает режим
        // полёта и перезагружается (любой Android: сетевого времени в начале загрузки нет).
        val last = ms(2026, 11, 13, 23, 30)
        val saved = SavedClock(ClockAnchor(3, last - hour, 5_000, AnchorSource.NETWORK), last, 5_000 + hour)
        val a = TrustedClockMath.resolveAnchor(saved, 4, 40_000, systemNowMs = ms(2026, 11, 14, 12, 0), networkNowMs = null)
        val snap = TimeSnapshot(TrustedClockMath.trustedNow(a, 60_000), moscow)
        assertEquals(day(2026, 11, 13), snap.day)
        assertTrue(BlockRules.isNight(snap, settings))
        assertEquals(AnchorSource.CONSERVATIVE, a.source)
    }

    @Test
    fun newBootPrefersNetworkTimeAheadOfHighWater() {
        val saved = ClockAnchor(3, ms(2026, 11, 13, 12, 0), 900_000)
        val last = ms(2026, 11, 13, 18, 0)
        val network = ms(2026, 11, 14, 9, 0) // телефон был выключен ночь — сеть знает настоящее время
        val a = TrustedClockMath.resolveAnchor(SavedClock(saved, last, null), 4, 5_000, systemNowMs = last, networkNowMs = network)
        assertEquals(ClockAnchor(4, network, 5_000, AnchorSource.NETWORK), a)
    }

    @Test
    fun networkBelowHighWaterKeepsTheMark() {
        val saved = ClockAnchor(3, ms(2026, 11, 13, 12, 0), 900_000)
        val last = ms(2026, 11, 15, 12, 0) // last_trusted «убежал» вперёд
        val network = ms(2026, 11, 13, 13, 0)
        val a = TrustedClockMath.resolveAnchor(SavedClock(saved, last, null), 4, 5_000, systemNowMs = last + day48, networkNowMs = network)
        // Остаёмся на отметке и идём от неё: назад время не уходит, сеть его «догонит».
        assertEquals(ClockAnchor(4, last, 5_000, AnchorSource.NETWORK), a)
        assertEquals(last + hour, TrustedClockMath.trustedNow(a, 5_000 + hour))
    }

    @Test
    fun conservativeAnchorMovesToNetworkOnlyForward() {
        val a = ClockAnchor(4, ms(2026, 11, 13, 18, 0), 10_000, AnchorSource.CONSERVATIVE)
        val elapsed = 10_000 + 5 * 60_000L
        val trusted = TrustedClockMath.trustedNow(a, elapsed)
        // Сеть впереди — новый якорь по сети в этой же загрузке.
        val ahead = trusted + 9 * hour
        assertEquals(ClockAnchor(4, ahead, elapsed, AnchorSource.NETWORK), TrustedClockMath.upgradeWithNetwork(a, elapsed, ahead))
        // Сеть позади — якорь прежний (назад никогда), только помечен сетевым: больше не опрашиваем.
        assertEquals(a.copy(source = AnchorSource.NETWORK), TrustedClockMath.upgradeWithNetwork(a, elapsed, trusted - hour))
        // Сети нет или она вне пределов — менять нечего.
        assertNull(TrustedClockMath.upgradeWithNetwork(a, elapsed, null))
        assertNull(TrustedClockMath.upgradeWithNetwork(a, elapsed, 5L))
        // Уже сетевой якорь не трогается.
        assertNull(TrustedClockMath.upgradeWithNetwork(a.copy(source = AnchorSource.NETWORK), elapsed, ahead))
        // В той же загрузке при перезапуске процесса консервативный якорь тоже переставляется вперёд.
        val r = TrustedClockMath.resolveAnchor(SavedClock(a, trusted - 60_000, elapsed - 60_000), 4, elapsed, 0L, ahead)
        assertEquals(ClockAnchor(4, ahead, elapsed, AnchorSource.NETWORK), r)
    }

    @Test
    fun unknownBootCountMeansNewUndeterminedBootUnlessUptimeKeptGrowing() {
        val saved = ClockAnchor(null, ms(2026, 11, 13, 12, 0), 1_000, AnchorSource.NETWORK)
        val lastElapsed = 3 * hour
        // Новая загрузка: uptime 30 с — меньше последнего наблюдавшегося (3 ч), хоть и больше якорного.
        assertFalse(TrustedClockMath.isSameBoot(saved, lastElapsed, null, 30_000))
        // Uptime не меньше последнего наблюдавшегося — та же загрузка.
        assertTrue(TrustedClockMath.isSameBoot(saved, lastElapsed, null, lastElapsed))
        // Последнего наблюдения нет — загрузка неопределённая, считается новой.
        assertFalse(TrustedClockMath.isSameBoot(saved, null, null, 30_000))
        assertFalse(TrustedClockMath.isSameBoot(saved, null, 5, 30_000))
        // Номера известны с обеих сторон — решают они (если uptime не ушёл назад).
        assertTrue(TrustedClockMath.isSameBoot(saved.copy(bootCount = 5), lastElapsed, 5, 4 * hour))
        assertFalse(TrustedClockMath.isSameBoot(saved.copy(bootCount = 4), lastElapsed, 5, 4 * hour))
        assertFalse(TrustedClockMath.isSameBoot(saved.copy(bootCount = 5), lastElapsed, 5, 30_000))

        val last = saved.anchorUtcMs + 3 * hour
        val a = TrustedClockMath.resolveAnchor(SavedClock(saved, last, lastElapsed), null, 30_000, last + day48, null)
        assertEquals(ClockAnchor(null, last + 30_000, 30_000, AnchorSource.CONSERVATIVE), a)
        // Номер загрузки появился в той же загрузке — сохраняется в якоре, сам якорь прежний.
        val b = TrustedClockMath.resolveAnchor(SavedClock(saved, last, lastElapsed), 5, 4 * hour, 0L, null)
        assertEquals(saved.copy(bootCount = 5), b)
        // Последнего момента нет (данные прежней версии): загрузка считается новой, но если это та же —
        // время не убегает вперёд на весь uptime: берётся меньшее из консервативных вариантов.
        val c = TrustedClockMath.resolveAnchor(SavedClock(saved, last, null), null, 4 * hour, last + day48, null)
        assertEquals(AnchorSource.CONSERVATIVE, c.source)
        assertEquals(saved.anchorUtcMs + 4 * hour - 1_000, TrustedClockMath.trustedNow(c, 4 * hour))
        // Настоящая новая загрузка (uptime 30 с) — не ниже отметки.
        val d = TrustedClockMath.resolveAnchor(SavedClock(saved, last, null), null, 30_000, last + day48, null)
        assertEquals(last, TrustedClockMath.trustedNow(d, 30_000))
    }

    @Test
    fun sameBootNeverGoesBelowHighWater() {
        val saved = ClockAnchor(5, ms(2026, 11, 13, 12, 0), 1_000, AnchorSource.NETWORK)
        val last = ms(2026, 11, 13, 20, 0) // отметка впереди того, что даёт якорь
        val a = TrustedClockMath.resolveAnchor(SavedClock(saved, last, 2_000), 5, 3_000, 0L, null)
        assertEquals(last, TrustedClockMath.trustedNow(a, 3_000))
        assertEquals(last + 1_000, TrustedClockMath.trustedNow(a, 4_000))
    }

    @Test
    fun firstRunWithoutHistoryUsesSystemTimeWithinLimits() {
        val sys = ms(2026, 11, 13, 12, 0)
        assertEquals(ClockAnchor(1, sys, 3_000, AnchorSource.CONSERVATIVE),
            TrustedClockMath.resolveAnchor(SavedClock.EMPTY, 1, 3_000, sys, null))
        // Системное время вне пределов — к границе.
        assertEquals(TrustedClockMath.MIN_UTC_MS, TrustedClockMath.resolveAnchor(SavedClock.EMPTY, 1, 3_000, 0L, null).anchorUtcMs)
        assertEquals(TrustedClockMath.MAX_UTC_MS - 1,
            TrustedClockMath.resolveAnchor(SavedClock.EMPTY, 1, 3_000, Long.MAX_VALUE, null).anchorUtcMs)
        // Сетевое время есть — по нему.
        assertEquals(ClockAnchor(1, sys + hour, 3_000, AnchorSource.NETWORK),
            TrustedClockMath.resolveAnchor(SavedClock.EMPTY, 1, 3_000, sys, sys + hour))
    }

    @Test
    fun invalidStoredValuesAreRejected() {
        val ok = ms(2026, 11, 13, 12, 0).toString()
        val meta = mapOf(
            TrustedClock.KEY_BOOT_COUNT to "3", TrustedClock.KEY_ANCHOR_UTC to ok, TrustedClock.KEY_ANCHOR_ELAPSED to "500",
            TrustedClock.KEY_ANCHOR_SOURCE to "NETWORK", TrustedClock.KEY_LAST_TRUSTED to ok, TrustedClock.KEY_LAST_ELAPSED to "900",
        )
        val good = TrustedClockMath.parseSaved { meta[it] }
        assertEquals(SavedClock(ClockAnchor(3, ok.toLong(), 500, AnchorSource.NETWORK), ok.toLong(), 900), good)

        fun parsed(key: String, value: String) = TrustedClockMath.parseSaved { (meta + (key to value))[it] }
        // Время вне 2024…2100, отрицательные монотонные часы, мусор — отбрасываются.
        assertNull(parsed(TrustedClock.KEY_ANCHOR_UTC, "1000").anchor)
        assertNull(parsed(TrustedClock.KEY_ANCHOR_UTC, "${TrustedClockMath.MAX_UTC_MS}").anchor)
        assertNull(parsed(TrustedClock.KEY_ANCHOR_ELAPSED, "-1").anchor)
        assertNull(parsed(TrustedClock.KEY_ANCHOR_UTC, "abc").anchor)
        assertEquals(listOf(TrustedClock.KEY_ANCHOR_UTC), parsed(TrustedClock.KEY_ANCHOR_UTC, "1000").rejected)
        assertNull(parsed(TrustedClock.KEY_LAST_TRUSTED, "${Long.MAX_VALUE}").lastTrustedMs)
        assertNull(parsed(TrustedClock.KEY_LAST_ELAPSED, "-5").lastElapsedMs)
        // Неизвестный источник — консервативный.
        assertEquals(AnchorSource.CONSERVATIVE, parsed(TrustedClock.KEY_ANCHOR_SOURCE, "???").anchor!!.source)

        // Якорь отброшен — загрузка неопределённая: консервативно от отметки, сетевое вне пределов не используется.
        val bad = parsed(TrustedClock.KEY_ANCHOR_UTC, "1000")
        val a = TrustedClockMath.resolveAnchor(bad, 3, 1_000, systemNowMs = ok.toLong() + day48, networkNowMs = 99L)
        assertEquals(ClockAnchor(3, ok.toLong() + 1_000, 1_000, AnchorSource.CONSERVATIVE), a)
    }

    @Test
    fun arithmeticDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, TrustedClockMath.addSaturating(Long.MAX_VALUE - 1, 5))
        assertEquals(Long.MIN_VALUE, TrustedClockMath.addSaturating(Long.MIN_VALUE + 1, -5))
        assertEquals(7L, TrustedClockMath.addSaturating(3, 4))
        val a = ClockAnchor(1, TrustedClockMath.MAX_UTC_MS - 10, 0)
        assertEquals(TrustedClockMath.MAX_UTC_MS - 1, TrustedClockMath.trustedNow(a, Long.MAX_VALUE))
        val far = SavedClock(null, TrustedClockMath.MAX_UTC_MS - 10, null)
        assertEquals(TrustedClockMath.MAX_UTC_MS - 1, TrustedClockMath.resolveAnchor(far, 1, Long.MAX_VALUE, 0L, null).anchorUtcMs)
    }

    @Test
    fun skewPersistAndRetryThresholds() {
        assertFalse(TrustedClockMath.isSkewed(1_000_000, 1_000_000 + 120_000))
        assertTrue(TrustedClockMath.isSkewed(1_000_000, 1_000_000 + 120_001))
        assertTrue(TrustedClockMath.isSkewed(1_000_000, 1_000_000 - day48))
        assertTrue(TrustedClockMath.shouldPersist(null, 5))
        assertFalse(TrustedClockMath.shouldPersist(10_000, 69_999))
        assertTrue(TrustedClockMath.shouldPersist(10_000, 70_000))
        assertTrue(TrustedClockMath.shouldRetryInit(null, 5))
        assertFalse(TrustedClockMath.shouldRetryInit(10_000, 14_999))
        assertTrue(TrustedClockMath.shouldRetryInit(10_000, 15_000))
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
    fun restoreKeepsLocalClockAndZoneAndSchedulesBackupZone() {
        val now = ms(2026, 11, 13, 22, 30)
        val local = mapOf(
            TrustedClock.KEY_BOOT_COUNT to "12", TrustedClock.KEY_ANCHOR_UTC to "1000",
            TrustedClock.KEY_ANCHOR_ELAPSED to "50", TrustedClock.KEY_LAST_TRUSTED to "2000",
            TrustedClock.KEY_LAST_ELAPSED to "70", TrustedClock.KEY_ANCHOR_SOURCE to "NETWORK",
            TrustedClock.KEY_ZONE to "Europe/Moscow",
        )
        val backup = mapOf(
            TrustedClock.KEY_BOOT_COUNT to "3", TrustedClock.KEY_ANCHOR_UTC to "9", TrustedClock.KEY_ZONE to "Asia/Tokyo",
            TrustedClock.KEY_PENDING_ZONE to "Europe/London", TrustedClock.KEY_PENDING_SINCE to "5",
            TrustedClock.KEY_LAST_ELAPSED to "999999",
        )
        val changes = TrustedClockMath.restoredMeta(local, backup, now)
        assertEquals("12", changes[TrustedClock.KEY_BOOT_COUNT])
        assertEquals("1000", changes[TrustedClock.KEY_ANCHOR_UTC])
        assertEquals("50", changes[TrustedClock.KEY_ANCHOR_ELAPSED])
        assertEquals("2000", changes[TrustedClock.KEY_LAST_TRUSTED])
        assertEquals("70", changes[TrustedClock.KEY_LAST_ELAPSED])
        assertEquals("NETWORK", changes[TrustedClock.KEY_ANCHOR_SOURCE])
        // Учётный пояс сразу не меняется — остаётся здешний.
        assertEquals("Europe/Moscow", changes[TrustedClock.KEY_ZONE])
        // Пояс копии — обычной отложенной сменой на 24 часа с момента восстановления.
        assertEquals("Asia/Tokyo", changes[TrustedClock.KEY_PENDING_ZONE])
        assertEquals(now.toString(), changes[TrustedClock.KEY_PENDING_SINCE])
        // Итоги шагов в копии — по суткам её пояса: оплата шагов заморожена по сегодня (здешний пояс).
        assertEquals(day(2026, 11, 13).toString(), changes[TrustedClock.KEY_STEPS_FROZEN])

        // Тот же пояс в копии — ничего не откладывается, здешняя отложенная смена сохраняется, шаги не замораживаются.
        val localPending = local + mapOf(TrustedClock.KEY_PENDING_ZONE to "Europe/London", TrustedClock.KEY_PENDING_SINCE to "1700")
        val same = TrustedClockMath.restoredMeta(localPending, backup + (TrustedClock.KEY_ZONE to "Europe/Moscow"), now)
        assertEquals("Europe/Moscow", same[TrustedClock.KEY_ZONE])
        assertEquals("Europe/London", same[TrustedClock.KEY_PENDING_ZONE])
        assertEquals("1700", same[TrustedClock.KEY_PENDING_SINCE])
        assertFalse(same.containsKey(TrustedClock.KEY_STEPS_FROZEN))
        val sameNoPending = TrustedClockMath.restoredMeta(local, backup + (TrustedClock.KEY_ZONE to "Europe/Moscow"), now)
        assertTrue(sameNoPending.containsKey(TrustedClock.KEY_PENDING_ZONE) && sameNoPending[TrustedClock.KEY_PENDING_ZONE] == null)

        // В старой копии пояса нет — остаётся здешний, без отложенной смены; шаги замораживаются.
        val old = TrustedClockMath.restoredMeta(local, emptyMap(), now)
        assertEquals("Europe/Moscow", old[TrustedClock.KEY_ZONE])
        assertNull(old[TrustedClock.KEY_PENDING_ZONE])
        assertEquals(day(2026, 11, 13).toString(), old[TrustedClock.KEY_STEPS_FROZEN])
        // Заморозка из копии не уменьшается.
        val later = (day(2026, 11, 20)).toString()
        assertEquals(later, TrustedClockMath.restoredMeta(local, backup + (TrustedClock.KEY_STEPS_FROZEN to later), now)[TrustedClock.KEY_STEPS_FROZEN])
    }

    @Test
    fun zoneChangeFreezesStepsThroughTheDayOfChangeInNewZone() {
        // Смена Москва → Токио применена в 21:00 13 ноября по Москве = 03:00 14 ноября в Токио.
        val t = ms(2026, 11, 13, 21, 0)
        assertEquals(day(2026, 11, 14), TrustedClockMath.stepsFrozenThrough(null, t, tokyo))
        // Обратно: 03:00 14 ноября в Токио = 21:00 13 ноября по Москве.
        assertEquals(day(2026, 11, 13), TrustedClockMath.stepsFrozenThrough(null, t, moscow))
        // Отметка только растёт.
        assertEquals(day(2026, 11, 20), TrustedClockMath.stepsFrozenThrough(day(2026, 11, 20), t, tokyo))
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
        // block_events — шаг 3 → 4; более поздние шаги только дописываются после него (0.5 — ночной перерыв).
        assertTrue(Diary.SCHEMA_VERSION >= 4)
        val m = Diary.MIGRATIONS[3]
        val table = m.first { it.contains("CREATE TABLE block_events") }
        for (col in listOf("id INTEGER PRIMARY KEY", "utc_ms", "accounting_day", "package_name", "reason", "\"trigger\"")) {
            assertTrue("нет столбца $col", table.contains(col))
        }
    }
}
