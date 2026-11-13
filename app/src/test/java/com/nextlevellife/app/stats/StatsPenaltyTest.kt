package com.nextlevellife.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пороги силы (полный подход), штрафы за простой, итоги без минуса и тексты «Как получить». */
class StatsPenaltyTest {
    private val start = 20_000L

    /** Дни подряд с [start]: в каждом — сила, выносливость, дисциплина. */
    private fun days(vararg pts: Triple<Int, Int, Int>, provisional: Set<Int> = emptySet()): Map<Long, DayPoints> =
        pts.mapIndexed { i, (s, e, d) ->
            val day = start + i
            day to DayPoints(day, s, e, d, emptyList(), provisional = i in provisional)
        }.toMap()

    private fun idle(n: Int) = Array(n) { Triple(0, 0, 0) }

    @Test
    fun repsCountOnlyWithFullSet() {
        // 3 подхода по 8 = 24 повторения, полного подхода нет — 0.
        val noFull = StatsMath.strength(StrengthInput(24, 0, 0, bestSet = 8))
        assertEquals(0, noFull.points)
        assertEquals(0, noFull.sources.single().raw)
        // Один подход от 10 — засчитываются все повторения дня.
        assertEquals(2, StatsMath.strength(StrengthInput(24, 0, 0, bestSet = 10)).points)
        // Завершённый комплекс тоже открывает повторения: 2 + 2.
        assertEquals(4, StatsMath.strength(StrengthInput(24, 0, 1, bestSet = 5)).points)
    }

    @Test
    fun penaltyLadder() {
        val s = StatsMath.STRENGTH_FREE_IDLE_DAYS
        assertEquals(listOf(0, 0, 0, -1, -2, -3, -3), (1..7).map { StatsMath.idlePenalty(it, s) })
        val d = StatsMath.DISCIPLINE_FREE_IDLE_DAYS
        assertEquals(listOf(0, 0, -1, -2, -3), (1..5).map { StatsMath.idlePenalty(it, d) })
    }

    @Test
    fun strengthPenaltyStartsOnFourthIdleDayAndResets() {
        // Тренировка, 5 дней без силы, тренировка, 1 день без. Дисциплина всё время есть.
        val input = days(
            Triple(5, 0, 3),
            Triple(0, 0, 3), Triple(0, 0, 3), Triple(0, 0, 3), Triple(0, 0, 3), Triple(0, 0, 3),
            Triple(4, 0, 3),
            Triple(0, 0, 3),
        )
        val today = start + input.size // все дни завершены
        val p = StatsMath.withPenalties(input, start, today).days
        assertEquals(listOf(5, 0, 0, 0, -1, -2, 4, 0), (0 until input.size).map { p.getValue(start + it).strength })
        val src = p.getValue(start + 4).sources.single()
        assertEquals(SourceKind.STRENGTH_IDLE, src.kind)
        assertEquals(4L, src.amount)
        assertEquals(-1, src.points)
        // Выносливость не штрафуется, дисциплина была — без штрафа.
        assertTrue(p.values.all { it.endurance == 0 && it.discipline == 3 })
    }

    @Test
    fun disciplinePenaltyAfterTwoFreeDays() {
        val input = days(Triple(3, 0, 0), Triple(3, 0, 0), Triple(3, 0, 0), Triple(3, 0, 0))
        val p = StatsMath.withPenalties(input, start, start + 4).days
        assertEquals(listOf(0, 0, -1, -2), (0 until 4).map { p.getValue(start + it).discipline })
        assertEquals(SourceKind.DISCIPLINE_IDLE, p.getValue(start + 2).sources.single().kind)
    }

    @Test
    fun noPenaltyBeforeSinceOrForToday() {
        val input = days(*idle(6))
        // Правила с 4-го дня: штрафы считаются с него, 6-й день — сегодня (не завершён).
        val p = StatsMath.withPenalties(input, start + 3, start + 5).days
        assertTrue(p.values.all { it.strength == 0 && it.discipline == 0 })
    }

    @Test
    fun provisionalDayStopsPenalties() {
        val input = days(*idle(6), provisional = setOf(2))
        val r = StatsMath.withPenalties(input, start, start + 6)
        // Дисциплина: 1-й и 2-й дни бесплатно, 3-й предварительный — дальше не считается.
        assertTrue(r.days.values.all { it.discipline == 0 && it.strength == 0 })
        assertTrue(r.risk.isEmpty())
    }

    @Test
    fun riskShowsTomorrowsPenalty() {
        // 3 дня без силы и дисциплины завершены, сегодня — 4-й и пока пустой.
        val input = days(*idle(4))
        val r = StatsMath.withPenalties(input, start, start + 3)
        assertEquals(-1, r.risk[Stat.STRENGTH])
        assertEquals(-2, r.risk[Stat.DISCIPLINE])
        assertNull(r.risk[Stat.ENDURANCE])
        // Сегодня уже есть очки силы — риска нет.
        val trained = input + ((start + 3) to DayPoints(start + 3, 2, 0, 0, emptyList()))
        assertEquals(0, StatsMath.withPenalties(trained, start, start + 3).risk[Stat.STRENGTH])
    }

    @Test
    fun totalsNeverGoBelowZero() {
        val p = mapOf(
            start to DayPoints(start, 2, 0, 0, emptyList()),
            start + 1 to DayPoints(start + 1, -3, 0, -1, emptyList()),
            start + 2 to DayPoints(start + 2, 4, 1, 0, emptyList()),
        )
        val t = StatsMath.totals(p)
        // Сила: 2 → 0 (не −1) → 4; дисциплина: 0 → 0.
        assertEquals(4L, t[Stat.STRENGTH])
        assertEquals(1L, t[Stat.ENDURANCE])
        assertEquals(0L, t[Stat.DISCIPLINE])
    }

    @Test
    fun textsForPenaltiesAndThresholds() {
        assertEquals(
            "Сила −2 · Выносливость +0 · Дисциплина +3",
            StatsText.dayLine(DayPoints(start, -2, 0, 3, emptyList())).replace(' ', ' '),
        )
        assertEquals("4-й день без тренировки → −1", StatsText.sourceLine(PointSource(SourceKind.STRENGTH_IDLE, 4, -1)))
        assertEquals("планка 0:45 → 0 (меньше минуты)", StatsText.sourceLine(PointSource(SourceKind.PLANK, 45, 0)))
        assertTrue(StatsText.sourceLine(PointSource(SourceKind.REPS, 24, 0)).endsWith("(нет подхода от 10)"))
        assertTrue(StatsText.sourceLine(PointSource(SourceKind.WALK, 4_000, 0)).contains("(меньше 5"))
        assertEquals("Сегодня ещё без очков силы — завтра сила −1", StatsText.riskLine(Stat.STRENGTH, -1))
        assertNull(StatsText.riskLine(Stat.STRENGTH, 0))
        assertNull(StatsText.riskLine(Stat.ENDURANCE, -1))
    }

    @Test
    fun oldDisciplineSurvivesRuleChange() {
        val oldDay = DayPoints(
            start, 3, 4, 7,
            listOf(
                PointSource(SourceKind.REPS, 30, 3),
                PointSource(SourceKind.PHONE_FREE_NIGHT, 1, 4),
                PointSource(SourceKind.LIMIT_KEPT, 50, 3),
            ),
        )
        // Кеш v1 читается только как v1; по новым правилам — нет.
        val cached = StatsMath.encode(oldDay, ruleVersion = 1)
        assertNull(StatsMath.decode(start, cached))
        val old = StatsMath.decode(start, cached, ruleVersion = 1)!!
        // Свежий расчёт: сила пересчитана, дисциплина потеряна (события удалены).
        val fresh = mapOf(start to DayPoints(start, 2, 1, 0, listOf(PointSource(SourceKind.REPS, 30, 2))))
        val merged = StatsMath.keepOldDiscipline(fresh, mapOf(start to old)).getValue(start)
        assertEquals(2, merged.strength)
        assertEquals(1, merged.endurance)
        assertEquals(7, merged.discipline)
        assertEquals(listOf(SourceKind.REPS, SourceKind.PHONE_FREE_NIGHT, SourceKind.LIMIT_KEPT), merged.sources.map { it.kind })
    }

    @Test
    fun guideTextsMatchRules() {
        val strength = StatsGuide.forStat(Stat.STRENGTH, 120)
        assertTrue(
            strength.penalty.replace(' ', ' ').startsWith(
                "3 дня подряд без тренировки — без штрафа. С 4-го дня: −1, на 5-й −2, дальше −3 в день. " +
                    "День хотя бы с одним очком силы обнуляет счёт.",
            ),
        )
        val discipline = StatsGuide.forStat(Stat.DISCIPLINE, 120)
        assertTrue(discipline.penalty.replace(' ', ' ').startsWith("2 дня подряд без очков дисциплины — без штрафа. С 3-го дня: −1"))
        assertTrue(discipline.rules.last().note!!.contains("не больше 96 мин"))
        assertTrue(StatsGuide.forStat(Stat.DISCIPLINE, 0).rules.last().note!!.contains("выключен"))
        assertEquals("Штрафа за простой нет.", StatsGuide.forStat(Stat.ENDURANCE, 120).penalty)
        assertEquals(listOf(0L, 200L, 800L, 2450L, 6050L, 18050L), StatsGuide.ladder().map { it.points })
        assertEquals("Новичок", StatsGuide.ladder().first().title)
        assertFalse(StatsGuide.levelFacts.isEmpty())
    }
}
