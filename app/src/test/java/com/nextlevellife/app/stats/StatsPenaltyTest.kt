package com.nextlevellife.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пороги силы (полный подход), штрафы за простой, итоги без минуса и тексты «Как получить». */
class StatsPenaltyTest {
    private val start = 20_000L

    /**
     * Дни подряд с [start]: в каждом — сила, выносливость, дисциплина. [steps] — шаги ходьбы дня
     * (null — данных о шагах нет): для штрафа выносливости важно, известны ли шаги.
     */
    private fun days(
        vararg pts: Triple<Int, Int, Int>,
        provisional: Set<Int> = emptySet(),
        steps: Long? = 3_000,
    ): Map<Long, DayPoints> =
        pts.mapIndexed { i, (s, e, d) ->
            val day = start + i
            val src = if (steps != null) listOf(PointSource(SourceKind.WALK, steps, e)) else emptyList()
            day to DayPoints(day, s, e, d, src, provisional = i in provisional)
        }.toMap()

    private fun idle(n: Int) = Array(n) { Triple(0, 0, 0) }

    private fun Map<Long, DayPoints>.column(stat: Stat, n: Int) = (0 until n).map { getValue(start + it).of(stat) }

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
    fun penaltySchedule() {
        // 3 дня без штрафа, 4-й — −1, и так по кругу: 4, 8, 12. Штраф всегда −1.
        assertEquals(
            listOf(0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1),
            (1..12).map { StatsMath.idlePenalty(it) },
        )
        assertEquals(0, StatsMath.idlePenalty(0))
        // За 12 дней простоя — ровно −3, а не больше.
        assertEquals(-3, (1..12).sumOf { StatsMath.idlePenalty(it) })
    }

    @Test
    fun strengthLosesOnePointPerThreeIdleDaysAndResets() {
        // Тренировка, 7 дней без силы, тренировка, 2 дня без. Дисциплина и выносливость всё время есть.
        val input = days(
            Triple(5, 2, 3),
            Triple(0, 2, 3), Triple(0, 2, 3), Triple(0, 2, 3), Triple(0, 2, 3),
            Triple(0, 2, 3), Triple(0, 2, 3), Triple(0, 2, 3),
            Triple(4, 2, 3),
            Triple(0, 2, 3), Triple(0, 2, 3),
        )
        val n = input.size
        val p = StatsMath.withPenalties(input, start, start + n).days
        // Простой: дни 1–7 → штраф только на 4-й; после тренировки счёт заново — 2 дня без штрафа.
        assertEquals(listOf(5, 0, 0, 0, -1, 0, 0, 0, 4, 0, 0), p.column(Stat.STRENGTH, n))
        val src = p.getValue(start + 4).sources.last()
        assertEquals(SourceKind.STRENGTH_IDLE, src.kind)
        assertEquals(4L, src.amount)
        assertEquals(-1, src.points)
        assertTrue(p.values.all { it.endurance == 2 && it.discipline == 3 })
    }

    @Test
    fun disciplineSameRule() {
        val input = days(*Array(8) { Triple(3, 1, 0) })
        val p = StatsMath.withPenalties(input, start, start + 8).days
        assertEquals(listOf(0, 0, 0, -1, 0, 0, 0, -1), p.column(Stat.DISCIPLINE, 8))
        assertEquals(SourceKind.DISCIPLINE_IDLE, p.getValue(start + 3).sources.last().kind)
    }

    @Test
    fun enduranceIdleOnlyWhenStepsKnown() {
        // Шаги известны, но меньше 5 000 (0 очков) — простой: −1 на 4-й день.
        val known = StatsMath.withPenalties(days(*Array(4) { Triple(3, 0, 3) }), start, start + 4).days
        assertEquals(listOf(0, 0, 0, -1), known.column(Stat.ENDURANCE, 4))
        assertEquals(SourceKind.ENDURANCE_IDLE, known.getValue(start + 3).sources.last().kind)
        // Health Connect не подключён (шагов нет) — дни не считаются, штрафа нет.
        val unknown = StatsMath.withPenalties(days(*Array(6) { Triple(3, 0, 3) }, steps = null), start, start + 6)
        assertTrue(unknown.days.values.all { it.endurance == 0 })
        assertEquals(0, unknown.risk[Stat.ENDURANCE])
    }

    /** День из настоящего расчёта: только шаги ([steps], из них [runSteps] — бег), без силы и дисциплины. */
    private fun realDay(day: Long, steps: Long?, runSteps: Long = 0): DayPoints {
        val e = StatsMath.endurance(EnduranceInput(steps, runSteps, 0, 0))
        return DayPoints(day, 0, e.points, 0, e.sources)
    }

    @Test
    fun enduranceIdleFromRealInputs() {
        // 0 шагов, все шаги — бег (без километра), 4 000 и 4 999 шагов: шаги известны, очков нет — 4 дня простоя.
        val input = mapOf(
            start to realDay(start, 0),
            start + 1 to realDay(start + 1, 2_000, runSteps = 2_000),
            start + 2 to realDay(start + 2, 4_000),
            start + 3 to realDay(start + 3, 4_999),
        )
        assertTrue(input.values.all { StatsMath.idle(it, Stat.ENDURANCE) == true })
        val p = StatsMath.withPenalties(input, start, start + 4).days
        assertEquals(listOf(0, 0, 0, -1), p.column(Stat.ENDURANCE, 4))
        // Шагов нет совсем — день неизвестен.
        assertNull(StatsMath.idle(realDay(start, null), Stat.ENDURANCE))
    }

    @Test
    fun noEnduranceRiskWhileTodayStepsUnknown() {
        // Три известных дня простоя, сегодня шаги ещё не пришли — такой день сбросит счёт, риска нет.
        val input = mapOf(
            start to realDay(start, 1_000),
            start + 1 to realDay(start + 1, 1_000),
            start + 2 to realDay(start + 2, 1_000),
            start + 3 to realDay(start + 3, null),
        )
        assertEquals(0, StatsMath.withPenalties(input, start, start + 3).risk[Stat.ENDURANCE])
        // Шаги пришли, но меньше 5 000 — риск есть.
        val synced = input + ((start + 3) to realDay(start + 3, 1_500))
        assertEquals(-1, StatsMath.withPenalties(synced, start, start + 3).risk[Stat.ENDURANCE])
    }

    @Test
    fun noPenaltyBeforeSinceOrForToday() {
        val input = days(*idle(6))
        // Правила с 4-го дня: штрафы считаются с него, 6-й день — сегодня (не завершён): 2 завершённых дня.
        val p = StatsMath.withPenalties(input, start + 3, start + 5).days
        assertTrue(p.values.all { it.strength == 0 && it.discipline == 0 && it.endurance == 0 })
    }

    @Test
    fun provisionalDayStopsPenalties() {
        val input = days(*idle(6), provisional = setOf(2))
        val r = StatsMath.withPenalties(input, start, start + 6)
        // 3-й день предварительный — дальше не считается, штрафов нет.
        assertTrue(r.days.values.all { it.discipline == 0 && it.strength == 0 && it.endurance == 0 })
        assertTrue(r.risk.isEmpty())
    }

    @Test
    fun riskShowsPenaltyForToday() {
        // 2 дня простоя завершены, сегодня — 3-й: штрафа сегодня не будет.
        val three = StatsMath.withPenalties(days(*idle(3)), start, start + 2)
        assertTrue(Stat.entries.all { three.risk[it] == 0 })
        // Сегодня 4-й день простоя — риск у всех трёх; с очками силы — у силы нет.
        val four = days(*idle(4))
        val r = StatsMath.withPenalties(four, start, start + 3)
        assertTrue(Stat.entries.filter { it.idlePenalized }.all { r.risk[it] == -1 })
        assertEquals(0, r.risk[Stat.KNOWLEDGE] ?: 0)
        val trained = four + ((start + 3) to DayPoints(start + 3, 2, 0, 0, listOf(PointSource(SourceKind.WALK, 3_000, 0))))
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
    fun oldDisciplineSurvivesRuleChange() {
        val oldDay = DayPoints(
            start, 3, 4, 7,
            listOf(
                PointSource(SourceKind.REPS, 30, 3),
                PointSource(SourceKind.PHONE_FREE_NIGHT, 1, 4),
                PointSource(SourceKind.LIMIT_KEPT, 50, 3),
            ),
        )
        // Кеш прежних версий по действующим правилам не читается.
        assertNull(StatsMath.decode(start, StatsMath.encode(oldDay, ruleVersion = StatsMath.RULE_VERSION - 1)))
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

    private fun plain(s: String) = s.replace(' ', ' ')

    @Test
    fun textsForPenaltiesAndThresholds() {
        assertEquals("Сила −2 · Выносливость +0 · Дисциплина +3", plain(StatsText.dayLine(DayPoints(start, -2, 0, 3, emptyList()))))
        assertEquals("3 дня подряд без очков силы → −1", plain(StatsText.sourceLine(PointSource(SourceKind.STRENGTH_IDLE, 3, -1))))
        assertEquals("6 дней подряд без очков выносливости → −1", plain(StatsText.sourceLine(PointSource(SourceKind.ENDURANCE_IDLE, 6, -1))))
        assertEquals("планка 0:45 → 0 (меньше минуты)", StatsText.sourceLine(PointSource(SourceKind.PLANK, 45, 0)))
        assertTrue(StatsText.sourceLine(PointSource(SourceKind.REPS, 24, 0)).endsWith("(нет подхода от 10)"))
        assertTrue(plain(StatsText.sourceLine(PointSource(SourceKind.WALK, 4_000, 0))).contains("(меньше 5 000)"))
        assertEquals("Если сегодня без очков силы — сила −1", plain(StatsText.riskLine(Stat.STRENGTH, -1)!!))
        assertEquals("Если сегодня без очков выносливости — выносливость −1", plain(StatsText.riskLine(Stat.ENDURANCE, -1)!!))
        assertNull(StatsText.riskLine(Stat.STRENGTH, 0))
    }

    @Test
    fun guideTextsMatchRules() {
        assertEquals(
            "3 дня подряд без очков силы — без штрафа. Если и 4-й день такой же — минус 1 очко. " +
                "Потом снова 3 дня без штрафа и минус 1 очко на 4-й, и так по кругу. Штраф всегда 1 очко и не растёт. " +
                "День хотя бы с одним очком силы обнуляет счёт. Ниже нуля очки не опускаются.",
            plain(StatsGuide.forStat(Stat.STRENGTH, 120).penalty),
        )
        val endurance = plain(StatsGuide.forStat(Stat.ENDURANCE, 120).penalty)
        assertTrue(endurance.startsWith("3 дня подряд без очков выносливости — без штрафа."))
        assertTrue(endurance.contains("от 5 000 шагов, 1 км бега или 5 минут комплекса"))
        val discipline = StatsGuide.forStat(Stat.DISCIPLINE, 120)
        assertTrue(plain(discipline.penalty).startsWith("3 дня подряд без очков дисциплины — без штрафа."))
        // Ни в одном тексте штрафа нет «−» рядом с номером дня, который можно прочитать как «−10».
        for (stat in Stat.entries) assertFalse(plain(StatsGuide.forStat(stat, 120).penalty).contains("−"))
        assertTrue(discipline.rules.last().note!!.contains("не больше 96 мин"))
        assertTrue(StatsGuide.forStat(Stat.DISCIPLINE, 0).rules.last().note!!.contains("выключен"))
        assertEquals(listOf(0L, 200L, 800L, 2450L, 6050L, 18050L), StatsGuide.ladder().map { it.points })
        assertEquals("Новичок", StatsGuide.ladder().first().title)
        assertFalse(StatsGuide.levelFacts.isEmpty())
    }
}
