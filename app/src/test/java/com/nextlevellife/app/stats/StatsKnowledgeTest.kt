package com.nextlevellife.app.stats

import com.nextlevellife.app.data.CardAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Знание»: очки за карточки, без штрафа за простой, в уровне, в кеше дня (правила 7). */
class StatsKnowledgeTest {
    private val day = 20_000L
    private val nb = ' '

    private fun a(card: String, author: String = "aaa", correct: Boolean = true) =
        CardAttempt("u-$card-$author-$correct", author, card, "mul", day * 86_400_000L, day, "UTC", correct, 0L)

    @Test
    fun threeDistinctCardsGiveOnePointUpToTen() {
        assertEquals(0, StatsMath.knowledge(KnowledgeInput(0)).points)
        assertTrue(StatsMath.knowledge(KnowledgeInput(0)).sources.isEmpty())
        val two = StatsMath.knowledge(KnowledgeInput(2))
        assertEquals(0, two.points)
        assertEquals("2${nb}карточки верно с первого раза → 0 (меньше 3)", StatsText.sourceLine(two.sources.single()))
        assertEquals(1, StatsMath.knowledge(KnowledgeInput(3)).points)
        assertEquals(3, StatsMath.knowledge(KnowledgeInput(11)).points)
        assertEquals(10, StatsMath.knowledge(KnowledgeInput(30)).points)
        val many = StatsMath.knowledge(KnowledgeInput(45))
        assertEquals(10, many.points)
        assertTrue(many.sources.single().capped)
        assertEquals(Stat.KNOWLEDGE, many.sources.single().kind.stat)
    }

    @Test
    fun distinctCorrectFirstAnswersFromBothPhones() {
        val attempts = listOf(
            a("mul:2x2"), a("mul:2x2", author = "bbb"), // одна карточка на двух телефонах — один раз
            a("mul:2x3"), a("mul:2x4", correct = false), a("mul:2x5", author = "bbb"),
        )
        assertEquals(KnowledgeInput(3), StatsMath.knowledgeInput(attempts))
        val p = StatsMath.day(
            day, StrengthInput(0, 0, 0), EnduranceInput(null, 0, 0, 0), DisciplineInput(dayEnded = true),
            k = StatsMath.knowledgeInput(attempts),
        )
        assertEquals(1, p.knowledge)
        assertEquals(1, p.of(Stat.KNOWLEDGE))
        assertEquals(1, p.total)
        assertEquals("Сила$nb+0 · Выносливость$nb+0 · Дисциплина$nb+0 · Знание$nb+1", StatsText.dayLine(p))
        // День без карточек — без «Знание +0».
        assertFalse("Знание" in StatsText.dayLine(DayPoints(day, 1, 0, 0, emptyList())))
    }

    @Test
    fun knowledgeIsNeverPenalizedAndCountsInLevel() {
        // 11 дней без очков: сила, выносливость (шаги известны) и дисциплина штрафуются, знание — нет.
        val days = (0 until 11).associate { i ->
            val d = day + i
            d to DayPoints(d, 0, 0, 0, listOf(PointSource(SourceKind.WALK, 100, 0)))
        }
        val r = StatsMath.withPenalties(days, day, day + 11)
        assertTrue(r.days.values.none { p -> p.sources.any { it.kind.stat == Stat.KNOWLEDGE } })
        assertTrue(r.days.values.all { it.knowledge == 0 })
        assertEquals(0, r.risk[Stat.KNOWLEDGE])
        assertEquals(-1, r.risk[Stat.STRENGTH])
        assertFalse(Stat.KNOWLEDGE.idlePenalized)

        val withCards = mapOf(day to DayPoints(day, 2, 0, 0, emptyList(), knowledge = 5), day + 1 to DayPoints(day + 1, 0, 0, 0, emptyList(), knowledge = 3))
        assertEquals(8L, StatsMath.totals(withCards)[Stat.KNOWLEDGE])
        val summary = StatsSummary(day, 120, 60, 30, emptyMap(), 0, knowledge = 40)
        assertEquals(250L, summary.overall)
        assertEquals(40L, summary.total(Stat.KNOWLEDGE))
    }

    @Test
    fun cacheKeepsKnowledgeAndReadsOldRowsForDiscipline() {
        assertEquals(7, StatsMath.RULE_VERSION)
        val p = DayPoints(
            day, 3, 2, 7,
            listOf(PointSource(SourceKind.REPS, 30, 3), PointSource(SourceKind.PHONE_FREE_NIGHT, 1, 4), PointSource(SourceKind.CARDS, 13, 4)),
            knowledge = 4,
        )
        assertEquals(p, StatsMath.decode(day, StatsMath.encode(p)))
        // Строка версии 6 (без поля знания) по правилам 7 не читается, а для переноса дисциплины — читается.
        val v6 = "6|3|2|7|REPS:30:3:3;PHONE_FREE_NIGHT:1:4:4"
        assertEquals(null, StatsMath.decode(day, v6))
        val old = StatsMath.decode(day, v6, ruleVersion = 6)
        assertNotNull(old)
        assertEquals(7, old!!.discipline)
        assertEquals(0, old.knowledge)
        // Пересчёт: знание — свежее, дисциплина — из прежнего кеша.
        val fresh = mapOf(day to DayPoints(day, 3, 2, 0, listOf(PointSource(SourceKind.CARDS, 6, 2)), knowledge = 2))
        val merged = StatsMath.keepOldDiscipline(fresh, mapOf(day to old)).getValue(day)
        assertEquals(7, merged.discipline)
        assertEquals(2, merged.knowledge)
        assertEquals(listOf(SourceKind.CARDS, SourceKind.PHONE_FREE_NIGHT), merged.sources.map { it.kind })
        // Метка «пересчитать» (invalidateDays) — та же строка с прежней версией: тоже читается для дисциплины.
        val marked = StatsMath.encode(p, StatsMath.RULE_VERSION - 1)
        assertEquals(null, StatsMath.decode(day, marked))
        assertEquals(7, StatsMath.decode(day, marked, ruleVersion = StatsMath.RULE_VERSION - 1)!!.discipline)
    }

    @Test
    fun guideAndTexts() {
        val g = StatsGuide.forStat(Stat.KNOWLEDGE, 120)
        assertEquals("Карточки: слова и счёт", g.subtitle)
        assertTrue(g.rules.single().what.contains("3"))
        assertFalse(g.penalty.contains("−"))
        assertTrue(g.limits.any { "5 мин" in it.replace(nb, ' ') })
        assertEquals("без очков знания", StatsText.idleWhat(Stat.KNOWLEDGE))
        assertEquals(null, StatsText.riskLine(Stat.KNOWLEDGE, 0))
        assertTrue(StatsGuide.levelFacts.first().contains("знания"))
    }
}
