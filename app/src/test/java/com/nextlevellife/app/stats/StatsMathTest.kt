package com.nextlevellife.app.stats

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.BlockEvent
import com.nextlevellife.app.data.ComplexRun
import com.nextlevellife.app.data.Measurement
import com.nextlevellife.app.data.SetEntry
import com.nextlevellife.app.sleep.NightStatus
import com.nextlevellife.app.sleep.ObservationGap
import com.nextlevellife.app.sleep.SleepNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsMathTest {

    private fun strength(reps: Int = 0, heldSec: Long = 0, complexes: Int = 0) =
        StatsMath.strength(StrengthInput(reps, heldSec * 1000, complexes))

    private fun endurance(steps: Long? = null, runSteps: Long = 0, runM: Long = 0, complexMs: Long = 0) =
        StatsMath.endurance(EnduranceInput(steps, runSteps, runM, complexMs))

    // ---------- Сила ----------

    @Test
    fun strengthRepsBoundaries() {
        assertEquals(0, strength(reps = 9).points)
        assertEquals(1, strength(reps = 10).points)
        assertEquals(1, strength(reps = 11).points)
        assertEquals(7, strength(reps = 70).points)
    }

    @Test
    fun strengthRepsCapIsEight() {
        assertEquals(8, strength(reps = 80).points)
        val s = strength(reps = 150)
        assertEquals(8, s.points)
        val src = s.sources.single()
        assertEquals(SourceKind.REPS, src.kind)
        assertEquals(15, src.raw)
        assertTrue(src.capped)
    }

    @Test
    fun strengthPlankBoundaries() {
        assertEquals(0, strength(heldSec = 29).points)
        assertEquals(1, strength(heldSec = 30).points)
        assertEquals(1, strength(heldSec = 59).points)
        assertEquals(2, strength(heldSec = 60).points)
        // Миллисекунды до целой секунды не засчитываются.
        assertEquals(0, StatsMath.strength(StrengthInput(0, 29_999, 0)).points)
    }

    @Test
    fun strengthRepsAndPlankShareCapOfEight() {
        val s = strength(reps = 70, heldSec = 90)
        assertEquals(8, s.points)
        assertEquals(listOf(7, 1), s.sources.map { it.points })
        assertTrue(s.sources[1].capped)
    }

    @Test
    fun complexBonusAddsTwoOverWorkCap() {
        assertEquals(2, strength(complexes = 1).points)
        assertEquals(2, strength(complexes = 3).points)
        assertEquals(9, strength(reps = 70, complexes = 1).points)
        // Потолок 8 за работу + 2 за комплекс = 10, больше не бывает.
        assertEquals(10, strength(reps = 275, heldSec = 600, complexes = 2).points)
        assertEquals(StatsMath.DAY_CAP, strength(reps = 10_000, heldSec = 10_000, complexes = 5).points)
    }

    @Test
    fun strengthEmptyDay() {
        val s = strength()
        assertEquals(0, s.points)
        assertTrue(s.sources.isEmpty())
    }

    @Test
    fun strengthInputCountsComplexSetsOnceAndPlankSeparately() {
        val sets = listOf(
            set(1, reps = 15),
            set(2, reps = 55, runId = 7), // подход комплекса
            set(3, reps = 0, measurement = Measurement.HOLD, heldMs = 80_000),
        )
        val runs = listOf(run(7, totalReps = 275, completed = true, durationMs = 25 * 60_000L))
        val input = StatsMath.strengthInput(sets, runs)
        // total_reps прохождения не добавляется: его повторения уже в подходах.
        assertEquals(70, input.reps)
        assertEquals(80_000L, input.heldMs)
        assertEquals(1, input.completedComplexes)
        assertEquals(0, StatsMath.strengthInput(sets, listOf(run(7, completed = false))).completedComplexes)
    }

    // ---------- Выносливость ----------

    @Test
    fun walkingStepsUnknownIsZeroNotPenalty() {
        assertEquals(0, endurance(steps = null).points)
        assertTrue(endurance(steps = null).sources.isEmpty())
        assertEquals(0, endurance(steps = 0).points)
        assertEquals(0, endurance(steps = 1999).points)
        assertEquals(1, endurance(steps = 2000).points)
        assertEquals(4, endurance(steps = 8400).points)
    }

    @Test
    fun runStepsAreNotWalking() {
        // 10 000 шагов, из них 6 000 — бег: ходьба 4 000 → 2, бег 5,2 км → 5.
        val e = endurance(steps = 10_000, runSteps = 6_000, runM = 5_200)
        assertEquals(7, e.points)
        assertEquals(listOf(SourceKind.WALK to 2, SourceKind.RUN to 5), e.sources.map { it.kind to it.points })
        assertEquals(4_000L, e.sources[0].amount)
        // Шагов бега больше, чем всех шагов (данные из разных источников) — ходьба 0, не минус.
        assertEquals(0L, StatsMath.walkSteps(3_000, 5_000))
        assertEquals(0L, StatsMath.walkSteps(null, 5_000))
    }

    @Test
    fun runMetersBoundaries() {
        assertEquals(0, endurance(runM = 999).points)
        assertEquals(1, endurance(runM = 1000).points)
        assertEquals(10, endurance(runM = 21_100).points)
    }

    @Test
    fun complexTimeBoundariesAndOwnCap() {
        assertEquals(0, endurance(complexMs = 4 * 60_000L).points)
        assertEquals(1, endurance(complexMs = 5 * 60_000L).points)
        val long = endurance(complexMs = 25 * 60_000L)
        assertEquals(4, long.points)
        assertEquals(5, long.sources.single().raw)
        assertTrue(long.sources.single().capped)
    }

    @Test
    fun enduranceCapIsTen() {
        val e = endurance(steps = 16_000, runM = 3_000, complexMs = 20 * 60_000L)
        // 8 + 3 + 4 = 15 → 10: ходьба 8, бег 2, комплексы 0.
        assertEquals(10, e.points)
        assertEquals(listOf(8, 2, 0), e.sources.map { it.points })
    }

    @Test
    fun enduranceInputUsesOnlyCompletedComplexes() {
        val day = ActivityDay(1, 9_000, 3_000, 900_000, 4_000, 1, emptyList(), 0, 0)
        val runs = listOf(
            run(1, completed = true, durationMs = 20 * 60_000L),
            run(2, completed = false, durationMs = 7 * 60_000L),
        )
        val input = StatsMath.enduranceInput(day, runs)
        assertEquals(EnduranceInput(9_000, 4_000, 3_000, 20 * 60_000L), input)
        assertEquals(EnduranceInput(null, 0, 0, 0), StatsMath.enduranceInput(null, emptyList()))
    }

    // ---------- Дисциплина ----------

    private val fullDay = DisciplineInput(
        dayEnded = true, nightKnown = true, phoneFree = true, nightModeOn = true, nightEnded = true,
        nightObserved = true, nightBlocks = 0, limitMin = 120, spentMs = 60 * 60_000L, dayObserved = true,
    )

    @Test
    fun disciplineFullDayIsTen() {
        val d = StatsMath.discipline(fullDay)
        assertEquals(10, d.points)
        assertEquals(
            listOf(SourceKind.PHONE_FREE_NIGHT, SourceKind.NIGHT_NO_BLOCKS, SourceKind.LIMIT_KEPT),
            d.sources.map { it.kind },
        )
        assertEquals(50L, d.sources.last().amount) // 60 из 120 минут — 50 %
    }

    @Test
    fun phoneFreeNightGivesFour() {
        assertEquals(4, StatsMath.discipline(DisciplineInput(dayEnded = false, nightKnown = true, phoneFree = true)).points)
        assertEquals(0, StatsMath.discipline(DisciplineInput(dayEnded = true, nightKnown = true, phoneFree = false)).points)
        assertEquals(0, StatsMath.discipline(DisciplineInput(dayEnded = true, nightKnown = false, phoneFree = true)).points)
    }

    @Test
    fun limitEightyPercentIsKeptEightyOneIsNot() {
        val limit = 100 // минут
        fun pts(spentMs: Long) = StatsMath.discipline(
            DisciplineInput(dayEnded = true, limitMin = limit, spentMs = spentMs, dayObserved = true),
        ).points
        assertEquals(3, pts(0))
        assertEquals(3, pts(80 * 60_000L))
        assertEquals(0, pts(80 * 60_000L + 1))
        assertEquals(0, pts(81 * 60_000L))
        assertTrue(StatsMath.limitKept(80 * 60_000L, 100))
        assertFalse(StatsMath.limitKept(81 * 60_000L, 100))
        assertEquals(60L, StatsMath.limitPercent(72 * 60_000L, 120))
    }

    @Test
    fun limitOffGivesNothing() {
        assertEquals(0, StatsMath.discipline(DisciplineInput(dayEnded = true, limitMin = 0, spentMs = 0, dayObserved = true)).points)
        assertFalse(StatsMath.limitKept(0, 0))
    }

    @Test
    fun observationGapMeansNoLimitPoints() {
        assertEquals(0, StatsMath.discipline(fullDay.copy(nightKnown = false, dayObserved = false)).points)
        assertEquals(7, StatsMath.discipline(fullDay.copy(dayObserved = false)).points)
    }

    @Test
    fun limitOnlyAfterDayEnds() {
        // Сегодня: только ночная часть.
        assertEquals(7, StatsMath.discipline(fullDay.copy(dayEnded = false)).points)
    }

    @Test
    fun nightBlockRemovesThree() {
        val d = StatsMath.discipline(fullDay.copy(nightBlocks = 1))
        assertEquals(7, d.points)
        assertTrue(d.sources.none { it.kind == SourceKind.NIGHT_NO_BLOCKS })
    }

    @Test
    fun nightModeOffOrUnobservedOrNotEndedGivesNoNightThree() {
        assertEquals(7, StatsMath.discipline(fullDay.copy(nightModeOn = false)).points)
        assertEquals(7, StatsMath.discipline(fullDay.copy(nightObserved = false)).points)
        assertEquals(7, StatsMath.discipline(fullDay.copy(nightEnded = false)).points)
    }

    // ---------- Сбор дисциплины из данных ----------

    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 11, 13).toEpochDay()
    private fun at(day: Long, h: Int, m: Int = 0) =
        LocalDate.ofEpochDay(day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val dayStart = at(day, 0)
    private val dayEnd = at(day + 1, 0)

    private fun night(phoneFree: Boolean = true, start: Int? = 23 * 60, end: Int? = 7 * 60) = SleepNight(
        wakeDay = day, status = NightStatus.OK, startMs = at(day - 1, 23), endMs = at(day, 7),
        interruptionMs = 0, interruptionCount = 0, phoneFree = phoneFree,
        nightStartMin = start, nightEndMin = end, zoneId = zone.id,
    )

    private fun input(
        now: Long = at(day + 1, 10),
        night: SleepNight? = night(),
        blocks: List<BlockEvent> = emptyList(),
        gaps: List<ObservationGap> = emptyList(),
        since: Long? = at(day - 30, 0),
        limit: Int = 120,
        spent: Long = 0,
    ) = StatsMath.disciplineInput(day, now, dayStart, dayEnd, night, blocks, gaps, since, limit, spent)

    private fun block(utc: Long, reason: String = "NIGHT") = BlockEvent(1, utc, day, "app", reason, "open")

    @Test
    fun disciplineInputFullPastDay() {
        val i = input()
        assertTrue(i.dayEnded && i.nightKnown && i.phoneFree && i.nightModeOn && i.nightEnded && i.nightObserved && i.dayObserved)
        assertEquals(0, i.nightBlocks)
        assertEquals(10, StatsMath.discipline(i).points)
    }

    @Test
    fun disciplineInputCountsOnlyNightBlocksInsideNightWindow() {
        val blocks = listOf(
            block(at(day - 1, 23, 30)), // ночью — считается
            block(at(day - 1, 22, 0)), // до начала ночи
            block(at(day, 7, 0)), // конец окна не входит
            block(at(day, 2, 0), reason = "LIMIT"), // не ночная причина
        )
        assertEquals(1, input(blocks = blocks).nightBlocks)
        assertEquals(0, input(blocks = blocks.drop(1)).nightBlocks)
    }

    @Test
    fun disciplineInputGapInsideDayOrNight() {
        val dayGap = input(gaps = listOf(ObservationGap(at(day, 14), at(day, 15))))
        assertFalse(dayGap.dayObserved)
        assertTrue(dayGap.nightObserved)
        val nightGap = input(gaps = listOf(ObservationGap(at(day - 1, 23, 50), at(day, 0, 30))))
        assertFalse(nightGap.nightObserved)
        assertTrue(nightGap.dayObserved.not()) // пропуск заходит в сутки (00:00–00:30)
        // Пропуск вне суток и ночи ничего не портит.
        val outside = input(gaps = listOf(ObservationGap(at(day - 1, 12), at(day - 1, 13))))
        assertTrue(outside.dayObserved && outside.nightObserved)
    }

    @Test
    fun disciplineInputNeedsObservationFromStart() {
        // Наблюдение началось посреди суток — сутки не полные; ночь до этого — тоже.
        val i = input(since = at(day, 9))
        assertFalse(i.dayObserved)
        assertFalse(i.nightObserved)
        assertEquals(4, StatsMath.discipline(i).points) // только «Ночь без телефона»
        assertFalse(input(since = null).dayObserved)
    }

    @Test
    fun disciplineInputNightModeOff() {
        val i = input(night = night(start = null, end = null))
        assertFalse(i.nightModeOn)
        assertFalse(i.nightEnded)
        // Без ночного режима нет +3 за ночь; «Ночь без телефона» и лимит остаются.
        assertEquals(StatsMath.LIMIT_POINTS + StatsMath.PHONE_FREE_POINTS, StatsMath.discipline(i).points)
    }

    @Test
    fun disciplineTodayBeforeAndAfterNightEnds() {
        // 06:00 — ночь ещё идёт: ни +3 за ночь, ни лимита.
        val early = input(now = at(day, 6), night = night(phoneFree = false))
        assertFalse(early.dayEnded)
        assertFalse(early.nightEnded)
        assertEquals(0, StatsMath.discipline(early).points)
        // 09:00 — ночь закончилась: +3 за ночь без блокировок (+4, если ночь без телефона); лимит — после суток.
        val morning = input(now = at(day, 9))
        assertTrue(morning.nightEnded && morning.nightObserved)
        assertEquals(7, StatsMath.discipline(morning).points)
    }

    @Test
    fun disciplineNoNightRecordIsZeroNotPenalty() {
        val i = input(night = null, limit = 0)
        assertEquals(0, StatsMath.discipline(i).points)
    }

    // ---------- Уровни ----------

    @Test
    fun levelBoundaries() {
        assertEquals(1, StatsMath.levelFor(0))
        assertEquals(1, StatsMath.levelFor(49))
        assertEquals(2, StatsMath.levelFor(50))
        assertEquals(2, StatsMath.levelFor(199))
        assertEquals(3, StatsMath.levelFor(200))
        assertEquals(1, StatsMath.levelFor(-5))
        assertEquals(0L, StatsMath.pointsForLevel(1))
        assertEquals(50L, StatsMath.pointsForLevel(2))
        assertEquals(200L, StatsMath.pointsForLevel(3))
        assertEquals(450L, StatsMath.pointsForLevel(4))
    }

    @Test
    fun levelProgressToNext() {
        val p = StatsMath.progress(125)
        assertEquals(2, p.level)
        assertEquals(50L, p.from)
        assertEquals(200L, p.to)
        assertEquals(75L, p.left)
        assertEquals(0.5f, p.fraction, 0.0001f)
        assertEquals(0f, StatsMath.progress(0).fraction, 0f)
    }

    @Test
    fun overallLevelIsSumOfThree() {
        val summary = StatsSummary(day, strength = 120, endurance = 60, discipline = 30, days = emptyMap(), legacyXp = 500)
        assertEquals(210L, summary.overall)
        assertEquals(3, summary.overallLevel)
        // Каждая характеристика отдельно — уровень 2 и 2 и 1; общий — по сумме.
        assertEquals(listOf(2, 2, 1), Stat.entries.map { StatsMath.levelFor(summary.total(it)) })
        assertEquals(1, StatsSummary.empty(day).overallLevel)
    }

    // ---------- День и кеш ----------

    @Test
    fun dayPointsCombineAll() {
        val p = StatsMath.day(
            day,
            StrengthInput(70, 0, 1),
            EnduranceInput(8_400, 0, 0, 0),
            fullDay,
        )
        assertEquals(9, p.strength)
        assertEquals(4, p.endurance)
        assertEquals(10, p.discipline)
        assertEquals(23, p.total)
        assertEquals(listOf(SourceKind.REPS, SourceKind.COMPLEX_DONE), p.sourcesOf(Stat.STRENGTH).map { it.kind })
    }

    @Test
    fun cacheRoundTripAndVersion() {
        val p = StatsMath.day(day, StrengthInput(150, 45_000, 1), EnduranceInput(5_000, 0, 1_200, 26 * 60_000L), fullDay)
        val s = StatsMath.encode(p)
        assertEquals(p, StatsMath.decode(day, s))
        // Другая версия правил — кеш не читается.
        assertNull(StatsMath.decode(day, s, ruleVersion = StatsMath.RULE_VERSION + 1))
        assertNull(StatsMath.decode(day, "мусор"))
        assertNull(StatsMath.decode(day, "1|1|2|3|NOPE:1:1:1"))
        val empty = DayPoints.empty(day)
        assertEquals(empty, StatsMath.decode(day, StatsMath.encode(empty)))
    }

    // ---------- Подписи ----------

    @Test
    fun texts() {
        val nb = ' '
        assertEquals("70${nb}повторений → 7", StatsText.sourceLine(PointSource(SourceKind.REPS, 70, 7)))
        assertEquals("завершён комплекс → 2", StatsText.sourceLine(PointSource(SourceKind.COMPLEX_DONE, 1, 2)))
        assertEquals("Ночь без телефона → 4", StatsText.sourceLine(PointSource(SourceKind.PHONE_FREE_NIGHT, 1, 4)))
        assertEquals("расход 60$nb% лимита → 3", StatsText.sourceLine(PointSource(SourceKind.LIMIT_KEPT, 60, 3)))
        assertEquals("планка 1:30 → 1 (потолок)", StatsText.sourceLine(PointSource(SourceKind.PLANK, 90, 1, 3)))
        assertEquals("комплексы 25 мин → 4 (потолок)", StatsText.sourceLine(PointSource(SourceKind.COMPLEX_TIME, 25 * 60_000L, 4, 5)))
        assertEquals(
            "Сила$nb+7 · Выносливость$nb+3 · Дисциплина$nb+4",
            StatsText.dayLine(DayPoints(day, 7, 3, 4, emptyList())),
        )
        assertEquals("Сила${nb}2", StatsText.statLevel(Stat.STRENGTH, 87))
        assertEquals("1${nb}очко", StatsText.pointsLabel(1))
        assertEquals("87${nb}очков", StatsText.pointsLabel(87))
        assertEquals("Опыт до обновления: 120", StatsText.legacyXpLine(120))
    }

    @Test
    fun strengthResultLine() {
        assertEquals("+3 силы (сегодня 7/10)", StatsText.strengthResult(3, 7))
        assertEquals("сила за сегодня уже 10/10", StatsText.strengthResult(0, 10))
        assertEquals("сила сегодня 4/10", StatsText.strengthResult(0, 4))
        assertNull(StatsText.strengthResult(null, 4))
        assertNull(StatsText.strengthResult(2, null))
    }

    // ---------- Данные ----------

    private fun set(
        id: Long, reps: Int, runId: Long? = null,
        measurement: Measurement = Measurement.REPS, heldMs: Long = 0L,
    ) = SetEntry(id, 0L, day, "pushups", "Отжимания", null, reps, 0L, 0L, 0L, runId, measurement, heldMs)

    private fun run(id: Long, totalReps: Int = 0, completed: Boolean = true, durationMs: Long = 0L) =
        ComplexRun(id, 0L, day, "minotaur", 16, durationMs, null, null, totalReps, 0L, 0L, completed)
}
