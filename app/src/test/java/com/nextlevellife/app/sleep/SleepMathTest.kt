package com.nextlevellife.app.sleep

import com.nextlevellife.app.sleep.PhoneEventType.SCREEN_OFF
import com.nextlevellife.app.sleep.PhoneEventType.SCREEN_ON
import com.nextlevellife.app.sleep.PhoneEventType.STATE
import com.nextlevellife.app.sleep.PhoneEventType.USER_PRESENT
import com.nextlevellife.app.sleep.SleepMath.HOUR_MS
import com.nextlevellife.app.sleep.SleepMath.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepMathTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    /** День пробуждения: 13 ноября 2026. */
    private val wake = LocalDate.of(2026, 11, 13).toEpochDay()

    /** Ночной режим 23:30–07:00 (через полночь). */
    private val night = NightMode(23 * 60 + 30, 7 * 60)

    /** Момент «вечер накануне» (dayOffset = −1) или «утро дня пробуждения» (0) в поясе [zone]. */
    private fun at(dayOffset: Int, h: Int, m: Int = 0, s: Int = 0, zone: ZoneId = moscow): Long =
        LocalDate.ofEpochDay(wake + dayOffset).atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun eve(h: Int, m: Int = 0, s: Int = 0, zone: ZoneId = moscow) = at(-1, h, m, s, zone)
    private fun morn(h: Int, m: Int = 0, s: Int = 0, zone: ZoneId = moscow) = at(0, h, m, s, zone)

    private fun present(t: Long) = PhoneEvent(t, USER_PRESENT)
    private fun off(t: Long) = PhoneEvent(t, SCREEN_OFF)
    private fun onLocked(t: Long) = PhoneEvent(t, SCREEN_ON, interactive = true, locked = true)
    private fun onUnlocked(t: Long) = PhoneEvent(t, SCREEN_ON, interactive = true, locked = false)

    /** Обычный вечер: пользовался до 23:20; утро — с 07:00 до 07:30 и с 10:00 до 10:15. */
    private fun evening() = listOf(present(eve(22, 50)), off(eve(23, 20)))
    private fun morning() = listOf(present(morn(7)), off(morn(7, 30)), present(morn(10)), off(morn(10, 15)))

    private val afterNoon get() = morn(13)

    private fun compute(
        events: List<PhoneEvent>,
        gaps: List<ObservationGap> = emptyList(),
        now: Long = afterNoon,
        mode: NightMode? = night,
        seed: PhoneEvent? = off(eve(18)),
        zone: ZoneId = moscow,
        wakeDay: Long = wake,
    ) = SleepMath.computeNight(wakeDay, zone, seed, events, gaps, now, mode)

    // ---------- Обычные ночи ----------

    @Test
    fun ordinaryNight() {
        val n = compute(evening() + morning())
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertEquals(7 * HOUR_MS + 40 * MINUTE_MS, n.durationMs)
        assertEquals(0, n.interruptionCount)
        assertTrue("использования в окне 23:30–07:00 не было", n.phoneFree)
        assertTrue(n.final)
        assertEquals(night.startMin, n.nightStartMin)
        assertEquals(night.endMin, n.nightEndMin)
        assertEquals("Europe/Moscow", n.zoneId)
        assertEquals(SleepMath.RULE_VERSION, n.ruleVersion)
    }

    @Test
    fun oneShortInterruptionIsMergedButTakesTheBadge() {
        val check = listOf(present(morn(3)), off(morn(3, 1)))
        val n = compute(evening() + check + morning())
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertEquals(1, n.interruptionCount)
        assertEquals(MINUTE_MS, n.interruptionMs)
        assertEquals(7 * HOUR_MS + 39 * MINUTE_MS, n.durationMs)
        assertFalse("обращение внутри ночного режима убирает значок", n.phoneFree)
    }

    @Test
    fun fiveMinuteUseSplitsTheNightAndTheLongerPartWins() {
        val check = listOf(present(morn(3)), off(morn(3, 5)))
        val n = compute(evening() + check + morning())
        assertEquals(NightStatus.OK, n.status)
        // 23:20–03:00 = 3 ч 40 мин, 03:05–07:00 = 3 ч 55 мин.
        assertEquals(morn(3, 5), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertEquals(0, n.interruptionCount)
        assertEquals(3 * HOUR_MS + 55 * MINUTE_MS, n.durationMs)
        assertFalse(n.phoneFree)
    }

    @Test
    fun longerMorningCutByNoonMakesAFragmentedNightPartial() {
        // Ночь разорвана на 3 ч 40 мин и 3 ч 55 мин, а с 07:30 телефон не трогали до полудня (4 ч 30 мин):
        // утренний перерыв мог оказаться самым длинным — данных для выбора не хватает.
        val events = evening() + listOf(present(morn(3)), off(morn(3, 5)), present(morn(7)), off(morn(7, 30)))
        val n = compute(events)
        assertEquals(NightStatus.PARTIAL, n.status)
        assertEquals(morn(7, 30), n.startMs)
        assertEquals(SleepMath.windowEndMs(wake, moscow), n.endMs)
    }

    @Test
    fun totalInterruptionsAreLimitedToFiveMinutes() {
        val checks = listOf(
            present(morn(1)), off(morn(1, 2)),
            present(morn(3)), off(morn(3, 2)),
            present(morn(5)), off(morn(5, 2)),
        )
        val n = compute(evening() + checks + morning())
        assertEquals(NightStatus.OK, n.status)
        // Все три обращения — 6 минут, больше лимита. С 23:20 вмещаются два (до 05:00, 5 ч 36 мин),
        // с 01:02 — тоже два (до 07:00, 5 ч 54 мин): выигрывает второй.
        assertEquals(morn(1, 2), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertEquals(2, n.interruptionCount)
        assertEquals(4 * MINUTE_MS, n.interruptionMs)
        assertEquals(5 * HOUR_MS + 54 * MINUTE_MS, n.durationMs)
    }

    @Test
    fun equalCandidatesPickTheLaterOne() {
        // 21:00–00:30 и 00:40–04:10 — по 3 ч 30 мин; обращение 10 минут не объединяется.
        val events = listOf(
            present(eve(20, 30)), off(eve(21)),
            present(morn(0, 30)), off(morn(0, 40)),
            present(morn(4, 10)),
        )
        val n = compute(events, mode = null)
        assertEquals(NightStatus.OK, n.status)
        assertEquals(morn(0, 40), n.startMs)
        assertEquals(morn(4, 10), n.endMs)
    }

    // ---------- Длина кандидата ----------

    @Test
    fun breaksShorterThanThreeHoursAreNotANight() {
        // Пользовался каждые два часа по 10 минут.
        val events = mutableListOf(present(eve(22, 50)), off(eve(23, 20)))
        for (h in listOf(1, 3, 5, 7)) events += listOf(present(morn(h, 20)), off(morn(h, 30)))
        events += present(morn(9, 20))
        val n = compute(events)
        assertEquals(NightStatus.UNKNOWN, n.status)
        assertNull(n.startMs)
        assertNull(n.durationMs)
        assertFalse(n.phoneFree)
    }

    @Test
    fun breakLongerThanFourteenHoursIsNotANight() {
        // 20:30–10:45 — 14 ч 15 мин без телефона.
        val events = listOf(present(eve(20, 10)), off(eve(20, 30)), present(morn(10, 45)))
        val n = compute(events)
        assertEquals(NightStatus.UNKNOWN, n.status)
        assertNull(n.durationMs)
    }

    // ---------- Края окна и пропуски ----------

    @Test
    fun candidateCutByWindowStartIsPartial() {
        // Телефон отложен в 19:00 (до окна) и взят в 05:00.
        val events = listOf(present(morn(5)), off(morn(5, 30)), present(morn(6)))
        val n = compute(events, seed = off(eve(19)))
        assertEquals(NightStatus.PARTIAL, n.status)
        assertEquals(SleepMath.windowStartMs(wake, moscow), n.startMs)
        assertEquals(morn(5), n.endMs)
        assertFalse(n.phoneFree)
    }

    @Test
    fun candidateCutByWindowEndIsPartial() {
        val n = compute(evening(), now = morn(13))
        assertEquals(NightStatus.PARTIAL, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(SleepMath.windowEndMs(wake, moscow), n.endMs)
    }

    @Test
    fun observationGapInsideTheNightIsUnknown() {
        val gap = ObservationGap(morn(2), morn(3))
        val restart = PhoneEvent(morn(3), STATE, interactive = false, locked = true, source = "connect")
        val n = compute(evening() + restart + morning(), gaps = listOf(gap))
        assertEquals(NightStatus.UNKNOWN, n.status)
        assertNull(n.durationMs)
        assertFalse(n.phoneFree)
    }

    @Test
    fun gapOutsideTheNightDoesNotMatter() {
        // Служба перезапускалась днём накануне: к ночи это не относится.
        val gap = ObservationGap(eve(14), eve(14, 30))
        val n = compute(evening() + morning(), gaps = listOf(gap))
        assertEquals(NightStatus.OK, n.status)
        assertTrue(n.phoneFree)
    }

    @Test
    fun noEventsIsUnknownNotZero() {
        val n = compute(emptyList(), seed = null)
        assertEquals(NightStatus.UNKNOWN, n.status)
        assertNull(n.durationMs)
        assertFalse(n.phoneFree)
        // Начальное состояние известно, но в окне ни одного события — всё равно «нет данных».
        assertEquals(NightStatus.UNKNOWN, compute(emptyList(), seed = off(eve(18))).status)
    }

    @Test
    fun noInitialStateBeforeTheFirstEventIsUnknown() {
        // Приложение поставили в 02:00: что было с 20:00 — неизвестно.
        val connect = PhoneEvent(morn(2), STATE, interactive = true, locked = false, source = "connect")
        val n = compute(listOf(connect, off(morn(2, 5)), present(morn(7))), seed = null)
        // 02:05–07:00 подтверждён, но с 20:00 до 02:00 мог быть более длинный перерыв.
        assertEquals(NightStatus.UNKNOWN, n.status)
        assertNull(n.durationMs)
    }

    // ---------- Как определяется использование ----------

    @Test
    fun screenLitByNotificationWithoutUnlockIsNotUse() {
        val notification = listOf(onLocked(morn(3)), off(morn(3, 0, 10)))
        val n = compute(evening() + notification + morning())
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(0, n.interruptionCount)
        assertEquals(7 * HOUR_MS + 40 * MINUTE_MS, n.durationMs)
        assertTrue(n.phoneFree)
    }

    @Test
    fun alarmOnLockScreenThenUnlockLater() {
        // Будильник в 06:30 зажёг экран поверх блокировки, разблокировали в 07:00.
        val events = evening() + listOf(onLocked(morn(6, 30)), off(morn(6, 31)), onLocked(morn(6, 59)), present(morn(7)))
        val n = compute(events)
        assertEquals(morn(7), n.endMs)
        assertEquals(0, n.interruptionCount)
    }

    @Test
    fun phoneWithoutLockScreenScreenOnIsUse() {
        val events = listOf(
            onUnlocked(eve(22, 50)), off(eve(23, 20)),
            onUnlocked(morn(3)), off(morn(3, 1)),
            onUnlocked(morn(7)), off(morn(7, 30)),
        )
        val n = compute(events)
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertEquals(1, n.interruptionCount)
        assertFalse(n.phoneFree)
    }

    // ---------- Ночной режим и значок ----------

    @Test
    fun nightModeWithinTheDayAndUseBeforeIt() {
        // Ночной режим 01:00–06:00; обращение в 00:30 — до него.
        val check = listOf(present(morn(0, 30)), off(morn(0, 31)))
        val events = evening() + check + morning()
        val inside = compute(events, mode = NightMode(60, 6 * 60))
        assertEquals(NightStatus.OK, inside.status)
        assertEquals(1, inside.interruptionCount)
        assertTrue(inside.phoneFree)
        // С ночным режимом 23:30–07:00 то же обращение попадает внутрь.
        assertFalse(compute(events, mode = night).phoneFree)
    }

    @Test
    fun nightModeOffGivesNoBadge() {
        val n = compute(evening() + morning(), mode = null)
        assertEquals(NightStatus.OK, n.status)
        assertFalse(n.phoneFree)
        assertNull(n.nightStartMin)
        assertNull(n.nightEndMin)
    }

    @Test
    fun useInsideNightModeBeforeTheBreakTakesTheBadge() {
        // Ночной режим с 23:00, а телефон отложен в 23:20.
        val n = compute(evening() + morning(), mode = NightMode(23 * 60, 7 * 60))
        assertEquals(NightStatus.OK, n.status)
        assertFalse(n.phoneFree)
    }

    @Test
    fun shortBreakGivesNoBadge() {
        // 01:00–06:30 — 5 ч 30 мин: меньше 6 часов, значка нет.
        val events = listOf(present(morn(0, 30)), off(morn(1)), present(morn(6, 30)))
        val n = compute(events, mode = NightMode(2 * 60, 6 * 60))
        assertEquals(NightStatus.OK, n.status)
        assertFalse(n.phoneFree)
    }

    @Test
    fun nightModeIntervals() {
        val cross = SleepMath.nightModeInterval(wake, moscow, NightMode(23 * 60, 7 * 60))!!
        assertEquals(eve(23), cross.first)
        assertEquals(morn(7), cross.second)
        val morningOnly = SleepMath.nightModeInterval(wake, moscow, NightMode(60, 6 * 60))!!
        assertEquals(morn(1), morningOnly.first)
        assertEquals(morn(6), morningOnly.second)
        val eveningOnly = SleepMath.nightModeInterval(wake, moscow, NightMode(21 * 60, 23 * 60))!!
        assertEquals(eve(21), eveningOnly.first)
        assertNull(SleepMath.nightModeInterval(wake, moscow, NightMode(60, 60)))
    }

    // ---------- Пояс и время ----------

    @Test
    fun accountingZoneChangeKeepsTheBreak() {
        // Те же моменты, посчитанные в другом учётном поясе (+2 ч): окно сдвигается, перерыв тот же.
        val events = evening() + morning()
        val msk = compute(events, mode = null)
        val yekat = compute(events, mode = null, zone = ZoneId.of("Asia/Yekaterinburg"))
        assertEquals(NightStatus.OK, yekat.status)
        assertEquals(msk.startMs, yekat.startMs)
        assertEquals(msk.endMs, yekat.endMs)
        assertEquals(msk.durationMs, yekat.durationMs)
        assertEquals("Asia/Yekaterinburg", yekat.zoneId)
    }

    @Test
    fun sameLocalNightInAnotherZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val events = listOf(present(eve(22, 50, zone = tokyo)), off(eve(23, 20, zone = tokyo)), present(morn(7, zone = tokyo)))
        val n = compute(events, zone = tokyo, seed = off(eve(18, zone = tokyo)), mode = night)
        assertEquals(NightStatus.OK, n.status)
        assertEquals(7 * HOUR_MS + 40 * MINUTE_MS, n.durationMs)
        assertTrue(n.phoneFree)
    }

    @Test
    fun daylightSavingNightIsMeasuredInRealTime() {
        // Берлин, 25 октября 2026: в 03:00 часы переводятся на 02:00 — ночь на час длиннее.
        val berlin = ZoneId.of("Europe/Berlin")
        val wakeDst = LocalDate.of(2026, 10, 25).toEpochDay()
        fun t(day: Long, h: Int, m: Int = 0) = LocalDate.ofEpochDay(day).atTime(h, m).atZone(berlin).toInstant().toEpochMilli()
        val events = listOf(present(t(wakeDst - 1, 22, 50)), off(t(wakeDst - 1, 23, 20)), present(t(wakeDst, 7)))
        val n = SleepMath.computeNight(wakeDst, berlin, off(t(wakeDst - 1, 18)), events, emptyList(), t(wakeDst, 13), null)
        assertEquals(NightStatus.OK, n.status)
        assertEquals(8 * HOUR_MS + 40 * MINUTE_MS, n.durationMs)
    }

    @Test
    fun nightIsProvisionalBeforeNoon() {
        // 05:00, телефон с 23:20 не трогали: перерыв ещё идёт — неполные данные, не окончательно.
        val n = compute(evening(), now = morn(5))
        assertFalse(n.final)
        assertEquals(NightStatus.PARTIAL, n.status)
        assertEquals(morn(5), n.endMs)
        assertFalse(n.phoneFree)
        // 09:00, утром пользовался: перерыв уже подтверждён, но ночь пересчитается до 12:00.
        val morningView = compute(evening() + morning() + present(morn(9)), now = morn(9))
        assertFalse(morningView.final)
        assertEquals(NightStatus.OK, morningView.status)
        assertEquals(morn(7), morningView.endMs)
        assertTrue(morningView.phoneFree)
    }

    @Test
    fun finalWakeDays() {
        assertEquals(wake - 1, SleepMath.lastFinalWakeDay(morn(11, 59), moscow))
        assertEquals(wake, SleepMath.lastFinalWakeDay(morn(12), moscow))
        assertEquals(wake, SleepMath.firstWakeDay(morn(2), moscow))
        assertEquals(wake + 1, SleepMath.firstWakeDay(morn(15), moscow))
        assertTrue(SleepMath.isFinal(wake, morn(12), moscow))
        assertFalse(SleepMath.isFinal(wake, morn(11, 59), moscow))
    }

    // ---------- Пропуск наблюдения при подключении службы ----------

    @Test
    fun connectGapRules() {
        val connect = morn(7)
        // Первое подключение — не пропуск.
        assertNull(SleepMath.connectGap(emptyList(), connect, 5))
        // Короткий перезапуск — не пропуск.
        assertNull(SleepMath.connectGap(listOf(present(morn(6, 59)).copy(bootCount = 5)), connect, 5))
        // Экран был включён и разблокирован — пропуск.
        val gapOn = SleepMath.connectGap(listOf(present(morn(3)).copy(bootCount = 5)), connect, 5)
        assertNotNull(gapOn)
        assertEquals(morn(3), gapOn!!.startMs)
        assertEquals(connect, gapOn.endMs)
        // Состояние неизвестно — пропуск.
        assertNotNull(SleepMath.connectGap(listOf(PhoneEvent(morn(3), STATE, bootCount = 5)), connect, 5))
        // Экран выключен, та же загрузка: служба не работала, что было — неизвестно.
        assertNotNull(SleepMath.connectGap(listOf(off(morn(3)).copy(bootCount = 5)), connect, 5))
        // Выключили посреди использования — пропуск.
        assertNotNull(SleepMath.connectGap(listOf(present(morn(3)).copy(bootCount = 5)), connect, 6))
    }

    // ---------- Пульс и остановка службы ----------

    private fun beat(t: Long, boot: Int? = 5) = PhoneEvent(t, PhoneEventType.HEARTBEAT, bootCount = boot, source = "service")
    private fun stop(t: Long, boot: Int? = 5) = PhoneEvent(t, PhoneEventType.SERVICE_STOP, bootCount = boot, source = "service")
    private fun shutdown(t: Long, boot: Int = 5) = PhoneEvent(t, SCREEN_OFF, interactive = false, bootCount = boot, source = SleepMath.SOURCE_SHUTDOWN)

    @Test
    fun gapCountsFromLastHeartbeatNotFromLastScreenEvent() {
        // Экран выключен в 23:20, дальше только пульс; служба убита без onDestroy после пульса 02:55.
        val recent = listOf(off(eve(23, 20)).copy(bootCount = 5), beat(morn(2, 50)), beat(morn(2, 55)))
        val gap = SleepMath.connectGap(recent, morn(3), 5)
        assertNotNull(gap)
        assertEquals("пропуск — от последнего пульса", morn(2, 55), gap!!.startMs)
        // Подключение через минуту после пульса — не пропуск.
        assertNull(SleepMath.connectGap(recent, morn(2, 56), 5))
    }

    @Test
    fun oneSecondServiceStopAtThreeDoesNotSpoilTheNight() {
        // Служба остановлена в 03:00:00 при выключенном экране и подключилась через секунду.
        val beforeStop = evening() + listOf(beat(eve(23, 25)), beat(morn(2, 55)), stop(morn(3)))
        val recent = beforeStop.map { it.copy(bootCount = 5) }
        assertNull("пропуска нет: остановка записана", SleepMath.connectGap(recent, morn(3, 0, 1), 5))
        val connectState = PhoneEvent(morn(3, 0, 1), STATE, interactive = false, locked = true, bootCount = 5, source = "connect")
        val n = compute(beforeStop + connectState + morning())
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(23, 20), n.startMs)
        assertEquals(morn(7), n.endMs)
        assertTrue(n.phoneFree)
    }

    @Test
    fun heartbeatsKeepPhaseAndAreNotScreenEvents() {
        // Пульс внутри перерыва состояние не меняет: ночь та же, что и без него.
        val beats = (0 until 8).map { beat(morn(it)) }
        assertEquals(compute(evening() + morning()), compute(evening() + beats + morning()))
        // Только пульс в окне, ни одного события экрана — «нет данных», как и без пульса.
        assertEquals(NightStatus.UNKNOWN, compute(beats).status)
        assertEquals(SleepMath.Phase.USE, SleepMath.phaseAfter(SleepMath.Phase.USE, beat(0)))
        assertEquals(SleepMath.Phase.IDLE, SleepMath.phaseAfter(SleepMath.Phase.IDLE, stop(0)))
    }

    // ---------- Перезагрузка ----------

    @Test
    fun confirmedShutdownHidesTheRebootGap() {
        val recent = listOf(off(eve(23, 20)).copy(bootCount = 5), beat(morn(2, 55)), shutdown(morn(3)))
        assertNull(SleepMath.connectGap(recent, morn(6), 6))
        // Остановка службы и пульс сразу после выключения (система гасит процессы) подтверждения не снимают.
        assertNull(SleepMath.connectGap(recent + stop(morn(3, 0, 5)) + beat(morn(3, 0, 10)), morn(6), 6))
    }

    @Test
    fun rebootWithoutConfirmedShutdownIsAGap() {
        // Выключение не записано (сбой, села батарея): от последнего наблюдения до подключения неизвестно.
        val noShutdown = listOf(off(eve(23, 20)).copy(bootCount = 5), beat(morn(2, 55)))
        val gap = SleepMath.connectGap(noShutdown, morn(6), 6)
        assertNotNull(gap)
        assertEquals(morn(2, 55), gap!!.startMs)
        assertEquals(morn(6), gap.endMs)
        // Выключение записано, но после него в той же загрузке были события экрана — не подтверждено.
        val shutdownThenUse = listOf(shutdown(morn(3)), present(morn(3, 1)).copy(bootCount = 5), off(morn(3, 2)).copy(bootCount = 5))
        val gap2 = SleepMath.connectGap(shutdownThenUse, morn(6), 6)
        assertNotNull(gap2)
        assertEquals(morn(3, 2), gap2!!.startMs)
        // Пульс через 10 минут после «выключения» — телефон работал дальше: не подтверждено.
        assertNotNull(SleepMath.connectGap(listOf(shutdown(morn(3)), beat(morn(3, 10))), morn(6), 6))
        // Выключение было в прошлой загрузке, а не в последней — не подтверждено.
        val oldShutdown = listOf(shutdown(eve(22), boot = 4), off(eve(23, 20)).copy(bootCount = 5))
        assertNotNull(SleepMath.connectGap(oldShutdown, morn(6), 6))
        // Номер загрузки неизвестен — перезагрузку не видно: пропуск.
        assertNotNull(SleepMath.connectGap(listOf(shutdown(morn(3)).copy(bootCount = null)), morn(6), null))
    }

    // ---------- Склейка через короткие обращения ----------

    @Test
    fun keepsShorterCandidateWhenMergeExceedsFourteenHours() {
        // Перерыв 20:30–10:30 (ровно 14 ч), обращение 10:30–10:31, перерыв до 11:30:
        // склейка дала бы 14 ч 59 мин (больше предела), но исходные 14 ч — годный кандидат.
        val events = listOf(
            present(eve(20)), off(eve(20, 30)),
            present(morn(10, 30)), off(morn(10, 31)),
            present(morn(11, 30)), off(morn(11, 45)),
        )
        val n = compute(events, mode = null)
        assertEquals(NightStatus.OK, n.status)
        assertEquals(eve(20, 30), n.startMs)
        assertEquals(morn(10, 30), n.endMs)
        assertEquals(14 * HOUR_MS, n.durationMs)
        assertEquals(0, n.interruptionCount)
    }

    @Test
    fun candidatesIncludeEveryMergePrefix() {
        val segs = SleepMath.timeline(
            off(eve(18)),
            listOf(present(eve(20)), off(eve(20, 30)), present(morn(3)), off(morn(3, 1)), present(morn(7)), off(morn(7, 30))),
            emptyList(), eve(20), morn(12),
        )
        val fromEvening = SleepMath.candidates(segs, unknownIsFree = false).filter { it.startMs == eve(20, 30) }
        assertEquals(listOf(morn(3), morn(7)), fromEvening.map { it.endMs })
        assertEquals(listOf(0, 1), fromEvening.map { it.interruptionCount })
    }

    // ---------- Окончательные итоги ----------

    @Test
    fun lateEventInvalidatesClosedNights() {
        val today = wake
        // Событие «сейчас» ничего не трогает.
        assertNull(SleepMath.invalidatedFromDay(morn(13), morn(13), moscow, today))
        // Событие 11:59 записано в 12:00:30 — ночь уже окончательная: её итог надо пересчитать.
        assertEquals(wake, SleepMath.invalidatedFromDay(morn(11, 59), morn(12, 0, 30), moscow, today))
        // Пропуск, начавшийся вчера в 03:00 и записанный сегодня в 13:00, — пересчёт со вчерашней ночи.
        assertEquals(wake - 1, SleepMath.invalidatedFromDay(at(-1, 3), morn(13), moscow, today))
        // Событие до 12:00 записано до 12:00 — ночь ещё предварительная, удалять нечего.
        assertNull(SleepMath.invalidatedFromDay(morn(11), morn(11, 30), moscow, today))
        // Совсем старые ночи (сырых данных уже нет) не трогаются.
        assertEquals(
            SleepMath.oldestRecomputableDay(today),
            SleepMath.invalidatedFromDay(at(-200, 3), morn(13), moscow, today),
        )
    }

    @Test
    fun nightsToFinalizeAndPruneCutoff() {
        val today = wake
        val stored = mapOf(wake - 3 to SleepMath.RULE_VERSION, wake - 2 to 1, wake - 200 to 1)
        assertEquals(listOf(wake - 2, wake - 1), SleepMath.nightsToFinalize(wake - 3, wake - 1, stored, today))
        // Старое правило, но сырых данных уже нет — не пересчитывается.
        assertEquals(emptyList<Long>(), SleepMath.nightsToFinalize(wake - 200, wake - 200, stored, today))
        // Ночи нет в базе — считается, даже старая (её события чистка не удаляет).
        assertEquals(listOf(wake - 150), SleepMath.nightsToFinalize(wake - 150, wake - 150, stored, today))

        val now = morn(13)
        // Все ночи записаны — граница 90 дней.
        assertEquals(now - 90 * SleepMath.DAY_MS, SleepMath.pruneCutoffMs(now, moscow, wake + 1))
        // Ночь 100 дней назад не записана — её события не чистятся.
        val cutoff = SleepMath.pruneCutoffMs(now, moscow, wake - 100)
        assertTrue(cutoff <= SleepMath.dataRange(wake - 100, moscow, night).first)
        assertTrue(cutoff <= SleepMath.windowStartMs(wake - 100, moscow))
    }

    @Test
    fun computeFromDataAddsGapWhenNotObserving() {
        val events = evening() + morning()
        val data = NightData(off(eve(18)), events, emptyList(), lastEventMs = morn(10, 15))
        assertEquals(NightStatus.OK, SleepMath.computeFromData(wake, moscow, data, morn(13), night, observing = true).status)
        // Утром служба не работает, последнее событие 05:00: что дальше — неизвестно.
        val early = NightData(off(eve(18)), evening(), emptyList(), lastEventMs = morn(5))
        assertEquals(NightStatus.UNKNOWN, SleepMath.computeFromData(wake, moscow, early, morn(11), night, observing = false).status)
    }

    @Test
    fun timelineMergesAndOverlaysGaps() {
        val from = eve(20)
        val to = morn(12)
        val segs = SleepMath.timeline(
            off(eve(18)),
            listOf(present(eve(22)), off(eve(23)), off(eve(23, 30))),
            listOf(ObservationGap(morn(1), morn(2))),
            from, to,
        )
        assertEquals(
            listOf(SleepMath.Phase.IDLE, SleepMath.Phase.USE, SleepMath.Phase.IDLE, SleepMath.Phase.UNKNOWN, SleepMath.Phase.IDLE),
            segs.map { it.phase },
        )
        assertEquals(from, segs.first().startMs)
        assertEquals(to, segs.last().endMs)
        assertEquals(morn(1), segs[3].startMs)
        assertEquals(morn(2), segs[3].endMs)
    }
}
